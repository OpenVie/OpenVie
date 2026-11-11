# AI model routing

[Documentation index](README.md) · [BizApps](BIZAPPS.md)

This page describes the model-selection boundary that is shipped with OpenVie.
Configuration defaults and environment-variable descriptions live in the
[BizApps configuration reference](BIZAPPS.md#9-configuration).

## Shipped routing

| Workload | Model selection | Notes |
| --- | --- | --- |
| Chat and RAG answers | `LLM_MODEL_ID` (the configured 3B model) | Latency-sensitive request path. |
| BizApp intent classification | `LLM_MODEL_ID` | The per-task override is blank by default. |
| BizApp slot extraction | `LLM_MODEL_ID` | The per-task override is blank by default. |
| BizApp manifest drafting and repair | `BIZAPPS_AI_DRAFT_MODEL` | Use a drafting-specific model when configured. |
| Draft retry after validation failure | `BIZAPPS_AI_DRAFT_FALLBACK_MODEL` | Used after the primary drafting model fails validation. |

Intent and slot overrides fall back to `LLM_MODEL_ID` when unset. Drafts retain
the selected model, attempt count, fallback use, and validation result in their
metadata and audit entry. Deterministic manifest validation and normalization
remain in the path for every model.

## Evaluation protocol

Run [`scripts/ai/model_eval.py`](../scripts/ai/model_eval.py) against the
current model and candidate models before changing routing. It measures:

- exact intent accuracy;
- draft JSON parsing and schema validity;
- business checks for approvals, RBAC, notification recipients, and external
  calls;
- latency per case.

For example:

```bash
python scripts/ai/model_eval.py --models current-model,candidate-model \
  --out scripts/ai/eval-results.json
```

The harness reads the current draft system prompt from the application source,
so evaluation uses the same prompt as the running service.

## Rollout, canary, and rollback

1. Evaluate the current model and each candidate; record validity, semantic
   checks, repair attempts, and latency.
2. Set `BIZAPPS_AI_DRAFT_MODEL` and, if needed,
   `BIZAPPS_AI_DRAFT_FALLBACK_MODEL`. Chat, intent, and slot routing remain on
   `LLM_MODEL_ID` unless explicitly overridden.
3. Canary the drafting model in one environment and compare validation pass
   rate, fallback use, repair attempts, and generation latency.
4. Keep the deterministic validators and normalizers enabled throughout the
   rollout.
5. Roll back by restoring the prior drafting-model environment variables.

## Evidence (2026-11)

The following results are a November 2026 snapshot, not a routing guarantee.

| Model | Draft schema | Semantic checks (raw) | Average draft latency |
| --- | --- | --- | --- |
| `vylinh` (3B) | 3/3 | 5/13 | 15.3 s |
| `qwen2.5:7b-instruct` | 3/3 | 8/13 | 117.4 s |

The `vylinh` baseline recorded 5/7 intent cases, including misses for a pain
narrative and a signature-request prompt. The 3B model also missed manager-email
wiring and `external_call` details in some drafts. The saved 7B drafts improved
semantic coverage but still relied on deterministic normalization for approval
hooks, notification recipients, and schedules.

The reference machine was a 4 GB GTX 1650 Ti: a 3B Q4 model fit on the GPU,
while a 7B Q4 model partly used CPU. Measure on the target deployment before
selecting a drafting model.
