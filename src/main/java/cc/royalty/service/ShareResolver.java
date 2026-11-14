package cc.royalty.service;

import cc.royalty.common.LargestRemainder;
import cc.royalty.common.Rational;
import cc.royalty.domain.Contract;
import cc.royalty.domain.ContractShare;
import cc.royalty.repo.CatalogRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按使用日期解析合同并分摊一笔权利预算。
 * 冲突策略：
 *  - 份额合计 > 100%：无法可靠确定任何人的金额，整笔转冲突暂扣；
 *  - 多份有效合同并存：出现在多份合同中的权利人份额为“争议份额”，暂扣相关份额，
 *    只出现在一份合同中的无争议权利人继续结算；
 *  - 份额合计 < 100%：剩余款单列待认领，不放大已知人的比例；
 *  - 无有效合同：整笔待认领。
 * 全部金额经最大余数法落到最小货币单位，三类金额之和严格等于可分配额。
 */
@Component
public class ShareResolver {

    public record PayeeResolution(long payeeId, Long contractId, Rational weight, long amountMinor,
                                  boolean disputed, String note) {
    }

    public record Resolution(boolean conflict, List<String> reasons,
                             List<PayeeResolution> payees,
                             long allocatedMinor, long conflictHoldMinor, long unclaimedMinor,
                             Rational totalWeight, List<Long> contractIds) {
    }

    private final CatalogRepository catalog;

    public ShareResolver(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public Resolution resolve(String rightType, Long workId, Long recordingId, LocalDate date,
                              String territory, String media, long distributableMinor) {
        Long refId = "PUBLISHING".equals(rightType) ? workId : recordingId;
        List<String> reasons = new ArrayList<>();
        if (refId == null) {
            reasons.add("缺少" + ("PUBLISHING".equals(rightType) ? "作品" : "录音") + "标识，无法选择合同");
            return new Resolution(true, reasons, List.of(), 0, 0, distributableMinor,
                    Rational.ZERO, List.of());
        }
        List<Contract> contracts = catalog.effectiveContracts(rightType, refId, date, territory, media);
        List<Long> contractIds = contracts.stream().map(Contract::id).toList();
        if (contracts.isEmpty()) {
            reasons.add("使用日 " + date + " 无有效合同，款项待认领");
            return new Resolution(false, reasons, List.of(), 0, 0, distributableMinor,
                    Rational.ZERO, List.of());
        }

        // payee -> 聚合份额；同时记录出现过的合同数与代表合同
        Map<Long, Rational> weightByPayee = new LinkedHashMap<>();
        Map<Long, Long> firstContractByPayee = new LinkedHashMap<>();
        Map<Long, Integer> contractCountByPayee = new LinkedHashMap<>();
        Map<Long, Rational> perContractSum = new LinkedHashMap<>();
        Rational total = Rational.ZERO;
        for (Contract c : contracts) {
            Rational cSum = Rational.ZERO;
            for (ContractShare s : catalog.sharesOfContract(c.id())) {
                Rational w = Rational.of(s.shareNumerator(), s.shareDenominator());
                weightByPayee.merge(s.payeeId(), w, Rational::add);
                firstContractByPayee.putIfAbsent(s.payeeId(), c.id());
                contractCountByPayee.merge(s.payeeId(), 1, Integer::sum);
                total = total.add(w);
                cSum = cSum.add(w);
            }
            perContractSum.put(c.id(), cSum);
        }

        // 规则 1：份额合计超出 100% —— 整笔阻止
        if (total.compareTo(Rational.ONE) > 0
                || perContractSum.values().stream().anyMatch(r -> r.compareTo(Rational.ONE) > 0)) {
            reasons.add("有效合同份额合计 " + total + " 超出 100%，阻止该权利类型全部份额结算");
            return new Resolution(true, reasons, List.of(), 0, distributableMinor, 0,
                    total, contractIds);
        }

        boolean multiContract = contracts.size() > 1;
        if (multiContract) {
            reasons.add("使用日存在 " + contracts.size() + " 份并存有效合同，多合同共有权利人份额暂扣，无争议部分继续");
        }

        // 池子拆分：无争议 / 争议 / 待认领，先在池子层面做一次 LRM 保证守恒
        Rational undisputedWeight = Rational.ZERO;
        Rational disputedWeight = Rational.ZERO;
        List<Long> payeeOrder = new ArrayList<>(weightByPayee.keySet());
        for (Long payeeId : payeeOrder) {
            if (multiContract && contractCountByPayee.get(payeeId) > 1) {
                disputedWeight = disputedWeight.add(weightByPayee.get(payeeId));
            } else {
                undisputedWeight = undisputedWeight.add(weightByPayee.get(payeeId));
            }
        }
        Rational unclaimedWeight = Rational.ONE.subtract(total);

        record Pool(String key, Rational w) {
        }
        List<LargestRemainder.WeightedBucket<String>> poolBuckets = new ArrayList<>();
        poolBuckets.add(new LargestRemainder.WeightedBucket<>("UNDISPUTED", undisputedWeight, 0));
        poolBuckets.add(new LargestRemainder.WeightedBucket<>("DISPUTED", disputedWeight, 0));
        poolBuckets.add(new LargestRemainder.WeightedBucket<>("UNCLAIMED", unclaimedWeight, 0));
        Map<String, Long> poolAmounts = new LinkedHashMap<>();
        poolAmounts.put("UNDISPUTED", 0L);
        poolAmounts.put("DISPUTED", 0L);
        poolAmounts.put("UNCLAIMED", 0L);
        // 三个池权重合计恰为 1；零权重池不会出现在结果里，缺省按 0 处理
        for (LargestRemainder.WeightedBucket<String> b :
                LargestRemainder.distribute(distributableMinor, poolBuckets)) {
            poolAmounts.put(b.key(), b.amount());
        }
        long undisputedPool = poolAmounts.get("UNDISPUTED");
        long disputedPool = poolAmounts.get("DISPUTED");
        long unclaimedPool = poolAmounts.get("UNCLAIMED");

        // 无争议池内按相对权重再做 LRM
        List<LargestRemainder.WeightedBucket<Long>> undisputedBuckets = new ArrayList<>();
        List<Long> disputedPayees = new ArrayList<>();
        for (Long payeeId : payeeOrder) {
            boolean disputed = multiContract && contractCountByPayee.get(payeeId) > 1;
            if (disputed) {
                disputedPayees.add(payeeId);
            } else {
                undisputedBuckets.add(new LargestRemainder.WeightedBucket<>(payeeId,
                        weightByPayee.get(payeeId), 0));
            }
        }
        final Rational undisputedWeightFinal = undisputedWeight;
        final Rational disputedWeightFinal = disputedWeight;
        List<PayeeResolution> out = new ArrayList<>();
        if (!undisputedBuckets.isEmpty()) {
            // 归一化到无争议权重内部
            List<LargestRemainder.WeightedBucket<Long>> normalized = undisputedBuckets.stream()
                    .map(b -> new LargestRemainder.WeightedBucket<>(b.key(),
                            undisputedWeightFinal.isZero() ? b.weight()
                                    : b.weight().divide(undisputedWeightFinal), 0L))
                    .toList();
            for (LargestRemainder.WeightedBucket<Long> b
                    : LargestRemainder.distribute(undisputedPool, normalized)) {
                out.add(new PayeeResolution(b.key(), firstContractByPayee.get(b.key()),
                        weightByPayee.get(b.key()), b.amount(), false, null));
            }
        }
        // 争议池按争议权利人的权重再分一次，金额状态仍是暂扣（用于解释暂扣去向）
        if (!disputedPayees.isEmpty()) {
            List<LargestRemainder.WeightedBucket<Long>> db = disputedPayees.stream()
                    .map(p -> new LargestRemainder.WeightedBucket<>(p,
                            disputedWeightFinal.isZero() ? weightByPayee.get(p)
                                    : weightByPayee.get(p).divide(disputedWeightFinal), 0L))
                    .toList();
            for (LargestRemainder.WeightedBucket<Long> b
                    : LargestRemainder.distribute(disputedPool, db)) {
                out.add(new PayeeResolution(b.key(), firstContractByPayee.get(b.key()),
                        weightByPayee.get(b.key()), b.amount(), true,
                        "该权利人出现在多份并存合同中，份额暂扣待裁定"));
            }
        }
        if (unclaimedPool > 0) {
            reasons.add("份额合计 " + total + " 不足 100%，" + unclaimedPool + " 最小货币单位待认领");
        }

        long allocated = out.stream().filter(p -> !p.disputed()).mapToLong(PayeeResolution::amountMinor).sum();
        long held = out.stream().filter(PayeeResolution::disputed).mapToLong(PayeeResolution::amountMinor).sum();
        return new Resolution(disputedWeight.signum() > 0, reasons, out,
                allocated, held, unclaimedPool, total, contractIds);
    }
}
