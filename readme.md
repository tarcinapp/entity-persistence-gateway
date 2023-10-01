# Overview
This application adds API Gateway capabilities to the entity-persistence-service of tarcinapp project. These capabilities include:
* Global request rate limiting for all endpoints. Some routes require even narrower request rates limits. This requirement is addressed in that particular route.
* Signed JWT based authorization token validation for all endpoints of entity-persistence-service.
* Authorization related fields (ownerUsers and ownerGroups) are filled by the gateway with the claim values provided in the JWT token payload.
* Authorization policy execution with the OPA (Open Policy Agent). With that, control's who can create, update, inquiry what.
* Manage certain fields based on caller's role (ownerUsers, ownerGroups, creationDateTime, createdBy, lastUpdatedDateTime, lastUpdatedBy)
* Field masking in responses based on the policy execution results.
* Reduce query scope according to caller's roles
* Generate idempotency key based on given fields list for the record creation (It's still backend's responsibility to enable/disable idempotency).
# Authentication
This application uses JWT based token authentication. JWT token validation takes place only if the rs256 encrypted public key provided. Roles are extraced from the JWT token. Roles must present in payload as string array with the `roles` key. Email verification status must present with the `email_verified` key.
# Authorization
This application requires 4 level of roles: admin, editor, member and visitor. You can configure names for the roles in configuration. Roles must be in JWT according to the configuration.
Admin: Can query, create, alter and delete all the data.
Editor: Can query all the data but can not change the creationDateTime field of records. Cannot delete any data.  Cannot change ownership of the records. Cannot create a data with creationDateTime field. Can not create a data with ownerUsers or ownerGroups fields
Member: 
* Can query all public data.
* Can query all protected data if the user is in one of the groups recorded in ownerGroups field of the specific data.
* Can query all private data if the user is in the ownerUsers field of the data.
* Never see the managed fields of the data such as: validFromDateTime, validUntilDateTime, visibility.
* Can create data if the policy allows the operation. Every invocation that the member attempts are subjected to OPA policies. OPA policies are managed externally. 
Visitor:
* Only GET operations are allowed.
* Can see the public data.
* Never see the managed fields of the data such as: validFromDateTime, validUntilDateTime, visibility.

# Configuration
## Saved Field Sets
Field sets can be defined in configuration file to make querying a complex list of fields easier. Instead of naming every field in the query parameter clients can give the name of the field set.
Role based masking on the fields is still applied. Role-based field masking remains in effect. Even if clients make specific requests or use field sets, they will be unable to view certain fields unless they have the necessary authorization.

`app.fieldsets.managed`: Selects only the managed fields from the backend.
`app.fieldsets.unmanaged`: Selects only the unmanaged fields from the backend. Only id, name and kind is kept in the list of requested fields.

Field sets can be used in query parameters such as:
generic-entities?fieldset=unmanaged

# Saved Queries
A query parameter string can be configured to shorten the long list of commonly used queries. Context variables such as userId, and now can be used while building queries

`app.queries.my`: set[owners][]=[${userId}][]
`app.queries.actives`: set[actives]

generic-entities?query=my

## JWTS Private Key
This application validates RSA256 encrypted authorization tokens using the private key string. Provide the key to the application with 'app.auth.rs256PublicKey' environment variable. For CI/CD pipelines in Rancher managed environment, please see *Deployment with Rancher Pipelines*.
## Deployment to Kubernetes
Use k8s/deployment.yaml file to deploy all related k8s resources.

## Local Development
Configure vscode to start application with -Dspring.profiles.active=dev
Make local configurations under src/main/resources/application-dev.yaml