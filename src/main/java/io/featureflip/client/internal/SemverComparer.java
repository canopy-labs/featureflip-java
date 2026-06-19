package io.featureflip.client.internal;

/**
 * Compares semantic-version strings (<a href="https://semver.org">semver.org</a>) for the
 * {@code Semver*} condition operators.
 *
 * <p>Tolerant of real-world version strings: an optional leading {@code v}, an arbitrary number of
 * dot-separated numeric segments (missing trailing segments compare as {@code 0}, so {@code 2.0}
 * equals {@code 2.0.0}), an optional {@code -prerelease} suffix (lower precedence than the release
 * it qualifies), and {@code +build} metadata (ignored for precedence). Numeric segments are
 * compared digit-by-digit rather than parsed into a fixed-width integer, so arbitrarily large
 * version numbers never overflow. A value whose release core is missing or non-numeric is "not a
 * version" and matches nothing — mirroring how the numeric and date/time operators treat
 * unparseable input.
 *
 * <p>Mirrors the JS SDK's {@code parseSemver}/{@code compareSemver}
 * ({@code packages/js-sdk/src/core/evaluator.ts}) and the Python SDK's {@code _semver}. Prerelease
 * alphanumeric identifiers are compared in <strong>case-sensitive ASCII sort order</strong> per
 * semver §11 — the evaluation engine's {@code SemverComparer} currently folds case, which is a
 * spec deviation tracked in #1447; this SDK follows the spec.
 */
final class SemverComparer {

    private SemverComparer() {
    }

    /** A parsed semantic version: dot-separated release segments and optional prerelease identifiers. */
    static final class SemverVersion {
        final String[] release;
        final String[] prerelease;

        SemverVersion(String[] release, String[] prerelease) {
            this.release = release;
            this.prerelease = prerelease;
        }
    }

    /**
     * Parses {@code value} as a semantic version, or returns {@code null} when the release core is
     * missing or any release segment is non-numeric.
     */
    static SemverVersion parse(String value) {
        if (value == null) {
            return null;
        }

        String s = value.trim();
        if (s.isEmpty()) {
            return null;
        }

        // Optional leading "v"/"V".
        char first = s.charAt(0);
        if (first == 'v' || first == 'V') {
            s = s.substring(1);
        }

        // Build metadata ("+...") does not affect precedence.
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }

        // Split the release core from the optional "-prerelease" suffix.
        String corePart;
        String[] prerelease;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            corePart = s.substring(0, dash);
            String pre = s.substring(dash + 1);
            if (pre.isEmpty()) {
                return null; // trailing "-" with no identifiers is malformed
            }
            // Keep empty segments (limit -1) so "a..b" / trailing "." are rejected below.
            prerelease = pre.split("\\.", -1);
            for (String identifier : prerelease) {
                if (identifier.isEmpty()) {
                    return null;
                }
            }
        } else {
            corePart = s;
            prerelease = new String[0];
        }

        if (corePart.isEmpty()) {
            return null;
        }

        String[] release = corePart.split("\\.", -1);
        for (String seg : release) {
            if (!isAllDigits(seg)) {
                return null;
            }
        }

        return new SemverVersion(release, prerelease);
    }

    /**
     * Returns a negative number, zero, or a positive number as {@code a} is less than, equal to, or
     * greater than {@code b}.
     */
    static int compare(SemverVersion a, SemverVersion b) {
        int max = Math.max(a.release.length, b.release.length);
        for (int i = 0; i < max; i++) {
            String segA = i < a.release.length ? a.release[i] : "0";
            String segB = i < b.release.length ? b.release[i] : "0";
            int cmp = compareNumericString(segA, segB);
            if (cmp != 0) {
                return cmp;
            }
        }

        return comparePrerelease(a.prerelease, b.prerelease);
    }

    private static int comparePrerelease(String[] a, String[] b) {
        // A version with no prerelease has higher precedence than one with a prerelease.
        if (a.length == 0 && b.length == 0) return 0;
        if (a.length == 0) return 1;
        if (b.length == 0) return -1;

        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int cmp = comparePrereleaseIdentifier(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }

        // All shared identifiers equal: the longer prerelease has higher precedence.
        return Integer.compare(a.length, b.length);
    }

    private static int comparePrereleaseIdentifier(String a, String b) {
        boolean aNum = isAllDigits(a);
        boolean bNum = isAllDigits(b);

        // Numeric identifiers always have lower precedence than alphanumeric ones.
        if (aNum && bNum) return compareNumericString(a, b);
        if (aNum) return -1;
        if (bNum) return 1;
        // Case-sensitive ASCII sort order (semver §11).
        return a.compareTo(b);
    }

    /**
     * Compares two all-digit strings as non-negative integers without parsing (overflow-free):
     * strip leading zeros, then the longer string is the larger number; equal lengths compare
     * lexically.
     */
    private static int compareNumericString(String a, String b) {
        a = stripLeadingZeros(a);
        b = stripLeadingZeros(b);
        if (a.length() != b.length()) {
            return a.length() < b.length() ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
