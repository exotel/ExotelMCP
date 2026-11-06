---
topic: known-problems
title: Known problems
description: Symptom, cause and fix for the voicebot pitfalls customers hit most: lost edits, versions with no traffic, voice and language errors, tool failures, call-start drops, caps and paging.
tools:
---
Fixes that write config, move traffic or delete need the user's yes.

## Saving config

| Slug | Symptom | Cause | Fix |
|---|---|---|---|
| `tag-text-stripped` | A placeholder or XML-style section in angle brackets vanished after a save; double spaces remain | The API deletes tag-shaped text (`<` then a letter, `/`, `!` or `?`, up to `>`) from every non-credential string: prompts, greetings, summary prompts, webhook templates, route YAML, tool definitions | Use `{name}` and markdown headings; `a < 5` survives. Diff stored text after each write |
| `field-not-stored` | Write succeeded, field reads back empty | Unknown nested keys are dropped; an unparsable `filler_config`, `pii_masking_config` or `variants` is stored as null | Compare `get_details(botId, version)` with what you sent; missing = failed write |
| `old-source-version` | New version lost settings added later | A version is `sourceVersion` + your `dataJson` | Use `latest` unless rolling back; diff before promoting |
| `agents-full-list` | An agent or tool disappeared after an edit | `agents` is the complete list; a changed agent keeps only the `mcp_tools` sent | Send every agent with its `id` and all its tools |
| `tool-wired-as-mcp-server` | Bot's tool never runs; an assistant version lists an `mcp_servers` entry pointing at the Exotel MCP URL | The agent attached the management MCP server it was using instead of the tool. Bots cannot call this server; it needs a caller's credentials and has no call-time tools | Remove the entry (new version without `mcp_servers`), attach the tools-server tool in the agent's `mcp_tools` as `tool_server.{name}`. The write tools now reject this with `MCP_WIRING_REJECTED` |
| `assistant-shared` | Promoting one bot changed another | Assistant fields fork a new assistant version; promoting the bot makes it the assistant's stable, used by every bot on `stable` | Find bots sharing the assistant; ask before promoting |

## Versions and traffic

| Slug | Symptom | Cause | Fix |
|---|---|---|---|
| `draft-gets-no-traffic` | New version made, calls still use old config | New versions start at 0% | `bot_version_promote` or `bot_traffic_set` with `confirm=true` after a yes |
| `stable-without-traffic` | `stable_version` is new, calls still old | Marking stable moves only the pointer; calls follow `traffic_allocation` | `bot_version_promote` (sets 100%); check `traffic_allocation` in `bot_versions_list` |
| `test-call-hits-live-version` | A test call ran the old config | `place_call` follows `traffic_allocation` | Canary a small share, or promote if the bot has no real traffic yet |

## Voice, speech and language

| Slug | Symptom | Cause | Fix |
|---|---|---|---|
| `v2-voice-shape` | Voice change ignored, vendor unchanged | Flat `{model_id, voice_id}` keeps the current vendor | Send `{provider:{id}, model:{id}, voice:{id}}` |
| `voice-provider-mismatch` | "Voice X does not belong to provider Y"; a provider is missing from the list | Voice and model must belong to the sent provider; `tts_list_providers` may return only the 10 newest | Take the voice from `tts_list_voices` of that provider; compare `metadata.total` with rows |
| `vad-preset-provider` | 400 "VAD config X does not belong to provider Y" | A preset has its own provider | Send its `provider_id` from `catalog_vad_configs` |
| `vad-never-fires` | Long pause after every caller turn | Custom VAD with confidence or min volume of 1: turn end waits for the speech timeout | Use a VAD preset |
| `tts-first-language` | Bot speaks the wrong language | Some TTS models carry a default language list that overrides the bot's languages; only its first code is used | Set `voice_config.model.initialization_params.language`, e.g. `["ta"]`; check with a call |
| `language-not-spoken` | Offered language sounds wrong | Catalogue lists can differ from the vendor model's | Check the vendor's list for that model |
| `asr-language-fallback` | Caller transcripts are nonsense or English | No overlap between bot and ASR model languages falls back silently to the ASR default | Pick an ASR model listing every bot language. Audio-input bots have no ASR: empty `asr_config` is normal |
| `slow-llm-cancel-spiral` | Dozens of cancelled turns; caller keeps saying "Hello?" | LLM slower than the caller, or timing out; each utterance restarts the turn | Faster model, leaner prompt, set `fallback_llm_config` |

## Call start, webhooks and insights

| Slug | Symptom | Cause | Fix |
|---|---|---|---|
| `session-start-webhook-slow` | Calls drop before the greeting (408 or 424) | `session_start` runs inside a ~3 s start deadline: a 2xx after ~2.95 s fails (408); two non-2xx fail (424) | Keep p99 under 2.5 s with no cold starts; move slow lookups to tools |
| `mid-call-insights-need-transcript-events` | Live insights set up, none arrive mid-call | Without `webhook_config.transcript_events` they run once, at the end | Add `transcript_events`; set `conversation_assistant_id` and `insights_assistant_id`; verify with `get_details` without version |
| `summary-prompt-draft-live` | Summary prompt in a 0% draft changed live calls | It is read from the newest version | Treat the change as live; ask first |
| `post-call-fields-shift` | Outcome text in intents, summary in query resolution | Answers are filed by position; a blank question shifts the rest | Define an intent in `post_session_insights.intents` and query-resolution criteria |
| `json-summary-empty` | JSON summary empty on about half the calls | Only a summary wrapped as an escaped string is stored | One flat object, an exact escaped example, and the rule to start `{\"summary\": \"` and end `\"}` |

## Tools

| Slug | Symptom | Cause | Fix |
|---|---|---|---|
| `tool-entry-format` | Calls fail at start: "Expected format: 'server_name.tool_name'" or "'NoneType' object has no attribute 'title'" | Entry lacks `tool_server.` prefix or `name` | `{"tool_name":"tool_server.NAME","name":"NAME","description":"..."}` |
| `tool-not-found` | "Tool X not found in server tool_server" (every call dies) or "X is not a valid tool, try one of []" | Tool inactive, untested, renamed, or its table deleted | Check `tool_list`; activate a tested version; match names exactly |
| `tool-activate-needs-test` | 400 `TS10003` on activate | That version has no passing test | Test that version; send `status` with `currentVersionId` |
| `tool-name-taken` | 409 `TS10002` on create | No tool delete; names are never reused | New version of the old tool; never use real names for tests |
| `shared-resource-delete` | Other bots fail after a table, corpus or route was deleted | Account-wide; a table delete deactivates every tool on it; route changes hit every bot at once | Get a yes first. After a table delete: new table, then a new version of the old tool with its `data_table_id`, test, activate |
| `tabular-missing-filter` | Lookup returns other callers' rows | A missing filter value is dropped, so the first rows come back | Preset from an always-present key (`""` matches nothing), `limit` 1; test the missing case |
| `slow-tool-holds-turns` | Bot silent long after a tool call | A running tool holds later turns (10 s deadline) | Fast backends, short upstream timeouts |
| `kb-empty-chunks` | Knowledge tool returns empty `_kb_chunks` | Corpus not `ready`, or deleted | `corpus_get` until `ready` |
| `te-body-string` | `${body[x]}` empty in a route | Body arrives as a string | `unmarshal` first |

## Limits and reading calls

| Slug | Symptom | Cause | Fix |
|---|---|---|---|
| `bot-cap` | 429 `RESOURCE_CAP_EXCEEDED` on create | Active-bot cap (default 25); assistants have their own cap; bot delete only deactivates; assistants cannot be deleted | Deactivate unused bots after a yes, or ask Exotel support |
| `concurrency-cap` | Calls rejected at peak, mostly not shown as failures | Per-account concurrent-call cap (default 5), inbound and outbound together | Pace campaigns; support raises it |
| `rate-limit` | 429 `RATE_LIMIT_EXCEEDED` | Request rate | Stay well under 100 requests/min; wait 60 s |
| `utc-dates` | Calls from the wrong day | Dates are UTC days | Shift local days to UTC |
| `paging-duplicates` | Rows repeat or go missing | Offset paging, newest first | Past window; dedupe on `conversation_id` |
| `open-calls-missing` | Ongoing calls absent | `endDate` filters on end time | Set `endDate` later; filter `start_time` |
| `empty-transcript` | Empty transcript, real duration | Masking unfinished | Retry later |
