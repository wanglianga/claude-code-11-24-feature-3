package cc.royalty.service;

import cc.royalty.common.Csv;
import cc.royalty.common.DomainException;
import cc.royalty.common.Fingerprints;
import cc.royalty.domain.*;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.EscrowRepository;
import cc.royalty.repo.PaymentRepository;
import cc.royalty.repo.SettlementRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * 付款与模拟银行回执（不访问真实银行）。
 * - 批准后生成付款文件：账户与正文指纹在生成时固定，重试不重新发款、文件内容不变；
 * - 超时只查询原付款标识；
 * - 回执必须金额与币种一致、账户与生成时快照一致才记已付；
 * - 已批准季度的权利更正走差额单，不改旧款。
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final SettlementRepository settlements;
    private final CatalogRepository catalog;
    private final EscrowRepository escrow;
    private final EscrowService escrowService;

    @Value("${app.storage.payment-dir:data/payment-files}")
    private String paymentDir;

    public PaymentService(PaymentRepository payments, SettlementRepository settlements,
                          CatalogRepository catalog, EscrowRepository escrow,
                          EscrowService escrowService) {
        this.payments = payments;
        this.settlements = settlements;
        this.catalog = catalog;
        this.escrow = escrow;
        this.escrowService = escrowService;
    }

    public record RunResult(long runId, String runNo, String currency, String status,
                            List<String> requestIds, String file, String fileFingerprint,
                            boolean reused) {
    }

    /** 生成（或幂等重试）付款文件。重试返回首次生成的不可变文件，绝不新发款 */
    @Transactional
    public RunResult generatePaymentRun(long batchId, String currency, String actor) {
        SettlementBatch batch = settlements.findBatchById(batchId)
                .orElseThrow(() -> new DomainException("结算批次不存在"));
        if (!List.of("APPROVED", "PAYMENT_CREATED", "CLOSED").contains(batch.status())) {
            throw new DomainException("批次未批准（当前 " + batch.status() + "），不能生成付款文件");
        }
        List<PaymentRun> existing = payments.runsOfBatch(batchId).stream()
                .filter(r -> r.currency().equals(currency)).toList();
        if (!existing.isEmpty()) {
            PaymentRun run = existing.get(0);
            List<PaymentRequest> reqs = payments.requestsOfRun(run.id());
            List<String> files = payments.filesOfRun(run.id());
            return new RunResult(run.id(), run.runNo(), currency, run.status(),
                    reqs.stream().map(PaymentRequest::requestId).toList(),
                    files.isEmpty() ? null : files.get(0),
                    files.isEmpty() ? null : fingerprintOf(files.get(0)), true);
        }

        List<Payable> payables = settlements.payablesOfBatch(batchId).stream()
                .filter(p -> "SETTLEMENT".equals(p.sourceKind()))
                .filter(p -> p.currency().equals(currency) && p.outstanding() > 0
                        && List.of("APPROVED", "PARTIALLY_PAID", "QUEUED").contains(p.status()))
                .toList();
        if (payables.isEmpty()) {
            throw new DomainException("该币种没有待付应付款");
        }

        String runNo = "PR" + String.format("%08d", batchId) + "-" + currency;
        long runId = payments.insertRun(runNo, batchId, currency, actor);

        List<String> bodyLines = new ArrayList<>();
        bodyLines.add("request_id,payee_code,payee_name,account_snapshot,amount_minor,currency,body_fingerprint");
        List<String> requestIds = new ArrayList<>();
        for (Payable p : payables) {
            Payee payee = catalog.findPayeeById(p.payeeId()).orElseThrow();
            long amount = p.outstanding();
            String accountSnapshot = payee.bankAccount() == null ? "NO-ACCOUNT" : payee.bankAccount();
            String canonicalBody = String.join("|", runNo, payee.payeeCode(), payee.payeeName(),
                    accountSnapshot, String.valueOf(amount), currency,
                    String.valueOf(p.id()), String.valueOf(p.rightType()));
            String bodyFp = Fingerprints.sha256(canonicalBody);
            // 确定性请求标识：重试同一应付款得到同一标识，不会产生新款
            String requestId = "RQ" + Fingerprints.sha256(canonicalBody).substring(0, 16).toUpperCase();
            payments.insertRequest(runId, p.id(), p.payeeId(), requestId, accountSnapshot,
                    bodyFp, amount, currency);
            settlements.updatePayableStatus(p.id(), "QUEUED");
            bodyLines.add(String.join(",", requestId, payee.payeeCode(), payee.payeeName(),
                    accountSnapshot, String.valueOf(amount), currency, bodyFp));
            requestIds.add(requestId);
        }

        String content = String.join("\n", bodyLines) + "\n";
        String fileName = runNo + ".csv";
        String filePath = writeFile(runNo, fileName, content);
        String fileFp = Fingerprints.sha256(content);
        payments.insertFile(runId, fileName, filePath, fileFp);
        settlements.updateStatus(batchId, "PAYMENT_CREATED");
        return new RunResult(runId, runNo, currency, "GENERATED", requestIds, filePath, fileFp, false);
    }

    /** 超时处理：只回查原付款标识与状态，不产生新付款请求 */
    public Map<String, Object> queryTimeout(String requestId) {
        PaymentRequest req = payments.findRequestByRequestId(requestId)
                .orElseThrow(() -> new DomainException("付款请求不存在: " + requestId));
        if ("ISSUED".equals(req.status())) {
            payments.updateRequestStatus(req.id(), "TIMEOUT_QUERIED");
        }
        PaymentRequest refreshed = payments.findRequestByRequestId(requestId).orElseThrow();
        return Map.of("request_id", refreshed.requestId(), "status", refreshed.status(),
                "amount_minor", refreshed.amountMinor(), "currency", refreshed.currency(),
                "account_snapshot", refreshed.accountSnapshot(),
                "note", "超时仅查询原标识，未重新发款");
    }

    public record ReceiptResult(int received, int matched, List<Map<String, Object>> failures) {
    }

    /** 导入模拟回执并核验：金额+币种+账户快照全部一致才记已付 */
    @Transactional
    public ReceiptResult importReceipts(String csvContent, String sourceFile) {
        int received = 0, matched = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Csv.Row row : Csv.parse(csvContent)) {
            received++;
            String receiptRef = row.get("receipt_ref");
            String requestId = row.get("request_id_ref");
            String accountRef = row.get("account_ref");
            long amount = Long.parseLong(row.get("amount_minor"));
            String currency = row.get("currency");

            String mismatch = null;
            Optional<PaymentRequest> reqOpt = payments.findRequestByRequestId(requestId);
            if (reqOpt.isEmpty()) {
                mismatch = "UNKNOWN_REQUEST";
            } else {
                PaymentRequest req = reqOpt.get();
                if ("SUSPENDED".equals(req.status())) {
                    // 裁决撤回已暂停该托管付款：回执不再可能匹配，防止另一路解冻重复支付
                    mismatch = "SUSPENDED";
                } else if (req.amountMinor() != amount) {
                    mismatch = "MISMATCH_AMOUNT";
                } else if (!req.currency().equals(currency)) {
                    mismatch = "MISMATCH_CURRENCY";
                } else if (accountRef != null && !accountRef.isBlank()
                        && !accountRef.equals(req.accountSnapshot())) {
                    // 旧账户回执按生成时资料核对：当前账户被修改也不影响
                    mismatch = "MISMATCH_ACCOUNT";
                } else if (payments.findReceiptByRef(receiptRef).isPresent()) {
                    mismatch = "DUPLICATE";
                }
            }

            if (mismatch == null) {
                PaymentRequest req = reqOpt.get();
                String reason = null;
                payments.insertReceipt(new PaymentReceipt(null, receiptRef, requestId, accountRef,
                        amount, currency, sourceFile, "MATCHED", reason));
                if (!"CONFIRMED".equals(req.status())) {
                    payments.confirmRequest(req.id());
                    settlements.addPaidAmount(req.payableId(), amount);
                    Payable p = settlements.findPayableById(req.payableId()).orElseThrow();
                    settlements.updatePayableStatus(p.id(),
                            p.paidMinor() >= p.amountMinor() ? "PAID" : "PARTIALLY_PAID");
                    // 托管付款：回执确认后资金才真正离开托管，按执行线 FIFO 出账并写释放/转付分录
                    if (req.isEscrowPayment()) {
                        escrowService.confirmEscrowPayment(
                                payments.findRequestByRequestId(requestId).orElseThrow());
                    }
                }
                matched++;
                refreshRunStatus(req.paymentRunId());
            } else {
                String reasonText = switch (mismatch) {
                    case "UNKNOWN_REQUEST" -> "找不到原付款请求 " + requestId;
                    case "MISMATCH_AMOUNT" -> "回执金额与请求金额不一致";
                    case "MISMATCH_CURRENCY" -> "回执币种与请求币种不一致";
                    case "MISMATCH_ACCOUNT" -> "回执账户与付款生成时账户快照不一致";
                    case "SUSPENDED" -> "付款请求已因裁决撤回暂停，回执不得确认";
                    case "DUPLICATE" -> "回执编号重复";
                    default -> mismatch;
                };
                payments.insertReceipt(new PaymentReceipt(null, receiptRef, requestId, accountRef,
                        amount, currency, sourceFile, mismatch, reasonText));
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("receipt_ref", receiptRef);
                f.put("request_id_ref", requestId);
                f.put("match_status", mismatch);
                f.put("reason", reasonText);
                failures.add(f);
            }
        }
        return new ReceiptResult(received, matched, failures);
    }

    private void refreshRunStatus(long runId) {
        List<PaymentRequest> reqs = payments.requestsOfRun(runId);
        boolean allConfirmed = reqs.stream().allMatch(r -> "CONFIRMED".equals(r.status()));
        boolean anyConfirmed = reqs.stream().anyMatch(r -> "CONFIRMED".equals(r.status()));
        payments.updateRunStatus(runId, allConfirmed ? "CONFIRMED"
                : anyConfirmed ? "PARTIALLY_CONFIRMED" : "GENERATED");
    }

    // ---------- 差额单：已批准季度的权利更正，不直接改旧款 ----------

    public record AdjustmentLineInput(String payeeCode, String rightType, long amountMinor,
                                      String priorContractCode, String newContractCode, String reason) {
    }

    @Transactional
    public Map<String, Object> createAdjustment(long sourceBatchId, String reason,
                                                List<AdjustmentLineInput> lines) {
        SettlementBatch source = settlements.findBatchById(sourceBatchId)
                .orElseThrow(() -> new DomainException("源结算批次不存在"));
        if (!List.of("APPROVED", "PAYMENT_CREATED", "CLOSED").contains(source.status())) {
            throw new DomainException("仅已批准季度可开立差额单");
        }
        String adjNo = "ADJ" + String.format("%08d", sourceBatchId) + "-"
                + (payments.adjustmentsOfBatch(sourceBatchId).size() + 1);
        long orderId = payments.insertAdjustmentOrder(adjNo, sourceBatchId, reason);
        long signedSum = 0;
        for (AdjustmentLineInput in : lines) {
            Long payeeId = in.payeeCode() == null ? null
                    : catalog.findPayeeByCode(in.payeeCode()).orElseThrow(() ->
                    new DomainException("权利人不存在: " + in.payeeCode())).id();
            Long priorId = in.priorContractCode() == null ? null
                    : catalog.findContractByCode(in.priorContractCode()).map(Contract::id).orElse(null);
            Long newId = in.newContractCode() == null ? null
                    : catalog.findContractByCode(in.newContractCode()).map(Contract::id).orElse(null);
            payments.insertAdjustmentLine(orderId, payeeId, in.rightType(), in.amountMinor(),
                    priorId, newId, in.reason());
            signedSum += in.amountMinor();
        }
        return Map.of("adjustment_no", adjNo, "order_id", orderId,
                "lines", lines.size(), "signed_sum_minor", signedSum,
                "note", "差额单仅记录更正；批准后另起 ADJUSTMENT 批次出账，旧批次金额不变");
    }

    @Transactional
    public Map<String, Object> approveAdjustment(String adjustmentNo, String actor) {
        AdjustmentOrder order = payments.findAdjustmentByNo(adjustmentNo)
                .orElseThrow(() -> new DomainException("差额单不存在"));
        if (!"DRAFT".equals(order.status())) {
            throw new DomainException("差额单状态为 " + order.status());
        }
        SettlementBatch source = settlements.findBatchById(order.sourceSettlementBatchId()).orElseThrow();
        String currency = settlements.payablesOfBatch(source.id()).stream()
                .map(Payable::currency).findFirst().orElse("USD");
        long newBatchId = settlements.insertBatch(adjustmentNo, "ADJUSTMENT", source.platformId(),
                source.periodStart(), source.periodEnd(),
                "ADJ:" + adjustmentNo, actor, "差额单 " + adjustmentNo + " 来源批次 " + source.batchNo());
        // 仅正向差额形成应付；负向冲减留痕于差额单，不修改旧批次应付
        payments.adjustmentLines(order.id()).stream()
                .filter(l -> ((Number) l.get("amount_minor")).longValue() > 0 && l.get("payee_id") != null)
                .forEach(l -> settlements.upsertPayable(newBatchId,
                        ((Number) l.get("payee_id")).longValue(), (String) l.get("right_type"),
                        currency, ((Number) l.get("amount_minor")).longValue()));
        payments.approveAdjustment(order.id(), newBatchId);
        settlements.approve(newBatchId, actor);
        return Map.of("adjustment_no", adjustmentNo, "new_settlement_batch_id", newBatchId,
                "status", "APPROVED");
    }

    // ---------- 托管付款：付款文件与托管释放分录绑定，回执未确认仍占待付额 ----------

    /**
     * 为托管账户生成付款文件（幂等）。只拾取 ESCROW_RELEASE/ESCROW_TRANSFER 应付，
     * 每个请求绑定触发它的托管分录与裁决；文件生成后回执未确认前应付仍占待付额，
     * 另一路解冻无法对同一笔款重复支付。
     */
    @Transactional
    public Map<String, Object> generateEscrowPaymentRun(String accountNo, String actor) {
        cc.royalty.domain.EscrowAccount account = escrow.findAccountByNo(accountNo)
                .orElseThrow(() -> new DomainException("托管账户不存在: " + accountNo));
        List<Payable> allDues = settlements.escrowSourcePayables().stream()
                .filter(p -> p.currency().equals(account.currency()) && p.outstanding() > 0
                        && List.of("APPROVED", "PARTIALLY_PAID", "QUEUED").contains(p.status()))
                .toList();
        if (allDues.isEmpty()) {
            throw new DomainException("托管账户 " + accountNo + " 当前没有待付的释放/转付款");
        }
        List<PaymentRun> runs = payments.escrowRunsOfAccount(account.id());
        // 已有运行覆盖的应付不再重复出款；新增待付（后续裁决产生）另起运行
        java.util.Set<Long> coveredPayableIds = runs.stream()
                .flatMap(r -> payments.requestsOfRun(r.id()).stream())
                .filter(r -> !"SUSPENDED".equals(r.status()) && r.payableId() != null)
                .map(PaymentRequest::payableId)
                .collect(java.util.stream.Collectors.toSet());
        List<Payable> dues = allDues;
        if (!runs.isEmpty()) {
            List<Payable> fresh = dues.stream()
                    .filter(p -> !coveredPayableIds.contains(p.id())).toList();
            if (fresh.isEmpty()) {
                PaymentRun run = runs.get(0);
                List<String> files = payments.filesOfRun(run.id());
                return Map.of("run_no", run.runNo(), "run_id", run.id(), "reused", true, "run_kind", "ESCROW",
                        "account_no", account.accountNo(), "status", run.status(),
                        "requests", payments.requestsOfRun(run.id()).stream()
                                .map(PaymentRequest::requestId).toList(),
                        "file", files.isEmpty() ? null : files.get(0));
            }
            dues = fresh;
        }
        long anchorBatch = dues.get(0).settlementBatchId();
        int seq = runs.size() + 1;
        String runNo = "ER" + String.format("%08d", account.id()) + "-" + account.currency()
                + (seq == 1 ? "" : "-" + seq);
        long runId = payments.insertEscrowRun(runNo, anchorBatch, account.id(), account.currency(), actor);

        List<String> bodyLines = new ArrayList<>();
        bodyLines.add("request_id,payee_code,payee_name,account_snapshot,amount_minor,currency,"
                + "source_kind,arbitration_id,body_fingerprint");
        List<String> requestIds = new ArrayList<>();
        for (Payable p : dues) {
            Payee payee = catalog.findPayeeById(p.payeeId()).orElseThrow();
            long amount = p.outstanding();
            String snapshot = payee.bankAccount() == null ? "NO-ACCOUNT" : payee.bankAccount();
            // 托管应付由裁决执行线产生；回执确认时才写释放分录并回填 payment_request_id
            Long arbId = escrow.arbitrationIdForPayable(p.id()).orElse(null);
            String canonical = String.join("|", runNo, payee.payeeCode(), snapshot,
                    String.valueOf(amount), account.currency(), String.valueOf(p.id()), p.sourceKind());
            String bodyFp = Fingerprints.sha256(canonical);
            String requestId = "ERQ" + Fingerprints.sha256(canonical).substring(0, 16).toUpperCase();
            payments.insertEscrowRequest(runId, p.id(), p.payeeId(), requestId, snapshot, bodyFp,
                    amount, account.currency(), null, arbId);
            settlements.updatePayableStatus(p.id(), "QUEUED");
            bodyLines.add(String.join(",", requestId, payee.payeeCode(), payee.payeeName(), snapshot,
                    String.valueOf(amount), account.currency(), p.sourceKind(),
                    String.valueOf(arbId), bodyFp));
            requestIds.add(requestId);
        }
        String content = String.join("\n", bodyLines) + "\n";
        String filePath = writeFile(runNo, runNo + ".csv", content);
        payments.insertFile(runId, runNo + ".csv", filePath, Fingerprints.sha256(content));
        return Map.of("run_no", runNo, "run_id", runId, "reused", false, "run_kind", "ESCROW",
                "account_no", account.accountNo(), "requests", requestIds, "file", filePath,
                "note", "回执确认前资金仍占托管待付额；裁决撤回会暂停未确认请求，不会重复支付");
    }

    private String writeFile(String runNo, String fileName, String content) {
        try {
            Path dir = Path.of(paymentDir, runNo);
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new DomainException("付款文件写入失败", e);
        }
    }

    private String fingerprintOf(String path) {
        try {
            return Fingerprints.sha256(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DomainException("付款文件读取失败", e);
        }
    }
}
