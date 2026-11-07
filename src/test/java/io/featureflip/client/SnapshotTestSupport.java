package io.featureflip.client;

import io.featureflip.client.internal.FlagHttpClient;
import io.featureflip.client.internal.SnapshotCodec;
import io.featureflip.client.internal.model.GetFlagsResponse;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared fixtures for the snapshot store tests. */
public final class SnapshotTestSupport {
    private SnapshotTestSupport() {}

    public static final String BASE_URL = "http://localhost:1";
    public static final String SDK_KEY = "sdk-key-snapshot";

    /** A boolean flag in the real wire spelling that serves {@code value} to everyone. */
    public static String flagJson(String key, boolean value) {
        return "{\"key\":\"" + key + "\",\"version\":3,\"type\":\"Boolean\",\"enabled\":true,"
            + "\"variations\":[{\"key\":\"on\",\"value\":true},{\"key\":\"off\",\"value\":false}],"
            + "\"rules\":[],\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"" + (value ? "on" : "off") + "\"},"
            + "\"offVariation\":\"off\",\"prerequisites\":[]}";
    }

    /** A richer flag exercising every enum that has a wire spelling. */
    public static String richFlagJson(String key) {
        return "{\"key\":\"" + key + "\",\"version\":7,\"type\":\"String\",\"enabled\":true,"
            + "\"variations\":[{\"key\":\"a\",\"value\":\"A\"},{\"key\":\"b\",\"value\":\"B\"}],"
            + "\"rules\":[{\"id\":\"r1\",\"priority\":1,\"conditionGroups\":[{\"operator\":\"Or\",\"conditions\":["
            + "{\"attribute\":\"plan\",\"operator\":\"In\",\"values\":[\"pro\",\"team\"],\"negate\":false},"
            + "{\"attribute\":\"v\",\"operator\":\"SemverGreaterThanOrEqual\",\"values\":[\"1.2.0\"],\"negate\":true}]}],"
            + "\"serve\":{\"type\":\"Fixed\",\"variation\":\"b\"}},"
            + "{\"id\":\"r2\",\"priority\":2,\"conditionGroups\":[],\"segmentKey\":\"beta\","
            + "\"serve\":{\"type\":\"Rollout\",\"bucketBy\":\"userId\",\"salt\":\"s\","
            + "\"variations\":[{\"key\":\"a\",\"weight\":30},{\"key\":\"b\",\"weight\":70}]}}],"
            + "\"fallthrough\":{\"type\":\"Fixed\",\"variation\":\"a\"},\"offVariation\":\"a\","
            + "\"prerequisites\":[{\"prerequisiteFlagKey\":\"p\",\"expectedVariationKey\":\"on\"}]}";
    }

    public static String segmentJson(String key) {
        return "{\"key\":\"" + key + "\",\"version\":2,\"conditionLogic\":\"And\",\"conditions\":["
            + "{\"attribute\":\"country\",\"operator\":\"Equals\",\"values\":[\"US\"],\"negate\":false}]}";
    }

    public static String wireData(String flagsJson, String segmentsJson) {
        return "{\"flags\":[" + flagsJson + "],\"segments\":[" + segmentsJson + "]}";
    }

    /** The /v1/sdk/flags response body. */
    public static String flagsResponse(String key, boolean value) {
        return "{\"environment\":\"test\",\"version\":1,\"flags\":[" + flagJson(key, value) + "],\"segments\":[]}";
    }

    public static String keyHash() {
        return SnapshotCodec.computeKeyHash(BASE_URL, SDK_KEY);
    }

    public static String envelope(String keyHash, long fetchedAt, String dataJson) {
        return "{\"v\":1,\"keyHash\":\"" + keyHash + "\",\"fetchedAt\":" + fetchedAt + ",\"data\":" + dataJson + "}";
    }

    public static GetFlagsResponse parseWire(String json) throws Exception {
        return FlagHttpClient.newObjectMapper().readValue(json, GetFlagsResponse.class);
    }

    /** Polls {@code condition} for up to {@code seconds}. */
    public static boolean eventually(int seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    /** In-memory store that records puts. */
    public static class MemoryStore implements SnapshotStore {
        public volatile String text;
        public final java.util.List<String> puts = new java.util.concurrent.CopyOnWriteArrayList<>();
        public volatile int gets;

        @Override
        public String get() {
            gets++;
            return text;
        }

        @Override
        public void put(String value) {
            puts.add(value);
            text = value;
        }
    }
}
