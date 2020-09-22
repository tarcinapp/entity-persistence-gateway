package com.tarcinapp.entitypersistencegateway;

import java.util.ArrayList;

public class GatewayContext {
    private String authSubject;
    private ArrayList<String> roles;
    private ArrayList<String> groups;

    public void setAuthSubject(String authSubject) {
        this.authSubject = authSubject;
    }

    public String getAuthSubject() {
        return this.authSubject;
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
