package com.tarcinapp.entitypersistencegateway;

/*
 * This class holds the configuration parameters when a kindPath is configured as an entity kind.
 * - Helps other filters to check if kindPath configured.
 * - Helps other filters to get the entity kind name if kindPath configured.
 * - Helps other filters to get original resource URL if kindPath configured.
 *   For instance, if the kindPath is configured as /users, then the original resource URL is /entities/{id}.
 *   Knowing the original resource URL is needed for the authorization logic.
 *   Authorization logic needs to know the original resource URL in order to check if the user is authorized to access the original resource.
 */
public class KindPathConfigAttr {
    boolean isKindPathConfigured;
    String kindName;
    String originalResourceUrl;

    public boolean isKindPathConfigured() {
        return isKindPathConfigured;
    }

    public void setKindPathConfigured(boolean isKindPathConfigured) {
        this.isKindPathConfigured = isKindPathConfigured;
    }

    public String getKindName() {
        return kindName;
    }

    public void setKindName(String entityKindName) {
        this.kindName = entityKindName;
    }

    public String getOriginalResourceUrl() {
        return originalResourceUrl;
    }

    public void setOriginalResourceUrl(String originalResourceUrl) {
        this.originalResourceUrl = originalResourceUrl;
    }

}
