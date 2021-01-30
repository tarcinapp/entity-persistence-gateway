package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.ArrayList;
import java.util.Set;

import com.tarcinapp.entitypersistencegateway.filters.base.AbstractPolicyAwareResponsePayloadModifierFilterFactory;
import com.tarcinapp.entitypersistencegateway.filters.base.PolicyEvaluatingFilterConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class DropFieldsForFindEntityById extends
        AbstractPolicyAwareResponsePayloadModifierFilterFactory<PolicyEvaluatingFilterConfig, DropFieldsForFindEntityById.PolicyResponse, String, String> {

    private Logger logger = LogManager.getLogger(DropFieldsForFindEntityById.class);

    public DropFieldsForFindEntityById(Set<MessageBodyDecoder> messageBodyDecoders,
            Set<MessageBodyEncoder> messageBodyEncoders) {
        super(PolicyEvaluatingFilterConfig.class, PolicyResponse.class, String.class, String.class, messageBodyDecoders,
                messageBodyEncoders);
    }

    @Override
    public Mono<String> modifyRequestPayload(PolicyEvaluatingFilterConfig config, ServerWebExchange exchange,
            PolicyResponse policyResult, String payload) {
        // TODO Auto-generated method stub
        return null;
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
