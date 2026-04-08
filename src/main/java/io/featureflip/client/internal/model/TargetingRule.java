package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TargetingRule {
    private String id = "";
    private int priority;
    private List<ConditionGroup> conditionGroups = new ArrayList<>();
    private ServeConfig serve;
    private String segmentKey;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public List<ConditionGroup> getConditionGroups() { return conditionGroups; }
    public void setConditionGroups(List<ConditionGroup> conditionGroups) { this.conditionGroups = conditionGroups; }
    public ServeConfig getServe() { return serve; }
    public void setServe(ServeConfig serve) { this.serve = serve; }
    public String getSegmentKey() { return segmentKey; }
    public void setSegmentKey(String segmentKey) { this.segmentKey = segmentKey; }
}
