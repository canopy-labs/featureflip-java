package io.featureflip.client.internal;

import io.featureflip.client.EvaluationContext;
import io.featureflip.client.EvaluationReason;
import io.featureflip.client.internal.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class FlagEvaluator {

    /**
     * Safety net against pathological prerequisite chains. Cycles are blocked at
     * write time on the server, so reaching this limit indicates a corrupt config.
     */
    static final int MAX_PREREQUISITE_DEPTH = 10;

    private final FlagStore store;

    public FlagEvaluator(FlagStore store) {
        this.store = store;
    }

    /**
     * Evaluates a flag with no prerequisite resolution. Equivalent to passing an
     * empty {@code allFlags} map — prerequisites declared on the flag will be
     * treated as missing and resolve to {@link EvaluationReason#PREREQUISITE_FAILED}.
     */
    public Result evaluate(FlagConfiguration flag, EvaluationContext context) {
        return evaluate(flag, context, null);
    }

    /**
     * Evaluates a flag, resolving any prerequisite flags using {@code allFlags}.
     *
     * @param allFlags map of all flags in the environment, keyed by flag key.
     *                 Required when the flag has prerequisites; pass {@code null}
     *                 if the flag is known to have no prerequisites.
     */
    public Result evaluate(FlagConfiguration flag, EvaluationContext context,
                           Map<String, FlagConfiguration> allFlags) {
        Map<String, Result> memo = new HashMap<>();
        return evaluateInternal(flag, context, allFlags, 0, memo);
    }

    /**
     * Evaluates a flag, sharing a memoisation map with other concurrent evaluations
     * (e.g. a batch "evaluate all" pass). Use this when evaluating multiple flags
     * in one sweep so that shared prerequisite flags are only evaluated once.
     */
    public Result evaluateWithSharedMemo(FlagConfiguration flag, EvaluationContext context,
                                         Map<String, FlagConfiguration> allFlags,
                                         Map<String, Result> memo) {
        return evaluateInternal(flag, context, allFlags, 0, memo);
    }

    private Result evaluateInternal(FlagConfiguration flag, EvaluationContext context,
                                    Map<String, FlagConfiguration> allFlags,
                                    int depth, Map<String, Result> memo) {
        if (depth > MAX_PREREQUISITE_DEPTH) {
            return new Result(flag.getOffVariation(), EvaluationReason.ERROR, null);
        }

        if (!flag.isEnabled()) {
            return new Result(flag.getOffVariation(), EvaluationReason.FLAG_DISABLED, null);
        }

        // Resolve prerequisites in order. A failing prerequisite short-circuits to the
        // off variation with reason PREREQUISITE_FAILED; error reasons propagate upward.
        for (Prerequisite prereq : flag.getPrerequisites()) {
            Result prereqResult = memo.get(prereq.getPrerequisiteFlagKey());

            if (prereqResult == null) {
                FlagConfiguration prereqFlag = allFlags != null
                    ? allFlags.get(prereq.getPrerequisiteFlagKey())
                    : null;

                if (prereqFlag == null) {
                    Result miss = new Result(flag.getOffVariation(),
                        EvaluationReason.PREREQUISITE_FAILED, null,
                        prereq.getPrerequisiteFlagKey());
                    memo.put(flag.getKey(), miss);
                    return miss;
                }

                prereqResult = evaluateInternal(prereqFlag, context, allFlags, depth + 1, memo);
                memo.put(prereq.getPrerequisiteFlagKey(), prereqResult);
            }

            if (prereqResult.getReason() == EvaluationReason.ERROR) {
                Result err = new Result(flag.getOffVariation(), EvaluationReason.ERROR, null);
                memo.put(flag.getKey(), err);
                return err;
            }

            if (!prereq.getExpectedVariationKey().equals(prereqResult.getVariationKey())) {
                Result failed = new Result(flag.getOffVariation(),
                    EvaluationReason.PREREQUISITE_FAILED, null,
                    prereq.getPrerequisiteFlagKey());
                memo.put(flag.getKey(), failed);
                return failed;
            }
        }

        List<TargetingRule> orderedRules = flag.getRules().stream()
            .sorted(Comparator.comparingInt(TargetingRule::getPriority))
            .collect(Collectors.toList());

        for (TargetingRule rule : orderedRules) {
            if (evaluateRule(rule, context)) {
                String variationKey = resolveServeConfig(rule.getServe(), context);
                Result ruleResult = new Result(variationKey, EvaluationReason.RULE_MATCH, rule.getId());
                memo.put(flag.getKey(), ruleResult);
                return ruleResult;
            }
        }

        if (flag.getFallthrough() != null) {
            String variationKey = resolveServeConfig(flag.getFallthrough(), context);
            Result fallResult = new Result(variationKey, EvaluationReason.FALLTHROUGH, null);
            memo.put(flag.getKey(), fallResult);
            return fallResult;
        }

        Result offResult = new Result(flag.getOffVariation(), EvaluationReason.FALLTHROUGH, null);
        memo.put(flag.getKey(), offResult);
        return offResult;
    }

    boolean evaluateRule(TargetingRule rule, EvaluationContext context) {
        // If rule references a segment, evaluate the segment's conditions instead.
        // A non-empty segmentKey must resolve its segment to match: if the segment
        // source isn't wired (store is null), or the segment can't be found, fail
        // closed (no match) — mirroring the engine + C# SDK — rather than falling
        // through to the rule's condition groups (which match unconditionally when
        // empty). See #1459.
        if (rule.getSegmentKey() != null && !rule.getSegmentKey().isEmpty()) {
            if (store == null) return false;
            Segment segment = store.getSegment(rule.getSegmentKey());
            if (segment == null) return false;
            return evaluateConditions(segment.getConditions(), segment.getConditionLogic(), context);
        }

        return evaluateConditionGroups(rule.getConditionGroups(), context);
    }

    private boolean evaluateConditionGroups(List<ConditionGroup> groups, EvaluationContext context) {
        if (groups == null || groups.isEmpty()) {
            return true;
        }

        // All groups must match (AND between groups)
        return groups.stream().allMatch(group ->
            evaluateConditions(group.getConditions(), group.getOperator(), context)
        );
    }

    private boolean evaluateConditions(List<Condition> conditions, ConditionLogic logic, EvaluationContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        if (logic == ConditionLogic.AND) {
            return conditions.stream().allMatch(c -> evaluateCondition(c, context));
        } else {
            return conditions.stream().anyMatch(c -> evaluateCondition(c, context));
        }
    }

    boolean evaluateCondition(Condition condition, EvaluationContext context) {
        Object attrValue = context.getAttribute(condition.getAttribute());

        // Missing attribute = non-match (unless negated), consistent with JS/Python SDKs
        if (attrValue == null) {
            return condition.isNegate();
        }

        // Type-aware numeric coercion for equality/membership operators (#1458).
        // When the attribute is a native Number — Java's Boolean does NOT implement
        // Number, so booleans are naturally excluded — compare the string condition
        // literals numerically rather than via stringification, so 1.0-vs-1 rendering
        // differences cannot diverge from the engine. Mirrors the engine's
        // TryGetNumericValue + double.TryParse equality branch. Only EQUALS/NOT_EQUALS/
        // IN/NOT_IN are coerced; CONTAINS/STARTS_WITH/ENDS_WITH stay on the string path.
        if (attrValue instanceof Number && isEqualityOperator(condition.getOperator())) {
            double a = ((Number) attrValue).doubleValue();
            boolean anyEqual = condition.getValues().stream().anyMatch(v -> {
                Double n = parseDouble(v);
                return n != null && n == a;
            });
            ConditionOperator op = condition.getOperator();
            boolean numericResult = (op == ConditionOperator.EQUALS || op == ConditionOperator.IN)
                ? anyEqual
                : !anyEqual; // NOT_EQUALS / NOT_IN
            return condition.isNegate() ? !numericResult : numericResult;
        }

        String stringValue = attrValue.toString();
        Boolean result = evaluateOperator(condition.getOperator(), stringValue, condition.getValues());

        // Issue #2262: an operator that cannot be evaluated fails CLOSED. Inverting
        // it with negate would turn "I cannot evaluate this" into "matches every
        // user", serving the flag to 100% of traffic. Java's enum typing means an
        // *unknown* operator cannot exist as a constant (Jackson raises
        // InvalidFormatException first), so the reachable shape here is a null
        // operator from a config that omits the field. Holding the invariant keeps
        // this SDK's contract identical to the string-typed SDKs (js/go/ruby/php),
        // where an unrecognised operator IS reachable over the wire.
        if (result == null) {
            return false;
        }

        return condition.isNegate() ? !result : result;
    }

    private static boolean isEqualityOperator(ConditionOperator op) {
        return op == ConditionOperator.EQUALS || op == ConditionOperator.NOT_EQUALS
            || op == ConditionOperator.IN || op == ConditionOperator.NOT_IN;
    }

    /**
     * Applies a single operator to {@code value}. Returns {@code null} — not {@code false} —
     * when the operator cannot be evaluated, so callers can tell "cannot evaluate" apart from
     * "evaluated, did not match"; only the latter may be inverted by negate (#2262).
     */
    Boolean evaluateOperator(ConditionOperator op, String value, List<String> targets) {
        if (op == null) {
            // Absent operator — "cannot evaluate", not "did not match". Guards the
            // switch below, which would throw NPE on a null enum.
            return null;
        }

        if (op == null) return false;
        switch (op) {
            case EQUALS:
                return targets.stream().anyMatch(t -> value.equalsIgnoreCase(t));
            case NOT_EQUALS:
                return targets.stream().allMatch(t -> !value.equalsIgnoreCase(t));
            case CONTAINS:
                return targets.stream().anyMatch(t -> value.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)));
            case NOT_CONTAINS:
                return targets.stream().allMatch(t -> !value.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)));
            case STARTS_WITH:
                return targets.stream().anyMatch(t -> value.toLowerCase(Locale.ROOT).startsWith(t.toLowerCase(Locale.ROOT)));
            case ENDS_WITH:
                return targets.stream().anyMatch(t -> value.toLowerCase(Locale.ROOT).endsWith(t.toLowerCase(Locale.ROOT)));
            case IN:
                return targets.stream().anyMatch(t -> value.equalsIgnoreCase(t));
            case NOT_IN:
                return targets.stream().allMatch(t -> !value.equalsIgnoreCase(t));
            case MATCHES_REGEX:
                return targets.stream().anyMatch(t -> matchesRegex(value, t));
            case GREATER_THAN:
                return compareNumeric(value, targets, c -> c > 0);
            case LESS_THAN:
                return compareNumeric(value, targets, c -> c < 0);
            case GREATER_THAN_OR_EQUAL:
                return compareNumeric(value, targets, c -> c >= 0);
            case LESS_THAN_OR_EQUAL:
                return compareNumeric(value, targets, c -> c <= 0);
            case BEFORE:
                return compareDateTime(value, targets, (a, b) -> a.isBefore(b));
            case AFTER:
                return compareDateTime(value, targets, (a, b) -> a.isAfter(b));
            case SEMVER_EQUALS:
                return compareSemver(value, targets, c -> c == 0);
            case SEMVER_GREATER_THAN:
                return compareSemver(value, targets, c -> c > 0);
            case SEMVER_GREATER_THAN_OR_EQUAL:
                return compareSemver(value, targets, c -> c >= 0);
            case SEMVER_LESS_THAN:
                return compareSemver(value, targets, c -> c < 0);
            case SEMVER_LESS_THAN_OR_EQUAL:
                return compareSemver(value, targets, c -> c <= 0);
            default:
                // Unrecognised operator — "cannot evaluate", not "did not match".
                return null;
        }
    }

    /**
     * Compares {@code value} against each condition value as a semantic version, returning true
     * when the comparison sign satisfies {@code predicate} for any condition value. Unparseable
     * versions contribute no match (consistent with the numeric and date/time operators). See
     * {@link SemverComparer} for the precedence rules.
     */
    private boolean compareSemver(String value, List<String> targets, IntPredicate predicate) {
        SemverComparer.SemverVersion left = SemverComparer.parse(value);
        if (left == null) {
            return false;
        }
        for (String target : targets) {
            SemverComparer.SemverVersion right = SemverComparer.parse(target);
            if (right != null && predicate.test(SemverComparer.compare(left, right))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRegex(String value, String pattern) {
        try {
            // Case-sensitive matching mirrors the engine (RegexOptions.None).
            // Case-insensitivity is opt-in via the (?i) inline flag in the pattern.
            //
            // ReDoS note (#1460): the engine bounds catastrophic backtracking with a
            // 100ms regex timeout, but java.util.regex has no built-in per-match
            // timeout — interrupting a match requires a watchdog thread or an
            // interruptible CharSequence, too heavyweight for this lightweight
            // evaluator. A pathological config pattern can therefore still be slow
            // here; an invalid pattern throws PatternSyntaxException → no match.
            return Pattern.compile(pattern).matcher(value).find();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compares {@code value} against each condition value as a number, returning true when the
     * comparison sign satisfies {@code predicate} for any condition value. A non-numeric value
     * matches nothing and non-numeric condition values are skipped — mirroring the engine's
     * {@code double.TryParse}. There is deliberately no lexical String-compare fallback: it
     * produced matches the engine rejects (e.g. "gold" &gt; "abc"), #1456.
     */
    private boolean compareNumeric(String value, List<String> targets, IntPredicate predicate) {
        Double left = parseDouble(value);
        if (left == null) {
            return false;
        }
        for (String target : targets) {
            Double right = parseDouble(target);
            if (right != null && predicate.test(Double.compare(left, right))) {
                return true;
            }
        }
        return false;
    }

    private static Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Compares {@code value} against each condition value as a date/time, returning true when
     * {@code comparison} holds against any condition value. Both sides are normalised to a UTC
     * {@link Instant} (see {@link #parseDateTime(String)}): an unparseable value matches nothing
     * and unparseable condition values are skipped — mirroring the engine's
     * {@code DateTimeOffset.TryParse}/unix-seconds logic. There is deliberately no lexical
     * String-compare fallback: it produced matches the engine rejects (#1455).
     */
    private boolean compareDateTime(String value, List<String> targets, BiPredicate<Instant, Instant> comparison) {
        Instant left = parseDateTime(value);
        if (left == null) {
            return false;
        }
        for (String target : targets) {
            Instant right = parseDateTime(target);
            if (right != null && comparison.test(left, right)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses {@code value} to a UTC-normalised {@link Instant}, mirroring the engine's date
     * handling. An ISO-8601 string carrying an offset ({@code +05:00} or {@code Z}) is honoured;
     * one without an offset is assumed UTC. A bare integer is treated as Unix time in seconds.
     * Returns {@code null} when none of those parses succeed.
     */
    /**
     * DateTimeOffset.MinValue / MaxValue as unix seconds — the exact bounds the engine's
     * {@code FromUnixTimeSeconds} accepts before throwing (#2432).
     */
    private static final long MIN_UNIX_SECONDS = -62135596800L;
    private static final long MAX_UNIX_SECONDS = 253402300799L;

    /**
     * The exact string shape the engine's {@code long.TryParse(v, NumberStyles.Integer,
     * CultureInfo.InvariantCulture, ...)} accepts as unix seconds: an optional ASCII sign
     * followed by ASCII digits.
     *
     * <p>{@link Long#parseLong} cannot be used as its own guard, because it resolves digits
     * through {@link Character#digit} and so accepts every character in Unicode category
     * {@code Nd} — {@code "\u0661\u0662\u0663"} (Arabic-Indic) parses to 123, {@code "\uFF15"}
     * (fullwidth) to 5. The engine accepts none of those, in either of its two branches:
     * invariant-culture number parsing is ASCII-only, and {@code DateTimeOffset.TryParse}
     * rejects a pure-numeric string outright. So a non-ASCII digit string is not a date to
     * the engine at all, and java was resolving it to an instant and matching alone (#2467).
     * The other SDKs already land on ASCII by construction — go's {@code strconv.ParseInt}
     * is ASCII-only, php's {@code preg_match} has no {@code /u}, and js/ruby {@code \d} is
     * ASCII absent a Unicode flag.
     */
    private static final Pattern UNIX_SECONDS = Pattern.compile("[+-]?[0-9]+");

    /**
     * The ONLY characters trimmed from a date operand, and the whole of the operand's
     * permitted whitespace: U+0009..U+000D plus U+0020 — exactly the class the engine's
     * {@code NumberStyles.Integer} accepts via {@code AllowLeadingWhite | AllowTrailingWhite}.
     *
     * <p>{@link String#trim()} is deliberately NOT used: it strips every character at or below
     * U+0020, so a NUL- or U+001C-prefixed operand was trimmed to {@code "5"} and matched here
     * while the engine rejected it (#2468). {@link String#strip()} is worse still (it is
     * Unicode-aware) and is unavailable on the Java 11 floor besides.
     */
    private static final String OPERAND_WHITESPACE = "\t\n\f\r ";

    /**
     * The ISO-8601 grammar a date operand may use: a calendar date, optionally followed by a
     * time (seconds and fractional seconds optional) and an optional offset in either extended
     * ({@code +05:00} / {@code Z}) or basic ({@code +0500}) form. The separator may be
     * {@code T} or a space — the engine accepts both.
     */
    private static final Pattern ISO_OPERAND = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?)?");

    /**
     * Trims only {@link #OPERAND_WHITESPACE}, then rejects any operand still carrying a
     * character no date operand may contain: a NUL or other control character, or a non-ASCII
     * whitespace/format character. An interior ASCII space is allowed — it is the ISO-8601
     * date/time separator. Returns {@code null} when the operand is rejected.
     */
    private static String normalizeOperand(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && OPERAND_WHITESPACE.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && OPERAND_WHITESPACE.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        String s = value.substring(start, end);
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                continue;
            }
            if (Character.isISOControl(c) || Character.isWhitespace(c)
                    || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT) {
                return null;
            }
        }
        return s;
    }

    /**
     * Rewrites an accepted ISO operand into the strict form {@code java.time} parses, so one
     * grammar covers every shape the engine accepts. Java's ISO parsers reject a space
     * separator and a basic offset outright, which is why java alone rejected
     * {@code "2024-01-01 00:00:00"} and {@code "2024-01-01T00:00:00+0500"} (#2468).
     * Returns {@code null} when the operand is not an accepted ISO shape.
     */
    private static String canonicalizeIso(String s) {
        Matcher m = ISO_OPERAND.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String date = m.group(1);
        String hh = m.group(2);
        if (hh == null) {
            return date;
        }
        // The engine's DateTimeOffset.TryParse rejects hour 24 outright rather than rolling
        // it over to 00:00 the next day.
        if (hh.compareTo("24") >= 0) {
            return null;
        }
        String mm = m.group(3);
        String ss = m.group(4) == null ? "00" : m.group(4);
        String frac = m.group(5) == null ? "" : m.group(5);
        String off = m.group(6) == null ? "" : m.group(6);
        // Basic offset (+0500) -> extended (+05:00); java.time accepts only the latter here.
        if (off.length() == 5 && !"Z".equals(off)) {
            off = off.substring(0, 3) + ":" + off.substring(3);
        }
        return date + "T" + hh + ":" + mm + ":" + ss + frac + off;
    }

    private static Instant parseDateTime(String rawValue) {
        String value = normalizeOperand(rawValue);
        if (value == null) {
            return null;
        }
        String canonical = canonicalizeIso(value);
        if (canonical != null) {
            // Offset-aware ISO-8601 (handles "+05:00" and "Z").
            try {
                return OffsetDateTime.parse(canonical).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            // ISO-8601 date-time without an offset — assume UTC.
            try {
                return LocalDateTime.parse(canonical).atOffset(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            // ISO-8601 date only — assume start-of-day UTC.
            try {
                return LocalDate.parse(canonical).atStartOfDay().atOffset(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        // Unix time in seconds.
        //
        // Out-of-range seconds match NOTHING rather than resolving to a far-future instant:
        // the engine's FromUnixTimeSeconds throws outside DateTimeOffset's range and
        // TryParseDateTime returns false. Instant's own range is vastly wider (it accepts
        // roughly +/-31 billion years) so it would happily take the value, which is why the
        // bound is explicit here. The case that matters in practice is a MILLISECONDS
        // timestamp pasted where seconds belong -- System.currentTimeMillis() is the obvious
        // way to produce one -- which would otherwise land in the year 55829 and satisfy
        // every After comparison (#2432).
        if (!UNIX_SECONDS.matcher(value).matches()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            if (seconds < MIN_UNIX_SECONDS || seconds > MAX_UNIX_SECONDS) {
                return null;
            }
            return Instant.ofEpochSecond(seconds);
        } catch (NumberFormatException | DateTimeException ignored) {
            // Still reachable: an all-ASCII digit string wider than a long satisfies the
            // pattern but overflows the parse, so it never reaches the bounds check.
            return null;
        }
    }

    String resolveServeConfig(ServeConfig serve, EvaluationContext context) {
        if (serve.getType() == ServeType.FIXED) {
            return serve.getVariation() != null ? serve.getVariation() : "";
        }

        // Rollout
        String bucketBy = serve.getBucketBy() != null ? serve.getBucketBy() : "userId";
        Object bucketAttr = context.getAttribute(bucketBy);
        String bucketValue = bucketAttr != null ? bucketAttr.toString() : "";
        String salt = serve.getSalt() != null ? serve.getSalt() : "";

        List<WeightedVariation> variations = serve.getVariations();

        // Keyless user contexts can't be bucketed. Rather than hashing the empty value
        // into an arbitrary salt-dependent bucket, serve the control (first) variation
        // deterministically. The engine assigns a random GUID per eval (spreading
        // anonymous users over HTTP); local SDK eval is deterministic, so parity is
        // guaranteed only for keyed contexts (#1457).
        if (bucketValue.isEmpty() && (bucketBy.equals("userId") || bucketBy.equals("user_id")) && variations != null && !variations.isEmpty())
            return variations.get(0).getKey();

        int bucket = calculateBucket(salt, bucketValue);

        if (variations == null || variations.isEmpty()) {
            return serve.getVariation() != null ? serve.getVariation() : "";
        }

        int cumulative = 0;
        for (WeightedVariation wv : variations) {
            cumulative += wv.getWeight();
            if (bucket < cumulative) {
                return wv.getKey();
            }
        }

        return variations.get(variations.size() - 1).getKey();
    }

    int calculateBucket(String salt, String value) {
        String input = salt + ":" + value;
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hash = md5.digest(input.getBytes(StandardCharsets.UTF_8));
            // Read first 4 bytes as little-endian uint32
            long hashValue = (hash[0] & 0xFFL)
                           | ((hash[1] & 0xFFL) << 8)
                           | ((hash[2] & 0xFFL) << 16)
                           | ((hash[3] & 0xFFL) << 24);
            return (int) (hashValue % 100);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private String convertToString(Object value) {
        if (value == null) return "";
        return value.toString();
    }

    public static final class Result {
        private final String variationKey;
        private final EvaluationReason reason;
        private final String ruleId;
        private final String prerequisiteKey;

        public Result(String variationKey, EvaluationReason reason, String ruleId) {
            this(variationKey, reason, ruleId, null);
        }

        public Result(String variationKey, EvaluationReason reason, String ruleId, String prerequisiteKey) {
            this.variationKey = variationKey;
            this.reason = reason;
            this.ruleId = ruleId;
            this.prerequisiteKey = prerequisiteKey;
        }

        public String getVariationKey() { return variationKey; }
        public EvaluationReason getReason() { return reason; }
        public String getRuleId() { return ruleId; }
        public String getPrerequisiteKey() { return prerequisiteKey; }
    }
}
