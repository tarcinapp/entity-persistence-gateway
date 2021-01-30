package com.tarcinapp.entitypersistencegateway.filters.base;

import static java.util.function.Function.identity;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;

import java.security.Key;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.tarcinapp.entitypersistencegateway.auth.IAuthorizationClient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
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
import reactor.core.scheduler.Schedulers;

public abstract class AbstractPolicyAwareResponsePayloadModifierFilterFactory<C extends PolicyEvaluatingFilterConfig, P, I, O>
        extends AbstractPolicyAwareFilterFactory<C, P> {

    @Autowired(required = false)
    Key key;

    @Autowired
    IAuthorizationClient authorizationClient;

    private Logger logger = LogManager.getLogger(AbstractPolicyAwareResponsePayloadModifierFilterFactory.class);

    private final static String POLICY_INQUIRY_DATA_ATTR = "PolicyInquiryData";

    private Class<P> policyResultClass;
    private Class<I> inClass;
    private Class<O> outClass;

    private final Map<String, MessageBodyDecoder> messageBodyDecoders;
    private final Map<String, MessageBodyEncoder> messageBodyEncoders;

    public AbstractPolicyAwareResponsePayloadModifierFilterFactory(Class<C> configClass, Class<P> policyResultClass,
            Class<I> inClass, Class<O> outClass, Set<MessageBodyDecoder> messageBodyDecoders,
            Set<MessageBodyEncoder> messageBodyEncoders) {
        super(configClass, policyResultClass);

        this.policyResultClass = policyResultClass;
        this.inClass = inClass;
        this.outClass = outClass;

        this.messageBodyDecoders = messageBodyDecoders.stream()
                .collect(Collectors.toMap(MessageBodyDecoder::encodingType, identity()));
        this.messageBodyEncoders = messageBodyEncoders.stream()
                .collect(Collectors.toMap(MessageBodyEncoder::encodingType, identity()));
    }

    public abstract Mono<O> modifyRequestPayload(C config, ServerWebExchange exchange, P policyResult, I payload);

    @Override
    public GatewayFilter apply(C config, P policyResult) {
        
        return new OrderedGatewayFilter((exchange, chain) -> {
            ServerHttpResponse originalResponse = exchange.getResponse();
            ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

                @SuppressWarnings("unchecked")
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {

                    String originalResponseContentType = exchange.getAttribute(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                    HttpHeaders httpHeaders = new HttpHeaders();

                    // explicitly add it in this way instead of
                    // 'httpHeaders.setContentType(originalResponseContentType)'
                    // this will prevent exception in case of using non-standard media
                    // types like "Content-Type: image"
                    httpHeaders.add(HttpHeaders.CONTENT_TYPE, originalResponseContentType);

                    ClientResponse clientResponse = prepareClientResponse(body, httpHeaders);

                    Mono modifiedBody = extractBody(exchange, clientResponse, inClass).flatMap(originalBody -> Mono
                            .just(applyTransform((String) originalBody, config)).switchIfEmpty(Mono.empty()));

                    BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, outClass);
                    CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange,
                            exchange.getResponse().getHeaders());
                    return bodyInserter.insert(outputMessage, new BodyInserterContext()).then(Mono.defer(() -> {
                        Mono<DataBuffer> messageBody = writeBody(getDelegate(), outputMessage, outClass);
                        HttpHeaders headers = getDelegate().getHeaders();
                        if (!headers.containsKey(HttpHeaders.TRANSFER_ENCODING)
                                || headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
                            messageBody = messageBody
                                    .doOnNext(data -> headers.setContentLength(data.readableByteCount()));
                        }

                        return getDelegate().writeWith(messageBody);
                    }));
                }

                @Override
                public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return writeWith(Flux.from(body).flatMapSequential(p -> p));
                }

                private ClientResponse prepareClientResponse(Publisher<? extends DataBuffer> body,
                        HttpHeaders httpHeaders) {
                    ClientResponse.Builder builder;
                    builder = ClientResponse.create(exchange.getResponse().getStatusCode(),
                            HandlerStrategies.withDefaults().messageReaders());
                    return builder.headers(headers -> headers.putAll(httpHeaders)).body(Flux.from(body)).build();
                }

                private <T> Mono<T> extractBody(ServerWebExchange exchange, ClientResponse clientResponse,
                        Class<T> inClass) {
                    // if inClass is byte[] then just return body, otherwise check if
                    // decoding required
                    if (byte[].class.isAssignableFrom(inClass)) {
                        return clientResponse.bodyToMono(inClass);
                    }

                    List<String> encodingHeaders = exchange.getResponse().getHeaders()
                            .getOrEmpty(HttpHeaders.CONTENT_ENCODING);

                    for (String encoding : encodingHeaders) {
                        MessageBodyDecoder decoder = messageBodyDecoders.get(encoding);
                        if (decoder != null) {
                            return clientResponse.bodyToMono(byte[].class).publishOn(Schedulers.parallel())
                                    .map(decoder::decode)
                                    .map(bytes -> exchange.getResponse().bufferFactory().wrap(bytes))
                                    .map(buffer -> prepareClientResponse(Mono.just(buffer),
                                            exchange.getResponse().getHeaders()))
                                    .flatMap(response -> response.bodyToMono(inClass));
                        }
                    }

                    return clientResponse.bodyToMono(inClass);
                }

                private Mono<DataBuffer> writeBody(ServerHttpResponse httpResponse, CachedBodyOutputMessage message,
                        Class<?> outClass) {
                    Mono<DataBuffer> response = DataBufferUtils.join(message.getBody());
                    if (byte[].class.isAssignableFrom(outClass)) {
                        return response;
                    }

                    List<String> encodingHeaders = httpResponse.getHeaders().getOrEmpty(HttpHeaders.CONTENT_ENCODING);
                    for (String encoding : encodingHeaders) {
                        MessageBodyEncoder encoder = messageBodyEncoders.get(encoding);
                        if (encoder != null) {
                            DataBufferFactory dataBufferFactory = httpResponse.bufferFactory();
                            response = response.publishOn(Schedulers.parallel()).map(buffer -> {
                                byte[] encodedResponse = encoder.encode(buffer);
                                DataBufferUtils.release(buffer);
                                return encodedResponse;
                            }).map(dataBufferFactory::wrap);
                            break;
                        }
                    }

                    return response;
                }

            };
            
            return chain.filter(exchange.mutate().response(decoratedResponse).build());
        }, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1);
    }

    protected String applyTransform(String input, C config) {
        // we're not doing anything fancy here
        return input.toUpperCase();
    }

}
