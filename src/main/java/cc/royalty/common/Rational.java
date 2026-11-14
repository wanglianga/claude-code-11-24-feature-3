package cc.royalty.common;

import java.math.BigInteger;

/**
 * 不可变有理数（分子/分母均为 BigInteger，分母恒正）。
 * 合同份额与分摊全程使用精确有理数，禁止用 double 浮点做份额运算。
 */
public final class Rational implements Comparable<Rational> {

    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger numerator;
    private final BigInteger denominator;

    public Rational(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("denominator is zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger g = numerator.gcd(denominator);
        if (g.signum() != 0) {
            numerator = numerator.divide(g);
            denominator = denominator.divide(g);
        }
        this.numerator = numerator;
        this.denominator = denominator;
    }

    public static Rational of(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public Rational add(Rational o) {
        return new Rational(
                numerator.multiply(o.denominator).add(o.numerator.multiply(denominator)),
                denominator.multiply(o.denominator));
    }

    public Rational subtract(Rational o) {
        return new Rational(
                numerator.multiply(o.denominator).subtract(o.numerator.multiply(denominator)),
                denominator.multiply(o.denominator));
    }

    public Rational multiply(Rational o) {
        return new Rational(numerator.multiply(o.numerator), denominator.multiply(o.denominator));
    }

    public Rational divide(Rational o) {
        return new Rational(numerator.multiply(o.denominator), denominator.multiply(o.numerator));
    }

    public Rational multiply(long v) {
        return new Rational(numerator.multiply(BigInteger.valueOf(v)), denominator);
    }

    /** 向下取整的整数部分 */
    public BigInteger floor() {
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        if (qr[1].signum() < 0) {
            return qr[0].subtract(BigInteger.ONE);
        }
        return qr[0];
    }

    /** 真分数余数部分（[0,1)），用于最大余数法 */
    public Rational fractionalPart() {
        BigInteger r = numerator.mod(denominator);
        return new Rational(r, denominator);
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    public int signum() {
        return numerator.signum();
    }

    public BigInteger numerator() {
        return numerator;
    }

    public BigInteger denominator() {
        return denominator;
    }

    @Override
    public int compareTo(Rational o) {
        return numerator.multiply(o.denominator).compareTo(o.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rational r)) return false;
        return numerator.equals(r.numerator) && denominator.equals(r.denominator);
    }

    @Override
    public int hashCode() {
        return numerator.hashCode() * 31 + denominator.hashCode();
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
