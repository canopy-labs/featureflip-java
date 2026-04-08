package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Variation {
    private String key = "";
    private JsonNode value;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
}
