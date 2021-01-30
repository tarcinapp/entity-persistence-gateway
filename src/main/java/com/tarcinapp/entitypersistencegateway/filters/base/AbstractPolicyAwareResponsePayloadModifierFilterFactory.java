package com.tarcinapp.entitypersistencegateway.filters.base;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;

import java.security.Key;

import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;
import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public abstract class AbstractPolicyAwareResponsePayloadModifierFilterFactory<C extends PolicyEvaluatingFilterConfig, PR, I, O>
        extends AbstractPolicyAwareFilterFactory<C, PR> {

    @Autowired(required = false)
    Key key;

    @Autowired
    IAuthorizationClient authorizationClient;

    private Logger logger = LogManager.getLogger(AbstractPolicyAwareResponsePayloadModifierFilterFactory.class);

    private Class<I> inClass;
    private Class<O> outClass;
    private Class<PR> policyResultClass;

    public AbstractPolicyAwareResponsePayloadModifierFilterFactory(Class<C> configClass, Class<PR> policyResultClass,
            Class<I> inClass, Class<O> outClass) {
        super(configClass, policyResultClass);

        this.inClass = inClass;
        this.outClass = outClass;
        this.policyResultClass = policyResultClass;
    }

    public abstract Mono<O> modifyRequestPayload(C config, ServerWebExchange exchange, PR policyResult, I payload);

    @Override
    public GatewayFilter apply(C config) {

        return new OrderedGatewayFilter((exchange, chain) -> {

            if (key == null) {
                logger.debug("Skipping policy aware payload modifier filter as key is not configured.");
                return chain.filter(exchange);
            }

            PolicyData policyInquiryData;
            try {
                policyInquiryData = AbstractPolicyAwareResponsePayloadModifierFilterFactory.super.getPolicyInquriyData(
                        exchange);
                policyInquiryData.setPolicyName(config.getPolicyName());
            } catch (CloneNotSupportedException e) {
                return Mono.error(e);
            }

            logger.debug("Asking PEP for response modification filter.");

            return AbstractPolicyAwareResponsePayloadModifierFilterFactory.super.executePolicy(policyInquiryData)
                    .flatMap(pr -> {

                        ServerHttpResponse originalResponse = exchange.getResponse();
                        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(
                                originalResponse) {

                            @SuppressWarnings("unchecked")
                            @Override
                            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {

                                String originalResponseContentType = exchange
                                        .getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                                HttpHeaders httpHeaders = new HttpHeaders();

                                // explicitly add it in this way instead of
                                // 'httpHeaders.setContentType(originalResponseContentType)'
                                // this will prevent exception in case of using non-standard media
                                // types like "Content-Type: image"
                                httpHeaders.add(HttpHeaders.CONTENT_TYPE, originalResponseContentType);

                                ClientResponse clientResponse = prepareClientResponse(body, httpHeaders);

                                Mono modifiedBody = extractBody(exchange, clientResponse, inClass)
                                        .flatMap(originalBody -> modifyRequestPayload(config, exchange, pr, (I) originalBody)
                                                .switchIfEmpty(Mono.empty()));

                                BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, outClass);
                                CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange,
                                        exchange.getResponse().getHeaders());
                                return bodyInserter.insert(outputMessage, new BodyInserterContext())
                                        .then(Mono.defer(() -> {
                                            Mono<DataBuffer> messageBody = writeBody(getDelegate(), outputMessage,
                                                    outClass);
                                            HttpHeaders headers = getDelegate().getHeaders();
                                            if (!headers.containsKey(HttpHeaders.TRANSFER_ENCODING)
                                                    || headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
                                                messageBody = messageBody.doOnNext(
                                                        data -> headers.setContentLength(data.readableByteCount()));
                                            }

                                            return getDelegate().writeWith(messageBody);
                                        }));
                            }

                            @Override
                            public Mono<Void> writeAndFlushWith(
                                    Publisher<? extends Publisher<? extends DataBuffer>> body) {
                                return writeWith(Flux.from(body).flatMapSequential(p -> p));
                            }

                            private ClientResponse prepareClientResponse(Publisher<? extends DataBuffer> body,
                                    HttpHeaders httpHeaders) {
                                ClientResponse.Builder builder;
                                builder = ClientResponse.create(exchange.getResponse().getStatusCode(),
                                        HandlerStrategies.withDefaults().messageReaders());
                                return builder.headers(headers -> headers.putAll(httpHeaders)).body(Flux.from(body))
                                        .build();
                            }

                            private <T> Mono<T> extractBody(ServerWebExchange exchange, ClientResponse clientResponse,
                                    Class<T> inClass) {
                                // if inClass is byte[] then just return body, otherwise check if
                                // decoding required
                                if (byte[].class.isAssignableFrom(inClass)) {
                                    return clientResponse.bodyToMono(inClass);
                                }

                                return clientResponse.bodyToMono(inClass);
                            }

                            private Mono<DataBuffer> writeBody(ServerHttpResponse httpResponse,
                                    CachedBodyOutputMessage message, Class<?> outClass) {
                                Mono<DataBuffer> response = DataBufferUtils.join(message.getBody());
                                if (byte[].class.isAssignableFrom(outClass)) {
                                    return response;
                                }

                                return response;
                            }

                        };

                        return chain.filter(exchange.mutate().response(decoratedResponse).build());
                    });

        }, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1);
    }

    @Override
    public GatewayFilter apply(C config, PR policyResult) {
        // TODO Auto-generated method stub
        return null;
    }
}
