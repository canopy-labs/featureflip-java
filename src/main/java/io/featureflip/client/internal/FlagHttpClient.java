package io.featureflip.client.internal;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.featureflip.client.FeatureFlagConfig;
import io.featureflip.client.internal.model.ConditionOperator;
import io.featureflip.client.internal.model.FlagConfiguration;
import io.featureflip.client.internal.model.ConditionLogic;
import io.featureflip.client.internal.model.FlagType;
import io.featureflip.client.internal.model.ServeType;
import io.featureflip.client.internal.model.GetFlagsResponse;
import io.featureflip.client.internal.model.SdkEvent;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FlagHttpClient {
    private static final Logger log = LoggerFactory.getLogger(FlagHttpClient.class);

    /**
     * Tolerates a {@link ConditionOperator} name this SDK build does not know, mapping it
     * to {@code null} instead of throwing (#2372).
     *
     * <p>Jackson raises {@code InvalidFormatException} on an unknown enum name, and because
     * that surfaces while reading the whole flags payload, ONE unrecognised operator in ONE
     * condition of ONE flag failed the ENTIRE fetch — every flag fell back to the caller's
     * defaults, not just the flag carrying it. The trigger is ordinary: a new operator
     * shipped server-side reaching an SDK pinned to an older version.
     *
     * <p>{@code null} is safe because {@code FlagEvaluator} fails a null operator CLOSED
     * (#2262) — the condition simply does not match, and {@code negate} never inverts it
     * into a match-everyone.
     *
     * <p>{@code FlagType} is tolerated for a different and stronger reason (#2395): nothing
     * evaluates it. It is parsed, stored on {@code FlagConfiguration}, and never read —
     * go, ruby and php do not model the field at all. Rejecting an unrecognised flag type
     * therefore failed the entire fetch over a field no evaluation logic consults, so the
     * day a new flag type ships server-side every pinned SDK stops serving EVERY flag. A
     * null type has no downstream consumer that could mis-serve on it, and equally none
     * that could warn about it, which is why the diagnostic is emitted here.
     *
     * <p>{@code ServeType} and {@code ConditionLogic} are tolerated too, but map to a
     * distinct {@code UNRECOGNIZED} sentinel rather than to null, because those two ARE
     * consulted and dispatch on a two-way branch: a null {@code ServeType} falls to the
     * rollout arm and a null {@code ConditionLogic} to OR semantics, turning a loud failure
     * into silent-wrong targeting. The sentinel never reaches the evaluator —
     * {@link UnevaluableEntities} drops the containing flag or segment at the parse
     * boundary, so the payload still applies minus that one entity (#2402). That is also
     * why {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} is still not enabled globally: it would
     * null these two rather than sentinel them, and the drop could not tell an unrecognised
     * value from an absent one.
     *
     * <p>Both branches tolerate an unknown NAME only. An integer is a TYPE violation on a
     * different axis and is still rejected by {@code FAIL_ON_NUMBERS_FOR_ENUMS} above —
     * {@code handleWeirdStringValue} is never reached for a numeric token (#2283/#2315).
     */
    private static final DeserializationProblemHandler UNKNOWN_ENUM_NAME_HANDLER =
        new DeserializationProblemHandler() {
            @Override
            public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType,
                                                 String valueToConvert, String failureMsg) {
                if (targetType == ConditionOperator.class) {
                    log.warn("Unrecognised condition operator '{}' - condition will not match. "
                        + "This SDK version may be older than the flag configuration.", valueToConvert);
                    return null;
                }
                if (targetType == FlagType.class) {
                    log.warn("Unrecognised flag type '{}' - the flag is still served; nothing "
                        + "evaluates this field. This SDK version may be older than the flag "
                        + "configuration.", valueToConvert);
                    return null;
                }
                // ServeType and ConditionLogic ARE consulted, so neither null nor the raw
                // value is safe here — both would reach a two-way dispatch and take the
                // ELSE arm. They map to a distinct UNRECOGNIZED sentinel that
                // UnevaluableEntities then uses to drop the containing flag or segment
                // (#2402). Logged here because this is the only point that still holds the
                // value the server actually sent; the drop itself names the entity.
                if (targetType == ServeType.class) {
                    log.warn("Unrecognised serve type '{}' - the flag carrying it will be "
                        + "dropped. This SDK version may be older than the flag configuration.",
                        valueToConvert);
                    return ServeType.UNRECOGNIZED;
                }
                if (targetType == ConditionLogic.class) {
                    log.warn("Unrecognised condition logic '{}' - the flag or segment carrying "
                        + "it will be dropped. This SDK version may be older than the flag "
                        + "configuration.", valueToConvert);
                    return ConditionLogic.UNRECOGNIZED;
                }
                return NOT_HANDLED;
            }
        };
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String sdkKey;
    final ObjectMapper objectMapper;

    public FlagHttpClient(String sdkKey, FeatureFlagConfig config) {
        this.sdkKey = sdkKey;
        this.baseUrl = config.getBaseUrl();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Enums must arrive as strings ("Boolean", "Fixed", "And"). Jackson would
            // otherwise accept an integer and resolve it by ORDINAL, silently bypassing
            // the @JsonProperty names on the constants — so a value inserted into the
            // middle of the corresponding .NET enum would shift every later value here
            // to the wrong one, with no exception and no log line. Nothing links the two
            // declaration orders, so that drift would be invisible at both edit sites.
            // Failing loudly turns a silent-wrong into a visible error (#2283; #2279 is
            // the server bug that made integer enums reach SDKs at all).
            .configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .addHandler(UNKNOWN_ENUM_NAME_HANDLER);

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .addInterceptor(chain -> {
                Request request = chain.request().newBuilder()
                    .addHeader("Authorization", sdkKey)
                    .build();
                return chain.proceed(request);
            })
            .build();
    }

    public GetFlagsResponse fetchFlags() throws IOException {
        Request request = new Request.Builder()
            .url(baseUrl + "/v1/sdk/flags")
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch flags: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Failed to fetch flags: empty response body (status " + response.code() + ")");
            }
            GetFlagsResponse parsed = objectMapper.readValue(body.string(), GetFlagsResponse.class);
            // Applied here rather than at each call site so polling and the SSE
            // segment-updated refetch cannot drift; handleSync parses its own payload and
            // calls this itself.
            UnevaluableEntities.dropUnevaluable(parsed);
            return parsed;
        }
    }

    public FlagConfiguration fetchFlag(String key) throws IOException {
        Request request = new Request.Builder()
            .url(baseUrl + "/v1/sdk/flags/" + key)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch flag '" + key + "': " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Failed to fetch flag '" + key + "': empty response body");
            }
            FlagConfiguration flag = objectMapper.readValue(body.string(), FlagConfiguration.class);
            // An unevaluable enum drops the flag rather than upserting it (#2402). For a
            // delta whose whole scope is one flag that means leaving the store's previous
            // copy alone: replacing it with one this build would mis-evaluate is the
            // outcome the drop exists to prevent, and FLAG_NOT_FOUND is the honest answer
            // if there was no previous copy.
            String reason = UnevaluableEntities.flagReason(flag);
            if (reason != null) {
                log.warn("Dropping flag delta for '{}': {}. This SDK version may be older "
                    + "than the flag configuration.", key, reason);
                return null;
            }
            return flag;
        }
    }

    public void sendEvents(List<SdkEvent> events) throws IOException {
        if (events.isEmpty()) return;

        String json = objectMapper.writeValueAsString(
            Collections.singletonMap("events", events));

        Request request = new Request.Builder()
            .url(baseUrl + "/v1/sdk/events")
            .post(RequestBody.create(json, JSON_MEDIA))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            // Throws rather than logging: the flush path has to know WHICH status came back
            // so it can keep the batch on a retryable failure instead of discarding it
            // (#2456). A log line here also could not be acted on, and the caller logs every
            // failure — see SharedFeatureflipCore#flushEvents.
            if (!response.isSuccessful()) {
                throw new EventSendException(response.code());
            }
        }
    }

    public OkHttpClient getHttpClient() { return httpClient; }
    public String getBaseUrl() { return baseUrl; }
    public String getSdkKey() { return sdkKey; }
    public ObjectMapper getObjectMapper() { return objectMapper; }

    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
