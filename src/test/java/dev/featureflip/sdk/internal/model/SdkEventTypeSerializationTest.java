package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SdkEventTypeSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<Arguments> eventTypesWithExpectedJson() {
        return Stream.of(
            Arguments.of(SdkEventType.EVALUATION, "Evaluation"),
            Arguments.of(SdkEventType.IMPRESSION, "Impression"),
            Arguments.of(SdkEventType.IDENTIFY, "Identify"),
            Arguments.of(SdkEventType.CUSTOM, "Custom")
        );
    }

    @ParameterizedTest
    @MethodSource("eventTypesWithExpectedJson")
    void serializesAsPascalCase(SdkEventType type, String expected) throws Exception {
        String json = mapper.writeValueAsString(type);
        assertThat(json).isEqualTo("\"" + expected + "\"");
    }

    @ParameterizedTest
    @MethodSource("eventTypesWithExpectedJson")
    void deserializesFromPascalCase(SdkEventType type, String jsonValue) throws Exception {
        SdkEventType deserialized = mapper.readValue("\"" + jsonValue + "\"", SdkEventType.class);
        assertThat(deserialized).isEqualTo(type);
    }
}
