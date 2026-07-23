# Apalchi Backend — Codebase Walkthrough

> **Snapshot: commit `b206a82`, 2026-07-23. Not reviewed by a human.**
> This is a point-in-time reading guide, not maintained documentation. Line numbers and file
> sizes drift within days. Before trusting any specific claim, diff against HEAD:
> `git log --oneline b206a82..HEAD -- src/main/java` — if that returns anything substantial,
> treat this as history and verify against the source. A walkthrough goes stale silently and
> then misleads confidently; the code is the truth.

A reading guide for the whole backend, with the AI pipelines covered in depth.
Every section names the exact files so you can open them in order.

- **Scale:** 659 Java files / ~59k lines in `src/main/java`, 279 test files, 122 Flyway migrations.
- **Stack:** Spring Boot 3.3.4, Java 21 (virtual threads ON), PostgreSQL + Flyway, Gradle KTS, Railway.
- **Layering:** `api` (web) → `domain` (logic + ports) → `infrastructure` (adapters). Domain never
  imports `infrastructure`/`api`/`jakarta.persistence` (enforced by `DomainLayeringGuardTest`).
- **Models:** Claude Haiku 4.5 (chat, OCR, micro-calls), Claude Sonnet 4.5 (Max/Family complex chat only),
  Gemini 2.5 Flash / Flash-Lite (wiki compile, classify, module content, summaries).

---

## 0. Suggested reading order

If you read nothing else, read these eight files in this order — they are the spine:

1. `domain/knowledge/usecase/UploadFileUseCase.java` — ingestion + all the gates
2. `domain/knowledge/usecase/CompileWikiUseCase.java` — compile orchestration, batching, windowing
3. `infrastructure/ai/GeminiWikiCompiler.java` — the 3-tier compiler with Haiku fallback
4. `domain/knowledge/usecase/WikiPagePersistenceService.java` — persist, conflicts, dedup, fan-out
5. `infrastructure/ai/WikiRecompileScheduler.java` — debounce, in-flight gate, zero-ready signals
6. `infrastructure/ai/ClaudeContextAssembler.java` — the chat prompt (cache blocks 1–4)
7. `domain/chat/usecase/SendMessageUseCase.java` — chat turn orchestration + gates
8. `domain/module/ModuleContentGenerator.java` — LEARN/TEST/PROVE content generation

Then `infrastructure/ai/ClaudeApiClient.java` (the low-level transport) and
`domain/knowledge/groundedness/GroundednessVerifier.java` (the anti-hallucination gate).

---

## 1. Repository map

```
src/main/java/com/pally/
├── api/                    # thin @RestControllers, one package per feature
├── domain/                 # business logic, ports, use cases (no Spring web, no JPA)
│   ├── knowledge/          # uploads, wiki pages, compile, groundedness  ← biggest AI surface
│   ├── module/             # LEARN → TEST → PROVE state machine
│   ├── chat/               # chat, Socratic, hint trees, session summaries
│   ├── quiz/ progress/ weakness/ marking/ homework/ centre/ …
│   ├── cost/               # AI spend ledger (AiUsageMeter)
│   └── consent/ subscription/ avatar/ auth/ …
├── infrastructure/
│   ├── ai/                 # 28 files — ALL model adapters + prompts   ← the AI layer
│   ├── ocr/                # 7 files — Claude/Gemini vision + PDF text
│   ├── persistence/        # JPA entities + repository adapters (per aggregate)
│   ├── config/ auth/ storage/ stripe/ push/ tts/ email/ ratelimit/ observability/
└── shared/                 # ApiResponse, exceptions, JsonExtraction, PallyTime (SGT), utils
src/main/resources/
├── application.yml         # ALL tunables — read this early
└── db/migration/           # V1..V122
```

Docs already in the repo worth reading alongside this:
- `CLAUDE.md` — the rules file (workflow, architecture, error contract, deploy checklist)
- `DEFERRED.md` (42KB) — consciously-deferred gaps; check before assuming a hole is unowned
- `WIKI_ARCHITECTURE_NOTES.md` — wiki indexing/scalability assessment
- `tools/eval/` + `scripts/*probe*.sh` — offline eval + quality probes

---

## 2. The AI layer at a glance

`infrastructure/ai/` (28 files, 6.5k lines). Grouped by role:

**Transport / routing**
| File | Role |
|---|---|
| `ClaudeApiClient` (803) | Anthropic Messages API: unary, fast-unary, tool-use loop, vision+tools, SSE streaming with prompt caching |
| `GeminiCompletionService` (182) | Gemini non-streaming completion for classify/summarise/generate; Haiku fallback |
| `ModelRouter` (162) | Single place model IDs are chosen; centre-guard forces Haiku |
| `GeminiThinkingBudgetConfig` (61) | Per-purpose Gemini `thinkingBudget` (cost lever) |

**Content generation**
| File | Role |
|---|---|
| `GeminiWikiCompiler` (658) `@Primary` | Tier1 Flash → Tier2 Flash-Lite → Tier3 Haiku-chunked |
| `ClaudeWikiCompiler` (446) | The Haiku fallback compiler; map-reduce chunking + truncation recovery |
| `WikiCompilerPrompts` (118) | Shared marking/weakness prompt headers (anti-drift) |
| `ClaudeQuizGenerator` (362) | MCQ generation + calculator verification |
| `ClaudeFlashcardGenerator` (171) | SM-2 flashcards per wiki page |
| `ClaudeSyllabusParser` (104) + `SyllabusParserAdapter` | Syllabus text → curriculum topics |
| `ClaudeHomeworkFeedbackGenerator` (80) | Teacher-only DRAFT homework feedback |

**Chat**
| File | Role |
|---|---|
| `ClaudeContextAssembler` (925) | Builds the 4-block cached system prompt + tiered trace |
| `ClaudeChatProxy` (215) | `ChatPort` impl: SSE parse, cache metrics, SOURCE-slug extraction, Sonnet→Haiku retry |
| `TopicRouter` (95) | Gemini call: message + wiki index → up to 5 relevant slugs |
| `CacheKeepAliveService` (142) / `CacheInvalidationService` (42) / `CacheMetrics` (38) | Prompt-cache TTL management + accounting |

**Verification / safety / deterministic tools**
| File | Role |
|---|---|
| `ModerationService` (189) | Input + post-hoc output screening (fail-safe on HIGH) |
| `SafetyAlertService` (94) | Email alert on HIGH severity / 3 flags in 24h |
| `ClaudeEntailmentJudge` (127) | Batched, citation-forced entailment (fails **closed**) |
| `ClaudeRelevanceChecker` (143) | Is this upload study material / on-topic? |
| `ClaudeTeachEvaluator` (149) | Feynman "Teach Mochi" grading |
| `CalculatorTool` (178) | exp4j sandboxed arithmetic — never an LLM |
| `AlgebraTool` (214) | Derivatives, quadratic roots, vector magnitude — deterministic |
| `ClaudeTool` (30) | Tool interface for the Anthropic tool-use loop |

**Orchestration**
| File | Role |
|---|---|
| `WikiRecompileScheduler` (472) | Debounce, per-avatar in-flight gate, daily compile cap, startup reconciler |

---

## 3. Pipeline A — Upload → OCR → Wiki compile ("the brain")

This is the core value loop and by far the most defended code in the repo.

### 3.1 Ingestion — `domain/knowledge/usecase/UploadFileUseCase.java`

Order of operations (`execute`, line 143). Each step is a gate that can end the request:

1. **Consent** — `consentGuard.requireChildDataIngressConsent(userId)` then `requireAiConsent(userId)`.
   Default-deny; blocks under-13 pending accounts *before any model call or DB write*.
2. **Slot guard** — `avatarSlotGuard.requireActive` (locked avatars can't receive knowledge).
3. **Upload quota** — `uploadQuotaGuard.requireUploadQuota` → 402 UPGRADE_REQUIRED (FREE cap = 5/30d,
   `subscription.free.upload-cap`).
4. **MIME allowlist** (line 55) — PDF, text/markdown, JPEG/PNG/WEBP/HEIC only.
5. **Read bytes once** (line 181) — `file.getBytes()`, because storage would otherwise drain the
   multipart stream and extraction would get EOF (documented past bug).
6. **Store to R2/S3**, then create `KnowledgeFile` in `PROCESSING`.
7. **Extract text** — PDF via `PdfTextExtractor`; TEXT decoded directly (no LLM); PHOTO via `OcrPort`.
8. **Empty-text guard** (line 245) — fail explicitly with a cause-specific message; never compile empty.
9. **Pathological-size reject** (line 265) — `compile.upload-reject-chars` = 5,000,000 (~2500 pages).
   Explicitly a *safety* bound, not a quality ceiling.
10. **OCR quality gate** (photos only, line 279) — `OcrQualityGate` returns GOOD/BORDERLINE/REJECTED
    plus cleaned text.
11. **Dedup** — `ContentDeduplicator.check`: SHA-256 exact hash, then Jaccard ≥0.75. Only compares
    against *present* statuses (READY/PROCESSING/SEGMENTED/PENDING_CHUNK) so a re-upload after a
    FAILED/IRRELEVANT rejection is allowed.
12. **Relevance** (line 312) — `relevancePort.check(subject, existingTitles, sample)`.
    Read `shouldRejectRelevance` (line 457): non-study-material (receipt/selfie) is rejected on
    *every* subject; off-topic score only bites on topically-bounded subjects, and *not* for STEM
    photos (OCR garbles maths, so the numeric score is untrustworthy). Failure of the check itself
    is treated as relevant (fail-open) — a Claude outage must not block a valid upload.
13. **Chapter segmentation** (line 360) — if text > `compile.segment-trigger-chars` (50k),
    `DocumentSegmentationService.segment` produces ≥2 segments: parent → `SEGMENTED`, children →
    `PENDING_CHUNK`. **Nothing compiles.** No recompile is scheduled — an unpicked chunk costs
    nothing. Children intentionally bypass the dedup gate (siblings are legitimately similar).
14. Otherwise → `markReady(pageCount)` and `recompileScheduler.requestRecompile(avatarId)`.

Returns immediately (`UploadResult.Success` / `.Segmented` / `.RelevanceWarning` / `.Failure`);
compile runs in the background so the HTTP response stays well under Railway's 60s proxy timeout.

### 3.2 OCR — `infrastructure/ocr/`

`ResilientOcrService` (`@Primary`, the `OcrPort` all callers get):

```
Claude Vision → (insufficient/failed) → Gemini Vision → (failed) → throw OcrUnavailableException
```

Quality guard: an image >10KB must yield ≥10 chars, else the next engine is tried. **No silent
empty-string fallback.** `getLastResult()` (ThreadLocal) records which engine served, for the
`ocrEngine` column. `PdfTextExtractor` handles text-layer PDFs; `DocumentTextExtractor` is the
generic entry for homework/marking artifacts.

### 3.3 Segmentation — `domain/knowledge/usecase/DocumentSegmentationService.java`

Three-strategy ladder, degrades silently and never throws:
1. **PDF bookmarks** (page-accurate outline)
2. **LLM titles** — one metered Gemini `segment` call (thinkingBudget=0) over a page-marked sample
3. **Uniform ranges** — fixed page/char windows titled "Pages X–Y"

Every segment is clamped to a max page count, so a fat chapter becomes "Ch 3 (part 2/3)".

### 3.4 Compile orchestration — `domain/knowledge/usecase/CompileWikiUseCase.java`

`execute(avatarId)` (line 160):

1. **Stale-page archive** — pages not retrieved in 60 days (best-effort).
2. **Inventory** (line 102) — counts *every* `KnowledgeFile.Status` + `totalChars`. This exists
   because of the "156-page-PDF incident": a file parked in a state nothing counted made the brain
   flip to a silent READY-empty.
3. **Zero-READY branch** (line 198) — the most important defensive block in the file:
   - `allFiles.isEmpty()` → intended empty brain; archive orphan pages.
   - else **never archive**, and return a state-aware signal in `tierServed`:
     `zero-ready-processing` | `zero-ready-awaiting-selection` | `zero-ready-failed`.
4. **Incremental filter** — "needs compile" = `compiled_by IS NULL` (a true completion marker, not
   merely "has a `wiki_page_sources` row", so a partially-failed segmented file is resumed).
   Nothing outstanding → early return with `tierServed = "skipped-all-compiled"`.
5. **Zero-source guard** — all-empty text → skip (don't burn budget on every deploy's reconcile).
6. **Delete-during-compile guard** (line 301) — re-check the avatar exists right before the spend.
7. `wikiCompiler.compileWithTier(...)` → `persistenceService.persistDrafts(...)`.
8. **Orphan archive** — surviving slugs = this run's slugs ∪ slugs owned by previously-compiled
   READY files (fixing a bug where incremental compiles archived everything else).
9. **Cache invalidation** — `cacheInvalidationService.onWikiContentChanged`, outside the persist
   transaction (best-effort cache work never blocks or rolls back a compile).
10. **`compiledBy` stamped only if pages were produced** — a 0-page result must stay re-compilable.

Two extra entry points:
- `executeBounded` — runs on the bounded AI pool with a 4-minute wait; `RejectedExecutionException`
  → 503, timeout → 504.
- `executeAsync` / `executeBatched` (line 448) — for big corpora. `segmentOversizedLegacyFiles`
  retroactively splits pre-chunking oversized files (money-leak fix); `windowOversizedFiles`
  (overlap 800 chars, `TextWindower`) makes transient "part k/N" variants that are fed to the
  compiler but **never persisted back**; `splitIntoBatches` bounds each call by `compile.max-sync-chars`.
  Per-batch persist means batch 3 failing doesn't lose batches 1–2; failed/zero-page fileIds are
  tracked so `compiled_by` is only stamped on fully-successful files.

### 3.5 The compiler — `infrastructure/ai/GeminiWikiCompiler.java` (`@Primary`)

```
Tier 1: gemini-2.5-flash        (primary)
Tier 2: gemini-2.5-flash-lite   (secondary)
Tier 3: ClaudeWikiCompiler      (Haiku, chunked — ~10× cost, logged loudly)
```

- Falls to Haiku on: missing key, active 429 cooldown (5 min), 0 pages, or exception.
- **Quota tracking** — daily counter reset at midnight Asia/Singapore, warn at 1200 (free tier 1500).
- **Internal chunking** — >30k chars → 25k-char chunks split at `\n## ` / paragraph boundaries,
  merged by slug (longest content wins).
- **STEM images** — for MATHS/SCIENCE/CODING avatars, up to 10 original photos are attached as
  `inlineData` so the model sees equations/diagrams, not just OCR text.
- **Cost metering** (line 481) — `usageMetadata` is extracted from the same response body;
  an *empty* response is recorded as `success=false` with the `finishReason` in the label
  (`wiki-compile:EMPTY:MAX_TOKENS` vs `:SAFETY`) — billed-but-failed spend stays visible.
- **Prompt heads** — `notesPromptHeader` / `markingPromptHeader` / `weaknessPromptHeader`.
  The marking + weakness heads live in `WikiCompilerPrompts` so they can't drift between the
  Gemini and Claude compilers (a documented past bug).
- Output contract: JSON array of `{slug, title, content, context, prerequisites[]}`.

`ClaudeWikiCompiler` (the Tier-3 fallback) is worth reading for its own reasons: heading-aware
chunking (`splitByHeadings`), 200-char overlap windows, `MAX_CHUNKS_PER_FILE=15`, and
`recoverPartialDrafts` — a backward scan for the last complete `}` so a response truncated at
`max_tokens` still yields the pages that finished.

### 3.6 Persistence — `domain/knowledge/usecase/WikiPagePersistenceService.java`

The AI call is deliberately *outside* any transaction; this class does only fast DB work.

- **Per-page `REQUIRES_NEW` transaction** (`writeSingleDraft`, line 200) — one bad page (e.g. a value
  too long for a column) can't poison the batch. Failures are collected as `FailedPage(slug, reason)`.
- **Unique-violation = success** (`isUniqueViolation`, line 320) — SQLState 23505 means the page is
  already there (race or re-run), not a failure.
- **Slug clamp** to 160 code points, surrogate-safe (`TextClamp`).
- **Conflict detection** (`detectConflict`, line 387), two-stage:
  1. `detectFactConflict` — deterministic fact diff. Builds a map of *context → value* for every
     number/proper-noun and reports a clash ("produces atp: 38 vs 36"). This runs **first**, at any
     overlap level, precisely because Haiku once rationalised a 36-vs-38 ATP contradiction away.
  2. Jaccard bands: ≥0.70 → no conflict; <0.40 → conflict; the 0.40–0.70 gray band → one Haiku
     yes/no call (`haikuContradicts`). Failure of that call defaults to no-conflict.
  A teacher-RESOLVED page is **locked**: a recompile that would change it opens a new conflict for
  the teacher instead of overwriting (`wikiConflictService.isResolvedLocked`).
- **Content-change gate** (line 246) — `normalizedEquals(old, new)`. Hint-tree + flashcard regen
  only fire when content actually changed (that fan-out was being re-billed on every persist, and
  flashcard regen wipes SM-2 state).
- **Quality verification** — `WikiQualityVerifier` (heuristics, no LLM) writes a 0–100 score that
  drives the client's LOW_CONFIDENCE band.
- **Dedup pass** (`deduplicatePages`, line 577) — O(n²) Jaccard over active pages; slug-token ≥0.8 or
  content ≥0.75. Human-verified pages always win; two verified near-duplicates are never auto-deleted.
- **Fan-out** — provenance rows (`wiki_page_sources`), then `queueModuleGeneration` per new slug.

### 3.7 Scheduling — `infrastructure/ai/WikiRecompileScheduler.java`

- **After-commit trigger** (line 126) — if a transaction is active, the recompile is deferred to
  `afterCommit`. Compiles read committed state; firing inside the transaction raced the write and
  read a just-picked chunk as still `PENDING_CHUNK` → empty brain.
- **First-upload-immediate**, otherwise debounce (`WIKI_DEBOUNCE_MS` 8s, `WIKI_MAXWAIT_MS` 60s).
- **Per-avatar in-flight set** + `dirtyAgain` follow-up. The explicit `POST /wiki/compile` path
  shares the same gate via `tryBeginExternalCompile` / `endExternalCompile`.
- **Daily budget** — warn at 20 compiles/avatar/day, hard cap 50.
- **`handleZeroReadySignal`** (line 318) — the honesty layer:
  - `zero-ready-processing` → re-run the whole compile on 1→2→5→5→5→5→5 minute backoff (~28 min),
    then give up **honestly** (brain stays non-READY).
  - `zero-ready-awaiting-selection` → no retry; waiting on the user to pick chapters.
  - `zero-ready-failed` → mark READY only if real pages already exist; otherwise stay non-READY.
  The rule throughout: **never present a READY-empty brain.**
- **Startup reconciler** (`@EventListener(ApplicationReadyEvent)`) — resumes avatars stuck in
  COMPILING from a crash/redeploy (the compile is idempotent), then enqueues avatars whose files are
  newer than their pages, staggered 500ms apart.
- Status is published both in-memory (`CompileJobStore`) and durably
  (`DurableCompileStatusStore`) so `GET /wiki/compile/status` is correct on any replica.

---

## 4. Pipeline B — Chat

### 4.1 Prompt assembly — `infrastructure/ai/ClaudeContextAssembler.java`

Two parallel representations:
- **Tiers 1–4** (`assemble`, line 119) → a flat string used only for the `harnessTrace` diagnostic.
- **Cache blocks** (`buildCacheBlocks`, line 200) → what the model actually sees.

**Block order is sacred** (prefix caching invalidates from the first changed block onward):

| Block | Content | Cached |
|---|---|---|
| 1 | Hard rules (subject boundary, honesty + general-knowledge disclaimer, `SOURCE:` citation, child safety, language) | yes* |
| 2 | Avatar config (name, subject, grade, curriculum, Socratic vs Direct, study-goal method rules, teacher instructions) | yes* |
| 3 | Knowledge base — every ACTIVE wiki page, with `[certainty]` + HIGH/MEDIUM/LOW confidence band and its `context` line | `cache_control` breakpoint here |
| 3.5 | Rolling session memory | no |
| 3.6 | Weakness focus (flag-gated, ≤3 pages × 400 chars) | no |
| 4 | Dynamic tail — routed page highlights, deleted-topic honesty note, verified calculations, Socratic-unlock note | never |

\* The single `cache_control` breakpoint is placed on Block 3 and **only when blocks 1+2+3 ≥ 2048
estimated tokens** (`CACHE_MIN_TOKENS`) — below that, Anthropic silently ignores it. The
`anthropic-beta: prompt-caching-2024-07-31` header is set in `ClaudeApiClient` (missing it was the
documented "$1.50/day cost bug").

Notable details:
- **Corpus indirection** — centre avatars read `avatar.getCorpusAvatarId()`'s wiki (the shared class
  corpus); session memory and history always stay on the student's own avatar id.
- **Retrieval** — `TopicRouter` (Gemini) picks ≤5 slugs → `findByKeywords` → up to 3 prerequisite
  pages → `recordRetrieval` (feeds the staleness archiver).
- **Deterministic maths injection** (`injectArithmeticVerification`, line 711) — regex-detects
  arithmetic, quadratics, derivatives, vector magnitudes; computes them with `CalculatorTool` /
  `AlgebraTool` and injects the verified result so the model doesn't guess.
- **Socratic unlock** (`isFrustrationTriggered`, line 678) — ≥4 user turns + a frustration phrase in
  the last two → append a note permitting a direct answer.
- **`safeFormat`** (line 345) — a bare `%` in prompt prose (e.g. "100% certain") used to throw
  `IllegalFormatException` and kill the whole turn before the call was made.

### 4.2 Turn orchestration — `domain/chat/usecase/SendMessageUseCase.java`

Gates, in order (`executeStream`, line 141):

1. Slot guard → child-data ingress consent → AI-transfer consent
2. Tier resolution (`PremiumService.TierContext`: tier + `isCentreSourced`)
3. Avatar lock check
4. **Module-context gate** (line 178) — chatting inside a module: keyword relevance vs that
   module's page < `centre.closedbook.relevance-threshold` (0.55) → deterministic refusal, and a
   `content_gap_signal` is recorded for centre classes.
5. **Centre closed-book gate** (line 233) — for centre avatars, relevance against the whole class
   corpus. **No LLM call at all** for off-corpus turns.
6. **Moderation** — `moderationService.screenInput`; HIGH severity → caring redirect, stream ends.
7. Persist the user message (skipped entirely for PENDING-consent accounts — ephemeral chat).
8. Assemble context; load ≤20 history messages.
9. Optional pre-stream work (hint trees, topic classify, session/attempt state) wrapped in
   try/catch — **must never abort the turn**.
10. `SocraticPromptBuilder.buildBlock4` replaces the last block; centre avatars get an extra
    closed-book Block 0 prepended.
11. Stream via `chatProxy.streamChat(...)` with the model from `ModelRouter.forChat(msg, tier, isCentreSourced)`.

On completion: strip the `SOURCE:` trailer, persist the reply, run **post-hoc output moderation**
off the reactor thread (`CompletableFuture.runAsync`), save `model_used` + cache metrics, and update
the rolling summary **every 3rd turn** (it used to run every turn — a pure cost leak).

### 4.3 Transport — `ClaudeChatProxy` + `ClaudeApiClient`

`ClaudeChatProxy`:
- `buildMessages` collapses consecutive same-role turns (Anthropic requires strict alternation) and
  guarantees the last turn is `user`.
- Parses SSE: `content_block_delta` → Token, `message_stop` → Done(slug), `error` → Error.
- `CacheMetrics.fromUsageJson` from the final usage block; effective input for the cost ledger folds
  in cache pricing (write ×1.25, read ×0.10).
- **Sonnet→Haiku retry** on stream error (line 93).
- `extractSourceSlug` — surfaces the slug on the Done event so the client can badge
  wiki-grounded vs `general-knowledge` answers.

`ClaudeApiClient` — the timeout/resilience contract worth memorising:

| Constant | Value | Why |
|---|---|---|
| `UNARY_BLOCK_TIMEOUT` | 200s | defensive ceiling above Netty's 180s response timeout |
| `MICRO_BLOCK_TIMEOUT` | 8s | `completeFast` for moderation/relevance — these run *before* the SSE Flux starts |
| `STREAM_IDLE_TIMEOUT` | 45s | inter-chunk idle, so a hung stream can't leak the socket |
| `TOOL_LOOP_TIMEOUT` / `MAX_TOOL_ITERATIONS` | 90s / 3 | agentic tool-use loop bound |

Resilience4j `@Retry(name="claude")` (3 attempts, exponential, only on 5xx/429/timeout) +
`@CircuitBreaker` (50% failure over 20 calls, 30s open). **The fallback never fabricates content** —
it throws `BusinessException("Mochi's resting for a moment", 503)`.
Every path meters into `AiUsageMeter`, including `meterBilledFailure` (line 261) for a 200 that
Anthropic billed but we couldn't parse.

---

## 5. Pipeline C — Learning modules (LEARN → TEST → PROVE)

### 5.1 Generation — `domain/module/ModuleContentGenerator.java` (886 lines)

Triggered from `WikiPagePersistenceService.queueModuleGeneration` per new wiki page.
Explicitly **not** `@Transactional` — four Gemini calls must not pin a DB connection; the UUID is
assigned in memory and only the final persist (`ModuleWriter`) is transactional.

| Stage | Item type | Count (FREE / CENTRE) | Prompt intent |
|---|---|---|---|
| LEARN | `MICRO_CARD` | 4 / 6 | one concept, <60 words, bold key terms |
| TEST | `HOT_TAKE` | 2 / 3 | true/false, ≥1 misconception; sort_order 100+ |
| TEST | `SPOT_MISTAKE` | 1 | a plausible WRONG worked solution; sort_order 200 |
| TEST | `CHALLENGE` | 1 / 3 | application/word problems; sort_order 300+ |
| PROVE | `PROVE_QUESTION` | 3 / 5, **on demand** | targets concepts the student scored poorly on |

Robustness patterns you'll see repeated (this is the "fix the family" rule in action):
- `robustJsonArray` / `robustJsonObject` — parse, salvage complete objects from truncation, **retry
  once**. Both delegate to `shared/json/JsonExtraction` — the single extractor every AI class must
  use (enforced by `JsonExtractionGuardTest`). The fragile private `extractJson` copy is what caused
  the PROVE launch blocker: 0 items → no module could ever COMPLETE → every student blocked.
- `hasNonBlank(...)` field-level guards — a *partial* parse would otherwise persist a
  structurally-valid-but-blank card (a spot-mistake with no answer to reveal).
- `fallbackStageItem` / `fallbackProveItem` — **no stage may ever ship empty**
  (enforced by `ModuleStageFallbackGuardTest`).
- Prompt hygiene: never name the reader's grade/age; explanations written as second-person feedback;
  `targetConcept` must be a 2–4 word label drawn only from the material.
- `MICRO_CARD_TOKENS = 3000` vs `MAX_TOKENS = 1500` — micro-cards were truncating and dropping the
  whole LEARN batch.

### 5.2 Groundedness gate

Spans two packages — read them together, the safety argument doesn't survive being split:
- `domain/knowledge/groundedness/` — `ClaimExtractor`, `GroundednessVerifier`, `EntailmentJudge` (port)
- `infrastructure/ai/ClaudeEntailmentJudge` — the judge adapter
- `domain/module/ModuleContentGenerator` — the **only** caller (`tagGroundedness`, line 222).
  Nothing else in the codebase invokes the verifier.

The anti-hallucination pass, cheap → expensive:

```
ClaimExtractor (no LLM)  →  lexical pre-pass (no LLM)  →  ONE batched EntailmentJudge call
```

1. **`ClaimExtractor`** (static, no LLM) — splits into sentences and keeps only those that are a
   hard fact **or** contain a definitional verb (`is/are/means/equals/defined as/refers to/…`);
   pure soft elaboration is dropped entirely, not kept as a soft claim. Also skipped: <8 chars,
   questions, scaffolding prefixes ("let's", "for example", "remember"…), and — see below —
   headings. `hardFact` = contains a digit, a formula/operator (`= √ × ÷ ± ∑ ∫ ≈ ≤ ≥ ^ a/b`), or a
   multi-word Title-Case named entity. A bare definition ("Mitochondria is the powerhouse") is
   deliberately **soft** — bias toward precision.
2. **Pre-pass** (`GroundednessVerifier.sourceCoverage`, line 157) — claim-token coverage by the best
   matching source sentence **or adjacent-sentence pair**; ≥ `groundedness.high-overlap` (0.6) →
   `Action.CLEAN`/`SUPPORTED` for free. Critically, a hard-fact claim only short-circuits if **all
   its numbers appear in the source** (`numbersCovered`) — otherwise 1789→1798 would sail through
   on high overlap.
3. **Judge** — exactly **one** batched `ClaudeEntailmentJudge` call for all low-overlap claims, and
   only if at least one of them is a hard fact. If none is, every low-overlap claim is `ALLOW`ed at
   **zero** LLM calls. Soft claims in a batch that does fire ride along for free.

Decision table (`mapVerdict`): SUPPORTED → CLEAN · CONTRADICTED → CONTRADICTED ·
NOT_IN_SOURCE + hardFact → FLAG · NOT_IN_SOURCE + soft → ALLOW (legitimate lesson-building is
never flagged).

#### The two failure directions — they are opposite, and that is deliberate

- **The judge fails CLOSED.** `ClaudeEntailmentJudge.judge` retries once; still unparseable (or an
  empty array, or a short array) → every unresolved claim becomes `NOT_IN_SOURCE`. Combined with the
  decision table that means **hard facts get FLAGged for teacher review; soft claims are still
  ALLOWed.** A judge outage does not flag everything — it flags the fact-bearing claims only.
- **The caller fails OPEN.** `ModuleContentGenerator.tagGroundedness` wraps the whole per-item check
  in try/catch and logs `skipping (fail open)` — so an exception anywhere in the gate means the item
  ships **untagged**. A blank/absent source page (`page.getContent()` null or blank) likewise skips
  grounding entirely.

So: a *parse* failure is conservative, an *exception* is permissive. Don't read "fails closed" as a
property of the gate as a whole.

#### Two preconditions that make fail-closed safe — do not remove either

`ClaudeEntailmentJudge`'s javadoc is explicit that failing closed is only safe because both of these
landed first. Remove one and fail-closed becomes an over-reject flood:

1. **Type scoping** — `GROUNDED_TYPES` in **`domain/module/ModuleContentGenerator.java` line 205**
   (note: *not* in the groundedness package): only `MICRO_CARD` and `PROVE_QUESTION` are checked.
   `SPOT_MISTAKE`, `HOT_TAKE` and `CHALLENGE` are *invented practice by design* — grounding them is a
   category error (a 2026-07-13 prod classification found 77% of all flags were on these three:
   "F = m + a" flagged as contradicting the notes, when that IS the planted mistake). Positive
   allow-list, so a 6th `ContentItemType` would not be grounded by default (there are 5 today).
2. **Heading/title filtering** — `ClaimExtractor.isHeadingOrTitle`: a short, majority-Title-Case,
   non-period-terminated line is not a proposition and is dropped. Without it, `NAMED_ENTITY` treats
   a micro-card title ("The Never-Ending Journey!") as a hard fact and flags it as ungrounded.
   Period-terminated lines are always kept, so "Water boils at 100 degrees Celsius" survives.

`recordCalibration` (line 204) logs a **sample** of what's being flagged once **≥20 items have been
checked** (the sample gate — it stays quiet below that) and the running flag rate exceeds
`groundedness.flag-rate-ceiling` (0.20). The rule is to fix the over-firing (coverage precision /
hard-fact classifier), never to relax the ceiling to silence the warning. Note the direction trap
called out in the source: *lowering* `high-overlap` widens the pre-pass and yields **fewer** flags;
*raising* it flags **more**.

### 5.3 Progression + health

- `ModuleProgressionService` (893) — the stage machine: `startModule`, `submitAnswers`,
  `getResults`, `startRevision`, `submitSelfReport`. Stage transitions are enforced server-side;
  PROVE items are generated lazily from TEST results.
- `ModuleProveEvaluator` — LLM grading of free-text PROVE answers against `expectedKeyPoints`.
- `ContentHealthReaper` (297) — finds legacy blank/invalid items. **Dry-run and disabled by
  default**; a live reap needs two flags flipped by an operator holding the damage report.
  Passes: SCAN → QUARANTINE (drops out of `SERVABLE_STATUSES` immediately) → RETIRE-NO-SOURCE →
  REGENERATE (capped LLM path, validate-before-swap, 2 failures → RETIRED). It never deletes rows
  (that would orphan `module_progress` FKs).
- `ModuleExamReadinessService`, `MuddiestService` (muddiest-point voting), `GradingWeights`.

---

## 6. Pipeline D — Quiz, photo homework, teach, marking

**Quiz** — `ClaudeQuizGenerator` → `domain/quiz/usecase/{GetDailyQuizUseCase, SubmitQuizAnswersUseCase}`.
Generates 5 MCQs, then `verifyAndFilter` (line 259): extracts arithmetic from the stem, evaluates it
with `CalculatorTool`, and **corrects `correctIndex` or drops the question** if the model's answer is
arithmetically wrong. `resolveSlug` maps the model's echoed page name back to a real wiki slug so the
weakness/mastery signal keys correctly. Failures throw 503 rather than returning an empty 200.
(Note the comment at line 64: tool_use was tried and abandoned — 2 Haiku calls, 50–60s, timeouts.)

**Photo homework** — `ClaudePhotoQuestionSolver`, three safety layers:
1. **Tier 0** — a cheap Haiku pass classifies each question `NONE|CHART|GRAPH|DIAGRAM|GEOMETRY|TABLE`
   × `HIGH|MEDIUM|LOW`.
2. **Tier 1** — visual questions get collaborative disclaimers ("I can see this is a bar chart… can
   you tell me the values?") instead of confident pixel-reading, and `calculatorVerified=false`.
3. **Tier 2** — model escalation: hard visual (GRAPH/GEOMETRY/LOW confidence) → `forPhotoQuestionMax`,
   any visual → `Heavy`, else `standard`. All three are centre-guarded to Haiku.

Vision path sends the original image bytes via `completeVisionWithTools` with `CalculatorTool`
attached; falls back to text-only if the vision call fails. Our pre-classification **overrides** the
model's own `visualType` (line 305) — Tier 0 is more trustworthy for safety.

**Teach (Feynman)** — `ClaudeTeachEvaluator` + `api/teach/TeachController`: the wiki page is ground
truth; the model extracts salient concepts then checks coverage in the child's explanation.

**Marking / homework (B2B)** — `domain/marking/` (20 files) + `domain/homework/`.
A `MARKING_CORPUS` avatar compiles a centre's rubrics, mark schemes and past marked papers into
marking-*behaviour* pages through the **same** wiki harness (`WikiCompilerPrompts.markingHeader`).
`ClaudeHomeworkFeedbackGenerator` produces a teacher-only **DRAFT**; `MarkingCorrectionCaptureService`
captures teacher edits, `AgreementScorer` measures AI-vs-teacher agreement, and
`MarkingCorrectionCompiler` feeds corrections back into the corpus.

**Weakness profile** — `domain/weakness/WeaknessProfileService`: a private per-(user, subject)
weakness brain compiled through the same harness, flag-gated (`weakness.profile.enabled`) and
surfaced as the capped Block 3.6 in chat.

---

## 7. Cost, safety and observability

**Cost ledger** — `domain/cost/`: `AiUsageMeter.record(...)` is called on every model path and
**never throws** (a metering bug must not break a compile). `AiCostRates` converts tokens to
micro-dollars; rows carry `callType`, `purposeLabel`, `trigger`, `success`, `estimated`.
Surfaced at `/api/v1/admin/ai-cost`.

Cost levers, in the order they matter:
1. Gemini for compile/classify/module-gen (10–13× cheaper than Haiku) — `GeminiCompletionService`
2. Prompt caching for chat (write ×1.25, read ×0.10) + `CacheKeepAliveService` 4-min ping
3. `gemini.thinking-budget.*` = 0 for extraction/classify/generation purposes; reasoning purposes
   (`teach-eval`, `module-prove-eval`) are deliberately *absent* → provider default (thinking ON)
4. Chapter chunking — an unpicked chunk costs nothing
5. Daily compile cap (50/avatar), FREE upload cap (5/30d), chunk-compile cap (5/30d)
6. Content-change gate on hint/flashcard regen; summariser every 3rd turn

**Safety** — `ModerationService` (input pre-screen fail-safe on HIGH, output post-hoc),
`SafetyAlertService` (email on HIGH or 3 flags/24h), `ConsentGuard` (default-deny child-data ingress
+ AI-transfer consent), closed-book gates for centre students, `infrastructure/ratelimit/`.

**Observability** — `[Pipeline:Upload]`, `[Pipeline:Compile]`, `[Pipeline:BatchCompile]`, `[Gemini]`,
`[Claude-<callId>]`, `[Brain]`, `[ChatCtx]`, `[Cache]`, `[CacheMetrics]`, `[Dedup]`, `[Groundedness]`,
`[Debounce]`. Micrometer via `infrastructure/observability/ClaudeMetrics`; actuator exposes
health/info/metrics/prometheus; `BuildInfoLogger` + `DeployInfoContributor` back the deploy
verification checklist in `CLAUDE.md`.

---

## 8. Cross-cutting infrastructure

- **`GlobalExceptionHandler`** (`api/`, 403 lines) — the *only* place errors become HTTP.
  `spring.mvc.throw-exception-if-no-handler-found: true` + `add-mappings: false` so unmapped routes
  give a clean 404 instead of a 500. **SSE is the exception**: error JSON must be written with
  `response.setStatus` + `getWriter()` *before* the Flux starts (see the API error contract in
  `CLAUDE.md`) — never throw inside the Flux, the client won't see it.
- **`ApiResponse`** wraps every response; a refactor must never change status/shape/fields.
- **Config** — `AiTaskExecutorConfig` (core 2 / max 4 / queue 10, `AbortPolicy` → 503 not silent
  back-pressure), `WebClientConfig` (connect 10s, response 180s, read 200s, 4MB buffer),
  `SecurityConfig`, `SecretsValidator` + `EnvironmentGuard` (a live `sk_live_` key on a non-prod
  profile refuses to boot), `CacheConfig`, `AdminBootstrap`.
- **Persistence** — `open-in-view: false` (a 60s Claude call must not hold a Hikari connection);
  Hikari pool env-tuned; JPA `ddl-auto: validate`; entity ↔ domain mapping in
  `infrastructure/persistence/<aggregate>/*Adapter`.
- **Time** — `shared/util/PallyTime.SGT`. Every daily reset, streak, quota window and report
  boundary uses Asia/Singapore, never UTC.
- **Auth** — JWT + Google/Apple social (`SocialTokenVerifier` is **fail-closed**: no configured
  audience ⇒ social sign-in returns 401 rather than silently accepting).
- **Billing** — Stripe (`infrastructure/stripe/`) + RevenueCat webhook, with
  `processed_revenuecat_events` for idempotency.

---

## 9. Testing

279 test files. The conventions worth knowing before you add code:

- Unit tests (JUnit + Mockito) for every use case/service: happy path + ≥1 failure path.
- Integration tests (Testcontainers) for every endpoint, asserting status **and** JSON shape.
- Concurrency harnesses for money/XP/stars/atomic paths (e.g. `BuyStreakFreezeConcurrencyTest`).
- **Guard tests** — the codebase's answer to "a good pattern applied in one place but not its
  siblings". Read these to learn the invariants: `DomainLayeringGuardTest` (allow-list only ever
  shrinks), `JsonExtractionGuardTest`, `ModuleStageFallbackGuardTest`, `ChunkCompileGuardTest`,
  `ChunkPersistenceInvariantsTest`, `CentreRouteUniquenessTest`, `FlashcardModelEvidenceGate`.
- Offline eval harnesses in `tools/eval/` (`content_quality_eval.py`, `smoke_multidoc_conflict.py`,
  `smoke_recompile_idempotency.py`, `smoke_adversarial_length.py`) and `tools/e2e-qa/`.

Mandatory workflow for any change: `./gradlew compileJava` → `./gradlew test` → only then "done".

---

## 10. Recurring design principles (why the code looks like this)

1. **Never fabricate.** Circuit-breaker fallbacks throw 503; they never invent tutoring content.
2. **Never present a lie as success.** No READY-empty brain, no "done" on a 0-page compile, no
   `compiled_by` on a failed segment, billed-but-failed calls still land in the ledger.
3. **Fix the family, not the instance.** Any parser/prompt/guard fix is applied to all siblings the
   same day, plus a guard test to hold the line.
4. **Cheap → expensive.** Deterministic checks first (hashes, Jaccard, regex, calculator), one
   batched LLM call last, and only when a hard fact is at stake.
5. **AI calls never hold a transaction or a request thread.** Bounded pool, bounded timeouts,
   OSIV off, per-page `REQUIRES_NEW`.
6. **Fail-open for convenience gates, fail-closed for safety gates.** Relevance check fails open;
   the entailment judge, social-token audience check, and consent gates fail closed.
7. **Measure gates, don't mute them.** Over-firing is fixed at the source; the ceiling stays.
