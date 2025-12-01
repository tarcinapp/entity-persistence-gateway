# Plan: Optimize ObjectMapper Usage Across Codebase

This refactoring will eliminate performance issues caused by repeated `ObjectMapper` instantiation by migrating all classes to use Spring's dependency injection pattern with constructor injection. The changes will affect 13 files with `new ObjectMapper()` calls and optimize `TypeReference` usage in 9 files.

## Steps

1. **Refactor [ValidateEntityRequestBody.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/common/ValidateEntityRequestBody.java)** — Replace 3 separate `new ObjectMapper()` calls (lines 74, 189, 291) with a single `private final ObjectMapper objectMapper` field using constructor injection. Add `ObjectMapper` parameter to the existing no-args constructor.

2. **Refactor filters in entitycontroller/outbound/** — Update [DropEntityFieldsBasedOnPermissions.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/outbound/DropEntityFieldsBasedOnPermissions.java), [AddFieldToResponse.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/outbound/AddFieldToResponse.java), and [LogEntityResponse.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/outbound/LogEntityResponse.java) to inject `ObjectMapper` via constructor. These currently create instances at lines 44, 43, and 112 respectively.

3. **Refactor filters in entitycontroller/inbound/** — Update [LogEntityRequest.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/inbound/LogEntityRequest.java) and [AbstractPolicyAwareResponsePayloadModifierFilterFactory.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/outbound/AbstractPolicyAwareResponsePayloadModifierFilterFactory.java) (line 133 and 130) to use constructor-injected `ObjectMapper`. Note: AbstractPolicyAware... already has a constructor with 2 parameters, so add `ObjectMapper` as third parameter.

4. **Refactor services package** — Update [UpdateEntityFieldsService.java](src/main/java/com/tarcinapp/entitypersistencegateway/services/UpdateEntityFieldsService.java) (remove constructor instantiation at line 40), [GetIdFromJwtTokenService.java](src/main/java/com/tarcinapp/entitypersistencegateway/services/GetIdFromJwtTokenService.java) (replace field init at line 25), and PolicyData-related services ([EntityResourcePolicyDataService.java](src/main/java/com/tarcinapp/entitypersistencegateway/services/policydata/EntityResourcePolicyDataService.java), [EntityCollectionPolicyDataService.java](src/main/java/com/tarcinapp/entitypersistencegateway/services/policydata/EntityCollectionPolicyDataService.java), [HealthCheckPolicyDataService.java](src/main/java/com/tarcinapp/entitypersistencegateway/services/policydata/HealthCheckPolicyDataService.java)) with constructor-injected instances.

5. **Extract static TypeReference constants** — In files using `new TypeReference<Map<String, Object>>(){}` repeatedly ([DropEntityFieldsBasedOnPermissions.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/outbound/DropEntityFieldsBasedOnPermissions.java) and similar), extract to `private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {}` at class level, following the pattern already used in [AddManagedFieldsInCreation.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/inbound/AddManagedFieldsInCreation.java).

6. **Validate and cleanup** — Remove unused `ObjectMapper` imports if any remain, verify all Spring components properly inject the bean, and confirm that classes already using constructor injection ([OpaClient.java](src/main/java/com/tarcinapp/entitypersistencegateway/clients/opa/OpaClient.java), [PlaceKindNameIntoPayload.java](src/main/java/com/tarcinapp/entitypersistencegateway/filters/entitycontroller/inbound/PlaceKindNameIntoPayload.java)) remain unchanged as they follow best practices.

## Further Considerations

1. **JavaTimeModule configuration** — Several classes register `JavaTimeModule` after creating `ObjectMapper`. Should we create a custom `@Bean` with pre-configured `JavaTimeModule`, or inject and configure per class? Recommendation: Keep per-class configuration to avoid breaking existing serialization behavior.
**Handle `JavaTimeModule`:**
    * **CRITICAL:** If you see code like `objectMapper.registerModule(new JavaTimeModule())` or `findAndRegisterModules()`, **REMOVE IT**.
    * Assume the injected Spring `ObjectMapper` bean is ALREADY configured with `JavaTimeModule` and correct date settings globally. Do not re-configure the injected bean inside the methods.

2. **OpaClient special case** — [OpaClient.java](src/main/java/com/tarcinapp/entitypersistencegateway/clients/opa/OpaClient.java) receives `ObjectMapper` via DI but creates a copy with custom settings. This is intentional and should remain unchanged. Yes.

3. **Spring Boot's default ObjectMapper** — Spring Boot auto-configures an `ObjectMapper` bean. Do you want to rely on this default, or create a custom `@Configuration` class with an explicit `@Bean` definition for better control and documentation? Current plan assumes using the default. Correct.
