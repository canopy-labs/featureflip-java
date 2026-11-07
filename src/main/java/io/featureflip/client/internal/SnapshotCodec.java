package io.featureflip.client.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.GetFlagsResponse;
import io.featureflip.client.internal.model.Segment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds and checks the text kept in a {@code SnapshotStore}.
 *
 * <p>The envelope is {@code {"v":1,"keyHash":...,"fetchedAt":<epoch ms>,"data":<wire state>}}.
 * {@code data} is the full in-memory state in the real wire spelling (PascalCase enums), so
 * it is parsed back by the same mapper and the same entity drop a live payload goes through.
 * The SDK key itself is never stored.
 */
public final class SnapshotCodec {
    /** How far in the future a stored {@code fetchedAt} may be and still be accepted. */
    public static final long FUTURE_FETCHED_AT_TOLERANCE_MS = 60_000L;

    /**
     * Deterministic output: the unchanged-state comparison that throttles rewrites relies
     * on equal state serializing to equal text. Properties are sorted, map entries are
     * sorted, and callers sort flags and segments by key.
     */
    private static final ObjectMapper WRITER = JsonMapper.builder()
        .defaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .build();

    private SnapshotCodec() {}

    /** Stored text failed a check. {@link #getWhy()} names the check, never the contents or key. */
    public static final class SnapshotInvalidException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String why;

        SnapshotInvalidException(String why) {
            super(why);
            this.why = why;
        }

        public String getWhy() { return why; }
    }

    /** A validated snapshot, already parsed into models. */
    public static final class Decoded {
        private final List<FlagConfiguration> flags;
        private final List<Segment> segments;
        private final long fetchedAtMs;

        Decoded(List<FlagConfiguration> flags, List<Segment> segments, long fetchedAtMs) {
            this.flags = flags;
            this.segments = segments;
            this.fetchedAtMs = fetchedAtMs;
        }

        public List<FlagConfiguration> getFlags() { return flags; }
        public List<Segment> getSegments() { return segments; }
        public long getFetchedAtMs() { return fetchedAtMs; }
    }

    /** Identifies the (base URL, SDK key) pair without storing the key. */
    public static String computeKeyHash(String baseUrl, String sdkKey) {
        String trimmed = baseUrl.replaceAll("/+$", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((trimmed + "\n" + sdkKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The wire-shape state as text: {@code {"flags":[...],"segments":[...]}}, both sorted
     * by key.
     */
    public static String serializeState(Map<String, FlagConfiguration> flags, Map<String, Segment> segments)
            throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flags", new ArrayList<>(new TreeMap<>(flags).values()));
        data.put("segments", new ArrayList<>(new TreeMap<>(segments).values()));
        return WRITER.writeValueAsString(data);
    }

    /** {@link #serializeState(Map, Map)} for the store's current state. */
    public static String serializeState(FlagStore store) throws JsonProcessingException {
        FlagStore.Snapshot state = store.current();
        return serializeState(state.flags, state.segments);
    }

    /** The stored text, from an already serialized {@code data}. */
    public static String encodeEnvelope(String keyHash, long fetchedAtMs, String dataText) {
        try {
            return "{\"v\":1,\"keyHash\":" + WRITER.writeValueAsString(keyHash)
                + ",\"fetchedAt\":" + fetchedAtMs + ",\"data\":" + dataText + "}";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses and checks stored text, in the pinned order: unparseable (including empty),
     * not an object, {@code v != 1}, {@code keyHash} mismatch, {@code fetchedAt} not a
     * positive integer, {@code fetchedAt} more than 60 s ahead, {@code data} missing or
     * failing the same payload validation a live response goes through. Age is not
     * checked here.
     *
     * @param mapper the wire mapper, see {@link FlagHttpClient#newObjectMapper()}
     */
    public static Decoded decode(String text, String keyHash, long nowMs, ObjectMapper mapper)
            throws SnapshotInvalidException {
        if (text == null) {
            throw new SnapshotInvalidException("store returned no text");
        }
        if (text.isEmpty()) {
            throw new SnapshotInvalidException("empty text");
        }
        JsonNode env;
        try {
            env = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(text);
        } catch (Exception e) {
            throw new SnapshotInvalidException("not valid JSON");
        }
        if (env == null || !env.isObject()) {
            throw new SnapshotInvalidException("not a snapshot envelope");
        }
        JsonNode version = env.get("v");
        if (version == null || !version.isNumber() || version.asDouble() != 1d) {
            throw new SnapshotInvalidException("unsupported envelope version");
        }
        JsonNode storedHash = env.get("keyHash");
        if (storedHash == null || !storedHash.isTextual() || !keyHash.equals(storedHash.textValue())) {
            throw new SnapshotInvalidException("written for a different SDK key or base URL");
        }
        long fetchedAt = positiveInteger(env.get("fetchedAt"));
        if (fetchedAt <= 0) {
            throw new SnapshotInvalidException("fetchedAt is not a positive integer");
        }
        if (fetchedAt > nowMs + FUTURE_FETCHED_AT_TOLERANCE_MS) {
            throw new SnapshotInvalidException("fetchedAt is in the future");
        }
        JsonNode data = env.get("data");
        if (data == null || !data.isObject()) {
            throw new SnapshotInvalidException("missing data");
        }
        try {
            if (!isArrayOfObjects(data.get("flags")) || !isArrayOfObjects(data.get("segments"))) {
                throw new IllegalArgumentException("flags and segments must be arrays of objects");
            }
            GetFlagsResponse response = mapper.treeToValue(data, GetFlagsResponse.class);
            UnevaluableEntities.dropUnevaluable(response);
            return new Decoded(response.getFlags(), response.getSegments(), fetchedAt);
        } catch (Exception e) {
            throw new SnapshotInvalidException("data failed payload validation");
        }
    }

    private static boolean isArrayOfObjects(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode element : node) {
            if (!element.isObject()) {
                return false;
            }
        }
        return true;
    }

    /** The value when {@code node} is a positive whole number that fits a long, else -1. */
    private static long positiveInteger(JsonNode node) {
        if (node == null || !node.isNumber() || !node.canConvertToLong()) {
            return -1;
        }
        double asDouble = node.asDouble();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble != Math.floor(asDouble)) {
            return -1;
        }
        long value = node.asLong();
        return value > 0 ? value : -1;
    }
}
