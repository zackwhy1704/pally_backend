# Deferred items — the tracked ledger

> Linked from [`CLAUDE.md`](CLAUDE.md). This is the tracked home for consciously-deferred
> gaps: things we chose not to do yet, each with a reason and **what closes it**. When you
> defer something, add it here — don't leave it "low priority" with no owner.

## How to read this file (process notes, learned the hard way)

- **Unledgered ≠ tracked.** A defect you can see on a screenshot but that has no line here
  is invisible, not "known" — file the entry the moment a gap is known, not after it's fixed.
  (The 0–1 vs 0–100 "2600% mastery" family bug sat in shipped code for weeks with no entry.)
- **An entry needs its EVIDENCE and a TRIGGER, or it gets re-litigated / rots into backlog.**
  Record the observed facts the decision rests on, not just the conclusion — a decision without
  its evidence is re-opened from intuition the first time someone finds the diff size annoying.
  (The per-user-attribution entry lists the actual executors seen at `AiUsageMeter.record(…)` —
  `ai-task-N`, `reactor-http-epoll-N`, `virtual-N`, never `tomcat-handler-N` — so the
  "just use a ThreadLocal" question is settled by evidence, not re-argued.) And name the EVENT
  that should surface it again, preferring an operationally sharp trigger over a calendar one:
  "the P3 spend cap trips and you need to know which avatar burned it" beats "post-launch".
  Without a trigger, pickup depends on someone rediscovering the entry.
- **Audits must sweep the OLD SIBLINGS of a new surface, not just the diff.** When a rule is
  locked ("a bearer token alone can never delete an account"), grep every sibling of the
  changed code and re-audit the ones the rule now implicates. (`DELETE /account/me` was
  locked while `DELETE /auth/account` — the endpoint the live client called — kept the old
  bearer-only semantics. Twice now an audit's blast radius was drawn around what changed,
  not around what the change made stale.)
- **A fix at the VISIBLE layer is not a fix at the WORK layer — and check the CALLER before
  calling a path unmetered.** Verify the behaviour beneath the label, but also don't stop at the
  wrong layer: `ClaudeApiClient`'s streaming methods carry no `record()`, yet their callers
  (`ClaudeChatProxy`, `CacheKeepAliveService`) parse the terminal SSE usage event and meter it.
  Reading only the low-level method wrongly reads "unmetered"; the meter lives at the caller,
  where the purpose is known. (A reconciliation pass first mis-called streaming "still open" for
  exactly this reason.)
- **Report structural SHAPE, not just string/line count, in a Phase 0.** A short list of short
  strings can still be a new pattern, not an application of an established recipe — string count
  is the wrong proxy for effort twice now (the zh content_language directive threading, and the
  achievement/level-reward catalog below: 31 short strings, but a NEW parallel-resource-bundle
  shape in Java, not a one-line directive-append). Say explicitly whether a task is "established
  recipe, N instances" or "new structural shape" — the second deserves its own sizing regardless
  of how small the string count reads.
- **Push at commit time.** On this repo, merge to `main` auto-deploys to prod (Railway).
  A held branch is not shipped; a "done" that isn't merged is not live.

Sections: **OPEN** (code, actionable — each needs a "closes it" line) · **OFF-KEYBOARD**
(human/ops, not code) · **CLOSED** (with commit refs, kept for archaeology).

---

# OPEN (code — actionable)

## zh audit round 4, Phase A — quick-onboard content_language (CLOSED, 2026-07-31)

**Correction to an earlier round's record:** an earlier pass in this same audit concluded
"General Mochi" was a user-typed name and not a bug — that check only ever looked at the
`create_tutor` wizard. `QuickOnboardService.java:89` (the PRIMARY signup path — the one virtually
every new B2C user goes through, not the secondary "add another tutor" flow `create_tutor` covers)
composed `subject.label() + " Mochi"` server-side, unconditionally in English, with no
`contentLanguage` parameter anywhere in `QuickOnboardRequest`. Workstream 1 (mobile content_language
gate, earlier this thread) fixed the secondary path and never reached the one that actually matters
most — this is worse than a missed string, it was silently producing English-content avatars for
most new signups since Workstream 1 shipped.

**Traced before assuming (per the instruction to check, not guess):** `Subject.label()` is
intentionally English-only — it's the canonical/backend form the client's `label_localizer.dart`'s
`localizedSubject()` resolves at DISPLAY time (the established "backend-label" pattern from PR-B).
But an avatar's default NAME is baked in at CREATE time and never re-resolved client-side (unlike
subject/level, which get resolved at render) — so composing it from the English-only `label()` was
never going to be fixed by adding contentLanguage alone; the name composition itself needed a zh
branch. Added `Subject.labelZh()` — a straight switch mirroring `label()`'s shape, values copied
VERBATIM from the client's existing `label_localizer.dart` zh strings (not re-translated, so the two
can't drift) — used only for this write-time exception; every other subject display still goes
through the client's resolver.

**Fixed:** `QuickOnboardRequest` gains `contentLanguage` (nullable — same
`SupportedLanguage.validate()` gate + null/blank→'en' fallback as `CreateAvatarRequest`/
`CreateAvatarUseCase`, mirrored exactly rather than reinvented). `QuickOnboardService.execute(...)`
composes `"数学小伴"` (no space, matching the cosmetic-name convention: subjectZh + 小伴) when
resolved language is zh, `"Maths Mochi"` unchanged when en/absent. Byte-identical-en proven by a
test pinning the exact pre-change avatar name for the no-contentLanguage call path every existing
caller still uses.

Gates: `./gradlew clean compileJava compileTestJava` (clean, not incremental) + full suite, both
green. 4 new `QuickOnboardServiceTest` cases (absent→en unchanged, explicit zh→zh name+language,
explicit en matches absent, unsupported language→400 before any persistence) + a `SubjectTest` case
pinning every `Subject` has a non-blank, non-English `labelZh()`.

**Still open, deliberately sequenced after this:** `direct_onboarding_view_model.dart`'s
`quickOnboard()` needs to actually SEND `contentLanguage` (currently doesn't send the field at all —
this backend change alone does nothing until the client sends it). See pally's `DEFERRED.md`. Also
open: `LocaleController.setLanguage()` provider-invalidation gap (Phase B, separate root cause,
separate PR) and the product decision on whether quick-onboard's own avatar-creation code path
should be removed entirely in favor of routing everyone through `create_tutor`'s wizard (which
already has this gate from Workstream 1) — DECIDED yes by the operator, scoped as its own follow-up
PR pending a funnel-impact report, not bundled here.

## i18n coverage — a third content category, named (2026-07-31)

**Context:** pally's client-side coverage guard (`test/guard/l10n_coverage_guard_test.dart`)
catches missed CLIENT strings; the `content_language`/`PromptLanguage` threading catches
teacher-material-derived AI generation. Achievement/level-reward copy was neither — a static,
backend-owned, non-AI-generated English catalog that both mechanisms structurally cannot see.
Naming it so the next audit checks for siblings of THIS shape (backend static data catalogs),
not just re-running the two mechanisms above and declaring victory.

### DONE this pass: `AchievementCatalog`/`LevelRewards` localized (both en/zh authored server-side)
`AchievementCatalog.Definition` and `LevelRewards.Reward` gained `nameZh`/`descriptionZh`/`labelZh`
fields (pre-authored, not derived — two independent fully-authored strings, not a directive-append)
resolved via a new `SupportedLanguage.resolve(en, zh, locale)` helper. Threaded through the THREE
render call sites, each using a `User`/`UserJpaEntity` ALREADY loaded for another reason (read-once,
no extra query): `AchievementController.list()`, `ProgressService.levelRoadmap()`, and
`GetProgressUseCase.execute()` → `ProgressSummary.preferredLocale` → `ProgressResponse.from()`'s
`nextUnlockLabel`. A FOURTH site was found mid-build, not in the original Phase 0 report:
`UserRepositoryAdapter.addXpAndStars`'s level-up-crossing `unlockedLabel` — fixed the same way
(`before.getPreferredLocale()`, `before` already loaded one line above). Byte-identical-en proven
by a snapshot test (the exact pre-change hardcoded literals), not a `zh == en + directive` equality
— there's no directive here, en and zh are independent maps. zh drafts logged in pally's
`lib/l10n/NEEDS_NATIVE_REVIEW.md` (new "BACKEND-OWNED" section) since that's the one artifact a
native-SG reviewer actually opens, even though the fix lives in this repo.

### Residual #1 — client-side rarity-badge resolver (deferred ON PURPOSE, not forgotten)
`Category` (STREAK/MASTERY/CURIOSITY/MILESTONE) is a code, never rendered as text — no work needed.
`Rarity` (COMMON/RARE/EPIC/LEGENDARY) **is** rendered as text: `achievements_screen.dart` does
`a.rarity.toLowerCase()` directly, no client-side resolver at all today. Confirmed via grep that
this vocabulary is SELF-CONTAINED to the achievements screen (EPIC/LEGENDARY appear nowhere else in
`lib/`) — a genuinely small, separate client PR (mirrors `localizedRarity` in `label_localizer.dart`,
4 cases). **Deliberately sequenced AFTER this backend PR lands**, so the resolver is built and
tested against real localized data instead of a guess at the shape.
**Closes it:** small pally client PR adding `localizedAchievementRarity(l, rarity)`.

### Residual #2 — `unlockedRewardLabel` has NO consumer — DECIDED: missing UI element, not vestigial
Traced the ORIGINAL commit (`799acc2`, "phase-3-backend: level-reward catalog + functional
unlocks"): its own message says "XpResult gains `unlockedRewardLabel` so the level-up overlay can
name what was earned" — this was intended from day one, not an accidental byproduct. Confirmed the
client side never got wired: `LevelUpOverlay.show(context, int newLevel)`'s signature has no
parameter slot for a label at all, and `LevelUpController.maybeCelebrate` (the SINGLE choke point
every screen calls through — quiz/chat/teach/photo) only takes `levelledUp`/`newLevel`. **This is
real, working, intended data nobody deleted-by-omission — a shipped-incomplete feature, not waste.
Do NOT delete `unlockedRewardLabel`.**
**Sizing (so it doesn't get mis-scoped like the achievement catalog was):** small, contained,
NOT a rabbit hole — `credit.unlockedRewardLabel()` sits on the exact same `XpResult`/`creditResult()`
object every caller already reads `.newLevel()`/`.levelledUp()` from (`SubmitQuizAnswersUseCase:312`,
`ChatOrchestrationService:104`, `SolvePhotoQuestionsUseCase:96`, `TeachController:91` — 4 backend
call sites), and the client has exactly ONE choke point to extend (`LevelUpController.maybeCelebrate`),
not four. The locale-aware fix at the computation site (`UserRepositoryAdapter`, Residual #1's
sibling above) already resolves the right language — this follow-up is pure plumbing + one new
UI element in `_CelebrationLayer`, not new decisions about what to say.
**CLOSED (2026-07-31, `feat/level-up-reward-label-wiring`):** all 3 steps shipped as sized.
`rewardLabel`/`unlockedRewardLabel` threaded into all 4 response producers
(`QuizResult`, `PhotoQuestionResponse`, `ChatOrchestrationService.sessionEnd`'s map,
`TeachResponse.withLevel`) from the same `XpResult`/`creditResult()` object each already read
`newLevel`/`levelledUp` from — no new lookups. Client: `LevelUpController.maybeCelebrate` +
`LevelUpOverlay.show` gained `String? rewardLabel`; `_CelebrationLayer` renders a 🎁 chip when
non-null. **CORRECTION to this entry's own sizing note:** {mascot} substitution does NOT apply
client-side — the label arrives ALREADY locale-resolved server-side (en says "Mochi" literally,
zh says "小伴" literally, both baked in by `SupportedLanguage.resolve`), so the client renders it
verbatim, exactly like `nextUnlockLabel`/`reward.label` already do on the progress/roadmap screens.
Also corrected: there are 4 BACKEND response producers but only 3 CLIENT trigger points — photo-
question's level-up does NOT skip celebration as first assumed; it feeds the SAME `pendingLevelUp`
(now `pendingRewardLabel` too) state `chat_view_model.dart`'s session-end path uses, surfaced
through `chat_screen.dart`'s one listener. Tests: byte-identical-en-path assertions carried through
(snapshot tests already covered en/zh at the catalog level); new backend tests on the 4 producers'
threading + a `TeachResponse.withLevel` unit test; new client tests on `QuizState`/`ChatState`
copyWith, `TeachEvaluation.fromJson`, and 2 widget-test files (`LevelUpOverlay`, `LevelUpController`)
proving the chip renders/doesn't render correctly including a zh-string-renders-verbatim case.
Also deleted a second dead-duplicate DTO found along the way: `api/chat/dto/PhotoQuestionResponse.java`
(same shape as the `ProgressResponse` duplicate below — zero imports anywhere).

### Housekeeping found alongside (not localization, fixed anyway — trivial + zero risk)
`src/main/java/com/pally/api/progress/dto/ProgressResponse.java` was a fully dead duplicate of the
live `domain/progress/dto/ProgressResponse.java` (identical content, zero imports anywhere) —
deleted rather than left to rot or accidentally maintained in parallel.

### CLOSED (2026-07-31, `chore/delete-dead-duplicate-dtos`): systemic `api/*/dto` dead-duplicate sweep
Two duplicate-DTO deletions in two consecutive sessions (`ProgressResponse`, `PhotoQuestionResponse`)
was the threshold this project has used all week to justify a scan (the ledger-title trap, the
branch-list staleness, the guard blind spots) — one grep across `api/*/dto/` vs `domain/*/dto/`
basenames found **twelve** more, not one: `ChatHistoryResponse`, `ChatMessageResponse`,
`FlashcardResponse`, `ParentDashboardResponse`, `QuestionAnswerDto`, `QuizQuestionResponse`,
`RateFlashcardRequest`, `ReadingPingRequest`, `SubmitAnswersRequest`, `SyncMessageDto`,
`WeeklyReportDetail`, `WeeklyReportSummary`. This reframes the earlier two as the visible edge of a
systemic leftover from whatever refactor moved DTO ownership from `api` to `domain` — not
coincidence.
**Verified before deleting (the blast-radius questions this deserved):** every one of the 12
`api/*/dto/` copies had **zero imports anywhere** in `src/main` or `src/test` (checked both `import`
statements AND bare fully-qualified references, to catch anything reflective/string-based — none
found). One internal wrinkle: `api/parent/dto/WeeklyReportDetail.java` imports nested types
(`SubjectMasteryDto`/`WeakAreaDto`) from `api/parent/dto/ParentDashboardResponse.java` — but that's a
reference from ONE dead file to ANOTHER dead file in the same cleanup, not an external dependency;
confirmed the live `domain/parent/dto/WeeklyReportDetail.java` correctly imports its own
`domain/parent/dto/ParentDashboardResponse` twin instead. 9 of the 12 were byte-identical to their
live `domain/*` counterpart; 3 (`QuizQuestionResponse`, `SubmitAnswersRequest`, `WeeklyReportDetail`)
had DIVERGED in content from their live twin — still zero-import, so no live behavioral risk from
the divergence, just further evidence these were abandoned mid-refactor rather than intentionally
duplicated.
Deleted all 12. Gates: `./gradlew clean compileJava compileTestJava` (clean, not incremental — to
rule out a stale build graph masking a missed reference) + full `test` suite, both green.

## Moderation / child-safety

### Context-blind moderation false-positives on material-grounded comprehension questions
- **What:** the chat-input moderation classifier (`ModerationService.screen`) screens the raw
  message text **in isolation** — it never sees the uploaded study material or the topic. So a
  legitimate reading-comprehension question about a CHARACTER in the text reads as the CHILD
  disclosing their own personal data, and gets HIGH-severity blocked.
- **Evidence (confirmed in prod `chat_safety_flags`, 2026-07-29):** 「小峰每天乘搭几号巴士上学？」
  ("which bus does Xiaofeng take to school?" — 小峰 is a character in the P3 华文 passage) →
  `category=PERSONAL_DATA severity=HIGH source=INPUT`, twice. Those two rows are the ONLY flags in
  the entire table — i.e. every real-world firing of this classifier so far has been this
  false-positive class. `SendMessageUseCase` blocks on `flagged() && isHighSeverity()`, so the
  child got a refusal instead of an answer. (The refusal's ENGLISH-in-a-zh-session leak is fixed
  separately, `ModerationService.buildSafeReply` now follows content_language.)
- **Why not a quick fix:** it's a prompt/context problem on a CHILD-SAFETY path, not threshold
  tuning. Candidate fixes each carry risk and need a real disclosure-vs-comprehension test set
  before shipping: (a) clarify the PERSONAL_DATA rubric — "the CHILD sharing THEIR OWN info, NOT a
  question about a person in the study material"; (b) pass the classifier minimal context (topic /
  "this is a question about uploaded material"); (c) stop hard-blocking PERSONAL_DATA at INPUT the
  way SELF_HARM is blocked. Weakening a child-safety gate on a hunch is exactly what not to do
  without measurement.
- **Closes it:** a labelled fixture set (genuine child-disclosure examples + material-grounded
  comprehension questions) that a prompt/rubric change must pass — reduce the false-positive class
  WITHOUT letting a real disclosure through. Measure both directions before/after.
- **Chinese-launch weighting:** this bites zh comprehension HARDER than en. Chinese reading passages
  are dense with named characters doing everyday things — taking buses, living in HDB blocks, visiting
  grandmothers — which is exactly the surface that reads as personal disclosure out of context. Treat
  as a **Chinese-launch precondition**, more than a general-launch one.
- **Trigger:** a student reports the tutor refusing to answer questions from their own notes; or
  the corrected-fixture E2E chat re-runs and the comprehension question is refused again.

## Extraction integrity

### Character-class loss in PDF extraction is undetected (silent, no gate)
- **What:** a PDF whose text layer uses a subset font with no usable ToUnicode map for a
  glyph class extracts that class as nothing — silently. Observed 2026-07-29 on a P3 华文
  fixture (`p3_huawen_wo_de_linli.pdf`): PDFBox extracted all CJK perfectly but dropped
  EVERY ASCII digit — `grep -o "[0-9]" <extracted>` returned nothing while CJK was intact.
  A bus number "218" vanished before it ever reached the model; the lesson still compiled
  clean and looked complete. Worse than the NUL blocker (now fixed, `335fa80`): NUL failed
  loudly with a 400; this fails silently — prices, dates, question numbers, measurements,
  page refs all disappear while the artifact reads as whole.
- **Evidence:** `OcrQualityGate` (the only extraction-quality gate) runs on **PHOTO uploads
  only** — PDFs skip it entirely (`UploadFileUseCase` bypasses it for PDF/TEXT). And it
  scores `isLetterOrDigit` in AGGREGATE (letters and digits interchangeable), so a document
  that kept all its letters but lost all its digits passes every ratio check. `extractedChars`
  / `degraded` are counts/flags, not per-class presence checks. There is no code anywhere
  that asks "should this document have had digits, and does the extracted text have any?".
- **Why not the naive fix:** "zero digits ⇒ broken" false-positives on legitimately
  digit-free prose — for a 华文 comprehension passage that is common, not exotic. Testing a
  downstream symptom mis-fires; the honest detector tests the CAUSE.
- **Closes it:** a ToUnicode-coverage check on the PDF's embedded fonts (PDFBox exposes
  `PDFont` per glyph run; a font used for rendering with no ToUnicode CMap → its glyphs are
  unreliable to extract) that, when a rendering font lacks ToUnicode, triggers the vision-OCR
  fallback (already wired for images via `ResilientOcrService`) instead of trusting the
  text layer. Design decision on the action (warn / OCR-fallback / flag `degraded`) belongs
  with this entry, not in the NUL-sanitize branch.
- **Trigger:** a student uploads a CJK worksheet and the generated quiz/lesson is missing
  every number (or a teacher reports "the AI dropped all the prices/dates"). Also re-surface
  it when the corrected-fixture E2E re-runs the "218" number-fidelity trap.

## Test harness gaps

### Large-PDF / segmentation compile fixture (budget-bounded)
- **What:** there is no test fixture that drives a real large, image-heavy PDF (or a
  compile-time-segmented file) end-to-end through the upload → OCR → compile pipeline. The
  segmented-compile-honesty work (state-aware zero-ready signals + derived
  `AvatarResponse.awaitingChapterSelection` / `compileFailureReason`) is pinned by
  *unit* tests that feed synthetic `KnowledgeFile` states — not by a real oversized document
  moving through the actual segmentation path.
- **Incident this class would catch:** a web upload of a 156-page image-heavy PDF landed the
  file in a state the compile inventory didn't count (`CompileWikiUseCase:406`
  `segmentOversizedLegacyFiles` flips the parent → SEGMENTED, children → PENDING_CHUNK **at
  compile time**, so the upload response never carried the chunks). The zero-READY branch
  mislabelled it and the scheduler flipped the brain to a **silent READY-empty** — the student
  saw an empty brain with no explanation.
- **Why deferred:** a faithful fixture needs a real multi-MB scanned PDF asset + OCR, which is
  slow and burns AI budget in CI; it must be budget-bounded (a small committed fixture that
  still exercises the compile-time segmentation branch, not the full 156-page document).
- **Closes it:** a bounded integration test that (a) seeds an oversized top-level READY file,
  (b) runs `executeBatched`, asserts the parent goes SEGMENTED + children PENDING_CHUNK, and
  (c) a subsequent `execute` returns `zero-ready-awaiting-selection` and the avatar mapper
  derives `awaitingChapterSelection=true` — reproducing the incident without a live LLM call.

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

### 2. All-HOT_TAKE TEST stage: the last item's reveal is bannerless
- **What:** the client per-item HOT_TAKE verdict fetch is SKIPPED for the LAST item of a stage
  (the no-advance invariant — a per-item submit can never be the one that completes the stage:
  completedInStage ≤ N-1 < N). So a stage's last hot-take shows its reveal with no Correct!/Not
  quite banner.
- **Why it (almost) never bites:** hot-takes sort at 100, SPOT_MISTAKE 200, CHALLENGE 300 — the
  last item is normally a CHALLENGE, so every hot-take gets its verdict. But that ordering is a
  generation-time convention, NOT enforced; a generator fallback could mint a hot-take-only TEST
  stage whose last item is then bannerless.
- **Accepted risk:** that one item is still graded correctly at the end-of-stage submit; only its
  inline banner is missing. Non-blocking.
- **Closes it:** render the last hot-take's verdict from the end-of-stage submit `results[]`
  before advancing, or add a non-advancing per-item grade endpoint.

### 3. CHALLENGE is UNGRADED by design — SPOT_MISTAKE now self-checked (feat/sm-self-check)
- **What:** of the TEST types, only HOT_TAKE has a discrete key. **SPOT_MISTAKE is now a
  self-checked SELF_REPORT signal** (student types a diagnosis → self-assesses "were you
  right?" → recorded at the 0.30 self-report trust weight, same as PROVE; `feat/sm-self-check`).
  CHALLENGE (open-ended) still has no signal — its explanation-reveal has the same hollow shape
  SM had; the same self-check treatment applies almost verbatim if SM proves out.
- **Accepted risk:** CHALLENGE TEST items still contribute no mastery signal.
- **Closes it:** apply the SPOT_MISTAKE self-check pattern to CHALLENGE (typed answer → reveal →
  self-check → SELF_REPORT), or LLM-grade both (see #5).

### 4. Exam-readiness UI label for self-report-only concepts — CLOSED (merged 2026-07-10)
`signalType` added to pally `ExamConceptMastery` + a "Self-assessed" caption on
`exam_prep_screen` (widget-test pinned). Merged to pally main (`9c2e93d`). Backend already
emits it (`ModuleExamReadinessService.java:67`); the parent-notification half was already
CLOSED. Nothing left. *(kept here briefly; belongs in CLOSED.)*

### 5. Free-text TEST answers are UNGRADED (SPOT_MISTAKE/CHALLENGE) — LLM-evaluate like PROVE?
- **What:** SPOT_MISTAKE ("I found it") and CHALLENGE (free-text) contribute no mastery
  signal (see #3). PROVE already runs `ModuleProveEvaluator.evaluateAnswer` for feedback; the
  same could grade TEST free-text answers (an `evalResult` per item) so TEST contributes a
  real (low-trust) signal instead of nothing.
- **Why deferred:** a PRODUCT + COST decision — it adds an LLM call per TEST free-text item on
  every submit (latency + spend), and the trust weighting/anti-spoof story needs design (the
  student's own text can't be a client-authoritative grade).
- **Closes it:** decide the product value vs per-submit cost; if yes, reuse the PROVE evaluator
  path with a TEST-appropriate trust weight and metering.
- **Update (feat/sm-self-check):** SPOT_MISTAKE now CAPTURES the student's typed diagnosis on
  the progress row (`responseJson`) — so the training/eval input for LLM grading is now being
  collected in the field. When this is picked up, the SM diagnosis text is already there to
  grade; only CHALLENGE would still need capture wiring.

### 10. Unify weakness inputs (module signals → weak-set)? — GATED
- **What:** the quiz-materialized weak-set (`WeaknessProfileService.weakSlugsFor`) is fed ONLY
  by quiz answer history (`WeaknessSignalRepositoryAdapter` reads `quiz_question_results`:
  `attempts>=2 AND correctRatio<0.6`). Module outcomes (TEST hot-takes, PROVE/SM self-reports)
  do NOT contribute rows to it — they drive only in-module PROVE re-targeting. So a concept a
  student first fumbles in a module TEST/SM is NOT badged by the next day's quiz until they also
  miss it in quizzes twice. The two adaptive loops (module, quiz) are connected by nothing.
- **Why deferred / GATED:** wiring module signals into the weak-set is NOT a one-liner — it is a
  slug-keyed aggregation of concept-keyed, trust-tiered signals (hot-take w1.0 / self-report
  w0.3) into an attempts/ratio model, i.e. a WEAKNESS-SEMANTICS design decision needing product
  sign-off. Module items now carry `sourcePageSlug` (933d0f4), so slug-keyed inclusion is
  mechanically possible — but the trust-weighting design and the "does a module miss deserve
  quiz-level adaptation" product call are the gate.
- **Closes it:** design the module→weak-set contribution (which signals, what weight, how they
  blend with quiz attempts/ratio) + product sign-off, then extend the signal repository.
- **Note (fix/weakset-refresh-on-quiz-submit):** the TRIGGER-TIMING gap — the quiz submit not
  refreshing its own weak-set snapshot, so weak-first read a stale set — was fixed SEPARATELY
  from this gated input question. `SubmitQuizAnswersUseCase` now calls the same guarded
  best-effort `weaknessProfileService.onMasteryUpdated` the module sites use. That changed only
  WHEN the surface refreshes (closing the staleness window), NOT WHAT feeds it. This #10 (module
  signals as an INPUT to the weak-set) remains gated and untouched.

### 6. Mastery visibly moves only at COMPLETE — TEST-completion mastery update (GATED)
- **What:** `updateMastery` runs only when a stage advances INTO COMPLETE (and on self-report),
  so a student sees mastery move only after finishing PROVE — not after TEST, even though TEST
  HOT_TAKEs are graded DETERMINISTIC and already stored. Feels unresponsive mid-module.
- **Why deferred / GATED:** this is a PROGRESSION-SEMANTICS change (when mastery is written).
  Writing mastery at TEST completion changes what a mid-module mastery number MEANS and could
  interact with the parent-notification / weakness-trigger timing. Needs an explicit decision,
  not a drive-by.
- **Closes it:** decide whether to call `updateMastery` at TEST→PROVE (blend the stored TEST
  DETERMINISTIC signal early); if so, add it behind a considered review of the downstream reads.

### 8. Revision serves OLD + NEW PROVE items (≈10) vs the "5 fresh" intent — decide
- **What:** entering revision regenerates PROVE questions, but the served set includes the
  old PROVE items alongside the fresh ones (≈10 total), while the code comment/intent was
  ~5 fresh questions. The student gets a longer-than-designed revision set.
- **Why deferred:** a content/UX decision (is a bigger pool good, or confusing?), plus a
  data question — whether old PROVE items should be retired/replaced on revision.
- **Closes it:** decide the intended revision set size; if "fresh only", retire/exclude the
  prior PROVE items when regenerating.

### 9. Revision silently UN-COMPLETES a module on tap — confirm dialog? (GATED-adjacent UX)
- **What:** tapping a COMPLETE module to revise flips its list badge COMPLETE → PROVE with
  no explanation. The user reads it as lost progress (they "finished" it; now it says PROVE).
- **Why deferred:** touches stage-semantics UX (not logic). A confirm dialog ("Revise this —
  it'll reopen at PROVE?") or a distinct "Revising" badge would set expectations.
- **Closes it:** product decision on the revision-entry affordance; add a confirm/label so the
  badge change is intentional, not a surprise. (Logic is correct — this is expectation-setting.)

### 7. Module card shows "0 prove" — cosmetic (PROVE is generated on-demand)
- **What:** the module card's item counts show 0 PROVE items because PROVE questions are
  generated adaptively on-demand (at PROVE start from TEST results), not at module creation. Reads
  as "missing content" when it's by-design.
- **Accepted risk:** cosmetic only — the module plays and completes correctly.
- **Closes it:** hide the PROVE count until generated, or label it "adaptive" instead of "0".

## Chat system-prompt assembly

### 1. Block-4 assembly is overwrite-not-merge — two builders CAN silently collide again
- **What:** `ClaudeContextAssembler.assembleSystemBlocks`/`buildCacheBlocks` builds blocks 1-4
  and returns them as a list; `SendMessageUseCase.buildBlocksWithSocraticTail` then unconditionally
  drops the LAST element of that list and replaces it with `SocraticPromptBuilder.buildBlock4(...)`'s
  own output. This is the exact mechanism that let a real, working frustration detector
  (`ClaudeContextAssembler.isFrustrationTriggered`, computing a "STUDENT SUPPORT NOTE" into what
  became that last block) get silently discarded for an unknown period before it was found and
  reconnected (`fix/reconnect-frustration-detector`, merged `307bd49`, 2026-08-04) —
  `ClaudeContextAssembler` isn't wrong to build a dynamic tail, `SocraticPromptBuilder` isn't wrong
  to need the last slot; the "last write wins" assembly model is what made two correct-in-isolation
  builders incompatible. Confirmed via source: `AssembledContext.systemPrompt()`, the field that
  would have carried the discarded content, is called from zero production code — only from tests
  that exercise `ClaudeContextAssembler` in isolation and never caught that the live path drops it.
- **Why deferred:** fixing the assembly model itself (merge blocks instead of overwrite, or give
  each builder a named, non-colliding slot) is a redesign of how the whole system prompt is
  constructed, not a rewire — out of scope for the frustration-detector fix, and the wrong size for
  a single-issue session. This is the SECOND time in this file's history a structural risk in this
  area has been named and deferred (see the frustration-detector fix itself, which named this same
  risk in its own commit message rather than attempting to fix it) — recording it here as its own
  entry so it has a real trigger instead of living only in commit prose and being rediscovered from
  scratch.
- **Accepted risk:** any future builder that computes its own version of "the last system-prompt
  block" (by whatever name) and gets merged after `SocraticPromptBuilder`'s block4 will have its
  output silently dropped, with no compile error and no test failure unless that specific test
  asserts on the actual argument values passed to the final model-facing call (as
  `SendMessageUseCaseOpenPathTest.frustratedMultiTurnHistory_escalatesEscapeFlagToBuildBlock4` now
  does for this one signal) — a new signal computed elsewhere would NOT be caught by that test.
- **Trigger — pick this up the next time, not on a calendar date:** anyone adds a new dynamic,
  per-turn system-prompt block, OR touches `buildBlocksWithSocraticTail`/`buildCacheBlocks` for any
  other reason. At that point: either (a) merge/concatenate the competing tail blocks instead of
  overwriting, or (b) give each builder a distinct, explicitly-ordered slot in the blocks list so
  "replace the last element" stops being the join mechanism.
- **Closes it:** redesign block assembly from positional "replace last element" to either a named-
  slot list or an explicit merge step, with a test that fails if any computed system-prompt content
  is silently dropped during assembly (not just a test for one specific signal).

## Retention & UX polish (post-launch)

### Weak-concept re-teach nudge — CLOSED (was blocked; product owner unblocked both)
- **What:** a Home nudge ("Struggling with {concept}? Let's review it together") when the user's
  weakness profile is non-empty, tapping into the tutor chat pre-filled to review that concept.
- **Decisions that unblocked it:** (a) NOT per-day — stateless ("show while weak-set non-empty",
  matching the sibling nudges; no persistence). (b) chat seed = **prefill, not auto-send** (`?seed=`
  on ChatRoute pre-fills the composer; the student taps send — keeps agency + doesn't fire an
  uncapped LLM call).
- **Shipped:** backend WEAK_CONCEPT nudge (stateless, flag-gated, top concept + avatarId) pally-backend
  `74b3bcb`; client nudge card + ChatRoute/ChatScreen `?seed=` prefill pally `d2c4378`.
- **What:** the streak economy is fully built (StreakService owns day-roll / freeze consumption /
  milestone grants / earned-freeze top-ups; freezes are a purchasable atomic star spend; L20+ cap
  scaling; EXTRA_FREEZE premium hook; MilestoneNotifier; client celebrates milestones + shows a
  freeze pill), but the card never SAYS what a freeze is or what milestones give. Mechanics only
  manifest across days, so a same-day tester (and a user) reads it as decorative.
- **Accepted risk:** the machinery works; only its legibility is thin — users may undervalue it.
- **Closes it:** copy pass on the streak/goal card — surface "freeze protects your streak", the
  next milestone and its reward. No logic change.

### Exam countdown + testDate→reminder integration (field retained, settings UI removed)
- **What:** the per-avatar `testDate` field is kept on the Avatar model + `/test-date` PATCH endpoint,
  but the SETTINGS editor that wrote it was removed (it was an input with no clear output — confusing
  pre-launch). NOTE for whoever revives this: `testDate` is NOT output-less — `exam_prep_screen` and
  `study_plan_screen` (both routed) READ it for a countdown / days-left. With the only writer removed,
  those screens now show empty states until a writer returns.
- **Why deferred:** the exam-countdown feature is half-built — no scheduled reminder is wired to
  `testDate` (the only zonedSchedule is the daily-quiz reminder), and no Home countdown. Shipping an
  input with no salient output reads as "broken".
- **Closes it (post-launch):** wire a testDate→reminder (reuse the daily-quiz `zonedSchedule` path) +
  a Home/hub countdown, then re-add a date input (ideally at avatar creation, not buried in settings).

### Billed-but-FAILED streaming — the narrow residual (streaming itself IS metered)
- **What:** streaming chat and keepalive ARE metered (see CLOSED). The only residual: if a chat
  stream errors/aborts BEFORE the terminal `message_delta`/`message_stop` usage event,
  `ClaudeChatProxy.parseEventWithMetrics` never fires, so the input tokens Anthropic already
  billed (carried in the earlier `message_start` usage) go unrecorded.
- **Accepted risk:** near-zero today (pre-launch, and only on a mid-stream abort). Under-counts
  the ledger by the input tokens of failed streams only.
- **Closes it:** capture `message_start` usage and, on stream error, record a `success=false`
  row with whatever usage the stream reported (same billed-but-failed rule as the unary path's
  `meterBilledFailure`).

### Per-USER cost attribution — the ledger records spend correctly, but mostly against `user_id = NULL`
- **What:** `ai_usage` totals are correct; the *attribution* columns are not. Only
  `GeminiWikiCompiler` (compile) and `CacheKeepAliveService` pass a real userId. Every Claude
  seam records `record(null, null, …)` — `ClaudeApiClient:158/239/270/396/556`,
  `ClaudeChatProxy:85` (chat, the largest single line), both OCR services. So
  `GET /api/v1/admin/ai-cost`'s per-user rollup is dominated by a `"(none)"` bucket and the
  per-user numbers it *does* show are essentially compile spend only.
- **NOT on the launch path:** the P3 spend cap needs TOTAL spend, which the ledger already gives
  correctly. This is analytics / unit-economics (cost-per-student), not a launch blocker.
- **Why deferred:** it is a signature refactor across four layers, not a local fix — 11 metering
  seams, 4 domain ports (`ChatPort`, `OcrPort`, `RelevancePort`, `QuizGeneratorPort`), ~25 main
  files and **20 test files**, incl. `IntegrationTestBase` (a shared fixture stubbing all four
  ports, so every Testcontainers test recompiles). Days-before-submission work competing with the
  visual walk / store forms / DPIA.
- **Transport decision — EXPLICIT PARAM, not a ThreadLocal (settled, don't re-litigate):** metering
  never runs on the thread that held the request context. Observed executors at `record(…)`:
  `ai-task-N` (compile pool), `reactor-http-epoll-N` (streaming chat), `ForkJoinPool.commonPool-N`
  (moderation), `virtual-N` (keepalive), `scheduling-N` (summariser) — never `tomcat-handler-N`.
  A ThreadLocal set in the controller is empty at every one of them, so it reproduces today's
  nulls with *worse* diagnosability: an invisible empty context instead of a greppable literal
  `record(null, null, …)`. A hybrid is worse still — it swaps a compiler guarantee for a rule
  ("pass it explicitly on async paths") that isn't locally decidable, since e.g.
  `CacheKeepAliveService` reads as ordinary synchronous code but runs on a scheduler's virtual
  thread. Explicit params make the compiler the enforcement mechanism: a twelfth AI call site
  cannot silently lose attribution, because it won't build.
- **Design constraint — do NOT make `AiPrincipal` a record of two nullable fields**, or the same
  ambiguity is rebuilt one layer up. `null` currently means three different things; name them:
  `AiPrincipal.of(userId, avatarId)` (attributed) · `AiPrincipal.avatarOnly(avatarId)` (compile
  paths, no user in scope) · `AiPrincipal.system(String reason)` (keepalive prewarm, scheduled
  reconciler). Carry the reason into `purpose_label` so a ledger-integrity query can separate
  *unattributable by design* from *attribution lost*. (Same lesson as the segmented-compile
  incident: the bug was distinct states collapsed into a count that didn't include them.)
- **Trigger (pick this up when either fires, not on a backlog sweep):** (a) the P3 spend cap trips
  and you need to know WHICH avatar/user burned it — the cap tells you there's a problem but not
  where, so attribution is the missing half of the alert; or (b) a centre / BIG-room conversation
  asks for cost-per-student.
- **Closes it:** thread `AiPrincipal` through the 11 seams + 4 ports, and add a guard test in the
  style of `DomainLayeringGuardTest` asserting no `AiUsageMeter.record` call site passes a
  constant-null principal.
- **Cheap partial, if a slice is ever wanted before the full pass:** `GeminiCompletionService`
  *already* takes an `avatarId`; 4 of its 10 call sites just don't pass the one already in scope
  (`TopicRouter`, `ChatSessionSummariser`, `ModuleProveEvaluator`, `ClassBriefService`). That is a
  no-port-change, no-test-churn edit — but it buys per-*avatar* on four minor paths, not
  per-*user*, and leaves chat (the big line) untouched. Low value; listed only so the option is
  costed rather than rediscovered.

### Flashcard model lever (Haiku → gemini-2.5-flash) — CODE MERGED, only the OPERATOR FLIP remains
- **Code shipped** (merged `0b55908`): `ClaudeFlashcardGenerator` routes through
  `GeminiCompletionService` behind `flashcard.use-gemini` (`FLASHCARD_USE_GEMINI`, default
  **OFF**), `gemini.thinking-budget.flashcard-gen=0` (thinking OFF — mandatory), both providers
  ledger under `flashcard-gen` for a direct before/after, + a cards/page log & 0-card WARN.
  Est. saving ~55% (~$0.23–0.42/150-page upload; the ~$1.25 was total cost, not the saving).
- **What remains (OFF-KEYBOARD, operator):** run the real 20-page evidence (the committed gate
  was 5 placeholder pages), then set `FLASHCARD_USE_GEMINI=true` on Railway (no deploy) and
  check the `flashcard-gen` ledger rows a day later. **Closes it:** that flip.

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

### "Grounded" for a teaching agent — the invented-illustration policy (product definition)
- **What:** after scoping groundedness to fact-claiming types (#1) + the heading filter (#2),
  the residual MICRO_CARD flags (~4.4% projected, 68/1548) include Mochi's **invented pedagogical
  illustrations of TRUE claims** — e.g. "5 apples or 5 metres" to explain units, tone analogies.
  Today these count as "not found in your notes" → flagged. The open question: should an invented
  illustration/analogy of a correct claim be "ungrounded"? Product read (Zack + Claude, 2026-07-13):
  **no** — the gate should verify the factual CLAIM ("water boils at 100°C"), not penalise an
  invented illustration of it; a tutor that can only repeat notes verbatim is a worse tutor.
- **Why deferred:** (a) genuinely harder to separate mechanically than headings/type-scoping —
  distinguishing "invented illustration of a true claim" from "invented FALSE claim" is subtle;
  (b) NOT blocking — after #1+#2 the rate is already well under the 20% ceiling, and groundedness
  flags are teacher-review metadata only (they never quarantine/block serving). Decide deliberately
  POST-LAUNCH with real MICRO_CARD examples in hand, not under a submission-week clock.
- **Closes it:** define "grounded" for a pedagogically-inventive agent (a DPIA-adjacent product
  call), then encode it — likely: only flag a hard fact whose NUMBER/FORMULA/named-entity is absent
  or contradicted, never a soft illustrative sentence (the hardFact split already exists to build on).

### Chapter compile — server single-flight 409 (COMPILE_IN_PROGRESS)
- **What:** `POST /avatars/{id}/files/{chunkId}/compile` on an already-in-flight chunk does NOT
  return a 409 COMPILE_IN_PROGRESS today. Deliberately deferred (2026-07-13) — a retry is already
  SAFE without it: the compile is a fast async ack (not a held LLM call), and `ChunkCompileGuard`
  is SUCCESS-BASED (counts children with `compiled_at` set), so a still-running or failed pick
  never burns allowance → retrying a running compile double-spends nothing and starts no duplicate
  job (the recompile scheduler coalesces via `inFlight`/`dirtyAgain`). The client already handles
  a 409 gracefully (`PallyError.compileInProgress` → friendly "already reading — check Library"),
  so the server can add the explicit guard later with zero client change.
- **Closes it:** a `@Transactional` check in `CompileChunkUseCase` — if the chunk is already
  PICKED/COMPILING (or the avatar's recompile is in flight), return 409 with the in-flight id in
  the body, nothing metered. Test: concurrent same-chunk → one runs, one 409s, quota + ledger
  untouched by the 409.

### Chapter compile — chunk-title quality papercut
- **What:** chapter-picker rows can render a bookmark's disclaimer paragraph (truncated) as a
  "title" instead of a real heading. Not a crash — cosmetic. The client already falls back to
  `'Chapter'` on blank, but a long non-heading string still shows.
- **Closes it:** at segmentation, prefer a real heading / outline entry; else synthesize
  "Chapter N · pages X–Y" server-side rather than passing the raw first paragraph as `chunk_title`.

### Flyway version bump for Postgres 18 (advisory)
- **What:** boot logs a Flyway "not certified for this database version" advisory on PG18.
  Advisory only — migrations still apply and run correctly.
- **Closes it:** bump Flyway (Spring BOM override or explicit `flyway-core` /
  `flyway-database-postgresql`) to a version listing PG18; re-run the Testcontainers suite.

## Grounding, provenance & trust surfacing

### Web "Generate narration" — UI already GATED OFF; only the unbuilt backend endpoint remains (not a live dead button)
- **What (verified 2026-07-15, investigation-only):** memoly's live `NarrationAction`
  (`NarrationAction.tsx:29,46` → `api.generateNarration`, `api.ts:1360-1361`) POSTs to
  `/api/v1/centre/organizations/{orgId}/classes/{classId}/modules/{moduleId}/narration/generate`
  and polls `.../narration` (`api.ts:1367`). There is **NO narration controller/mapping/service
  anywhere in `pally-backend`** — a case-insensitive grep for `narration` over `src/main/java`
  returns zero hits; the centre controllers cover staff/class/marking/assignments/submissions/
  report/challenges but not narration. So a teacher clicking "Generate narration" gets a 404
  (unmapped-route). This supersedes the old "trace narration prompt" ledger item: there is no
  prompt to trace because there is no endpoint. (404 itself is source-inferred, not runtime-run.)
- **Severity:** HIGH — a shipped, reachable teacher button that never works. Not a data/security
  issue; a broken capability with a visible affordance.
- **UI hidden (2026-07-15):** the memoly button is gated off behind `NARRATION_ENABLED`
  (`NEXT_PUBLIC_NARRATION_ENABLED`, default off) at memoly `9af92df` — no longer 404s in the
  teacher dashboard. UNHIDE (flip the flag) when the backend endpoint ships.
- **Closes it (build the endpoint):** implement `POST .../narration/generate` (+ the GET status).
  When built it MUST follow the FIX C–F conventions from the module generators — inject the
  brain (`truncate(page.getContent(), 3000)` + only-from-material grounding, FIX F), neutral
  persona + no grade-leak guard (FIX C/E), student/teacher-facing feedback not rubric (FIX D).
  Do NOT ship a title-only or ungrounded narration prompt.

### B2C verification tags are unconsumed — decide the surface + alert on verifier failures
- **What:** the groundedness/verification signal is produced but has no B2C consumer surface —
  it's effectively centre-only today. And the verifier fail-opens (a failure accepts content),
  which is only safe while failures are VISIBLE to us.
- **Closes it:** decide the consumer surface (a student-facing trust marker, OR consciously accept
  "verifier is centre-only") AND add a metric + alert on the verifier's exception/fail-open rate so
  a silent spike in fail-opens can't degrade grounding unnoticed.

### 3000-char head-truncation at the 3 compile/gen sites — measure before raising
- **What:** module generation + PROVE grounding (FIX F) + the sibling generators all
  `truncate(page.getContent(), 3000)` — a HEAD truncation. A long page's tail concepts are never
  seen by the generator, so questions/cards skew to the page's opening.
- **Why deferred:** raising the cap costs tokens on every gen; sampling (head+tail, or chunked) is
  more work. Neither is justified without knowing how many pages actually exceed 3000 chars.
- **Closes it:** measure the page-length distribution (how many pages > 3000 chars, by how much),
  then raise the cap or switch to a head+tail / sampled window where it matters.

### Provenance chips — "from {pageTitle}" on module items + quiz cards (sequence AFTER FIX F)
- **What:** the serve payload doesn't carry the source `pageTitle`/`slug`, so items can't show
  where they came from. A small tappable provenance chip on each module item + quiz card is
  high-trust ("this question is from YOUR notes on X") and cheap.
- **Why AFTER FIX F:** grounding must be real first (FIX F) — a provenance chip on ungrounded
  content would be a lie. Now that PROVE is grounded, the chip tells the truth.
- **Closes it:** add `pageTitle`/`slug` to the item serve payload + a tappable chip on the module
  item and quiz cards.

### Concept-comeback reveal line — "you got X wrong before; here it is again" (post-launch)
- **What:** when a weak concept comes back, say so — a reveal line that names the returning concept.
  Quiz first (it already has the data); TEST needs `targetConcept` continuity across stages to do
  the same, which isn't wired yet.
- **Accepted risk:** none — purely additive retention polish; design is ready.
- **Closes it (post-launch slice):** quiz reveal line first; then thread `targetConcept` continuity
  into TEST to extend it there.

## Auth hardening (branch merged — these follow-ups remain)

> `feat/auth-hardening-a` is **MERGED to main** (`1740d95`…`1fdad48`, age inversion `76358e0`).
> The branch closed signup-upsert takeover, social auto-link takeover, email normalization,
> fail-closed status, sub-keying, linking challenges, real forgot-password, birthYear
> collection, and the `isUnder13(null)` inversion. Deferred follow-ups:

- **UNIQUE `lower(email)` index upgrade — CLOSED (merged 2026-07-10, `cd13643`).** Gate cleared
  (prod dup-count 0 rows / 38 users); V121 drops V114's non-unique index and adds
  `UNIQUE (lower(email))`; case-variant 409 pinned by `UniqueEmailLowerIndexIntegrationTest`.
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

## EULA / Terms-of-Use signup gate (merged — sibling account-creation paths remain ungated)

> `feat/eula-terms-acceptance` **MERGED to main** (`bf7a770`): `QuickOnboardRequest.acceptedTerms`
> (`@AssertTrue`) gates `QuickOnboardService.execute` (the pally mobile primary signup path) and
> records acceptance via `ConsentService.recordTermsAcceptance`. `CreateAvatarUseCase` was checked
> and does NOT create accounts, so it was correctly excluded, not a missed sibling.

- **`AuthController.register` (`api/auth/AuthController.java:98-117`, calling
  `AuthService.register(...)` at `infrastructure/auth/AuthService.java:76-92`) — the memoly web
  signup path — has no `acceptedTerms` field and creates accounts with zero terms gate.**
  Deferred because gating it risked breaking memoly's existing signup UI (no checkbox/consent UI
  built there yet) mid-task, and no user-facing memoly signup flow is live/reachable today.
  **Closes it:** when memoly ships a real (non-marketing) signup flow, add `acceptedTerms` to its
  request DTO the same way, add the UI checkbox, and record consent the same way
  `QuickOnboardService` does.
- **`AuthService.signInWithSocial` (`infrastructure/auth/AuthService.java:369`, called from
  `AuthController.java:173` and `:203`) — Google/Apple social sign-in — creates accounts on
  first sign-in with no terms gate at all.** Deferred because no client (pally or memoly) calls
  this path today (per the backend CLAUDE.md deploy-verification note, social sign-in itself is
  currently fail-closed with no client integration). **Closes it:** before pally or memoly wires
  up an actual "Sign in with Google/Apple" button, add an explicit terms-acceptance step to that
  flow (a social first-auth still needs an affirmative checkbox, since Apple/Google's own consent
  screen doesn't cover Apalchi's own terms) and record it via `ConsentService`.

## Chat history IDOR — fixed; sibling missing-ownership checks found during the sweep

> `fix/chat-history-idor` fixes `GET /avatars/{avatarId}/chat/history/full`
> (`ChatController.java:219-226` → `ChatOrchestrationService.getFullHistory` → `ChatHistoryService`):
> previously took no `userId`, so any authenticated user could read another student's full raw chat
> — including messages persisted through the closed-book-refusal path, which bypass
> `ModerationService.screenInput` entirely. Fixed by mirroring the exact ownership filter already
> used by the sibling `getChatHistory` (`avatarRepository.findById(avatarId).filter(a ->
> a.getUserId().equals(userId)).orElseThrow(() -> new AvatarNotFoundException(avatarId))`). Pinned
> by `ChatOrchestrationServiceTest` (3 tests), `ChatControllerTest` (1 test), and a real end-to-end
> `ChatHistoryIdorIntegrationTest` — all three proven to fail on the pre-fix code, pass after.
> Sweeping `ChatController`/`ChatOrchestrationService` for the same pattern found two more:

- **`ChatSyncService.sync` — CLOSED (`fix/chat-sync-write-idor`).** Two distinct gaps in one
  method, both fixed: (1) the update branch (`chatRepo.existsById(dto.id())` →
  `updateFeedbackType`/`markSavedToBrain`) never checked that `dto.id()` belonged to the caller —
  fixed with a `chatRepo.existsByIdAndUserId(dto.id(), userId)` guard (the same idiom
  `ChatFeedbackService.submitFeedback` already used), rejecting (log + skip) rather than mutating;
  (2) the create branch wrote `ChatMessage.reconstitute(dto.id(), avatarId, userId, ...)` with no
  check that the path `avatarId` belonged to the caller — fixed by adding the standard
  `avatarRepository.findById(avatarId).filter(a -> a.getUserId().equals(userId)).orElseThrow(...)`
  guard to `ChatOrchestrationService.syncMessages` before delegating, same as `getFullHistory`/
  `getChatHistory`. This was upgraded from "different bug class, not a blocker" to "fix before the
  1.2 reply": a write-IDOR here means a victim's chat can be altered by another user (fabricated
  content injected, feedback tampered) — content-safety exposure in the other direction from the
  read-IDOR, not merely an economic/integrity nit. Pinned by `ChatOrchestrationServiceTest` (2 new
  tests), `ChatSyncServiceTest` (1 new test), and a real end-to-end
  `ChatSyncWriteIdorIntegrationTest` (bundles both a mutate-existing and a plant-new attempt in one
  request, asserts rejection AND that the victim's chat is byte-for-byte unchanged) — all proven to
  fail on the pre-fix code, pass after.
- **`sessionEnd` → `XpService.awardForChat` — CLOSED (`fix/session-end-xp-farming-idor`).** The
  once-per-SGT-day chat-XP cap keys on `(userId, avatarId)`, and with no ownership check anywhere
  in the call chain a user who knew other avatarIds could call `POST /avatars/{theirAvatarId}
  /chat/session-end` once per distinct foreign avatarId per day, farming +5 XP each time. Fixed by
  adding the same `avatarRepository.findById(avatarId).filter(a -> a.getUserId().equals(userId))
  .orElseThrow(...)` guard used by every sibling — chosen over re-keying the daily cap to `userId`
  alone because that alternative would have silently capped legitimate users who chat with more
  than one of their OWN avatars in a day at the same +5 total, a real product/reward-design change
  this fix wasn't asked to make. Pinned by 2 new `ChatOrchestrationServiceTest` cases, proven to
  fail on the pre-fix code (temporarily reverted), pass after. Full suite green: 1981 tests, 0
  failures.

---

# OFF-KEYBOARD (human / ops — not code)

- **⚠️ CONTENT-REAPER RE-ENABLE PRECONDITION (runbook — check the FAMILY before flipping
  `CONTENT_REAPER_DRY_RUN=false`).** The reaper is the ONLY thing in the system that creates
  non-servable rows (QUARANTINED/RETIRED) at scale, so EVERY consumer of "how many items are in
  this stage" is a potential sibling of the same invariant: *a non-servable row must not be
  treated as present.* Before re-enabling, confirm BOTH layers reason over the SERVABLE set, not
  the unfiltered count:
    - **serve layer** — `startModule`/`buildStageResponse` (empty-served → CONTENT_UPDATING). ✅ P1.
    - **advance layer** — `submitAnswers` stageComplete denominator = `countServableByModuleIdAndStage`,
      servable==0 → not complete. ✅ this fix (`fix/stage-advance-servable-denominator`).
  History: the reaper's re-flip had to be reverted TWICE — once for the serve layer (P1), once for
  the advance layer (this) — because each was gated on the previous fix in FORM but the fix didn't
  reach the sibling. The discipline: grep every `countByModuleIdAndStage` caller and classify it
  (completion → servable; existence/regen-gate/sort-order → unfiltered, with a rider comment) as a
  FAMILY before flipping, not after a student gets stuck. Still-unfixed non-stranding siblings
  (display/analytics only): `listModules` itemCounts + `CentreAnalyticsService:512` count RETIRED
  rows → cosmetically inflated counts; acceptable, not a blocker.
- **Store submission, manual QA pass, DPIA.** (App is pre-launch.)
- **Device-verify the weakness loop on real hardware** — wrong hot-takes → mastery moves → weak
  concept → weak-first next quiz.
- **Set the account-level AI Studio spend cap** (2 min, $0) — makes the per-avatar LLM budget
  redundant.
- **Consent-evidence retention duration** — the DPO/lawyer's N years (feeds the dated purge above).
- **DPIA (P6) — does parental approval's consent scope include overseas AI data transfer?** Today
  `ConsentService.PURPOSES_JSON` = `[tutoring,quiz,progress,parent_visibility]` — it does NOT include
  `AI_DATA_TRANSFER`, so `requireAiConsent` is satisfied only by the child's OWN self-grant (a
  disclosure-ack), never by the parent's approval. Post-compile-chunk-fix that self-grant is never
  load-bearing (the parental gate `requireChildDataIngressConsent` gates every ingress), so it's
  benign. But whether an under-13 should be recording ANY consent to overseas AI transfer, vs the
  parent's approval covering it, is a PDPA scope decision — NOT an engineering call. Decide: if the
  parent's consent should legally cover AI transfer, add `AI_DATA_TRANSFER` to the approval's purpose
  set and make the child self-ack redundant/removable. (This is why the "block under-13 self-grant"
  fix was NOT built — blocking it with the current scope would strand every approved under-13 at the
  AI gate. See the consent-authority pass, 2026-07-11.)
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

- **Provenance chip on TEST cards** — the ProvenanceChip ("From your notes: {title}", tap → wiki)
  now renders on HotTakeCard/SpotMistakeCard/ChallengeCard (it already shipped on PROVE + quiz).
  Serve already carried sourcePageTitle on TEST items; client model already parsed it. Silent
  degrade on old content. Closed pally `c6ff86d` (per-card render + absence tests).
- **PROVE comeback via self-assess** — the comeback line (shipped on quiz) now also renders on a
  POSITIVE PROVE self-report (YES) of a concept with served priorScore < 0.5. Render-only; the
  /self-report submission + mastery are untouched (payload-unchanged pin). Closed pally `ac76149`.
- **TEST reveal + verdict read null serve-time answerJson** — the reveal rendered blank and
  HOT_TAKE "Correct!/Not quite" degenerated to "tapped Agree" (the client `isTrue ?? true`),
  miseducating since the widget shipped. DISPLAY-only: server grading was always deterministic
  from the raw choice (`ModuleProgressionService:322-326`), so NO stored mastery was contaminated
  (Phase 0 verified). Fixed option B: `buildStageResponse` serves a field-filtered `revealJson`
  for SPOT_MISTAKE/CHALLENGE via the `pickStringFields` whitelist (leak-proof by construction;
  HOT_TAKE serves nothing — its key stays secret); the client hydrates the HOT_TAKE verdict from
  a per-item submit, SKIPPING the last item so advancement stays solely on the end-of-stage submit
  (completedInStage ≤ N-1 < N). Backend `cc5c453`, client (pally) `194f392`. Pinned by leak-tests
  t1–t4 + client verdict/failure/no-advance-invariant tests. (Residual: the all-hot-take last-item
  bannerless edge is OPEN #2 above.)
- **Generation validator: keyless deterministic item** — `RulesOutputValidator.isValidModuleItem`
  (`:75`) enforces `HOT_TAKE` needs `answer_json.isTrue`; a keyless HOT_TAKE fails validation and
  never persists. Closed by the store-blockers audit (2026-07).
- **RelevanceChecker fail-open inconsistency** — `parseResponse` parse-error now returns `1.0`
  (accept), consistent with the API-failure path in `check()`. Both "checker unavailable" paths
  fail toward accepting the upload. Closed `46c5264` (2026-07).
- **OCR metering** — both `GeminiVisionOcrService:139` and `ClaudeVisionOcrService:137` record an
  `ai_usage` row (`purpose_label='ocr'`) at their HTTP seam. Closed `72a3154`.
- **Streaming chat + keepalive metering** — metered at the CALLER (not inside `ClaudeApiClient`):
  `ClaudeChatProxy.parseEventWithMetrics` extracts the terminal `message_delta`/`message_stop`
  usage (`CacheMetrics`) and records a `"chat"` row with real input/output + cache-adjusted
  tokens; `CacheKeepAliveService` records an estimated `"cache-keepalive"` row. Closed `8455952`.
  (Prod `ai_usage` shows no `chat`/`ocr`/`cache-keepalive` rows yet only because there's been no
  such traffic since 2026-07-08 on the deployed build — verified the code path at source; the
  ledger will populate with traffic. The narrow billed-but-FAILED-stream residual is OPEN above.)
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
