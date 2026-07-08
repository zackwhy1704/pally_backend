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

### Lazy module generation — decide after 2 weeks of ledger data
- **What:** don't generate a page's Learn/Test modules until a student opens that
  topic (PROVE is ALREADY adaptive — generateProveItemsAdaptively at TEST
  completion — so only LEARN+TEST are eager).
- **Why deferred (blocker, not a wrinkle):** assignment resolution SNAPSHOTS a
  student's module set at start; lazy gen would put a 5-call latency storm in the
  student's critical path (30 kids opening an assigned topic at 7pm = 150
  simultaneous generations) and make teacher previews empty. It's a design split,
  not a flag flip: **eager for class/assigned avatars, lazy LEARN+TEST for B2C
  self-study.** Decide from the ledger — if it shows N% of pages never opened,
  lazy is a data-backed win with a known dollar value.

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
