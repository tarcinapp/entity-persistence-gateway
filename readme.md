# Overview
This application adds API Gateway capabilities to the entity-persistence-service of tarcinapp project. These capabilities include:
* Global request rate limiting for all endpoints. Some endpoints require even narrower request rates limits. This requirement is addressed in that particular route.
* Signed JWT based authorization token validation for all endpoints of entity-persistence-service.
* Authorization related fields (ownerUsers and ownerGroups) are filled by the gateway with the claim values provided in the JWT token payload.
* Authorization policy execution with the OPA (Open Policy Agent).
* Resource filtering in responses based on the policy execution results.
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

## JWTS Private Key
This application validates RSA256 encrypted authorization tokens using the private key string. Provide the key to the application with 'app.auth.rs256PublicKey' environment variable. For CI/CD pipelines in Rancher managed environment, please see *Deployment with Rancher Pipelines*.
## Deployment to Kubernetes
Use k8s/deployment.yaml file to deploy all related k8s resources.

## Local Development
Configure vscode to start application with -Dspring.profiles.active=dev
