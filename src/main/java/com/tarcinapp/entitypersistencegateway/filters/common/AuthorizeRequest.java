package com.tarcinapp.entitypersistencegateway.filters.common;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.authorization.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyData;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyResult;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AuthorizeRequest extends AbstractGatewayFilterFactory<AuthorizeRequest.Config> {

    @Autowired
    IAuthorizationClient authorizationClient;

    @Autowired
    IBackendClientBase backendBaseClient;

    private Logger logger = LogManager.getLogger(AuthorizeRequest.class);

    /**
     * responding with the reason string to unauthorized requests may leak private information.
     * I found this unsecure and embedded a static string into code.
     */
    private static String UNAUTHORIZATION_REASON = "You are unauthorized to perform this operation";

    public AuthorizeRequest() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        // implement in seperate method in order to reduce nesting
        return (exchange, chain) -> this.filter(config, exchange, chain);
    }

    private Mono<Void> filter(Config config, ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String token = this.extractToken(request);

        logger.info("Authorization filter is started. Policy name: " + config.getPolicyName());

        if (token == null) {
            logger.warn(
                    "Token not found to authorize the request. To enforce token to be sent, please configure rs256 key. This request won't be authorized.");
            return chain.filter(exchange);
        }

        HttpMethod httpMethod = request.getMethod();

        // instantiate policy data and fill it with HTTP info
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(config.getPolicyName());
        policyData.setHttpMethod(httpMethod);
        policyData.setEncodedJwt(token);
        policyData.setQueryParams(request.getQueryParams());
        policyData.setRequestPath(request.getPath());

        // check if we have a payload, if so, we will be using the payload in policy data
        boolean hasPayload = httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT
                || httpMethod == HttpMethod.PATCH;

        // if we have a payload, we need to extract the body to pass to the policy
        if (hasPayload) {
            logger.info(httpMethod + " method contains a payload. Payload will be attached to the policy data.");

            ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setContentType(MediaType.APPLICATION_JSON_VALUE)
                    .setRewriteFunction(String.class, String.class, (exchange1, inboundJsonRequestStr) -> {

                        try {
                            // parse the inbound string as JSON
                            ObjectMapper objectMapper = new ObjectMapper();
                            Map<String, Object> payloadJSON;
                            payloadJSON = objectMapper.readValue(inboundJsonRequestStr,
                                    new TypeReference<Map<String, Object>>() {
                                    });

                            // take the parsed payload JSON, throw exception if it is not authorized
                            return this.authorizeWithPayload(exchange1, policyData, payloadJSON)
                                .map((result) -> inboundJsonRequestStr);
                        } catch (JsonProcessingException e) {
                            logger.error(e);
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
                        }
                    });

            return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
        }

        return this.executePolicy(policyData).flatMap(result -> chain.filter(exchange));
    }

    /**
     * This method fills the policy data with the managed fields extracted from the request payload.
     * Checks if the operation is intentended to create new record, or update an existing record.
     * 
     * For creation requests, this method delegates the authorization to authorizeRecordCreation method 
     * in order to add the record counts into policy data.
     * 
     * For update requests, this method delegates the authorization to authorizeRecordUpdate method
     * in order to add the information about the record that is going to be updated into the policy data.
     * */ 
    private Mono<Void> authorizeWithPayload(ServerWebExchange exchange, PolicyData policyData,
            Map<String, Object> payloadJSON) {
        ServerHttpRequest request = exchange.getRequest();

        // prepare the record base from the request payload
        AnyRecordBase recordBaseFromPayload = this.prepareRecordBaseFromPayload(payloadJSON);

        // let the policy data contain record base of request payload
        policyData.setRequestPayload(recordBaseFromPayload);

        /**
         * if this operation is to create new record, we need to include the record
         * counts to the policy data.
         * */ 
        if (request.getMethod() == HttpMethod.POST) {

            // add record count details to the policy data and authorize
            return this.authorizeRecordCreation(exchange, policyData);
        }

        /**
         * if this operation is to update existing record, then we need to include the
         * original record to the policy
         */
        if (request.getMethod() == HttpMethod.PUT || request.getMethod() == HttpMethod.PATCH) {
            return this.authorizeRecordUpdate(exchange, policyData);
        }

        // practically we will never reach to this line as this method is invoked with a payload
        return this.executePolicy(policyData);
    }

    private AnyRecordBase prepareRecordBaseFromPayload(Map<String, Object> payloadJSON) {
        // record base is an object containing all managed fields in the request, we use
        // it in policyData
        AnyRecordBase recordBaseFromPayload = new AnyRecordBase();

        // extract managed fields from body
        String id = (String) payloadJSON.get("id");
        String kind = (String) payloadJSON.get("kind");
        String name = (String) payloadJSON.get("name");
        String slug = (String) payloadJSON.get("slug");
        String visibility = (String) payloadJSON.get("visibility");
        String creationDateTime = (String) payloadJSON.get("creationDateTime");
        String validFromDateTime = (String) payloadJSON.get("validFromDateTime");
        String validUntilDateTime = (String) payloadJSON.get("validUntilDateTime");
        List<String> ownerUsers = (List<String>) payloadJSON.get("ownerUsers");
        List<String> ownerGroups = (List<String>) payloadJSON.get("ownerGroups");

        recordBaseFromPayload.setId(id);
        recordBaseFromPayload.setKind(kind);
        recordBaseFromPayload.setName(name);
        recordBaseFromPayload.setSlug(slug);
        recordBaseFromPayload.setVisibility(visibility);
        if (creationDateTime != null)
            recordBaseFromPayload.setCreationDateTime(ZonedDateTime.parse(creationDateTime));
        if (validFromDateTime != null)
            recordBaseFromPayload.setValidFromDateTime(ZonedDateTime.parse(validFromDateTime));
        if (validUntilDateTime != null)
            recordBaseFromPayload.setValidUntilDateTime(ZonedDateTime.parse(validUntilDateTime));
        recordBaseFromPayload.setOwnerUsers(ownerUsers);
        recordBaseFromPayload.setOwnerGroups(ownerGroups);

        return recordBaseFromPayload;
    }

    private Mono<Void> authorizeRecordCreation(ServerWebExchange exchange, PolicyData policyData) {

        return this.executePolicy(policyData);
    }

    private Mono<Void> authorizeRecordUpdate(ServerWebExchange exchange, PolicyData policyData) {
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);

        // get the original record id
        String recordId = uriVariables.get("recordId");

        return this.executePolicy(policyData);
    }

    /**
     * An in-class wrapper on AuthorizationClient's execute policy.
     * The only functionality we add here by wrapping is DEBUG logging.
     * @param policyData
     * @return
     */
    private Mono<Void> executePolicy(PolicyData policyData) {

        if (logger.getLevel().compareTo(Level.DEBUG) >= 0) {
            logger.debug("Policy data is prepared.");
            ObjectMapper mapper = new ObjectMapper();

            try {
                String policyDataStr = mapper.writeValueAsString(policyData);
                logger.debug("Policy data: {}", policyDataStr);
            } catch (JsonProcessingException e) {
                logger.debug("Unable to serialize policy data to JSON string.");
            }
        }

        return authorizationClient.executePolicy(policyData)
            .flatMap(result -> {

                if (result.isAllow())
                    return Mono.empty();

                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZATION_REASON);
        });
    }

    private String extractToken(ServerHttpRequest request) {
        if (!request.getHeaders().containsKey("Authorization"))
            return null;

        String authHeader = request.getHeaders().get("Authorization").get(0);

        if (!authHeader.startsWith("Bearer "))
            return null;

        return authHeader.replaceFirst("Bearer\\s", "");
    }

    public static class Config {

        String policyName;

        public String getPolicyName() {
            return this.policyName;
        }

        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }

    }
}
