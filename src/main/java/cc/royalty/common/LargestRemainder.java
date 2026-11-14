package cc.royalty.common;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 最大余数法（Largest Remainder Method）：
 * 按有理数权重把一笔最小货币单位整数金额分摊到若干桶，
 * 先取整数部分，再把尾差按余数从大到小每桶 +1。
 * 权重合计不足 1 时，未覆盖部分保留为 leftover（待认领），绝不放大已知桶；
 * 权重合计恰为 1 时各桶金额之和严格等于总额（总额守恒）。
 */
public final class LargestRemainder {

    public record WeightedBucket<K>(K key, Rational weight, long amount) {
    }

    public record PartialResult<K>(List<WeightedBucket<K>> buckets, long leftover) {
    }

    /** 权重合计必须恰为 1 的全量分摊，返回各桶金额，总和等于 totalMinor */
    public static <K> List<WeightedBucket<K>> distribute(long totalMinor, List<WeightedBucket<K>> weighted) {
        PartialResult<K> r = distributePartial(totalMinor, weighted);
        if (r.leftover() != 0) {
            throw new IllegalStateException("weights do not cover the whole total, leftover=" + r.leftover());
        }
        return r.buckets();
    }

    /** 允许权重合计 <=1：未覆盖金额作为 leftover 返回，不分给任何已知桶 */
    public static <K> PartialResult<K> distributePartial(long totalMinor, List<WeightedBucket<K>> weighted) {
        List<WeightedBucket<K>> inputs = weighted.stream().filter(b -> b.weight().signum() > 0).toList();
        Rational weightSum = inputs.stream().map(WeightedBucket::weight).reduce(Rational.ZERO, Rational::add);
        if (weightSum.compareTo(Rational.ONE) > 0) {
            throw new IllegalArgumentException("weights exceed 1: " + weightSum);
        }
        List<K> keys = new ArrayList<>();
        List<Long> floors = new ArrayList<>();
        List<Rational> remainders = new ArrayList<>();
        long assigned = 0;
        for (WeightedBucket<K> b : inputs) {
            Rational quota = b.weight().multiply(totalMinor);
            long floor = quota.floor().longValueExact();
            assigned += floor;
            keys.add(b.key());
            floors.add(floor);
            remainders.add(quota.fractionalPart());
        }
        long leftover = totalMinor - assigned;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) order.add(i);
        order.sort(Comparator.comparing((Integer i) -> remainders.get(i)).reversed().thenComparingInt(i -> i));
        long[] amounts = new long[keys.size()];
        for (int i = 0; i < amounts.length; i++) amounts[i] = floors.get(i);
        // 只有仍有余数的桶才能接收尾差；余数为 0 后剩余的就是待认领 leftover
        int ptr = 0;
        while (leftover > 0 && ptr < order.size() && remainders.get(order.get(ptr)).signum() > 0) {
            amounts[order.get(ptr)]++;
            leftover--;
            ptr++;
        }
        List<WeightedBucket<K>> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            out.add(new WeightedBucket<>(keys.get(i), inputs.get(i).weight(), amounts[i]));
        }
        return new PartialResult<>(out, leftover);
    }

    /** 四舍五入（HALF_UP）单笔有理数金额为最小货币单位；整数不进位 */
    public static long roundHalfUp(Rational r) {
        BigInteger floor = r.floor();
        BigInteger remainder = r.numerator().mod(r.denominator()); // 0..d-1
        boolean halfOrMore = remainder.multiply(BigInteger.TWO).compareTo(r.denominator()) >= 0;
        if (r.signum() >= 0) {
            return halfOrMore ? floor.add(BigInteger.ONE).longValueExact() : floor.longValueExact();
        }
        // 负数 HALF_UP 朝零方向进位（如 -3/2 -> -1）
        boolean strictlyMoreThanHalf = remainder.multiply(BigInteger.TWO).compareTo(r.denominator()) > 0;
        return strictlyMoreThanHalf ? floor.add(BigInteger.ONE).longValueExact() : floor.longValueExact();
    }

    private LargestRemainder() {
    }
}
