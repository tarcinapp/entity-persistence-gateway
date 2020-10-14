package com.tarcinapp.entitypersistencegateway;

import java.util.ArrayList;

public class GatewayContext {
    private String authSubject;
    private String authParty;
    private String requestId;
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

    public String getRequestId() {
        return this.requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}