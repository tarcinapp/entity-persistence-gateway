# Overview
This application adds API Gateway capabilities to the entity-persistence-service of tarcinapp project. These capabilities include:
* Global request rate limiting for all endpoints. Some endpoints require even narrower request rates limits. This requirement is addressed in that particular route.
* Signed JWT based authorization token validation for all endpoints of entity-persistence-service.
* Authorization related fields (ownerUsers and ownerGroups) are filled by the gateway with the claim values provided in the JWT token payload.
* Authorization policy execution with the OPA (Open Policy Agent).
* Resource filtering in responses based on the policy execution results.
# Configuration
## JWTS Private Key
This application validates RSA256 encrypted authorization tokens using the private key string. Provide the key to the application with 'JWTS_PRIVATE_KEY' environment variable. It's better to set the value from a Secret in K8S environments. For CI/CD pipelines in Rancher managed environment, please see *Deployment with Rancher Pipelines*.
# Deployment
## Deployment with Rancher Pipelines
This application contains .rancher-pipeline.yaml file for CI/CD pipeline configuration in Rancher. This file configured to tell Rancher to use the YAML files located under /k8s folder of the application for creating k8s resources.
### Configuration
Application deployment yaml file assumes that a Secret named entity-persistence-gateway-secret is configured in the tarcinapp-test namespace prior to the deployment. Create the secret as follows before deploying the application:
```yaml
apiVersion: v1
kind: Secret
metadata:
    name: entity-persistence-gateway-secret
    namespace: tarcinapp-test
data:
    JWTS_PRIVATE_KEY: *your private key here*
```