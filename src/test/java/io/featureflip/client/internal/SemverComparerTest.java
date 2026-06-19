package io.featureflip.client.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SemverComparerTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "1.2.3",
        "1",
        "1.0",
        "v2.1",                  // leading "v" tolerated
        "V2.1",                  // leading "V" tolerated
        "2.0.0+build.7",         // build metadata stripped
        "1.0.0-alpha",           // prerelease
        "1.0.0-alpha.1",
        "99999999999999999999.0" // overflow-free (won't fit in long)
    })
    void parseValidVersionsReturnsNonNull(String value) {
        assertThat(SemverComparer.parse(value)).isNotNull();
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {
        "   ",
        "abc",
        "1.x",       // non-numeric release segment
        "1..2",      // empty release segment
        "v",         // empty core after stripping "v"
        "1.0.0-",    // trailing "-" with no identifiers
        "1.0.0-a..b" // empty prerelease identifier
    })
    void parseInvalidVersionsReturnsNull(String value) {
        assertThat(SemverComparer.parse(value)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        // Missing trailing segments compare as 0.
        "2.0,       2.0.0, 0",
        "2.0.0,     2.0,   0",
        // Multi-segment ordering (the regression: decimal comparison got these wrong).
        "2.10.1,    2.0,   1",
        "2.10,      2.9,   1",
        "1.9.9,     2.0,  -1",
        // Leading zeros do not change the numeric value.
        "1.01,      1.1,   0",
        // Overflow-free comparison of huge segments.
        "99999999999999999999.0, 1.0, 1",
        // Leading "v" and build metadata are ignored for precedence.
        "v1.2.3,    1.2.3, 0",
        "1.2.3+build, 1.2.3, 0",
        // Prerelease precedence (semver §11).
        "1.0.0-alpha,   1.0.0,         -1", // prerelease < release
        "1.0.0-alpha,   1.0.0-alpha.1, -1", // longer wins when shared equal
        "1.0.0-alpha.1, 1.0.0-alpha.beta, -1", // numeric < alphanumeric
        "1.0.0-alpha,   1.0.0-beta,    -1", // ordinal identifier compare
        "1.0.0-1,       1.0.0-2,       -1", // numeric identifier compare
        "1.0.0-1,       1.0.0-10,      -1", // numeric, not lexical
    })
    void compareReturnsExpectedSign(String a, String b, int expectedSign) {
        SemverComparer.SemverVersion left = SemverComparer.parse(a);
        SemverComparer.SemverVersion right = SemverComparer.parse(b);
        assertThat(left).isNotNull();
        assertThat(right).isNotNull();

        assertThat(Integer.signum(SemverComparer.compare(left, right))).isEqualTo(expectedSign);
        // Comparison must be antisymmetric.
        assertThat(Integer.signum(SemverComparer.compare(right, left))).isEqualTo(-expectedSign);
    }

    /**
     * Prerelease alphanumeric identifiers are compared in case-sensitive ASCII sort order (semver
     * §11): {@code 'B'} (66) sorts before {@code 'a'} (97), and {@code RC != rc}. This is the
     * spec-correct behavior and intentionally diverges from the evaluation engine's current
     * {@code OrdinalIgnoreCase} folding (tracked in #1447).
     */
    @ParameterizedTest
    @CsvSource({
        "1.0.0-Beta, 1.0.0-alpha, -1", // 'B'(66) < 'a'(97) — case-sensitive, opposite of case-folding
        "1.0.0-RC,   1.0.0-rc,    -1", // mixed case is NOT equal
    })
    void compareIsCaseSensitiveForPrereleaseIdentifiers(String a, String b, int expectedSign) {
        SemverComparer.SemverVersion left = SemverComparer.parse(a);
        SemverComparer.SemverVersion right = SemverComparer.parse(b);
        assertThat(left).isNotNull();
        assertThat(right).isNotNull();

        assertThat(Integer.signum(SemverComparer.compare(left, right))).isEqualTo(expectedSign);
        assertThat(Integer.signum(SemverComparer.compare(right, left))).isEqualTo(-expectedSign);
    }
}
