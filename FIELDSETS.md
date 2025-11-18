# Fieldsets Configuration Guide

## Overview

Fieldsets allow you to control which fields are included or excluded from API responses. This is useful for:
- Reducing response payload size
- Hiding sensitive or internal fields
- Providing different views of the same data for different use cases

## Features

- **Query Parameter Support**: Clients can specify fieldsets using `?fieldset=name`
 - **Disable Via Query**: Clients can disable fieldsets entirely with `?fieldsets=false`
- **Default Fieldsets**: Configure default fieldsets per resource type
- **Global & Resource-Specific**: Define fieldsets globally or per resource (entities, lists, relations, reactions)
- **Show/Hide Modes**: 
  - `show` mode: Only specified fields are included (whitelist)
  - `hide` mode: Specified fields are excluded (blacklist)
- **JSON Path Support**: Field paths can be simple or nested using JSON path notation

## Configuration Structure

### Basic Structure

```yaml
app:
  fieldsets:
    # Global fieldsets available for all resources
    global:
      <fieldset-name>:
        mode: show|hide
        fields:
          - field1
          - field2.nested
          - field3[*].arrayItem
    
    # Resource-specific configurations
    entities:
      defaultFieldset: <fieldset-name>  # Optional: default fieldset when none specified
      fieldsets:
        <fieldset-name>:
          mode: show|hide
          fields:
            - field1
            - field2
```

### Field Path Notation

Field paths support JSON path notation:

| Pattern | Description | Example |
|---------|-------------|---------|
| `field` | Top-level field | `_id`, `name`, `status` |
| `parent.child` | Nested field | `data.user.name`, `metadata.version` |
| `array[*].field` | Array element field | `items[*].price`, `users[*].email` |

## Usage Examples

### Example 1: Basic Show Mode

Show only specific fields:

```yaml
app:
  fieldsets:
    global:
      minimal:
        mode: show
        fields:
          - _id
          - _kind
          - name
          - status
```

**Request**: `GET /api/v1/entities/123?fieldset=minimal`

**Original Response**:
```json
{
  "_id": "123",
  "_kind": "user",
  "name": "John Doe",
  "status": "active",
  "_createdDateTime": "2023-01-01T00:00:00Z",
  "_ownerUsers": ["user1"],
  "email": "john@example.com",
  "metadata": {
    "tags": ["admin"],
    "preferences": {}
  }
}
```

**Filtered Response**:
```json
{
  "_id": "123",
  "_kind": "user",
  "name": "John Doe",
  "status": "active"
}
```

### Example 2: Basic Hide Mode

Hide specific fields:

```yaml
app:
  fieldsets:
    global:
      public:
        mode: hide
        fields:
          - _ownerUsers
          - _ownerGroups
          - _viewerUsers
          - _viewerGroups
          - internalId
```

**Request**: `GET /api/v1/entities/123?fieldset=public`

This will return all fields except the ones listed.

### Example 3: Nested Field Paths

Show only specific nested fields:

```yaml
app:
  fieldsets:
    entities:
      fieldsets:
        profile:
          mode: show
          fields:
            - _id
            - data.user.name
            - data.user.email
            - data.user.avatar
```

**Request**: `GET /api/v1/entities/123?fieldset=profile`

**Original Response**:
```json
{
  "_id": "123",
  "data": {
    "user": {
      "name": "John Doe",
      "email": "john@example.com",
      "avatar": "avatar.jpg",
      "password": "hashed",
      "ssn": "123-45-6789"
    },
    "settings": {
      "theme": "dark"
    }
  }
}
```

**Filtered Response**:
```json
{
  "_id": "123",
  "data": {
    "user": {
      "name": "John Doe",
      "email": "john@example.com",
      "avatar": "avatar.jpg"
    }
  }
}
```

### Example 4: Array Field Paths

Filter fields in array elements:

```yaml
app:
  fieldsets:
    entities:
      fieldsets:
        orderSummary:
          mode: show
          fields:
            - _id
            - orderId
            - items[*].name
            - items[*].quantity
            - items[*].price
```

**Request**: `GET /api/v1/entities?fieldset=orderSummary`

**Original Response**:
```json
[
  {
    "_id": "order1",
    "orderId": "ORD-001",
    "customerId": "CUST-123",
    "items": [
      {
        "name": "Product A",
        "quantity": 2,
        "price": 19.99,
        "sku": "SKU-001",
        "warehouse": "WH-1"
      },
      {
        "name": "Product B",
        "quantity": 1,
        "price": 29.99,
        "sku": "SKU-002",
        "warehouse": "WH-2"
      }
    ]
  }
]
```

**Filtered Response**:
```json
[
  {
    "_id": "order1",
    "orderId": "ORD-001",
    "items": [
      {
        "name": "Product A",
        "quantity": 2,
        "price": 19.99
      },
      {
        "name": "Product B",
        "quantity": 1,
        "price": 29.99
      }
    ]
  }
]
```

### Example 5: Resource-Specific Default Fieldsets

Configure different defaults per resource type:

```yaml
app:
  fieldsets:
    global:
      managed:
        mode: show
        fields:
          - _id
          - _kind
          - _slug
          - _createdDateTime
      
      unmanaged:
        mode: hide
        fields:
          - _slug
          - _ownerUsers
          - _ownerGroups
    
    entities:
      defaultFieldset: unmanaged  # Hide managed fields by default for entities
      fieldsets: {}
    
    lists:
      defaultFieldset: managed    # Show only managed fields by default for lists
      fieldsets: {}
```

**Request** (without fieldset parameter): `GET /api/v1/entities/123`
- Will use `unmanaged` fieldset (hides managed fields)

**Request** (without fieldset parameter): `GET /api/v1/lists/456`
- Will use `managed` fieldset (shows only managed fields)

### Example 6: Resource-Specific Overrides

Define resource-specific fieldsets that override global ones:

```yaml
app:
  fieldsets:
    global:
      summary:
        mode: show
        fields:
          - _id
          - name
    
    entities:
      fieldsets:
        summary:  # Overrides global summary for entities
          mode: show
          fields:
            - _id
            - _kind
            - name
            - description
    
    lists:
      fieldsets: {}  # Uses global summary
```

**Request**: `GET /api/v1/entities/123?fieldset=summary`
- Uses entity-specific summary (includes `_kind` and `description`)

**Request**: `GET /api/v1/lists/456?fieldset=summary`
- Uses global summary (only `_id` and `name`)

## Complete Configuration Example

Here's a comprehensive example configuration:

```yaml
app:
  fieldsets:
    # Global fieldsets
    global:
      # Show only managed/system fields
      managed:
        mode: show
        fields:
          - _id
          - _kind
          - _slug
          - _visibility
          - _version
          - _ownerUsers
          - _ownerGroups
          - _createdDateTime
          - _lastUpdatedDateTime
          - _lastUpdatedBy
          - _createdBy
          - _validFromDateTime
          - _validUntilDateTime
          - _idempotencyKey
          - _viewerUsers
          - _viewerGroups
      
      # Hide managed/system fields
      unmanaged:
        mode: hide
        fields:
          - _slug
          - _visibility
          - _version
          - _ownerUsers
          - _ownerGroups
          - _createdDateTime
          - _lastUpdatedDateTime
          - _lastUpdatedBy
          - _createdBy
          - _validFromDateTime
          - _validUntilDateTime
          - _idempotencyKey
          - _viewerUsers
          - _viewerGroups
      
      # Public-facing view
      public:
        mode: hide
        fields:
          - _ownerUsers
          - _ownerGroups
          - _viewerUsers
          - _viewerGroups
          - internalNotes
    
    # Entity-specific configuration
    entities:
      defaultFieldset: unmanaged
      fieldsets:
        minimal:
          mode: show
          fields:
            - _id
            - _kind
            - data.title
            - data.description
        
        detailed:
          mode: show
          fields:
            - _id
            - _kind
            - _createdDateTime
            - _lastUpdatedDateTime
            - data.title
            - data.description
            - data.content
            - data.author.name
            - data.author.email
            - data.tags
    
    # List-specific configuration
    lists:
      defaultFieldset: unmanaged
      fieldsets:
        compact:
          mode: show
          fields:
            - _id
            - name
            - itemCount
    
    # Relation-specific configuration
    relations:
      defaultFieldset: unmanaged
      fieldsets: {}
    
    # Reaction-specific configuration
    reactions:
      defaultFieldset: unmanaged
      fieldsets: {}
```

## API Usage

### Without Fieldset (Default)

If no fieldset is specified and a default is configured:

```
GET /api/v1/entities/123
```

Uses the default fieldset configured for `entities`.

### With Fieldset Parameter

Explicitly specify a fieldset:

```
GET /api/v1/entities/123?fieldset=minimal
GET /api/v1/entities?fieldset=public
GET /api/v1/lists/456?fieldset=compact
```

### Disable Fieldsets via Query

To bypass any default fieldset and return the full payload, set `fieldsets=false`:

```
GET /api/v1/entities/123?fieldsets=false
```

Notes:
- `fieldsets=false` takes precedence and disables defaults.
- Supported falsy values: `false`, `0`, `no`, `off` (case-insensitive).
- If both `fieldsets=false` and `fieldset=...` are provided, `fieldsets=false` wins and no fieldset is applied.

### No Fieldset Applied

If no fieldset is specified and no default is configured, the full response is returned.

## Best Practices

1. **Use Show Mode for Minimal Views**: When you want to expose only a few fields, use `show` mode. It's safer as new fields won't be accidentally exposed.

2. **Use Hide Mode for Full Views**: When you want to expose most fields except a few sensitive ones, use `hide` mode.

3. **Set Sensible Defaults**: Configure default fieldsets that make sense for typical API consumers, especially for list/collection endpoints where payload size matters.

4. **Document Your Fieldsets**: Maintain clear documentation of available fieldsets for API consumers.

5. **Consider Performance**: Fieldsets are applied after the backend response, so they don't reduce database load, only network payload.

6. **Test Array Paths**: When using array paths like `items[*].field`, test with various data structures to ensure correct filtering.

## Migration from Old Configuration

### Old Configuration Format

```yaml
app:
  fieldsets:
    managed:
      show: _id, _kind, _slug, _visibility, ...
    unmanaged:
      hide: _slug, _visibility, _version, ...
  
  defaultFieldset:
    entities: unmanaged
    lists: managed
```

### New Configuration Format

```yaml
app:
  fieldsets:
    global:
      managed:
        mode: show
        fields:
          - _id
          - _kind
          - _slug
          - _visibility
      unmanaged:
        mode: hide
        fields:
          - _slug
          - _visibility
          - _version
    
    entities:
      defaultFieldset: unmanaged
      fieldsets: {}
    
    lists:
      defaultFieldset: managed
      fieldsets: {}
```

## Troubleshooting

### Fieldset Not Applied

- Check that the fieldset name matches exactly (case-sensitive)
- Verify the fieldset is defined either globally or for the specific resource type
- Check logs for warnings about missing fieldsets

### Unexpected Fields in Response

- Verify the field paths are correct (check for typos)
- For nested fields, ensure you're using the correct path separator (`.`)
- For array fields, use `[*]` notation if you want to filter array elements

### Empty Response

- If using `show` mode, ensure you've included all necessary fields
- Check that parent paths are included when showing nested fields

## Technical Details

### Implementation Classes

- **FieldSetsConfiguration**: Configuration class that binds to `app.fieldsets` in application.yml
- **FieldsetService**: Service that applies fieldset filtering to JSON payloads
- **ApplyFieldsetConfig**: Gateway filter that integrates fieldset logic into the request pipeline

### Resolution Order

1. If `?fieldsets=false` is provided, do not apply any fieldset and return the original response.
2. If `?fieldset=name` is provided, look for the fieldset in this order:
   - Resource-specific fieldsets
   - Global fieldsets
3. If no `fieldset` parameter, use the default fieldset configured for the resource (if any)
4. If no default configured, return the original response

### JSON Path Processing

The implementation supports:
- Top-level fields
- Nested object fields using dot notation
- Array element fields using `[*]` notation
- Recursive filtering through nested structures
