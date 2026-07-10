# Deferred items — the tracked ledger

> Linked from [`CLAUDE.md`](CLAUDE.md). This is the tracked home for consciously-deferred
> gaps: things we chose not to do yet, each with a reason and **what closes it**. When you
> defer something, add it here — don't leave it "low priority" with no owner.

## How to read this file (process notes, learned the hard way)

- **Unledgered ≠ tracked.** A defect you can see on a screenshot but that has no line here
  is invisible, not "known" — file the entry the moment a gap is known, not after it's fixed.
  (The 0–1 vs 0–100 "2600% mastery" family bug sat in shipped code for weeks with no entry.)
- **Audits must sweep the OLD SIBLINGS of a new surface, not just the diff.** When a rule is
  locked ("a bearer token alone can never delete an account"), grep every sibling of the
  changed code and re-audit the ones the rule now implicates. (`DELETE /account/me` was
  locked while `DELETE /auth/account` — the endpoint the live client called — kept the old
  bearer-only semantics. Twice now an audit's blast radius was drawn around what changed,
  not around what the change made stale.)
- **A fix at the VISIBLE layer is not a fix at the WORK layer.** Verify the behaviour exists
  beneath the label. (A commit subject once claimed "meter streaming chat" while metering
  only the *unary* path — see the streaming-meter entry below.)
- **Push at commit time.** On this repo, merge to `main` auto-deploys to prod (Railway).
  A held branch is not shipped; a "done" that isn't merged is not live.

Sections: **OPEN** (code, actionable — each needs a "closes it" line) · **OFF-KEYBOARD**
(human/ops, not code) · **CLOSED** (with commit refs, kept for archaeology).

---

# OPEN (code — actionable)

## Grading harness & trust

### 1. Quiz keyless client-map fallback (`SubmitQuizAnswersUseCase:187`)
- **What:** when no server answer key exists for a question, grading falls back to the
  client-supplied `correctMap` — the last place in the system that reads a client answer key.
- **Why deferred:** unreachable for normal play — the serve chokepoint persists a key for
  every quiz and the reaper TTL is 7 days (`quiz.answer-key-retention-days`). Only a quiz
  served, abandoned, and submitted >7 days later reaches it.
- **Accepted risk:** a >7-day-stale abandoned quiz can be graded from the (spoofable) client
  map for its analytics row. Pinned by
  `SubmitQuizAnswersUseCaseTest.noServerKey_fallsBackToClientMap_theKnownLegacyGap_pinnedExplicitly`.
- **Closes it:** add `signal_type` to `quiz_question_results`; keyless → UNGRADED (excluded
  from analytics) instead of client-map.

### 3. SPOT_MISTAKE / CHALLENGE are UNGRADED by design
- **What:** of the TEST types, only HOT_TAKE has a discrete key. SPOT_MISTAKE
  (`errorDescription`/`correctSolution` free text) and CHALLENGE (open-ended) have no discrete
  key, so they stay UNGRADED (never a client-trusted or guessed score).
- **Accepted risk:** these TEST items contribute no mastery signal.
- **Closes it:** convert SPOT_MISTAKE to a keyed (multiple-choice) format, or route CHALLENGE
  through the PROVE self-assessment path.

### 4. Exam-readiness UI label for self-report-only concepts — FIX BUILT (pally), merge-held
- **What:** the backend exam-readiness DTO emits `signalType` (`ModuleExamReadinessService.java:67`),
  but the pally exam-prep concept model didn't parse it, so a SELF_REPORT concept's
  trust-weighted % rendered as if graded.
- **Status:** fix built on pally branch `feat/ledger-reconcile-cheap-closes` — `signalType`
  added to `ExamConceptMastery` + a "Self-assessed" caption on `exam_prep_screen`; pinned by a
  widget test. Merge-held with that branch. The parent-notification half is already CLOSED
  (see below).
- **Closes it:** merge the pally cheap-closes branch.

## Cost / fan-out control

### Streaming + keepalive metering — the last ledger blind spot
- **What:** the completion clients and OCR are metered, but `ClaudeApiClient`'s SSE streaming
  path (`streamResponseWithCache` / `streamResponseWithCacheAndModel` / `streamResponse`,
  :668–802) relays the raw flux and never parses the terminal `message_delta`/`message_stop`
  usage event → streaming chat + `CacheKeepAliveService` keepalive pings are unmetered.
  (Note: commit `8455952`'s subject said "meter streaming chat" but metered only the *unary*
  `completeFast` — a visible-layer/work-layer gap; the SSE flux is genuinely still open.)
- **Why deferred:** the corpus-∝ chat streaming is the likely home of the remaining spend the
  fan-out fixes meter-but-don't-reduce; worth doing carefully with the terminal-event parse.
- **Closes it:** capture usage from the terminal SSE event; meter with purpose + avatarId; on
  stream error/abort, meter `success=false` with whatever usage the stream reported. Keepalive
  metered with its own purpose label. (Prompt B2.)

### Flashcard model lever (Haiku → gemini-2.5-flash) — saving UNREALIZED
- **What:** the ~$1.25/upload lever. `ClaudeFlashcardGenerator.java:82` still hardcodes
  `modelRouter.getHaikuModel()`; `ModelRouter` has **no** `forFlashcardGeneration()`. The
  evidence gate (`FlashcardModelEvidenceGate`) passed **only with thinking disabled**
  (gemini-2.5-flash with thinking ON silently drops ~40% of pages; `thinkingBudget=0` → 0
  dropped, 100% valid, 4.4 vs 5.0 cards/page).
- **Why still open:** the flip is not applied — the saving is not being realized. It is **not
  one line**: it needs a new router method → gemini-2.5-flash AND routing the flashcard prompt
  through a Gemini path that sets `thinkingBudget=0` + `robustJsonArray` retry/salvage (Flash
  empties must be retried, never silently dropped).
- **Closes it:** add `ModelRouter.forFlashcardGeneration()` + the Gemini path (Prompt B1),
  confirm `gemini.thinking-budget` maps the flashcard purpose to 0, ship a cards/page counter.

### Gemini thinking-mode on the two REASONING evals — GATED
- **What:** `teach-eval` and `module-prove-eval` are deliberately UNLISTED in
  `gemini.thinking-budget` (thinking ON). The extraction/structured-gen purposes are already 0
  (`GeminiThinkingBudgetConfig`); the failure-path metering is DONE (see CLOSED).
- **Why deferred:** flipping the two reasoning purposes to 0 needs a teach-eval evidence-gate
  variant (parse-rate + AgreementScorer sanity, thinking on vs off) with live keys — same
  10-min protocol as the flashcard gate. No flip without it.
- **Closes it:** run the gate; if it passes, add `teach-eval: 0` / `module-prove-eval: 0`.

### Async flashcard generation job
- **What:** POST returns a job id; client polls (reuse `CompileJobStore` +
  `DurableCompileStatusStore` + the `/wiki/compile` status pattern). Kills the synchronous
  all-pages loop; survives navigation. The 2.2 threshold CTA is the interim shield.
- **Accepted risk:** a *confirmed* large-corpus generate still runs synchronously (behind the
  CTA — a chosen wait, not a surprise hang).
- **Closes it:** build the job-id + poll path.

### Module-gen batching (Phase 2) — the launch-blocker-prone harness
- **What:** merge the 3 TEST calls (HOT_TAKE+SPOT_MISTAKE+CHALLENGE) → 1 (≤2–3/page); batch
  flashcards 3–5 pages/call with per-member salvage. LEARN separate; PROVE already adaptive.
- **Why deferred (risk):** this is the generation harness with the B2 launch-blocker history
  (0 PROVE items → no module could complete). Do NOT rush at a session tail.
- **Closes it:** raise token budget per merged call + salvage parser + a per-merge config flag
  that REVERTS to the split path if the merged call's validator-drop rate exceeds the split
  baseline. `ModuleGeneratorMergeParityTest` exists — build on it.

### Config caps — data-driven
- **What:** max new cards/day per student (Anki-style due+N-new queue) + a per-avatar daily
  LLM-call budget with a "resumes tomorrow" state.
- **Why deferred:** caps chosen before ledger data are guesses. The per-avatar LLM budget is
  redundant while the account-level AI Studio spend cap exists (set that — off-keyboard).
- **Closes it:** decide both from `ai_usage` data; the new-cards/day cap ships with the picker.

### pally flashcard page/topic picker
- **What:** filter by `sourceSlug` (column exists) so a kid with 2000 cards studies the chapter
  they chose; default view = due-today + today's new allotment.
- **Closes it:** ships with the new-cards/day cap as one UX pass.

### Lazy module generation — narrowed by chunked compile
- **What remains:** WITHIN a compiled chunk, LEARN+TEST are still eager for every page (PROVE
  is already adaptive). The headline dollar win (a whole textbook generated eagerly) is already
  banked by chunking (`ca40cea`): modules are only generated for pages of chunks the student
  picked.
- **Why the residue holds:** assignment resolution SNAPSHOTS the module set, and lazy gen would
  put a 5-call latency storm in the student's path.
- **Closes it:** decide from the ledger whether within-chunk laziness is worth the design split
  (eager for class/assigned, lazy LEARN+TEST for B2C self-study).

### Page-based upload quota — largely superseded by chunked compile
- **What remains (narrow):** a rolling-30d page-volume budget on top of the doc-count +
  chunk-compile caps, IF the ledger shows users uploading enormous corpora and compiling all.
  The 600k "split by chapter" reject is dissolved (segments at 50k, rejects only at 5M
  pathological); the expensive op (compile) is already capped by `ChunkCompileGuard`
  (`Entitlements.monthlyChunkCompiles`, FREE 5 / PRO 100 / unlimited).
- **Closes it:** a data-driven maybe — decide from ai_usage; no longer a launch item.

### Flyway version bump for Postgres 18 (advisory)
- **What:** boot logs a Flyway "not certified for this database version" advisory on PG18.
  Advisory only — migrations still apply and run correctly.
- **Closes it:** bump Flyway (Spring BOM override or explicit `flyway-core` /
  `flyway-database-postgresql`) to a version listing PG18; re-run the Testcontainers suite.

## Auth hardening (branch merged — these follow-ups remain)

> `feat/auth-hardening-a` is **MERGED to main** (`1740d95`…`1fdad48`, age inversion `76358e0`).
> The branch closed signup-upsert takeover, social auto-link takeover, email normalization,
> fail-closed status, sub-keying, linking challenges, real forgot-password, birthYear
> collection, and the `isUnder13(null)` inversion. Deferred follow-ups:

- **UNIQUE `lower(email)` index upgrade** — V114 added a NON-unique index; the UNIQUE upgrade
  is gated on the prod case-variant duplicate count (dedup is a human call).
  **Closes it:** run the dup-count query (off-keyboard); if zero, a V-next migration dropping
  the non-unique index and adding `UNIQUE (lower(email))` + a case-variant 409 test (Prompt C1).
- **Keychain/secure-storage wipe on first launch/logout** — iOS Keychain survives uninstall, so
  a reinstalled app can resurrect a stale identity. Server epoch invalidates the session, but
  the client should wipe. **Closes it:** pally first-launch (SharedPreferences flag absent)
  secure-storage wipe before reading any token (Prompt C2).
- **Breach-password screening (HIBP k-anonymity)** — reject known-breached passwords at
  register/reset. Deferred: net-new external call + UX; not a takeover fix.
- **Refresh-token rotation** — auth is stateless JWT with a session-epoch kill switch; a true
  short-lived-access + rotating-refresh model is a larger redesign. Deferred.
- **Login/verify rate-limiting + attestation** — login is rate-limited; per-endpoint limits on
  link/verify-code beyond the challenge attempt-cap, and device attestation, are hardening.
- **Email verification at register** — we don't block app use on unverified email (kids start
  fast); a verification gate is a separate product call.

## Account deletion (branch merged — these follow-ups remain)

> Account deletion Phase 1 (`ad35005`) **and** Phase 2 (`92d729b`) are **MERGED to main**:
> V118/V119 grace-lifecycle, `POST /account/delete` (re-auth), `DeletionPurgeReaper`, restore
> (`a659212`), consent-proof retention, public delete-by-email, and `/auth/account` → 410.
> Deferred follow-ups:

- **Back-port batch limit to `PendingParentalConsentReaper`** — the consent reaper still selects
  ALL stale accounts unbounded; `DeletionPurgeReaper` added a batch limit + cursor. Same
  unbounded-scheduled-deleter shape. **Closes it:** give the consent reaper the same treatment.
- **Persistent purge-abort escalation** — an org acquired during grace makes the reaper abort
  that user every run (stays PENDING, loud log) and re-occupies a batch slot daily. Fine at
  near-zero volume. **Closes it (if it recurs at scale):** a PENDING_REVIEW sub-state so aborts
  don't starve healthy purges.
- **Consent-evidence retention DURATION** — V119 RETAINS `consent_records`/`consent_requests`
  through erasure (PDPA proof-of-consent), approval token scrubbed. How many years to keep is a
  DPO/lawyer number. **Closes it:** a dated consent-evidence purge once N is set (off-keyboard
  / DPIA).

### Apple full-name capture — PRECONDITION on future Apple sign-in
- **What:** Apple sends the user's name only in the first-auth `user` payload (not the JWT); the
  client must forward it. Backend already persists email on first auth.
- **Status:** MOOT until pally ships Apple sign-in (no `sign_in_with_apple` in the client today).
  Not a TODO now — a precondition on that future work: *when Apple sign-in is added, forward the
  first-auth name payload* (else the name is lost forever after first auth).

---

# OFF-KEYBOARD (human / ops — not code)

- **Store submission, manual QA pass, DPIA.** (App is pre-launch.)
- **Device-verify the weakness loop on real hardware** — wrong hot-takes → mastery moves → weak
  concept → weak-first next quiz.
- **Set the account-level AI Studio spend cap** (2 min, $0) — makes the per-avatar LLM budget
  redundant.
- **Consent-evidence retention duration** — the DPO/lawyer's N years (feeds the dated purge above).
- **Prod diagnostic — email case-variant duplicates** (gates the UNIQUE-index migration, Prompt C):
  ```sql
  SELECT lower(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
  ```
  Any rows → STOP; dedup is a human decision before the migration.
- **Prod diagnostic — null-birthYear population** (sizes the complete-profile re-prompt):
  ```sql
  SELECT COUNT(*) FROM users WHERE birth_year IS NULL;
  ```
- **Prod forensics — the four zombie-recompile avatars** (the reconciler CODE is fixed; this is
  "why do these four have 0 pages", decide reaper-vs-leave per avatar; do NOT blind-recompile):
  ```sql
  SELECT kf.avatar_id,
         COUNT(*)                                            AS ready_files,
         COUNT(*) FILTER (WHERE kf.compiled_by IS NOT NULL)  AS compiled_files,
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
  `archived_pages > 0` → content existed then was archived (investigate why). `archived = 0 and
  active = 0` → compile produced nothing (extraction/relevance drop — check `extracted_chars`).
  The reconciler no longer churns on these either way.

---

# CLOSED (kept for archaeology)

- **Generation validator: keyless deterministic item** — `RulesOutputValidator.isValidModuleItem`
  (`:75`) enforces `HOT_TAKE` needs `answer_json.isTrue`; a keyless HOT_TAKE fails validation and
  never persists. Closed by the store-blockers audit (2026-07).
- **RelevanceChecker fail-open inconsistency** — `parseResponse` parse-error now returns `1.0`
  (accept), consistent with the API-failure path in `check()`. Both "checker unavailable" paths
  fail toward accepting the upload. Closed `46c5264` (2026-07).
- **OCR metering** — both `GeminiVisionOcrService:139` and `ClaudeVisionOcrService:137` record an
  `ai_usage` row (`purpose_label='ocr'`) at their HTTP seam. Closed `72a3154`.
- **Gemini failure-path metering** — the empty-text branch in both Gemini services records
  `success=false` + the billed `usageMetadata` tokens (finishReason in the purpose suffix) before
  throwing; the wiki compiler no longer records empties as `success=true`. Closed `6539b3f`.
- **Per-purpose Gemini thinking budget** — `GeminiThinkingBudgetConfig`
  (`gemini.thinking-budget.<purpose>`); extraction/classify/structured-gen purposes → 0; a purpose
  absent from the map gets provider default (thinking ON). The two reasoning evals stay unlisted
  (still OPEN — see above). Closed `6539b3f`.
- **Avatar attribution through GeminiCompletionService** — `avatarId` threaded through callers.
  Closed `78c1927`.
- **Zombie recompile reconciler (code)** — `findAvatarIdsNeedingRecompile()`'s "0 active pages"
  branch now also requires an uncompiled READY file (`compiled_by IS NULL`) — genuine work only,
  no more per-restart churn. Pinned by `ZombieRecompileReconcilerTest` (real Postgres). Closed
  `6539b3f`. (Prod forensics for the four known avatars → OFF-KEYBOARD.)
- **Item 4 parent notification** — the old claim that `signalType` was emitted at the notifier was
  WRONG; now plumbed: `ModuleProgressionService.isSelfReportOnly` derives it and
  `MilestoneNotifier.onModuleCompleted` renders "completed (self-assessed)" (no %). Pinned by
  `MilestoneNotifierTest.onModuleCompleted_selfReportOnly_rendersStateNotPercent`. Closed 2026-07.
  (The exam-readiness UI half is OPEN — see above.)
- **PROVE old-client stranding** — MOOT-at-launch: the self-assess overlay shipped in pally
  `2ad9dae` (build `1.0.1+6`, the current & only build); the app is pre-launch, so no released
  client predates the overlay. **Launch precondition:** set the server min-version floor
  (`/app/min-version`) to `1.0.1+6` at store launch so no pre-overlay internal build survives.
- **Mastery scale 0–1 vs 0–100 ("2600%")** — canonical 0–100 on `LearningModule.masteryPct`;
  clients use `masteryDisplayPct`/`masteryFraction`; web helper de-×100'd; backend clamp. Closed
  in the store-blockers pass (`8527f5e`).
- **Age fail-safe inversion (`isUnder13(null)`)** — integrated on top of Auth Hardening A and
  merged; the ConsentGuard family fails closed; full suite green with the inversion active. Closed
  `1fdad48` / `76358e0`.
- **blank-role→ADULT default retired** — `AuthController /register` returns 400 on a blank role +
  null birth year (was defaulting to age-exempt ADULT). Verified safe: memoly sends explicit
  `role:"adult"` (prod Vercel `ed6d5e0`); mobile uses `/onboard/quick` with a birth year. Pinned by
  `SignupSecurityIntegrationTest`. Closed 2026-07-09 (`95e2bf5`/`7439a15`).
