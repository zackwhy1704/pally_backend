# Deferred items — grading harness & trust

> Linked from [`CLAUDE.md`](CLAUDE.md). This is the tracked home for consciously-deferred
> gaps; when you defer something, add it here (don't leave it "low priority" with no owner).

Consciously-accepted gaps with a home, so "low priority with no owner" never again
becomes how a fail-open survives for months. Each item: what, why deferred, the
accepted risk, and what closes it.

## 1. Quiz keyless client-map fallback (`SubmitQuizAnswersUseCase:187`)
- **What:** when no server answer key exists for a question, grading falls back to
  the client-supplied `correctMap`. This is the LAST place in the system that reads
  a client-supplied answer key.
- **Why deferred:** unreachable for normal play — the serve chokepoint persists a
  key for every quiz and the reaper TTL is 7 days (`quiz.answer-key-retention-days`).
  Only a quiz served, abandoned, and submitted >7 days later reaches it. Full
  closure needs quiz-side signal typing (a schema change to `quiz_question_results`),
  which is separate work.
- **Accepted risk:** a >7-day-stale abandoned quiz can be graded from the client map
  (spoofable) for its analytics row. Pinned by test
  (`SubmitQuizAnswersUseCaseTest.noServerKey_fallsBackToClientMap_theKnownLegacyGap_pinnedExplicitly`).
- **Closes it:** add `signal_type` to quiz results; keyless → UNGRADED (exclude from
  analytics) instead of client-map.

## 2. PROVE old-client stranding
- **What:** the self-assessment overlay renders only from the PROVE submit response
  in the new pally client (>= 2ad9dae). Modules completed on an older build — or
  before the overlay shipped — have PROVE items stranded as permanently UNGRADED
  with no later UI path to self-assess.
- **Why deferred:** safe (UNGRADED asserts nothing false); a retro-self-assess UI is
  net-new surface.
- **Accepted risk:** those modules contribute no PROVE mastery signal, ever.
- **Closes it:** a "review past modules" surface that lets a student self-assess
  historical PROVE items, or backfill on next module open.

## 3. SPOT_MISTAKE / CHALLENGE are UNGRADED by design
- **What:** of the TEST types, only HOT_TAKE has a discrete key. SPOT_MISTAKE
  (`errorDescription`/`correctSolution` free text) and CHALLENGE (open-ended) have no
  discrete key, so they stay UNGRADED (never a client-trusted or guessed score).
- **Why deferred:** deterministic grading needs a discrete key these types don't have.
- **Accepted risk:** these TEST items contribute no mastery signal.
- **Closes it:** either convert SPOT_MISTAKE to a keyed (multiple-choice) format, or
  route CHALLENGE through the PROVE self-assessment path.

## 4. Display-as-state for self-report-only modules (product decision)
- **What:** a module whose only signal is a self-report renders a trust-weighted
  percentage (e.g. YES → 30%). Exam-readiness is now weighted + labelled with
  `signalType` (consistent with module mastery), so the adjacent-surface CONTRADICTION
  is fixed. The remaining question is whether a **30% to a parent notification** for a
  self-assessed module should instead read as a STATE ("completed — self-assessed").
- **Why deferred:** product/UX call, not a correctness bug (the numbers are now
  consistent). The `signalType` label is already emitted so the UI can switch to a
  state rendering without further backend work.
- **Decision:** DEFER, recommend rendering self-report-only as a state label in the
  parent notification + exam-readiness; deterministic-bearing modules keep the %.
- **Closes it:** UI reads `signalType` and renders "self-assessed" instead of a % when
  no deterministic signal is present.

## 5. Generation validator: keyless deterministic item (generation session)
- **What:** a deterministic-type item (HOT_TAKE) generated WITHOUT its key
  (`answer_json.isTrue`) should fail generation validation, not persist.
- **Why deferred / where:** belongs to the generation/reaper session, not the grading
  harness. The server already degrades a keyless HOT_TAKE to UNGRADED, so this is a
  quality gate, not a correctness gate.
- **Accepted risk:** a keyless HOT_TAKE produces no signal (UNGRADED) until the
  generation session adds the validator + reaper regenerates it.

## Off-keyboard (not code)
- Device-verify the weakness loop on real hardware (wrong hot-takes → mastery moves →
  weak concept → weak-first next quiz).
- Store submission, manual QA pass, DPIA.

## Cost / fan-out control — deferred (2026-07, from the AI-cost-ledger pass)

Shipped this pass: Teach EVAL_FAILED, the content-change fan-out gate (2.4/2.3),
the ai_usage ledger extension + GeminiCompletionService metering (P1), and the
flashcard auto-gen threshold CTA (2.2). Deferred, deliberately, from data not guesses:

### Async flashcard generation job (2.1)
- **What:** POST returns a job id; client polls (reuse CompileJobStore +
  DurableCompileStatusStore + the /wiki/compile status pattern). Kills the
  synchronous all-pages loop entirely; survives navigation.
- **Why deferred:** the heavy piece. The 2.2 threshold CTA is the interim shield.
- **Accepted risk:** a *confirmed* large-corpus generate still runs synchronously
  (behind the CTA, so it's a chosen wait, not a surprise hang).

### Config caps (2.5) — BOTH halves
- **What:** max new cards/day to a student (Anki-style due+N-new queue) + a
  per-avatar daily LLM-call budget with a "resumes tomorrow" state.
- **Why deferred:** caps chosen before ledger data are guesses. The new-cards/day
  cap is pedagogy UX that belongs with the flashcard page-picker pass; the
  per-avatar LLM budget is redundant while the account-level AI Studio spend cap
  exists (SET THAT — 2 min, $0). Decide both from ai_usage data.

### pally flashcard page/topic picker (2.6)
- **What:** filter by sourceSlug (column exists) so a kid with 2000 cards studies
  the chapter they chose; default view = due-today + today's new allotment.
- **Why deferred:** ships with the new-cards/day cap (2.5) as one UX pass.

### Lazy module generation — PARTIALLY RESOLVED structurally by chapter-chunking
- **What:** don't generate a page's Learn/Test modules until a student opens that
  topic (PROVE is ALREADY adaptive — generateProveItemsAdaptively at TEST
  completion — so only LEARN+TEST are eager).
- **RESHAPED by chunked compile (shipped ca40cea):** the biggest waste this entry
  targeted — an entire textbook's pages generated eagerly when few are studied — is
  now gone at the SOURCE: an oversized upload no longer compiles whole, so modules
  are only ever generated for pages of chunks the student PICKED. Generation follows
  compiled chunks; a never-picked chapter produces no pages → no module fan-out
  (verified: all three generators iterate wiki pages, not KnowledgeFiles).
- **What REMAINS (narrower):** WITHIN a compiled chunk, LEARN+TEST are still eager
  for every page. That's a much smaller surface (~25 pages, deliberately picked, so
  high open-probability) — the "N% of pages never opened" argument is weak here. The
  original blocker still holds for the residue: assignment resolution SNAPSHOTS the
  module set, and lazy gen would put a 5-call latency storm in the student's path.
  Decide from the ledger whether within-chunk laziness is still worth the design
  split (eager for class/assigned, lazy LEARN+TEST for B2C self-study) — but the
  headline dollar win this entry chased is already banked by chunking.

### Ledger blind spots still open (P1 covered the completion services)
- OCR (GeminiVisionOcrService, ClaudeVisionOcrService — direct HTTP) and streaming
  chat + keepalive (ClaudeApiClient streaming) bypass the metered completion
  clients → still unmeter'd. The ledger will show them as gaps; the corpus-∝ chat
  streaming is the likely home of the rest of the spike (the fan-out fixes meter
  but don't reduce it). Meter these next.

### RelevanceChecker fail-open inconsistency (ledger-grade, from the family sweep)
- `ClaudeRelevanceChecker:120` parse-error → 0.0 (reject) vs `:46` exception → 1.0
  (accept). Inconsistent, but relevance is a soft warning (not a hard gate), so
  low-priority. Make the parse-error path match the accept-on-failure behaviour.

## Cost levers + page quota — deferred (2026-07, from the cost-levers/quota pass)

Shipped this pass: P3 attribution (avatarId threaded through GeminiCompletionService
callers; commit 78c1927) + P4 OCR metering (both VisionOcrServices; 72a3154). The
ai_usage ledger is now COMPLETE (completion services + OCR) and avatar-attributed —
so "what does a FREE user cost/month" is a one-query answer. Remaining, scoped:

### Flashcard model lever (Phase 1) — gate BUILT + run; PASSES with thinking OFF
- **What:** route flashcard gen Haiku → gemini-2.5-flash (the ~$1.25/upload lever).
  ClaudeFlashcardGenerator:82 hardcodes getHaikuModel(); ModelRouter has no
  forFlashcardGeneration(). Add it + a Gemini path.
- **Evidence gate:** `FlashcardModelEvidenceGate` (committed, run with CLAUDE_API_KEY
  + GEMINI_API_KEY via `railway run`). KEY FINDING: gemini-2.5-flash as-is (thinking
  ON, low token cap) SILENTLY DROPS ~40% of pages (0 cards — the thinking tokens eat
  the output budget). With `generationConfig.thinkingConfig.thinkingBudget=0` it's
  reliable: 0 dropped pages, 100% valid, 4.4 vs 5.0 cards/page → GATE PASSED. So the
  switch is VIABLE **only with thinking disabled**, AND still needs the real 20-page
  run (the committed run was 5 easy placeholder pages) before flipping.
- **On flip:** add ModelRouter.forFlashcardGeneration()=gemini-2.5-flash + route the
  flashcard prompt through a Gemini path that sets thinkingBudget=0 + robustJsonArray-
  style retry/salvage (Flash empties must be retried, never silently dropped).

### Gemini thinking-mode starves low-token calls — MOSTLY RESOLVED (per-purpose config)
- **What it was:** `GeminiCompletionService`/`GeminiWikiCompiler` set generationConfig
  WITHOUT thinkingConfig → gemini-2.5-flash ran thinking ON at low token caps → thinking
  ate the output budget → silent empties → robustJsonArray retries (2× calls) or Haiku
  fallback (10×). The flashcard gate proved the mechanism (~40% empties → 0 with
  thinkingBudget=0).
- **RESOLVED:** thinking is now a PER-PURPOSE config (`GeminiThinkingBudgetConfig`,
  `gemini.thinking-budget.<purpose>` in application.yml). EXTRACTION / classify /
  structured-generation purposes (topic-router, summarizer, class-brief, wiki-compile,
  all module LEARN/TEST/PROVE-**gen** + centre-regen-*) → `thinkingBudget=0`. A purpose
  ABSENT from the map OMITS thinkingConfig → provider default (thinking ON). Revert any
  single purpose by deleting its yml line. NOTE: an earlier blanket hardcode (15bb23c)
  had wrongly set 0 on the two REASONING evals too — the config restores thinking-ON
  there.
- **STILL GATED (the only remaining piece):** the two REASONING purposes `teach-eval`
  and `module-prove-eval` are deliberately UNLISTED (thinking ON). Flipping them to 0
  needs the teach-eval evidence-gate variant (parse-rate + AgreementScorer sanity,
  thinking on vs off) run with live keys — same 10-min protocol as the flashcard gate.
  No flip without it. To flip after the gate passes: add `teach-eval: 0` /
  `module-prove-eval: 0` to `gemini.thinking-budget`.
- **Failure-path metering (DONE this pass):** the empty-text branch in BOTH Gemini
  services now records a ledger row with `success=false` + the usageMetadata tokens
  (Google billed the call) BEFORE throwing, with `finishReason` in the purpose_label
  suffix (`<task>:EMPTY:MAX_TOKENS` vs `:SAFETY`) — no migration. The wiki compiler used
  to record such empties as `success=true`; fixed.

### Zombie recompile reconciler — RESOLVED (four affected avatars: run diagnostic in prod)
- **What it was:** `findAvatarIdsNeedingRecompile()` flagged any avatar with READY files
  and 0 ACTIVE wiki pages EVERY startup — even when all its files were already compiled
  (`compiled_by` set). executeBatched then skips every file (idempotent) → guaranteed
  no-op → re-flags again next restart, forever.
- **RESOLVED:** the "0 active pages" branch now also requires an uncompiled READY file
  (`compiled_by IS NULL`) — genuine work. The "file newer than pages" incremental branch
  is untouched. Pinned by `ZombieRecompileReconcilerTest` (real Postgres).
- **REMAINING (prod forensics, do NOT blind-recompile):** identify WHY the four known
  avatars have 0 pages (compile produced none / all archived / all irrelevant). Run
  against prod, then decide per-avatar (reaper vs leave):
  ```sql
  SELECT kf.avatar_id,
         COUNT(*)                                         AS ready_files,
         COUNT(*) FILTER (WHERE kf.compiled_by IS NOT NULL) AS compiled_files,
         (SELECT COUNT(*) FROM wiki_pages wp
           WHERE wp.avatar_id = kf.avatar_id AND wp.status = 'ACTIVE')   AS active_pages,
         (SELECT COUNT(*) FROM wiki_pages wp
           WHERE wp.avatar_id = kf.avatar_id AND wp.status = 'ARCHIVED') AS archived_pages
    FROM knowledge_files kf
   WHERE kf.status = 'READY'
   GROUP BY kf.avatar_id
  HAVING (SELECT COUNT(*) FROM wiki_pages wp
           WHERE wp.avatar_id = kf.avatar_id AND wp.status = 'ACTIVE') = 0;
  ```
  archived_pages > 0 → content existed then was archived (investigate why). archived = 0
  and active = 0 → compile genuinely produced nothing (extraction/relevance drop — check
  extracted_chars). These are now unflagged by the reconciler either way (no more churn).

### Module-gen batching (Phase 2) — the launch-blocker-prone harness
- **What:** merge the 3 TEST calls (HOT_TAKE+SPOT_MISTAKE+CHALLENGE) → 1 (target
  ≤2–3/page); batch flashcards 3–5 pages/call with per-member salvage. LEARN
  separate; PROVE already adaptive.
- **Why deferred (risk):** this is the generation harness with the B2 launch-blocker
  history (0 PROVE items → no module could complete). NON-NEGOTIABLE guard: raise
  token budget per merged call + salvage parser + a per-merge config flag that
  REVERTS to the split path if the merged call's truncation rate (validator drops)
  exceeds the split baseline. `ModuleGeneratorMergeParityTest` already exists — build
  on it. Do NOT rush at the tail of a long session.

### Page-based upload quota — LARGELY SUPERSEDED by chapter-chunking (shipped ca40cea)
- **UPDATE (chunked compile shipped):** the two problems this entry was designed to
  solve are now handled a different way, so most of it is moot:
  - **The 600k "split by chapter" reject is DISSOLVED.** That ceiling conflated a
    quality unit with a safety bound; both jobs are now separated. Upload segments at
    50k (`compile.segment-trigger-chars`) into pickable chapters and rejects only at
    5M (`compile.upload-reject-chars`, pathological-file safety). A 500-page textbook
    is INGESTED (chunked), not rejected — the "split by chapter" hint now points at a
    capability that exists by default. **Verified: the parent extraction path has NO
    600k trap** — the old reject was retired, and PDFBox extracts the whole file; only
    a genuinely broken 5M+-char file is refused.
  - **The per-doc page ceiling is SUPERSEDED.** A big doc no longer needs a per-doc
    cap — it's split into ≤25-page chunks, each compiled individually.
  - **The cost gate this entry chased already exists** as the chunk-compile allowance
    (`Entitlements.monthlyChunkCompiles` + `ChunkCompileGuard`, FREE 5 / PRO 100 /
    unlimited), which caps the EXPENSIVE op (compile) directly, per rolling 30d, same
    guard code path for B2C and centre-resolved B2B.
- **What could still be worth doing (much narrower):** a rolling-30d *page-volume*
  budget on top of the doc-count + chunk-compile caps, IF the ledger shows a few
  users uploading enormous corpora and compiling everything. Decide from data; it's
  no longer a launch item. Original scoping kept below for reference.
- **Original scoping (for reference — mostly moot now):**
  - Chokepoint: enforce at the TOP of UploadFileUseCase (same place the doc-count
    guard + preflight already sit), BEFORE OCR/extraction.
  - 600k ceiling — DISSOLVED (see UPDATE above). The old note read: a ~1M-char/500-page
    file was REJECTED LOUDLY with a "split by chapter" hint. Chunking makes that the
    default behaviour instead of a reject.
  - Charge model: preflight ESTIMATE (PDF metadata pages / image count / ceil(chars/2000)
    / ceil(bytes/3500) flagged) to REJECT only; CHARGE AT READY on ACTUAL extracted chars.
    Prefer a stored extracted_chars column set at READY (one migration) over len()-per-check.
    Grandfather-ZERO (pre-migration READY files count 0). Bounded concurrent overshoot
    (< 1 per-doc ceiling) accepted vs reservation complexity — document in guard javadoc.
  - Scope: USER default; CENTRE budget = -1 (unlimited) so B2B is unaffected; FAMILY/
    ORG_CLASS pooling → DEFERRED (decide from ledger data, same date as lazy-modules).
  - Tiers/limits config live in Entitlements.forTier + application.yml (join, don't
    rebuild). Migration must warn on the stale subscription.free.upload-cap key.
- **Status:** largely SUPERSEDED by chapter-chunking (backend + both clients shipped).
  Any residual page-volume budget is now a data-driven maybe, not a launch item — the
  expensive-op gate (compile) is already covered by the chunk-compile allowance.

### Flyway version bump for Postgres 18 (advisory, deferred)
- **What:** boot logs a Flyway "not certified for this database version" advisory on
  Postgres 18. Advisory ONLY — migrations still apply and run correctly; it's Flyway's
  compatibility whitelist lagging the PG release, not a functional break.
- **Why deferred:** a Flyway dependency bump is a build-infra change with its own
  regression surface (migration-runner behavior), out of scope for the deploy-guards PR.
- **Closes it:** bump the Flyway version (via the Spring Boot BOM override or an explicit
  `org.flywaydb:flyway-core`/`flyway-database-postgresql` version) to one that lists PG18,
  then re-run the Testcontainers migration suite to confirm no runner behavior change.
