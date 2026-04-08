package io.featureflip.client.internal;

import io.featureflip.client.EvaluationContext;
import io.featureflip.client.EvaluationReason;
import io.featureflip.client.internal.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class FlagEvaluator {

    private final FlagStore store;

    public FlagEvaluator(FlagStore store) {
        this.store = store;
    }

    public Result evaluate(FlagConfiguration flag, EvaluationContext context) {
        if (!flag.isEnabled()) {
            return new Result(flag.getOffVariation(), EvaluationReason.FLAG_DISABLED, null);
        }

        List<TargetingRule> orderedRules = flag.getRules().stream()
            .sorted(Comparator.comparingInt(TargetingRule::getPriority))
            .collect(Collectors.toList());

        for (TargetingRule rule : orderedRules) {
            if (evaluateRule(rule, context)) {
                String variationKey = resolveServeConfig(rule.getServe(), context, flag.getKey());
                return new Result(variationKey, EvaluationReason.RULE_MATCH, rule.getId());
            }
        }

        if (flag.getFallthrough() != null) {
            String variationKey = resolveServeConfig(flag.getFallthrough(), context, flag.getKey());
            return new Result(variationKey, EvaluationReason.FALLTHROUGH, null);
        }

        return new Result(flag.getOffVariation(), EvaluationReason.FALLTHROUGH, null);
    }

    boolean evaluateRule(TargetingRule rule, EvaluationContext context) {
        // If rule references a segment, evaluate the segment's conditions instead
        if (rule.getSegmentKey() != null && !rule.getSegmentKey().isEmpty() && store != null) {
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

        String stringValue = attrValue.toString();
        boolean result = evaluateOperator(condition.getOperator(), stringValue, condition.getValues());
        return condition.isNegate() ? !result : result;
    }

    boolean evaluateOperator(ConditionOperator op, String value, List<String> targets) {
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
                return targets.stream().anyMatch(t -> compareNumeric(value, t) > 0);
            case LESS_THAN:
                return targets.stream().anyMatch(t -> compareNumeric(value, t) < 0);
            case GREATER_THAN_OR_EQUAL:
                return targets.stream().anyMatch(t -> compareNumeric(value, t) >= 0);
            case LESS_THAN_OR_EQUAL:
                return targets.stream().anyMatch(t -> compareNumeric(value, t) <= 0);
            case BEFORE:
                return targets.stream().anyMatch(t -> compareDateTime(value, t) < 0);
            case AFTER:
                return targets.stream().anyMatch(t -> compareDateTime(value, t) > 0);
            default:
                return false;
        }
    }

    private boolean matchesRegex(String value, String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value).find();
        } catch (Exception e) {
            return false;
        }
    }

    private int compareNumeric(String value, String target) {
        try {
            double numValue = Double.parseDouble(value);
            double numTarget = Double.parseDouble(target);
            return Double.compare(numValue, numTarget);
        } catch (NumberFormatException e) {
            return value.compareToIgnoreCase(target);
        }
    }

    private int compareDateTime(String value, String target) {
        try {
            java.time.Instant v = java.time.Instant.parse(value);
            java.time.Instant t = java.time.Instant.parse(target);
            return v.compareTo(t);
        } catch (Exception e) {
            return value.compareToIgnoreCase(target);
        }
    }

    String resolveServeConfig(ServeConfig serve, EvaluationContext context, String flagKey) {
        if (serve.getType() == ServeType.FIXED) {
            return serve.getVariation() != null ? serve.getVariation() : "";
        }

        // Rollout
        String bucketBy = serve.getBucketBy() != null ? serve.getBucketBy() : "userId";
        Object bucketAttr = context.getAttribute(bucketBy);
        String bucketValue = bucketAttr != null ? bucketAttr.toString() : "";
        String salt = serve.getSalt() != null ? serve.getSalt() : flagKey;

        int bucket = calculateBucket(salt, bucketValue);

        List<WeightedVariation> variations = serve.getVariations();
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

        public Result(String variationKey, EvaluationReason reason, String ruleId) {
            this.variationKey = variationKey;
            this.reason = reason;
            this.ruleId = ruleId;
        }

        public String getVariationKey() { return variationKey; }
        public EvaluationReason getReason() { return reason; }
        public String getRuleId() { return ruleId; }
    }
}
