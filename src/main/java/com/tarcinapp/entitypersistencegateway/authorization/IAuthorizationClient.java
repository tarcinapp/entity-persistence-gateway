package com.tarcinapp.entitypersistencegateway.authorization;

public interface IAuthorizationClient {
    PolicyResult executePolicy(String policyName, PolicyData data);
}
