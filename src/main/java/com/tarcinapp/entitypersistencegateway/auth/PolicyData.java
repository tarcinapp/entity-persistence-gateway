package com.tarcinapp.entitypersistencegateway.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.util.MultiValueMap;

@JsonInclude(Include.NON_NULL)
public class PolicyData implements Cloneable {

    private String policyName;
    private String appShortcode;
    private HttpMethod httpMethod;
    private RequestPath requestPath;
    private MultiValueMap<String, String> queryParams;
    private String encodedJwt;
    private AnyRecordBase requestPayload;
    private AnyRecordBase originalRecord;

    public String getPolicyName() {
        return this.policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public String getAppShortcode() {
        return this.appShortcode;
    }

    public void setAppShortcode(String appShortcode) {
        this.appShortcode = appShortcode;
    }

    public AnyRecordBase getRequestPayload() {
        return this.requestPayload;
    }

    public void setRequestPayload(AnyRecordBase requestPayload) {
        this.requestPayload = requestPayload;
    }

    public AnyRecordBase getOriginalRecord() {
        return this.originalRecord;
    }

    public void setOriginalRecord(AnyRecordBase originalRecord) {
        this.originalRecord = originalRecord;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public HttpMethod getHttpMethod() {
        return this.httpMethod;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public RequestPath getRequestPath() {
        return this.requestPath;
    }

    public void setRequestPath(RequestPath requestPath) {
        this.requestPath = requestPath;
    }

    public MultiValueMap<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(MultiValueMap<String, String> queryParams) {
        this.queryParams = queryParams;
    }

    public String getEncodedJwt() {
        return this.encodedJwt;
    }

    public void setEncodedJwt(String encodedJwt) {
        this.encodedJwt = encodedJwt;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return (PolicyData) super.clone();
    }

}
