package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.ArrayList;

import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.stereotype.Component;

@Component
public class DropFieldsForFindEntityById extends
        AbstractPolicyAwareFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsForFindEntityById.PolicyResponse> {

    private Logger logger = LogManager.getLogger(DropFieldsForFindEntityById.class);

    public DropFieldsForFindEntityById() {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class);
    }

    @Override
    public GatewayFilter apply(PolicyEvaluatingFilterConfig config, PolicyResponse policyResult) {
        return (exchange, chain) -> {
            System.out.println("custom filter is executed" + policyResult.getFields());
            return chain.filter(exchange);
        };
    }

    /**
     * This POJO is used to map PDP response of inquiry of forbidden fields.
     */
    static class PolicyResponse {
        ArrayList<String> fields;

        public ArrayList<String> getFields() {
            return this.fields;
        }

        public void setFields(ArrayList<String> fields) {
            this.fields = fields;
        }
    }
}
