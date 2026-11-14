package cc.royalty.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RationalAndLrmTest {

    @Test
    void rationalArithmeticIsExact() {
        Rational third = Rational.of(1, 3);
        Rational sum = third.add(third).add(third);
        assertEquals(Rational.ONE, sum);
        assertEquals(0, Rational.of(10, 4).fractionalPart().compareTo(Rational.of(1, 2)));
        assertEquals(3, Rational.of(10, 3).floor().longValueExact());
        assertEquals(-4, Rational.of(-10, 3).floor().longValueExact());
    }

    @Test
    void largestRemainderConservesTotalAndHandlesThirds() {
        // 100 按 1/3 各分三份：34/33/33，总和必须仍是 100
        List<LargestRemainder.WeightedBucket<String>> in = new ArrayList<>();
        in.add(new LargestRemainder.WeightedBucket<>("a", Rational.of(1, 3), 0));
        in.add(new LargestRemainder.WeightedBucket<>("b", Rational.of(1, 3), 0));
        in.add(new LargestRemainder.WeightedBucket<>("c", Rational.of(1, 3), 0));
        List<LargestRemainder.WeightedBucket<String>> out = LargestRemainder.distribute(100, in);
        long total = out.stream().mapToLong(LargestRemainder.WeightedBucket::amount).sum();
        assertEquals(100, total);
        assertEquals(34, out.get(0).amount());
        assertEquals(33, out.get(1).amount());
        assertEquals(33, out.get(2).amount());
    }

    @Test
    void partialSharesLeaveUnclaimedInsteadOfScaling() {
        // 权重合计 75%，分 10000：已知人得 7500，2500 作为 leftover 待认领，不放大到总额
        List<LargestRemainder.WeightedBucket<String>> in = new ArrayList<>();
        in.add(new LargestRemainder.WeightedBucket<>("known", Rational.of(3, 4), 0));
        LargestRemainder.PartialResult<String> r = LargestRemainder.distributePartial(10000, in);
        assertEquals(7500, r.buckets().get(0).amount());
        assertEquals(2500, r.leftover());
    }

    @Test
    void rejectsWeightsAboveOne() {
        List<LargestRemainder.WeightedBucket<String>> in = List.of(
                new LargestRemainder.WeightedBucket<>("x", Rational.of(6, 10), 0),
                new LargestRemainder.WeightedBucket<>("y", Rational.of(6, 10), 0));
        assertThrows(IllegalArgumentException.class, () -> LargestRemainder.distribute(100, in));
    }

    @Test
    void halfUpRounding() {
        assertEquals(2, LargestRemainder.roundHalfUp(Rational.of(3, 2)));
        assertEquals(1, LargestRemainder.roundHalfUp(Rational.of(1, 2)));
        assertEquals(0, LargestRemainder.roundHalfUp(Rational.of(49, 100)));
        assertEquals(10000, LargestRemainder.roundHalfUp(Rational.of(10000, 1)),
                "整数金额不得被错误进位");
        assertEquals(-2, LargestRemainder.roundHalfUp(Rational.of(-3, 2)));
        assertEquals(0, LargestRemainder.roundHalfUp(Rational.of(-1, 4)));
    }
}
