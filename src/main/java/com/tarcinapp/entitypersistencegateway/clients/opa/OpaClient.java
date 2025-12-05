package com.tarcinapp.entitypersistencegateway.clients.opa;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;
import com.tarcinapp.entitypersistencegateway.auth.PolicyResult;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

@Component
@Slf4j
public class OpaClient implements IAuthorizationClient {

    private WebClient webClient;
    
    private final ObjectMapper objectMapper;

    @Value("${app.opa.host:localhost}")
    private String host;

    @Value("${app.opa.port:8181}")
    private String port;

    @Value("${app.opa.protocol:http}")
    private String protocol;

    @Value("${app.opa.connectTimeoutMs:500}")
    private int connectTimeoutMs;

    @Value("${app.opa.responseTimeout:500ms}")
    private Duration responseTimeout;

    @Value("${app.opa.readTimeoutMs:300}")
    private int readTimeoutMs;

    @Value("${app.opa.writeTimeoutMs:300}")
    private int writeTimeoutMs;

    public OpaClient(ObjectMapper objectMapper) {
        // Create a dedicated mapper derived from the main one but with specific config
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    private void initWebClient() {
        String url = this.protocol + "://" + this.host + ":" + this.port + "/v1/data/";

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .doOnConnected(connection -> {
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
            .map(PolicyResponse::getResult)
            .retryWhen(Retry.backoff(2, Duration.ofMillis(50))
                .filter(this::isRetryableException)) 
            .onErrorMap(this::handleError);
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
            .map(gpr -> objectMapper.convertValue(gpr.getResult(), type))
            .retryWhen(Retry.backoff(2, Duration.ofMillis(50))
                .filter(this::isRetryableException))
            .onErrorMap(this::handleError);
    }

    private boolean isRetryableException(Throwable ex) {
        if (ex instanceof WebClientRequestException) {
            return ex.getCause() instanceof ReadTimeoutException;
        }
        return ex instanceof TimeoutException;
    }
    
    private Throwable handleError(Throwable ex) {
        if (ex instanceof WebClientRequestException && ex.getCause() instanceof ReadTimeoutException) {
            log.warn("OPA Read Timeout: {}", ex.getMessage());
            return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Authorization service timed out");
        }
        
        if (ex instanceof TimeoutException) {
             log.warn("OPA Global Timeout: {}", ex.getMessage());
             return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Authorization service timed out");
        }

        log.error("OPA execution failed", ex);
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Authorization check failed");
    }
}