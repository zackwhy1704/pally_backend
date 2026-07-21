# Apalchi prod-API QA harness — automated results

> ## UPDATE 2026-07-21 — post-fix field validation + day-2 (deployed `1e6c991`, `profile=prod`, verified via `/actuator/info`)
>
> ### Rejection gauntlet re-run — the fix's field validation (QA-1.2). Result flipped from the findings below to clean:
> | Fixture | Pre-fix | Post-fix (`1e6c991`) | Finding closed |
> |---|---|---|---|
> | empty / corrupt / encrypted / scanned PDF | HTTP **500** | HTTP **422** | **F1** ✓ |
> | receipt_photo.jpg | 201 `GOOD` + **compiled** | **200 refused** (RelevanceWarning), **no compile** | **F2** ✓ |
> | receipt reason | cross-file "Kopi Corner" bleed | *"This is a receipt from a coffee shop…"* — its **own** file | **F3** ✓ |
> | wrong_format.txt reason | "A Kopi Corner receipt…" (wrong file) | *"…not a PDF…no educational material"* — its **own** content | **F3** ✓ |
>
> **6/6 PASS, zero generation spend** (receipt now refused *before* compile). The gauntlet phase is hereby the standing F1/F2 regression test — re-run before every store submission.
>
> ### Day-2 (QA-3.4) — weak-first quiz:
> - **Provenance:** PASS — quiz questions carry `pageTitle`/`sourcePageSlug`.
> - **Weak-first `WEAK_TOPIC` selection:** **CODE-VERIFIED, e2e NOT COVERED this run.** The flag-gated code path executes (prod log: `[Pipeline:Quiz] weak-first bias weakSlugs=0 matchedInPool=0` — it reads `weakSlugsFor` and orders the pool). It emitted no `WEAK_TOPIC` because the weakness profile is empty. **Root cause (traced to source):** the quiz's weakness signal is materialized from **quiz answer history** (`quiz_question_results` via `findTopicMastery`: `attempts>=2 AND correctRatio<0.6`), **NOT** from PROVE self-reports. Phase-3 seeded weakness via PROVE self-reports → wrong signal → profile stayed empty. This is a **harness seeding-path limitation, not a product defect** (empty-profile → default-order fallback is documented, correct behaviour).
> - **Corrected for future runs:** `--phase day2` now submits deliberately-wrong daily-quiz answers (seeds `quiz_question_results`; a normal user op, no generation spend). Weak-first is a genuinely **multi-day** feature (MIN_ATTEMPTS=2, once-per-day Asia/Singapore reset) — so a positive `WEAK_TOPIC` assertion needs ≥2 wrong-quiz days accumulated. This run seeded **day 1**; re-running `--phase day2` on ≥2 subsequent days will let weak-first fire and the assertion turn PASS.
> - **Home weak-concept nudge:** NOT COVERED (render-layer; no dedicated API — MANUAL visual check).
>
> ### Behaviour to know (NOT a bug): the `skipRelevance=true` "Add Anyway" override still lets a user force any file (incl. a receipt) past the relevance gate — deliberate user agency, correctly logged (`Skipping relevance check … (user override)`). **A forced-in receipt in future QA is override-working-as-designed, not an F2 regression.** F2 only governs the DEFAULT (non-override) path.

- **Base:** `https://pallybackend-production.up.railway.app` (live prod) · account: self-registered throwaway `qa-…@qa.apalchi.local` (13+ student, no consent wall)
- **Spend (disclosed honestly):** PROVE-gens 2/3. Compiles this run = **2** — 1 kestrel PDF (budgeted) **+ 1 incidental**: `receipt_photo.jpg` unexpectedly passed the relevance gate and auto-compiled (308 OCR chars, trivial cost). Session incl. the earlier buggy run-1 = **3 compiles = 2 kestrel-PDF (within the ≤2 fixture-PDF budget) + 1 receipt-JPG**. Flagged, not hidden.
- **Tally:** PASS 20 · FAIL 4 · INFO 6 · NOT COVERED 1
- **Run shape:** clean end-to-end (gauntlet → kestrel LEARN→TEST→PROVE→COMPLETE→revision). The 4 FAILs are one real product finding (bad PDFs → 500), not harness artifacts.

## Findings that need a human decision (real)

| # | QA case | finding | evidence |
|---|---|---|---|
| F1 | QA-1.2 | **Unreadable/corrupt/encrypted/scanned PDFs return HTTP 500**, not a 4xx. Messages are clean and explicable, but a *server-error* class for bad *user input* pollutes error monitoring and (per the gauntlet rubric) is a FAIL. Recommend mapping `UploadResult.Failure` → 422. | empty/corrupt/encrypted/scanned all `500`; e.g. `{"error":"Text extraction failed: Missing root object specification in trailer."}` |
| F2 | QA-1.2 | **Relevance gate false-accept:** `receipt_photo.jpg` (a Kopi Corner receipt) was accepted as `quality=GOOD` study material and compiled into the brain. | `HTTP 201 quality=GOOD extractedChars=308 compile=DONE` |
| F3 | QA-1.2 | **Relevance reason is hallucinated/mismatched:** `wrong_format.txt` (content: "This is not a PDF…") was *correctly* rejected (`score 0.0`) but the reason describes "**A Kopi Corner receipt is a commercial document…**" — an explanation unrelated to the actual input. Classification right, rationale fabricated. | `HTTP 200 score=0.0 reason="A Kopi Corner receipt…"` |
| F4 | QA-1.14 | **PROVE evaluator parse failure → UNGRADED (fail-open).** The self-assess evaluation returned `"Could not parse evaluation."` with `graded:false`. This is the *correct* honest-grading behaviour (no fabricated score) but the evaluator couldn't parse the model output for these items. | submit resp: `{"feedback":"Could not parse evaluation.","graded":false,"selfAssess":true,…}` |
| F5 | QA-1.1 | **Compile-status endpoint reports `state:"NONE"` ("No compile job found") for the entire compile**, flipping to `DONE` only at the end, while `avatar.wikiPageCount` climbed 0→5. Pollers relying on `compile/status` alone would see "no job"; the harness's dual-signal poll (status **and** avatar readiness) handled it. Minor API inconsistency. | ~15 polls `state:NONE` then `{"pagesCompiled":5,"state":"DONE"}` |

## Automated QA-case results

| QA case | automated check | verdict | evidence |
|---|---|---|---|
| QA-1.2 | rejection gauntlet: empty.pdf | FAIL | HTTP 500 "Couldn't read any text from this PDF…" |
| QA-1.2 | rejection gauntlet: corrupt.pdf | FAIL | HTTP 500 "Missing root object specification in trailer" |
| QA-1.2 | rejection gauntlet: encrypted.pdf | FAIL | HTTP 500 "Cannot decrypt PDF, the password is incorrect" |
| QA-1.2 | rejection gauntlet: scanned_style.pdf | FAIL | HTTP 500 "Couldn't read any text from this PDF…" |
| QA-1.2 | rejection gauntlet: receipt_photo.jpg | INFO | 201 GOOD + compiled — false-accept (F2) |
| QA-1.2 | rejection gauntlet: wrong_format.txt | INFO | 200 rejected score=0.0, reason mismatch (F3) |
| QA-1.2 | throwaway avatar deleted after gauntlet | INFO | avatar 36ff559f… → 204 |
| QA-1.1 | kestrel upload accepted | PASS | 201, pageCount=4, quality=GOOD, extractedChars=767 |
| QA-1.1 | compile reached terminal state | PASS | DONE, pagesCompiled=5/5 (F5 on status endpoint) |
| QA-5.6 | module count in 5–7 | PASS | count=5: Wind Reading, Kestrel Principle, Perch Blocks & Glides, The Stoop, Molt Reviews |
| QA-1.3 | LEARN grounding sweep clean (no banned/persona/rubric leak) | PASS | 68 strings swept |
| QA-1.4 | TEST grounding sweep clean | PASS | 58 strings swept |
| QA-1.4 | number-fact fidelity: canonical invented values present | PASS | 7/9 concepts (Perch Block 40, Glide 9, Long Glide 30, chained 3, Stoops/wk 2, Molt 20, chart 14) |
| QA-1.4 | number co-occurrences flagged for manual review (advisory heuristic — ordinals / scenario numbers like "report due in 15 minutes"; NOT contradictions) | INFO | 24 co-occurrences across Stoop/chart/Perch Block/Molt — all confirmed benign on inspection |
| QA-1.10 | TEST items strip answer key (no isTrue/answer; HOT_TAKE no reveal) — both directions | PASS | no graded keys served |
| QA-5.4 | TEST items carry provenance (sourcePageTitle/slug) | PASS | present on all items |
| QA-1.12 | stage does NOT advance on per-item submits | PASS | stageComplete=false after each per-item |
| QA-1.12 | stage advances exactly once, on end-of-stage submit | PASS | stageComplete=true, next=PROVE |
| QA-1.12 | per-item results carry honest `correct` grade | PASS | 3 graded HOT_TAKE rows (mix of correct true/false) |
| QA-1.14 | PROVE items carry targetConcept | PASS | 5/5 items |
| QA-1.14 | PROVE items carry priorScore | PASS | 2/5 (present where a prior signal exists) |
| QA-1.14 | PROVE grounding sweep clean | PASS | 60 strings swept |
| QA-1.13 | PROVE generation fired (task=module-prove-gen) | PASS | `[GeminiCompletion] task=module-prove-gen latency=2824ms chars=1760` |
| QA-1.13 | promptChars>2000 literal assert | NOT COVERED | prompt length not logged on the Gemini happy path; proxy = real targetConcept (QA-1.14 PASS) + gen-fired log above |
| QA-1.14 | PROVE self-reports recorded (seeds weakness for day-2) | PASS | 5 items → NO; seeded concepts persisted |
| QA-2.2 | revision re-start returns {stage:PROVE, revision:true} | PASS | stage=PROVE, revision=True (genuine cycle after COMPLETE) |
| QA-2.2 | revision-PROVE grounding sweep clean | PASS | 120 strings swept |

Grounding verdict: **no invented real-world method** (zero BANNED_REAL_WORLD hits across all stages), **no persona/grade leak, no rubric leak**, canonical invented numbers survived compilation, and the `violet anchor` canary did **not** surface (compiler did not over-read past 3000 chars). The Kestrel content is grounded on the uploaded notes.

## REMAINS MANUAL (render / UX / device — not machine-verifiable here)

- **QA-1.8/1.9** — LEARN card visual rendering, Mochi placeholder art, chip layout
- **QA-1.11** — TEST answer-reveal animation / reveal timing (client render)
- **QA-1.15** — PROVE self-assess UI + comeback line render
- **QA-2.1/2.3** — revision-mode banner + visual diff of fresh questions
- **QA-3.1–3.3** — home surfaces, streak, XP toast rendering
- **QA-3.4-nudge** — home weak_concept nudge card render + human-readable label (no dedicated API found in recon)
- **QA-4.x** — upload UX: progress spinner, error banners, add-anyway dialog
- **QA-5.1–5.3,5.5** — empty-state Mochi placeholders (library/chat/teach/wiki/groups) — the placeholder sweep just merged; eyeball here
- **QA-6.x** — store-build behaviour, iOS price gating, deep links

## Day-2 (run tomorrow: `--phase day2`)

Weakness was seeded on the `wind-reading-energy-management` module (concepts incl. *Matching work to wind types*, *Cost of fighting the chart*, *Willpower vs. Scheduling*). Day-2 asserts the daily quiz serves `selectionReason=WEAK_TOPIC:{concept}` matching one of these, plus `pageTitle`/`sourcePageSlug` provenance. **Precondition:** the server-side `weakness.profile.enabled` Railway flag must be ON — if off, `selectionReason` is absent and the harness reports INFO (not a false PASS).

## Trace (representative, redacted)

```
upload(empty.pdf)      -> 500  {"error":"Couldn't read any text from this PDF…"}
upload(corrupt.pdf)    -> 500  {"error":"Text extraction failed: Missing root object…"}
upload(encrypted.pdf)  -> 500  {"error":"…Cannot decrypt PDF, the password is incorrect"}
upload(receipt.jpg)    -> 201  {"quality":"GOOD","extractedChars":308}  + compile DONE   (F2)
upload(scanned.pdf)    -> 500  {"error":"Couldn't read any text from this PDF…"}
upload(wrong_format.txt)-> 200 {"score":0.0,"reason":"A Kopi Corner receipt…"}            (F3)
kestrel upload         -> 201  {"pageCount":4,"quality":"GOOD","extractedChars":767}
compile/status         -> 200  state:NONE ×~15 … then {"pagesCompiled":5,"state":"DONE"}  (F5)
modules                -> 200  5 modules
module/start LEARN → submit → start TEST → per-item AGREE ×2 (stageComplete:false)
module/submit TEST     -> 200  {"stageComplete":true, results:[correct:false/true/…]}     (QA-1.12)
module/start PROVE     -> 200  {"stage":"PROVE", items:[targetConcept,priorScore]}         (QA-1.14)
module/submit PROVE    -> 200  {"feedback":"Could not parse evaluation.","graded":false}   (F4)
self-report ×5         -> 200  {"signalType":"SELF_REPORT","recorded":true}
module/start (revision)-> 200  {"stage":"PROVE","revision":true}                           (QA-2.2)
```
