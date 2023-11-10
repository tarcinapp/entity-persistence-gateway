# Overview
The Entity Persistence Gateway, powered by [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway) framework, is a central component within the Tarcinapp Suite. This gateway provides comprehensive functionality, including
* Authentication
* Authorization
* Routing
* Rate Limiting
* Distributed Lock
* Field masking
* Predefined queries
* Predefined sets of fields to streamline querying processes

Here is the visualized representation of the request-response flow for the `createEntity` operation in the entity-persistence-gateway application.
<p align="center">
  <img src="./doc/img/flow.png" alt="Request-Response flow for createEntity" width="400">
</p>

As the front door to the Entity Persistence Service and Entity Persistence Policies, the Entity Persistence Gateway efficiently handles incoming requests, enforces security policies, and orchestrates responses. With its diverse set of features, it simplifies the management of fine-grained access control, making it an indispensable part of your application ecosystem.

## What is Tarcinapp Suite?

The Tarcinapp suite is a comprehensive and flexible application framework, harmoniously blending a suite of interconnected components designed to deliver a seamless and secure microservices architecture. It also provides the flexibility for users to leverage it as an upstream project for their own REST API-based backend implementations, allowing for easy adaptation to their specific requirements and use cases.

<p align="center">
  <img src="./doc/img/tarcinapp.png" alt="Tarcinapp Suite Overview">
</p>

At its core is the **Entity Persistence Service**, an easily adaptable REST-based backend application built on the [Loopback 4](https://loopback.io) framework. This service utilizes on a schemaless MongoDB database to provide a scalable and highly adaptable data persistence layer. Offering a generic data model with predefined fields such as `id`, `name`,  `kind`, `lastUpdateDateTime`, `creationDateTime`, `ownerUsers` and [more](#programming-conventions), it effortlessly adapts to diverse use cases.  

The integration with the **Entity Persistence Gateway** empowers users to implement enhanced validation, authentication, authorization, and rate-limiting functionalities. Leveraging the power of **Redis**, the application seamlessly manages distributed locks and rate limiting. Furthermore, the ecosystem includes the [Open Policy Agent (OPA](https://www.openpolicyagent.org)) to enforce policies, safeguarding your application against unauthorized access and ensuring compliance with your security and operational requirements.  
  
Here is an example request and response to the one of the most basic endpoint: `/generic-entities`:
<p align="left">
  <img src="./doc/img/request-response.png" alt="Sample request and response">
</p>  

**Note:** The client's authorization to create an entity, the fields that user can specify, and the fields returned in the response body may vary based on the user's role. The values of managed fields such as `visibility`, `idempotencyKey`, `validFromDateTime`, and `validUntilDateTime` can also be adjusted according to the user's role and the system's configuration.  
  
**Note**: Endpoints can be configured with arbitrary values within the gateway component. For example, `/books` can be used for records with `kind: book`, and the field `kind` can be completely omitted from the API interaction.  

# Entity Persistence Gateway in Detail
Serving as a reverse proxy to each endpoint defined by the Entity Persistence Service, it channels incoming requests to their respective destinations. This enables the Entity Persistence Gateway to efficiently manage API traffic, enforce security policies, and direct responses back to the requesting clients. It ensures that clients interact with the Entity Persistence Service in a secure and controlled manner, serving as a crucial security and routing layer for your application.  

Here's a more structured list of the capabilities provided by the Entity Persistence Gateway:  
1. **Request Size Limiting**: The gateway enforces a default 1KB limit on incoming HTTP request sizes to prevent oversized requests from reaching the Entity Persistence Service.

2. **Request Rate Limiting**: Rate limiting mechanisms are applied uniformly to all endpoints to control the rate of incoming requests, ensuring fair usage and system stability.

3. **JWT Token Validation**: The gateway validates incoming JWT-based authorization tokens to ensure that requests to the Entity Persistence Service are accompanied by valid and secure authorization.

4. **Authorization Policy Execution**: Leveraging the Open Policy Agent (OPA), the gateway executes authorization policies that determine who can create, update, or query specific records, providing fine-grained access control.

5. **Claim-Based Field Population**: Fields related to authorization, such as `ownerUsers` and `ownerGroups`, are automatically populated by the gateway based on the claims provided in the JWT token payload.

6. **Role-Based Field Management**: The gateway dynamically manages certain fields (e.g., `ownerUsers`, `ownerGroups`, `creationDateTime`, `createdBy`, `lastUpdatedDateTime`, `lastUpdatedBy`) according to the caller's role, ensuring controlled access and data integrity.

7. **Field Masking**: The gateway masks certain fields in responses based on the results of policy execution, enhancing data security by restricting sensitive information from being exposed. To learn how to configure which role can see what field see [entity-persistence-gateway-policies](https://github.com/tarcinapp/entity-persistence-gateway-policies).

8. **Query Scope Reduction**: The gateway reduces the scope of queries based on the caller's role, ensuring that users can only access data that aligns with their authorized roles and permissions.

9. **Distributed Lock Management**: For CRUD operations, the gateway acquires distributed locks to prevent data conflicts and ensure data consistency in a distributed system.

10. **Query Language Abstraction**: The gateway hides the underlying Loopback data querying notation from clients, simplifying the API interaction and providing a user-friendly query experience. For example, clients can use `?limit=5&skip=10` instead of `?filter[limit]=5&filter[skip]=10`.

11. **Field Sets for Easier Querying**: It offers the ability to define sets of fields, enhancing the querying process by allowing clients to request specific sets of fields for response data. Field sets are defined at the gateway configuration. This way, clients can make a query like `?fieldset=bookinfo` which only returns `id`, `name` and `author` fields only of the book records.

12. **Predefined Queries**: Clients can utilize predefined queries (e.g., `?q=my-query`) to streamline their data retrieval process by specifying common query conditions. When setting up predefined queries, utilize context variables such as user id and other query parameters. You can leverage advanced Java operations using SPEL to manipulate these variables while constructing the query for the backend.

13. **Route Configuration**: The gateway allows route configuration for the `/generic-entity` endpoint based on the 'kind' of entity. For example, it can route all requests for `kind: book` to the `/generic-entity`, narrowing the scope specifically to book records for all CRUD operations. Multiple routes can be defined for various kinds, providing semantic endpoints in routing requests to the Entity Persistence Service.

These capabilities collectively empower the Entity Persistence Gateway to deliver comprehensive security, access control, data management, and routing features to your application.

# Configuration
The overall configuration of the Entity Persistence Gateway is primarily managed through a Spring YAML file. You can see the whole configuration from this file: [application.yaml](src/main/resources/application.yml).

You can map environment variables to various configuration properties in the Spring application.yaml file for containerized environments.  
For example to configure  `app.auth.rs256PublicKey` parameter in the YAML file you can use the environment variable named `APP_AUTH_RS256_PUBLIC_KEY`. See [Externalized Configuration](https://docs.spring.io/spring-boot/docs/1.5.6.RELEASE/reference/html/boot-features-external-config.html).

## Authentication
Entity Persistence Gateway utilizes JWT-based token authentication to secure its endpoints. JWT token validation is a crucial step in ensuring that requests to the gateway are legitimate. This validation process is based on the presence of an RS256 encrypted public key that should be provided.

```yaml
app:
  auth: 
    rs256PublicKey: your-public-key-here
```

### Role Extraction
Roles play a critical role in controlling and authorizing access to various parts of the Entity Persistence Gateway. These roles are extracted from the JWT token provided in the request. To properly set roles for your users, ensure that the JWT token includes a string array of roles, identified by the roles key. Assuming your `app.shortcode` is configured as tarcinapp:  

```json
{
  "roles": ["tarcinapp.admin", "tarcinapp.editor", "tarcinapp.member"],
  // Other JWT claims...
}

```
In the example above, the JWT token includes an array of roles that are essential for determining the user's access level within the system.

### Email Verification Status
Email verification status is another important attribute that can influence a user's access to certain resources. The JWT token should include the email verification status, marked by the `email_verified` key.

```json
{
  "email_verified": true,
  // Other JWT claims...
}
```

## Authorization
Tarcinapp Suite utilizes a role-based access control system, provided by the entity-persistence-policies and entity-persistence-gateway components, to determine whether a caller is authorized to execute particular operations or access specific fields.

Roles within Tarcinapp Suite are derived from JWT claims, specifically from the `roles` field in the JWT body. Each user is assigned roles via this string array field.

Effective role names can be constructed using the application short code, `tarcinapp` for instance, as a prefix to enable a single user to have different roles for various Tarcinapp instances. This prefix is taken from the `app.shortcode` configuration. For instance if `app.shortcode` value is configured as `tarcinapp`, then these are the valid roles for that specific application instance: `tarcinapp.member`, `tarcinapp.editor`, etc.. Similarly, you can set `app.shortcode` to `books` and assign a user the `books.member` role to grant them the corresponding permissions for that specific Tarcinapp instance.  

To determine if a user is allowed to perform an operation, a policy data containing the application shortcode, JWT token, request body, requested endpoint, query parameters and HTTP headers is prepared and sent to the entity-persistence-policies component through an HTTP call. If request is allowed, gateway permits the flow to proceed. Otherwise, gateway returns a HTTP 401 error. Similarly, same policy data is used to determine which fields are forbidden for what operation.

To learn what roles are privileged to make which operations see [entity-persistence-policies](https://github.com/tarcinapp/entity-persistence-gateway-policies#policies) documentation for each route.

## Saved Field Sets
Field sets can be defined in configuration file to make querying a complex list of fields easier. Instead of naming every field in the query parameter clients can give the name of the field set.  
These are the preconfigured field sets:  
```yaml
app: 
 fieldsets:
    managed:
      show: id, kind, name, slug, visibility, version, ownerUsers, ownerGroups, ownerUsersCount, ownerGroupsCount, creationDateTime, lastUpdatedDateTime, lastUpdatedBy, createdBy, validFromDateTime, validUntilDateTime, idempotencyKey
    unmanaged:
      hide: slug, visibility, version, ownerUsers, ownerGroups, ownerUsersCount, ownerGroupsCount, creationDateTime, lastUpdatedDateTime, lastUpdatedBy, createdBy, validFromDateTime, validUntilDateTime, idempotencyKey
```
  
`app.fieldsets.managed`: Selects only the managed fields from the backend.  
`app.fieldsets.unmanaged`: Selects only the unmanaged fields from the backend. Only id, name and kind is kept in the list of requested fields along with other unmanaged fields.

Field sets can be used in query parameters such as:  
`generic-entities?fieldset=unmanaged`  

**Example field set configuration for books application**:  
```bash
APP_FIELDSETS_BOOKINFO=id, name, slug, author
```

**Note:** Role-based field masking remains in effect. Even if clients make specific requests or use field sets, they will be unable to view certain fields unless they have the necessary authorization.

### Default Field Set
You can define a default field set configuration which applies to all findAll, findById and create operations. Default field set can be configured as follows:
`app.defaultFieldset:unmanaged`

# Saved Queries
A query parameter string can be configured to shorten the long list of commonly used queries. Context variables such as `userId`, `now` can be used while building queries. You can use `query` context variable to access other query parameters to build new query to the backend.

Here you can find examples from predefined queries:

```yaml
app:  
  queries:  
    my: "'sets[owners]=['+#userId+'][]'"  
    actives: "'sets[actives]'"
```
**Example usage:**  
`generic-entities?query=my`

**Introducing new predefined query configuration:**  
```bash
APP_QUERIES_BY_BOOK_NAME="'filter[where][slug]=' + #query['book-name']"
```
Usage: `?books/q=by-book-name&book-name=overcoat`

**Power of SPEL:**  
Predefined query configuration within entity-persistence-gateway levareges [Spring Expression Language (SPEL)](https://docs.spring.io/spring-framework/docs/3.0.x/reference/expressions.html) to let advanced configurations. For example you can 
split the given list of ids from the specific query parameter and make a query to the backend to retrieve all records with these list of ids:
```bash
APP_QUERIES_BY_IDS="#{#query[ids].split(',') !.stream().map(value -> 'filter[or][where][id]=' + value).collect(T(java.util.stream.Collectors).joining('&'))}"
```

## Loopback Query Abstraction
Loopback 4 is using a certain notation to enable backend querying as described here: [Querying Data](https://loopback.io/doc/en/lb4/Querying-data.html). While Loopback's approach is very useful, it may be a security issue to let your clients know what backend technology you are using. `allowLoopbackQueryNotation` configuration can be useful for purpose.


**Searching Entities:**  
**Original**: `?s=foo`
**Mapped**: `?filter[where][name][regexp]=.*foo.*`

**Ordering Entities:**  
**Original**: `?order=name`  
**Mapped**: `?filter[order]=name`  

**Skipping Entities:**  
**Original**: `?skip=10`  
**Mapped**: `?filter[skip]=10`  

**Limiting Entities:**  
**Original**: `?limit=20`  
**Mapped**: `?filter[limit]=20`  

Use this functionality along with predefined queries to address your application needs.  

## JWTS Private Key
This application validates RSA256 encrypted authorization tokens using the private key string. Provide the key to the application with 'app.auth.rs256PublicKey' environment variable. For CI/CD pipelines in Rancher managed environment, please see *Deployment with Rancher Pipelines*.
## Deployment to Kubernetes
Use k8s/deployment.yaml file to deploy all related k8s resources.

## Local Development
Configure vscode to start application with -Dspring.profiles.active=dev
Make local configurations under src/main/resources/application-dev.yaml