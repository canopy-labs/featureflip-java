package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ServeConfig {
    private ServeType type;
    private String variation;
    private String bucketBy;
    private String salt;
    private List<WeightedVariation> variations;

    public ServeType getType() { return type; }
    public void setType(ServeType type) { this.type = type; }
    public String getVariation() { return variation; }
    public void setVariation(String variation) { this.variation = variation; }
    public String getBucketBy() { return bucketBy; }
    public void setBucketBy(String bucketBy) { this.bucketBy = bucketBy; }
    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }
    public List<WeightedVariation> getVariations() { return variations; }
    public void setVariations(List<WeightedVariation> variations) { this.variations = variations; }
}
