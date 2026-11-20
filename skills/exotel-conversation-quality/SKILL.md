---
name: exotel-conversation-quality
description: Score call and chat quality with Exotel Conversational Intelligence (CQA) - set up quality profiles, ingest recordings or transcripts, and read the AI scoring with justifications. Use when the user wants to audit calls, score agents, check compliance, or analyse conversations with Exotel.
---

# Exotel Conversational Intelligence

Needs the CQA fields (`cqa_api_key`, `cqa_account_id`, `cqa_host`) in the Exotel MCP credentials.
If CQA tools return a credential error, follow the `exotel-setup` skill.

## 1. Quality profile (what to score)

- `exotel_cqa_list_quality_profiles` to see existing ones; `exotel_cqa_get_quality_profile` to read one.
- New profile: `exotel_cqa_create_quality_profile`; copy-and-edit: `exotel_cqa_duplicate_quality_profile`
  then `exotel_cqa_update_quality_profile`. Draft the categories and KPIs with the user first
  (for example greeting, verification, resolution, compliance phrases, tone).
- Routing interactions to a profile: `exotel_cqa_create_assignment_rule`, `exotel_cqa_list_assignment_rules`.
- Extra fields on interactions (agent, team, campaign): `exotel_cqa_configure_metadata`.

## 2. Ingest

| Source | Tool |
|--------|------|
| One call: public HTTPS audio URL, transcript URL, or inline transcript text | `exotel_cqa_ingest_interaction` |
| Many calls | `exotel_cqa_ingest_batch` |
| A file | `exotel_cqa_ingest_file` |

Keep the returned interaction ID or job ID. Audio must be reachable over HTTPS (presigned URLs work).
Do not upload recordings the user has not confirmed they may process.

## 3. Results

1. `exotel_cqa_track_job` (batch/file) or `exotel_cqa_get_interaction` until processing finishes.
2. From the interaction's `analyses` array take the `analysis_id` → `exotel_cqa_get_analysis`.
3. Report scores by category, the lowest KPIs with the AI's justification quoted, and patterns across
   calls when several were ingested (`exotel_cqa_list_analyses`).
