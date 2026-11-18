---
topic: tools-extension
title: Tools Extension routes
description: How to build, test, version and attach a Tools Extension route, a custom HTTP flow written as Camel YAML that a bot agent can call as a tool.
tools: exotel_voicebot_te_route_list, exotel_voicebot_te_route_get, exotel_voicebot_te_route_create, exotel_voicebot_te_route_update, exotel_voicebot_te_route_delete, exotel_voicebot_te_route_test, exotel_voicebot_te_runs_get, exotel_voicebot_te_route_attach_as_tool, exotel_tools_server_tool_version_test, exotel_tools_server_tool_update, exotel_voicebot_update_config
---

# Tools Extension routes

A **route** is a small HTTP flow written as Apache Camel YAML. Once created it is served at:

```
{te_base}/api/v1/accounts/{ACCOUNT_SID}/published-routes/{path_suffix}
```

`{te_base}` is your Tools Extension base URL (`te_base_url` in the connection). A bot never calls
a route directly: the route is wrapped as a tools-server openapi tool, and the agent calls that tool.

## Route or plain openapi tool?

| Need | Use |
|---|---|
| One upstream API whose request and response already suit the model | openapi tool (`tools-server`) |
| Validate or reshape input, pick fields from a large response, return a fixed answer, branch, chain calls | Tools Extension route |

A published route must not carry customer secrets. Tools-server openapi tools support basic auth
only, so plan upstream authentication before choosing this path.

## Route model

| Field | Rule |
|---|---|
| `name` | Required, unique in the account, at most 200 characters |
| `http_method` | `GET`, `POST`, `PUT` or `DELETE` |
| `path_suffix` | Letters, digits, `/`, `_`, `-`, e.g. `orders/status`, at most 200 characters |
| `yaml` | A list with one `- route:` whose `from.uri` is `direct:placeholder`; at most 128,000 characters |

`exotel_voicebot_te_route_create` makes version 1, active and served at once. There is no publish
step and no idempotency key: list routes before retrying a failed create.

## Inputs and outputs

- The request body reaches the route as a **string**. `unmarshal` it before reading `${body[field]}`.
- Every request header and query parameter is available as `${header.NAME}`.
- The response is the route's own body, status and content type, with no envelope. Return compact
  JSON with `Content-Type: application/json`: the model reads every byte.
- Set the status with `setProperty` `CamelHttpResponseCode`. For "not found" or "missing input",
  prefer status 200 with a JSON message the model can speak: a tools-server tool test fails on any
  status of 400 or more, and only a version whose last test passed can be activated.
- Angle-bracket tags are stripped from saved YAML. Plain comparison operators (`>`, `==`) are kept.

## Step types that work

| Step | Use |
|---|---|
| `unmarshal: {json: {library: Jackson}}` / `marshal: ...` | Parse the body string / turn a value back into JSON text |
| `setProperty`, `setHeader` with `constant:` or `simple:` | Keep values; set `CamelHttpMethod`, `CamelHttpQuery`, `Content-Type` |
| `setBody` with `constant:` or `simple:` | Build the response or the outbound body |
| `choice: {when: [{simple, steps}], otherwise: {steps}}` | Branch |
| `removeHeaders: {pattern: "CamelHttp*"}` | Clear inbound HTTP headers before an outbound call |
| `to: {uri: "https://..."}` | Outbound HTTPS call |
| `log` | Write a log line into the run |

Simple expressions: `${body[a][0][b]}`, `${header.X}`, `${exchangeProperty.X}`, `${bodyAs(String)}`,
`${exchangeId}`, `${x.substring(3)}`, `${x.replace(' ', '%20')}`, with `==`, `>`, `startsWith`, `||`.

## Example A: fixed answer

```yaml
- route:
    from:
      uri: direct:placeholder
      steps:
      - setBody:
          constant: '{"open": "09:00", "close": "21:00"}'
      - setHeader:
          name: Content-Type
          constant: application/json
```

## Example B: validate input, then call an upstream API

```yaml
- route:
    from:
      uri: direct:placeholder
      steps:
      - unmarshal: {json: {library: Jackson}}
      - setProperty:
          name: order_id
          simple: ${body[order_id]}
      - choice:
          when:
          - simple: ${exchangeProperty.order_id} == null || ${exchangeProperty.order_id} == ''
            steps:
            - setBody:
                constant: '{"found": false, "message": "Ask the caller for the order id"}'
          otherwise:
            steps:
            - removeHeaders: {pattern: CamelHttp*}
            - setHeader: {name: CamelHttpMethod, constant: GET}
            - setHeader:
                name: CamelHttpQuery
                simple: id=${exchangeProperty.order_id.replace(' ', '%20')}
            - setBody: {constant: ''}
            - to:
                uri: https://api.example.com/orders
            - unmarshal: {json: {library: Jackson}}
            - setBody:
                simple: '{"found": true, "status": "${body[status]}", "eta": "${body[eta]}"}'
      - setHeader: {name: Content-Type, constant: application/json}
```

## Test a route

`exotel_voicebot_te_route_test(routeId, bodyJson, headersJson, queryParamsJson)` sends this envelope:

```json
{"body": "{\"order_id\": \"A-1001\"}", "headers": {"X-Session-Id": "test-1"}, "query_params": {}}
```

You pass `bodyJson` as a JSON object; the tool turns it into the string. Outbound calls really
happen, writes included. The result holds `route_status` and `route_output`. Test a hit, a miss, a
missing field and an upstream error.

## Run traces

`exotel_voicebot_te_runs_get`:

| Arguments | Returns |
|---|---|
| `routeId` | The route's recent runs |
| `routeId`, `runId`, `includeSteps=true` | One run and its per-step trace: find the step that failed |
| `sessionId` | All runs recorded for one call |

A run records its session id from the `X-Session-Id` header, else from a top-level `session_id`
in a JSON body.

## Versions and rollback

- `exotel_voicebot_te_route_update(routeId, yaml, changeLog, httpMethod?, pathSuffix?, confirm)`
  saves the complete new YAML as the next version, live at once for every bot tool that calls the
  route. Without `confirm=true` it returns a preview; show it to the user first.
- Changing `pathSuffix` breaks every tool built on the old URL.
- Rollback: read an older version with `exotel_voicebot_te_route_get(routeId, version=N)` (or
  `includeVersions=true` to list them), then update again with that YAML and a `changeLog`.
- After every write, read the version back and compare its YAML with what you sent.
- `exotel_voicebot_te_route_delete(routeId, confirm)` is a soft delete; every tool calling the route
  starts failing at once.

## Make it a bot tool

1. `exotel_voicebot_te_route_attach_as_tool(routeId, toolName, description, inputSchemaJson)`.
   It builds a one-operation OpenAPI 3.0 definition (server = the published-routes base, path =
   `/` + `path_suffix`; inputs become a JSON body for POST/PUT and query parameters for GET/DELETE)
   and creates the tools-server tool in the bot's account. `description` is what the model reads.
   ```json
   {"type": "object",
    "properties": {"order_id": {"type": "string", "description": "Order id the caller gives"}},
    "required": ["order_id"]}
   ```
2. The tool starts inactive. Test it with `exotel_tools_server_tool_version_test`, then
   `exotel_tools_server_tool_update(toolId, status="active", currentVersionId=VERSION_ID)`.
3. Attach `{"tool_name": "tool_server.TOOL_NAME", "name": "TOOL_NAME"}` to the agent's `mcp_tools`
   with `exotel_voicebot_update_config` (full agent list, every tool kept), and add an instruction
   line saying when to call it.
4. Test a call that needs the tool, then check `exotel_voicebot_te_runs_get(sessionId=...)`.

The tools-server stops a tool call after about 10 s, so the route must answer well within that.

## Checklist

- [ ] Body unmarshalled before `${body[...]}`
- [ ] Missing-input branch returns a clear JSON message
- [ ] Compact JSON response with `Content-Type` set
- [ ] No angle-bracket text, no secrets in the YAML
- [ ] Tested hit, miss, missing input, upstream error
- [ ] Tool tested, activated, attached, instruction line added
