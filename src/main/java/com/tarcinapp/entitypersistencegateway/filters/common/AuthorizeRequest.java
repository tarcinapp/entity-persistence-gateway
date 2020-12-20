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

    private Logger logger = LogManager.getLogger(AuthorizeRequest.class);

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

        if(token == null) {
            logger.warn("Token not found to authorize the request. To enforce token to be sent, please configure rs256 key. This request won't be authorized");
            return chain.filter(exchange);
        }

        HttpMethod httpMethod = request.getMethod();

        // instantiate policy data
        PolicyData policyData = new PolicyData();
        policyData.setPolicyName(config.getPolicyName());
        policyData.setHttpMethod(httpMethod);
        policyData.setEncodedJwt(token);
        policyData.setQueryParams(request.getQueryParams());
        policyData.setRequestPath(request.getPath());
        
        boolean hasPayload = httpMethod == HttpMethod.POST 
            || httpMethod == HttpMethod.PUT
            || httpMethod == HttpMethod.PATCH;

        /**
         * If we have a payload, we need to extract the body to pass to the policy
         */
        if(hasPayload) {
            logger.info(httpMethod + " method contains a payload. Payload will be attached to the policy data.");

            ModifyRequestBodyGatewayFilterFactory.Config modifyRequestConfig = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(String.class, String.class,
                        (exchange1, inboundJsonRequestStr) -> this.authorizeWithPayload(exchange1, policyData, inboundJsonRequestStr));

            return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
        }

        return authorizationClient.executePolicy(policyData)
            .flatMap(result -> {

                if(!result.isAllow())
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, result.getReason());

                return chain.filter(exchange);
            });
    }

    // This method takes the already filled policyData and adds the record base into policyData. Then calls the authorizeWithPolicyData method
    private Publisher<String> authorizeWithPayload(ServerWebExchange exchange, PolicyData policyData, String inboundJsonRequestStr) {
        ServerHttpRequest request = exchange.getRequest();


        /**
         * if this operation is to create new record, we need to include the record counts to the policy data
         */
        if(request.getMethod() == HttpMethod.POST) {
            
            // TODO: if we have extracted record id, that means we are creating the new record over a relation,
            // this situation requires special handling
        }

        /**
         * if this operation is to update existing record, then we need to include the original record to the policy
         */
        if(request.getMethod() == HttpMethod.PUT || request.getMethod() == HttpMethod.PATCH) {
            
        }

        // record base is an object containing all managed fields in the request, we use it in policyData
        AnyRecordBase anyRecordBase = new AnyRecordBase();

        try {
            // parse the inbound string as JSON 
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> inboundJsonRequestMap = objectMapper.readValue(inboundJsonRequestStr,
                    new TypeReference<Map<String, Object>>() {
                    });

            // extract managed fields from body
            String id = (String) inboundJsonRequestMap.get("id");
            String kind = (String) inboundJsonRequestMap.get("kind");
            String name = (String) inboundJsonRequestMap.get("name");
            String slug = (String) inboundJsonRequestMap.get("slug");
            String visibility = (String) inboundJsonRequestMap.get("visibility");
            String creationDateTime = (String) inboundJsonRequestMap.get("creationDateTime");
            String validFromDateTime = (String) inboundJsonRequestMap.get("validFromDateTime");
            String validUntilDateTime = (String) inboundJsonRequestMap.get("validUntilDateTime");
            List<String> ownerUsers = (List<String>) inboundJsonRequestMap.get("ownerUsers");
            List<String> ownerGroups = (List<String>) inboundJsonRequestMap.get("ownerGroups");

            anyRecordBase.setId(id);
            anyRecordBase.setKind(kind);
            anyRecordBase.setName(name);
            anyRecordBase.setSlug(slug);
            anyRecordBase.setVisibility(visibility);
            if(creationDateTime!=null) anyRecordBase.setCreationDateTime(ZonedDateTime.parse(creationDateTime));
            if(validFromDateTime!=null) anyRecordBase.setValidFromDateTime(ZonedDateTime.parse(validFromDateTime));
            if(validUntilDateTime!=null) anyRecordBase.setValidUntilDateTime(ZonedDateTime.parse(validUntilDateTime));
            anyRecordBase.setOwnerUsers(ownerUsers);
            anyRecordBase.setOwnerGroups(ownerGroups);

            policyData.setRequestPayload(anyRecordBase);
        } catch (JsonMappingException e) {
            logger.error(e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        } catch (JsonProcessingException e) {
            logger.error(e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if(logger.getLevel().compareTo(Level.DEBUG) >= 0) {
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
            .map(result -> {

                if(result.isAllow())
                    return inboundJsonRequestStr;
                
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, result.getReason());
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
