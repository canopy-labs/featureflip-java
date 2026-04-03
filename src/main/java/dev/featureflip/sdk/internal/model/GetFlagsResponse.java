package dev.featureflip.sdk.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class GetFlagsResponse {
    private String environment = "";
    private int version;
    private List<FlagConfiguration> flags = new ArrayList<>();
    private List<Segment> segments = new ArrayList<>();

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public List<FlagConfiguration> getFlags() { return flags; }
    public void setFlags(List<FlagConfiguration> flags) { this.flags = flags; }
    public List<Segment> getSegments() { return segments; }
    public void setSegments(List<Segment> segments) { this.segments = segments; }
}
