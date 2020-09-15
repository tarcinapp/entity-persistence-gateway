# Configuration
## JWTS Private Key
This application validates RSA256 encrypted authorization tokens using the private key string. Provide the key to the application with 'JWTS_PRIVATE_KEY' environment variable. It's better to set the value from a Secret in K8S environments. For CI/CD pipelines in Rancher managed environment, please see *Deployment with Rancher Pipelines*.
# Deployment
## Deployment with Rancher Pipelines
This application contains .rancher-pipeline.yaml file for CI/CD pipeline configuration Rancher. This file configured to tell Rancher to use the YAML files located under /k8s folder of the application for creating k8s resources.
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