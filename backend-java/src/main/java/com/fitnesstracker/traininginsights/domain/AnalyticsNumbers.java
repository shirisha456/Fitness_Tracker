package com.fitnesstracker.traininginsights.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact-value rounding and evidence-string formatting for the analytics engine.
 *
 * <p>The rules here look arbitrary and are not: every one of them is pinned by the
 * compatibility fixtures, because the numbers this engine emits are part of the API
 * contract. They reproduce CPython's semantics, which is where the contract originated —
 * see {@code ContractFixtures} for that history.
 *
 * <h2>Rounding</h2>
 *
 * Python's {@code round()} is round-half-to-even applied to the <em>exact binary value</em>
 * of the double. Java offers three plausible spellings and only one agrees:
 *
 * <pre>
 *   value    python round(v,2)   Math.round-style   BigDecimal.valueOf   new BigDecimal
 *   2.675    2.67                2.68               2.68                 2.67  ✓
 *   2.665    2.67                2.67               2.66                 2.67  ✓
 *   12.345   12.35               12.35              12.34                12.35 ✓
 *   2.5      round(v)=2          3                  —                    2     ✓
 * </pre>
 *
 * {@code BigDecimal.valueOf(double)} goes through {@code Double.toString}, which gives the
 * shortest round-tripping decimal — "2.675" — and then rounds that, landing on 2.68 where
 * Python sees the true value 2.67499...  {@code new BigDecimal(double)} keeps the exact
 * binary value, which is what CPython rounds. {@code Math.round} is half-up and disagrees
 * on every tie.
 *
 * <p>Pinned by {@code training-insights-fixtures.json} on the test classpath.
 */
public final class AnalyticsNumbers {

    private AnalyticsNumbers() {}

    /** Equivalent of Python's {@code round(value, digits)}. */
    public static double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Equivalent of Python's single-argument {@code round(value)} — ties to even. */
    public static long roundToLong(double value) {
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).longValue();
    }

    /**
     * Equivalent of Python's {@code f"{value:g}"}: up to six significant digits with
     * trailing zeros removed, so 45.0 renders as "45" and 42.5 as "42.5".
     */
    public static String formatG(double value) {
        BigDecimal decimal = new BigDecimal(value).round(new java.math.MathContext(6));
        return decimal.stripTrailingZeros().toPlainString();
    }

    /**
     * Equivalent of Python's {@code f"{value}"} for a float produced by
     * {@code round(x, 1)} — always exactly one decimal place, because that is the shortest
     * representation that round-trips such a value.
     */
    public static String formatOneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** Equivalent of Python's {@code f"{value:+}"} for the same one-decimal floats. */
    public static String formatSignedOneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%+.1f", value);
    }
}
