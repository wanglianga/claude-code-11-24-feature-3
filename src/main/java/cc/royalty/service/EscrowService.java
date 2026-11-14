package cc.royalty.service;

import cc.royalty.common.DomainException;
import cc.royalty.common.LargestRemainder;
import cc.royalty.common.Rational;
import cc.royalty.domain.*;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.EscrowRepository;
import cc.royalty.repo.PaymentRepository;
import cc.royalty.repo.ReportRepository;
import cc.royalty.repo.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 争议版税托管与裁决执行（实物资金口径）。
 *
 * 冻结：争议以 录音+权利类别 限定，声明地区/时段/比例，与结算分摊行求交得到实际冻结范围；
 *      比例拆分用最大余数法落到最小货币单位；重叠争议先求并集（escrow_freeze_cover），
 *      同一分摊行全系统至多一条冻结；已经付出的争议款不进托管，生成追回事项。
 * 裁决：只作用于声明的子范围；执行只产生托管待付（payable），不移动托管资金、不写释放分录；
 *      执行前预览可释放/应转付/仍被其他争议覆盖的部分；部分失败保留进度，重复执行不重复付款。
 * 出款：托管付款文件与释放分录绑定；回执确认资金才真正离开托管（RELEASE/TRANSFER_OUT），
 *      回执未确认前资金仍在托管余额内并占用待付，另一路解冻无法重复支付。
 * 撤回：不删除任何释放分录；未付暂停（资金本就仍在托管），已付列回收差额。
 * 守恒：托管入账 = 可支配余额 + 待付 + 已释放，逐账户硬断言。
 */
@Service
public class EscrowService {

    private final EscrowRepository escrow;
    private final SettlementRepository settlements;
    private final ReportRepository reports;
    private final PaymentRepository payments;
    private final CatalogRepository catalog;

    public EscrowService(EscrowRepository escrow, SettlementRepository settlements,
                         ReportRepository reports, PaymentRepository payments, CatalogRepository catalog) {
        this.escrow = escrow;
        this.settlements = settlements;
        this.reports = reports;
        this.payments = payments;
        this.catalog = catalog;
    }

    // ============================ 争议声明与冻结 ============================

    public record DisputeInput(String disputeNo, String recordingCode, String rightType,
                               List<String> territories, LocalDate periodStart, LocalDate periodEnd,
                               long ratioNumerator, long ratioDenominator,
                               String claimantCode, String respondentCode,
                               String legalRef, String reason, String actor) {
    }

    public record FreezeResult(String disputeNo, long frozenMinor, long clawbackMinor,
                               int freezeLines, int clawbackItems, List<Map<String, Object>> detail) {
    }

    /** 登记争议并立即按声明范围与结算项求交冻结（含历史全部批次） */
    @Transactional
    public FreezeResult registerDispute(DisputeInput in) {
        if (in.ratioNumerator() <= 0 || in.ratioDenominator() <= 0
                || in.ratioNumerator() > in.ratioDenominator()) {
            throw new DomainException("争议比例非法: " + in.ratioNumerator() + "/" + in.ratioDenominator());
        }
        if (in.periodEnd().isBefore(in.periodStart())) {
            throw new DomainException("争议时段结束日早于开始日");
        }
        if (in.disputeNo() != null && escrow.findDisputeByNo(in.disputeNo()).isPresent()) {
            throw new DomainException("争议编号已存在: " + in.disputeNo());
        }
        SoundRecording rec = catalog.findRecordingByCode(in.recordingCode())
                .orElseThrow(() -> new DomainException("录音不存在: " + in.recordingCode()));
        Long claimant = resolvePayee(in.claimantCode());
        Long respondent = resolvePayee(in.respondentCode());
        List<String> terrs = in.territories() == null || in.territories().isEmpty()
                ? List.of("GLOBAL") : in.territories().stream().distinct().toList();

        long disputeId = escrow.insertDispute(in.disputeNo() == null ? "TMP" : in.disputeNo(),
                rec.id(), in.rightType(), String.join(",", terrs), in.periodStart(), in.periodEnd(),
                in.ratioNumerator(), in.ratioDenominator(), claimant, respondent,
                in.legalRef(), in.reason(), in.actor() == null ? "legal" : in.actor());
        String disputeNo = "DSP" + String.format("%08d", disputeId);
        if (in.disputeNo() == null) {
            escrow.renameDispute(disputeId, disputeNo);
        } else {
            disputeNo = in.disputeNo();
        }

        FreezeResult result = applyFreeze(disputeId);
        return new FreezeResult(disputeNo, result.frozenMinor(), result.clawbackMinor(),
                result.freezeLines(), result.clawbackItems(), result.detail());
    }

    private Long resolvePayee(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return catalog.findPayeeByCode(code)
                .orElseThrow(() -> new DomainException("权利人不存在: " + code)).id();
    }

    /**
     * 争议与结算项求交（并集口径）：扫描新争议范围内的分摊行，
     * 该行的目标冻结比例取所有覆盖它的现行争议比例之并集（最大值），
     * 只补足差额，重叠争议不会对同一份额重复冻结；已付款部分转追回。
     */
    private FreezeResult applyFreeze(long disputeId) {
        DisputeCase newDispute = escrow.findDisputeById(disputeId).orElseThrow();
        List<DisputeCase> activeDisputes = escrow.activeDisputes();

        List<SettlementRepository.DisputeAllocRow> rows = settlements.allocationsForDisputeScope(
                newDispute.recordingId(), newDispute.rightType(),
                newDispute.periodStart(), newDispute.periodEnd(), newDispute.territories());

        long totalFrozen = 0, totalClawback = 0;
        int freezeLines = 0, clawbackItems = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        Map<String, Long> accountIdByCurrency = new LinkedHashMap<>();
        // 已付款部分按应付聚合追回（同一应付多个分摊行合并为一条追回，并受已付额上限约束）
        Map<Long, long[]> clawbackByPayable = new LinkedHashMap<>(); // payableId -> [amount]
        Map<Long, Payable> payableCache = new LinkedHashMap<>();

        for (SettlementRepository.DisputeAllocRow row : rows) {
            SettlementAllocation a = row.allocation();
            SettlementLine sl = row.line();
            // 覆盖该结算行的全部现行争议
            List<DisputeCase> covering = activeDisputes.stream()
                    .filter(d -> d.recordingId() == sl.recordingId())
                    .filter(d -> d.rightType().equals(sl.rightType()))
                    .filter(d -> d.coversTerritory(sl.territory()))
                    .filter(d -> d.coversDate(sl.usageDate()))
                    .toList();
            if (covering.isEmpty()) {
                continue;
            }
            // 并集比例 = 各争议主张比例的最大值；目标额按来源行原始金额计算，不随已冻结果进
            Rational maxRatio = covering.stream()
                    .map(d -> Rational.of(d.ratioNumerator(), d.ratioDenominator()))
                    .reduce(Rational.ZERO, (x, y) -> x.compareTo(y) >= 0 ? x : y);
            long target = Math.min(a.amountMinor(),
                    LargestRemainder.roundHalfUp(maxRatio.multiply(a.amountMinor())));
            // 已冻结 + 已追回都算已处置：重叠/重复争议对同一份额不二次处理
            long delta = target - a.frozenMinor() - a.clawedMinor();
            Optional<EscrowFreeze> existingFreeze = escrow.findFreezeByAllocation(a.id());
            // 即使并集未扩大冻结（delta<=0），也要在既有冻结上补登各争议的独立主张，
            // 这样撤回其中一个争议时能识别该份额仍被其他争议覆盖
            if (existingFreeze.isPresent()) {
                EscrowFreeze ef = existingFreeze.get();
                for (DisputeCase d : covering) {
                    long claim = Math.min(a.amountMinor(), LargestRemainder.roundHalfUp(
                            Rational.of(d.ratioNumerator(), d.ratioDenominator())
                                    .multiply(a.amountMinor())));
                    escrow.insertCover(ef.id(), d.id(), claim);
                    escrow.linkDispute(ef.accountId(), d.id());
                }
            }
            if (delta <= 0) {
                continue;
            }

            // 已付款部分不能进托管：按对应应付的可冻结额度切分，已付部分先归集，循环后统一追回
            long intoEscrow = delta;
            Long payableId = null;
            if ("ALLOCATED".equals(a.allocStatus()) && a.payeeId() != null) {
                Optional<Payable> payable = settlements.findPayable(sl.settlementBatchId(),
                        a.payeeId(), sl.rightType(), currencyOf(row));
                if (payable.isPresent()) {
                    Payable p = payable.get();
                    payableId = p.id();
                    payableCache.put(p.id(), p);
                    long payableFreezable = p.amountMinor() - p.paidMinor() - p.frozenMinor();
                    if (delta > payableFreezable) {
                        long clawback = delta - Math.max(0, payableFreezable);
                        intoEscrow = Math.max(0, payableFreezable);
                        // 分摊行级去重：同一份额的已付款只追回一次
                        settlements.addAllocationClawed(a.id(), clawback);
                        clawbackByPayable.merge(p.id(), new long[]{clawback},
                                (x, y) -> new long[]{x[0] + y[0]});
                    }
                }
            }

            Map<String, Object> lineDetail = new LinkedHashMap<>();
            lineDetail.put("settlement_allocation_id", a.id());
            lineDetail.put("report_line_id", sl.reportLineId());
            lineDetail.put("territory", sl.territory());
            lineDetail.put("usage_date", String.valueOf(sl.usageDate()));
            lineDetail.put("source_amount_minor", a.amountMinor());
            lineDetail.put("target_freeze_minor", target);
            lineDetail.put("covering_dispute_ids", covering.stream().map(DisputeCase::id).toList());

            if (intoEscrow > 0) {
                String ccy = currencyOf(row);
                long accountId = accountIdByCurrency.computeIfAbsent(ccy, this::ensureAccount);
                covering.forEach(d -> escrow.linkDispute(accountId, d.id()));
                long freezeId = upsertFreeze(accountId, row, intoEscrow, covering);
                settlements.addAllocationFrozen(a.id(), intoEscrow);
                if (row.holdBalanceId() != null) {
                    reports.addHoldFrozen(row.holdBalanceId(), intoEscrow);
                }
                if (payableId != null) {
                    settlements.addPayableFrozen(payableId, intoEscrow);
                }
                writeEntry(accountId, "FREEZE_IN", intoEscrow, ccy, freezeId, disputeId,
                        null, null, null, newDispute.legalRef(),
                        "争议 " + disputeId + " 冻结 " + sl.territory() + " " + sl.usageDate());
                totalFrozen += intoEscrow;
                freezeLines++;
                lineDetail.put("escrow_freeze_id", freezeId);
                lineDetail.put("frozen_minor", intoEscrow);
            }
            detail.add(lineDetail);
        }

        // 按应付合并追回，且同一应付累计追回不得超过其已付款
        for (Map.Entry<Long, long[]> e : clawbackByPayable.entrySet()) {
            Payable p = payableCache.get(e.getKey());
            long wanted = e.getValue()[0];
            long cap = p.paidMinor() - escrow.sumClawbackByPayable(p.id());
            long amount = Math.min(wanted, Math.max(0, cap));
            if (amount <= 0) {
                continue;
            }
            long cbId = createClawback(disputeId, p.settlementBatchId(), p.id(), p.payeeId(),
                    p.currency(), amount, "争议款已支付，不属托管现存资金: " + newDispute.reason());
            totalClawback += amount;
            clawbackItems++;
            detail.add(Map.of("payable_id", p.id(), "clawback_id", cbId, "clawback_minor", amount));
        }

        assertAccountConservation(accountIdByCurrency.values());
        return new FreezeResult(null, totalFrozen, totalClawback, freezeLines, clawbackItems, detail);
    }

    private String currencyOf(SettlementRepository.DisputeAllocRow row) {
        return reports.findLineById(row.line().reportLineId())
                .map(ReportLine::currency).orElse("USD");
    }

    /**
     * 并集冻结：同一分摊行已有冻结则追加金额并合并争议 id，否则新建冻结单元。
     * 每个覆盖争议记录其独立主张额（escrow_freeze_cover），实际冻结取并集，不重复。
     */
    private long upsertFreeze(long accountId, SettlementRepository.DisputeAllocRow row,
                              long delta, List<DisputeCase> covering) {
        SettlementAllocation a = row.allocation();
        SettlementLine sl = row.line();
        Optional<EscrowFreeze> existing = escrow.findFreezeByAllocation(a.id());
        long freezeId;
        List<Long> disputeIds;
        if (existing.isPresent()) {
            EscrowFreeze f = existing.get();
            disputeIds = new ArrayList<>(f.disputeIds());
            for (DisputeCase d : covering) {
                if (!disputeIds.contains(d.id())) {
                    disputeIds.add(d.id());
                }
            }
            escrow.appendFreeze(f.id(), delta, disputeIds);
            freezeId = f.id();
        } else {
            EscrowFreeze f = new EscrowFreeze(null, accountId, a.id(), row.holdBalanceId(),
                    sl.settlementBatchId(), sl.reportLineId(), sl.recordingId(), sl.rightType(),
                    a.payeeId(), sl.territory(), sl.usageDate(), currencyOf(row),
                    a.amountMinor(), delta,
                    covering.stream().map(DisputeCase::id).sorted().toList(), "FROZEN", 0, 0, null);
            freezeId = escrow.insertFreeze(f);
            disputeIds = f.disputeIds();
        }
        // 各争议独立主张额（幂等：同一 freeze+争议 不重复记）
        for (DisputeCase d : covering) {
            long claim = LargestRemainder.roundHalfUp(
                    Rational.of(d.ratioNumerator(), d.ratioDenominator()).multiply(a.amountMinor()));
            escrow.insertCover(freezeId, d.id(), Math.min(claim, a.amountMinor()));
        }
        return freezeId;
    }

    private long ensureAccount(String currency) {
        String accountNo = "ESCROW-" + currency;
        return escrow.findAccountByNo(accountNo)
                .map(EscrowAccount::id)
                .orElseGet(() -> escrow.insertAccount(accountNo, currency, "争议版税托管账户(" + currency + ")"));
    }

    private long createClawback(long disputeId, long batchId, long payableId, long payeeId,
                                String currency, long amount, String reason) {
        int seq = escrow.clawbacksOfDispute(disputeId).size() + 1;
        String no = "CB" + String.format("%08d", disputeId) + "-" + seq;
        try {
            return escrow.insertClawback(no, disputeId, batchId, payableId, payeeId,
                    currency, amount, reason);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            throw new DomainException("该争议对应付 " + payableId + " 已生成追回事项，不得重复追回");
        }
    }

    // ============================ 裁决预览 ============================

    public record PreviewLine(long escrowFreezeId, String territory, String usageDate,
                              long frozenMinor, long remainingMinor, long actionableMinor,
                              long remainMinor, List<Long> coveringDisputeIds,
                              Long payeeId, String note) {
    }

    public record ArbitrationPreview(String arbitrationNo, String outcome,
                                     long releasableMinor, long transferableMinor,
                                     long retainedMinor, List<PreviewLine> lines) {
    }

    /** 执行前预览：可释放、应转付、仍被其他现行争议覆盖的部分（不落库） */
    @Transactional(readOnly = true)
    public ArbitrationPreview previewArbitration(String arbitrationNo) {
        Arbitration arb = escrow.findArbitrationByNo(arbitrationNo)
                .orElseThrow(() -> new DomainException("裁决不存在: " + arbitrationNo));
        List<PreviewLine> lines = collectPreviewLines(arb);
        long actionable = lines.stream().mapToLong(PreviewLine::actionableMinor).sum();
        long retained = lines.stream().mapToLong(PreviewLine::remainMinor).sum();
        long releasable = "RELEASE".equals(arb.outcome()) ? actionable : 0;
        long transferable = "TRANSFER".equals(arb.outcome()) ? actionable : 0;
        return new ArbitrationPreview(arbitrationNo, arb.outcome(), releasable, transferable,
                retained, lines);
    }

    private List<PreviewLine> collectPreviewLines(Arbitration arb) {
        List<PreviewLine> out = new ArrayList<>();
        for (EscrowFreeze f : escrow.openFreezesOfDispute(arb.disputeId())) {
            if (f.recordingId() != arb.recordingId() || !f.rightType().equals(arb.rightType())) {
                continue;
            }
            if (!arb.coversTerritory(f.territory()) || !arb.coversDate(f.usageDate())) {
                continue; // 裁决只覆盖声明的子范围
            }
            EscrowRepository.CoverRow mine = escrow.coversOfFreeze(f.id()).stream()
                    .filter(c -> c.disputeId() == arb.disputeId()).findFirst().orElse(null);
            if (mine == null) {
                continue;
            }
            // 已被其他/本裁决建待付但尚未出款的占用必须扣除：检查当前裁决集合与资金余额，不重复处置
            long committedPending = escrow.committedPendingOfFreeze(f.id());
            long disposable = Math.max(0, f.remainingMinor() - committedPending);
            long actionable = mine.coveredMinor() - mine.consumedMinor();
            if ("RETAIN".equals(arb.outcome())) {
                actionable = 0;
            }
            actionable = Math.min(actionable, disposable);
            long remain = f.remainingMinor() - actionable;
            List<Long> covering = escrow.coversOfFreeze(f.id()).stream()
                    .filter(c -> c.disputeId() != arb.disputeId())
                    .map(EscrowRepository.CoverRow::disputeId)
                    .filter(id -> escrow.findDisputeById(id).map(DisputeCase::isActive).orElse(false))
                    .toList();
            String note = switch (arb.outcome()) {
                case "RELEASE" -> f.payeeId() == null
                        ? "无主挂账不能释放给个人，应转付受益人或继续保留"
                        : "可释放给原权利人 " + f.payeeId();
                case "TRANSFER" -> "应转付受益人 " + arb.beneficiaryPayeeId();
                default -> "裁决要求继续保留在托管";
            };
            if (!covering.isEmpty() && remain > 0) {
                note += "；剩余 " + remain + " 仍被争议 " + covering + " 覆盖";
            }
            out.add(new PreviewLine(f.id(), f.territory(), String.valueOf(f.usageDate()),
                    f.frozenAmountMinor(), f.remainingMinor(), actionable, remain, covering,
                    f.payeeId(), note));
        }
        return out;
    }

    // ============================ 裁决登记与执行 ============================

    public record ArbitrationInput(String arbitrationNo, String disputeNo, List<String> territories,
                                   LocalDate periodStart, LocalDate periodEnd, String outcome,
                                   String beneficiaryCode, String legalBasis, String note, String actor) {
    }

    @Transactional
    public Map<String, Object> registerArbitration(ArbitrationInput in) {
        DisputeCase d = escrow.findDisputeByNo(in.disputeNo())
                .orElseThrow(() -> new DomainException("争议不存在: " + in.disputeNo()));
        if (!d.isActive()) {
            throw new DomainException("争议已撤回，不能再登记裁决");
        }
        if (!List.of("RELEASE", "TRANSFER", "RETAIN").contains(in.outcome())) {
            throw new DomainException("裁决方式非法: " + in.outcome());
        }
        Long beneficiary = null;
        if ("TRANSFER".equals(in.outcome())) {
            if (in.beneficiaryCode() == null || in.beneficiaryCode().isBlank()) {
                throw new DomainException("转付裁决必须指定受益人");
            }
            beneficiary = catalog.findPayeeByCode(in.beneficiaryCode())
                    .orElseThrow(() -> new DomainException("受益人不存在: " + in.beneficiaryCode())).id();
        }
        List<String> terrs = in.territories() == null || in.territories().isEmpty()
                ? d.territories() : in.territories().stream().distinct().toList();
        LocalDate from = in.periodStart() == null ? d.periodStart() : in.periodStart();
        LocalDate to = in.periodEnd() == null ? d.periodEnd() : in.periodEnd();
        if (to.isBefore(from)) {
            throw new DomainException("裁决适用时段结束日早于开始日");
        }
        if (in.arbitrationNo() != null && escrow.findArbitrationByNo(in.arbitrationNo()).isPresent()) {
            throw new DomainException("裁决编号已存在: " + in.arbitrationNo());
        }
        long id = escrow.insertArbitration(in.arbitrationNo() == null ? "TMP" : in.arbitrationNo(),
                d.id(), d.recordingId(), d.rightType(), String.join(",", terrs), from, to,
                in.outcome(), beneficiary, in.legalBasis(), in.note(),
                in.actor() == null ? "legal" : in.actor());
        String no = in.arbitrationNo() == null ? "ARB" + String.format("%08d", id) : in.arbitrationNo();
        if (in.arbitrationNo() == null) {
            escrow.renameArbitration(id, no);
        }
        return Map.of("arbitration_id", id, "arbitration_no", no, "dispute_no", d.disputeNo(),
                "outcome", in.outcome(),
                "note", "先预览 /api/escrow/arbitrations/" + no + "/preview 再执行；执行只建待付，回执确认才出托管资金");
    }

    public record ExecutionResult(String arbitrationNo, String status, int attempt,
                                  long releasedMinor, long transferredMinor, long retainedMinor,
                                  int lines, List<Map<String, Object>> lineResults) {
    }

    /**
     * 执行裁决：按当前裁决集合与账户余额逐条处置，只产生托管待付、不动托管资金。
     * 同一裁决重复执行只补做未完成部分（cover.consumed 判重），不重复建应付、不重复付款。
     */
    @Transactional
    public ExecutionResult executeArbitration(String arbitrationNo, String actor) {
        Arbitration arb = escrow.findArbitrationByNo(arbitrationNo)
                .orElseThrow(() -> new DomainException("裁决不存在: " + arbitrationNo));
        if (!arb.isEffective()) {
            throw new DomainException("裁决已被撤销，不能执行");
        }
        int attempt = escrow.nextExecutionAttempt(arb.id());
        long execId = escrow.insertExecution(arb.id(), attempt, "PARTIAL", actor, "执行中");
        List<PreviewLine> preview = collectPreviewLines(arb);
        long released = 0, transferred = 0, retained = 0;
        int handled = 0;
        List<Map<String, Object>> lineResults = new ArrayList<>();
        boolean allDone = true;

        for (PreviewLine pl : preview) {
            EscrowFreeze f = escrow.findFreezeById(pl.escrowFreezeId()).orElseThrow();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("escrow_freeze_id", f.id());
            r.put("territory", f.territory());
            r.put("usage_date", String.valueOf(f.usageDate()));
            try {
                if ("RETAIN".equals(arb.outcome()) || pl.actionableMinor() == 0) {
                    escrow.insertExecutionLine(execId, f.id(), "REMAIN", pl.remainMinor(), null, null,
                            pl.coveringDisputeIds().isEmpty() ? null
                                    : pl.coveringDisputeIds().toString(), pl.note());
                    retained += pl.remainMinor();
                    r.put("action", "REMAIN");
                    r.put("amount_minor", pl.remainMinor());
                } else if ("RELEASE".equals(arb.outcome())) {
                    if (f.payeeId() == null) {
                        throw new DomainException("冻结 " + f.id() + " 是无主挂账，不能直接释放");
                    }
                    long amount = pl.actionableMinor();
                    Disposition d = doRelease(f, arb, amount);
                    escrow.insertExecutionLine(execId, f.id(), "RELEASE", amount, null, d.payableId(),
                            null, "释放给原权利人 " + f.payeeId());
                    released += amount;
                    r.put("action", "RELEASE");
                    r.put("amount_minor", amount);
                    r.put("payable_id", d.payableId());
                    handled++;
                } else { // TRANSFER
                    long amount = pl.actionableMinor();
                    Disposition d = doTransfer(f, arb, amount);
                    escrow.insertExecutionLine(execId, f.id(), "TRANSFER", amount, null, d.payableId(),
                            null, "转付受益人 " + arb.beneficiaryPayeeId());
                    transferred += amount;
                    r.put("action", "TRANSFER");
                    r.put("amount_minor", amount);
                    r.put("payable_id", d.payableId());
                    handled++;
                }
                if (pl.remainMinor() > 0) {
                    retained += pl.remainMinor();
                }
            } catch (Exception e) {
                // 部分失败：保留已成功进度，本线记失败原因，整体标记 PARTIAL 后可重试
                allDone = false;
                r.put("action", "FAILED");
                r.put("error", e.getMessage());
            }
            lineResults.add(r);
        }

        String status = allDone ? "COMPLETED" : "PARTIAL";
        escrow.updateExecutionStatus(execId, status);
        assertAllAccountsConservation();
        return new ExecutionResult(arbitrationNo, status, attempt, released, transferred,
                retained, handled, lineResults);
    }

    /** 一次处置的落账结果：托管应付（资金仍在托管，等待回执确认） */
    private record Disposition(Long payableId) {
    }

    /** 释放：原应付冻结额度转为托管释放待付；资金仍在托管，不写分录 */
    private Disposition doRelease(EscrowFreeze f, Arbitration arb, long amount) {
        escrow.addCoverConsumed(f.id(), arb.disputeId(), amount);
        Long payableId = null;
        if (f.payeeId() != null) {
            settlements.findPayable(f.settlementBatchId(), f.payeeId(), f.rightType(), f.currency())
                    .ifPresent(p -> settlements.releasePayableFrozen(p.id(), amount));
            payableId = settlements.upsertEscrowPayable(f.settlementBatchId(), f.payeeId(),
                    f.rightType(), f.currency(), amount, "ESCROW_RELEASE", arb.id());
        }
        return new Disposition(payableId);
    }

    /** 转付：原权利人应付冻结核销，形成受益人托管转付待付；资金仍在托管，不写分录 */
    private Disposition doTransfer(EscrowFreeze f, Arbitration arb, long amount) {
        escrow.addCoverConsumed(f.id(), arb.disputeId(), amount);
        if (f.payeeId() != null) {
            settlements.findPayable(f.settlementBatchId(), f.payeeId(), f.rightType(), f.currency())
                    .ifPresent(p -> settlements.consumePayableFrozen(p.id(), amount));
        }
        long payableId = settlements.upsertEscrowPayable(f.settlementBatchId(),
                arb.beneficiaryPayeeId(), f.rightType(), f.currency(), amount,
                "ESCROW_TRANSFER", arb.id());
        return new Disposition(payableId);
    }

    // ============================ 回执确认：资金真正离开托管 ============================

    /**
     * 托管付款回执确认后调用：按托管应付关联的执行线 FIFO 出款，
     * 释放来源侧冻结/挂账并写 RELEASE/TRANSFER_OUT 分录。幂等：重复回执不会重复出款。
     */
    @Transactional
    public void confirmEscrowPayment(PaymentRequest req) {
        if (req.payableId() == null) {
            return;
        }
        Payable payable = settlements.findPayableById(req.payableId()).orElseThrow();
        List<Map<String, Object>> lines = escrow.executionLinesByPayable(payable.id());
        long remaining = req.amountMinor();
        for (Map<String, Object> line : lines) {
            if (remaining <= 0) {
                break;
            }
            long lineAmount = ((Number) line.get("amount_minor")).longValue();
            long settled = ((Number) line.get("settled_minor")).longValue();
            long lineDue = lineAmount - settled;
            if (lineDue <= 0) {
                continue;
            }
            long part = Math.min(remaining, lineDue);
            long lineId = ((Number) line.get("id")).longValue();
            long freezeId = ((Number) line.get("escrow_freeze_id")).longValue();
            EscrowFreeze f = escrow.findFreezeById(freezeId).orElseThrow();
            boolean transfer = "TRANSFER".equals(line.get("action"));
            Long arbId = escrow.arbitrationIdForPayable(payable.id()).orElse(null);
            Arbitration arb = arbId == null ? null : escrow.findArbitrationById(arbId).orElse(null);
            Long disputeId = arb == null ? null : arb.disputeId();
            Long counterparty = transfer ? (arb == null ? null : arb.beneficiaryPayeeId()) : f.payeeId();
            String legalRef = arb == null ? null : arb.legalBasis();
            String arbNo = arb == null ? null : arb.arbitrationNo();

            // 来源侧解冻与台账出账
            settlements.releaseAllocationFrozen(f.settlementAllocationId(), part);
            if (transfer) {
                escrow.addTransferred(f.id(), part);
                if (f.holdBalanceId() != null) {
                    reports.reduceHold(f.holdBalanceId(), part);
                }
                writeEntry(f.accountId(), "TRANSFER_OUT", part, f.currency(), f.id(), disputeId, arbId,
                        req.id(), counterparty, legalRef,
                        "回执 " + req.requestId() + " 确认转付（裁决 " + arbNo + "）");
            } else {
                escrow.addReleased(f.id(), part);
                if (f.holdBalanceId() != null) {
                    reports.releaseHoldFrozen(f.holdBalanceId(), part);
                }
                writeEntry(f.accountId(), "RELEASE", part, f.currency(), f.id(), disputeId, arbId,
                        req.id(), counterparty, legalRef,
                        "回执 " + req.requestId() + " 确认释放（裁决 " + arbNo + "）");
            }
            escrow.addLineSettled(lineId, part);
            escrow.markFreezeProgress(f.id());
            remaining -= part;
        }
        if (remaining != 0) {
            throw new DomainException("托管付款 " + req.requestId() + " 出款分摊不平，剩余 " + remaining);
        }
        assertAllAccountsConservation();
    }

    private long writeEntry(long accountId, String type, long amount, String currency,
                            Long freezeId, Long disputeId, Long arbitrationId,
                            Long paymentRequestId, Long counterparty, String legalRef, String note) {
        long seq = escrow.nextEntrySeq(accountId);
        long balanceAfter = escrow.currentBalance(accountId)
                + (List.of("FREEZE_IN", "CLAWBACK_ADJ", "MANUAL_ADJ").contains(type) ? amount : -amount);
        EscrowEntry e = new EscrowEntry(null, accountId, seq, type, amount, currency, freezeId,
                disputeId, arbitrationId, paymentRequestId, counterparty, legalRef, note,
                balanceAfter, null);
        return escrow.insertEntry(e);
    }

    // ============================ 裁决撤回 ============================

    public record RevokeResult(String arbitrationNo, long suspendedMinor, long clawbackMinor,
                               int suspendedRequests, int clawbackItems) {
    }

    /**
     * 裁决撤回：不删除任何释放/转付分录（已出款不可改）。
     * 未出款的托管待付暂停并回滚到冻结状态（资金本就仍在托管，无资金分录）；
     * 已确认出款的部分列回收差额。冻结的 released/transferred 计数只在回执确认时递增，
     * 故未出款回滚只退争议覆盖额与应付，不动这两个计数。
     */
    @Transactional
    public RevokeResult revokeArbitration(String arbitrationNo, String reason, String actor) {
        Arbitration arb = escrow.findArbitrationByNo(arbitrationNo)
                .orElseThrow(() -> new DomainException("裁决不存在: " + arbitrationNo));
        if (!arb.isEffective()) {
            throw new DomainException("裁决已处于撤销状态，不能重复撤销");
        }
        long suspended = 0, clawbackAmt = 0;
        int suspendedReqs = 0, clawbacks = 0;
        java.util.Set<Long> handledPayables = new java.util.HashSet<>();

        // 1) 已有付款请求的托管应付：未确认→暂停；已确认→列回收
        for (PaymentRequest req : payments.requestsByArbitration(arb.id())) {
            if (req.payableId() != null) {
                handledPayables.add(req.payableId());
            }
            if (List.of("ISSUED", "TIMEOUT_QUERIED").contains(req.status())) {
                payments.suspendRequest(req.id());
                suspendedReqs++;
                suspended += req.amountMinor();
                if (req.payableId() != null) {
                    settlements.reduceEscrowPayable(req.payableId(), req.amountMinor());
                }
            } else if ("CONFIRMED".equals(req.status()) && req.payableId() != null) {
                Payable paid = settlements.findPayableById(req.payableId()).orElseThrow();
                createClawback(arb.disputeId(), paid.settlementBatchId(), paid.id(),
                        req.payeeId(), req.currency(), req.amountMinor(),
                        "裁决 " + arbitrationNo + " 撤回，已付款须回收: " + reason);
                clawbackAmt += req.amountMinor();
                clawbacks++;
            }
        }

        // 2) 已执行但尚未生成付款文件的托管待付：回滚未出款部分到冻结
        for (Map<String, Object> line : escrow.executionLinesOfArbitration(arb.id())) {
            String action = (String) line.get("action");
            if (!List.of("RELEASE", "TRANSFER").contains(action)) {
                continue;
            }
            Long payableId = (Long) line.get("payable_id");
            if (payableId != null && handledPayables.contains(payableId)) {
                continue; // 已在第 1 步按付款请求处理
            }
            long amount = ((Number) line.get("amount_minor")).longValue();
            long settled = ((Number) line.get("settled_minor")).longValue();
            long unpaid = amount - settled;
            if (unpaid <= 0) {
                continue;
            }
            long freezeId = ((Number) line.get("escrow_freeze_id")).longValue();
            EscrowFreeze f = escrow.findFreezeById(freezeId).orElseThrow();
            if (payableId != null) {
                settlements.reduceEscrowPayable(payableId, unpaid);
            }
            // 回滚争议覆盖额，使该部分重新受争议约束、可被后续裁决处置
            escrow.subtractCoverConsumed(freezeId, arb.disputeId(), unpaid);
            if ("RELEASE".equals(action) && f.payeeId() != null) {
                // 释放待付回退为原应付冻结（frozen_release → frozen）
                settlements.findPayable(f.settlementBatchId(), f.payeeId(), f.rightType(), f.currency())
                        .ifPresent(p -> settlements.revertReleasedPayableFrozen(p.id(), unpaid));
            }
            escrow.markFreezeProgress(freezeId);
            suspended += unpaid;
        }

        escrow.revokeArbitration(arb.id(), reason);
        assertAllAccountsConservation();
        return new RevokeResult(arbitrationNo, suspended, clawbackAmt, suspendedReqs, clawbacks);
    }

    // ============================ 争议撤回 ============================

    public record WithdrawResult(String disputeNo, long releasedMinor, int freezeLines) {
    }

    /** 争议撤回：剩余冻结自动解冻给原权利人（无主挂账解除冻结留挂账），历史分录不删除 */
    @Transactional
    public WithdrawResult withdrawDispute(String disputeNo, String reason, String actor) {
        DisputeCase d = escrow.findDisputeByNo(disputeNo)
                .orElseThrow(() -> new DomainException("争议不存在: " + disputeNo));
        if (!d.isActive()) {
            throw new DomainException("争议已撤回");
        }
        long released = 0;
        int lines = 0;
        for (EscrowFreeze f : escrow.openFreezesOfDispute(d.id())) {
            EscrowRepository.CoverRow mine = escrow.coversOfFreeze(f.id()).stream()
                    .filter(c -> c.disputeId() == d.id()).findFirst().orElse(null);
            if (mine == null) {
                continue;
            }
            long amount = Math.min(mine.coveredMinor() - mine.consumedMinor(), f.remainingMinor());
            if (amount <= 0) {
                continue;
            }
            // 仍被其他现行争议覆盖的部分不能解冻
            long otherCover = escrow.coversOfFreeze(f.id()).stream()
                    .filter(c -> c.disputeId() != d.id())
                    .filter(c -> escrow.findDisputeById(c.disputeId())
                            .map(DisputeCase::isActive).orElse(false))
                    .mapToLong(c -> c.coveredMinor() - c.consumedMinor()).sum();
            long releasable = Math.min(amount, Math.max(0, f.remainingMinor() - otherCover));
            if (releasable <= 0) {
                continue;
            }
            escrow.addCoverConsumed(f.id(), d.id(), releasable);
            escrow.addReleased(f.id(), releasable);
            settlements.releaseAllocationFrozen(f.settlementAllocationId(), releasable);
            if (f.holdBalanceId() != null) {
                reports.releaseHoldFrozen(f.holdBalanceId(), releasable);
            }
            writeEntry(f.accountId(), "RELEASE", releasable, f.currency(), f.id(), d.id(), null,
                    null, f.payeeId(), d.legalRef(), "争议撤回自动解冻: " + reason);
            if (f.payeeId() != null) {
                settlements.findPayable(f.settlementBatchId(), f.payeeId(), f.rightType(), f.currency())
                        .ifPresent(p -> settlements.releasePayableFrozen(p.id(), releasable));
            }
            released += releasable;
            lines++;
            escrow.markFreezeProgress(f.id());
        }
        escrow.withdrawDispute(d.id(), reason);
        assertAllAccountsConservation();
        return new WithdrawResult(disputeNo, released, lines);
    }

    // ============================ 只读查询（逐项解释台账） ============================

    /** 争议详情：声明范围、冻结/追回结果与各冻结单元 */
    @Transactional(readOnly = true)
    public Map<String, Object> disputeDetail(String disputeNo) {
        DisputeCase d = escrow.findDisputeByNo(disputeNo)
                .orElseThrow(() -> new DomainException("争议不存在: " + disputeNo));
        Map<String, Object> m = baseDisputeView(d);
        m.put("freezes", escrow.openFreezesOfDispute(d.id()).stream().map(f -> {
            Map<String, Object> fm = freezeView(f);
            fm.put("my_cover", escrow.coversOfFreeze(f.id()).stream()
                    .filter(c -> c.disputeId() == d.id()).findFirst()
                    .map(c -> Map.of("covered_minor", c.coveredMinor(),
                            "consumed_minor", c.consumedMinor())).orElse(null));
            return fm;
        }).toList());
        m.put("clawbacks", escrow.clawbacksOfDispute(d.id()).stream().map(this::clawbackView).toList());
        return m;
    }

    /** 托管账户台账：余额、分录、冻结单元与待付，可逐项核对 */
    @Transactional(readOnly = true)
    public Map<String, Object> accountLedger(String accountNo) {
        EscrowAccount account = escrow.findAccountByNo(accountNo)
                .orElseThrow(() -> new DomainException("托管账户不存在: " + accountNo));
        Map<String, Object> m = accountConservation(account.id());
        m.put("dispute_ids", escrow.disputeIdsOfAccount(account.id()));
        m.put("entries", escrow.entriesOfAccount(account.id()).stream().map(e -> {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("seq", e.entrySeq());
            em.put("entry_type", e.entryType());
            em.put("amount_minor", e.amountMinor());
            em.put("balance_after_minor", e.balanceAfterMinor());
            em.put("escrow_freeze_id", e.escrowFreezeId());
            em.put("dispute_id", e.disputeId());
            em.put("arbitration_id", e.arbitrationId());
            em.put("payment_request_id", e.paymentRequestId());
            em.put("counterparty_payee_id", e.counterpartyPayeeId());
            em.put("legal_ref", e.legalRef());
            em.put("note", e.note());
            return em;
        }).toList());
        m.put("freezes", escrow.freezesOfAccount(account.id()).stream().map(this::freezeView).toList());
        m.put("escrow_payables", settlements.escrowPayablesOfAccount(account.id()).stream()
                .map(p -> Map.of("payable_id", p.id(), "payee_id", p.payeeId(),
                        "right_type", p.rightType(), "source_kind", p.sourceKind(),
                        "amount_minor", p.amountMinor(), "paid_minor", p.paidMinor(),
                        "outstanding_minor", p.outstanding(), "status", p.status())).toList());
        return m;
    }

    public List<Map<String, Object>> listDisputes() {
        return escrow.allDisputes().stream().map(this::baseDisputeView).toList();
    }

    public List<Map<String, Object>> listArbitrations() {
        return escrow.allArbitrations().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("arbitration_id", a.id());
            m.put("arbitration_no", a.arbitrationNo());
            m.put("dispute_id", a.disputeId());
            m.put("recording_id", a.recordingId());
            m.put("right_type", a.rightType());
            m.put("territories", a.territories());
            m.put("period_start", String.valueOf(a.periodStart()));
            m.put("period_end", String.valueOf(a.periodEnd()));
            m.put("outcome", a.outcome());
            m.put("beneficiary_payee_id", a.beneficiaryPayeeId());
            m.put("legal_basis", a.legalBasis());
            m.put("status", a.status());
            m.put("execution_lines", escrow.executionLinesOfArbitration(a.id()));
            return m;
        }).toList();
    }

    public List<Map<String, Object>> listClawbacks(String status) {
        return escrow.allClawbacks(status).stream().map(this::clawbackView).toList();
    }

    public List<Map<String, Object>> listAccounts() {
        return escrow.allAccounts().stream().map(a -> accountConservation(a.id())).toList();
    }

    private Map<String, Object> baseDisputeView(DisputeCase d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispute_id", d.id());
        m.put("dispute_no", d.disputeNo());
        m.put("recording_id", d.recordingId());
        m.put("right_type", d.rightType());
        m.put("territories", d.territories());
        m.put("period_start", String.valueOf(d.periodStart()));
        m.put("period_end", String.valueOf(d.periodEnd()));
        m.put("ratio", d.ratioNumerator() + "/" + d.ratioDenominator());
        m.put("claimant_payee_id", d.claimantPayeeId());
        m.put("respondent_payee_id", d.respondentPayeeId());
        m.put("legal_ref", d.legalRef());
        m.put("reason", d.reason());
        m.put("status", d.status());
        return m;
    }

    private Map<String, Object> freezeView(EscrowFreeze f) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("escrow_freeze_id", f.id());
        fm.put("account_id", f.accountId());
        fm.put("settlement_allocation_id", f.settlementAllocationId());
        fm.put("hold_balance_id", f.holdBalanceId());
        fm.put("settlement_batch_id", f.settlementBatchId());
        fm.put("report_line_id", f.reportLineId());
        fm.put("recording_id", f.recordingId());
        fm.put("right_type", f.rightType());
        fm.put("payee_id", f.payeeId());
        fm.put("territory", f.territory());
        fm.put("usage_date", String.valueOf(f.usageDate()));
        fm.put("currency", f.currency());
        fm.put("original_amount_minor", f.originalAmountMinor());
        fm.put("frozen_amount_minor", f.frozenAmountMinor());
        fm.put("released_minor", f.releasedMinor());
        fm.put("transferred_minor", f.transferredMinor());
        fm.put("remaining_minor", f.remainingMinor());
        fm.put("dispute_ids", f.disputeIds());
        fm.put("status", f.status());
        fm.put("covers", escrow.coversOfFreeze(f.id()).stream()
                .map(c -> Map.of("dispute_id", c.disputeId(), "covered_minor", c.coveredMinor(),
                        "consumed_minor", c.consumedMinor())).toList());
        return fm;
    }

    private Map<String, Object> clawbackView(ClawbackItem c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clawback_no", c.clawbackNo());
        m.put("dispute_id", c.disputeId());
        m.put("settlement_batch_id", c.settlementBatchId());
        m.put("payable_id", c.payableId());
        m.put("payee_id", c.payeeId());
        m.put("currency", c.currency());
        m.put("amount_minor", c.amountMinor());
        m.put("status", c.status());
        m.put("reason", c.reason());
        m.put("recovery_ref", c.recoveryRef());
        return m;
    }

    // ============================ 守恒校验 ============================

    /**
     * 账户恒等式（实物资金口径）：托管入账 = 可支配余额 + 待付 + 已释放。
     * 物理托管余额由分录驱动（FREEZE_IN − RELEASE − TRANSFER_OUT）；
     * 裁决待付对应的实物资金仍在账户内但已被指定用途，故
     * 可支配余额 = 物理余额 − 待付占用。回执未确认前待付持续占用，防止重复支付。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> accountConservation(long accountId) {
        EscrowAccount account = escrow.findAccountById(accountId)
                .orElseThrow(() -> new DomainException("托管账户不存在"));
        long freezeIn = escrow.sumByType(accountId, List.of("FREEZE_IN"));
        long clawIn = escrow.sumByType(accountId, List.of("CLAWBACK_ADJ", "MANUAL_ADJ"));
        long released = escrow.sumByType(accountId, List.of("RELEASE"));
        long transferred = escrow.sumByType(accountId, List.of("TRANSFER_OUT"));
        long bookedIn = freezeIn + clawIn;                    // 托管入账
        long netReleased = released + transferred;           // 已释放（已出款）
        long physicalBalance = escrow.currentBalance(accountId); // 分录实物余额
        // 待付：托管来源应付未付清部分（含付款文件已生成、回执未确认）
        long pending = settlements.escrowPayablesOfAccount(accountId).stream()
                .mapToLong(Payable::outstanding).sum();
        long availableBalance = physicalBalance - pending;    // 可支配（未被裁决待付占用）
        long rhs = availableBalance + pending + netReleased;
        boolean ok = bookedIn == rhs && physicalBalance == bookedIn - netReleased
                && availableBalance >= 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account_no", account.accountNo());
        m.put("currency", account.currency());
        m.put("escrow_booked_in_minor", bookedIn);
        m.put("freeze_in_minor", freezeIn);
        m.put("clawback_in_minor", clawIn);
        m.put("released_minor", released);
        m.put("transferred_out_minor", transferred);
        m.put("net_released_minor", netReleased);
        m.put("physical_balance_minor", physicalBalance);
        m.put("pending_payment_minor", pending);
        m.put("available_balance_minor", availableBalance);
        m.put("conservation_ok", ok);
        m.put("identity", bookedIn + " = 可支配余额 " + availableBalance + " + 待付 " + pending
                + " + 已释放 " + netReleased);
        if (!ok) {
            throw new DomainException("托管守恒校验失败: " + m.get("identity"));
        }
        return m;
    }

    private void assertAccountConservation(java.util.Collection<Long> accountIds) {
        for (Long id : accountIds) {
            if (id != null && id > 0) {
                accountConservation(id);
            }
        }
    }

    private void assertAllAccountsConservation() {
        for (EscrowAccount a : escrow.allAccounts()) {
            accountConservation(a.id());
        }
    }

    /** 追回事项登记回收（模拟回款到账，入托管账 CLAWBACK_ADJ） */
    @Transactional
    public Map<String, Object> recoverClawback(String clawbackNo, String recoveryRef, String actor) {
        ClawbackItem item = escrow.findClawbackByNo(clawbackNo)
                .orElseThrow(() -> new DomainException("追回事项不存在: " + clawbackNo));
        if (!"OPEN".equals(item.status())) {
            throw new DomainException("追回事项状态为 " + item.status());
        }
        escrow.markClawbackRecovered(item.id(), recoveryRef);
        long accountId = ensureAccount(item.currency());
        writeEntry(accountId, "CLAWBACK_ADJ", item.amountMinor(), item.currency(), null,
                item.disputeId(), null, null, item.payeeId(), null,
                "追回款到账 " + clawbackNo + " (" + recoveryRef + ")");
        assertAccountConservation(List.of(accountId));
        return Map.of("clawback_no", clawbackNo, "status", "RECOVERED",
                "amount_minor", item.amountMinor());
    }
}
