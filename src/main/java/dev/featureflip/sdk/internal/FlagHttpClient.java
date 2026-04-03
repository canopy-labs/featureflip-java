package dev.featureflip.sdk.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.featureflip.sdk.FeatureFlagConfig;
import dev.featureflip.sdk.internal.model.FlagConfiguration;
import dev.featureflip.sdk.internal.model.GetFlagsResponse;
import dev.featureflip.sdk.internal.model.SdkEvent;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FlagHttpClient {
    private static final Logger log = LoggerFactory.getLogger(FlagHttpClient.class);
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
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

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
            return objectMapper.readValue(body.string(), GetFlagsResponse.class);
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
            return objectMapper.readValue(body.string(), FlagConfiguration.class);
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
            if (!response.isSuccessful()) {
                log.warn("Failed to send events: {}", response.code());
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
