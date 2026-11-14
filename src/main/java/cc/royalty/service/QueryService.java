package cc.royalty.service;

import cc.royalty.domain.*;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.PaymentRepository;
import cc.royalty.repo.ReportRepository;
import cc.royalty.repo.SettlementRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读查询：工作台清单、应收钻取、守恒汇总与挂账解释 */
@Service
public class QueryService {

    private final SettlementRepository settlements;
    private final ReportRepository reports;
    private final CatalogRepository catalog;
    private final PaymentRepository payments;

    public QueryService(SettlementRepository settlements, ReportRepository reports,
                        CatalogRepository catalog, PaymentRepository payments) {
        this.settlements = settlements;
        this.reports = reports;
        this.catalog = catalog;
        this.payments = payments;
    }

    /** 已计算待审清单 */
    public List<Map<String, Object>> calculatedPendingReview() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettlementBatch b : settlements.allBatches()) {
            if (!List.of("CALCULATED", "IN_REVIEW").contains(b.status())) {
                continue;
            }
            List<SettlementLine> lines = settlements.linesOfBatch(b.id());
            Map<String, Object> m = baseBatchView(b, lines);
            m.put("review_hold_lines", lines.stream().filter(l -> "REVIEW_HOLD".equals(l.status())).map(l -> {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("settlement_line_id", l.id());
                lm.put("report_line_id", l.reportLineId());
                lm.put("right_type", l.rightType());
                lm.put("distributable_minor", l.distributableMinor());
                lm.put("conflict_flag", l.conflictFlag());
                lm.put("hold_reason", l.holdReason());
                lm.put("conflict_detail", l.conflictDetail());
                return lm;
            }).toList());
            out.add(m);
        }
        return out;
    }

    /** 已批准待付清单 */
    public List<Map<String, Object>> approvedPendingPayment() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettlementBatch b : settlements.allBatches()) {
            if (!List.of("APPROVED", "PAYMENT_CREATED", "CLOSED").contains(b.status())) {
                continue;
            }
            List<Payable> payables = settlements.payablesOfBatch(b.id()).stream()
                    .filter(p -> p.outstanding() > 0).toList();
            if (payables.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("settlement_batch_id", b.id());
            m.put("batch_no", b.batchNo());
            m.put("status", b.status());
            m.put("payables", payables.stream().map(p -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("payable_id", p.id());
                pm.put("payee_id", p.payeeId());
                pm.put("payee_name", catalog.findPayeeById(p.payeeId()).map(Payee::payeeName).orElse("?"));
                pm.put("right_type", p.rightType());
                pm.put("currency", p.currency());
                pm.put("amount_minor", p.amountMinor());
                pm.put("paid_minor", p.paidMinor());
                pm.put("outstanding_minor", p.outstanding());
                pm.put("status", p.status());
                return pm;
            }).toList());
            out.add(m);
        }
        return out;
    }

    /** 回执核验失败清单 */
    public List<Map<String, Object>> receiptFailures(Long platformId) {
        return payments.failedReceipts(platformId).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("receipt_ref", r.receiptRef());
            m.put("request_id_ref", r.requestIdRef());
            m.put("account_ref", r.accountRef());
            m.put("amount_minor", r.amountMinor());
            m.put("currency", r.currency());
            m.put("match_status", r.matchStatus());
            m.put("mismatch_reason", r.mismatchReason());
            m.put("source_file", r.sourceFile());
            return m;
        }).toList();
    }

    /** 批次汇总（守恒视图） */
    public Map<String, Object> batchSummary(long batchId) {
        SettlementBatch b = settlements.findBatchById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        List<SettlementLine> lines = settlements.linesOfBatch(batchId);
        Map<String, Object> m = baseBatchView(b, lines);
        long payableTotal = settlements.payablesOfBatch(batchId).stream()
                .mapToLong(Payable::amountMinor).sum();
        long paidTotal = settlements.payablesOfBatch(batchId).stream()
                .mapToLong(Payable::paidMinor).sum();
        List<HoldBalance> holds = reports.holdsOfBatch(batchId);
        Map<String, Long> holdsByType = new LinkedHashMap<>();
        for (HoldBalance h : holds) {
            holdsByType.merge(h.balanceType(), h.amountMinor(), Long::sum);
        }
        m.put("payable_total_minor", payableTotal);
        m.put("paid_total_minor", paidTotal);
        m.put("holds_by_type_minor", holdsByType);
        m.put("holds_detail", holds.stream().map(h -> {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("balance_type", h.balanceType());
            hm.put("right_type", h.rightType());
            hm.put("amount_minor", h.amountMinor());
            hm.put("currency", h.currency());
            hm.put("reason", h.reason());
            hm.put("report_line_id", h.reportLineId());
            return hm;
        }).toList());
        m.put("conservation_identity",
                m.get("distributable_total_minor") + " == 应付 " + payableTotal
                        + " + 挂账 " + holdsByType.values().stream().mapToLong(Long::longValue).sum());
        return m;
    }

    /** 权利人应收展开到合同与平台原行 */
    public Map<String, Object> payeeReceivable(long payeeId) {
        Payee payee = catalog.findPayeeById(payeeId)
                .orElseThrow(() -> new IllegalArgumentException("权利人不存在"));
        List<Map<String, Object>> receivables = new ArrayList<>();
        for (SettlementBatch b : settlements.allBatches()) {
            for (Payable p : settlements.payablesOfBatch(b.id()).stream()
                    .filter(x -> x.payeeId() == payeeId).toList()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("settlement_batch_no", b.batchNo());
                pm.put("right_type", p.rightType());
                pm.put("currency", p.currency());
                pm.put("amount_minor", p.amountMinor());
                pm.put("paid_minor", p.paidMinor());
                pm.put("outstanding_minor", p.outstanding());
                pm.put("status", p.status());
                List<Map<String, Object>> drill = new ArrayList<>();
                for (SettlementLine sl : settlements.linesOfBatch(b.id())) {
                    for (SettlementAllocation a : settlements.allocationsOfLine(sl.id())) {
                        if (payeeId == (a.payeeId() == null ? -1 : a.payeeId())
                                && "ALLOCATED".equals(a.allocStatus())) {
                            Map<String, Object> am = new LinkedHashMap<>();
                            am.put("settlement_line_id", sl.id());
                            am.put("amount_minor", a.amountMinor());
                            am.put("share", a.shareNumerator() + "/" + a.shareDenominator());
                            am.put("contract_id", a.contractId());
                            am.put("contract", a.contractId() == null ? null
                                    : catalog.findContractById(a.contractId())
                                    .map(c -> Map.of("contract_code", c.contractCode(),
                                            "right_type", c.rightType(), "territory", c.territory(),
                                            "media", c.media(), "valid_from", String.valueOf(c.validFrom()),
                                            "valid_to", String.valueOf(c.validTo()))).orElse(null));
                            ReportLine rl = reports.findLineById(sl.reportLineId()).orElse(null);
                            if (rl != null) {
                                ReportBatch rb = reports.findBatchById(rl.batchId()).orElse(null);
                                Map<String, Object> rm = new LinkedHashMap<>();
                                rm.put("batch_ref", rb == null ? null : rb.batchRef());
                                rm.put("platform_line_no", rl.platformLineNo());
                                rm.put("revision_no", rl.revisionNo());
                                rm.put("platform_item_code", rl.platformItemCode());
                                rm.put("usage_date", String.valueOf(rl.usageDate()));
                                rm.put("territory", rl.territory());
                                rm.put("media", rl.media());
                                rm.put("usage_count", rl.usageCount());
                                rm.put("net_minor", rl.netMinor());
                                am.put("platform_original_line", rm);
                            }
                            drill.add(am);
                        }
                    }
                }
                pm.put("drilldown", drill);
                receivables.add(pm);
            }
        }
        return Map.of("payee", Map.of("payee_id", payee.id(), "payee_code", payee.payeeCode(),
                "payee_name", payee.payeeName()), "receivables", receivables);
    }

    public List<Map<String, Object>> allBatches() {
        return settlements.allBatches().stream()
                .map(b -> baseBatchView(b, settlements.linesOfBatch(b.id()))).toList();
    }

    /** 付款运行明细：请求标识、生成时账户快照、金额币种、文件路径与指纹（供制作/核对回执） */
    public Map<String, Object> paymentRun(String runNo) {
        PaymentRun run = payments.findRunByNo(runNo)
                .orElseThrow(() -> new IllegalArgumentException("付款运行不存在: " + runNo));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_no", run.runNo());
        m.put("settlement_batch_id", run.settlementBatchId());
        m.put("currency", run.currency());
        m.put("status", run.status());
        m.put("requests", payments.requestsOfRun(run.id()).stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("request_id", r.requestId());
            rm.put("payee_id", r.payeeId());
            rm.put("account_snapshot", r.accountSnapshot());
            rm.put("body_fingerprint", r.bodyFingerprint());
            rm.put("amount_minor", r.amountMinor());
            rm.put("currency", r.currency());
            rm.put("status", r.status());
            return rm;
        }).toList());
        m.put("files", payments.filesOfRun(run.id()));
        m.put("receipts", payments.receiptsOfRun(run.id()).stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("receipt_ref", r.receiptRef());
            rm.put("request_id_ref", r.requestIdRef());
            rm.put("amount_minor", r.amountMinor());
            rm.put("currency", r.currency());
            rm.put("match_status", r.matchStatus());
            rm.put("mismatch_reason", r.mismatchReason());
            return rm;
        }).toList());
        return m;
    }

    private Map<String, Object> baseBatchView(SettlementBatch b, List<SettlementLine> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlement_batch_id", b.id());
        m.put("batch_no", b.batchNo());
        m.put("batch_type", b.batchType());
        m.put("platform_id", b.platformId());
        m.put("period_start", String.valueOf(b.periodStart()));
        m.put("period_end", String.valueOf(b.periodEnd()));
        m.put("status", b.status());
        m.put("snapshot_fingerprint", b.contractSnapshotFingerprint());
        m.put("report_scope", b.reportScope());
        m.put("line_count", lines.size());
        m.put("distributable_total_minor", lines.stream().mapToLong(SettlementLine::distributableMinor).sum());
        m.put("allocated_total_minor", lines.stream().flatMap(l ->
                settlements.allocationsOfLine(l.id()).stream())
                .filter(a -> "ALLOCATED".equals(a.allocStatus()))
                .mapToLong(SettlementAllocation::amountMinor).sum());
        m.put("held_or_unclaimed_minor", lines.stream().flatMap(l ->
                settlements.allocationsOfLine(l.id()).stream())
                .filter(a -> !"ALLOCATED".equals(a.allocStatus()))
                .mapToLong(SettlementAllocation::amountMinor).sum());
        return m;
    }
}
