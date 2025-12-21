# Test Development Guide for Entity-Persistence-Gateway

## Overview

This document provides a comprehensive testing strategy for the Entity-Persistence-Gateway, a Spring Cloud Gateway application. The strategy covers unit tests, filter-level integration tests, and end-to-end testing patterns using reactive testing frameworks.

## Current Status

### Working Tests ✅
- **Unit tests** for TokenParserRegistry, FieldFilterService, AuthenticateRequest filter, AuthorizeRequest filter
- All unit tests pass and can be run with: `./mvnw test -Dtest="*Test,!*IntegrationTest"`

### Disabled Tests ⚠️
- **Integration tests** are currently disabled (`@Disabled`) because they require complex external service configuration
- Integration tests need OPA client to use WireMock instead of the default `entity-persistence-gateway-policies` hostname

### Why Integration Tests are Disabled

The OPA client (`OpaClient.java`) uses `@Value` annotations to inject its configuration:
```java
@Value("${app.outbound.opa.host:localhost}")
private String opaHost;
```

These values are resolved at bean instantiation time, before `@DynamicPropertySource` can inject test values. To enable integration tests, you would need to either:

1. **Create a test profile** that overrides OPA configuration in `application-test.yml` with localhost
2. **Use `@TestPropertySource`** with explicit property values before context creation
3. **Replace the OpaClient bean** with a test double using `@MockBean` or `@TestConfiguration`
4. **Use Spring Cloud Contract** with embedded stubs

## Table of Contents

1. [Test Dependencies](#test-dependencies)
2. [Test Directory Structure](#test-directory-structure)
3. [Shared Test Infrastructure](#shared-test-infrastructure)
4. [Unit Testing Patterns](#unit-testing-patterns)
5. [Filter Integration Testing](#filter-integration-testing)
6. [External Service Mocking](#external-service-mocking)
7. [Test Implementation Phases](#test-implementation-phases)
8. [Running Tests](#running-tests)

---

## Test Dependencies

The following dependencies have been added to `pom.xml`:

```xml
<!-- Test Dependencies -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- Reactive Testing -->
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- Embedded Redis -->
<dependency>
    <groupId>com.github.codemonstur</groupId>
    <artifactId>embedded-redis</artifactId>
    <version>1.4.3</version>
    <scope>test</scope>
</dependency>

<!-- WireMock for External Service Mocking -->
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.3.1</version>
    <scope>test</scope>
</dependency>

<!-- MockWebServer for WebClient mocking -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>4.12.0</version>
    <scope>test</scope>
</dependency>
```

---

## Test Directory Structure

```
src/test/java/com/tarcinapp/entitypersistencegateway/
├── unit/
│   ├── filters/
│   │   ├── AuthenticateRequestTest.java
│   │   ├── AuthorizeRequestTest.java
│   │   ├── AddManagedFieldsInCreationTest.java
│   │   ├── FetchForbiddenFieldsTest.java
│   │   ├── FieldFilterGatewayFilterFactoryTest.java
│   │   └── ...
│   ├── services/
│   │   ├── JwtAuthenticationServiceTest.java
│   │   ├── SecurityContextBuilderTest.java
│   │   ├── FieldFilterServiceTest.java
│   │   ├── FieldsetServiceTest.java
│   │   └── ...
│   ├── helpers/
│   │   ├── TokenParserRegistryTest.java
│   │   ├── QueryStringTargetAnalyzerTest.java
│   │   └── RecordTypeResolverTest.java
│   └── clients/
│       └── OpaClientTest.java
├── integration/
│   ├── filters/
│   │   ├── AuthenticationFilterIntegrationTest.java
│   │   ├── AuthorizationFilterIntegrationTest.java
│   │   ├── FieldFilterIntegrationTest.java
│   │   └── LockingFilterIntegrationTest.java
│   └── routes/
│       ├── EntityRoutesIntegrationTest.java
│       └── ListRoutesIntegrationTest.java
├── config/
│   ├── TestConfiguration.java
│   ├── EmbeddedRedisConfiguration.java
│   └── BaseFilterIntegrationTest.java
├── fixtures/
│   ├── JwtFixtures.java
│   ├── PolicyResponseFixtures.java
│   ├── EntityFixtures.java
│   └── ExchangeAttributeFixtures.java
└── util/
    ├── MockExchangeBuilder.java
    ├── MockGatewayFilterChain.java
    ├── TestJwtGenerator.java
    └── WireMockSetup.java
```

---

## Shared Test Infrastructure

### Key Utilities

| Utility | Purpose |
|---------|---------|
| `MockExchangeBuilder` | Fluent builder for creating mock `ServerWebExchange` instances |
| `MockGatewayFilterChain` | Mock filter chain that captures exchange and tracks invocations |
| `TestJwtGenerator` | Generates valid/invalid/expired JWT tokens for testing |
| `WireMockSetup` | Pre-configured stubs for OPA and backend services |

### Key Fixtures

| Fixture | Purpose |
|---------|---------|
| `JwtFixtures` | Pre-built tokens for different roles (admin, editor, member, visitor) |
| `PolicyResponseFixtures` | OPA policy responses (allow, deny, with forbidden fields) |
| `EntityFixtures` | Sample entity payloads and responses |
| `ExchangeAttributeFixtures` | Common exchange attributes like security context |

---

## Unit Testing Patterns

### Pattern 1: Filter Unit Test with Mocked Dependencies

```java
@ExtendWith(MockitoExtension.class)
class AuthorizeRequestTest {
    
    @Mock
    private IAuthorizationClient authorizationClient;
    
    @Mock
    private JwtAuthenticationService jwtAuthenticationService;
    
    private MockGatewayFilterChain filterChain;
    
    @BeforeEach
    void setUp() {
        filterChain = new MockGatewayFilterChain();
    }
    
    @Test
    void shouldAllowAuthorizedRequest() {
        // Given
        when(jwtAuthenticationService.isConfigured()).thenReturn(true);
        when(authorizationClient.executePolicy(any()))
            .thenReturn(Mono.just(PolicyResponseFixtures.allowedPolicy()));
        
        ServerWebExchange exchange = MockExchangeBuilder.create()
            .method(HttpMethod.POST)
            .path("/api/v1/entities")
            .authorization(JwtFixtures.memberToken())
            .body("{\"name\": \"test\"}")
            .build();
        
        // When & Then
        StepVerifier.create(filter.apply(config).filter(exchange, filterChain))
            .verifyComplete();
        
        assertThat(filterChain.wasFilterCalled()).isTrue();
    }
}
```

### Pattern 2: Service Unit Test

```java
class FieldFilterServiceTest {
    
    private FieldFilterService fieldFilterService;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fieldFilterService = new FieldFilterService(objectMapper);
    }
    
    @Test
    void shouldRemoveForbiddenFieldsFromResponse() throws Exception {
        // Given
        String response = EntityFixtures.completeEntity();
        List<String> forbiddenFields = PolicyResponseFixtures.memberForbiddenFields();
        
        // When
        String filtered = fieldFilterService.filterFields(response, forbiddenFields);
        
        // Then
        var node = objectMapper.readTree(filtered);
        assertThat(node.has("ownerUsers")).isFalse();
        assertThat(node.has("name")).isTrue();
    }
}
```

### Pattern 3: Helper Unit Test with Parameterized Tests

```java
class QueryStringTargetAnalyzerTest {
    
    @ParameterizedTest
    @CsvSource({
        "filter[where][name]=test, name",
        "filter[where][and][0][kind]=book, kind",
        "filter[fields][name]=true, name"
    })
    void shouldExtractFieldFromComplexQuery(String query, String expectedField) {
        var result = analyzer.analyze(query);
        assertThat(result.getTargetFields()).contains(expectedField);
    }
}
```

---

## Filter Integration Testing

### Base Test Configuration

All integration tests extend `BaseFilterIntegrationTest` which provides:

- WireMock servers for backend and OPA
- Embedded Redis
- `WebTestClient` for making requests
- Dynamic property configuration
- Test JWT key setup

### Example Integration Test

```java
class AuthenticationFilterIntegrationTest extends BaseFilterIntegrationTest {
    
    @Test
    @DisplayName("Should allow request with valid JWT token")
    void shouldAllowValidToken() {
        // Given
        opaSetup.stubOpaAllowPolicy("/v1/data/tarcinapp/entities/findall");
        backendSetup.stubBackendGetEntities();
        
        // When & Then
        webTestClient.get()
            .uri("/api/v1/entities")
            .header("Authorization", "Bearer " + JwtFixtures.memberToken())
            .exchange()
            .expectStatus().isOk();
    }
    
    @Test
    @DisplayName("Should reject request with expired token")
    void shouldRejectExpiredToken() {
        webTestClient.get()
            .uri("/api/v1/entities")
            .header("Authorization", "Bearer " + JwtFixtures.expiredToken())
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
```

---

## External Service Mocking

### OPA Response Scenarios

```java
// Allow policy
opaSetup.stubOpaAllowPolicy("/v1/data/tarcinapp/entities/create");

// Deny policy
opaSetup.stubOpaDenyPolicy("/v1/data/tarcinapp/entities/create");

// Allow with forbidden fields
opaSetup.stubOpaWithForbiddenFields(
    "/v1/data/tarcinapp/entities/findall",
    Arrays.asList("ownerUsers", "ownerGroups", "_createdDateTime")
);

// OPA error (for testing error handling)
opaSetup.stubOpaError("/v1/data/tarcinapp/entities/create");

// OPA timeout (for testing timeout handling)
opaSetup.stubOpaTimeout("/v1/data/tarcinapp/entities/create");
```

### Backend Response Scenarios

```java
// Successful entity fetch
backendSetup.stubBackendGetEntity("entity-uuid-here");

// Entity not found
backendSetup.stubBackendGetEntityNotFound("non-existent-id");

// Entity list
backendSetup.stubBackendGetEntities();

// Create entity
backendSetup.stubBackendCreateEntity();

// Backend timeout
backendSetup.stubBackendTimeout();
```

---

## Test Implementation Phases

### Phase 1: Core Authentication (Priority: P0)
| Test Class | Status | Description |
|------------|--------|-------------|
| `JwtAuthenticationServiceTest` | ✅ | Token validation logic |
| `TokenParserRegistryTest` | ✅ | Multi-issuer JWT parsing |
| `AuthenticateRequestTest` | ✅ | Authentication filter unit test |
| `AuthenticationFilterIntegrationTest` | ✅ | Full authentication flow |

### Phase 2: Authorization & Field Security (Priority: P0-P1)
| Test Class | Status | Description |
|------------|--------|-------------|
| `AuthorizeRequestTest` | ✅ | Authorization filter unit test |
| `FetchForbiddenFieldsTest` | ⬜ | Forbidden fields fetching |
| `FieldFilterServiceTest` | ✅ | Field masking service |
| `FieldFilterGatewayFilterFactoryTest` | ⬜ | Response field filtering |
| `AuthorizationFilterIntegrationTest` | ✅ | Full authorization flow |
| `FieldFilterIntegrationTest` | ✅ | Field filtering integration |

### Phase 3: Distributed Systems (Priority: P1)
| Test Class | Status | Description |
|------------|--------|-------------|
| `AcquireLockForCreationTest` | ⬜ | Lock acquisition for create |
| `AcquireLockForUpdateTest` | ⬜ | Lock acquisition for update |
| `DynamicLocalCacheServiceTest` | ⬜ | Local caching logic |
| `LockingFilterIntegrationTest` | ⬜ | Lock contention scenarios |

### Phase 4: Request Transformation (Priority: P2)
| Test Class | Status | Description |
|------------|--------|-------------|
| `AddManagedFieldsInCreationTest` | ⬜ | Managed fields injection |
| `AddManagedFieldsFromOriginalTest` | ⬜ | Preserve fields on replace |
| `PolicyDataBuilderTest` | ⬜ | Policy data construction |
| `SecurityContextBuilderTest` | ⬜ | Security context building |

### Phase 5: Query & Routing (Priority: P2-P3)
| Test Class | Status | Description |
|------------|--------|-------------|
| `ConvertSimplerQueriesToBackendFormatTest` | ⬜ | Query transformation |
| `PreventQueryByForbiddenFieldsTest` | ⬜ | Query field validation |
| `ApplyFieldsetConfigTest` | ⬜ | Fieldset application |
| `KindResolutionGatewayFilterFactoryTest` | ⬜ | Kind alias resolution |
| `QueryStringTargetAnalyzerTest` | ⬜ | Query parsing |

### Phase 6: Minor Filters (Priority: P3)
| Test Class | Status | Description |
|------------|--------|-------------|
| `GenerateRequestIdTest` | ⬜ | Request ID generation |
| `CheckIfRouteEnabledTest` | ⬜ | Route toggle checks |
| `DynamicRequestSizeFilterTest` | ⬜ | Request size validation |
| `PreventStringifiedJsonFilterTest` | ⬜ | Input validation |

---

## Running Tests

### Run All Tests
```bash
./mvnw test
```

### Run Unit Tests Only
```bash
./mvnw test -Dtest="**/unit/**"
```

### Run Integration Tests Only
```bash
./mvnw test -Dtest="**/integration/**"
```

### Run Specific Test Class
```bash
./mvnw test -Dtest="AuthenticateRequestTest"
```

### Run With Coverage
```bash
./mvnw test jacoco:report
```

### Run Tests Matching Pattern
```bash
./mvnw test -Dtest="*Authentication*"
```

### Run Only Unit Tests (excluding disabled integration tests)
```bash
./mvnw test
```

All integration tests are currently `@Disabled`, so running all tests will only execute unit tests.

---

## Enabling Integration Tests

To enable integration tests, you'll need to configure the application to use WireMock for OPA calls. Here's a recommended approach:

### Option 1: Test Properties File

Create `src/test/resources/application-integration.yml`:

```yaml
app:
  outbound:
    opa:
      protocol: http
      host: localhost
      port: ${wiremock.server.port:8181}
```

Then annotate integration tests with:
```java
@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "wiremock.server.port=${wiremock.server.port}"
})
```

### Option 2: Replace OPA Bean

Create a test configuration that provides a mock OPA client:

```java
@TestConfiguration
public class TestOpaConfiguration {
    
    @Bean
    @Primary
    public IAuthorizationClient testAuthorizationClient(WireMockServer wireMockServer) {
        // Return configured client pointing to WireMock
    }
}
```

---

## Best Practices

1. **Use `StepVerifier` for reactive assertions** - Essential for testing Mono/Flux returns
2. **Mock external services at the HTTP level** - Use WireMock for realistic integration tests
3. **Create reusable fixtures** - Reduces duplication and improves maintainability
4. **Test error scenarios** - Include timeouts, service unavailability, and invalid inputs
5. **Use `@DisplayName`** - Makes test reports more readable
6. **Isolate tests** - Reset WireMock and clear caches between tests
7. **Test security boundaries** - Ensure role-based access control works correctly
8. **Verify filter chain behavior** - Test that filters properly continue or halt the chain

---

## Test Coverage Goals

| Category | Target Coverage |
|----------|-----------------|
| Filters | 80% |
| Services | 85% |
| Helpers | 90% |
| Clients | 75% |
| Overall | 80% |

---

## Troubleshooting

### Common Issues

1. **Port conflicts with embedded Redis**
   - Solution: Use a non-standard port (6370) configured in `EmbeddedRedisConfiguration`

2. **JWT validation failures in tests**
   - Solution: Ensure `TestJwtGenerator` keys match the configured public key

3. **WireMock not matching requests**
   - Solution: Check URL paths and use `urlPathEqualTo` instead of `urlEqualTo`

4. **Reactive tests timing out**
   - Solution: Use `StepVerifier.create(...).expectTimeout(Duration.ofSeconds(5))`

5. **Filter chain not proceeding**
   - Solution: Check that mocked dependencies return expected values
