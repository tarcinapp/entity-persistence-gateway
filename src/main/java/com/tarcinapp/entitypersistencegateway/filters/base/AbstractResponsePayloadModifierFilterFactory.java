package com.tarcinapp.entitypersistencegateway.filters.base;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import com.tarcinapp.entitypersistencegateway.auth.PolicyData;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public abstract class AbstractResponsePayloadModifierFilterFactory<C, I, O> extends AbstractGatewayFilterFactory<C> {

    private Class<I> inClass;
    private Class<O> outClass;

    private Logger logger = LogManager.getLogger(AbstractResponsePayloadModifierFilterFactory.class);

    public AbstractResponsePayloadModifierFilterFactory(Class<C> configClass, Class<I> inClass, Class<O> outClass) {
        super(configClass);
        this.inClass = inClass;
        this.outClass = outClass;
    }

    public abstract Mono<O> modifyResponsePayload(C config, ServerWebExchange exchange, I payload);

    @Override
    public GatewayFilter apply(C config) {

        return new OrderedGatewayFilter((exchange, chain) -> {
            ServerWebExchangeDecorator exchangeDecorator = new ServerWebExchangeDecorator(exchange) {

                @Override
                public ServerHttpResponse getResponse() {
                    ServerHttpResponse originalResponse = super.getResponse();

                    return new ServerHttpResponseDecorator(originalResponse) {

                        @Override
                        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {

                            if (originalResponse.getStatusCode().is2xxSuccessful()) {

                                String originalResponseContentType = exchange
                                        .getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                                HttpHeaders httpHeaders = new HttpHeaders();

                                // explicitly add it in this way instead of
                                // 'httpHeaders.setContentType(originalResponseContentType)'
                                // this will prevent exception in case of using non-standard media
                                // types like "Content-Type: image"
                                httpHeaders.add(HttpHeaders.CONTENT_TYPE, originalResponseContentType);

                                ClientResponse clientResponse = prepareClientResponse(body, httpHeaders);

                                Mono<O> modifiedBody = extractBody(exchange, clientResponse, inClass)
                                        .flatMap(originalBody -> modifyResponsePayload(config, exchange,
                                                (I) originalBody)
                                                .switchIfEmpty(Mono.empty()));

                                BodyInserter<Mono<O>, ReactiveHttpOutputMessage> bodyInserter = BodyInserters
                                        .fromPublisher(modifiedBody, outClass);

                                CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(
                                        exchange,
                                        exchange.getResponse().getHeaders());

                                return bodyInserter.insert(outputMessage, new BodyInserterContext())
                                        .then(Mono.defer(() -> {

                                            Mono<DataBuffer> messageBody = writeBody(getDelegate(),
                                                    outputMessage,
                                                    outClass);
                                            HttpHeaders headers = getDelegate().getHeaders();

                                            if (!headers.containsKey(HttpHeaders.TRANSFER_ENCODING)
                                                    || headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
                                                messageBody = messageBody.doOnNext(
                                                        data -> headers.setContentLength(
                                                                data.readableByteCount()));
                                            }

                                            return getDelegate().writeWith(messageBody);
                                        }));
                            }

                            return super.writeWith(body);
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
                }
            };

            return chain.filter(exchangeDecorator);
        
            // this is placed here to perform response payload modifications after retrieving the data from cache
        }, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 10);
    }

}
