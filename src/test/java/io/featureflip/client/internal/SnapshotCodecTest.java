package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featureflip.client.SnapshotTestSupport;
import io.featureflip.client.internal.model.GetFlagsResponse;
import io.featureflip.client.internal.model.ServeType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.featureflip.client.SnapshotTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotCodecTest {
    private static final long NOW = 1_800_000_000_000L;
    private final ObjectMapper mapper = FlagHttpClient.newObjectMapper();
    private final String hash = keyHash();

    private String why(String text) {
        return catchWhy(text, hash);
    }

    private String catchWhy(String text, String forHash) {
        try {
            SnapshotCodec.decode(text, forHash, NOW, mapper);
            return null;
        } catch (SnapshotCodec.SnapshotInvalidException e) {
            return e.getWhy();
        }
    }

    private String validData() {
        return wireData(flagJson("f", true), "");
    }

    @Test
    void keyHashIsSha256OfTrimmedBaseUrlNewlineKey() {
        assertThat(SnapshotCodec.computeKeyHash("https://x.test///", "k"))
            .isEqualTo(SnapshotCodec.computeKeyHash("https://x.test", "k"))
            // sha256("https://x.test\nk"), computed independently
            .isEqualTo("9d1a60eb1ec725ee80910d0354e4e6d4603628a78afc48cf84a7628cdff013b7");
        assertThat(SnapshotCodec.computeKeyHash("https://x.test", "k"))
            .isNotEqualTo(SnapshotCodec.computeKeyHash("https://x.test", "k2"))
            .isNotEqualTo(SnapshotCodec.computeKeyHash("https://y.test", "k"));
    }

    @Test
    void validEnvelopeDecodes() throws Exception {
        SnapshotCodec.Decoded d = SnapshotCodec.decode(envelope(hash, NOW - 5_000, validData()), hash, NOW, mapper);
        assertThat(d.getFlags()).hasSize(1);
        assertThat(d.getFlags().get(0).getKey()).isEqualTo("f");
        assertThat(d.getFetchedAtMs()).isEqualTo(NOW - 5_000);
    }

    @Test
    void everyPinnedFailureIsInvalidInOrder() {
        assertThat(catchWhy("", hash)).isEqualTo("empty text");
        assertThat(why("{not json")).isEqualTo("not valid JSON");
        assertThat(why(envelope(hash, NOW, validData()).substring(0, 40))).isEqualTo("not valid JSON");
        assertThat(why("{} trailing")).isEqualTo("not valid JSON");
        assertThat(why("[1,2]")).isEqualTo("not a snapshot envelope");
        assertThat(why("\"text\"")).isEqualTo("not a snapshot envelope");
        assertThat(why(envelope(hash, NOW, validData()).replace("\"v\":1", "\"v\":2")))
            .isEqualTo("unsupported envelope version");
        assertThat(why(envelope(hash, NOW, validData()).replace("\"v\":1", "\"v\":true")))
            .isEqualTo("unsupported envelope version");
        assertThat(why(envelope("deadbeef", NOW, validData()))).isEqualTo("written for a different SDK key or base URL");
        assertThat(why(envelope(hash, 0, validData()))).isEqualTo("fetchedAt is not a positive integer");
        assertThat(why(envelope(hash, -5, validData()))).isEqualTo("fetchedAt is not a positive integer");
        assertThat(why("{\"v\":1,\"keyHash\":\"" + hash + "\",\"fetchedAt\":1.5,\"data\":" + validData() + "}"))
            .isEqualTo("fetchedAt is not a positive integer");
        assertThat(why("{\"v\":1,\"keyHash\":\"" + hash + "\",\"fetchedAt\":\"1\",\"data\":" + validData() + "}"))
            .isEqualTo("fetchedAt is not a positive integer");
        assertThat(why("{\"v\":1,\"keyHash\":\"" + hash + "\",\"data\":" + validData() + "}"))
            .isEqualTo("fetchedAt is not a positive integer");
        assertThat(why("{\"v\":1,\"keyHash\":\"" + hash + "\",\"fetchedAt\":" + NOW + "}"))
            .isEqualTo("missing data");
        assertThat(why(envelope(hash, NOW, "[]"))).isEqualTo("missing data");
        assertThat(why(envelope(hash, NOW, "{\"flags\":[],\"segments\":{}}"))).isEqualTo("data failed payload validation");
        assertThat(why(envelope(hash, NOW, "{\"flags\":[]}"))).isEqualTo("data failed payload validation");
        assertThat(why(envelope(hash, NOW, "{\"flags\":[1],\"segments\":[]}"))).isEqualTo("data failed payload validation");
    }

    @Test
    void integerEnumInDataFailsPayloadValidationWholesale() {
        String data = wireData(flagJson("f", true).replace("\"type\":\"Boolean\"", "\"type\":0"), "");
        assertThat(why(envelope(hash, NOW, data))).isEqualTo("data failed payload validation");
    }

    @Test
    void futureTolerance() {
        assertThat(why(envelope(hash, NOW + 60_000, validData()))).isNull();
        assertThat(why(envelope(hash, NOW + 61_000, validData()))).isEqualTo("fetchedAt is in the future");
    }

    @Test
    void whenSeveralChecksFailTheEarliestInThePinnedOrderIsReported() {
        // v:2 AND the wrong key hash: version is checked first.
        assertThat(why(envelope("deadbeef", NOW, validData()).replace("\"v\":1", "\"v\":2")))
            .isEqualTo("unsupported envelope version");
        // wrong key hash AND a bad fetchedAt: the hash comes first.
        assertThat(why(envelope("deadbeef", 0, validData()))).isEqualTo("written for a different SDK key or base URL");
        // future fetchedAt AND missing data: fetchedAt comes first.
        assertThat(why("{\"v\":1,\"keyHash\":\"" + hash + "\",\"fetchedAt\":" + (NOW + 120_000) + "}"))
            .isEqualTo("fetchedAt is in the future");
    }

    @Test
    void textNeverContainsTheKey() throws Exception {
        String text = SnapshotCodec.encodeEnvelope(hash, NOW, validData());
        assertThat(text).doesNotContain(SDK_KEY);
    }

    @Test
    void persistedStateRoundTripsThroughTheSdksOwnParserInRealWireSpelling() throws Exception {
        String wire = "{\"flags\":[" + richFlagJson("z-flag") + "," + richFlagJson("a-flag") + "],\"segments\":["
            + segmentJson("beta") + "]}";
        GetFlagsResponse parsed = parseWire(wire);
        Map<String, io.featureflip.client.internal.model.FlagConfiguration> flags = new LinkedHashMap<>();
        parsed.getFlags().forEach(f -> flags.put(f.getKey(), f));
        Map<String, io.featureflip.client.internal.model.Segment> segments = new LinkedHashMap<>();
        parsed.getSegments().forEach(s -> segments.put(s.getKey(), s));

        String data = SnapshotCodec.serializeState(flags, segments);

        // Real wire casing, not the Java constant names.
        assertThat(data).contains("\"type\":\"String\"", "\"Fixed\"", "\"Rollout\"", "\"Or\"", "\"And\"",
            "\"In\"", "\"SemverGreaterThanOrEqual\"")
            .doesNotContain("FIXED", "ROLLOUT", "\"OR\"", "SEMVER_", "STRING");
        // Sorted by key, so equal state serializes equal regardless of insertion order.
        JsonNode tree = mapper.readTree(data);
        assertThat(tree.get("flags").get(0).get("key").asText()).isEqualTo("a-flag");
        assertThat(tree.get("flags").get(1).get("key").asText()).isEqualTo("z-flag");

        Map<String, io.featureflip.client.internal.model.FlagConfiguration> reversed = new LinkedHashMap<>();
        reversed.put("a-flag", flags.get("a-flag"));
        reversed.put("z-flag", flags.get("z-flag"));
        assertThat(SnapshotCodec.serializeState(reversed, segments)).isEqualTo(data);

        SnapshotCodec.Decoded back = SnapshotCodec.decode(
            SnapshotCodec.encodeEnvelope(hash, NOW, data), hash, NOW, mapper);
        assertThat(back.getFlags()).hasSize(2);
        assertThat(back.getSegments()).hasSize(1);
        var rule2 = back.getFlags().get(0).getRules().get(1);
        assertThat(rule2.getServe().getType()).isEqualTo(ServeType.ROLLOUT);
        assertThat(rule2.getServe().getVariations()).hasSize(2);
        assertThat(rule2.getSegmentKey()).isEqualTo("beta");
        assertThat(back.getFlags().get(0).getRules().get(0).getConditionGroups().get(0).getOperator())
            .isEqualTo(io.featureflip.client.internal.model.ConditionLogic.OR);
        // Structurally equal to the wire it came from (nulls omitted on both sides), flags in key order.
        JsonNode original = mapper.readTree(wire);
        com.fasterxml.jackson.databind.node.ArrayNode sortedFlags = mapper.createArrayNode();
        sortedFlags.add(original.get("flags").get(1));
        sortedFlags.add(original.get("flags").get(0));
        assertThat(tree.get("flags")).isEqualTo(sortedFlags);
        assertThat(tree.get("segments")).isEqualTo(original.get("segments"));
        // Serializing what was read back is byte-identical: a stable fixed point.
        Map<String, io.featureflip.client.internal.model.FlagConfiguration> f2 = new LinkedHashMap<>();
        back.getFlags().forEach(f -> f2.put(f.getKey(), f));
        Map<String, io.featureflip.client.internal.model.Segment> s2 = new LinkedHashMap<>();
        back.getSegments().forEach(s -> s2.put(s.getKey(), s));
        assertThat(SnapshotCodec.serializeState(f2, s2)).isEqualTo(data);
    }

    @Test
    void decodeNullIsInvalid() {
        assertThatThrownBy(() -> SnapshotCodec.decode(null, hash, NOW, mapper))
            .isInstanceOf(SnapshotCodec.SnapshotInvalidException.class);
    }
}
