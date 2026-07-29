package io.featureflip.client.internal.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GetFlagsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void nullFlagsAndSegmentsDeserializeToEmptyLists() throws Exception {
        // Jackson calls the setter with null for an explicit `"flags": null`,
        // overriding the field initializer — the setters must normalize to empty
        // so no caller can observe a null list.
        var json = "{\"environment\":\"prod\",\"version\":3,\"flags\":null,\"segments\":null}";

        GetFlagsResponse response = mapper.readValue(json, GetFlagsResponse.class);

        assertThat(response.getFlags()).isNotNull().isEmpty();
        assertThat(response.getSegments()).isNotNull().isEmpty();
    }

    @Test
    void sizeIsSafeToReadWhenPayloadSendsNull() throws Exception {
        // Regression: both PollingDataSource.poll() and the initial fetch log
        // `response.getFlags().size()` immediately after a null-safe replace(). The
        // NPE landed between the store update and the initialization signal, so the
        // client never reported initialized and re-failed on every poll.
        var json = "{\"flags\":null,\"segments\":null}";

        GetFlagsResponse response = mapper.readValue(json, GetFlagsResponse.class);

        assertThat(response.getFlags().size()).isZero();
        assertThat(response.getSegments().size()).isZero();
    }

    @Test
    void omittedFlagsDeserializeToEmptyLists() throws Exception {
        GetFlagsResponse response = mapper.readValue("{\"version\":1}", GetFlagsResponse.class);

        assertThat(response.getFlags()).isEmpty();
        assertThat(response.getSegments()).isEmpty();
    }

    @Test
    void explicitNullViaSetterNormalizesToEmpty() {
        GetFlagsResponse response = new GetFlagsResponse();

        response.setFlags(null);
        response.setSegments(null);

        assertThat(response.getFlags()).isEmpty();
        assertThat(response.getSegments()).isEmpty();
    }

    @Test
    void populatedFlagsArePreserved() throws Exception {
        var json = "{\"flags\":[{\"key\":\"flag-a\",\"enabled\":true}],\"segments\":[{\"key\":\"seg-a\"}]}";

        GetFlagsResponse response = mapper.readValue(json, GetFlagsResponse.class);

        assertThat(response.getFlags()).hasSize(1);
        assertThat(response.getFlags().get(0).getKey()).isEqualTo("flag-a");
        assertThat(response.getSegments()).hasSize(1);
    }
}
