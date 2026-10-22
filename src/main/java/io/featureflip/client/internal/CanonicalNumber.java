package io.featureflip.client.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Canonical spelling of a floating-point rollout bucket value (#4236).
 *
 * <p>Same text as ECMAScript {@code Number::toString} and the Evaluation API's
 * {@code BucketValueFormatter.CanonicalNumber}, so every SDK hashes one number to one bucket.
 * Bucket path only; condition evaluation never calls it.
 *
 * <p>Finds the shortest round-trip digits: for p = 0..16 it takes the exact value rounded to
 * p+1 significant digits (half-even, the {@code %.{p}e} rule), or failing that its other
 * neighbour at that width, and keeps the first that parses back to the same number. {@code Double.toString} is not used: on Java 11 it is not always
 * the shortest form.
 */
final class CanonicalNumber {
    private CanonicalNumber() {}

    static String format(double x) {
        return format(x, false);
    }

    static String format(float x) {
        return format((double) x, true);
    }

    private static boolean roundTrips(BigDecimal candidate, double x, boolean single) {
        String text = candidate.toString();
        return single ? Float.parseFloat(text) == (float) x : Double.parseDouble(text) == x;
    }

    private static String format(double x, boolean single) {
        if (Double.isNaN(x)) return "NaN";
        if (Double.isInfinite(x)) return x > 0 ? "Infinity" : "-Infinity";
        if (x == 0) return "0";
        if (x < 0) return "-" + format(-x, single);

        BigDecimal exact = new BigDecimal(x);
        BigDecimal best = exact;
        int maxPrecision = single ? 8 : 16;
        for (int p = 0; p <= maxPrecision; p++) {
            BigDecimal nearest = exact.round(new MathContext(p + 1, RoundingMode.HALF_EVEN));
            BigDecimal down = exact.round(new MathContext(p + 1, RoundingMode.DOWN));
            BigDecimal up = exact.round(new MathContext(p + 1, RoundingMode.UP));
            // The nearest p+1 digit decimal is not always the one that round-trips (the shortest
            // form can sit one digit away, e.g. 2^-44), so try both neighbours, nearest first.
            BigDecimal other = nearest.compareTo(down) == 0 ? up : down;
            if (roundTrips(nearest, x, single)) {
                best = nearest;
                break;
            }
            if (roundTrips(other, x, single)) {
                best = other;
                break;
            }
        }

        // Value = 0.<digits> * 10^n, digits without trailing zeros.
        String all = best.unscaledValue().toString();
        int n = all.length() - best.scale();
        String digits = all.replaceAll("0+$", "");
        if (digits.isEmpty()) digits = "0";
        int k = digits.length();

        if (k <= n && n <= 21) {
            return digits + "0".repeat(n - k);
        }
        if (0 < n && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            return "0." + "0".repeat(-n) + digits;
        }
        int e = n - 1;
        String mantissa = digits.substring(0, 1) + (k > 1 ? "." + digits.substring(1) : "");
        return mantissa + "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
    }
}
