-- 音乐版权使用归属与版税结算服务 初始结构
-- 所有金额以 BIGINT 存储最小货币单位（如美分）；份额以 分子/分母 有理数存储

-- 权利人（收款人）
CREATE TABLE payee (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payee_code       VARCHAR(64)  NOT NULL UNIQUE,
    payee_name       VARCHAR(255) NOT NULL,
    payee_type       VARCHAR(16)  NOT NULL CHECK (payee_type IN ('COMPANY','WRITER','PERFORMER','OTHER')),
    bank_account     VARCHAR(128),
    account_currency CHAR(3)      NOT NULL DEFAULT 'USD',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 词曲作品
CREATE TABLE work (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_code   VARCHAR(64)  NOT NULL UNIQUE,
    title       VARCHAR(255) NOT NULL,
    iswc        VARCHAR(32),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE work_contributor (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_id          BIGINT NOT NULL REFERENCES work(id),
    payee_id         BIGINT REFERENCES payee(id),
    contributor_name VARCHAR(255) NOT NULL,
    role             VARCHAR(32)  NOT NULL CHECK (role IN ('COMPOSER','LYRICIST','ARRANGER','ADAPTOR')),
    UNIQUE (work_id, contributor_name, role)
);

-- 录音版本（同一作品可有多个录音；recording_code 即明确录音标识，类 ISRC）
CREATE TABLE sound_recording (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_code     VARCHAR(64)  NOT NULL UNIQUE,
    work_id            BIGINT NOT NULL REFERENCES work(id),
    version_title      VARCHAR(255) NOT NULL,
    duration_seconds   INTEGER,
    owner_payee_id     BIGINT REFERENCES payee(id),
    first_release_date DATE,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','WITHDRAWN')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_recording_work ON sound_recording(work_id);

-- 平台
CREATE TABLE platform (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_code VARCHAR(64)  NOT NULL UNIQUE,
    platform_name VARCHAR(255) NOT NULL,
    home_currency CHAR(3)      NOT NULL DEFAULT 'USD',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 平台内部编号 -> 版权目录录音 的人工审核映射（归属的唯一确定性依据）
CREATE TABLE platform_mapping (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_id            BIGINT NOT NULL REFERENCES platform(id),
    platform_item_code     VARCHAR(128) NOT NULL,
    recording_id           BIGINT REFERENCES sound_recording(id),
    status                 VARCHAR(16)  NOT NULL CHECK (status IN ('PROPOSED','APPROVED','REVOKED')),
    adopted_candidate_id   BIGINT,
    approved_by            VARCHAR(64),
    approved_at            TIMESTAMPTZ,
    revoked_at             TIMESTAMPTZ,
    revoke_reason          VARCHAR(255),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 同一平台编号同一时刻至多一条有效（非撤回）映射
CREATE UNIQUE INDEX uq_mapping_active ON platform_mapping(platform_id, platform_item_code)
    WHERE status <> 'REVOKED';

CREATE TABLE platform_mapping_history (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mapping_id   BIGINT NOT NULL REFERENCES platform_mapping(id),
    old_status   VARCHAR(16),
    new_status   VARCHAR(16) NOT NULL,
    recording_id BIGINT,
    reason       VARCHAR(255),
    actor        VARCHAR(64),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 合同（限定权利类型/地区/媒介/有效区间；出版权挂作品，录音权挂录音）
CREATE TABLE contract (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_code VARCHAR(64) NOT NULL UNIQUE,
    work_id      BIGINT REFERENCES work(id),
    recording_id BIGINT REFERENCES sound_recording(id),
    right_type   VARCHAR(16) NOT NULL CHECK (right_type IN ('PUBLISHING','RECORDING')),
    territory    VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    media        VARCHAR(32) NOT NULL DEFAULT 'ALL' CHECK (media IN ('STREAM','DOWNLOAD','BROADCAST','ALL')),
    valid_from   DATE NOT NULL,
    valid_to     DATE,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED','TERMINATED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_contract_dimension CHECK (
        (right_type = 'PUBLISHING' AND work_id IS NOT NULL) OR
        (right_type = 'RECORDING' AND recording_id IS NOT NULL)
    ),
    CONSTRAINT chk_contract_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX idx_contract_recording ON contract(recording_id, right_type, status);
CREATE INDEX idx_contract_work ON contract(work_id, right_type, status);

CREATE TABLE contract_share (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id      BIGINT NOT NULL REFERENCES contract(id),
    payee_id         BIGINT NOT NULL REFERENCES payee(id),
    role             VARCHAR(32),
    share_numerator   BIGINT NOT NULL CHECK (share_numerator > 0),
    share_denominator BIGINT NOT NULL CHECK (share_denominator > 0 AND share_numerator <= share_denominator),
    UNIQUE (contract_id, payee_id, role)
);

-- 平台协议（按次单价 / 直接报告收入 两种结算方式，及平台费用条款）
CREATE TABLE platform_agreement (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_id       BIGINT NOT NULL REFERENCES platform(id),
    right_type        VARCHAR(16) NOT NULL CHECK (right_type IN ('PUBLISHING','RECORDING')),
    territory         VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    media             VARCHAR(32) NOT NULL DEFAULT 'ALL' CHECK (media IN ('STREAM','DOWNLOAD','BROADCAST','ALL')),
    settlement_method VARCHAR(16) NOT NULL CHECK (settlement_method IN ('PER_UNIT','GROSS_REPORT')),
    rate_minor        NUMERIC(18,6),
    currency          CHAR(3) NOT NULL DEFAULT 'USD',
    fee_basis         VARCHAR(24) NOT NULL DEFAULT 'NONE' CHECK (fee_basis IN ('NONE','PERCENT_OF_GROSS','FIXED')),
    fee_numerator     BIGINT NOT NULL DEFAULT 0,
    fee_denominator   BIGINT NOT NULL DEFAULT 1,
    -- GROSS_REPORT 方式下该权利类型占报告净额的预算份额（不同权利类型预算分列）
    budget_numerator   BIGINT NOT NULL DEFAULT 1,
    budget_denominator BIGINT NOT NULL DEFAULT 1,
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','TERMINATED')),
    CONSTRAINT chk_agreement_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT chk_per_unit_rate CHECK (settlement_method <> 'PER_UNIT' OR rate_minor IS NOT NULL)
);
CREATE INDEX idx_agreement_lookup ON platform_agreement(platform_id, right_type, territory, media, status);

-- 平台上报批次（带原始声明合计）
CREATE TABLE report_batch (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_ref            VARCHAR(128) NOT NULL UNIQUE,
    platform_id          BIGINT NOT NULL REFERENCES platform(id),
    period_start         DATE NOT NULL,
    period_end           DATE NOT NULL,
    source_file          VARCHAR(255),
    declared_total_count BIGINT,
    declared_gross_minor BIGINT,
    declared_fee_minor   BIGINT,
    declared_net_minor   BIGINT,
    currency             CHAR(3) NOT NULL DEFAULT 'USD',
    status               VARCHAR(16) NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED','RECONCILING','ACCEPTED','SUPERSEDED')),
    parse_valid_count    INTEGER NOT NULL DEFAULT 0,
    parse_bad_count      INTEGER NOT NULL DEFAULT 0,
    parse_dup_count      INTEGER NOT NULL DEFAULT 0,
    reconciliation       VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (reconciliation IN ('PENDING','MATCHED','MISMATCH')),
    note                 VARCHAR(512),
    received_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 平台上报明细行（平台原行编号 + 修订号辨识重传/晚到更正；撤销以 REVOKED 标记）
CREATE TABLE report_line (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id            BIGINT NOT NULL REFERENCES report_batch(id),
    platform_line_no    VARCHAR(64) NOT NULL,
    revision_no         INTEGER NOT NULL DEFAULT 1,
    supersedes_line_id  BIGINT REFERENCES report_line(id),
    platform_item_code  VARCHAR(128) NOT NULL,
    reported_title      VARCHAR(255),
    reported_artist     VARCHAR(255),
    reported_duration   INTEGER,
    usage_date          DATE,
    territory           VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    media               VARCHAR(32) NOT NULL CHECK (media IN ('STREAM','DOWNLOAD','BROADCAST')),
    usage_count         BIGINT,
    gross_minor         BIGINT,
    fee_minor           BIGINT,
    net_minor           BIGINT,
    currency            CHAR(3) NOT NULL DEFAULT 'USD',
    raw_text            TEXT,
    parse_status        VARCHAR(8) NOT NULL CHECK (parse_status IN ('VALID','BAD','DUP')),
    reject_reason       VARCHAR(255),
    usage_status        VARCHAR(16) NOT NULL DEFAULT 'NEW'
        CHECK (usage_status IN ('NEW','SUPERSEDED','REVOKED')),
    superseded_by_line_id BIGINT REFERENCES report_line(id),
    UNIQUE (batch_id, platform_line_no, revision_no)
);
CREATE INDEX idx_rline_item ON report_line(platform_item_code, usage_date);
CREATE INDEX idx_rline_batch ON report_line(batch_id, parse_status, usage_status);

-- 候选关系：标题/作者/时长仅产生候选，不能自动归属
CREATE TABLE mapping_candidate (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_line_id        BIGINT NOT NULL REFERENCES report_line(id),
    recording_id          BIGINT NOT NULL REFERENCES sound_recording(id),
    score                 NUMERIC(6,3) NOT NULL,
    title_similarity      NUMERIC(6,3),
    contributor_match     BOOLEAN NOT NULL DEFAULT FALSE,
    duration_diff_seconds INTEGER,
    basis                 VARCHAR(32) NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'PROPOSED'
        CHECK (status IN ('PROPOSED','ADOPTED','REJECTED')),
    decided_at            TIMESTAMPTZ,
    UNIQUE (report_line_id, recording_id)
);

-- 结算范围锁：同一结算范围不可被两个执行者重复出账
CREATE TABLE settlement_lock (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_key            VARCHAR(255) NOT NULL UNIQUE,
    settlement_batch_id  BIGINT,
    locked_by            VARCHAR(64) NOT NULL,
    locked_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 结算批次（固定合同/目录/原始明细范围；差额单批次用 ADJUSTMENT 类型）
CREATE TABLE settlement_batch (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_no               VARCHAR(64) NOT NULL UNIQUE,
    batch_type             VARCHAR(16) NOT NULL DEFAULT 'USAGE'
        CHECK (batch_type IN ('USAGE','ADJUSTMENT')),
    platform_id            BIGINT REFERENCES platform(id),
    period_start           DATE NOT NULL,
    period_end             DATE NOT NULL,
    status                 VARCHAR(24) NOT NULL CHECK (status IN
        ('CREATED','CALCULATED','IN_REVIEW','APPROVED','PAYMENT_CREATED','CLOSED')),
    contract_snapshot_fingerprint VARCHAR(128),
    report_scope           VARCHAR(512),
    locked_by              VARCHAR(64),
    approved_by            VARCHAR(64),
    approved_at            TIMESTAMPTZ,
    note                   VARCHAR(512),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    calculated_at          TIMESTAMPTZ
);

-- 逐笔使用的计算结果（每条使用 × 权利类型一行；预算分列，互不挤占）
CREATE TABLE settlement_line (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_batch_id   BIGINT NOT NULL REFERENCES settlement_batch(id),
    report_line_id        BIGINT NOT NULL REFERENCES report_line(id),
    right_type            VARCHAR(16) NOT NULL CHECK (right_type IN
        ('PUBLISHING','RECORDING','UNBUDGETED')),
    recording_id          BIGINT REFERENCES sound_recording(id),
    work_id               BIGINT REFERENCES work(id),
    usage_date            DATE NOT NULL,
    territory             VARCHAR(32) NOT NULL,
    media                 VARCHAR(32) NOT NULL,
    allocation_scope      VARCHAR(24) NOT NULL
        CHECK (allocation_scope IN ('FULL','RECORDING_BUDGET','PUBLISHING_BUDGET')),
    distributable_minor   BIGINT NOT NULL CHECK (distributable_minor >= 0),
    fee_minor             BIGINT NOT NULL DEFAULT 0,
    basis                 VARCHAR(32) NOT NULL
        CHECK (basis IN ('PER_UNIT_RATE','REPORTED_NET','REPORTED_GROSS_LESS_FEE','UNDETERMINED')),
    rate_used             NUMERIC(18,6),
    chosen_contract_ids   VARCHAR(255),
    conflict_flag         BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_detail       VARCHAR(512),
    status                VARCHAR(20) NOT NULL CHECK (status IN
        ('CALCULATED','REVIEW_HOLD','APPROVED','PAID','PARTIALLY_PAID','ADJUSTED')),
    hold_reason           VARCHAR(255),
    source_fingerprint    VARCHAR(128) NOT NULL,
    segment_no            INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (settlement_batch_id, report_line_id, right_type)
);
CREATE INDEX idx_sline_status ON settlement_line(settlement_batch_id, status);

-- 分摊明细：有理数份额 + 最大余数法后的实际金额；各行金额之和必须等于可分配额
CREATE TABLE settlement_allocation (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_line_id BIGINT NOT NULL REFERENCES settlement_line(id),
    payee_id           BIGINT REFERENCES payee(id),
    contract_id        BIGINT REFERENCES contract(id),
    alloc_status       VARCHAR(16) NOT NULL CHECK (alloc_status IN ('ALLOCATED','UNCLAIMED','HELD')),
    share_numerator    BIGINT NOT NULL,
    share_denominator  BIGINT NOT NULL,
    amount_minor       BIGINT NOT NULL CHECK (amount_minor >= 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alloc_line ON settlement_allocation(settlement_line_id);
CREATE INDEX idx_alloc_payee ON settlement_allocation(payee_id);

-- 待归属/暂扣/未认领余额台账（各自保留余额，不猜收款人）
CREATE TABLE hold_balance (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_batch_id BIGINT NOT NULL REFERENCES settlement_batch(id),
    report_line_id      BIGINT REFERENCES report_line(id),
    right_type          VARCHAR(16),
    balance_type        VARCHAR(20) NOT NULL
        CHECK (balance_type IN ('UNMATCHED','CONFLICT_HOLD','WITHHOLD','UNCLAIMED')),
    amount_minor        BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency            CHAR(3) NOT NULL,
    reason              VARCHAR(512),
    resolved            BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_note     VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 应付（按批次/权利人/权利类型/币种汇总，支持部分付款）
CREATE TABLE payable (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_batch_id BIGINT NOT NULL REFERENCES settlement_batch(id),
    payee_id            BIGINT NOT NULL REFERENCES payee(id),
    right_type          VARCHAR(16) NOT NULL,
    currency            CHAR(3) NOT NULL,
    amount_minor        BIGINT NOT NULL,
    paid_minor          BIGINT NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL CHECK (status IN
        ('CALCULATED','APPROVED','QUEUED','PARTIALLY_PAID','PAID','FAILED')),
    UNIQUE (settlement_batch_id, payee_id, right_type, currency)
);

-- 付款批次与文件（文件内容在生成时固定，账户后改不影响重试）
CREATE TABLE payment_run (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_no              VARCHAR(64) NOT NULL UNIQUE,
    settlement_batch_id BIGINT NOT NULL REFERENCES settlement_batch(id),
    currency            CHAR(3) NOT NULL,
    status              VARCHAR(24) NOT NULL CHECK (status IN
        ('GENERATED','CONFIRMED','PARTIALLY_CONFIRMED','FAILED')),
    created_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payment_request (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_run_id    BIGINT NOT NULL REFERENCES payment_run(id),
    payable_id        BIGINT NOT NULL REFERENCES payable(id),
    payee_id          BIGINT NOT NULL REFERENCES payee(id),
    request_id        VARCHAR(128) NOT NULL UNIQUE,
    account_snapshot  VARCHAR(255) NOT NULL,
    body_fingerprint  VARCHAR(128) NOT NULL,
    amount_minor      BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN
        ('ISSUED','TIMEOUT_QUERIED','CONFIRMED','REJECTED','FAILED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at      TIMESTAMPTZ
);

CREATE TABLE payment_file (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_run_id      BIGINT NOT NULL REFERENCES payment_run(id),
    file_name           VARCHAR(255) NOT NULL,
    file_path           VARCHAR(255) NOT NULL,
    content_fingerprint VARCHAR(128) NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 模拟银行回执（不访问真实银行；金额+币种一致才能记已付，旧账户按生成时快照核对）
CREATE TABLE payment_receipt (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_ref      VARCHAR(128) NOT NULL UNIQUE,
    request_id_ref   VARCHAR(128) NOT NULL,
    account_ref      VARCHAR(255),
    amount_minor     BIGINT NOT NULL,
    currency         CHAR(3) NOT NULL,
    source_file      VARCHAR(255),
    match_status     VARCHAR(24) NOT NULL CHECK (match_status IN
        ('MATCHED','MISMATCH_AMOUNT','MISMATCH_CURRENCY','MISMATCH_ACCOUNT','UNKNOWN_REQUEST','DUPLICATE')),
    mismatch_reason  VARCHAR(255),
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_receipt_request ON payment_receipt(request_id_ref);

-- 差额单：已批准季度的权利更正，不直接改旧款
CREATE TABLE adjustment_order (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    adjustment_no             VARCHAR(64) NOT NULL UNIQUE,
    source_settlement_batch_id BIGINT NOT NULL REFERENCES settlement_batch(id),
    new_settlement_batch_id   BIGINT REFERENCES settlement_batch(id),
    reason                    VARCHAR(255) NOT NULL,
    status                    VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','APPROVED','SETTLED')),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at               TIMESTAMPTZ
);

CREATE TABLE adjustment_line (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    adjustment_order_id BIGINT NOT NULL REFERENCES adjustment_order(id),
    payee_id            BIGINT REFERENCES payee(id),
    right_type          VARCHAR(16) NOT NULL,
    amount_minor        BIGINT NOT NULL,
    prior_contract_id   BIGINT REFERENCES contract(id),
    new_contract_id     BIGINT REFERENCES contract(id),
    reason              VARCHAR(255) NOT NULL
);
