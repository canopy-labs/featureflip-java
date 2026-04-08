package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Segment {
    private String key = "";
    private int version;
    private List<Condition> conditions = new ArrayList<>();
    private ConditionLogic conditionLogic;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
    public ConditionLogic getConditionLogic() { return conditionLogic; }
    public void setConditionLogic(ConditionLogic conditionLogic) { this.conditionLogic = conditionLogic; }
}
