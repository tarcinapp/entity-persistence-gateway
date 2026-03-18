package com.tarcinapp.entitypersistencegateway.unit.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarcinapp.entitypersistencegateway.KindAliasConfigAttr;
import com.tarcinapp.entitypersistencegateway.filters.common.request.InjectTypeHintsToQueryGatewayFilterFactory;
import com.tarcinapp.entitypersistencegateway.registry.TypeHintSchemaRegistry;
import com.tarcinapp.entitypersistencegateway.util.MockExchangeBuilder;
import com.tarcinapp.entitypersistencegateway.util.MockGatewayFilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InjectTypeHintsToQueryGatewayFilterFactory}.
 *
 * Strategy: use a mocked {@link TypeHintSchemaRegistry} to isolate the filter
 * logic from schema-parsing concerns, and verify query-param mutation through
 * the captured exchange on the mock filter chain.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InjectTypeHintsToQueryFilterFactory Unit Tests")
class InjectTypeHintsToQueryGatewayFilterFactoryTest {

    @Mock
    private TypeHintSchemaRegistry typeHintSchemaRegistry;

    @InjectMocks
    private InjectTypeHintsToQueryGatewayFilterFactory filterFactory;

    private MockGatewayFilterChain filterChain;
    private InjectTypeHintsToQueryGatewayFilterFactory.Config config;

    // A KindAliasConfigAttr that signals a kind alias IS configured
    private KindAliasConfigAttr activeAttr;

    @BeforeEach
    void setUp() {
        filterChain = new MockGatewayFilterChain();
        config = new InjectTypeHintsToQueryGatewayFilterFactory.Config();

        activeAttr = new KindAliasConfigAttr();
        activeAttr.setKindAliasConfigured(true);
        activeAttr.setKindName("book");
        activeAttr.setControllerName("entities");
        activeAttr.setBaseControllerName("entities");
    }

    // =========================================================================
    // 1. Skip scenarios
    // =========================================================================

    @Nested
    @DisplayName("Skip / pass-through scenarios")
    class SkipScenarios {

        @Test
        @DisplayName("Should pass through when query params are empty")
        void shouldPassThroughOnEmptyQueryParams() {
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            verifyNoInteractions(typeHintSchemaRegistry);
        }

        @Test
        @DisplayName("Should pass through when no KindAliasConfigAttr is set")
        void shouldPassThroughWhenNoAttr() {
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price]", "10")
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("filter[where][price][type]")).isFalse();
        }

        @Test
        @DisplayName("Should pass through when kind alias is not configured")
        void shouldPassThroughWhenAliasNotConfigured() {
            KindAliasConfigAttr noAlias = new KindAliasConfigAttr();
            noAlias.setKindAliasConfigured(false);

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, noAlias)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("filter[where][price][type]")).isFalse();
        }

        @Test
        @DisplayName("Should pass through when registry returns no hint")
        void shouldPassThroughWhenNoHint() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), anyString()))
                    .thenReturn(Optional.empty());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][title]", "foo")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("filter[where][title][type]")).isFalse();
        }

        @Test
        @DisplayName("Should skip managed fields (starting with _)")
        void shouldSkipManagedFields() {
            // Registry should NOT be called for _ fields
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][_createdBy]", "user1")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("filter[where][_createdBy][type]")).isFalse();
            verify(typeHintSchemaRegistry, never()).resolveHint(any(), any(), eq("_createdBy"));
        }

        @Test
        @DisplayName("Should skip lookup params")
        void shouldSkipLookupParams() {
            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[lookup][author][where][id]", "1")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            assertThat(filterChain.wasFilterCalled()).isTrue();
            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("filter[lookup][author][where][id][type]")).isFalse();
        }
    }

    // =========================================================================
    // 2. Injection – implicit-eq (no operator in key)
    // =========================================================================

    @Nested
    @DisplayName("Type hint injection – implicit-eq keys")
    class ImplicitEqInjection {

        @Test
        @DisplayName("Should inject number type hint and convert implicit eq to explicit [eq]")
        void shouldInjectNumberHintForFilterWhere() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Implicit eq must be converted to explicit [eq]
            assertThat(params.containsKey("filter[where][price]")).isFalse();
            assertThat(params.getFirst("filter[where][price][eq]")).isEqualTo("10");
            // Type hint sits at the field level (alongside the renamed [eq] key)
            assertThat(params.getFirst("filter[where][price][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject boolean type hint for filter[where][field]")
        void shouldInjectBooleanHint() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("inStock")))
                    .thenReturn(Optional.of("boolean"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][inStock]", "true")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Implicit eq must be converted to explicit [eq]
            assertThat(params.containsKey("filter[where][inStock]")).isFalse();
            assertThat(params.getFirst("filter[where][inStock][eq]")).isEqualTo("true");
            assertThat(params.getFirst("filter[where][inStock][type]")).isEqualTo("boolean");
        }

        @Test
        @DisplayName("Should inject hint for where[field] (bulk-operation family)")
        void shouldInjectForWhereBracket() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("where[price]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("where[price]")).isFalse();
            assertThat(params.getFirst("where[price][eq]")).isEqualTo("10");
            assertThat(params.getFirst("where[price][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hint for entityFilter[where][field]")
        void shouldInjectForEntityFilterWhere() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("rating")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("entityFilter[where][rating]", "5")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("entityFilter[where][rating]")).isFalse();
            assertThat(params.getFirst("entityFilter[where][rating][eq]")).isEqualTo("5");
            assertThat(params.getFirst("entityFilter[where][rating][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hint for listFilter[where][field]")
        void shouldInjectForListFilterWhere() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("pageCount")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("listFilter[where][pageCount]", "100")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("listFilter[where][pageCount]")).isFalse();
            assertThat(params.getFirst("listFilter[where][pageCount][eq]")).isEqualTo("100");
            assertThat(params.getFirst("listFilter[where][pageCount][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hint for filterThrough[where][field]")
        void shouldInjectForFilterThroughWhere() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("score")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filterThrough[where][score]", "90")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("filterThrough[where][score]")).isFalse();
            assertThat(params.getFirst("filterThrough[where][score][eq]")).isEqualTo("90");
            assertThat(params.getFirst("filterThrough[where][score][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hint for entityWhere[field]")
        void shouldInjectForEntityWhere() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("entityWhere[price]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("entityWhere[price]")).isFalse();
            assertThat(params.getFirst("entityWhere[price][eq]")).isEqualTo("10");
            assertThat(params.getFirst("entityWhere[price][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hint for listWhere[field]")
        void shouldInjectForListWhere() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("views")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("listWhere[views]", "500")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.containsKey("listWhere[views]")).isFalse();
            assertThat(params.getFirst("listWhere[views][eq]")).isEqualTo("500");
            assertThat(params.getFirst("listWhere[views][type]")).isEqualTo("number");
        }
    }

    // =========================================================================
    // 3. Injection – explicit operator (operator segment present in key)
    // =========================================================================

    @Nested
    @DisplayName("Type hint injection – explicit operator keys")
    class ExplicitOperatorInjection {

        @Test
        @DisplayName("Should inject type sibling after [gt] operator")
        void shouldInjectAfterGtOperator() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price][gt]", "5")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Original key must still be present unchanged
            assertThat(params.getFirst("filter[where][price][gt]")).isEqualTo("5");
            assertThat(params.getFirst("filter[where][price][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject type sibling after [lte] operator")
        void shouldInjectAfterLteOperator() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("pageCount")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][pageCount][lte]", "300")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Original key must still be present unchanged
            assertThat(params.getFirst("filter[where][pageCount][lte]")).isEqualTo("300");
            assertThat(params.getFirst("filter[where][pageCount][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject type sibling after [inq] with array index — stripping the index")
        void shouldStripArrayIndexAndInjectAfterInq() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price][inq][0]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Trailing [inq][0] stripped; type goes at field level
            assertThat(params.getFirst("filter[where][price][type]")).isEqualTo("number");
            // Original key must still be present
            assertThat(params.getFirst("filter[where][price][inq][0]")).isEqualTo("10");
        }

        @Test
        @DisplayName("Should inject only one type sibling for multiple array elements of same field")
        void shouldInjectOneSiblingForMultipleArrayElements() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price][inq][0]", "5")
                    .queryParam("filter[where][price][inq][1]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.get("filter[where][price][type]")).hasSize(1);
            assertThat(params.getFirst("filter[where][price][type]")).isEqualTo("number");
            // All original array elements must still be present
            assertThat(params.getFirst("filter[where][price][inq][0]")).isEqualTo("5");
            assertThat(params.getFirst("filter[where][price][inq][1]")).isEqualTo("10");
        }
    }

    // =========================================================================
    // 4. Logical operators (and / or nesting)
    // =========================================================================

    @Nested
    @DisplayName("Type hint injection – logical operator nesting")
    class LogicalOperatorNesting {

        @Test
        @DisplayName("Should inject hint for field nested under [and]")
        void shouldInjectForAndNestedField() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][and][0][price]", "10")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Implicit eq must be converted to explicit [eq]
            assertThat(params.containsKey("filter[where][and][0][price]")).isFalse();
            assertThat(params.getFirst("filter[where][and][0][price][eq]")).isEqualTo("10");
            assertThat(params.getFirst("filter[where][and][0][price][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hint for operator under [or] nesting")
        void shouldInjectForOrNestedFieldWithOperator() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("pageCount")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][or][1][pageCount][gt]", "100")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Original key must still be present unchanged
            assertThat(params.getFirst("filter[where][or][1][pageCount][gt]")).isEqualTo("100");
            assertThat(params.getFirst("filter[where][or][1][pageCount][type]")).isEqualTo("number");
        }
    }

    // =========================================================================
    // 5. Dot-notation field names
    // =========================================================================

    @Nested
    @DisplayName("Type hint injection – dot-notation field names")
    class DotNotationFields {

        @Test
        @DisplayName("Should inject hint for dot-notation field info.pageCount")
        void shouldInjectForDotNotationField() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("info.pageCount")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][info.pageCount]", "200")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Implicit eq must be converted to explicit [eq]
            assertThat(params.containsKey("filter[where][info.pageCount]")).isFalse();
            assertThat(params.getFirst("filter[where][info.pageCount][eq]")).isEqualTo("200");
            assertThat(params.getFirst("filter[where][info.pageCount][type]")).isEqualTo("number");
        }

        @Test
        @DisplayName("Should inject hints for dot-notation with [lt] and bracket-notation implicit-eq under [or] clause")
        void shouldInjectForOrNestedDotNotationWithLtAndBracketImplicitEq() {
            // ?filter[where][or][0][publisher.yearFounded][lt]=1998
            //  &filter[where][or][1][dimensions][widthCm]=17
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("publisher.yearFounded")))
                    .thenReturn(Optional.of("number"));
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("dimensions.widthCm")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][or][0][publisher.yearFounded][lt]", "1998")
                    .queryParam("filter[where][or][1][dimensions.widthCm]", "17")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();

            // Explicit-op key must remain unchanged; type sibling placed at field level
            assertThat(params.getFirst("filter[where][or][0][publisher.yearFounded][lt]")).isEqualTo("1998");
            assertThat(params.getFirst("filter[where][or][0][publisher.yearFounded][type]")).isEqualTo("number");

            // Implicit-eq bracket-notation key must be renamed to explicit [eq]
            assertThat(params.containsKey("filter[where][or][1][dimensions.widthCm]")).isFalse();
            assertThat(params.getFirst("filter[where][or][1][dimensions.widthCm][eq]")).isEqualTo("17");
            assertThat(params.getFirst("filter[where][or][1][dimensions.widthCm][type]")).isEqualTo("number");
        }
    }

    // =========================================================================
    // 6. Multiple fields in the same request
    // =========================================================================

    @Nested
    @DisplayName("Multiple fields in one request")
    class MultipleFields {

        @Test
        @DisplayName("Should inject hints for multiple different fields independently")
        void shouldInjectForMultipleFields() {
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("inStock")))
                    .thenReturn(Optional.of("boolean"));
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("title")))
                    .thenReturn(Optional.empty());

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price]", "10")
                    .queryParam("filter[where][inStock]", "true")
                    .queryParam("filter[where][title]", "Spring")
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            // Implicit eq keys must be converted
            assertThat(params.containsKey("filter[where][price]")).isFalse();
            assertThat(params.getFirst("filter[where][price][eq]")).isEqualTo("10");
            assertThat(params.getFirst("filter[where][price][type]")).isEqualTo("number");
            assertThat(params.containsKey("filter[where][inStock]")).isFalse();
            assertThat(params.getFirst("filter[where][inStock][eq]")).isEqualTo("true");
            assertThat(params.getFirst("filter[where][inStock][type]")).isEqualTo("boolean");
            // String field without a hint must pass through unchanged
            assertThat(params.getFirst("filter[where][title]")).isEqualTo("Spring");
            assertThat(params.containsKey("filter[where][title][type]")).isFalse();
        }

        @Test
        @DisplayName("Should not add duplicate type siblings when key already contains [type]")
        void shouldNotDuplicateTypeHint() {
            // Client already sent a type hint; the filter must not add another one
            when(typeHintSchemaRegistry.resolveHint(any(), any(), eq("price")))
                    .thenReturn(Optional.of("number"));

            ServerWebExchange exchange = MockExchangeBuilder.create()
                    .path("/api/v1/books")
                    .queryParam("filter[where][price]", "10")
                    .queryParam("filter[where][price][type]", "number")   // already present
                    .attribute(KindAliasConfigAttr.KIND_ALIAS_CONFIG_ATTR, activeAttr)
                    .build();

            GatewayFilter filter = filterFactory.apply(config);
            filter.filter(exchange, filterChain).block();

            MultiValueMap<String, String> params = filterChain.getCapturedExchange()
                    .getRequest().getQueryParams();
            assertThat(params.get("filter[where][price][type]")).hasSize(1);
        }
    }
}
