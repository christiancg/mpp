package org.ccg.mpp.ops.req.agg.operators;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.OperatorType;

public class FieldOperator extends BaseOperator {
    private FieldOperatorType fieldOperatorType;
    private String field;
    private JsonBaseElement value;

    public FieldOperator(FieldOperatorType operatorType, String field, JsonBaseElement value) {
        super(OperatorType.FIELD);
        this.fieldOperatorType = operatorType;
        this.field = field;
        this.value = value;
    }

    public FieldOperatorType getFieldOperatorType() {
        return fieldOperatorType;
    }

    public void setFieldOperatorType(FieldOperatorType fieldOperatorType) {
        this.fieldOperatorType = fieldOperatorType;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public JsonBaseElement getValue() {
        return value;
    }

    public void setValue(JsonBaseElement value) {
        this.value = value;
    }
}
