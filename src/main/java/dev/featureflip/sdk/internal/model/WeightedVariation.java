package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class WeightedVariation {
    private String key = "";
    private int weight;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
}
