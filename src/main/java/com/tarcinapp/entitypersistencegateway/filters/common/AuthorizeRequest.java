package com.tarcinapp.entitypersistencegateway.filters.common;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.authorization.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

        // if we have a payload, we need to extract the body to pass to the policy, PUT, POST, PATCH methods are handled here
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
        
        // from here, we do not have body block
        // only GET or DELETE method authorizations are reaching here
        // from here, its important to know if we are targeting a single record (GET-one, or DELETE-one) or multiple records (GET-all, count, or DELETE-all)
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        // check if we are getting or removing a single data
        if(recordId != null) {
            // TODO: this block may be executed if we may be deleting multiple data over relation
            // from here we know that the we are dealing with single record.
            // as operations with payloads are handled above, we only need to include the originalRecord to the policy
            return this.authorizeWithOriginalRecord(exchange, policyData)
                .flatMap(result -> chain.filter(exchange));
        }

        // the only cases cause following line to be executed  is get-all, count and delete-all operations.
        // these operations do not have request payload, or single targeted originalRecord
        // we can go ahead and ask the PDP to decide whether this operation is authorized
        return this.executePolicy(policyData).flatMap(result -> chain.filter(exchange));
    }

    /**
     * This method fills the policy data with the managed fields extracted from the request payload.
     * Checks if the operation is intentended to create new record, or update an existing record.
     * 
     * For update requests, this method delegates the authorization to authorizeRecordUpdate method
     * in order to add the information about the record that is going to be updated into the policy data.
     * */ 
    private Mono<Boolean> authorizeWithPayload(ServerWebExchange exchange, PolicyData policyData,
            Map<String, Object> payloadJSON) {
        ServerHttpRequest request = exchange.getRequest();

        // prepare the record base from the request payload
        AnyRecordBase recordBaseFromPayload = this.prepareRecordBaseFromPayload(payloadJSON);

        // let the policy data contain record base of request payload
        policyData.setRequestPayload(recordBaseFromPayload);

        /**
         * if this operation is to update existing record, then we need to include the
         * original record to the policy
         */
        if (request.getMethod() == HttpMethod.PUT || request.getMethod() == HttpMethod.PATCH) {
            return this.authorizeWithOriginalRecord(exchange, policyData);
        }

        // authorize POST request
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

    /**
     * This method adds the original targeted record to the policy if we have a record id.
     * That is, if we are going to authorize single record update, delete or get method,
     * this method retrieves the original record and adds it to the policy.
     * 
     * Note: if the method contains a payload, payload should be added to the policy data
     * before invoking this method.
     * 
     * @param exchange
     * @param policyData
     * @return
     */
    private Mono<Boolean> authorizeWithOriginalRecord(ServerWebExchange exchange, PolicyData policyData) {
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);

        // we use recordId to check if this is updateAll operation
        String recordId = uriVariables.get("recordId");

        // if we do not have a record id. this operation is updateAll, we do not have single targeted record.
        if(recordId == null)
            return this.executePolicy(policyData);

        // we have single targeted record
        return this.retriveOriginalRecord(exchange)
            .flatMap(originalRecord -> {
                policyData.setOriginalRecord(originalRecord);
                return this.executePolicy(policyData);
            });
    }

    /**
     * One of the main problem this method solves is to decide which path to ask for the original record.
     * 
     * If the Http method is GET and we have the recordId, then we can directly ask to the same path for the original record
     * to pass to the auth policy.
     * 
     * According to the API spec generated by LB backend, PUT operations always target a single record.
     * Thus, if this operation is PUT, then we can directly ask with GET to the same path to retrieve
     * the original record.
     * 
     * That is, PUT and GET-with-recordId treated as same.
     * 
     * Whereas PATCH operations may target single record or multiple records.
     * Thus, if this operation is PATCH and if the request path ends with the 'recordId' predicate variable, 
     * then we can say that the operation targets a single record. We can ask with GET to the same path to retrieve 
     * the original record. 
     * If the path does not end with the 'recordId, we can say that the operation targets multiple record over 
     * the relation. In that case, we simply return null.
     * 
     * @param exchange
     * @return
     */
    private Mono<AnyRecordBase> retriveOriginalRecord(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().toString();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);

        // we use recordId to check if this is updateAll operation
        String recordId = uriVariables.get("recordId");

        if(path.endsWith(recordId))
            return this.backendBaseClient.get(path.toString(), AnyRecordBase.class);

        return Mono.just(null);
    }

    /**
     * An in-class wrapper on AuthorizationClient's execute policy.
     * The only functionality we add here by wrapping is DEBUG logging.
     * @param policyData
     * @return
     */
    private Mono<Boolean> executePolicy(PolicyData policyData) {

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
            .map(result -> {

                if (result.isAllow())
                    return Boolean.TRUE;

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
