---
topic: prompting
title: Writing voicebot prompts
description: How to write an assistant or agent instruction for a voice call, with the structure and fixes each supported model family (Gemini Flash, Gemma 4, GPT-4.1-mini, Qwen 3) needs.
tools: exotel_voicebot_assistant_create, exotel_voicebot_update_config, exotel_voicebot_assistant_get_config, exotel_voicebot_catalog_llm_models
---

# Writing voicebot prompts

The prompt is the assistant's `instruction` (and each agent's `instruction`). Everything the model
writes is spoken by text-to-speech, so write for the ear. Find the model's family from its name in
`exotel_voicebot_catalog_llm_models`, then apply the general rules and the family section below.

## Rules for every model

**Speech**
1. Reply in one or two short sentences. Ask one question at a time.
2. Never output markdown, bullets, emoji, JSON or code to the caller.
3. Say numbers the way a person says them: amounts in words ("two thousand five hundred rupees"),
   phone numbers and ids digit by digit, dates as "the fifth of March".
4. Do not explain your reasoning to the caller.
5. Lock the language: "Reply in the language the caller uses. If unclear, use {default_language}."
6. The first spoken line is the bot's `greeting_message` (a bot field). Write the prompt to continue
   from it.

**Text the platform deletes**
- Angle-bracket tags are stripped on save: a name or section label wrapped in `<` and `>` vanishes.
- Write variables as `{customer_name}` and sections as markdown headings (`## Flow`). The headings
  structure the prompt for the model; the speech rules stop the model from speaking markdown.
- A bare `<` before a space or digit (`a < 5`) survives, but prefer words ("less than five").

**Tools**
- For every tool, add one line: when to call it, when not to, and what to say with the result.
  Without it, models say "one moment, let me check" and never call the tool.
- "Never invent a tool result. If the tool fails, say you could not check and offer {fallback_action}."
- "Call the tool only after the caller has given {required_value}." This also prevents lookups with
  an empty key.
- Use the tool's `name` from the agent's `mcp_tools` exactly as written.

**Latency**
- Keep prompts lean: every turn sends the whole prompt. Long prompts slow every reply.
- When the model is slow, callers say "hello?", which cancels the turn and starts it again.
  Keep `initialization_params.timeout` short and set a `fallback_llm_config`.
- On bots where the LLM hears audio directly (input modality `audio`), each turn costs two model
  calls, so latency counts twice.
- Keep `temperature` at 0.3 or lower for voice (allowed range 0.0-1.0).

## Skeleton

```
You are {bot_name}, a {role} for {company}. You speak to callers on the phone.

## Hard rules
1. Reply in at most two short sentences. Ask one question at a time.
2. Do NOT use markdown, lists or symbols. Speak numbers in words.
3. Do NOT invent order details. Only use values from the caller or from tools.

## Flow
1. Ask for the order id.
2. When you have it, call order_status.
3. Tell the caller the status and delivery date.
4. Ask if they need anything else. If not, thank them and end the call.

## Tools
- order_status: call when the caller has given an order id. Do NOT call it without one.

## Edge cases
- Caller asks something off-topic: say you can only help with orders.
- Caller wants a human: say you will transfer them.
```

## Gemini Flash

| Aspect | Guidance |
|---|---|
| Order | Identity, hard rules (MUST / MUST NOT), flow, tool rules, output format, guardrails |
| Style | Imperative present tense, one rule per numbered line, "Do NOT" rather than "avoid" |
| Repetition | State each rule once: repeated rules get over-weighted |
| Gets wrong | Over-literal reading; skips optional steps; may call tools with guessed data; long error replies; re-asks for known details |
| Fixes | "Use judgment for unclear answers"; write every step, even "skip if not applicable"; "Only call tools with data the caller confirmed"; "Keep error replies under two sentences"; "Check earlier turns before asking again" |
| Language | Detects language well; state only the fallback language |

## Gemma 4

| Aspect | Guidance |
|---|---|
| Structure | `##` sections of under ~500 tokens each; keep the whole prompt short (under ~2,000 tokens for smaller variants, ~4,000 for larger) |
| Style | Numbered steps; `condition → action` tables instead of prose; at most ~15 words per line |
| Repetition | Restate critical rules at section boundaries (unlike Gemini) |
| Examples | One or two short input → reply examples for tricky behaviour |
| Gets wrong | Loses rules in long prompts; rambles; invents entity values; tool-call format slips |
| Fixes | "Respond in 1-2 sentences maximum" in each section; "ONLY use values from the caller or tools. NEVER guess."; narrow tool schemas; add tools one at a time and retest |
| Settings | Temperature about 0.2. Expect more test iterations than Gemini |

## GPT-4.1-mini

| Aspect | Guidance |
|---|---|
| Order | Role, the three meta-rules below, hard rules, flow, tool rules, output format, edge cases |
| Meta-rules | Persistence: "Keep helping until the caller's request is resolved." Tool use: "Use tools to look things up; never guess." Planning: "Before replying, decide which step of the flow you are in." |
| Style | Very literal: write every rule and every if / else branch explicitly; precise limits ("exactly 1-2 sentences", not "be brief"); `###` separators |
| Examples | One or two, placed after the rules, labelled `### Example 1` |
| Gets wrong | Does only what is written; skips tool calls; may sound too terse; leaks format |
| Fixes | End with a catch-all: "For anything not covered, {default_behaviour}"; "When in doubt, call the tool"; "Never output JSON, markdown or code"; "Do not make up tool results" |

## Qwen 3

| Aspect | Guidance |
|---|---|
| Opening | Line 1: role ("You are {name}, a {role} for {company}"). Line 2: format lock ("Respond only in plain spoken sentences") |
| Structure | `##` sections; "Follow these steps in exact order: 1... 2... 3..." |
| Ending | Repeat the format lock at the end of the prompt |
| Gets wrong | Verbose; explains its reasoning; uses markdown; switches language unprompted |
| Fixes | Length limit in each section ("under two sentences"); "Do NOT explain your reasoning"; "NEVER use markdown"; "Respond ONLY in {language} unless the caller switches" |
| Thinking | Reasoning ("thinking") modes add latency to every turn; keep voice steps simple instead |

## Choosing a family

Qualitative guidance; measure on your own bot before switching models.

| Family | Strengths | Watch for |
|---|---|---|
| Gemini Flash | Strong instruction following, low latency, good multilingual | Over-literal; skipped implicit steps |
| Gemma 4 | Fast; good with tight structured prompts | Weaker on long or implicit instructions; needs more tuning |
| GPT-4.1-mini | Precise instruction following, strong tool use, low latency | Needs exhaustive rules |
| Qwen 3 | Strong reasoning, multilingual | Verbose; thinking adds latency |

## Before saving

- [ ] No tag-shaped text; placeholders use `{braces}`
- [ ] Speech rules present: length, one question, numbers, no markdown, language lock
- [ ] One trigger line per tool, plus "never invent tool results"
- [ ] Family-specific structure applied
- [ ] Temperature 0.3 or lower; fallback LLM set
- [ ] After saving, read back with `exotel_voicebot_assistant_get_config(assistantId, version)` and
      compare with what you sent
