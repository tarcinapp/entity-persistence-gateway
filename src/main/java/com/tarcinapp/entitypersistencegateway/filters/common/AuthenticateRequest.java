package com.tarcinapp.entitypersistencegateway.filters.common;

import java.security.Key;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.GatewaySecurityContext;
import com.tarcinapp.entitypersistencegateway.KindPathConfigAttr;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.clients.backend.IBackendClientBase;
import com.tarcinapp.entitypersistencegateway.dto.AnyRecordBase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RLockReactive;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RReadWriteLockReactive;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
 * 
 * Authentication filter may be used for requests coming over kindPath.
 * For instance, if the kindPath is configured as /users/{id}, then the original
 * resource URL is /generic-entities/{id}.
 */
@Component
public class AuthenticateRequest extends AbstractGatewayFilterFactory<AuthenticateRequest.Config> {

    @Autowired(required = false)
    private Key key;

    @Autowired
    IBackendClientBase backendBaseClient;

    @Autowired
    RedissonReactiveClient redissonReactiveClient;

    @Value("${app.shortcode:#{tarcinapp}}")
    private String appShortcode;

    @Value("${app.auth.issuer:#{null}}")
    private String tokenIssuer;

    private final static String GATEWAY_SECURITY_CONTEXT_ATTR = "GatewaySecurityContext";
    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    Logger logger = LogManager.getLogger(AuthenticateRequest.class);

    public AuthenticateRequest() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        // implement in seperate method in order to reduce nesting
        return ((exchange, chain) -> {

            logger.debug("Authentication filter is started.");

            // set the GatewaySecurityContext to attributes
            exchange.getAttributes().put(GATEWAY_SECURITY_CONTEXT_ATTR, new GatewaySecurityContext());

            // set the PolicyInquiryData to attributes
            PolicyData policyData = new PolicyData();
            policyData.setAppShortcode(this.appShortcode);
            
            exchange.getAttributes().put(POLICY_INQUIRY_DATA_ATTR, policyData);

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
        });
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

            try {
                return this.onUserAuthenticated(claims, exchange, chain);
            } catch (InterruptedException e) {
                logger.error(e);

                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Failed while trying to get write-lock over resource.", e);
            }

        }).onErrorResume(e -> {

            ServerHttpResponse response = exchange.getResponse();

            if (e instanceof WebClientResponseException) {
                WebClientResponseException clientResponseException = (WebClientResponseException) e;

                if (clientResponseException.getStatusCode() == HttpStatus.NOT_FOUND)
                    response.setStatusCode(HttpStatus.NOT_FOUND);
            } else if (e instanceof ResponseStatusException) {
                response.setStatusCode(((ResponseStatusException) e).getStatus());
            } else {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                logger.error(e);
            }

            return response.setComplete();
        });
    }

    /**
     * This method tries to validate the token with the configured key.
     * 
     * In order to do that, we are first trying to extract the Bearer token.
     * If a valid Bearer token can be extracted, it is put into
     * GatewaySecurityContext.
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

            if(!claims.getIssuer().equals(this.tokenIssuer)) {
                throw new JwtException("Invalid issuer");
            }

            logger.debug("JWT token is validated.");

            // put the jwt into GatewaySecurityContext
            this.getSecurityContext(exchange)
                    .setEncodedJwt(jwt);

            return Mono.just(claims);
        } catch (JwtException e) {
            logger.error(e);
            return Mono.error(new Exception("Invalid Authorization header", e));
        }
    }

    /**
     * A facade method for performing some tasks after user authentication
     * 
     * @param claims
     * @param exchange
     * @param chain
     * @return
     * @throws InterruptedException
     */
    private Mono<Void> onUserAuthenticated(Claims claims, ServerWebExchange exchange, GatewayFilterChain chain)
            throws InterruptedException {
        logger.debug("Claims: " + claims);

        /*
         * Fill GatewaySecurityContext with data extracted from JWT claims and put it to
         * attributes.
         */
        this.buildGatewaySecurityContext(claims, exchange);

        return buildPolicyInquiryData(exchange, chain);
    }

    /**
     * This method takes JWT claims for authenticated users and extracts some data.
     * This context data includes: subject, roles, groups and auth party.
     * 
     * Extracted data builds the GatewaySecurityContext.
     * 
     * @param claims
     * @param exchange
     */
    private void buildGatewaySecurityContext(Claims claims, ServerWebExchange exchange) {
        GatewaySecurityContext gc = getSecurityContext(exchange);

        // extract data
        String subject = claims.getSubject();
        String authParth = claims.get("azp", String.class);

        @SuppressWarnings("unchecked")
        ArrayList<String> groups = Optional.ofNullable((ArrayList<String>) claims.get("groups", ArrayList.class))
                .orElse(new ArrayList<String>());

        @SuppressWarnings("unchecked")
        ArrayList<String> roles = Optional.ofNullable((ArrayList<String>) claims.get("roles"))
                .orElse(new ArrayList<String>());

        // put extracted data into context
        gc.setAuthSubject(subject);
        gc.setGroups(groups);
        gc.setRoles(roles);
        gc.setAuthParty(authParth);
    }

    /**
     * Policy data preparation requires access to the payload.
     * 
     * @throws InterruptedException
     */
    private Mono<Void> buildPolicyInquiryData(ServerWebExchange exchange, GatewayFilterChain chain)
            throws InterruptedException {
        GatewaySecurityContext gatewaySecurityContext = getSecurityContext(exchange);
        PolicyData policyInquiryData = getPolicyInquriyData(exchange);
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod httpMethod = request.getMethod();
        Map<String, String> uriVariables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String recordId = uriVariables.get("recordId");

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
            logger.debug(httpMethod + " method contains a payload. Payload will be attached to the policy data.");

            // if we have a payload and a recordId given, then we are performing write
            // operation over a resource. thus we need to lease a distributed write-lock to
            // prevent other threads to write on the same resource while we are doing our
            // work on it.

            if (recordId != null) {

                final long currentThreadId = Thread.currentThread().getId();
                final RReadWriteLockReactive lock = redissonReactiveClient.getReadWriteLock(appShortcode + "+lock-on-" + recordId);
                final RLockReactive writeLock = lock.writeLock();

                return writeLock.isLocked()
                        .flatMap(locked -> {

                            if (locked) {
                                throw new ResponseStatusException(HttpStatus.LOCKED,
                                        "Resource already locked. Resource id: " + recordId);
                            }

                            return writeLock
                                    .tryLock(3, 30, TimeUnit.SECONDS, currentThreadId)
                                    .flatMap(lockAcquired -> {

                                        if (!lockAcquired) {
                                            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                    "Failed to acquire write lock on resource " + recordId);
                                        }

                                        logger.debug("Lock acquired for the resource: " + recordId);

                                        return preparePolicyInquiryDataWithPayloadAndOriginalRecord(exchange, chain);
                                    })
                                    .onErrorResume(throwable  -> {

                                        return writeLock.unlock(currentThreadId)
                                            .then(Mono.error(throwable));
                                    })
                                    .doFinally(signalType -> {
                                        writeLock.unlock(currentThreadId).subscribe();
                                    });
                        });
            }
        }

        // from here, we do not have body block
        // only GET or DELETE method authorizations are reaching here
        // from here, its important to know if we are targeting a single record
        // (GET-one, or DELETE-one) or multiple records (GET-all, count, or DELETE-all)

        // check if we are getting or removing a single data
        if (recordId != null) {
            // TODO: this block may be executed if we may be deleting multiple data over
            // relation
            // from here we know that the we are dealing with single record.
            // as operations with payloads are handled above, we only need to include the
            // originalRecord to the policy

            // this path may contain relations /my-model/{recordId}/some-relation
            // we need to convert it to the /my-model/{recordId}
            String requestPath = request.getPath().toString();

            // path becomes /my-model/{recordId}
            String rootPath = requestPath.replaceAll("\\/" + recordId + "\\/.*", "\\/" + recordId);

            return this.backendBaseClient.get(rootPath, AnyRecordBase.class)
                    .flatMap(originalRecord -> {

                        // set original record to the policy data
                        policyInquiryData.setOriginalRecord(originalRecord);

                        return chain.filter(exchange);
                    });
        }

        return chain.filter(exchange);
    }

    // add incoming request body to the policy inquiry data
    // add original record retrieved from backend to the policy inquiry data
    private Mono<Void> preparePolicyInquiryDataWithPayloadAndOriginalRecord(ServerWebExchange exchange,
            GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        PolicyData policyInquiryData = getPolicyInquriyData(exchange);

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
                         * if this operation is to perform update on existing record, then we need to
                         * include the
                         * original record to the policy.
                         * 
                         * Request may be coming from kindPath. Thus, we are first checking if the
                         * request
                         * is a kindPath request.
                         * If it is a kindPath request, then in order to query for the original record
                         * from the backend, we need to know the original resource URL, which is
                         * accessible from the
                         * KindPathConfigAttr.
                         */
                        if (request.getMethod() == HttpMethod.PUT || request.getMethod() == HttpMethod.PATCH) {
                            String originalResourceUrl = request.getPath().toString();

                            // check if we have a kindPath configuration
                            KindPathConfigAttr kindPathConfigAttr = exchange.getAttribute("KindPathConfigAttr");

                            if (kindPathConfigAttr != null && kindPathConfigAttr.isKindPathConfigured()) {

                                // we have a kindPath configuration
                                originalResourceUrl = kindPathConfigAttr.getOriginalResourceUrl();
                            }

                            return this.backendBaseClient.get(originalResourceUrl, AnyRecordBase.class)
                                    .flatMap(originalRecord -> {

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

    /**
     * A shorthand method for accessing the GatewaySecurityContext
     * 
     * @param exchange
     * @return
     */
    private GatewaySecurityContext getSecurityContext(ServerWebExchange exchange) {
        return exchange.getAttribute(GATEWAY_SECURITY_CONTEXT_ATTR);
    }

    /**
     * A shorthand method for accessing the PolicyInquriyData
     * 
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
