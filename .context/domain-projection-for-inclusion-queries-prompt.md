You are an expert Solutions Architect and Senior Java Developer working on the **Tarcinapp Suite**, specifically the `entity-persistence-gateway` (a Spring Cloud Gateway application). 

I need you to design and implement a new feature: **Domain-Specific Inclusions**.

### Context
Our backend (`entity-persistence-service`) is built on Loopback 4. It supports fetching relational data via REST using a specific inclusion syntax. 
The core generic data model consists of `lists`, `entities`, `listReactions`, and `entityReactions`. 
The valid base inclusion relations defined at the backend level are: `_entities` and `_reactions`.

Examples of native backend calls:
- `GET /lists?filter[include][0][relation]=_entities`
- `GET /entities?filter[include][0][relation]=_reactions`
- Nested example: `GET /lists?filter[include][0][relation]=_entities&filter[include][0][scope][include][0][relation]=_reactions`
This syntax works identically on collection endpoints (e.g., `/lists`) and singular endpoints (e.g., `/lists/{id}`).

### The Problem
We have a **Domain Projection** feature in the gateway that maps domain-specific endpoints to generic ones using an alias-to-kind mapping provided by our configuration. 
For example: 
- `/bookshelves` maps to `/lists`
- `/books` maps to `/entities`
- `/book-comments` maps to `/entity-reactions`
The gateway automatically resolves the alias and injects the corresponding `_kind` query parameter for the backend.

However, the `filter[include]` statements currently remain highly technical. A client has to request `/bookshelves?filter[include][0][relation]=_entities` instead of using the domain language.

### The Feature Requirements
We need to make inclusion statements domain-aware. 

If a client calls: 
`GET /bookshelves?filter[include][0][relation]=books`

The gateway should transform this query string before routing it to the backend into:
`GET /bookshelves?filter[include][0][relation]=_entities&filter[include][0][scope][where][_kind]=book`

**Core Logic Required:**
1. Intercept the `include` statements in the incoming query string.
2. Check the `relation` value. If it is NOT a standard generic relation (like `_entities` or `_reactions` for the given context), treat it as a potential domain alias.
3. Look up this alias in our domain projection configuration.
4. If a mapping is found, replace the `relation` value with the correct generic base relation.
5. Inject a `where` filter into the inclusion `scope` to filter by the resolved `_kind`.
6. If no mapping is found, leave the relation value as it is and pass it to the downstream filters.

**Handling Existing Scope Filters:**
If the client already provides a `where` filter inside the scope, you must safely combine it with our new `_kind` filter using an `AND` operator to prevent data loss or overriding.
Example input: 
`...&filter[include][0][relation]=books&filter[include][0][scope][where][price][gt]=100`
Expected output: 
`...&filter[include][0][relation]=_entities&filter[include][0][scope][where][and][0][_kind]=book&filter[include][0][scope][where][and][1][price][gt]=100`

### Architectural Constraints & Guidelines
- **New Gateway Filter:** Create a new Spring Cloud Gateway `GatewayFilterFactory`.
- **Filter Ordering:** Do NOT implement the `Ordered` interface for this filter in the Java code. Its execution order should be strictly determined by its placement in the `application-routes.yml` file. This improves readability and declarative configuration.
- **YAML Integration:** Add this new filter to all relevant routes in `application-routes.yml` including KindAliasPath routes
- **Query Scoping Compatibility:** We already have other gateway features like "query scoping". Every incoming query is modified to ensure users cannot see records they don't have access to. These scoping filters often wrap the query string in `AND` blocks or append native query parameters. 
Your new domain-inclusion filter must be configured to run **before** the query scoping filters. It will do the first pass of query string modification. The query scoping filters will run afterward and modify your resulting query string further. Ensure your design generates a clean, standard Loopback 4 query string that the subsequent filters can easily parse and wrap without conflicts.
- **Agentic Freedom:** You are an advanced AI agent. Please thoroughly analyze the current codebase to understand our existing patterns for query string parsing, URL encoding/decoding, and domain projection configuration. Look closely at how we currently resolve aliases to kinds. Determine the most elegant, performant design that fits our architecture. If you find edge cases or need clarification on our internal API structures before proceeding, feel free to highlight them and propose a solution.