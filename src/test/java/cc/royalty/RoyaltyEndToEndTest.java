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
 * 端到端场景：同名不同录音、季度中途转权、份额冲突、不足额待认领、
 * 坏行/重复行、晚到更正与撤销、幂等付款、回执核验失败、部分付款、差额单。
 */
@TestPropertySource(properties = "app.settlement.segment-size=3")
class RoyaltyEndToEndTest extends AbstractIntegrationTest {

    @Autowired private CatalogImportService catalogImport;
    @Autowired private ReportImportService reportImport;
    @Autowired private MappingService mappingService;
    @Autowired private SettlementService settlementService;
    @Autowired private PaymentService paymentService;
    @Autowired private QueryService queryService;

    private long payeeId(String code) {
        return jdbc.queryForObject("select id from payee where payee_code=?", Long.class, code);
    }

    private long payableAmount(long batchId, String payeeCode, String rightType) {
        Long v = jdbc.queryForObject("select coalesce(sum(amount_minor),0) from payable p join payee pe on pe.id=p.payee_id "
                + "where settlement_batch_id=? and pe.payee_code=? and right_type=?",
                Long.class, batchId, payeeCode, rightType);
        return v == null ? 0 : v;
    }

    private long holdAmount(long batchId, String type) {
        Long v = jdbc.queryForObject(
                "select coalesce(sum(amount_minor),0) from hold_balance where settlement_batch_id=? and balance_type=?",
                Long.class, batchId, type);
        return v == null ? 0 : v;
    }

    private long distributableTotal(long batchId) {
        return jdbc.queryForObject("select coalesce(sum(distributable_minor),0) from settlement_line where settlement_batch_id=?",
                Long.class, batchId);
    }

    private void seedCatalog() {
        catalogImport.importPayees(sample("payees.csv"));
        catalogImport.importWorks(sample("works.csv"));
        catalogImport.importContributors(sample("contributors.csv"));
        catalogImport.importRecordings(sample("recordings.csv"));
        catalogImport.importPlatforms(sample("platforms.csv"));
        catalogImport.importContracts(sample("contracts.csv"));
        catalogImport.importAgreements(sample("agreements.csv"));
        catalogImport.importMappings(sample("mappings.csv"));
    }

    @Test
    void fullRoyaltyLifecycle() {
        seedCatalog();

        // ---------- 1. 导入三个平台的季度报告 ----------
        var plfa = reportImport.importReport(sample("reports/q1_plfa.csv"), "q1_plfa.csv");
        var plfb = reportImport.importReport(sample("reports/q1_plfb.csv"), "q1_plfb.csv");
        var plfc = reportImport.importReport(sample("reports/q1_plfc.csv"), "q1_plfc.csv");
        assertEquals("MATCHED", plfa.reconciliation());
        assertEquals("MATCHED", plfb.reconciliation());
        assertEquals(7, plfa.valid());
        assertEquals(3, plfb.valid());

        // 坏行 + 重复行批次：声明 4 行，实际 2 有效 + 1 重复 + 1 坏行 → 转核对
        var recon = reportImport.importReport(sample("reports/recon_bad_dup.csv"), "recon.csv");
        assertEquals(2, recon.valid());
        assertEquals(1, recon.dup());
        assertEquals(1, recon.bad());
        assertEquals("MISMATCH", recon.reconciliation());
        assertEquals("RECONCILING",
                jdbc.queryForObject("select status from report_batch where id=?", String.class, recon.batchId()));

        // ---------- 2. 候选只生成候选，绝不自动归属 ----------
        mappingService.generateCandidates(plfa.batchId());
        long noMapCandidates = jdbc.queryForObject(
                "select count(*) from mapping_candidate mc join report_line rl on rl.id=mc.report_line_id "
                        + "where rl.platform_item_code='PLF-NO-MAP'", Long.class);
        assertTrue(noMapCandidates > 0, "标题相似应产生候选");
        assertTrue(mappingService.pendingMatchings(plfa.batchId()).stream()
                .anyMatch(m -> "PLF-NO-MAP".equals(m.get("platform_item_code"))));

        // 采用前预览影响金额（不落库）；最高相似候选应为 Original 而非 Live 版本
        long candidateId = jdbc.queryForObject(
                "select mc.id from mapping_candidate mc join report_line rl on rl.id=mc.report_line_id "
                        + "where rl.platform_item_code='PLF-NO-MAP' order by mc.score desc limit 1", Long.class);
        Map<String, Object> preview = mappingService.previewAdoption(candidateId);
        assertEquals(true, preview.get("conservation_check"));
        assertEquals("SR-ME-ORIG",
                jdbc.queryForObject("select recording_code from sound_recording where id=?",
                        String.class, jdbc.queryForObject(
                                "select recording_id from mapping_candidate where id=?", Long.class, candidateId)));
        // PROPOSED 预置映射不能被结算采信：PLF-UNKNOWN 仍在待匹配
        assertTrue(mappingService.pendingMatchings(plfb.batchId()).stream()
                .anyMatch(m -> "PLF-UNKNOWN".equals(m.get("platform_item_code"))));

        // ---------- 3. PLF-A 结算（按次计费，费用条款只扣一次；未采用行留待归属） ----------
        long platformA = jdbc.queryForObject("select id from platform where platform_code='PLF-A'", Long.class);
        var sa = settlementService.createAndCalculate(platformA,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), List.of(), "settler-zhang");
        long sbA = sa.batchId();
        assertEquals("CALCULATED", sa.status());

        // 总额守恒：123500 = 应付 91750 + 挂账 31750（RECONCILING 批次不参与结算）
        assertEquals(123500, distributableTotal(sbA));
        // 季度中途转权：1 月款给 RecordCo Alpha，3 月款给 RecordCo Beta
        assertEquals(20250, payableAmount(sbA, "P-REC-A", "RECORDING"));
        assertEquals(16500, payableAmount(sbA, "P-REC-B", "RECORDING"));
        // 词曲权收款人独立于录音权
        assertEquals(42500, payableAmount(sbA, "P-PUB-A", "PUBLISHING"));
        assertEquals(7500, payableAmount(sbA, "P-WRITER", "PUBLISHING"));
        assertEquals(5000, payableAmount(sbA, "P-PUB-B", "PUBLISHING"));
        // 冲突暂扣（Silver Street 争议 6000 + Cold Tide 整笔 9000）
        assertEquals(15000, holdAmount(sbA, "CONFLICT_HOLD"));
        // 不足 100% 的剩余款待认领（Over Top 2250+5000），不放大已知人
        assertEquals(7250, holdAmount(sbA, "UNCLAIMED"));
        // PLF-NO-MAP 未采用：50 次的两条权利预算（4500+5000）整体待归属，不猜收款人
        assertEquals(9500, holdAmount(sbA, "UNMATCHED"));
        // 未知款没有被分给看似合理的人：挂账总额闭合
        assertEquals(31750, holdAmount(sbA, "CONFLICT_HOLD") + holdAmount(sbA, "UNCLAIMED")
                + holdAmount(sbA, "UNMATCHED"));

        // 同一范围不可重复出账
        assertThrows(cc.royalty.common.DomainException.class, () -> settlementService.createAndCalculate(
                platformA, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), List.of(), "intruder"));

        // 结算后人工补采用 PLF-NO-MAP：已出账原行全系统不二次分配，新批次金额为 0
        var adopted = mappingService.adoptCandidate(candidateId, "settler-zhang");
        assertEquals("APPROVED", adopted.get("status"));
        var sa2 = settlementService.createAndCalculate(platformA,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"),
                List.of(plfa.batchId()), "settler-zhang");
        assertEquals(0, distributableTotal(sa2.batchId()),
                "同一平台原行已在旧批次出账，补采用不得产生重复款项");

        // ---------- 4. PLF-B 结算（直接报告收入，预算 60/40 分列） ----------
        long platformB = jdbc.queryForObject("select id from platform where platform_code='PLF-B'", Long.class);
        var sb = settlementService.createAndCalculate(platformB,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), List.of(plfb.batchId()), "settler-zhang");
        long sbB = sb.batchId();
        assertEquals(30000, distributableTotal(sbB));
        // B-001 录音预算 6000 全归 Beta（2 月已转权）；B-002 无争议部分 Beta 得 2000
        assertEquals(8000, payableAmount(sbB, "P-REC-B", "RECORDING"));
        assertEquals(4000, holdAmount(sbB, "CONFLICT_HOLD"));
        // 未采用映射的 PLF-UNKNOWN：10000 整体待归属，不猜收款人
        assertEquals(10000, holdAmount(sbB, "UNMATCHED"));

        // ---------- 5. PLF-C 结算（无净额按条款扣一次费；预算 90% 余 10% 待认领） ----------
        long platformC = jdbc.queryForObject("select id from platform where platform_code='PLF-C'", Long.class);
        var sc = settlementService.createAndCalculate(platformC,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), List.of(plfc.batchId()), "settler-zhang");
        long sbC = sc.batchId();
        // 10000 - 15% 费 = 8500/行；预算合计 90%，每行 850 待认领
        assertEquals(850 * 2, holdAmount(sbC, "UNCLAIMED"));
        // Cold Tide 录音份额 150% 冲突，整笔阻止（5100）
        assertEquals(5100, holdAmount(sbC, "CONFLICT_HOLD"));

        // ---------- 6. 复核与批准 ----------
        assertTrue(settlementService.review(sbA).isEmpty());
        settlementService.approve(sbA, "reviewer-li");
        settlementService.approve(sbB, "reviewer-li");
        assertEquals("APPROVED",
                jdbc.queryForObject("select status from settlement_batch where id=?", String.class, sbA));

        // ---------- 7. 付款文件：幂等重试、指纹固定 ----------
        var run1 = paymentService.generatePaymentRun(sbA, "USD", "settler-zhang");
        assertFalse(run1.reused());
        var run2 = paymentService.generatePaymentRun(sbA, "USD", "settler-zhang");
        assertTrue(run2.reused(), "重试必须复用原付款运行，不新发款");
        assertEquals(run1.fileFingerprint(), run2.fileFingerprint(), "文件内容不随后续重试改变");
        // 超时只查原标识
        String firstRequest = (String) ((List<?>) run1.requestIds()).get(0);
        var timeout = paymentService.queryTimeout(firstRequest);
        assertEquals("TIMEOUT_QUERIED", timeout.get("status"));

        // ---------- 8. 模拟回执：匹配、金额/币种/账户不符、部分付款 ----------
        Map<String, Object> runDetail = queryService.paymentRun(run1.runNo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requests = (List<Map<String, Object>>) runDetail.get("requests");
        assertEquals(5, requests.size());
        Map<String, Object> req0 = requests.get(0);
        Map<String, Object> req1 = requests.get(1);

        String goodReceipt = "receipt_ref,request_id_ref,account_ref,amount_minor,currency\n"
                + "RCPT-1," + req0.get("request_id") + "," + req0.get("account_snapshot") + ","
                + req0.get("amount_minor") + ",USD\n";
        var good = paymentService.importReceipts(goodReceipt, "receipts-1.csv");
        assertEquals(1, good.matched());
        assertTrue(good.failures().isEmpty());

        String badReceipts = String.join("\n",
                "receipt_ref,request_id_ref,account_ref,amount_minor,currency",
                // 金额不符
                "RCPT-2," + req1.get("request_id") + "," + req1.get("account_snapshot") + ",1,USD",
                // 币种不符
                "RCPT-3," + req1.get("request_id") + "," + req1.get("account_snapshot") + ","
                        + req1.get("amount_minor") + ",EUR",
                // 账户与生成时快照不符（即使现在改了账户，也按快照核对）
                "RCPT-4," + req1.get("request_id") + ",ACC-HACKED," + req1.get("amount_minor") + ",USD",
                // 不存在的付款标识
                "RCPT-5,RQNOPE,ACC-X,100,USD") + "\n";
        var bad = paymentService.importReceipts(badReceipts, "receipts-bad.csv");
        assertEquals(0, bad.matched());
        assertEquals(4, bad.failures().size());
        assertEquals(4, queryService.receiptFailures(platformA).size());

        // 部分付款：仅第一个应付已付，运行状态为部分确认，其余仍待付
        assertEquals("PARTIALLY_CONFIRMED",
                jdbc.queryForObject("select status from payment_run where id=?", String.class, run1.runId()));
        assertFalse(queryService.approvedPendingPayment().isEmpty());

        // ---------- 9. 晚到更正与使用量撤销 ----------
        var corr = reportImport.importReport(sample("reports/late_correction.csv"), "late.csv");
        assertEquals(2, corr.valid());
        assertEquals("SUPERSEDED",
                jdbc.queryForObject("select usage_status from report_line where batch_id=? and platform_line_no='B-001' "
                        + "and revision_no=1", String.class, plfb.batchId()));
        assertEquals("REVOKED",
                jdbc.queryForObject("select usage_status from report_line where batch_id=? and platform_line_no='B-002' "
                        + "and revision_no=1", String.class, plfb.batchId()));

        // 晚到更正形成新计算（独立范围锁，不覆盖原批次）
        var scorr = settlementService.createAndCalculate(platformB,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), List.of(corr.batchId()), "settler-zhang");
        assertEquals("CALCULATED", scorr.status());
        // 原批次复核现在必须暴露被撤销/取代的采用依据
        List<String> reviewProblems = settlementService.review(sbB);
        assertTrue(reviewProblems.stream().anyMatch(p -> p.contains("撤销或取代")));

        // 已批准季度的权利更正走差额单，旧批次金额不动
        long payablesBefore = jdbc.queryForObject(
                "select coalesce(sum(amount_minor),0) from payable where settlement_batch_id=?", Long.class, sbA);
        var adj = paymentService.createAdjustment(sbA, "季度结账后确认的词曲权更正", List.of(
                new PaymentService.AdjustmentLineInput("P-PUB-B", "PUBLISHING", 1200,
                        "PC-ME-1", null, "补计应归 Epsilon 的份额"),
                new PaymentService.AdjustmentLineInput("P-PUB-A", "PUBLISHING", -1200,
                        "PC-ME-1", null, "冲回 Gamma 多计部分")));
        var adjApproved = paymentService.approveAdjustment((String) adj.get("adjustment_no"), "reviewer-li");
        long adjustmentBatch = ((Number) adjApproved.get("new_settlement_batch_id")).longValue();
        assertEquals("ADJUSTMENT",
                jdbc.queryForObject("select batch_type from settlement_batch where id=?", String.class, adjustmentBatch));
        assertEquals(payablesBefore, jdbc.queryForObject(
                "select coalesce(sum(amount_minor),0) from payable where settlement_batch_id=?", Long.class, sbA));

        // ---------- 10. 权利人应收可展开到合同与平台原行 ----------
        var receivable = queryService.payeeReceivable(payeeId("P-REC-B"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receivables = (List<Map<String, Object>>) receivable.get("receivables");
        assertFalse(receivables.isEmpty());
        assertTrue(receivables.stream().flatMap(r -> ((List<Map<String, Object>>) r.get("drilldown")).stream())
                .anyMatch(d -> d.get("contract") != null && d.get("platform_original_line") != null));

        // ---------- 11. 撤回已被已批准批次采用的映射，复核必须拦截 ----------
        long usedMappingId = jdbc.queryForObject(
                "select id from platform_mapping where platform_id=? and platform_item_code='PLF-ME-ORIG' "
                        + "and status='APPROVED'", Long.class, platformB);
        mappingService.revokeMapping(usedMappingId, "reviewer-li", "目录关系存疑");
        assertTrue(settlementService.review(sbB).stream()
                .anyMatch(p -> p.contains("撤回或改派")));
    }

    @Test
    void settlementCanResumeAfterInterruption() {
        seedCatalog();
        var plfa = reportImport.importReport(sample("reports/q1_plfa.csv"), "q1_plfa.csv");
        mappingService.generateCandidates(plfa.batchId());
        long candidateId = jdbc.queryForObject(
                "select mc.id from mapping_candidate mc join report_line rl on rl.id=mc.report_line_id "
                        + "where rl.platform_item_code='PLF-NO-MAP' order by mc.score desc limit 1", Long.class);
        mappingService.adoptCandidate(candidateId, "settler-zhang");

        long platformA = jdbc.queryForObject("select id from platform where platform_code='PLF-A'", Long.class);
        var created = settlementService.createAndCalculate(platformA,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), List.of(), "settler-zhang");
        long batchId = created.batchId();
        assertEquals(123500, distributableTotal(batchId));

        // 模拟中断：清空该批次所有中间结果并退回 CREATED（锁保留），再走 resume 续算
        jdbc.update("delete from settlement_allocation where settlement_line_id in "
                + "(select id from settlement_line where settlement_batch_id=?)", batchId);
        jdbc.update("delete from settlement_line where settlement_batch_id=?", batchId);
        jdbc.update("delete from hold_balance where settlement_batch_id=?", batchId);
        jdbc.update("delete from payable where settlement_batch_id=?", batchId);
        jdbc.update("update settlement_batch set status='CREATED', contract_snapshot_fingerprint=null where id=?",
                batchId);

        // 续算后总额与一次跑完完全一致，且逐笔守恒通过、复核无问题
        var resumed = settlementService.resume(batchId, "settler-zhang");
        assertTrue(resumed.resumed());
        assertEquals("CALCULATED", resumed.status());
        assertEquals(123500, distributableTotal(batchId));
        assertTrue(settlementService.review(batchId).isEmpty());
    }
}
