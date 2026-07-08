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
