# SCIM Extension Attributes (Outbound + Reconcile) — Design

**Status:** Approved (design phase)
**Date:** 2026-06-13

## Problem & goal

Today the provider pushes only SCIM **core** schema fields for users — a
hard-coded set in `UserAdapter` (userName, name, displayName, emails, active,
roles). SCIM servers commonly expect data carried in **extension schemas**:
the standard Enterprise User extension
(`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`) and/or
deployment-specific custom schemas.

The goal is to let an admin map **Keycloak user attributes → SCIM extension
attributes** so they flow to the SCIM server alongside the core schema, with
**no custom UI work** beyond Keycloak's stock component-config widgets.

## Scope

- **Resource:** Users only. The mapping/extension machinery is built
  resource-type-agnostic so `GroupAdapter` can reuse it for
  `group-extension-mappings` later without a rewrite.
- **Direction:** Outbound + reconcile. Mapped attributes are written on create
  (POST), update (PATCH/PUT), the sync-refresh re-push, and `/Bulk`. The
  sync-refresh re-push *is* the reconcile path — it re-asserts mapped values on
  every refresh, correcting drift. **No inbound import:** `apply(User)` (remote
  → Keycloak) and the deletion reconciler are untouched.
- **Schemas:** Both the standard Enterprise User extension (first-class, via the
  SDK's typed object) and arbitrary custom URN-namespaced extensions, through a
  single mapping table.
- **Typing:** Typed coercion is in scope — a per-row declared type drawn from
  `string` (default), `boolean`, `integer`, `decimal`, `dateTime`, `reference`.
  Complex types (notably Enterprise User's `manager`) and `binary` are deferred.
- **Cardinality:** Single- and multi-valued attributes are both in scope. A
  multivalued mapping reads a Keycloak multivalued attribute and emits a JSON
  array; single-valued (the default) emits a scalar.

## Why this lands in two adapter methods

Every outbound write already routes through `Adapter.toSCIM()` and
`Adapter.toPatchBuilder()`:

- `ScimClient.create` → POST with `adapter.toSCIM(false)`
- `ScimClient.replace` → PATCH via `toPatchBuilder` (or full PUT via `toSCIM`)
- `ScimClient.refreshResources` (sync-refresh) → re-push via `replace`
- `/Bulk` create path → serializes `adapter.toSCIM(false).toString()`

So wiring extensions into `toSCIM()` and `toPatchBuilder()` covers create,
update, refresh/reconcile, and bulk with no new code paths. No changes are
required in `ScimClient`, the dispatcher, or the bulk lane.

## Components

### 1. Config property

Add `user-extension-mappings` to `ScimStorageProviderFactory.configMetadata`
as `MULTIVALUED_STRING_TYPE`. Renders as a repeatable list of single-line text
inputs with add/remove controls — supported uniformly across Keycloak 25.x and
26.x. The `user-` prefix reserves namespace for a future
`group-extension-mappings`.

Help text documents the row grammar (below) with an Enterprise User example.

### 2. `ExtensionAttributeMappings` (new unit in `core`)

A resource-type-agnostic unit that:

1. **Parses** the config rows into a validated mapping table (per-row: Keycloak
   attribute name, schema URN, attribute name, declared type, cardinality).
2. **Reads** mapped values off an attribute source (a `UserModel`): single-valued
   via `getFirstAttribute(name)`; multivalued via `getAttributes().get(name)`
   (a `List<String>`).
3. **Coerces** each raw string value into its declared type (below).
4. **Attaches** the coerced values as SCIM extensions onto a target
   `ResourceNode`.
5. **Emits** PATCH REPLACE operations for the same paths.

Kept out of `UserAdapter` so `GroupAdapter` reuses it verbatim.

#### Row grammar

```
<keycloakAttrName> = <fully-qualified-scim-path> [ ; type=<t> ] [ ; multi ]
```

The two trailing modifiers are optional and order-independent. `type` defaults
to `string`; absence of `multi` means single-valued. The `;` delimiter cannot
collide with the last-colon path split.

Example rows:

```
department  = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department
employeeId  = urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber
costCenter  = urn:example:custom:2.0:User:costCenter
hireDate    = urn:example:custom:2.0:User:hireDate ; type=dateTime
active      = urn:example:custom:2.0:User:active ; type=boolean
tags        = urn:example:custom:2.0:User:tags ; multi
```

Parsing rules:

- Strip the optional `; type=…` / `; multi` modifiers first, then split the
  remaining path on the **last colon** → `(schemaUrn, attributeName)`. Safe
  because SCIM attribute names never contain `:`; nested sub-attributes use `.`
  (e.g. `manager.value`), which stays on the attribute side.
- Left-hand side is the Keycloak attribute name; surrounding whitespace trimmed.

#### Type coercion

Keycloak stores every attribute value as a string; coercion turns it into the
right JSON type for the extension node:

- `string` / `reference` → JSON string (reference passed through; it is a URI
  string on the wire).
- `boolean` → JSON boolean, parsed from `true`/`false` (case-insensitive).
- `integer` → JSON number, parsed as a long.
- `decimal` → JSON number, parsed as a `BigDecimal`.
- `dateTime` → JSON string, but validated as ISO-8601 before emission.

A value that fails to coerce is **skipped with a warning** (for a multivalued
attribute, only the offending element is dropped; the rest still emit) rather
than failing the whole user or sync. This mirrors the existing
`StaleAttributeWitness` "abstain on unparseable" posture.

### 3. Enterprise vs. custom dispatch

The single mapping table feeds both, dispatched by schema URN:

- **Enterprise URN** → build the SDK's typed `EnterpriseUser` object, populate
  it via a small hardcoded switch over its five known string setters
  (`employeeNumber`, `costCenter`, `organization`, `division`, `department`),
  and attach with `User.setEnterpriseUser(...)`. That setter is the only SDK
  method that auto-manages the `schemas` array, so the enterprise URN is added
  for us. These five fields are single-valued strings, so an enterprise mapping
  may only be `type=string` (or omit it) and may not be `multi`; an attribute
  mapped to an unknown enterprise field, a non-string type, or `multi` is
  rejected at validation time. `manager` (complex) is deferred — see Out of
  scope.
- **Any other URN** → build a Jackson `ObjectNode` (we do **not** use
  `ScimObjectNode`, whose `setAttribute` is `protected` and unreachable from
  this package). For each mapped attribute, set the coerced value:
  single-valued → `objectNode.put(attrName, <typed>)` (string/boolean/long/
  BigDecimal); multivalued → an `ArrayNode` of the coerced elements via
  `objectNode.set(attrName, arrayNode)`. Then do the schema bookkeeping
  explicitly: `user.addSchema(urn)` (public, from `AbstractSchemasHolder`)
  **and** attach the node with `JsonHelper.addAttribute(user, urn, objectNode)`
  (public static). There is no auto-adding extension mechanism for
  non-enterprise URNs in the SDK.

Both produce spec-correct wire JSON; the difference is the typed convenience
object for the enterprise case and that typed/multivalued coercion applies only
to the custom path (enterprise fields are string/single).

### 4. `UserAdapter` wiring

- `apply(UserModel)`: read the mapped Keycloak attributes (per the parsed table)
  into a held collection on the adapter — single- or multivalued per the row's
  cardinality.
- `toSCIM(addMeta)`: after building the core `User`, hand the held values +
  target user to `ExtensionAttributeMappings` to attach extensions.
- `toPatchBuilder(...)`: append a REPLACE operation per mapped extension path
  (fully-qualified, e.g.
  `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department`)
  alongside the existing core REPLACE ops.

The parsed mapping table is obtained from the component model at adapter
construction / first use (`getModel()` is a protected instance method on
`Adapter`, reachable from `apply(UserModel)`). Because `user-extension-mappings`
is multivalued, it **must** be read with
`getModel().getConfig().getList("user-extension-mappings")` —
`ComponentModel.get(String)` returns only the *first* row and would silently
drop every other mapping. An absent or empty list means "no mappings" (same as
blank config). Note `MultivaluedMap.getList` inserts an empty list for an absent
key as a side effect; harmless for this read.

### 5. Validation

Add (or extend) `validateConfiguration` in `ScimStorageProviderFactory` so
Keycloak validates mapping rows on component save. The validator and the
runtime parser share one parse routine so their notion of "valid" cannot drift.
A row is rejected when: it has no `=`; the left-hand side (Keycloak attribute
name) is empty; the right-hand side has no `:` (so the last-colon split yields
no schema portion); the schema portion is empty; the attribute segment after the
last colon is empty; or the declared `type` is not one of the supported set
(`string`, `boolean`, `integer`, `decimal`, `dateTime`, `reference`). For the
enterprise URN specifically, the attribute must be one of the five known fields,
the type must be `string` (or omitted), and `multi` is not allowed. Each failure
produces a clear per-row message. Misconfiguration fails at save time, not
silently at sync time. (Value-level coercion is *not* validated here — values
aren't known at config time; bad values are handled at runtime per Error
handling.)

## Error handling

- A Keycloak attribute named in a mapping but absent on a given user → that
  extension field is simply omitted for that user (no error).
- A value that fails to coerce to its declared type → skipped with a warning;
  for a multivalued attribute only the offending element is dropped, the rest
  emit. A skip never fails the user or the sync.
- Empty/blank `user-extension-mappings` → no extensions attached; behavior
  identical to today.
- Malformed rows are caught at save time by validation; if a malformed row ever
  reaches runtime (e.g. legacy config), it is skipped with a warning rather than
  failing the whole sync.

## Testing (TDD)

**Unit**

- `ExtensionAttributeMappings` parser: valid rows; malformed rows rejected;
  enterprise vs. custom URN split; `type=` and `multi` modifiers parsed
  (order-independent); default type `string` / single-valued; blank config →
  empty table.
- Type coercion: each supported type coerces correctly; `dateTime` validates
  ISO-8601; an uncoercible value is skipped (single) / its element dropped
  (multi) with the rest preserved.
- `UserAdapter.toSCIM()` emits correct Enterprise User extension JSON and lists
  the enterprise URN in `schemas`.
- `UserAdapter.toSCIM()` emits a custom-URN extension with correct JSON types
  (boolean/number as JSON primitives, not strings) and a multivalued attribute
  as a JSON array; lists that URN in `schemas`.
- `UserAdapter.toPatchBuilder()` includes a REPLACE op per mapped extension
  path, using the fully-qualified URN path syntax.
- `apply(UserModel)` reads single- and multivalued mapped attributes into the
  adapter.
- Factory `validateConfiguration` rejects malformed mapping rows, unknown types,
  and illegal enterprise rows (non-string type / `multi` / unknown field) —
  shares the parser with the runtime.

**Integration**

- Create a user with the mapped attributes set on the `UserModel` → assert the
  outbound POST body carries the expected extension(s) and `schemas` entries
  (both enterprise and a custom URN), with a multivalued attribute serialized as
  a JSON array and a typed attribute as the correct JSON primitive.
- Update path: assert the PATCH body carries a REPLACE op on the fully-qualified
  extension path. This branch is less-exercised in practice (`user-patchOp`
  defaults to `false`, i.e. PUT via `toSCIM`), so SDK `PatchBuilder` support for
  extension-URN paths is confirmed here rather than assumed.

## Out of scope (deferred)

- Inbound import of extension attributes (remote → Keycloak).
- Complex types — notably Enterprise User's `manager` — and `binary`.
- Group extension attributes (`group-extension-mappings`) — machinery is built
  to accept it, wiring deferred.
