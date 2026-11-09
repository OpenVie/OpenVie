# AI model routing: chat vs. manifest drafting

Status: implemented (per-task config, fallback model, AI-service model override).
The eval harness under `scripts/ai/` remains the gate for choosing models.

## Current state

- The Python AI service generates chat/RAG answers with `LLM_MODEL_ID`
  (default `vylinh`, a 3B Qwen2 model served by Ollama).
- The Java API no longer hardcodes the model in BizApp calls: intent, slot
  extraction and manifest drafting read
  `app.bizapps.ai.intent-model`, `app.bizapps.ai.slot-model`,
  `app.bizapps.ai.draft-model` and `app.bizapps.ai.draft-fallback-model`.
  Blank values mean "use the AI service's `LLM_MODEL_ID`", so existing
  installations keep their behavior until an operator opts into a per-task
  model.
- `CompleteJson` in the AI service now forwards the request `model_id` to the
  Ollama payload (it used to be ignored), which makes per-call models real.

## Observed failure modes (vylinh, 2026-11)

| Task | Result |
|---|---|
| Plain “tạo giúp tôi ứng dụng mua sắm…” draft | Valid after the deterministic hook/UI clamps |
| Pain narrative (“công ty tôi mua sắm lộn xộn…”) intent | `APP_RECORD_CREATE`, not `CREATE_APP` (rule issue; router fallback now covers it) |
| “Email cho quản lý người tạo chọn” draft | No `managerEmail` field; invented recipients (`requestor`, `record.email`) → invalid draft |
| Approval block | Emitted, but often unhooked and with `candidateRole` joined by `|` (clamped since) |

These are a mix of **rule/architecture** defects and **model capability**
defects. A stronger drafting model addresses the latter; validators and
clamps remain mandatory.

## Target routing

| Task | Model | Why |
|---|---|---|
| Chat/RAG answers | 3B (`vylinh` or successor) | High volume, latency-sensitive |
| Slot extraction | 3B | Narrow schema, low token count |
| Intent classification | 3B + deterministic gate/fallback | Rules cover ambiguous pain narratives |
| Manifest generation + repair | Stronger instruct model | Low volume, high value, complex schema |
| Manifest repair after validation failure | Stronger model first, then current model as fallback | Small models repeat the same error |

Configuration:

```yaml
app:
  bizapps:
    ai:
      intent-model: ""            # blank = AI service LLM_MODEL_ID
      slot-model: ""
      draft-model: qwen2.5:7b-instruct
      draft-fallback-model: vylinh
```

The Python service keeps `LLM_MODEL_ID` for chat/RAG. The Java call sites read
these properties through `@Value` fields; the gRPC request carries the model id
as it already does. A blank value is sent as an empty id and the AI service
falls back to its configured default.

## Draft flow (implemented)

1. Try `draft-model` up to the existing 3-attempt repair loop.
2. If the result is still invalid, retry once with `draft-fallback-model` and
   the accumulated validation errors (skipped when both values match/blank).
3. Persist `model`, `attempts`, `fallbackUsed`, `validation.ok` and `errors`
   in draft meta and the generated audit entry.
4. Keep all existing deterministic normalizers (approval BPMN/role, hook
   repair, UI view repair).
5. If the strong model makes synchronous generation too slow, move generation
   to a background job; the drafts list already supports async completion.

## Baseline (vylinh, 2026-11-07)

`python scripts/ai/model_eval.py --models vylinh`:

- intent: **5/7** (pain narrative → `APP_RECORD_SUBMIT`, signature request → `APP_RECORD_UPDATE`);
- draft JSON/schema: **3/3** — the model can emit structurally valid manifests;
- draft semantic checks: **5/13** — procurement approval 3/3, manager email 2/5, IoT external_call 0/5;
- average draft latency: **15.3s**.

Interpretation: JSON validity is not the bottleneck; semantic completeness is. The
3B model omits `external_call` and the manager-email wiring. The router fallback
now treats `APP_RECORD_SUBMIT` with no matching app the same as
`APP_RECORD_CREATE`, so the pain narrative still reaches app creation.

## Candidate comparison (raw model output)

| Model | Draft schema | Semantic checks (raw) | Avg draft latency |
|---|---|---|---|
| `vylinh` (3B) | 3/3 | 5/13 | 15.3s |
| `qwen2.5:7b-instruct` | 3/3 | 8/13 | 117.4s |

Raw 7B output still omits some hooks/schedules, but the deterministic
normalizers close the gap: re-running the saved 7B manifests through
`normalizeGeneratedManifest` + `BizAppManifestValidator` produced:

- procurement approval: approval block auto-hooked → valid, member create submits;
- manager email: `manager_email` + notify auto-hooked on `onCreate` → valid, email sends;
- IoT: `external_call` present with credential/sink, schedule auto-added as
  `PT5M` from “cứ 5 phút” → valid, polling runs.

The 7B is therefore a good default for drafting **with** the clamps in place;
chat/RAG stays on the 3B.

## Evaluation protocol

`scripts/ai/model_eval.py` runs a golden prompt set through any Ollama model
and reports:

- intent accuracy (exact expected intent),
- draft JSON parse + JSON-schema validity,
- business checks (approval block/hook, `rbac.approve`, manager email wiring,
  external-call URL/credential/sink/schedule),
- latency per case.

Baseline command:

```bash
python scripts/ai/model_eval.py --models vylinh,qwen2.5:3b --out scripts/ai/eval-results.json
```

The harness reads the **current production draft system prompt** directly from
`BizAppDraftService.java`, so prompt drift fails loudly instead of silently
evaluating an outdated prompt. Intent cases use the classifier's documented
contract.

## Rollout

1. Run the eval on the current model and 1–2 candidates; record pass rate and
   latency. Do not switch on a single good sample.
2. Set `BIZAPPS_AI_DRAFT_MODEL` (and optionally `BIZAPPS_AI_DRAFT_FALLBACK_MODEL`)
   in the environment; chat/RAG stays on `LLM_MODEL_ID`.
3. Canary the draft model on one environment; compare draft validation pass
   rate, repair attempts, and generation latency.
4. Keep the fallback and clamps; add notify-recipient normalization only if the
   eval shows a recurring, safely repairable pattern.
5. Roll back by changing one config value.

## Hardware note

The reference machine has a 4 GB GTX 1650 Ti. A 3B Q4 model fits; a 7B Q4
partially offloads to CPU. Since manifest generation is low-volume, CPU-assisted
7B is acceptable if latency stays within the draft UX budget; measure before
committing.
