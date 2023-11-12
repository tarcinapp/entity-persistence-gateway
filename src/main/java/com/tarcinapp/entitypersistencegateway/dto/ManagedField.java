package com.tarcinapp.entitypersistencegateway.dto;

public enum ManagedField {
    OWNER_USERS("ownerUsers"),
    CREATED_BY("createdBy"),
    LAST_UPDATED_BY("lastUpdatedBy"),
    CREATION_DATE_TIME("creationDateTime"),
    LAST_UPDATED_DATE_TIME("lastUpdatedDateTime");

    private String fieldName;

    ManagedField(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return this.fieldName;
    }
}
