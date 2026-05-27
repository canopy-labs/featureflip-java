package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Prerequisite {
    private String prerequisiteFlagKey = "";
    private String expectedVariationKey = "";

    public String getPrerequisiteFlagKey() { return prerequisiteFlagKey; }
    public void setPrerequisiteFlagKey(String prerequisiteFlagKey) { this.prerequisiteFlagKey = prerequisiteFlagKey; }
    public String getExpectedVariationKey() { return expectedVariationKey; }
    public void setExpectedVariationKey(String expectedVariationKey) { this.expectedVariationKey = expectedVariationKey; }
}
