package com.tarcinapp.entitypersistencegateway.oas.client;

import com.tarcinapp.entitypersistencegateway.oas.config.OasOrchestratorProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive client for fetching and parsing the backend's raw OpenAPI
 * specification.
 * 
 * <p>
 * This client is responsible for:
 * </p>
 * <ul>
 * <li>Fetching the OAS from the backend service
 * (entity-persistence-service)</li>
 * <li>Parsing the JSON/YAML content into swagger-parser's OpenAPI model</li>
 * <li>Caching the raw OAS with configurable TTL (shared across all users)</li>
 * <li>Handling network errors with appropriate fallbacks</li>
 * </ul>
 * 
 * <h2>Caching Strategy</h2>
 * <p>
 * The raw OAS is cached in-memory since it's identical for all users.
 * Only the transformed/pruned specs are cached per-role in Redis.
 * </p>
 */
@Component
@Slf4j
public class BackendOasClient {

    private final OasOrchestratorProperties properties;
    private final OpenAPIV3Parser openApiParser;

    private WebClient webClient;

    // In-memory cache for raw OAS (shared across all users)
    private final AtomicReference<CachedOas> cachedRawOas = new AtomicReference<>();

    @Value("${app.outbound.routing-target.protocol:http}")
    private String backendProtocol;

    @Value("${app.outbound.routing-target.host:localhost}")
    private String backendHost;

    @Value("${app.outbound.routing-target.port:3000}")
    private String backendPort;

    @Value("${app.outbound.routing-target.baseUri:/}")
    private String backendBaseUri;

    public BackendOasClient(OasOrchestratorProperties properties) {
        this.properties = properties;
        this.openApiParser = new OpenAPIV3Parser();
    }

    @PostConstruct
    private void initWebClient() {
        OasOrchestratorProperties.BackendConfig config = properties.getBackend();

        String baseUrl = backendProtocol + "://" + backendHost + ":" + backendPort + backendBaseUri;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
                .doOnConnected(connection -> {
                    connection.addHandlerLast(new ReadTimeoutHandler(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS));
                    connection
                            .addHandlerLast(new WriteTimeoutHandler(config.getWriteTimeoutMs(), TimeUnit.MILLISECONDS));
                });

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        log.info("BackendOasClient initialized with base URL: {}", baseUrl);
    }

    /**
     * Fetches the raw OpenAPI specification from the backend service.
     * 
     * <p>
     * Uses in-memory caching with TTL to avoid repeated network calls.
     * The raw OAS is shared across all users.
     * </p>
     * 
     * @return Mono containing the parsed OpenAPI model
     */
    public Mono<OpenAPI> fetchRawOas() {
        // Check in-memory cache first
        CachedOas cached = cachedRawOas.get();
        if (cached != null && !cached.isExpired()) {
            return Mono.just(cached.getOpenApi());
        }

        log.debug("Fetching raw OAS from backend: {}", properties.getBackend().getSpecPath());

        return webClient.get()
                .uri(properties.getBackend().getSpecPath())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), response -> {
                    log.error("Backend OAS fetch failed with status: {}", response.statusCode());
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Backend OpenAPI spec fetch failed with status: " + response.statusCode()));
                })
                .bodyToMono(String.class)
                .timeout(properties.getBackend().getResponseTimeout())
                .map(this::parseOasContent)
                .doOnNext(openApi -> {
                    Duration ttl = properties.getCache().getRawOasTtl();
                    cachedRawOas.set(new CachedOas(openApi, ttl));
                })
                // DO NOT TOLERATE STALE DATA
                .doOnError(e -> {
                    log.error("CRITICAL: Failed to fetch raw OAS, clearing cache to prevent stale usage. Error: {}",
                            e.getMessage());
                    cachedRawOas.set(null); // Clear cache to force retry on next request
                })
                .onErrorMap(e -> {
                    // Convert all errors to 503 Service Unavailable
                    if (e instanceof ResponseStatusException)
                        return e;
                    return new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "OpenAPI specification is currently unavailable and no fresh data exists.");
                });
    }

    /**
     * Forces a refresh of the cached raw OAS.
     * Useful for admin endpoints or cache invalidation scenarios.
     * 
     * @return Mono containing the freshly fetched OpenAPI model
     */
    public Mono<OpenAPI> forceRefresh() {
        cachedRawOas.set(null);
        return fetchRawOas();
    }

    /**
     * Parses the OAS content string into an OpenAPI model.
     * 
     * @param content JSON or YAML content
     * @return Parsed OpenAPI model
     * @throws ResponseStatusException if parsing fails
     */
    private OpenAPI parseOasContent(String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        SwaggerParseResult result = openApiParser.readContents(content, null, options);

        if (result.getOpenAPI() == null) {
            String errors = result.getMessages() != null
                    ? String.join(", ", result.getMessages())
                    : "Unknown parse error";
            log.error("Failed to parse backend OAS: {}", errors);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Invalid OpenAPI specification from backend: " + errors);
        }

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            log.warn("OAS parse warnings: {}", result.getMessages());
        }

        log.debug("Successfully parsed OAS: {} paths, {} schemas",
                result.getOpenAPI().getPaths() != null ? result.getOpenAPI().getPaths().size() : 0,
                result.getOpenAPI().getComponents() != null && result.getOpenAPI().getComponents().getSchemas() != null
                        ? result.getOpenAPI().getComponents().getSchemas().size()
                        : 0);

        return result.getOpenAPI();
    }

    /**
     * In-memory cache entry for raw OAS with TTL tracking.
     */
    private static class CachedOas {
        private final OpenAPI openApi;
        private final long expiresAt;

        CachedOas(OpenAPI openApi, Duration ttl) {
            this.openApi = openApi;
            this.expiresAt = System.currentTimeMillis() + ttl.toMillis();
        }

        OpenAPI getOpenApi() {
            return openApi;
        }
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
