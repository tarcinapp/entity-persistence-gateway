package com.tarcinapp.entitypersistencegateway;

import java.util.ArrayList;

import lombok.Data;

/**
 * This object is instantiated and filled at the authentication filter and is available through all filters.
 * GatewayContext is designed to keep all common data properties about the request. 
 * With this approach, filters do not need to recalculate values for these fields.
 * 
 * GatewaySecurityContext is available from the request attributes.
 */
@Data
public class GatewaySecurityContext {
    public static final String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    private String authSubject;
    private String authParty;
    private String encodedJwt;
    private ArrayList<String> roles;
    private ArrayList<String> groups;
}
