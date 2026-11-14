package cc.royalty;

import cc.royalty.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 争议版税托管与裁决执行端到端：
 * 重叠争议并集去重、部分地区/时段裁决、回执未确认占待付、裁决撤回（未付暂停/已付追回）、
 * 已付款争议生成追回而非托管、托管入账=余额+待付+已释放 恒等式逐项核对。
 */
@TestPropertySource(properties = "app.settlement.segment-size=50")
class EscrowLifecycleTest extends AbstractIntegrationTest {

    @Autowired private CatalogImportService catalogImport;
    @Autowired private ReportImportService reportImport;
    @Autowired private SettlementService settlementService;
    @Autowired private PaymentService paymentService;
    @Autowired private EscrowService escrowService;
    @Autowired private QueryService queryService;

    private long payeeId(String code) {
        return jdbc.queryForObject("select id from payee where payee_code=?", Long.class, code);
    }

    private long recordingId(String code) {
        return jdbc.queryForObject("select id from sound_recording where recording_code=?", Long.class, code);
    }

    private long escrowAccountId() {
        return jdbc.queryForObject("select id from escrow_account where account_no='ESCROW-USD'", Long.class);
    }

    private long sum(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> conservation() {
        return escrowService.accountConservation(escrowAccountId());
    }

    private void seedAndSettle() {
        catalogImport.importPayees(sample("payees.csv"));
        catalogImport.importWorks(sample("works.csv"));
        catalogImport.importContributors(sample("contributors.csv"));
        catalogImport.importRecordings(sample("recordings.csv"));
        catalogImport.importPlatforms(sample("platforms.csv"));
        catalogImport.importContracts(sample("contracts.csv"));
        catalogImport.importAgreements(sample("agreements.csv"));
        catalogImport.importMappings(sample("mappings.csv"));
        var report = reportImport.importReport(sample("reports/q1_escrow.csv"), "q1_escrow.csv");
        assertEquals("MATCHED", report.reconciliation());
        long platformA = jdbc.queryForObject(
                "select id from platform where platform_code='PLF-A'", Long.class);
        var result = settlementService.createAndCalculate(platformA,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"),
                List.of(report.batchId()), "settler-zhang");
        assertEquals("CALCULATED", result.status());
    }

    private EscrowService.DisputeInput dispute(String no, List<String> terrs, long num, long den) {
        return new EscrowService.DisputeInput(no, "SR-ME-ORIG", "PUBLISHING", terrs,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), num, den,
                "P-PUB-A", "P-PUB-B", "LEGAL-" + no, "两家公司就词曲权归属发生争议", "legal-wang");
    }

    @Test
    void overlappingDisputesUnionFreezeAndPartialArbitration() {
        seedAndSettle();

        // 无争议收入照常结算：录音权与部分词曲权应付不受影响
        assertEquals(27000, sum("select coalesce(sum(amount_minor),0) from payable where source_kind='SETTLEMENT' "
                + "and payee_id=? and right_type='RECORDING'", payeeId("P-REC-A")));
        assertEquals(15000, sum("select coalesce(sum(amount_minor),0) from payable where source_kind='SETTLEMENT' "
                + "and payee_id=? and right_type='PUBLISHING'", payeeId("P-WRITER")));

        // 争议 1：US 地区、半季度词曲权 1/2
        var d1 = escrowService.registerDispute(dispute("DSP-ESC-1", List.of("US"), 1, 2));
        assertEquals(10000, d1.frozenMinor());
        assertEquals(0, d1.clawbackMinor());
        long accountId = escrowAccountId();
        assertEquals(10000, sum("select coalesce(sum(amount_minor),0) from escrow_entry "
                + "where account_id=? and entry_type='FREEZE_IN'", accountId));
        // 只冻词曲权，录音权零冻结
        assertEquals(0, sum("select count(*) from escrow_freeze where right_type='RECORDING'"));

        // 争议 2：US+EU 同样 1/2 —— US 已被争议1覆盖（并集 max 比例相同，不重复冻），仅 EU 新增
        var d2 = escrowService.registerDispute(dispute("DSP-ESC-2", List.of("US", "EU"), 1, 2));
        assertEquals(10000, d2.frozenMinor(), "重叠的 US 不得二次冻结，只新增 EU 部分");
        // 并集后总冻结 20000：US 10000 + EU 10000，JP 不冻
        assertEquals(20000, sum("select coalesce(sum(amount_minor),0) from escrow_entry "
                + "where entry_type='FREEZE_IN'"));
        assertEquals(20000, sum("select coalesce(sum(frozen_amount_minor),0) from escrow_freeze"));
        assertEquals(0, sum("select coalesce(sum(frozen_amount_minor),0) from escrow_freeze "
                + "where territory='JP'"));
        // 同一分摊行全系统至多一条冻结（不能同一份额冻两次）
        assertEquals(0, sum("select count(*) from (select settlement_allocation_id from escrow_freeze "
                + "group by settlement_allocation_id having count(*) > 1) t"));
        assertEquals(true, conservation().get("conservation_ok"));

        // 裁决 1：只覆盖 US 的 2 月（半个子范围），释放给原权利人
        long recId = recordingId("SR-ME-ORIG");
        long dispute1Id = jdbc.queryForObject("select id from dispute_case where dispute_no='DSP-ESC-1'", Long.class);
        escrowService.registerArbitration(new EscrowService.ArbitrationInput(
                "ARB-ESC-1", "DSP-ESC-1", List.of("US"),
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"),
                "RELEASE", null, "COURT-ORDER-1", "US 二月词曲权归原权利人", "legal-wang"));
        var preview = escrowService.previewArbitration("ARB-ESC-1");
        assertEquals(5000, preview.releasableMinor(), "仅 US 二月 5000 可释放");
        assertEquals(0, preview.retainedMinor(), "裁决子范围（US 二月）内无保留额");
        // 子范围外的 US 三月 5000 仍是冻结余额，不会被本次裁决误付
        assertEquals(5000, sum("select coalesce(sum(frozen_amount_minor - released_minor - transferred_minor),0) "
                + "from escrow_freeze where territory='US' and usage_date >= date '2026-03-01'"));
        var exec = escrowService.executeArbitration("ARB-ESC-1", "legal-wang");
        assertEquals("COMPLETED", exec.status());
        assertEquals(5000, exec.releasedMinor());
        // 执行只建待付，资金仍在托管：无 RELEASE 分录，待付占用 5000
        assertEquals(0, sum("select count(*) from escrow_entry where entry_type='RELEASE'"));
        Map<String, Object> c1 = conservation();
        assertEquals(5000L, c1.get("pending_payment_minor"));
        assertEquals(15000L, c1.get("available_balance_minor"));
        assertEquals(true, c1.get("conservation_ok"));

        // 托管付款文件（与释放绑定）；回执未确认前仍占待付额
        var run = paymentService.generateEscrowPaymentRun("ESCROW-USD", "legal-wang");
        assertFalse((Boolean) run.get("reused"));
        Map<String, Object> c2 = conservation();
        assertEquals(5000L, c2.get("pending_payment_minor"), "回执未确认，待付仍占用");
        assertEquals(15000L, c2.get("available_balance_minor"));

        // 模拟回执确认 → 资金才真正离开托管，写 RELEASE 分录（US 二月两个权利人各一笔，合计 5000）
        @SuppressWarnings("unchecked")
        List<String> requestIds = (List<String>) run.get("requests");
        assertEquals(2, requestIds.size());
        var runDetail = queryService.paymentRun((String) run.get("run_no"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) runDetail.get("requests");
        StringBuilder receipt = new StringBuilder(
                "receipt_ref,request_id_ref,account_ref,amount_minor,currency\n");
        int ri = 1;
        long receiptTotal = 0;
        for (Map<String, Object> rq : reqs) {
            receipt.append("ERCPT-").append(ri++).append(",").append(rq.get("request_id")).append(",")
                    .append(rq.get("account_snapshot")).append(",").append(rq.get("amount_minor"))
                    .append(",USD\n");
            receiptTotal += ((Number) rq.get("amount_minor")).longValue();
        }
        assertEquals(5000, receiptTotal);
        var receiptResult = paymentService.importReceipts(receipt.toString(), "escrow-receipt.csv");
        assertEquals(2, receiptResult.matched());
        Map<String, Object> c3 = conservation();
        assertEquals(5000L, c3.get("released_minor"));
        assertEquals(0L, c3.get("pending_payment_minor"));
        assertEquals(15000L, c3.get("available_balance_minor"));
        assertEquals(true, c3.get("conservation_ok"));

        // 裁决 2：EU 转付受益人，执行后撤回（未出款）→ 暂停并退回冻结，不产生释放分录
        escrowService.registerArbitration(new EscrowService.ArbitrationInput(
                "ARB-ESC-2", "DSP-ESC-2", List.of("EU"),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"),
                "TRANSFER", "P-PUB-B", "COURT-ORDER-2", "EU 词曲权转 Epsilon", "legal-wang"));
        var exec2 = escrowService.executeArbitration("ARB-ESC-2", "legal-wang");
        assertEquals(10000, exec2.transferredMinor());
        assertEquals(10000L, conservation().get("pending_payment_minor"));
        var revoke2 = escrowService.revokeArbitration("ARB-ESC-2", "上诉改判", "legal-wang");
        assertEquals(10000, revoke2.suspendedMinor());
        assertEquals(0, sum("select count(*) from escrow_entry where entry_type='TRANSFER_OUT'"),
                "未出款撤回不得产生转付分录");
        assertEquals(0L, conservation().get("pending_payment_minor"));
        // EU 覆盖额回退后冻结仍完整保留，可被后续新裁决处置
        assertEquals(10000, sum("select coalesce(sum(frozen_amount_minor - released_minor - transferred_minor),0) "
                + "from escrow_freeze where territory='EU'"));

        // 裁决 3：US 三月释放，出款后撤回 → 已付列回收差额，历史释放分录保留
        escrowService.registerArbitration(new EscrowService.ArbitrationInput(
                "ARB-ESC-3", "DSP-ESC-1", List.of("US"),
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"),
                "RELEASE", null, "COURT-ORDER-3", "US 三月归原权利人", "legal-wang"));
        var exec3 = escrowService.executeArbitration("ARB-ESC-3", "legal-wang");
        assertEquals(5000, exec3.releasedMinor());
        var run3 = paymentService.generateEscrowPaymentRun("ESCROW-USD", "legal-wang");
        var detail3 = queryService.paymentRun((String) run3.get("run_no"));
        StringBuilder receipt3 = new StringBuilder(
                "receipt_ref,request_id_ref,account_ref,amount_minor,currency\n");
        int r3 = 1;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqs3 = (List<Map<String, Object>>) detail3.get("requests");
        long run3Total = 0;
        for (Map<String, Object> rq : reqs3) {
            receipt3.append("ERCPT3-").append(r3++).append(",").append(rq.get("request_id")).append(",")
                    .append(rq.get("account_snapshot")).append(",").append(rq.get("amount_minor"))
                    .append(",USD\n");
            run3Total += ((Number) rq.get("amount_minor")).longValue();
        }
        assertEquals(5000, run3Total);
        paymentService.importReceipts(receipt3.toString(), "escrow-receipt-2.csv");
        long entriesBefore = sum("select count(*) from escrow_entry");
        var revoke3 = escrowService.revokeArbitration("ARB-ESC-3", "终审推翻", "legal-wang");
        assertEquals(5000, revoke3.clawbackMinor());
        assertEquals(2, revoke3.clawbackItems());
        assertEquals(entriesBefore, sum("select count(*) from escrow_entry"), "撤回不得删除历史释放分录");
        assertEquals(5000, sum("select coalesce(sum(amount_minor),0) from clawback_item where status='OPEN'"));

        // 最终恒等式：入账 20000 = 可支配 10000 + 待付 0 + 已释放 10000
        Map<String, Object> finalC = conservation();
        assertEquals(20000L, finalC.get("escrow_booked_in_minor"));
        assertEquals(10000L, finalC.get("available_balance_minor"));
        assertEquals(0L, finalC.get("pending_payment_minor"));
        assertEquals(10000L, finalC.get("net_released_minor"));
        assertEquals(true, finalC.get("conservation_ok"));
    }

    @Test
    void alreadyPaidDisputedMoneyBecomesClawbackNotEscrow() {
        seedAndSettle();
        // 先批准并对全部结算应付出款、回执确认（争议款已付出）
        long batchId = jdbc.queryForObject("select id from settlement_batch order by id limit 1", Long.class);
        assertTrue(settlementService.review(batchId).isEmpty());
        settlementService.approve(batchId, "reviewer-li");
        var run = paymentService.generatePaymentRun(batchId, "USD", "settler-zhang");
        var detail = queryService.paymentRun(run.runNo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) detail.get("requests");
        StringBuilder sb = new StringBuilder("receipt_ref,request_id_ref,account_ref,amount_minor,currency\n");
        int i = 1;
        for (Map<String, Object> r : reqs) {
            sb.append("RCPT-E").append(i++).append(",").append(r.get("request_id")).append(",")
                    .append(r.get("account_snapshot")).append(",").append(r.get("amount_minor"))
                    .append(",USD\n");
        }
        assertEquals(reqs.size(), paymentService.importReceipts(sb.toString(), "all-paid.csv").matched());

        // 再登记 US 半额词曲争议：目标款全部已付，只能生成追回，不进托管
        var d = escrowService.registerDispute(dispute("DSP-PAID", List.of("US"), 1, 2));
        assertEquals(0, d.frozenMinor(), "已付款不能进托管冻结");
        // US 词曲半额 = P-PUB-A 7500 + P-WRITER 2500 = 10000
        assertEquals(10000, d.clawbackMinor());
        assertEquals(2, d.clawbackItems());
        assertEquals(0, sum("select count(*) from escrow_account"), "无托管现存资金则不建账户");
        assertEquals(10000, sum("select coalesce(sum(amount_minor),0) from clawback_item"));

        // 重复登记同一争议范围不产生重复追回
        assertDoesNotThrow(() -> {
            var again = escrowService.registerDispute(
                    dispute("DSP-PAID-2", List.of("US"), 1, 2));
            assertEquals(0, again.clawbackMinor());
        });
    }

    @Test
    void unconfirmedReceiptOccupiesPendingAndRevokedPaymentIsRejected() {
        seedAndSettle();
        escrowService.registerDispute(dispute("DSP-OCC", List.of("US"), 1, 2));
        assertEquals(10000L, conservation().get("physical_balance_minor"));

        escrowService.registerArbitration(new EscrowService.ArbitrationInput(
                "ARB-OCC", "DSP-OCC", List.of("US"),
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"),
                "RELEASE", null, "COURT-OCC", "US 二月释放", "legal-wang"));
        escrowService.executeArbitration("ARB-OCC", "legal-wang");

        // 生成托管付款文件但不确认回执：待付占用 5000，另一路解冻无法对同一笔款再支付
        var run = paymentService.generateEscrowPaymentRun("ESCROW-USD", "legal-wang");
        var detail = queryService.paymentRun((String) run.get("run_no"));
        @SuppressWarnings("unchecked")
        Map<String, Object> req = ((List<Map<String, Object>>) detail.get("requests")).get(0);
        assertEquals(5000L, conservation().get("pending_payment_minor"));

        // 同一冻结单元的待付占用使第二次处置无可支配余额（防重复支付）
        long duplicateCommitted = sum("select coalesce(sum(amount_minor - settled_minor),0) "
                + "from arbitration_execution_line where escrow_freeze_id in "
                + "(select id from escrow_freeze where territory='US' and usage_date < date '2026-03-01') "
                + "and action in ('RELEASE','TRANSFER')");
        assertEquals(5000, duplicateCommitted);

        // 撤回裁决 → 未确认付款请求被暂停，待付释放回托管
        var revoke = escrowService.revokeArbitration("ARB-OCC", "暂缓执行", "legal-wang");
        assertEquals(5000, revoke.suspendedMinor());
        assertEquals("SUSPENDED", jdbc.queryForObject(
                "select status from payment_request where request_id=?", String.class, req.get("request_id")));
        assertEquals(0L, conservation().get("pending_payment_minor"));
        assertEquals(10000L, conservation().get("available_balance_minor"));

        // 已暂停的请求再收到回执必须被拒，不能确认付款
        var lateReceipt = paymentService.importReceipts(
                "receipt_ref,request_id_ref,account_ref,amount_minor,currency\n"
                        + "ERCPT-X," + req.get("request_id") + "," + req.get("account_snapshot") + ","
                        + req.get("amount_minor") + ",USD\n", "late.csv");
        assertEquals(0, lateReceipt.matched());
        assertEquals("SUSPENDED", lateReceipt.failures().get(0).get("match_status"));
        // 没有产生任何释放分录，资金仍完整在托管
        assertEquals(0, sum("select count(*) from escrow_entry where entry_type in ('RELEASE','TRANSFER_OUT')"));
        assertEquals(true, conservation().get("conservation_ok"));
    }

    @Test
    void disputeWithdrawalAutoUnfreezesButKeepsOtherDisputeCoverage() {
        seedAndSettle();
        // 争议 A 覆盖 US，争议 B 覆盖 US+EU（US 重叠、EU 独立）
        escrowService.registerDispute(dispute("DSP-W1", List.of("US"), 1, 2));
        escrowService.registerDispute(dispute("DSP-W2", List.of("US", "EU"), 1, 2));
        assertEquals(20000L, conservation().get("physical_balance_minor"));

        // 撤回争议 A：US 仍被争议 B 覆盖不能解冻，仅无其他争议覆盖的部分才释放；此处 US 全被 B 覆盖
        var w = escrowService.withdrawDispute("DSP-W1", "争议双方和解", "legal-wang");
        assertEquals(0, w.releasedMinor(), "US 仍被争议 B 覆盖，不得解冻");
        assertEquals(20000L, conservation().get("physical_balance_minor"), "托管资金不变");

        // 再撤回争议 B：US+EU 均无其他现行争议，全部自动解冻回结算流
        var w2 = escrowService.withdrawDispute("DSP-W2", "全部和解", "legal-wang");
        assertEquals(20000, w2.releasedMinor());
        Map<String, Object> c = conservation();
        assertEquals(0L, c.get("physical_balance_minor"));
        assertEquals(20000L, c.get("net_released_minor"));
        assertEquals(true, c.get("conservation_ok"));
        // 历史 FREEZE_IN 与 RELEASE 分录都保留，不删除（8 个冻结单元各一条释放）
        assertEquals(8, sum("select count(*) from escrow_entry where entry_type='FREEZE_IN'"));
        assertEquals(8, sum("select count(*) from escrow_entry where entry_type='RELEASE'"));
        // 原结算应付冻结解除，普通待付恢复（可被普通付款运行拾取）
        assertEquals(0, sum("select coalesce(sum(frozen_minor),0) from payable where source_kind='SETTLEMENT'"));
    }
}
