# WSDL 1.1 / XSD → OpenAPI 3.1 mapping

spec-lint turns a SOAP contract into the contract of a **JSON/REST facade**. This is what an API gateway, a backend-for-frontend or a strangler-fig migration exposes in front of the SOAP service. The rules below are implemented identically in Python, Java and Go.

## Service level

| WSDL | OpenAPI 3.1 |
|---|---|
| `service` name (single portType) or `portType` name | `info.title`, operation `tags` |
| `definitions/documentation` | `info.description` (plus a generated note) |
| (no version in WSDL) | `info.version: 1.0.0`, with a conversion note |
| `soap:address` / `soap12:address` | `servers[0].x-soap-addresses` (the facade server is `/`) |
| Ports bound to the same portType | One set of operations; a **SOAP 1.1** binding is preferred over 1.2 |
| Non-SOAP bindings (`http:binding`) | Skipped, with a note |
| No `service` (abstract WSDL) | Falls back to bindings, then to bare portTypes |

## Operation level

| WSDL | OpenAPI 3.1 |
|---|---|
| `portType/operation` `GetForecast` | `POST /get-forecast`, `operationId: getForecast`, `summary: GetForecast` |
| Name collision across portTypes | `/{port-type}/{operation}`, then `-2`, `-3`… |
| input message | `requestBody` (`application/json`, required) |
| output message | `200` response; `202` for one-way operations; `204` for an empty output |
| `fault` messages | `500` response: `SoapFault` (`faultcode`, `faultstring`, `faultactor`, `detail`), with `detail` typed from the fault message (`oneOf` for several faults) |
| `soapAction`, style, binding, port | `x-soap` extension on the operation |

### Message bodies

| Style | Mapping |
|---|---|
| document/literal, one `element` part (WS-I) | `$ref` to the element's schema |
| rpc, or several parts | A wrapper schema `<Operation>Request` / `<Operation>Response` with one required property per part |
| rpc/**encoded** | Mapped as literal, with a note (rpc/encoded is not WS-I compliant) |

## XSD → JSON Schema

| XSD | JSON Schema 2020-12 |
|---|---|
| Named `complexType` / `simpleType` | `components/schemas/<PascalCase>` (emitted only if reachable; recursive types work through `$ref`) |
| Global element with an anonymous type | Its own component |
| Global element with `type="tns:T"` | Direct `$ref` to `T` |
| `sequence` / `all` | `type: object` + `properties` + `required` (from `minOccurs`) |
| `maxOccurs > 1` / `unbounded` | `type: array` with `minItems` / `maxItems` |
| `choice` | Properties are merged; `oneOf: [{required: [a]}, {required: [b]}]` (two choices use `allOf`) |
| `complexContent/extension` | `allOf: [$ref base, {own properties}]` |
| `complexContent/restriction` | Own particles |
| `simpleContent` | Object with a `value` property plus attributes |
| `attribute` (`use="required"`) | Property (required); renamed to `@name` when it clashes with an element |
| `default` / `fixed` | `default` / `const`, typed (`"5"` on an `xs:int` becomes `5`) |
| `nillable="true"` | `type: [T, "null"]` or `anyOf: [..., {type: null}]` |
| `enumeration` | `enum` (typed values) |
| `pattern` | `pattern: ^(?:p1\|p2)$` (XSD patterns are implicitly anchored) |
| `min/maxInclusive`, `min/maxExclusive` | `minimum`/`maximum`, `exclusiveMinimum`/`exclusiveMaximum` |
| `length`, `minLength`, `maxLength` | `minLength` / `maxLength` |
| `list` | `type: array` (with a note: XML lists are space-separated) |
| `union` | `anyOf` |
| `group`, `attributeGroup` refs | Inlined |
| `soapenc:Array` + `wsdl:arrayType="xsd:string[]"` | `type: array, items: {type: string}` |
| `xs:int` / `long` / `short` / `byte`, unsigned variants | `integer` with format or range |
| `xs:decimal` / `float` / `double` | `number` (with format) |
| `xs:date` / `dateTime` / `time` / `duration` | `string` with the matching format |
| `xs:base64Binary` / `hexBinary` | `contentEncoding: base64` / hex `pattern` |
| `xs:anyType`, untyped elements, `xs:any` | `{}` (anything) |
| Annotations / documentation | `description` |

## Known limitations

Each of these is reported as a conversion note rather than silently dropped:

- `wsdl:import` of other WSDL documents (inline them first)
- SOAP headers are not mapped to header parameters yet
- Mixed content (text interleaved with elements)
- Repeating `sequence` groups are flattened
- Remote `xsd:import` is never fetched; local imports must stay inside the input's folder
- XSD regex dialect differences (for example `\i`, `\c`) are not translated
- WSDL 2.0 is not supported
