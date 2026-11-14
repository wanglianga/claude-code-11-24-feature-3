package cc.royalty.service;

import cc.royalty.common.DomainException;
import cc.royalty.common.Fingerprints;
import cc.royalty.common.Rational;
import cc.royalty.domain.*;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.ReportRepository;
import cc.royalty.repo.SettlementRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结算引擎（写侧）。
 * 分段处理、每段独立事务，中断后可续算未处理分段；
 * 同一结算范围有唯一锁，禁止两个执行者重复出账。
 * reportBatchIds 为空表示平台+周期全范围；非空时只结算指定报告批次
 * （晚到更正/撤销形成的新计算批次用不同 scope_key，不会与原件批次互锁）。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlements;
    private final ReportRepository reports;
    private final CatalogRepository catalog;
    private final IncomeCalculator incomeCalculator;
    private final ShareResolver shareResolver;
    private final SnapshotService snapshotService;
    private final ScopeLockManager scopeLockManager;
    private final TransactionTemplate tx;

    @Value("${app.settlement.segment-size:200}")
    private int segmentSize;

    public SettlementService(SettlementRepository settlements, ReportRepository reports,
                             CatalogRepository catalog, IncomeCalculator incomeCalculator,
                             ShareResolver shareResolver, SnapshotService snapshotService,
                             ScopeLockManager scopeLockManager, TransactionTemplate tx) {
        this.settlements = settlements;
        this.reports = reports;
        this.catalog = catalog;
        this.incomeCalculator = incomeCalculator;
        this.shareResolver = shareResolver;
        this.snapshotService = snapshotService;
        this.scopeLockManager = scopeLockManager;
        this.tx = tx;
    }

    public record CreateResult(long batchId, String batchNo, long totalLines, long processedLines,
                               String status, boolean resumed) {
    }

    public String scopeKey(Long platformId, LocalDate from, LocalDate to, List<Long> reportBatchIds) {
        String batches = (reportBatchIds == null || reportBatchIds.isEmpty())
                ? "ALL" : reportBatchIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return "P:" + (platformId == null ? "ALL" : platformId) + ":" + from + ":" + to + ":B:" + batches;
    }

    /** 创建结算批次并逐段计算；任一段失败都保留现场，可用 resume 续算 */
    public CreateResult createAndCalculate(Long platformId, LocalDate from, LocalDate to,
                                           List<Long> reportBatchIds, String actor) {
        List<Long> batchIds = normalize(reportBatchIds);
        String scope = scopeKey(platformId, from, to, batchIds);
        if (!scopeLockManager.tryAcquire(scope, 0, actor)) {
            Optional<Long> existing = settlements.batchIdForScope(scope);
            throw new DomainException("结算范围 " + scope + " 已被批次 "
                    + existing.map(String::valueOf).orElse("?") + " 占用，禁止重复出账");
        }
        long batchId = tx.execute(status -> settlements.insertBatch("TMP", "USAGE", platformId,
                from, to, scope, actor,
                "platform=" + platformId + ",period=" + from + ".." + to + ",reportBatches=" + scope));
        String batchNo = "SB" + String.format("%08d", batchId);
        settlements.updateBatchNo(batchId, batchNo);
        scopeLockManager.bindBatch(scope, batchId);
        runSegments(batchId, platformId, from, to, batchIds);
        SettlementBatch b = settlements.findBatchById(batchId).orElseThrow();
        long total = reports.countActiveLinesForSettlement(platformId, from.toString(), to.toString(), batchIds);
        long done = settlements.processedReportLines(batchId).size();
        return new CreateResult(batchId, batchNo, total, done, b.status(), false);
    }

    /** 中断后续算未处理分段 */
    public CreateResult resume(long batchId, String actor) {
        SettlementBatch b = settlements.findBatchById(batchId)
                .orElseThrow(() -> new DomainException("结算批次不存在"));
        if (!"CREATED".equals(b.status())) {
            throw new DomainException("批次状态为 " + b.status() + "，无需续算");
        }
        List<Long> batchIds = parseBatchIds(b.reportScope());
        // runSegments 末尾会在全部处理完时调用 finishIfComplete 完成状态翻转
        runSegments(batchId, b.platformId(), b.periodStart(), b.periodEnd(), batchIds);
        SettlementBatch refreshed = settlements.findBatchById(batchId).orElseThrow();
        long total = reports.countActiveLinesForSettlement(b.platformId(),
                b.periodStart().toString(), b.periodEnd().toString(), batchIds);
        return new CreateResult(batchId, b.batchNo(), total,
                settlements.processedReportLines(batchId).size(),
                refreshed.status(), true);
    }

    private static List<Long> normalize(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().sorted().distinct().toList();
    }

    private static List<Long> parseBatchIds(String reportScope) {
        if (reportScope == null || !reportScope.contains("reportBatches=")) {
            return List.of();
        }
        String tail = reportScope.substring(reportScope.indexOf("reportBatches=") + "reportBatches=".length());
        if (tail.endsWith(":B:ALL") || "ALL".equals(tail)) {
            return List.of();
        }
        String raw = tail.contains(":B:") ? tail.substring(tail.indexOf(":B:") + 3) : tail;
        if ("ALL".equals(raw) || raw.isBlank()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            out.add(Long.parseLong(p.trim()));
        }
        return out;
    }

    private enum SettlementStatus {DONE, PENDING}

    private void runSegments(long batchId, Long platformId, LocalDate from, LocalDate to,
                             List<Long> reportBatchIds) {
        long total = reports.countActiveLinesForSettlement(platformId,
                from.toString(), to.toString(), reportBatchIds);
        Set<Long> processed = new java.util.HashSet<>(settlements.processedReportLines(batchId));
        // 跨批次去重：已在其他批次出账过的原行不再二次分配
        Set<Long> globallySettled = settlements.globallySettledLineIds(batchId);
        long offset = 0;
        while (offset < total) {
            List<ReportLine> page = reports.activeLinesForSettlement(platformId,
                    from.toString(), to.toString(), reportBatchIds, offset, segmentSize);
            if (page.isEmpty()) {
                break;
            }
            List<ReportLine> todo = page.stream()
                    .filter(l -> !processed.contains(l.id()))
                    .filter(l -> !globallySettled.contains(l.id()))
                    .toList();
            if (!todo.isEmpty()) {
                int seg = (int) (offset / segmentSize) + 1;
                tx.executeWithoutResult(s -> processSegment(batchId, platformId, todo, seg));
                processed.clear();
                processed.addAll(settlements.processedReportLines(batchId));
            }
            offset += page.size();
        }
        finishIfComplete(batchId, platformId, from, to, reportBatchIds);
    }

    private SettlementStatus finishIfComplete(long batchId, Long platformId, LocalDate from, LocalDate to,
                                               List<Long> reportBatchIds) {
        Set<Long> processed = settlements.processedReportLines(batchId);
        Set<Long> globallySettled = settlements.globallySettledLineIds(batchId);
        long remaining = reports.activeLinesForSettlement(platformId,
                        from.toString(), to.toString(), reportBatchIds, 0, Integer.MAX_VALUE).stream()
                .filter(l -> !processed.contains(l.id()))
                .filter(l -> !globallySettled.contains(l.id()))
                .count();
        if (remaining == 0 && "CREATED".equals(settlements.findBatchById(batchId).orElseThrow().status())) {
            assertBatchConservation(batchId);
            settlements.markCalculated(batchId, snapshotService.fingerprintForBatch(batchId));
            return SettlementStatus.DONE;
        }
        return SettlementStatus.PENDING;
    }

    /** 处理一段（独立事务） */
    private void processSegment(long batchId, long platformId, List<ReportLine> lines, int seg) {
        for (ReportLine line : lines) {
            String sourceFp = Fingerprints.sha256("line:" + line.id() + ":rev:" + line.revisionNo()
                    + ":" + line.rawText());
            Optional<PlatformMapping> mapping = catalog.findActiveMapping(platformId, line.platformItemCode());
            IncomeCalculator.LineIncome income = incomeCalculator.determine(line, platformId);

            if (income.budgets().isEmpty()) {
                // 无法确定可分配收入：原始上报款整体暂扣，不猜收款人
                long rawMoney = Math.max(line.netMinor() == null ? 0 : line.netMinor(),
                        line.grossMinor() == null ? 0 : line.grossMinor());
                long slId = insertLine(batchId, line, "RECORDING", null, null, "FULL",
                        rawMoney, income.totalFee(), "UNDETERMINED", null,
                        true, String.join("; ", income.problems()), "REVIEW_HOLD",
                        "无法确定可分配收入: " + String.join("; ", income.problems()), sourceFp, seg);
                settlements.insertAllocation(slId, null, null, "HELD", 0, 1, rawMoney);
                long holdId = reports.insertHold(batchId, line.id(), null, "WITHHOLD", rawMoney,
                        line.currency(), String.join("; ", income.problems()));
                reports.linkHoldAllocation(holdId, settlements.allocationsOfLine(slId).get(0).id());
                continue;
            }

            for (IncomeCalculator.RightBudget rb : income.budgets()) {
                Long recordingId = null;
                Long workId = null;
                if (mapping.isPresent() && mapping.get().recordingId() != null) {
                    recordingId = mapping.get().recordingId();
                    SoundRecording rec = catalog.findRecordingById(recordingId).orElse(null);
                    workId = rec == null ? null : rec.workId();
                }
                String allocationScope = "PER_UNIT_RATE".equals(rb.basis()) ? "FULL"
                        : ("RECORDING".equals(rb.rightType()) ? "RECORDING_BUDGET" : "PUBLISHING_BUDGET");

                if (mapping.isEmpty() || recordingId == null) {
                    // 无已审映射：进入待归属，款项保留余额
                    long slId = insertLine(batchId, line, rb.rightType(), null, workId, allocationScope,
                            rb.distributableMinor(), rb.feeMinor(), rb.basis(), rb.rateUsed(),
                            false, "无已审平台映射，待归属", "REVIEW_HOLD",
                            "平台编号 " + line.platformItemCode() + " 缺少已审核映射", sourceFp, seg);
                    long heldAllocId = settlements.insertAllocation(slId, null, null, "HELD", 0, 1, rb.distributableMinor());
                    long unmatchedHold = reports.insertHold(batchId, line.id(), rb.rightType(), "UNMATCHED",
                            rb.distributableMinor(), line.currency(),
                            "标题/作者/时长仅为候选，未经人工采用不归属");
                    reports.linkHoldAllocation(unmatchedHold, heldAllocId);
                    continue;
                }

                ShareResolver.Resolution r = shareResolver.resolve(rb.rightType(), workId, recordingId,
                        line.usageDate(), line.territory(), line.media(), rb.distributableMinor());

                if (r.conflict() && r.allocatedMinor() == 0 && r.conflictHoldMinor() == rb.distributableMinor()) {
                    // 份额合计 >100%：阻止该权利类型全部份额
                    long slId = insertLine(batchId, line, rb.rightType(), recordingId, workId, allocationScope,
                            rb.distributableMinor(), rb.feeMinor(), rb.basis(), rb.rateUsed(),
                            true, String.join("; ", r.reasons()), "REVIEW_HOLD",
                            "合同冲突，整笔暂扣", sourceFp, seg);
                    long conflictAllocId = settlements.insertAllocation(slId, null, null, "HELD", 0, 1, rb.distributableMinor());
                    long conflictHoldId = reports.insertHold(batchId, line.id(), rb.rightType(), "CONFLICT_HOLD",
                            rb.distributableMinor(), line.currency(), String.join("; ", r.reasons()));
                    reports.linkHoldAllocation(conflictHoldId, conflictAllocId);
                    continue;
                }

                long slId = insertLine(batchId, line, rb.rightType(), recordingId, workId, allocationScope,
                        rb.distributableMinor(), rb.feeMinor(), rb.basis(), rb.rateUsed(),
                        r.conflict(), String.join("; ", r.reasons()),
                        r.conflict() ? "REVIEW_HOLD" : "CALCULATED",
                        r.conflict() ? "存在争议份额，无争议部分继续结算" : null, sourceFp, seg);
                if (!r.contractIds().isEmpty()) {
                    settlements.updateChosenContracts(slId,
                            r.contractIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
                }

                long allocSum = 0;
                for (ShareResolver.PayeeResolution p : r.payees()) {
                    if (p.amountMinor() == 0) {
                        continue;
                    }
                    if (p.disputed()) {
                        long disputedAllocId = settlements.insertAllocation(slId, p.payeeId(), p.contractId(), "HELD",
                                p.weight().numerator().longValueExact(),
                                p.weight().denominator().longValueExact(), p.amountMinor());
                        long disputedHoldId = reports.insertHold(batchId, line.id(), rb.rightType(), "CONFLICT_HOLD",
                                p.amountMinor(), line.currency(), p.note());
                        reports.linkHoldAllocation(disputedHoldId, disputedAllocId);
                    } else {
                        settlements.insertAllocation(slId, p.payeeId(), p.contractId(), "ALLOCATED",
                                p.weight().numerator().longValueExact(),
                                p.weight().denominator().longValueExact(), p.amountMinor());
                        settlements.upsertPayable(batchId, p.payeeId(), rb.rightType(),
                                line.currency(), p.amountMinor());
                    }
                    allocSum += p.amountMinor();
                }
                if (r.unclaimedMinor() > 0) {
                    Rational missing = Rational.ONE.subtract(r.totalWeight());
                    long unclaimedAllocId = settlements.insertAllocation(slId, null, null, "UNCLAIMED",
                            missing.numerator().longValueExact(),
                            missing.denominator().longValueExact(), r.unclaimedMinor());
                    long unclaimedHoldId = reports.insertHold(batchId, line.id(), rb.rightType(), "UNCLAIMED",
                            r.unclaimedMinor(), line.currency(),
                            "权利份额合计不足 100%，剩余款待认领，不放大已知人比例");
                    reports.linkHoldAllocation(unclaimedHoldId, unclaimedAllocId);
                    allocSum += r.unclaimedMinor();
                }
                // 逐笔守恒硬断言：各分摊之和必须等于可分配额
                if (allocSum != rb.distributableMinor()) {
                    throw new DomainException("守恒校验失败: 结算行 " + slId + " 分摊合计 "
                            + allocSum + " != 可分配额 " + rb.distributableMinor());
                }
            }

            if (income.budgetUnclaimedMinor() > 0) {
                // 权利类型预算合计不足 100%：预算级剩余款待认领，词曲/录音都不能领走
                String basis = income.budgets().stream().findFirst()
                        .map(IncomeCalculator.RightBudget::basis).orElse("UNDETERMINED");
                long slId = insertLine(batchId, line, "UNBUDGETED", null, null, "FULL",
                        income.budgetUnclaimedMinor(), 0, basis, null, false,
                        "权利类型预算合计不足 100%", "REVIEW_HOLD",
                        "预算未覆盖余额，待明确预算分配后认领", sourceFp, seg);
                long budgetUnclaimedAlloc = settlements.insertAllocation(slId, null, null, "UNCLAIMED", 0, 1,
                        income.budgetUnclaimedMinor());
                long budgetHoldId = reports.insertHold(batchId, line.id(), null, "UNCLAIMED",
                        income.budgetUnclaimedMinor(), line.currency(),
                        "权利类型预算合计不足 100%，剩余款待认领，词曲与录音均不得领走整笔");
                reports.linkHoldAllocation(budgetHoldId, budgetUnclaimedAlloc);
            }
        }
    }

    private long insertLine(long batchId, ReportLine line, String rightType, Long recordingId, Long workId,
                            String allocationScope, long distributable, long fee, String basis,
                            java.math.BigDecimal rateUsed, boolean conflict, String detail,
                            String status, String holdReason, String sourceFp, int seg) {
        return settlements.insertLine(new SettlementLine(null, batchId, line.id(), rightType,
                recordingId, workId, line.usageDate(), line.territory(), line.media(), allocationScope,
                distributable, fee, basis, rateUsed, null, conflict, detail, status,
                holdReason, sourceFp, seg));
    }

    /** 批次级守恒：分摊 + 挂账台账两条路径各自闭合 */
    private void assertBatchConservation(long batchId) {
        List<SettlementLine> slines = settlements.linesOfBatch(batchId);
        long distSum = slines.stream().mapToLong(SettlementLine::distributableMinor).sum();
        long allocSum = 0;
        for (SettlementLine sl : slines) {
            long lineSum = settlements.allocationsOfLine(sl.id()).stream()
                    .mapToLong(SettlementAllocation::amountMinor).sum();
            if (lineSum != sl.distributableMinor()) {
                throw new DomainException("批次守恒失败: 结算行 " + sl.id() + " 分摊 " + lineSum
                        + " != 可分配 " + sl.distributableMinor());
            }
            allocSum += lineSum;
        }
        long holdLedger = reports.holdsOfBatch(batchId).stream()
                .mapToLong(HoldBalance::amountMinor).sum();
        long nonAllocated = 0;
        for (SettlementLine sl : slines) {
            nonAllocated += settlements.allocationsOfLine(sl.id()).stream()
                    .filter(a -> !"ALLOCATED".equals(a.allocStatus()))
                    .mapToLong(SettlementAllocation::amountMinor).sum();
        }
        if (holdLedger != nonAllocated) {
            throw new DomainException("挂账台账 " + holdLedger + " 与非应付分摊 " + nonAllocated + " 不一致");
        }
        if (allocSum != distSum) {
            throw new DomainException("批次总额守恒失败: " + allocSum + " != " + distSum);
        }
    }

    // ---------- 复核与批准（检查采用依据是否已被撤回） ----------

    public List<String> review(long batchId) {
        SettlementBatch b = settlements.findBatchById(batchId)
                .orElseThrow(() -> new DomainException("结算批次不存在"));
        List<String> problems = new ArrayList<>();
        String nowFp = snapshotService.fingerprintForBatch(batchId);
        if (b.contractSnapshotFingerprint() != null
                && !b.contractSnapshotFingerprint().equals(nowFp)) {
            problems.add("合同/目录/映射快照已变化（计算时 " + b.contractSnapshotFingerprint()
                    + "，当前 " + nowFp + "）");
        }
        for (SettlementLine sl : settlements.linesOfBatch(batchId)) {
            ReportLine rl = reports.findLineById(sl.reportLineId()).orElse(null);
            if (rl == null || !"NEW".equals(rl.usageStatus())) {
                problems.add("结算行 " + sl.id() + " 的原始报告行已被撤销或取代");
            }
            if (sl.recordingId() == null) {
                continue;
            }
            Long platformId = b.platformId();
            String itemCode = rl == null ? null : rl.platformItemCode();
            Optional<PlatformMapping> m = itemCode == null ? Optional.empty()
                    : catalog.findActiveMapping(platformId, itemCode);
            if (m.isEmpty() || "REVOKED".equals(m.get().status())
                    || !sl.recordingId().equals(m.get().recordingId())) {
                problems.add("结算行 " + sl.id() + " 的采用依据已被撤回或改派（平台编号 "
                        + itemCode + "）");
            }
            if (catalog.findRecordingById(sl.recordingId())
                    .filter(r -> "WITHDRAWN".equals(r.status())).isPresent()) {
                problems.add("结算行 " + sl.id() + " 的录音已撤出目录");
            }
        }
        if (!problems.isEmpty() && "CALCULATED".equals(b.status())) {
            settlements.updateStatus(batchId, "IN_REVIEW");
        }
        return problems;
    }

    public void approve(long batchId, String actor) {
        List<String> problems = review(batchId);
        if (!problems.isEmpty()) {
            throw new DomainException("复核未通过，禁止批准:\n- " + String.join("\n- ", problems));
        }
        SettlementBatch b = settlements.findBatchById(batchId).orElseThrow();
        if (!List.of("CALCULATED", "IN_REVIEW").contains(b.status())) {
            throw new DomainException("批次状态 " + b.status() + " 不可批准");
        }
        settlements.approve(batchId, actor);
        for (Payable p : settlements.payablesOfBatch(batchId)) {
            if ("CALCULATED".equals(p.status())) {
                settlements.updatePayableStatus(p.id(), "APPROVED");
            }
        }
    }
}
