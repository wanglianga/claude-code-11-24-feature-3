package cc.royalty.service;

import cc.royalty.common.DomainException;
import cc.royalty.common.LargestRemainder;
import cc.royalty.common.Rational;
import cc.royalty.domain.PlatformAgreement;
import cc.royalty.domain.ReportLine;
import cc.royalty.repo.CatalogRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每条使用先确定可分配收入：
 * - PER_UNIT：确认有效次数 × 当期单价；平台费用仅在协议有明确条款时扣一次。
 * - GROSS_REPORT：优先取已核对原币净额（费用已在平台侧扣除，不再重扣）；
 *   无净额时按协议费用条款对总额扣一次；再按各权利类型协议的预算份额分列，
 *   词曲与录音各走各的预算，互不挤占整笔。
 */
@Component
public class IncomeCalculator {

    public record RightBudget(String rightType, long distributableMinor, long feeMinor,
                              String basis, BigDecimal rateUsed, Rational budgetWeight,
                              String problem) {
    }

    public record LineIncome(String currency, List<RightBudget> budgets, long totalDistributable,
                             long totalFee, long budgetUnclaimedMinor, List<String> problems) {
    }

    private final CatalogRepository catalog;

    public IncomeCalculator(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public LineIncome determine(ReportLine line, long platformId) {
        List<String> problems = new ArrayList<>();
        Map<String, PlatformAgreement> byRight = new LinkedHashMap<>();
        for (String right : List.of("RECORDING", "PUBLISHING")) {
            List<PlatformAgreement> agrs = catalog.effectiveAgreements(platformId, right,
                    line.usageDate(), line.territory(), line.media());
            if (agrs.size() > 1) {
                problems.add(right + " 存在 " + agrs.size() + " 份相互冲突的有效平台协议");
            }
            if (!agrs.isEmpty()) {
                byRight.put(right, agrs.get(0));
            }
        }
        if (byRight.isEmpty()) {
            problems.add("使用日无有效平台协议，无法确定可分配收入");
            return new LineIncome(line.currency(), List.of(), 0, 0, 0, problems);
        }

        // 同一行不允许两种结算方式混用
        long distinctMethods = byRight.values().stream().map(PlatformAgreement::settlementMethod).distinct().count();
        if (distinctMethods > 1) {
            problems.add("同一使用行上的协议分别声明了按次计费与直接收入两种方式，阻止结算");
            return new LineIncome(line.currency(), List.of(), 0, 0, 0, problems);
        }

        String method = byRight.values().iterator().next().settlementMethod();
        List<RightBudget> budgets = new ArrayList<>();

        if ("PER_UNIT".equals(method)) {
            if (line.usageCount() == null || line.usageCount() <= 0) {
                problems.add("按次计费行缺少有效使用次数");
                return new LineIncome(line.currency(), List.of(), 0, 0, 0, problems);
            }
            if (line.grossMinor() != null && line.grossMinor() != 0) {
                problems.add("按次计费行同时携带平台总额，禁止次数乘单价后再重复分配总额");
                return new LineIncome(line.currency(), List.of(), 0, 0, 0, problems);
            }
            long total = 0;
            long totalFee = 0;
            for (Map.Entry<String, PlatformAgreement> e : byRight.entrySet()) {
                PlatformAgreement a = e.getValue();
                Rational quota = Rational.of(line.usageCount(), 1)
                        .multiply(Rational.of(scaleRate(a.rateMinor()), 1000000));
                long gross = LargestRemainder.roundHalfUp(quota);
                long fee = computeFeeOnce(a, gross);
                long dist = gross - fee;
                total += dist;
                totalFee += fee;
                budgets.add(new RightBudget(e.getKey(), dist, fee, "PER_UNIT_RATE",
                        a.rateMinor(), Rational.ONE, null));
            }
            return new LineIncome(line.currency(), budgets, total, totalFee, 0, problems);
        }

        // GROSS_REPORT：直接报告收入
        long gross = line.grossMinor() == null ? 0 : line.grossMinor();
        long reportedFee = line.feeMinor() == null ? 0 : line.feeMinor();
        long reportedNet = line.netMinor() == null ? 0 : line.netMinor();
        if (gross <= 0 && reportedNet <= 0) {
            problems.add("直接收入行缺少已核对总额或净额");
            return new LineIncome(line.currency(), List.of(), 0, 0, 0, problems);
        }

        long totalDist;
        long fee;
        String basis;
        if (reportedNet > 0) {
            // 平台已扣费并核对净额：直接取净额，费用绝不重扣
            totalDist = reportedNet;
            fee = reportedFee;
            basis = "REPORTED_NET";
        } else {
            // 无净额：按协议明确条款对总额扣一次费（取录音权协议上的条款，费用只计算一次）
            PlatformAgreement feeOwner = byRight.getOrDefault("RECORDING", byRight.values().iterator().next());
            fee = computeFeeOnce(feeOwner, gross);
            totalDist = gross - fee;
            basis = "REPORTED_GROSS_LESS_FEE";
        }

        // 按权利类型预算份额分列；预算合计不足 1 的剩余额不放大、留待认领
        Rational budgetSum = byRight.values().stream()
                .map(a -> Rational.of(a.budgetNumerator(), a.budgetDenominator()))
                .reduce(Rational.ZERO, Rational::add);
        if (budgetSum.compareTo(Rational.ONE) > 0) {
            problems.add("权利类型预算份额合计超过 100%: " + budgetSum);
            return new LineIncome(line.currency(), List.of(), 0, fee, 0, problems);
        }
        List<LargestRemainder.WeightedBucket<String>> weighted = byRight.entrySet().stream()
                .map(e -> new LargestRemainder.WeightedBucket<>(e.getKey(),
                        Rational.of(e.getValue().budgetNumerator(), e.getValue().budgetDenominator()), 0))
                .toList();
        // 预算不足 100% 时 leftover 作为预算级待认领，绝不塞回某个权利类型
        LargestRemainder.PartialResult<String> split =
                LargestRemainder.distributePartial(totalDist, weighted);
        for (LargestRemainder.WeightedBucket<String> b : split.buckets()) {
            budgets.add(new RightBudget(b.key(), b.amount(), 0, basis, null, b.weight(), null));
        }
        long budgetUnclaimed = split.leftover();
        if (budgetUnclaimed > 0) {
            problems.add("权利类型预算合计不足 100%，剩余 " + budgetUnclaimed
                    + " 最小货币单位单列待认领，词曲/录音都不得领走整笔");
        }
        return new LineIncome(line.currency(), budgets, totalDist, fee, budgetUnclaimed, problems);
    }

    /** rate_minor 以 NUMERIC(18,6) 存储：每百万分之一最小货币单位为刻度，换算回有理数 */
    private static long scaleRate(BigDecimal rateMinor) {
        if (rateMinor == null) {
            throw new DomainException("按次协议缺少单价");
        }
        return rateMinor.multiply(BigDecimal.valueOf(1_000_000)).longValueExact();
    }

    /** 平台费用只在协议存在明确条款时计算，且每个预算最多扣一次 */
    private static long computeFeeOnce(PlatformAgreement a, long grossMinor) {
        return switch (a.feeBasis()) {
            case "PERCENT_OF_GROSS" -> LargestRemainder.roundHalfUp(
                    Rational.of(grossMinor, 1).multiply(Rational.of(a.feeNumerator(), a.feeDenominator())));
            case "FIXED" -> Rational.of(a.feeNumerator(), a.feeDenominator()).floor().longValueExact();
            default -> 0L;
        };
    }
}
