package com.tarcinapp.entitypersistencegateway;

import java.util.ArrayList;

import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

/**
 * This object is instantiated and filled at the authentication filter and is available through all filters.
 * GatewayContext is designed to keep all common data properties about the request. 
 * With this approach, filters do not need to recalculate values for these fields.
 * 
 * GatewaySecurityContext is available from the request attributes.
 */
public class GatewaySecurityContext {
    private String authSubject;
    private String authParty;
    private String encodedJwt;
    private ArrayList<String> roles;
    private ArrayList<String> groups;

    public void setAuthSubject(String authSubject) {
        this.authSubject = authSubject;
    }

    public String getAuthSubject() {
        return this.authSubject;
    }

    public void setAuthParty(String authParty) {
        this.authParty = authParty;
    }

    public String getAuthParty() {
        return this.authParty;
    }

    public String getEncodedJwt() {
        return this.encodedJwt;
    }

    public void setEncodedJwt(String encodedJwt) {
        this.encodedJwt = encodedJwt;
    }

    public void setGroups(ArrayList<String> groups) {
        this.groups = groups;
    }

    public ArrayList<String> getGroups() {
        return this.groups;
    }

    public void setRoles(ArrayList<String> roles) {
        this.roles = roles;
    }

    public ArrayList<String> getRoles() {
        return this.roles;
    }
}
