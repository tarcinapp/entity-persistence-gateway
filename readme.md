# Overview
The Entity Persistence Gateway, powered by [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway), is a central component within the Tarcinapp Suite. This gateway provides comprehensive functionality, including authentication, authorization, routing, and field masking, ensuring that your APIs are securely accessed and data is protected. Furthermore, it offers predefined queries and field sets to streamline querying processes, making it easier to access and manipulate data within your application. As the front door to the Entity Persistence Service and Entity Persistence Policies, the Entity Persistence Gateway efficiently handles incoming requests, enforces security policies, and orchestrates responses. With its diverse set of features, it simplifies the management of fine-grained access control, making it an indispensable part of your application ecosystem.

## What is Tarcinapp Suite?

The Tarcinapp suite is a comprehensive and flexible application framework, harmoniously blending a suite of interconnected components designed to deliver a seamless and secure microservices architecture. It also provides the flexibility for users to leverage it as an upstream project for their own REST API-based backend implementations, allowing for easy adaptation to their specific requirements and use cases.

<p align="center">
  <img src="./doc/img/tarcinapp.png" alt="Tarcinapp Suite Overview">
</p>

At its core is the **Entity Persistence Service**, an easily adaptable REST-based backend application built on the [Loopback 4](https://loopback.io) framework. This service utilizes on a schemaless MongoDB database to provide a scalable and highly adaptable data persistence layer. Offering a generic data model with predefined fields such as `id`, `name`,  `kind`, `lastUpdateDateTime`, `creationDateTime`, `ownerUsers` and [more](#programming-conventions), it effortlessly adapts to diverse use cases.  

The integration with the **Entity Persistence Gateway** empowers users to implement enhanced validation, authentication, authorization, and rate-limiting functionalities, ensuring a secure and efficient environment. Leveraging the power of **Redis**, the application seamlessly manages distributed locks, enabling robust data synchronization and rate limiting. Furthermore, the ecosystem includes the **Open Policy Agent (OPA)** to enforce policies, safeguarding your application against unauthorized access and ensuring compliance with your security and operational requirements. These policies, combined with the entire suite of components, form a cohesive and powerful ecosystem, paving the way for efficient and secure microservice development.  
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

5. **Claim-Based Field Population**: Fields related to authorization, such as ownerUsers and ownerGroups, are automatically populated by the gateway based on the claims provided in the JWT token payload.

6. **Role-Based Field Management**: The gateway dynamically manages certain fields (e.g., ownerUsers, ownerGroups, creationDateTime, createdBy, lastUpdatedDateTime, lastUpdatedBy) according to the caller's role, ensuring controlled access and data integrity.

7. **Field Masking**: The gateway masks certain fields in responses based on the results of policy execution, enhancing data security by restricting sensitive information from being exposed.

8. **Query Scope Reduction**: The gateway reduces the scope of queries based on the caller's role, ensuring that users can only access data that aligns with their authorized roles and permissions.

9. **Distributed Lock Management**: For CRUD (Create, Read, Update, Delete) operations, the gateway acquires distributed locks to prevent data conflicts and ensure data consistency in a distributed system.

10. **Query Language Abstraction**: The gateway hides the underlying Loopback data querying notation from clients, simplifying the API interaction and providing a user-friendly query experience.

11. **Field Sets for Easier Querying**: It offers the ability to define sets of fields, enhancing the querying process by allowing clients to request specific sets of fields for response data.

12. **Predefined Queries**: Clients can utilize predefined queries (e.g., ?q=my-query) to streamline their data retrieval process by specifying common query conditions.

13. **Route Configuration**: The gateway allows route configuration for the /generic-entity endpoint based on the 'kind' of entity. For example, it can route all requests for 'kind: book' to the /generic-entity, narrowing the scope specifically to book records for all CRUD operations. Multiple routes can be defined for various 'kinds,' providing flexibility in routing requests to the Entity Persistence Service.

These capabilities collectively empower the Entity Persistence Gateway to deliver comprehensive security, access control, data management, and routing features to your application.

# Configuration
The overall configuration of the Entity Persistence Gateway is primarily managed through a Spring YAML file. This configuration file plays a pivotal role in shaping the behavior of the gateway and ensures seamless communication between the client applications and the underlying Entity Persistence Service.  

Each route is meticulously configured, defining various aspects such as request size limits, authentication, authorization, and more.  

You can map environment variables to various configuration properties in your Spring application.yaml file. For example, by using Spring Boot's built-in support for reading environment variables, you can map an environment variable named `APP_AUTH_RS256_PUBLIC_KEY` to the `app.auth.rs256PublicKey` property. This way, you can set the RS256 public key for JWT validation as an environment variable.

## Authentication
Entity Persistence Gateway utilizes JWT-based token authentication to secure its endpoints. JWT token validation is a crucial step in ensuring that requests to the gateway are legitimate. This validation process is based on the presence of an RS256 encrypted public key that should be provided. Without proper JWT token validation, requests will not be authenticated.

### Public Key Configuration
Before diving into JWT validation, it's important to note that you must configure the RS256 encrypted public key to enable proper token validation. This configuration is pivotal for the security of the gateway. Without it, requests will not be authenticated, leaving your application exposed to potential security risks.

### How to Configure the Public Key
To configure the RS256 encrypted public key for JWT validation, you should provide it in your application's environment variables or configuration settings. The exact method of configuration may vary depending on your deployment environment. Make sure the public key is stored securely and made available to the Entity Persistence Gateway application.

### Role Extraction
Roles play a critical role in controlling and authorizing access to various parts of the Entity Persistence Gateway. These roles are extracted from the JWT token provided in the request. To properly set roles for your users, ensure that the JWT token includes a string array of roles, identified by the roles key.

```json
{
  "roles": ["tarcinapp.admin", "tarcinapp.editor", "tarcinapp.member"],
  // Other JWT claims...
}

```
In the example above, the JWT token includes an array of roles that are essential for determining the user's access level within the system.

### Email Verification Status
Email verification status is another important attribute that can influence a user's access to certain resources. The JWT token should include the email verification status, marked by the email_verified key.

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

To learn more see entity-persistence-policies documentation.



# Configuration
## Saved Field Sets
Field sets can be defined in configuration file to make querying a complex list of fields easier. Instead of naming every field in the query parameter clients can give the name of the field set.
Role-based field masking remains in effect. Even if clients make specific requests or use field sets, they will be unable to view certain fields unless they have the necessary authorization.

`app.fieldsets.managed`: Selects only the managed fields from the backend.
`app.fieldsets.unmanaged`: Selects only the unmanaged fields from the backend. Only id, name and kind is kept in the list of requested fields along with other unmanaged fields.

Field sets can be used in query parameters such as:
generic-entities?fieldset=unmanaged

### Default Field Set
You can define a default field set configuration which applies to all findAll, findById and create operations. Default field set can be configured as follows:
`app.defaultFieldset:unmanaged`

# Saved Queries
A query parameter string can be configured to shorten the long list of commonly used queries. Context variables such as userId, and now can be used while building queries

`app.queries.my`: "'sets[owners]=['+#userId+'][]'"
`app.queries.actives`: "'sets[actives]'"

generic-entities?query=my

## JWTS Private Key
This application validates RSA256 encrypted authorization tokens using the private key string. Provide the key to the application with 'app.auth.rs256PublicKey' environment variable. For CI/CD pipelines in Rancher managed environment, please see *Deployment with Rancher Pipelines*.
## Deployment to Kubernetes
Use k8s/deployment.yaml file to deploy all related k8s resources.

## Local Development
Configure vscode to start application with -Dspring.profiles.active=dev
Make local configurations under src/main/resources/application-dev.yaml