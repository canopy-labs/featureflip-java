package io.featureflip.client.internal;

import io.featureflip.client.internal.model.ConditionGroup;
import io.featureflip.client.internal.model.ConditionLogic;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.GetFlagsResponse;
import io.featureflip.client.internal.model.Segment;
import io.featureflip.client.internal.model.ServeConfig;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.TargetingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity-level drop for enum values this SDK build cannot evaluate (#2402).
 *
 * <p>{@code ServeType} and {@code ConditionLogic} are the two enums that are BOTH
 * carried on the wire as strings AND consulted by {@code FlagEvaluator}, and each
 * dispatches on a two-way branch with no third arm:
 *
 * <pre>
 *   serve.getType() == FIXED ... else ROLLOUT
 *   logic == AND             ... else ANY (OR)
 * </pre>
 *
 * <p>So an unrecognised value cannot simply be tolerated the way an unknown
 * {@code FlagType} is (#2395) — it would take the ELSE arm and silently serve a rollout,
 * or match ANY condition where the server asked for ALL, so a rule fails OPEN and
 * over-targets. Equally it cannot keep throwing {@code InvalidFormatException} out of
 * the whole payload, because one additive server change then takes down every flag on a
 * pinned client — the #2372/#2395 outage shape, reached by an ordinary server release.
 *
 * <p>So the containing entity is dropped and the rest of the payload applies. The caller
 * gets their default for exactly the affected flag through the FLAG_NOT_FOUND path the
 * SDK already implements. Dropping a SEGMENT leaves rules pointing at it dangling, which
 * is safe because {@code FlagEvaluator} already returns false for an unresolvable
 * {@code segmentKey} (#1459) — the cascade fails CLOSED, pinned by the engine-generated
 * {@code f-segment-unresolvable} golden vector.
 *
 * <p>Keyed off the {@code UNRECOGNIZED} sentinel, never off null: null is what an ABSENT
 * field deserializes to, which is the missing-required-field axis and deliberately out
 * of scope here. Checking only values that arrived and were not recognised keeps this
 * change purely additive.
 */
final class UnevaluableEntities {
    private static final Logger log = LoggerFactory.getLogger(UnevaluableEntities.class);

    private UnevaluableEntities() {}

    /**
     * Replace {@code response}'s flag and segment lists with the entities this build can
     * evaluate, logging one line per drop.
     *
     * <p>Applied at every parse boundary that produces a full snapshot. Letting the
     * transports diverge on the same payload shape is its own bug class (#2279).
     */
    static void dropUnevaluable(GetFlagsResponse response) {
        List<FlagConfiguration> flags = new ArrayList<>();
        for (FlagConfiguration flag : response.getFlags()) {
            String reason = flagReason(flag);
            if (reason != null) {
                log.warn("Dropping flag '{}': {}. This SDK version may be older than the flag "
                    + "configuration; the rest of the configuration was applied.", key(flag), reason);
                continue;
            }
            flags.add(flag);
        }
        response.setFlags(flags);

        List<Segment> segments = new ArrayList<>();
        for (Segment segment : response.getSegments()) {
            String reason = segmentReason(segment);
            if (reason != null) {
                log.warn("Dropping segment '{}': {}. This SDK version may be older than the flag "
                    + "configuration; the rest of the configuration was applied.",
                    segment == null ? null : segment.getKey(), reason);
                continue;
            }
            segments.add(segment);
        }
        response.setSegments(segments);
    }

    /**
     * Why this flag cannot be evaluated, or {@code null} if it can. A reason rather than
     * a boolean so the diagnostic can name the FIELD that carried the bad value.
     *
     * <p>The value itself is gone by this point — the sentinel does not carry it — so
     * {@code FlagHttpClient}'s unknown-enum handler logs the raw name at the moment it
     * sees it. Between the two lines an operator gets both halves: which value the
     * server sent, and which entity disappeared because of it.
     */
    static String flagReason(FlagConfiguration flag) {
        if (flag == null) return null;

        String fallthrough = serveReason(flag.getFallthrough(), "fallthrough");
        if (fallthrough != null) return fallthrough;

        List<TargetingRule> rules = flag.getRules();
        if (rules == null) return null;

        for (TargetingRule rule : rules) {
            if (rule == null) continue;

            String serve = serveReason(rule.getServe(), "rule[" + rule.getId() + "].serve");
            if (serve != null) return serve;

            List<ConditionGroup> groups = rule.getConditionGroups();
            if (groups == null) continue;
            for (ConditionGroup group : groups) {
                if (group != null && group.getOperator() == ConditionLogic.UNRECOGNIZED) {
                    return "rule[" + rule.getId() + "].conditionGroup.operator is not a condition "
                        + "logic this SDK version understands";
                }
            }
        }

        return null;
    }

    /** Why this segment cannot be evaluated, or {@code null} if it can. */
    static String segmentReason(Segment segment) {
        if (segment != null && segment.getConditionLogic() == ConditionLogic.UNRECOGNIZED) {
            return "conditionLogic is not a condition logic this SDK version understands";
        }
        return null;
    }

    private static String serveReason(ServeConfig serve, String path) {
        if (serve != null && serve.getType() == ServeType.UNRECOGNIZED) {
            return path + ".type is not a serve type this SDK version understands";
        }
        return null;
    }

    private static String key(FlagConfiguration flag) {
        return flag == null ? null : flag.getKey();
    }
}
