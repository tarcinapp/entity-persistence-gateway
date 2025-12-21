package com.tarcinapp.entitypersistencegateway;

import lombok.Data;

/*
 * This class holds the configuration parameters when a kind alias is configured.
 * - Helps other filters to check if kind alias configured.
 * - Helps other filters to get the entity kind name if kind alias configured.
 * - Helps other filters to get original resource URL if kind alias configured.
 *   For instance, if the kind alias is configured as /users, then the original resource URL is /entities/{id}.
 *   Knowing the original resource URL is needed for the authorization logic.
 *   Authorization logic needs to know the original resource URL in order to check if the user is authorized to access the original resource.
 */
@Data
public class KindAliasConfigAttr {
    public static final String KIND_ALIAS_CONFIG_ATTR = "KindAliasConfigAttr";
    boolean isKindAliasConfigured;
    String kindName;
    String kindAlias;
    String controllerName;
    String baseControllerName;
    String recordType;
    String originalResourceUrl;
}
