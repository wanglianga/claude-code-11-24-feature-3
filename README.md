# 音乐版权使用归属与版税结算服务

面向结算员的版权结算后端：把平台上报的每一次使用匹配到**正确的录音版本与权利人**，
按**使用当日有效合同**计算应付款，并对每一笔暂扣、冲突与调整给出可追溯解释。
技术栈：Java 25、Spring Boot 3.5、PostgreSQL 18、Flyway、springdoc-openapi。
金额一律以 **BIGINT 最小货币单位**（如美分）存储与运算，份额全程使用**有理数（分子/分母）**，
分摊采用**最大余数法**保证逐笔金额之和严格等于可分配额。

> 本服务为后端工程，不访问真实银行：付款产出本地 CSV 付款文件，回执由模拟银行文件导入核验。
> OpenAPI 3 文档与 Swagger UI 随应用提供。

---

## 1. 领域规则如何落地

| 业务要求 | 实现位置与做法 |
| --- | --- |
| 相同曲名有不同录音版本，词曲权与录音权收款人不同 | `sound_recording` 以明确录音标识（类 ISRC）为唯一归属锚点；词曲合同挂 `work_id`、录音合同挂 `recording_id`，两类权利各自独立选合同、独立出应付 |
| 平台内部编号与版权目录不通用 | `platform_mapping` 维护平台编号→录音的人工审核映射；`mapping_candidate` 仅保存标题/作者/时长候选，**任何相似度都不能自动归属**，无 APPROVED 映射的款项进 `UNMATCHED` 待归属，不猜收款人 |
| 合同份额季度中途改变 | 合同带 `valid_from/valid_to`、地区、媒介、权利类型；`CatalogRepository.effectiveContracts` 严格按**使用日期**选取，旧款不会被最新份额重算 |
| 份额合计超 100% 或多份相互冲突的有效合同 | `ShareResolver`：合计>100% 整笔 `CONFLICT_HOLD` 阻止结算；多份并存合同中跨合同出现的权利人份额暂扣，**无争议部分继续**；不足 100% 的剩余单列 `UNCLAIMED`，绝不放大已知人比例 |
| 按次单价 vs 直接报告收入两种方式 | `platform_agreement.settlement_method` 声明 `PER_UNIT`/`GROSS_REPORT`，由 `IncomeCalculator` 实现；按次=有效次数×当期单价，直接收入=已核对原币净额；同一行两种方式混用直接阻止 |
| 平台费用只扣一次 | 净额已含扣费则直接取净额（`REPORTED_NET`）不重扣；无净额时仅按协议明确条款对总额扣一次（`REPORTED_GROSS_LESS_FEE`）；按次行携带平台总额会被拒绝，防止“次数乘单价又重复分总额” |
| 报告批次原始合计、坏行/重复行/有效行分别计数 | 报告文件 `#BATCH` 头声明合计；`ReportImportService` 输出 valid/bad/dup 计数，声明与明细不符或存在坏行即转 `RECONCILING`，**只有 ACCEPTED 批次能进入结算**，不漏行却自称完整 |
| 撤销关联原报告行、重传与晚到更正 | 以平台原行编号+修订号辨识；同修订号重传只计重复不落库、原件不覆盖；高修订号 NORMAL 标记原件 SUPERSEDED，REVOKE 标记原件 REVOKED；晚到更正形成新计算批次 |
| 逐笔守恒、尾差分配 | `Rational` + `LargestRemainder`：每结算行写入后硬断言分摊合计=可分配额；批次级再断言分摊闭合且挂账台账与非应付分摊一致 |
| 不同权利类型预算分列 | 直接收入按协议预算份额（如录音 60%/词曲 40%）拆分；预算合计不足 100% 的余额记 `UNBUDGETED` 待认领，词曲和录音都不能领走整笔 |
| 采用前预览影响金额 | `GET /api/candidates/{id}/preview` 与 `/api/mappings/preview` 实时计算影响金额且不落库，返回守恒校验与告警 |
| 批准后才生成付款文件，重试内容不变 | `PaymentService` 保存账户快照与正文 SHA-256 指纹，请求标识由正文确定性派生；重复调用复用原运行与原文件 |
| 超时查原标识、不重发；回执金额+币种+账户一致才记已付 | `query-timeout` 只回查原 `request_id`；回执核验分 MATCHED/MISMATCH_AMOUNT/MISMATCH_CURRENCY/MISMATCH_ACCOUNT/UNKNOWN_REQUEST/DUPLICATE；旧账户按生成时快照核对 |
| 结算批次固定依据、复核撤回 | `SnapshotService` 对结算行源指纹、选中合同及份额、录音、分摊结果生成指纹；`review` 额外显式检查映射撤回/改派、录音撤出、原报告行撤销 |
| 已批准季度的权利更正 | 差额单 `adjustment_order` 留痕，批准后另起 `ADJUSTMENT` 批次出账，**旧批次金额不变** |
| 中断续算、同范围不可重复出账 | 分段（默认 200 行/段）独立事务；`settlement_lock` 唯一约束 + `ScopeLockManager`（REQUIRES_NEW 独立事务）保证同范围双执行者拦截 |
| 应收可展开到合同与平台原行 | `GET /api/payees/{id}/receivable` 逐级钻取：批次→结算行→合同维度→平台原行编号/修订号 |
| 争议只冻有争议的份额，不冻结整首录音 | 法务以**录音+权利类别**限定争议（两家公司争部分词曲权不连带录音权），再声明地区/使用时段/比例，与 `settlement_allocation` 求交；比例拆分用最大余数法落最小货币单位，无争议收入照常结算 |
| 重叠争议求并集，不重复冻结 | 同一分摊行全系统至多一条 `escrow_freeze`；各争议独立主张记 `escrow_freeze_cover`，实际冻结取各争议比例**并集（最大值）**，重叠部分只补差额，绝不同一份额冻两次 |
| 已付争议款不进托管 | 争议声明时已确认付款的部分，按应付聚合并受已付额上限生成 `clawback_item` 追回事项，分摊行 `clawed_minor` 防止重复追回；它不是托管现存资金 |
| 裁决只覆盖声明子范围，执行前可预览 | 裁决声明地区/时段/处置（RELEASE 释放、TRANSFER 转付受益人、RETAIN 保留）；`GET .../preview` 给出可释放、应转付与仍被其他争议覆盖的金额，不落库 |
| 托管台账独立、不随目录换主 | `escrow_account` 按币种独立，`escrow_entry` 不可变分录记录每笔进入/释放/转付；余额只由分录驱动，权利目录变化不自动换所有者 |
| 裁决执行部分失败可续、不重复付款 | 执行只产生托管待付（不动托管资金）；按 `arbitration_execution_line` 记录进度，待付占用检查 `committedPendingOfFreeze`，两份裁决作用同一余额时只处置未占用部分，重复执行幂等 |
| 付款文件与托管释放绑定 | 托管付款 `run_kind=ESCROW`，请求绑定裁决；**回执确认资金才离开托管**（写 RELEASE/TRANSFER_OUT 分录）；回执未确认前资金仍占待付额，另一路解冻无法重复支付 |
| 裁决撤销不删历史 | 已生成的释放/转付分录保留；未付款请求置 `SUSPENDED` 并退回冻结，其再来回执按 SUSPENDED 拒绝；已付款列回收差额（clawback） |
| 托管守恒可逐项核对 | 恒等式 **托管入账 = 可支配余额 + 待付 + 已释放**，每次冻结/执行/回执/撤回后硬断言；`GET /api/escrow/accounts/{no}/ledger` 逐笔分录解释 |

## 2. 工程结构

```
src/main/java/cc/royalty
├── common/        Rational 有理数、最大余数法、SHA-256 指纹、CSV、领域异常
├── domain/        不可变领域记录（争议、托管账户/冻结/分录、裁决、追回）
├── repo/          JdbcTemplate 仓储（无 ORM 魔法，SQL 显式可审）
├── service/       导入、候选匹配、收入确定、份额解析、结算引擎、快照、付款回执、差额单、EscrowService 争议托管与裁决、查询
├── web/           REST 控制器（ImportController / WorkflowController / EscrowController）与全局异常处理
└── config/        事务模板、OpenAPI 配置
src/main/resources/db/migration/V1__init.sql   结算域表结构
src/main/resources/db/migration/V2__escrow_arbitration.sql  争议/托管/裁决/追回表与既有表扩展（Flyway 自动迁移）
samples/          权利人/作品/录音/合同/协议/映射/平台报告/模拟回执/多地区托管样本
```

## 3. 本地启动

### 3.1 一键启动（Docker Compose，推荐）

```bash
docker compose up --build
# 应用: http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
# OpenAPI:    http://localhost:8080/v3/api-docs
```

Compose 内含 PostgreSQL 18（库/用户/密码 `ccdev`），Flyway 启动时自动建表到 `royalty` schema。

### 3.2 本机直跑（使用容器/本机已装 PostgreSQL 18）

```bash
# 数据库默认连接 127.0.0.1:5432/ccdev（ccdev/ccdev），可用环境变量覆盖
/opt/maven/bin/mvn -B spring-boot:run
# 或先打包
/opt/maven/bin/mvn -B package -DskipTests
/opt/jdk-25/bin/java -jar target/royalty-settlement-1.0.0.jar
```

环境变量：`DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD DB_SCHEMA PAYMENT_DIR RECEIPT_DIR`。

### 3.3 账号说明

- 数据库账号：`ccdev / ccdev`（隔离开发库，非生产配置）。
- 应用无登录体系，业务操作人通过接口 `actor` 参数留痕（默认 `settler`，复核示例用 `reviewer-li`）。

## 4. 验证方式（可复现脚本）

### 4.1 自动化测试

```bash
/opt/maven/bin/mvn -B test
```

测试使用独立 schema `royalty_test`（`src/test/resources/application-test.yml`），每个用例自动清表。

### 4.2 HTTP 全流程（应用启动后）

```bash
B=http://localhost:8080; S=samples
# 1) 目录与合同
for f in payees works contributors recordings platforms contracts agreements mappings; do
  curl -s -F "file=@$S/$f.csv" $B/api/import/$f; echo
done
# 2) 平台报告（含坏行/重复行核对批次、晚到更正批次）
curl -s -F "file=@$S/reports/q1_plfa.csv"      $B/api/import/reports
curl -s -F "file=@$S/reports/q1_plfb.csv"      $B/api/import/reports
curl -s -F "file=@$S/reports/q1_plfc.csv"      $B/api/import/reports
curl -s -F "file=@$S/reports/recon_bad_dup.csv" $B/api/import/reports   # 期望 reconciliation=MISMATCH
curl -s -F "file=@$S/reports/late_correction.csv" $B/api/import/reports # 修订/撤销原件
# 3) 候选与待匹配（只出候选，不自动归属）
curl -s -X POST $B/api/batches/1/candidates
curl -s "$B/api/workbench/pending-matching?batchId=1"
# 4) 结算（platformId 可查 /api/import 后用 SQL：select id from platform where platform_code='PLF-A'）
curl -s -X POST $B/api/settlements -H 'Content-Type: application/json' \
  -d '{"platformId":1,"periodStart":"2026-01-01","periodEnd":"2026-03-31","actor":"settler-zhang"}'
curl -s $B/api/settlements/1/summary                 # 守恒汇总与挂账解释
# 5) 复核批准 → 付款文件 → 模拟回执
curl -s -X POST $B/api/settlements/1/review
curl -s -X POST "$B/api/settlements/1/approve?actor=reviewer-li"
curl -s -X POST "$B/api/settlements/1/payment-runs?currency=USD"
curl -s $B/api/payment-runs/PR00000001-USD           # 取 request_id/账户快照后制作回执
curl -s -F "file=@signed-receipt.csv" $B/api/import/receipts
curl -s $B/api/workbench/receipt-failures

# 6) 争议托管与裁决（多地区样本；先导入 q1_escrow.csv 并完成一个结算批次）
curl -s -F "file=@$S/reports/q1_escrow.csv" $B/api/import/reports
# 争议1：仅 US 地区、半季度词曲权 1/2（录音权不受影响）
curl -s -X POST $B/api/escrow/disputes -H 'Content-Type: application/json' -d '{
  "disputeNo":"DSP-1","recordingCode":"SR-ME-ORIG","rightType":"PUBLISHING",
  "territories":["US"],"periodStart":"2026-01-01","periodEnd":"2026-03-31",
  "ratioNumerator":1,"ratioDenominator":2,"claimantCode":"P-PUB-A","respondentCode":"P-PUB-B",
  "legalRef":"LEGAL-1","reason":"词曲权争议","actor":"legal-wang"}'
# 争议2：US+EU 同比例 —— US 重叠部分求并集不二次冻结，只新增 EU
curl -s -X POST $B/api/escrow/disputes -H 'Content-Type: application/json' -d '{
  "disputeNo":"DSP-2","recordingCode":"SR-ME-ORIG","rightType":"PUBLISHING",
  "territories":["US","EU"],"periodStart":"2026-01-01","periodEnd":"2026-03-31",
  "ratioNumerator":1,"ratioDenominator":2,"claimantCode":"P-PUB-A","respondentCode":"P-PUB-B",
  "legalRef":"LEGAL-2","reason":"争议扩区","actor":"legal-wang"}'
curl -s $B/api/escrow/accounts                                   # 托管入账 = 余额+待付+已释放
# 裁决只覆盖 US 二月（半个子范围），先预览再执行
curl -s -X POST $B/api/escrow/arbitrations -H 'Content-Type: application/json' -d '{
  "arbitrationNo":"ARB-1","disputeNo":"DSP-1","territories":["US"],
  "periodStart":"2026-02-01","periodEnd":"2026-02-28","outcome":"RELEASE",
  "legalBasis":"COURT-1","note":"US二月释放","actor":"legal-wang"}'
curl -s $B/api/escrow/arbitrations/ARB-1/preview               # 可释放/保留逐项
curl -s -X POST "$B/api/escrow/arbitrations/ARB-1/execute?actor=legal-wang"
curl -s -X POST "$B/api/escrow/accounts/ESCROW-USD/payment-runs?actor=legal-wang"  # 托管付款文件
# 按返回 request_id/账户快照制作回执并导入；确认前资金占待付，确认后才写 RELEASE 分录
curl -s $B/api/escrow/accounts/ESCROW-USD/ledger               # 逐笔分录与余额
# 裁决撤回：未付暂停退回冻结，已付列回收（历史释放分录不删除）
curl -s -X POST "$B/api/escrow/arbitrations/ARB-1/revoke?reason=appeal&actor=legal-wang"
curl -s $B/api/escrow/clawbacks                                # 追回事项清单
```

## 5. 样本覆盖的关键场景（`samples/`）

- **同名不同录音**：`Midnight Echo` 有 Original/Live 两个录音（SR-ME-ORIG、SR-ME-LIVE），候选分数 Original 0.995 > Live 0.85，但必须人工采用。
- **季度中途转权**：SR-ME-ORIG 录音权 2026-02-16 起从 RecordCo Alpha 转至 RecordCo Beta（RC-ME-1/RC-ME-2 日期不重叠）。
- **多合同争议**：SR-SS-1 两份并存合同，跨合同的 Alpha 份额暂扣、Beta 无争议继续。
- **份额超 100%**：SR-CT-1 有效份额合计 150%，整笔阻止结算。
- **份额不足 100%**：SR-OT-1 仅 75% 有主，25% 待认领；PLF-C 预算合计 90%，余 10% 待认领。
- **两种结算方式**：PLF-A 按次单价（含 10% 费用条款）、PLF-B 直接净额（60/40 预算）、PLF-C 只报总额（15% 条款扣一次）。
- **坏行/重复行/撤销/晚到更正**：`recon_bad_dup.csv`、`late_correction.csv`。
- **部分付款与回执失败**：测试内构造金额/币种/账户/未知标识四类失败回执。
- **争议托管（`reports/q1_escrow.csv`）**：同一录音 SR-ME-ORIG 在 US/EU/JP 三地区、2/3 两月各 100 次使用，支撑重叠争议并集、部分地区/半月裁决、回执占额与裁决撤回场景。

## 6. 已执行的验证结论

- `mvn test`：**10 个测试全部通过**——5 个有理数/LRM 单元测试、2 个原有端到端/续算集成测试（回归无破坏）、3 个新增争议托管集成测试 `EscrowLifecycleTest`：
  - 重叠争议并集去重（US 重叠不二次冻结，仅新增 EU，同一分摊行至多一条冻结）；
  - 裁决只覆盖 US 二月子范围，US 三月与 EU/JP 不被误付；执行只建待付，回执确认才出托管资金；
  - 回执未确认持续占用待付额，裁决撤回后请求置 SUSPENDED、迟到回执被拒；
  - 裁决撤回：未付暂停退回冻结（不产生释放分录）、已付列回收差额且历史分录不删除；
  - 已付款争议生成追回事项而非托管，重复争议不重复追回；
  - 每个用例结尾硬断言 **托管入账 = 可支配余额 + 待付 + 已释放**。
- 打包 jar 实际启动成功，Flyway V1+V2 自动迁移通过；Swagger UI 返回 200，OpenAPI 暴露 44 个接口路径（其中 `/api/escrow/**` 12 个）。
- 真实 HTTP 冒烟跑通 导入→结算→批准→重叠争议登记（并集冻结 20000）→US 二月裁决预览/执行→托管付款文件→回执确认（RELEASE 5000）→EU 转付裁决撤回（退回 10000）；各时点 `GET /api/escrow/accounts` 恒等式均 `conservation_ok=true`，`ledger` 可逐笔说明 FREEZE_IN/RELEASE 分录与余额。
- 原结算冒烟保持不变：重复结算范围返回 400；普通付款重试 `reused=true` 且文件指纹不变；普通付款运行不拾取托管来源应付（`source_kind='SETTLEMENT'` 过滤）。
- 付款文件生成于 `data/payment-files/<runNo>/`（普通 `PR...`、托管 `ER...`），内容含账户快照与正文指纹，不随账户后改或重试变化。

## 7. 关键约束与边界

- 不连接真实银行、不发起真实支付；`payment_request` 为模拟指令，回执必须由文件导入。
- 结算只采信 `APPROVED` 映射与 `ACCEPTED` 报告批次；`PROPOSED` 映射、`RECONCILING` 批次不产生款项。
- 所有金额计算在应用层以整数/有理数完成，数据库仅存储，杜绝浮点误差。
- 托管是独立台账：裁决执行不立即移动资金，只有托管付款回执确认才写 RELEASE/TRANSFER_OUT 出账分录；`escrow_entry` 不可变，任何撤回都不删除历史分录。
- 争议比例与裁决范围只能收窄、不能放大已确认的权利；超出实际冻结余额或被其他争议/待付占用的部分不会被处置。
- 追回事项针对**已付出**的争议款，与托管现存资金严格区分；追回款到账才记 `CLAWBACK_ADJ` 入托管。
