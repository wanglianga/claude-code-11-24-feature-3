-- 争议版税托管与裁决执行
-- 设计要点：
--  1) 争议以 录音+权利类别 限定，再声明地区/使用时段/比例；与结算分摊行求交得到实际冻结范围，
--     不按权利目录批量解冻，也不冻结整首录音的无争议权利；
--  2) 重叠争议先求并集，同一最小冻结单元在所有现行争议下只冻结一次（escrow_freeze 唯一约束 +
--     escrow_freeze_cover 记录各争议独立主张）；
--  3) 托管账户是独立台账：不可变分录记录每笔进入/释放/转付/追回到账，余额只由分录驱动，
--     不随权利目录变化自动换所有者；
--  4) 裁决只作用于其声明的子范围；执行只产生托管待付（payable），执行前预览可释放、应转付、
--     仍被其他争议覆盖的部分；回执确认资金才真正离开托管；
--  5) 裁决撤回不删除已发生的释放/转付分录：未付暂停并退回冻结，已付列回收差额（clawback_item）；
--  6) 托管付款文件与裁决绑定，回执未确认前资金仍占待付额，防止另一路解冻重复支付；
--  7) 恒等式：托管入账 = 可支配余额 + 待付 + 已释放。

-- ---------- 争议案件（法务声明：录音 + 权利类别 + 地区集合 + 时段 + 比例） ----------
CREATE TABLE dispute_case (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_no         VARCHAR(64) NOT NULL UNIQUE,
    recording_id       BIGINT NOT NULL REFERENCES sound_recording(id),
    right_type         VARCHAR(16) NOT NULL CHECK (right_type IN ('PUBLISHING','RECORDING')),
    territories        VARCHAR(512) NOT NULL,           -- 逗号分隔地区码；GLOBAL 表示全部地区
    period_start       DATE NOT NULL,
    period_end         DATE NOT NULL,
    ratio_numerator    BIGINT NOT NULL CHECK (ratio_numerator > 0),
    ratio_denominator  BIGINT NOT NULL CHECK (ratio_denominator > 0 AND ratio_numerator <= ratio_denominator),
    claimant_payee_id  BIGINT REFERENCES payee(id),
    respondent_payee_id BIGINT REFERENCES payee(id),
    legal_ref          VARCHAR(255),
    reason             VARCHAR(512) NOT NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','WITHDRAWN')),
    withdrawn_at       TIMESTAMPTZ,
    withdraw_reason    VARCHAR(512),
    created_by         VARCHAR(64) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_dispute_dates CHECK (period_end >= period_start)
);
CREATE INDEX idx_dispute_scope ON dispute_case(recording_id, right_type, status);

-- ---------- 托管账户（按币种独立；台账余额只由分录驱动） ----------
CREATE TABLE escrow_account (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_no    VARCHAR(64) NOT NULL UNIQUE,
    currency      CHAR(3) NOT NULL,
    title         VARCHAR(255) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 账户与争议多对多：一个账户可承接多个重叠争议的冻结，一笔冻结也可被多个争议覆盖
CREATE TABLE escrow_dispute_link (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id       BIGINT NOT NULL REFERENCES escrow_account(id),
    dispute_id       BIGINT NOT NULL REFERENCES dispute_case(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, dispute_id)
);

-- ---------- 最小冻结单元 ----------
-- 统一锚定结算分摊行（settlement_allocation）：
-- ALLOCATED 行是有具体权利人的应付份额；HELD/UNCLAIMED 行是无主挂账在分摊侧的镜像，
-- hold_balance_id 仅在可对应到挂账台账行时记录（用于挂账侧冻结留痕）。
-- 同一分摊行全系统至多一条冻结，重叠争议靠 escrow_freeze_cover 求并集，绝不同一份额冻两次。
CREATE TABLE escrow_freeze (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id                  BIGINT NOT NULL REFERENCES escrow_account(id),
    settlement_allocation_id    BIGINT NOT NULL REFERENCES settlement_allocation(id),
    hold_balance_id             BIGINT REFERENCES hold_balance(id),
    settlement_batch_id         BIGINT NOT NULL REFERENCES settlement_batch(id),
    report_line_id              BIGINT NOT NULL REFERENCES report_line(id),
    recording_id                BIGINT NOT NULL REFERENCES sound_recording(id),
    right_type                  VARCHAR(16) NOT NULL,
    payee_id                    BIGINT REFERENCES payee(id),
    territory                   VARCHAR(32) NOT NULL,
    usage_date                  DATE NOT NULL,
    currency                    CHAR(3) NOT NULL,
    original_amount_minor       BIGINT NOT NULL CHECK (original_amount_minor >= 0),  -- 来源行原始金额
    frozen_amount_minor         BIGINT NOT NULL CHECK (frozen_amount_minor >= 0),    -- 累计实际冻结（并集后）
    dispute_ids                 VARCHAR(512) NOT NULL,        -- 覆盖该冻结的现行争议 id 并集（升序逗号）
    status                      VARCHAR(16) NOT NULL DEFAULT 'FROZEN'
        CHECK (status IN ('FROZEN','PARTIALLY_RELEASED','RELEASED','TRANSFERRED')),
    released_minor              BIGINT NOT NULL DEFAULT 0,
    transferred_minor           BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_freeze_amount CHECK (released_minor + transferred_minor <= frozen_amount_minor)
);
-- 同一分摊行在系统内至多一条有效冻结（重叠争议走并集更新，不新增冻结）
CREATE UNIQUE INDEX uq_freeze_alloc ON escrow_freeze(settlement_allocation_id);
CREATE INDEX idx_freeze_hold ON escrow_freeze(hold_balance_id);
CREATE INDEX idx_freeze_scope ON escrow_freeze(recording_id, right_type, status);
CREATE INDEX idx_freeze_account ON escrow_freeze(account_id, status);

-- 每个争议对每条冻结实际覆盖的金额：冻结增量归属于触发它的争议，
-- 各争议覆盖额之和恰等于冻结额，裁决时据此判断可处置部分与仍被其他争议覆盖的部分
CREATE TABLE escrow_freeze_cover (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    escrow_freeze_id BIGINT NOT NULL REFERENCES escrow_freeze(id),
    dispute_id      BIGINT NOT NULL REFERENCES dispute_case(id),
    covered_minor   BIGINT NOT NULL CHECK (covered_minor >= 0),
    consumed_minor  BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (escrow_freeze_id, dispute_id)
);
CREATE INDEX idx_cover_dispute ON escrow_freeze_cover(dispute_id);

-- ---------- 托管台账分录（不可变；进入/释放/转付/追回入账全留痕） ----------
-- 恒等式（按账户）：托管入账 = 可支配余额 + 待付 + 已释放
--   裁决执行只产生托管待付（payable），不写分录；付款回执确认资金才真正离开托管（RELEASE/TRANSFER_OUT）。
--   付款文件已生成但回执未确认时，资金仍在余额内并占用待付，另一路解冻无法重复支付。
--   争议撤回自动解冻、裁决回执确认记 RELEASE；转付记 TRANSFER_OUT；追回到账记 CLAWBACK_ADJ。
CREATE TABLE escrow_entry (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id        BIGINT NOT NULL REFERENCES escrow_account(id),
    entry_seq         BIGINT NOT NULL,
    entry_type        VARCHAR(24) NOT NULL CHECK (entry_type IN (
        'FREEZE_IN','RELEASE','TRANSFER_OUT','CLAWBACK_ADJ','MANUAL_ADJ')),
    amount_minor      BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency          CHAR(3) NOT NULL,
    escrow_freeze_id  BIGINT REFERENCES escrow_freeze(id),
    dispute_id        BIGINT REFERENCES dispute_case(id),
    arbitration_id    BIGINT,                          -- 运行时再补 FK（arbitration 后建）
    payment_request_id BIGINT,
    counterparty_payee_id BIGINT REFERENCES payee(id),
    legal_ref         VARCHAR(255),
    note              VARCHAR(512),
    balance_after_minor BIGINT NOT NULL,               -- 入账后账户余额
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, entry_seq)
);
CREATE INDEX idx_entry_account ON escrow_entry(account_id, id);
CREATE INDEX idx_entry_freeze ON escrow_entry(escrow_freeze_id);

-- ---------- 裁决（法务裁定：子范围 + 处置方式 + 受益人 + 生效依据） ----------
CREATE TABLE arbitration (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    arbitration_no       VARCHAR(64) NOT NULL UNIQUE,
    dispute_id           BIGINT NOT NULL REFERENCES dispute_case(id),
    recording_id         BIGINT NOT NULL REFERENCES sound_recording(id),
    right_type           VARCHAR(16) NOT NULL,
    territories          VARCHAR(512) NOT NULL,        -- 裁决适用子范围地区（GLOBAL=全部）
    period_start         DATE NOT NULL,
    period_end           DATE NOT NULL,
    outcome              VARCHAR(16) NOT NULL CHECK (outcome IN ('RELEASE','TRANSFER','RETAIN')),
    beneficiary_payee_id BIGINT REFERENCES payee(id),  -- TRANSFER 时必填
    legal_basis          VARCHAR(255) NOT NULL,
    note                 VARCHAR(512),
    status               VARCHAR(20) NOT NULL DEFAULT 'EFFECTIVE'
        CHECK (status IN ('EFFECTIVE','REVOKED')),
    revoked_at           TIMESTAMPTZ,
    revoke_reason        VARCHAR(512),
    created_by           VARCHAR(64) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_arbitration_dates CHECK (period_end >= period_start),
    CONSTRAINT chk_transfer_beneficiary CHECK (outcome <> 'TRANSFER' OR beneficiary_payee_id IS NOT NULL)
);
CREATE INDEX idx_arb_dispute ON arbitration(dispute_id, status);
ALTER TABLE escrow_entry ADD CONSTRAINT fk_entry_arbitration
    FOREIGN KEY (arbitration_id) REFERENCES arbitration(id);

-- 裁决执行批次（部分失败保留进度，可重复执行直到完成；不重复付款）
CREATE TABLE arbitration_execution (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    arbitration_id BIGINT NOT NULL REFERENCES arbitration(id),
    attempt_seq    INTEGER NOT NULL,
    status         VARCHAR(16) NOT NULL CHECK (status IN ('COMPLETED','PARTIAL','FAILED')),
    actor          VARCHAR(64) NOT NULL,
    note           VARCHAR(512),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (arbitration_id, attempt_seq)
);

CREATE TABLE arbitration_execution_line (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id        BIGINT NOT NULL REFERENCES arbitration_execution(id),
    escrow_freeze_id    BIGINT NOT NULL REFERENCES escrow_freeze(id),
    payable_id          BIGINT REFERENCES payable(id),
    action              VARCHAR(16) NOT NULL CHECK (action IN ('RELEASE','TRANSFER','REMAIN')),
    amount_minor        BIGINT NOT NULL CHECK (amount_minor >= 0),
    settled_minor       BIGINT NOT NULL DEFAULT 0,   -- 已随回执确认出款的金额（支持部分付款 FIFO）
    escrow_entry_id     BIGINT REFERENCES escrow_entry(id),
    covering_dispute_ids VARCHAR(512),                 -- REMAIN 时说明仍被哪些其他争议覆盖
    note                VARCHAR(512),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_exeline_exec ON arbitration_execution_line(execution_id);
CREATE INDEX idx_exeline_freeze ON arbitration_execution_line(escrow_freeze_id);
CREATE INDEX idx_exeline_payable ON arbitration_execution_line(payable_id);
CREATE INDEX idx_exeline_arbitration ON arbitration_execution_line(execution_id);

-- ---------- 追回事项：已经付出的争议款不属于托管现存资金，另行追回 ----------
CREATE TABLE clawback_item (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clawback_no        VARCHAR(64) NOT NULL UNIQUE,
    dispute_id         BIGINT NOT NULL REFERENCES dispute_case(id),
    settlement_batch_id BIGINT NOT NULL REFERENCES settlement_batch(id),
    payable_id         BIGINT NOT NULL REFERENCES payable(id),
    payee_id           BIGINT NOT NULL REFERENCES payee(id),
    currency           CHAR(3) NOT NULL,
    amount_minor       BIGINT NOT NULL CHECK (amount_minor > 0),
    status             VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','RECOVERED','WRITTEN_OFF')),
    reason             VARCHAR(512) NOT NULL,
    recovered_at       TIMESTAMPTZ,
    recovery_ref       VARCHAR(128),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_clawback_status ON clawback_item(status, dispute_id);
-- 同一争议对同一应付只能生成一次追回，重复声明争议不得重复追回
CREATE UNIQUE INDEX uq_clawback_dispute_payable ON clawback_item(dispute_id, payable_id);

-- ---------- 既有表扩展：冻结金额与托管付款 ----------
-- 分摊/挂账/应付各自记录被托管冻结的金额（来源侧留痕，托管台账为资金权威）
ALTER TABLE settlement_allocation ADD COLUMN frozen_minor BIGINT NOT NULL DEFAULT 0;
-- 已就该分摊生成追回的金额：重复争议不得对同一份额二次追回
ALTER TABLE settlement_allocation ADD COLUMN clawed_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hold_balance ADD COLUMN frozen_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payable ADD COLUMN frozen_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payable ADD COLUMN frozen_release_minor BIGINT NOT NULL DEFAULT 0;
-- 托管释放/转付形成的应付不允许被普通付款运行拾取，由托管付款单独处理
ALTER TABLE payable ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'SETTLEMENT'
    CHECK (source_kind IN ('SETTLEMENT','ESCROW_RELEASE','ESCROW_TRANSFER'));
-- 托管应付按裁决隔离，不同裁决即使收款人相同也各自成行，避免重复付款误判
ALTER TABLE payable ADD COLUMN arbitration_id BIGINT REFERENCES arbitration(id);
ALTER TABLE payable DROP CONSTRAINT IF EXISTS payable_settlement_batch_id_payee_id_right_type_currency_key;
ALTER TABLE payable DROP CONSTRAINT IF EXISTS uq_payable_scope_kind;
CREATE UNIQUE INDEX uq_payable_settlement ON payable(settlement_batch_id, payee_id, right_type, currency)
    WHERE source_kind = 'SETTLEMENT';
CREATE UNIQUE INDEX uq_payable_escrow ON payable(settlement_batch_id, payee_id, right_type,
    currency, source_kind, arbitration_id) WHERE source_kind <> 'SETTLEMENT';

-- 挂账与分摊行的精确关联（V1 时代两者并行插入，V2 起在结算引擎内回填）
ALTER TABLE hold_balance ADD COLUMN settlement_allocation_id BIGINT REFERENCES settlement_allocation(id);
CREATE UNIQUE INDEX uq_hold_allocation ON hold_balance(settlement_allocation_id)
    WHERE settlement_allocation_id IS NOT NULL;

-- 付款运行区分普通付款与托管付款；托管付款文件与释放分录绑定
ALTER TABLE payment_run ADD COLUMN run_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
    CHECK (run_kind IN ('NORMAL','ESCROW'));
ALTER TABLE payment_run ADD COLUMN account_id BIGINT REFERENCES escrow_account(id);

-- 付款请求扩展：托管请求挂释放分录；SUSPENDED 表示裁决撤回后暂停未确认付款
ALTER TABLE payment_request ADD COLUMN escrow_entry_id BIGINT REFERENCES escrow_entry(id);
ALTER TABLE payment_request ADD COLUMN arbitration_id BIGINT REFERENCES arbitration(id);
ALTER TABLE payment_request ALTER COLUMN payable_id DROP NOT NULL;
-- V1 中未命名的列级 CHECK 由 PostgreSQL 自动命名为 payment_request_status_check
ALTER TABLE payment_request DROP CONSTRAINT IF EXISTS payment_request_status_check;
ALTER TABLE payment_request ADD CONSTRAINT chk_payment_request_status CHECK (status IN
    ('ISSUED','TIMEOUT_QUERIED','CONFIRMED','REJECTED','FAILED','SUSPENDED'));
CREATE INDEX idx_request_payable ON payment_request(payable_id);

-- 回执核验结果增加 SUSPENDED：裁决撤回后暂停的付款请求，其回执不再可能匹配
ALTER TABLE payment_receipt DROP CONSTRAINT IF EXISTS payment_receipt_match_status_check;
ALTER TABLE payment_receipt ADD CONSTRAINT chk_receipt_match_status CHECK (match_status IN
    ('MATCHED','MISMATCH_AMOUNT','MISMATCH_CURRENCY','MISMATCH_ACCOUNT','UNKNOWN_REQUEST','DUPLICATE','SUSPENDED'));
