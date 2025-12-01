package com.tarcinapp.entitypersistencegateway.clients.backend;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
public class BackendClientBase implements IBackendClientBase {
    
    private WebClient webClient;

    @Value("${app.outbound.backend.host:entity-persistence-service}")
    private String host;

    @Value("${app.outbound.backend.port:80}")
    private String port;

    @Value("${app.outbound.backend.protocol:http}")
    private String protocol;

    // TCP Handshake Timeout
    @Value("${app.outbound.backend.connectTimeoutMs:3000}")
    private int connectTimeoutMs;

    // Socket Read Timeout (Data gap)
    @Value("${app.outbound.backend.readTimeoutMs:300}")
    private int readTimeoutMs;

    // Socket Write Timeout
    @Value("${app.outbound.backend.writeTimeoutMs:300}")
    private int writeTimeoutMs;
    
    // Total Request Timeout (Deadline)
    @Value("${app.outbound.backend.responseTimeout:3000ms}")
    private Duration responseTimeout;

    @PostConstruct
    private void initWebClient() {
        String url = this.protocol + "://" + this.host + ":" + this.port;
    
        // Modern Reactor Netty Configuration (Replaces deprecated TcpClient)
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

    public <T> Mono<T> get(String path, Class<T> type) {
        return webClient
            .get()
            .uri(path)
            .retrieve()
            .bodyToMono(type)
            .timeout(responseTimeout); // Enforce global timeout
    }
}