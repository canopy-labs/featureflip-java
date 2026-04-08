package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ConditionGroup {
    private ConditionLogic operator;
    private List<Condition> conditions = new ArrayList<>();

    public ConditionLogic getOperator() { return operator; }
    public void setOperator(ConditionLogic operator) { this.operator = operator; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
}
