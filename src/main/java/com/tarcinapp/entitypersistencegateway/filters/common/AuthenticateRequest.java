package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

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
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * This filter authenticates the requests with Bearer token. If request is
 * authenticated GatewaySecurityContext is initialized and a policy data is
 * prepared.
 * 
 * Preparing policy data at the authentication point lets subsequent filters to
 * use existing policy data without dealing with re-preparing it again.
 */
@Component
public class AuthenticateRequest extends AbstractGatewayFilterFactory<AuthenticateRequest.Config> {

    @Autowired
    private Key key;

    @Autowired
    IBackendClientBase backendBaseClient;

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    Logger logger = LogManager.getLogger(AuthenticateRequest.class);

    public AuthenticateRequest() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        // implement in seperate method in order to reduce nesting
        return (exchange, chain) -> {

            logger.debug("Authentication filter is started.");

            // set the GatewaySecurityContext to attributes
            exchange.getAttributes().put(GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext());

            // set the PolicyInquiryData to attributes
            exchange.getAttributes().put(POLICY_INQUIRY_DATA_ATTR, new PolicyData());

            /**
             * Check if we have autowired key field in class. Existence of the key variable
             * means that we have a public key configured and authentication filter can use
             * it.
             * 
             * If the key is null, we are logging a warning and exiting from filter without
             * doing anything. That is, leaving public key configuration empty means
             * requests will neither be authenticated nor authorized.
             */
            if (this.key == null) {
                logger.warn(
                        "RS256 key is not configured. Requests won't be authenticated! Please configure a valid RS256 public key to enable authentication and authorization.");

                return chain.filter(exchange);
            }

            return this.filter(exchange, chain);
        };
    }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        /**
         * We have a public key configured. Authorization and authentication can be
         * performed.
         */
        logger.debug("RS256 public key is configured. Request will be authenticated.");

        /**
         * We can start to try authenticate the request.
         */
        return this.authenticateUser(exchange).flatMap((claims) -> {

            /**
             * Request is authenticated. This filter has still tasks to do for authenticated
             * users such as preparing the GatewaySecurityContext.
             */
            return this.onUserAuthenticated(claims, exchange, chain);
        }).onErrorResume(e -> {
            logger.error(e);

            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);

            return response.setComplete();
        });
    }

    /**
     * This method tries to validate the token with the configured key.
     * 
     * In order to do that, we are first trying to extract the Bearer token.
     * If a valid Bearer token can be extracted, it is put into GatewaySecurityContext.
     * 
     * @param exchange
     * @return
     */
    private Mono<Claims> authenticateUser(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        if (!request.getHeaders().containsKey("Authorization")) {
            return Mono.error(new Exception("No Authorization header"));
        }

        String authHeader = request.getHeaders().get("Authorization").get(0);

        if (!authHeader.startsWith("Bearer ")) {
            return Mono.error(new Exception("Only token authorization is allowed"));
        }

        String jwt = authHeader.replaceFirst("Bearer\\s", "");

        try {
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(this.key)
                .build()
                .parseClaimsJws(jwt)
                .getBody();
            
            logger.debug("JWT token is validated.");

            // put the jwt into GatewaySecurityContext
            this.getSecurityContext(exchange)
                .setEncodedJwt(jwt);

            return Mono.just(claims);
        } catch (JwtException e) {
            logger.error(e);
            return Mono.error(new Exception("Invalid Authorization header"));
        }
    }

    /**
     * A facade method for performing some tasks after user authentication
     * 
     * @param claims
     * @param exchange
     * @param chain
     * @return
     */
    private Mono<Void> onUserAuthenticated(Claims claims, ServerWebExchange exchange, GatewayFilterChain chain) {
        logger.debug("Claims: " + claims);

        // claims are 
        this.buildGatewaySecurityContext(claims, exchange);

        return buildPolicyInquiryData(exchange, chain);
    }

    /**
     * This method takes JWT claims for authenticated users and extracts some data.
     * This context data includes: subject, roles, groups and auth party.
     * 
     * Extracted data builds the GatewaySecurityContext.
     * @param claims
     * @param exchange
     */
    private void buildGatewaySecurityContext(Claims claims, ServerWebExchange exchange) {
        GatewaySecurityContext gc = getSecurityContext(exchange);

        // extract data
        String subject = claims.getSubject();
        String authParth = claims.get("azp", String.class);
        
        @SuppressWarnings("unchecked")
        ArrayList<String> groups = (ArrayList<String>) claims.get("groups", ArrayList.class);

        @SuppressWarnings("unchecked")
        ArrayList<String> roles = (ArrayList<String>) claims.get("roles");

        // put extracted data into context
        gc.setAuthSubject(subject);
        gc.setGroups(groups);
        gc.setRoles(roles);
        gc.setAuthParty(authParth);
    }

    /**
     * Policy data preparation requires access to the payload.
     */
    private Mono<Void> buildPolicyInquiryData(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewaySecurityContext gatewaySecurityContext = getSecurityContext(exchange);
        PolicyData policyInquiryData = getPolicyInquriyData(exchange);
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod httpMethod = request.getMethod();

        policyInquiryData.setHttpMethod(httpMethod);
        policyInquiryData.setEncodedJwt(gatewaySecurityContext.getEncodedJwt());
        policyInquiryData.setQueryParams(request.getQueryParams());
        policyInquiryData.setRequestPath(request.getPath());

        // check if we have a payload, if so, we will be using the payload in policy
        // data
        boolean hasPayload = httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT
                || httpMethod == HttpMethod.PATCH;

        // if we have a payload, we need to extract the body to pass to the policy, PUT,
        // POST, PATCH methods are handled here
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

                            // prepare the record base from the request payload
                            AnyRecordBase recordBaseFromPayload = this.prepareRecordBaseFromPayload(payloadJSON);

                            // let the policy data contain record base of request payload
                            policyInquiryData.setRequestPayload(recordBaseFromPayload);

                            logger.debug("Request payload attached to the policy data.");

                            /**
                             * if this operation is to update existing record, then we need to include the
                             * original record to the policy
                             */
                            if (request.getMethod() == HttpMethod.PUT || request.getMethod() == HttpMethod.PATCH) {
                                return this.backendBaseClient.get(request.getPath().toString(), AnyRecordBase.class)
                                        .flatMap(originalRecord -> {
                                            // set original record to gateway security context
                                            gatewaySecurityContext.setOriginalRecord(originalRecord);

                                            // set original record to the policy data
                                            policyInquiryData.setOriginalRecord(originalRecord);

                                            return Mono.just(inboundJsonRequestStr);
                                        });
                            }
                        } catch (JsonProcessingException e) {
                            logger.error(e);
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
                        }

                        return Mono.just(inboundJsonRequestStr);
                    });

            return new ModifyRequestBodyGatewayFilterFactory().apply(modifyRequestConfig).filter(exchange, chain);
        }

        // from here, we do not have body block
        // only GET or DELETE method authorizations are reaching here
        // from here, its important to know if we are targeting a single record
        // (GET-one, or DELETE-one) or multiple records (GET-all, count, or DELETE-all)
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

        // check if we are getting or removing a single data
        if (recordId != null) {
            // TODO: this block may be executed if we may be deleting multiple data over
            // relation
            // from here we know that the we are dealing with single record.
            // as operations with payloads are handled above, we only need to include the
            // originalRecord to the policy
            return this.backendBaseClient.get(request.getPath().toString(), AnyRecordBase.class)
                    .flatMap(originalRecord -> {
                        // set original record to gateway security context
                        gatewaySecurityContext.setOriginalRecord(originalRecord);

                        // set original record to the policy data
                        policyInquiryData.setOriginalRecord(originalRecord);

                        return chain.filter(exchange);
                    });
        }

        return chain.filter(exchange);
    }

    /**
     * A shorthand method for accessing the GatewaySecurityContext
     * @param exchange
     * @return
     */
    private GatewaySecurityContext getSecurityContext(ServerWebExchange exchange) {
        return exchange.getAttribute(GATEWAY_SECURITY_CONTEXT_ATTR);
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * @param exchange
     * @return
     */
    private PolicyData getPolicyInquriyData(ServerWebExchange exchange) {
        return exchange.getAttribute(POLICY_INQUIRY_DATA_ATTR);
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

        @SuppressWarnings("unchecked")
        List<String> ownerUsers = (List<String>) payloadJSON.get("ownerUsers");

        @SuppressWarnings("unchecked")
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
    public static class Config {

    }
}
