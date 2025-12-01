package com.tarcinapp.entitypersistencegateway.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Data;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.util.MultiValueMap;

@Data
@JsonInclude(Include.NON_NULL)
public class PolicyData implements Cloneable {

    public static final String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private String policyName;
    private String appShortcode;
    
    @JsonSerialize(using = ToStringSerializer.class)
    private HttpMethod httpMethod;

    @JsonSerialize(using = ToStringSerializer.class)
    private RequestPath requestPath;

    private MultiValueMap<String, String> queryParams;
    private String encodedJwt;
    private Object requestPayload;
    private Object originalRecord;

    @Override
    public PolicyData clone() {
        try {
            return (PolicyData) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(); // Should not happen since we implement Cloneable
        }
    }
}