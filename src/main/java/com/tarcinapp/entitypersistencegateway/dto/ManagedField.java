package com.tarcinapp.entitypersistencegateway.dto;

public enum ManagedField {
    OWNER_USERS("_ownerUsers"),
    VIEWER_USERS("_viewerUsers"),
    CREATED_BY("_createdBy"),
    LAST_UPDATED_BY("_lastUpdatedBy"),
    CREATION_DATE_TIME("_createdDateTime"),
    LAST_UPDATED_DATE_TIME("_lastUpdatedDateTime");

    private final String fieldName;

    ManagedField(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return this.fieldName;
    }
}
