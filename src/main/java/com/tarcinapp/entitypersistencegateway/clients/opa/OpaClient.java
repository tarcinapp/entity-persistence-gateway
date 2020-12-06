package com.tarcinapp.entitypersistencegateway.clients.opa;

import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import com.tarcinapp.entitypersistencegateway.authorization.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyData;
import com.tarcinapp.entitypersistencegateway.authorization.PolicyResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class OpaClient implements IAuthorizationClient {

    private WebClient webClient;

    @Value("${app.opa.host:#{localhost}}")
    private String host;

    @Value("${app.opa.port:#{8181}}")
    private String port;

    @Value("${app.opa.protocol:#{http}}")
    private String protocol;

    private String url;

    public OpaClient() {
        
    }

    /**
     * Set the opa url, default timeouts and headers
     */
    @PostConstruct 
    private void initWebClient() {
        this.url = this.protocol + "://" + this.host + ":" + this.port + "/v1/data/";
    
        TcpClient tcpClient = TcpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .doOnConnected(connection -> {
                    connection.addHandlerLast(new ReadTimeoutHandler(2000, TimeUnit.MILLISECONDS));
                    connection.addHandlerLast(new WriteTimeoutHandler(2000, TimeUnit.MILLISECONDS));
                });

        this.webClient = WebClient.builder().baseUrl(url)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_CHARSET, "UTF-8")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.from(tcpClient)))
            .build();
    }

    public PolicyResult executePolicy(String policyName, PolicyData data) {

        PolicyRequest policyInput = new PolicyRequest();
        policyInput.setInput(data);

        PolicyResult result = new PolicyResult();

        String response = webClient
            .post()
            .uri(policyName)
            .body(BodyInserters.fromValue(policyInput))
            .retrieve()
            .bodyToMono(String.class).block();

        System.out.println(response);

        return result;
    }
}
