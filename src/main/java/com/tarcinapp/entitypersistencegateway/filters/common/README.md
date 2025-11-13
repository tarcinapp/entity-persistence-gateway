# AuthorizeRequest Filter

## Overview

`AuthorizeRequest` is a Spring Cloud Gateway filter that enforces authorization policies on incoming requests. It acts as a Policy Enforcement Point (PEP) by delegating authorization decisions to an external authorization service.

## Purpose

This filter intercepts incoming requests and validates whether the request should be allowed to proceed based on configured authorization policies. It integrates with an authorization client (likely OPA - Open Policy Agent) to make policy-based access control decisions.

## How It Works

1. **Extracts Policy Data**: Retrieves policy inquiry data from the exchange attributes (populated by previous filters)
2. **Policy Execution**: Sends the policy data along with a specified policy name to the authorization client
3. **Decision Enforcement**: 
   - If authorized: allows the request to continue through the filter chain
   - If denied: returns HTTP 401 (Unauthorized) and terminates the request
4. **Error Handling**: Any errors during authorization result in an unauthorized response

## Configuration

The filter accepts a `Config` object with:
- `policyName`: The name of the authorization policy to execute

## Key Features

- **RS256 Key Validation**: Checks if an RSA key is configured; skips authorization if not available (with a warning)
- **Debug Logging**: Provides detailed logging of policy data and authorization decisions when debug level is enabled
- **Error Resilience**: Handles errors gracefully by returning unauthorized responses rather than failing open
- **Policy Data Cloning**: Creates a copy of the policy inquiry data to avoid mutation issues

## Dependencies

- `IAuthorizationClient`: Interface for communicating with the policy decision point
- `Key`: RS256 public key for JWT validation (optional)
- Policy inquiry data must be populated in exchange attributes by upstream filters

## Usage in Routes

```yaml
filters:
  - AuthorizeRequest=policyName=my_policy
```

## Security Considerations

- Fails closed: Any error during authorization results in denial
- Requires RS256 key configuration in production
- Logs authorization decisions for audit purposes
