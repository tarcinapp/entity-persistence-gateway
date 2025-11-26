package com.tarcinapp.entitypersistencegateway.clients.opa;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.auth.PolicyResult;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
public class OpaClient implements IAuthorizationClient {

    private WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.opa.host:localhost}")
    private String host;

    @Value("${app.opa.port:8181}")
    private String port;

    @Value("${app.opa.protocol:http}")
    private String protocol;

    // Timeout for TCP handshake
    @Value("${app.opa.connectTimeoutMs:500}")
    private int connectTimeoutMs;

    // Hard deadline for the total request-response duration
    @Value("${app.opa.responseTimeout:500ms}")
    private Duration responseTimeout;

    // Timeout for idle read connections (no data received from server)
    @Value("${app.opa.readTimeoutMs:300}")
    private int readTimeoutMs;

    // Timeout for idle write connections (cannot send data to server)
    @Value("${app.opa.writeTimeoutMs:300}")
    private int writeTimeoutMs;

    /**
     * Dependency Injection for ObjectMapper.
     */
    public OpaClient(ObjectMapper objectMapper) {
        // Create a copy to avoid side effects on the global mapper
        this.objectMapper = objectMapper.copy();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    private void initWebClient() {
        String url = this.protocol + "://" + this.host + ":" + this.port + "/v1/data/";

        HttpClient httpClient = HttpClient.create()
                // Phase 1: Connection Timeout
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .doOnConnected(connection -> {
                    // Phase 2: Socket-level idle timeouts
                    connection.addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS));
                    connection.addHandlerLast(new WriteTimeoutHandler(writeTimeoutMs, TimeUnit.MILLISECONDS));
                });

        this.webClient = WebClient.builder()
                .baseUrl(url)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_CHARSET, "UTF-8")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<PolicyResult> executePolicy(PolicyData data) {
        PolicyRequest policyInput = new PolicyRequest();
        policyInput.setInput(data);

        return webClient
            .post()
            .uri(data.getPolicyName())
            .body(BodyInserters.fromValue(policyInput))
            .retrieve()
            .bodyToMono(PolicyResponse.class)
            .timeout(responseTimeout)
            .map(PolicyResponse::getResult);
    }

    @Override
    public <T> Mono<T> executePolicy(PolicyData data, Class<T> type) {
        PolicyRequest policyInput = new PolicyRequest();
        policyInput.setInput(data);

        return webClient
            .post()
            .uri(data.getPolicyName())
            .body(BodyInserters.fromValue(policyInput))
            .retrieve()
            .bodyToMono(GenericPolicyResponse.class)
            .timeout(responseTimeout)
            .map(gpr -> {
                
                return objectMapper.convertValue(gpr.getResult(), type);
            });
    }
}