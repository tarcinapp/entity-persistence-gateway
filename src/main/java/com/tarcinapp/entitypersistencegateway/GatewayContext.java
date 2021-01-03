package com.tarcinapp.entitypersistencegateway;

import java.util.ArrayList;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import reactor.core.publisher.Mono;

public class GatewayContext {
    private String authSubject;
    private String authParty;
    private String requestId;
    private String encodedJwt;
    private ArrayList<String> roles;
    private ArrayList<String> groups;
    private Mono<AnyRecordBase> originalRecord;
    private Mono<PolicyData> policyData;

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

    public String getRequestId() {
        return this.requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Mono<AnyRecordBase> getOriginalRecord() {
        return this.originalRecord;
    }

    public void setOriginalRecord(Mono<AnyRecordBase> originalRecord) {
        this.originalRecord = originalRecord;
    }

    public Mono<PolicyData> getPolicyData() {
        return this.policyData;
    }

    public void setPolicyData(Mono<PolicyData> policyData) {
        this.policyData = policyData;
    }
}
