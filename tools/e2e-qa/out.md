# Apalchi prod-API QA harness — automated results

- Base: `https://pallybackend-production.up.railway.app`  ·  account: `qa-1784536758-cdefa2@qa.apalchi.local`
- Spend: compiles=1/3, prove-gens=0/3, chat-turns/last-test=2/2
- Tally: **INFO** 3, **NOT COVERED** 7, **PASS** 36

| QA case | automated check | verdict | evidence |
|---|---|---|---|
| QA-1.2 | upload empty.pdf | PASS | HTTP 422 status=422 |
| QA-1.2 | upload corrupt.pdf | PASS | HTTP 422 status=422 |
| QA-1.2 | upload encrypted.pdf | PASS | HTTP 422 status=422 |
| QA-1.2 | upload receipt_photo.jpg | PASS | HTTP 200 reason=This is a receipt from a coffee shop showing food items and prices, which is a transactional document with no educational or instructional content related to Science or any other subject. |
| QA-1.2 | upload scanned_style.pdf | PASS | HTTP 422 status=422 |
| QA-1.2 | upload wrong_format.txt | PASS | HTTP 200 reason=The content is not a PDF and contains no educational material—it is a repeated error message about file format rejection with no teachable substance. |
| QA-1.2 | throwaway avatar deleted | INFO | 0a3da1fb-3b02-4723-ba73-6137bc51e514 |
| QA-3.4 | quiz questions carry pageTitle/sourcePageSlug | PASS |  |
| QA-3.4 | weak-first WEAK_TOPIC selectionReason present | PASS | weak_first=['WEAK_TOPIC:Wind Reading', 'WEAK_TOPIC:The Stoop and Dive Card', 'WEAK_TOPIC:The Kestrel Principle & The Hover', 'WEAK_TOPIC:Perch Blocks and Glides', 'WEAK_TOPIC:The Stoop and Dive Card'] after 4 wrong-quiz  |
| QA-3.4 | seed weakness via wrong daily-quiz answers (accumulates quiz_question_results for a later weak-first assert) | PASS | submitted 5 wrong; wrong-quiz days now 5 (weak-first fires at >=2) |
| QA-3.4 | home weak_concept nudge (human label, not slug) | NOT COVERED | no dedicated home-nudge API found in recon; render-layer — MANUAL |
| PQA-0 | matured kestrel avatar reused (READY + wiki pages, no recompile) | PASS | aid=b9aff218-9b6b-4523-900c-42bc706bff26 brainState=READY pages=5 |
| PQA-A1 | fresh throwaway register issues a session token | PASS | qa-1784626570-625a8f@qa.apalchi.local token=set |
| PQA-A1 | login → GET /auth/me returns 200 profile | PASS | login=200 me.userId=set |
| PQA-A4 | profile carries displayName + defaultAnswerMode default GUIDE | PASS | displayName='QA Full A' defaultAnswerMode='GUIDE' |
| PQA-A1 | bad-password vs unknown-email → identical 401 (no enumeration delta) | PASS | bad-pw=401:'Invalid email or password' unknown=401:'Invalid email or password' |
| PQA-A1 | duplicate-email register → 409 'Email already registered' (expected) | PASS | 409 'Email already registered' |
| PQA-A1 | token refresh | NOT COVERED | no refresh endpoint exists (JWT long-lived; invalidated by a session_epoch bump) |
| PQA-A1 | revoked-token / logout | NOT COVERED | AuthController has NO logout/signout/session-invalidation route; session_epoch is bumped only by password-reset + account-deletion, neither a bearer-only self-serve op |
| PQA-T2 | PATCH answer-mode ANSWER persists (GET /me reflects it) | PASS | patch=200 defaultAnswerMode='ANSWER' |
| PQA-U3 | skipRelevance=true override ACCEPTS receipt (201, working-as-designed) | PASS | HTTP 201 {"data":{"fileId":"b6a4c2e1-0e55-461e-b89e-79d8f3819ea5","pageCount":1,"wikiPageTitles":[]… |
| PQA-U3 | railway log confirms user-override path | PASS | 09:30:32.378 [tomcat-handler-470] INFO  c.p.d.k.u.UploadFileUseCase - Skipping relevance check for fileId=ae190ebc-4729-4e1d-86ec-883afaaf2fa2 (user override) |
| PQA-U5 | scanned/no-text PDF → honest 422 bad-input (not a 5xx, not a zombie) | PASS | HTTP 422 {"error":"Couldn't read any text from this PDF. It may contain only scanned images with no… |
| PQA-L7 | flashcards carry full SRS shape (id/front/back/sourceSlug/SRS fields) | PASS | 25 cards; missing=none |
| PQA-L7 | FLASHCARDS: 7 number co-occurrence(s) flagged for manual review (advisory heuristic — ordinals/scenario numbers; concepts: Glide, Hover, Perch Block, Stoop) | INFO | …rted incorrectly. Practitioners who skip the Hover on urgent tasks redo those tasks 3 time… \| …ive Glides degrades the quality of the third Perch Block by roughly 25 percent. |
| PQA-L7 | FLASHCARDS: grounding sweep clean | PASS | 50 strings swept |
| PQA-L7 | rate OKAY advances SRS (repetitions↑ and/or nextReviewAt→forward) | PASS | reps 0→1, nextReviewAt 2026-07-21T09:36:21.150319Z→2026-07-22T09:36:34.094689566Z, rate=200 |
| PQA-W4 | quiz WEAK_TOPIC set = quiz-history slugs only (no module-PROVE-only concept leaks in) | PASS | quiz-weak=['Perch Blocks and Glides', 'The Kestrel Principle & The Hover', 'The Stoop and Dive Card', 'Wind Reading']; PROVE-only(state)=['Applying the Hover', 'Benefits of the Hover', 'Fixed Hover duration', 'Hovering b |
| PQA-G1 | quiz submit: xpEarned>0, starsEarned≥0, score consistent (base=20+4·correct pre-decay; already-taken-today ⇒ decayed, so assert >0) | PASS | score=5/5 xp=6 stars=3 level=2 |
| PQA-R3 | duplicate submit (same idempotencyKey) returns the first result — XP NOT doubled | PASS | session a7a9d781-28b6-43c2-b71c-46a746e61292==a7a9d781-28b6-43c2-b71c-46a746e61292? xp 6==6? |
| PQA-G2 | across 2 distinct submits: newLevel non-decreasing, no negative XP | PASS | level 2→2, xp2=4 |
| PQA-G1 | streak on quiz submit | NOT COVERED | streak updates on LOGIN (updateLoginStreak), not surfaced in the quiz-submit response |
| PQA-C1 | on-brain question → grounded answer referencing brain content | PASS | HTTP 200, 1093 chars: … the full answer this time. 📚---## What is a Perch Block?A **Perch Block** is a **40-minut… |
| PQA-C1 | off-brain question → honest general-knowledge answer, NOT fabricated as brain content | PASS | HTTP 200, 554 chars, deflection=True falseGrounding=False: …ence! Ask your teacher about that one 😊The **Pomodoro Technique** is a time-management met… |
| PQA-C2 | weakness-context injection in chat | INFO | no weakness-injection log line during the chat window — chat context assembly ([ChatCtx]) does NOT inject weakness pages by design (the weakness loop is the quiz/module path, not chat); absence is architecturally expecte |
| PQA-P4 | entitlement shape {isPremium,source,plan,status,trialEndsAt} present | PASS | isPremium=True source=TRIAL plan=trial status=trialing |
| PQA-P4 | free-tier-limit → 402 path | NOT COVERED | the throwaway is on a 7-day TRIAL (source=TRIAL → MAX tier → premium); the 402 free-limit path is unreachable without ageing out a trial — NOT burned deliberately |
| PQA-S2 | homework list for a NON-centre avatar → clean 200 empty list | PASS | HTTP 200 body=[] |
| PQA-S2 | homework SUBMIT | NOT COVERED | multipart + active centre-class-member only; a self-registered B2C throwaway has no centre class, so the write path can't be exercised safely |
| PQA-S1 | group create → 201 with inviteCode | PASS | HTTP 201 id=b8001c3a-cf58-40a7-9dd5-635fb2dfb945 inviteCode=ZGH3QE |
| PQA-S1 | 2nd account joins by inviteCode | PASS | HTTP 200 {"data":{"createdAt":"2026-07-21T09:36:50.897925Z","groupType":"PEER","createdBy":"b09db08… |
| PQA-S1 | group roster shows BOTH members after join | PASS | members=2 creator∈=True joiner∈=True |
| PQA-S1 | member leave → 200, roster shrinks | PASS | leave=200 joinerGone=True members=1 |
| PQA-S5 | invalid class code → clean 404 'That class code doesn't exist' | PASS | HTTP 404 "That class code doesn't exist" |
| PQA-S5 | valid class code → 200 {classId,className,organizationId,avatarId} (NOTE: mutated the throwaway's own centreId — within safety bound, flagged) | PASS | class='p4 math' org=2a738d67-53b6-4a74-a55f-d7747555dd5d avatar=85d8ef47-35e2-4929-b33a-b2318095e893 |
| PQA-O3 | AI-disclosure consent gate | NOT COVERED | consentGuard.requireAiConsent fires ONLY for under-13; a 13+ throwaway is ungated by design, and an under-13 hits the parental-consent wall (can't be self-registered + used) — the gate is unreachable on any self-register |

## REGRESSION — do the F1–F5 / W1–W2 tags still hold?

**F1/F2/F3 — rejection gauntlet (re-run live this session):** all clean — upload empty.pdf=PASS, upload corrupt.pdf=PASS, upload encrypted.pdf=PASS, upload receipt_photo.jpg=PASS, upload scanned_style.pdf=PASS, upload wrong_format.txt=PASS.

**W1/W2 — day-2 weak-first quiz (re-run live on the MATURED avatar):** WEAK_TOPIC serve = **PASS** — weak_first=['WEAK_TOPIC:Wind Reading', 'WEAK_TOPIC:The Stoop and Dive Card', 'WEAK_TOPIC:The Kestrel Principle & The Hover', 'WEAK_TOPIC:Perch Blocks and Glides', 'WEAK_TOPIC:The Stoop and Dive Card'].

**F4/F5 + kestrel L1–L5 grounding — carried forward (NOT re-executed):** phase_kestrel creates a FRESH avatar (resetting the very W2 maturity above and spending a compile), so it was deliberately not re-run. F1/F2/F3 (gauntlet) and W1/W2 (day-2) ARE freshly re-verified live this run against the current prod deploy. F4 (PROVE parse fail-open → UNGRADED), F5 (compile-status `NONE` until DONE), and the kestrel grounding sweeps (no banned real-world method / persona / rubric leak; canonical invented numbers survived; `violet anchor` canary absent) were last directly verified at deploy `1e6c991`; prod has since advanced (see /actuator/info in the header) but nothing in those paths changed. Re-verify with `--phase kestrel` when a fresh compile budget is available.

## PRODUCT FINDINGS FOR TRIAGE (STOP — do not fix product code here)

- No hard FAILs this run.
- **Flashcard grounding (intermittent, advisory):** flashcard generation is non-deterministic; a generated card was observed using the real-world term **"deep work"** (*"…secondary screens—to enable uninterrupted deep work."*). Source-verified: "deep work" is **absent** from the Kestrel PDF (7 677 chars), so the generator paraphrased an invented concept with an off-source productivity phrase. On inspection the usage is **descriptive** (focused work), not an imported method — a grounding-hygiene signal, not a clear fabrication. Decision for a human: constrain the flashcard generator to source vocabulary, or accept descriptive collisions. (May or may not reproduce on any given run.)

## REMAINS MANUAL (render / UX — not machine-verifiable here)

- **QA-1.8/1.9** — LEARN card visual rendering, Mochi placeholder art, chip layout
- **QA-1.11** — TEST answer-reveal animation / reveal timing (client render)
- **QA-1.15** — PROVE self-assess UI + comeback line render
- **QA-2.1/2.3** — revision-mode banner + visual diff of fresh questions
- **QA-3.1-3.3** — home surfaces, streak, XP toast rendering
- **QA-3.4-nudge** — home weak_concept nudge card render + human-readable label
- **QA-4.x** — upload UX: progress spinner, error banners, add-anyway dialog
- **QA-5.1-5.3,5.5** — empty-state Mochi placeholders (library/chat/teach/wiki/groups)
- **QA-6.x** — store-build behaviour, iOS price gating, deep links

## Raw call trace (trimmed)
```
{"call": "login", "status": 200, "ms": 169, "resp": "{\"data\": {\"userId\": \"b09db085-3114-4612-9c70-87b88f11d630\", \"token\": \"***\", \"isNewUser\": false, \"setupComplete\": false, \"accountType\": \"SOLO\"}, \"status\": 200}"}
{"call": "createAvatar(Gauntlet Throwaway)", "status": 201, "ms": 83, "resp": "{\"data\": {\"id\": \"0a3da1fb-3b02-4723-ba73-6137bc51e514\", \"name\": \"Gauntlet Throwaway\", \"subject\": \"SCIENCE\", \"characterType\": \"MOCHI\", \"wikiPag
{"call": "upload(empty.pdf)", "status": 422, "ms": 121, "resp": "{\"error\": \"Couldn't read any text from this PDF. It may contain only scanned images with no selectable text. Try: (1) use a text-based PDF, (2) copy-paste the text instead,
{"call": "upload(corrupt.pdf)", "status": 422, "ms": 122, "resp": "{\"error\": \"Text extraction failed: Missing root object specification in trailer.\", \"status\": 422}"}
{"call": "upload(encrypted.pdf)", "status": 422, "ms": 138, "resp": "{\"error\": \"Text extraction failed: Cannot decrypt PDF, the password is incorrect\", \"status\": 422}"}
{"call": "upload(receipt_photo.jpg)", "status": 200, "ms": 10896, "resp": "{\"data\": {\"fileId\": \"0ee327e0-c1e7-4c75-9d47-f4e37de81e18\", \"score\": 0.0, \"reason\": \"This is a receipt from a coffee shop showing food items and prices, w
{"call": "upload(scanned_style.pdf)", "status": 422, "ms": 108, "resp": "{\"error\": \"Couldn't read any text from this PDF. It may contain only scanned images with no selectable text. Try: (1) use a text-based PDF, (2) copy-paste the text 
{"call": "upload(wrong_format.txt)", "status": 200, "ms": 1362, "resp": "{\"data\": {\"fileId\": \"b4fb3b81-70b0-495d-bfd1-0a258d8e915e\", \"score\": 0.0, \"reason\": \"The content is not a PDF and contains no educational material—it is a r
{"call": "deleteAvatar", "status": 204, "ms": 50, "resp": ""}
{"call": "quiz/daily", "status": 200, "ms": 74, "resp": "{\"data\": [{\"id\": \"61257cb7-b2a9-4801-b0eb-148baad5ffd7\", \"question\": \"A practitioner schedules a Stoop during what they initially think is a tailwind hour, but while writing 
{"call": "quiz/answers(seed-wrong)", "status": 200, "ms": 153, "resp": "{\"data\": {\"sessionId\": \"cd7a29e7-954b-41fd-840b-3879727234dc\", \"score\": 0, \"total\": 5, \"xpEarned\": 2, \"starsEarned\": 1, \"levelledUp\": false, \"newLevel\
{"call": "getAvatar", "status": 200, "ms": 34, "resp": "{\"data\": {\"id\": \"b9aff218-9b6b-4523-900c-42bc706bff26\", \"name\": \"Kestrel QA\", \"subject\": \"SCIENCE\", \"characterType\": \"MOCHI\", \"wikiPageCount\": 5, \"fileCount\": 1, 
{"call": "createAvatar(PQA-U3 Throwaway)", "status": 201, "ms": 71, "resp": "{\"data\": {\"id\": \"35840753-a9a4-45e2-a823-5d78fa8c5265\", \"name\": \"PQA-U3 Throwaway\", \"subject\": \"SCIENCE\", \"characterType\": \"MOCHI\", \"wikiPageCou
{"call": "upload(receipt_photo.jpg)", "status": 201, "ms": 3811, "resp": "{\"data\": {\"fileId\": \"b6a4c2e1-0e55-461e-b89e-79d8f3819ea5\", \"pageCount\": 1, \"wikiPageTitles\": [], \"quality\": \"GOOD\", \"extractedText\": \"KOPI CORNER PT
{"call": "deleteAvatar(U3)", "status": 204, "ms": 64, "resp": ""}
{"call": "createAvatar(PQA-U5 Throwaway)", "status": 201, "ms": 81, "resp": "{\"data\": {\"id\": \"62be3e9d-c611-4534-91c0-187d7c3b6bcf\", \"name\": \"PQA-U5 Throwaway\", \"subject\": \"SCIENCE\", \"characterType\": \"MOCHI\", \"wikiPageCou
{"call": "upload(scanned_style.pdf)", "status": 422, "ms": 121, "resp": "{\"error\": \"Couldn't read any text from this PDF. It may contain only scanned images with no selectable text. Try: (1) use a text-based PDF, (2) copy-paste the text 
{"call": "deleteAvatar(U5)", "status": 204, "ms": 43, "resp": ""}
{"call": "flashcards/generate", "status": 200, "ms": 15812, "resp": "{\"data\": {\"generated\": 25, \"pageCount\": 5, \"hasWikiPages\": true, \"needsConfirmation\": false}, \"status\": 200}"}
{"call": "flashcards", "status": 200, "ms": 81, "resp": "{\"data\": [{\"id\": \"742313e2-511e-4de3-8b04-d6de75baa667\", \"front\": \"What does the Kestrel Principle state?\", \"back\": \"Hover before you dive. It's a system for managing att
{"call": "flashcards/rate", "status": 200, "ms": 78, "resp": "{\"data\": {\"id\": \"742313e2-511e-4de3-8b04-d6de75baa667\", \"front\": \"What does the Kestrel Principle state?\", \"back\": \"Hover before you dive. It's a system for managing
{"call": "quiz/daily(full)", "status": 200, "ms": 74, "resp": "{\"data\": [{\"id\": \"61257cb7-b2a9-4801-b0eb-148baad5ffd7\", \"question\": \"A practitioner schedules a Stoop during what they initially think is a tailwind hour, but while wr
{"call": "quiz/answers(K1)", "status": 200, "ms": 232, "resp": "{\"data\": {\"sessionId\": \"a7a9d781-28b6-43c2-b71c-46a746e61292\", \"score\": 5, \"total\": 5, \"xpEarned\": 6, \"starsEarned\": 3, \"levelledUp\": false, \"newLevel\": 2, \"
{"call": "quiz/answers(K1-replay)", "status": 200, "ms": 35, "resp": "{\"data\": {\"sessionId\": \"a7a9d781-28b6-43c2-b71c-46a746e61292\", \"score\": 5, \"total\": 5, \"xpEarned\": 6, \"starsEarned\": 3, \"levelledUp\": false, \"newLevel\":
{"call": "quiz/answers(K2)", "status": 200, "ms": 168, "resp": "{\"data\": {\"sessionId\": \"42410059-606c-4069-8f71-7e91e19f4dd3\", \"score\": 5, \"total\": 5, \"xpEarned\": 4, \"starsEarned\": 2, \"levelledUp\": false, \"newLevel\": 2, \"
{"call": "chat(on-brain)", "status": 200, "ms": 7593, "resp": "I see you're asking the same question twice — no problem! Let me give you the full answer this time. 📚---## What is a Perch Block?A **Perch Block** is a **40-minute** block of s
{"call": "chat(off-brain)", "status": 200, "ms": 6086, "resp": "That's a great question, but I only know about Science! Ask your teacher about that one 😊The **Pomodoro Technique** is a time-management method, not a science topic.---**I noti
{"call": "entitlement", "status": 200, "ms": 53, "resp": "{\"data\": {\"trialEndsAt\": \"2026-07-27T08:39:19.606246Z\", \"isPremium\": true, \"source\": \"TRIAL\", \"plan\": \"trial\", \"status\": \"trialing\"}, \"status\": 200}"}
{"call": "homework(list)", "status": 200, "ms": 48, "resp": "{\"data\": [], \"status\": 200}"}
{"call": "groups/create", "status": 201, "ms": 73, "resp": "{\"data\": {\"createdAt\": \"2026-07-21T09:36:50.897925365Z\", \"groupType\": \"PEER\", \"createdBy\": \"b09db085-3114-4612-9c70-87b88f11d630\", \"subject\": \"SCIENCE\", \"inviteC
{"call": "groups/detail", "status": 200, "ms": 63, "resp": "{\"data\": {\"createdAt\": \"2026-07-21T09:36:50.897925Z\", \"groupType\": \"PEER\", \"sharedNotes\": [], \"createdBy\": \"b09db085-3114-4612-9c70-87b88f11d630\", \"subject\": \"SC
{"call": "groups/detail(after leave)", "status": 200, "ms": 63, "resp": "{\"data\": {\"createdAt\": \"2026-07-21T09:36:50.897925Z\", \"groupType\": \"PEER\", \"sharedNotes\": [], \"createdBy\": \"b09db085-3114-4612-9c70-87b88f11d630\", \"su
{"call": "groups/leave(creator cleanup)", "status": 200, "ms": 41, "resp": "{\"status\": 200}"}
```