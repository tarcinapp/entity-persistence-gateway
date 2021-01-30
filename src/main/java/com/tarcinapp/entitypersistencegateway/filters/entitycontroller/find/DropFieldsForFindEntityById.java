package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.ArrayList;

import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class DropFieldsForFindEntityById extends
        AbstractPolicyAwareResponsePayloadModifierFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsForFindEntityById.PolicyResponse, String, String> {

    private Logger logger = LogManager.getLogger(DropFieldsForFindEntityById.class);

    public DropFieldsForFindEntityById() {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class, String.class, String.class);
    }

    @Override
    public String modifyRequestPayload(PolicyEvaluatingFilterConfig config, ServerWebExchange exchange, PolicyResponse pr, String payload) {
        System.out.println(pr.getFields());
        return payload.toUpperCase();
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
