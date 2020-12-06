package com.tarcinapp.entitypersistencegateway.authorization;

import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.util.MultiValueMap;

public class PolicyData {
    
    private HttpMethod httpMethod;
    private RequestPath requestPath;
    private MultiValueMap<String, String> queryParams;
    private String encodedJwt;
    private int recordCountByUser;
    private int recordCountInKindByUser;
    private AnyRecordBase requestPayload;
    private AnyRecordBase originalRecord;

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
   

    public HttpMethod getHttpMethod() {
        return this.httpMethod;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public RequestPath getRequestPath() {
        return this.requestPath;
    }

    public void setRequestPath(RequestPath requestPath) {
        this.requestPath = requestPath;
    }

    public MultiValueMap<String,String> getQueryParams() {
		return queryParams;
	}

    public void setQueryParams(MultiValueMap<String,String> queryParams) {
		this.queryParams = queryParams;
	}

    public int getRecordCountByUser() {
        return this.recordCountByUser;
    }

    public void setRecordCountByUser(int recordCountByUser) {
        this.recordCountByUser = recordCountByUser;
    }

    public int getRecordCountInKindByUser() {
        return this.recordCountInKindByUser;
    }

    public void setRecordCountInKindByUser(int recordCountInKindByUser) {
        this.recordCountInKindByUser = recordCountInKindByUser;
    }

    public String getEncodedJwt() {
        return this.encodedJwt;
    }

    public void setEncodedJwt(String encodedJwt) {
        this.encodedJwt = encodedJwt;
    }

}
