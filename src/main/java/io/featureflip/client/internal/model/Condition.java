package io.featureflip.client.internal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Condition {
    private String attribute = "";
    private ConditionOperator operator;
    private List<String> values = new ArrayList<>();
    private boolean negate;

    public String getAttribute() { return attribute; }
    public void setAttribute(String attribute) { this.attribute = attribute; }
    public ConditionOperator getOperator() { return operator; }
    public void setOperator(ConditionOperator operator) { this.operator = operator; }
    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }
    public boolean isNegate() { return negate; }
    public void setNegate(boolean negate) { this.negate = negate; }
}
