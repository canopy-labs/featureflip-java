package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class FlagConfiguration {
    private String key = "";
    private int version;
    private FlagType type;
    private boolean enabled;
    private List<Variation> variations = new ArrayList<>();
    private List<TargetingRule> rules = new ArrayList<>();
    private ServeConfig fallthrough;
    private String offVariation = "";

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public FlagType getType() { return type; }
    public void setType(FlagType type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<Variation> getVariations() { return variations; }
    public void setVariations(List<Variation> variations) { this.variations = variations; }
    public List<TargetingRule> getRules() { return rules; }
    public void setRules(List<TargetingRule> rules) { this.rules = rules; }
    public ServeConfig getFallthrough() { return fallthrough; }
    public void setFallthrough(ServeConfig fallthrough) { this.fallthrough = fallthrough; }
    public String getOffVariation() { return offVariation; }
    public void setOffVariation(String offVariation) { this.offVariation = offVariation; }

    /** Find a variation by key, or null if not found. */
    public Variation getVariationByKey(String variationKey) {
        for (Variation v : variations) {
            if (v.getKey().equals(variationKey)) return v;
        }
        return null;
    }
}
