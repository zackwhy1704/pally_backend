# Apalchi — Consolidated Production-Readiness QA Checklist

**Scope:** Web (`memoly`) + Mobile (`pally`) + Backend (`pally_backend`).
**Priority:** P0 = launch blocker · P1 = must-fix-soon · P2 = polish.
**Result:** Pass / Fail / Blocked / N-A. Log defects with ID + screenshot + device/build.
**⚑ tags** = static code-audit findings (see §0). ✅ FIXED rows are already closed in code — re-verify, don't re-discover.

---

## §0 — STATIC CODE-AUDIT STATUS

### ✅ FIXED IN CODE (verify at runtime, then mark Pass)
| CA | What was wrong | Fix commit |
|----|----|----|
| **CA-1** | Birth year optional in BOTH signup flows → server never learned under-13 | `pally` 4aa9342 (now required; tested) |
| **CA-2** | Server-side AI consent enforced on only 2 of ~10 AI endpoints | `pally_backend` 0fffe2e (`ConsentGuard.requireAiAllowed` applied to photo-question, wiki compile/recompile, modules gen/start/submit/narration, quiz-daily, flashcard-gen, teach-Mochi) |
| **CA-4** | Content-review cross-tenant: any staff could read/edit/**publish** another centre's drafts | `pally_backend` b57a002 (scoped to caller's class) |
| **CA-5** | Module IDOR: any user could read/mutate another's module by id | `pally_backend` cdddebe (owns-avatar OR active class member) |
| **CA-6** | Flashcard-rate IDOR: any user could corrupt anyone's SM-2 schedule | `pally_backend` 308a55f (ownership check) |
| **CA-7** | Chat-feedback IDOR: any user could set feedback/SAVE_TO_BRAIN on any message | `pally_backend` 308a55f (existsByIdAndUserId) |
| **CA-13** | iOS Info.plist missing FaceID + notification keys | `pally` 4aa9342 |

### ✅ VERIFIED GOOD (low risk — spot-check)
- Centre student → PRO/Haiku, never Sonnet (`PremiumService.java:289`, `ModelRouter.java:80-83`).
- Lapsed-org downgrade for owner+staff+students (`OrgSubscriptionService.isEntitled():94-106`).
- Staff vs student counting (role-filtered).
- JWT signature+expiry enforced (`JwtService.java:73-78`); admin `/admin/**` requires ADMIN (`SecurityConfig.java:70`).
- Timezone SGT for streaks/caps/goals; client does no device-local day math.
- iOS brand icon present; display name "Apalchi".

### 🟠 STILL OPEN
| CA | Sev | Status |
|----|----|----|
| **CA-3** | P0 | Partially mooted by CA-1 for new users. Remaining: "unknown-age treated as 13+" + guards fail-open on user-load error. **Policy decision** (stricter could lock out existing adults) before code change. |
| **CA-8 / CA-9** | P1 | **Config:** confirm `GOOGLE_CLIENT_IDS` + `JWT_SECRET` set in Railway (fail-open auth otherwise). |
| **CA-10** | P1 | **Config:** photo-vision model routing not centre-bounded; only matters if `CLAUDE_VISION_*` set to a Sonnet model. |
| **CA-11** | P2 | `/actuator/prometheus` public (`SecurityConfig.java:56`). **Infra decision** — removing public access breaks any external Prometheus scraper (there's a CORS rule for `/actuator/**`); protect via network policy / scrape credential instead. |
| **CA-14** | P0 | APNs `aps-environment=development` → flip to `production` in release build/signing config. |
| **CA-15** | P0 | Consumer iOS subscriptions via external Stripe (Apple 3.1.1 rejection risk) — IAP vs web-only decision. |
| **CA-16** | P1 | No forced-update/min-version gate. |

---

## Test accounts to prepare first
- **Web:** super-admin, centre **owner**, **teacher** (invited non-owner), pending invite token, parent (consent link), external reviewer (review link), logged-out browser.
- **Mobile:** under-13 child (birth year <2013 **and** one who tries to skip it — confirm CA-1 blocks), 13–17 minor, 18–25 adult; one each Spark / trial / Pro / Max / Family parent+child / centre-joined student / staff-admin. iOS + Android real device; one SE-class screen; one tablet.
- **Pre-seeded demo:** owner `demo.centre.0623@apalchi.test` / `ApalchiDemo2026!`; admin `admin.test.0623@apalchi.test` / `ApalchiAdmin2026!` (needs `UPDATE users SET role='ADMIN' …`).

---

## PART 1 — WEB (memoly)

### W-A · Anonymous / marketing
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-A01 | Load `/` | Renders, no console errors | P1 | |
| W-A02 | Load `/pricing` | B2B tiers; prices from Stripe, not hardcoded | P0 | |
| W-A03 | Load `/demo` | Renders; demo data labelled | P1 | |
| W-A04 | Load `/get-the-app` | Store links live | P1 | |
| W-A05 | Every nav/footer link | No 404s | P2 | |
| W-A06 | `/dashboard` logged out | Redirect to login, no flash | P0 | |
| W-A07 | `/admin` logged out | Redirect, no leak | P0 | |
| W-A08 | 375px viewport | Responsive | P1 | |
| W-A10 | View source/network | No secrets/tokens | P0 | ⚑ CA-11 prometheus exposure |

### W-B · Auth & onboarding
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-B01 | Sign up valid | Account created | P0 | |
| W-B02 | Weak password | Inline validation | P1 | |
| W-B03 | Email in use | Clear error, no dup | P0 | |
| W-B04 | Login valid | Routed by role | P0 | |
| W-B05 | Wrong password | Clear error | P0 | |
| W-B06 | Click Google | Picker, no client_id 400 | P0 | |
| W-B07 | Complete Google | No 401 | P0 | ⚑ CA-8: confirm GOOGLE_CLIENT_IDS in Railway |
| W-B08 | Client-id unset | Button hidden, not 400 | P1 | ✅ guard shipped |
| W-B09 | Logout | Cleared; back-btn no restore | P0 | |
| W-B10 | Force 401 | Redirect, no loop | P0 | |
| W-B11 | Refresh | Stays logged in | P1 | |
| W-B13 | Invite logged out | Prompts auth, binds | P0 | |
| W-B14 | Used/expired invite | Clear error | P0 | |
| W-B15 | Accept teacher invite | Correct org/class/role | P0 | |
| W-B17 | Inspect storage | No PII in localStorage | P1 | |
| W-B18 | 5× fast login | Rate-limited | P1 | |

### W-C · Centre OWNER
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-C01 | `/dashboard` | KPIs or empty state, no crash | P0 | |
| W-C03 | Create class | In list | P0 | |
| W-C05 | Delete class | Confirm; removes + memberships | P0 | |
| W-C07 | Class 0 students | Heatmap empty, no crash | P0 | |
| W-C09 | Invite teacher | Token generated | P0 | |
| W-C10 | Teachers tab 0 teachers | No crash (`['me']` fix) | P0 | |
| W-C11 | Remove teacher | Loses access | P0 | |
| W-C14 | Upload content | Progress + success | P0 | |
| W-C20 | `/account/billing` | Plan/seats/invoices | P0 | |
| W-C21 | Checkout/upgrade | Stripe opens, returns | P0 | ⚑ CA-15 (web OK; no consumer iOS path) |
| W-C22 | Cancel/downgrade | No mid-cycle lockout | P0 | |
| W-C23 | Teacher opens billing | Blocked | P0 | |
| W-C25 | Toggle theme | Persists; themed | P1 | |
| W-C28 | Offline API | Error + retry, no spinner | P0 | |

### W-D · TEACHER (scoped)
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-D01 | Login | Scoped to their classes | P0 | |
| W-D02 | View classes | Only assigned | P0 | |
| W-D03 | Owner-only action | Blocked server-side | P0 | |
| W-D06 | Other teacher's class by URL | 403, no leak | P0 | ✅ CA-4 (content-review scoped) |
| W-D08 | Student in other class by URL | Blocked | P0 | ✅ CA-4/5 |
| W-D11 | Lapsed org | Read-only/grace | P1 | |
| W-D12 | admin↔dashboard | No cross-routing | P0 | |

### W-E · SUPER-ADMIN
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-E01 | `/admin` | Loads | P0 | |
| W-E02 | Non-admin `/admin` | Blocked | P0 | ✅ hasRole ADMIN |
| W-E03 | `/admin/centres` | Lists orgs; error+retry | P1 | |
| W-E04 | `/admin/users` search | Server-side search correct | P1 | |
| W-E10 | `/admin/safety` | Queue loads | P0 | |
| W-E11 | Action flagged item | State + audit | P0 | |
| W-E16 | Destructive action | Confirm + audit | P0 | |
| W-E18 | Direct admin API w/o role | 403 | P0 | ✅ SecurityConfig:70 |

### W-F · PARENT (consent)
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-F01 | `/consent/approve` | Child/scope/disclosure clear | P0 | |
| W-F02 | Approve | Child unblocked | P0 | |
| W-F03 | Decline/close | Child stays gated | P0 | |
| W-F04 | Expired link | Clear error | P1 | |
| W-F08 | Approve → audit | Timestamp + identity | P1 | |

### W-G · External REVIEWER
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-G01 | `/review/[token]` | Loads w/o login | P1 | |
| W-G02 | Submit feedback | Saved | P1 | |
| W-G04 | Reach dashboard/admin | No escalation | P0 | |
| W-G07 | XSS in feedback | Sanitized | P0 | |

### W-H · Web cross-cutting
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| W-H01 | Forge JWT | Rejected | P0 | ✅ / ⚑ CA-9 confirm JWT_SECRET |
| W-H02 | Teacher → owner API | 403 | P0 | |
| W-H03 | Tamper org/class IDs | No cross-tenant | P0 | ✅ CA-4/5/6/7 fixed — re-verify |
| W-H04 | SQL/script inputs | Sanitized | P0 | |
| W-H05 | 7 paginated pages | No `.map` of undefined | P0 | |
| W-H06 | 320–375px | No overflow | P1 | |
| W-H11 | Force render error | Boundary catches | P0 | |
| W-H17 | Double-click submit | No dup writes | P1 | |
| W-H20 | Console walkthrough | Zero errors | P1 | |

---

## PART 2 — MOBILE (pally)

### M-A · Onboarding & AGE-GATE (PDPA)
| ID | Age | Steps | Expected | Pri | Result |
|----|----|----|----|----|----|
| M-A01 | New | First launch | No stock Flutter assets/icon | P0 | ✅ brand icon |
| M-A02 | New | Reach age collection | Birthday collected before AI | P0 | ✅ CA-1 (now required) |
| M-A03 | <13 | Enter <13 birthday | Routed to parental gate, AI blocked | P0 | ✅ CA-1 |
| M-A04 | <13 | No parent consent | Cannot use AI; clear blocked | P0 | ✅ CA-2 (all AI endpoints gated server-side) |
| M-A05 | <13 | Parent approves | Child unblocked | P0 | |
| M-A06 | 13–17 | Minor birthday | AI-disclosure shown | P0 | |
| M-A08 | Any | AI-disclosure screen | Acknowledged; not inert | P0 | ✅ CA-2 |
| M-A09 | Any | Decline disclosure | Cannot use AI | P0 | ✅ CA-2 (server-enforced on all paths) |
| M-A10 | Any | Reopen after consent | Remembered | P1 | |
| M-A11 | Any | Implausible birthday | Validation rejects | P1 | |

### M-B · Auth
| ID | User | Steps | Expected | Pri | Result |
|----|----|----|----|----|----|
| M-B01 | New | Sign up | Created | P0 | |
| M-B02 | Existing | Login | Lands home | P0 | |
| M-B05 | FaceID | Enable biometric | NSFaceID prompt; works | P1 | ✅ CA-13 (key added) |
| M-B07 | FaceID denied | Decline | Falls back, no crash | P0 | |
| M-B09 | Staff/admin | Login on mobile | Not force-logged-out | P0 | ✅ centre-block removed |
| M-B10 | Any | Token expiry | Silent refresh / clean re-login | P0 | |

### M-C · FREE (Spark)
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-C01 | Entitlement | Free; Haiku | P0 | ✅ audit |
| M-C03 | Exceed cap | Paywall, no calls | P0 | |
| M-C04 | Reset next day | SGT midnight | P0 | ✅ audit |
| M-C06 | Paywall prices | Stripe, SGD | P0 | |
| M-C09 | Upgrade CTA | Correct checkout | P0 | ⚑ CA-15 (iOS IAP) |

### M-D · TRIAL
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-D01 | Start trial (no card) | Max unlocked | P0 | |
| M-D05 | Use Sonnet features | Available | P0 | |
| M-D06 | Day-8 open | Expired → Spark | P0 | |
| M-D08 | Convert to paid | Restores Max | P0 | ⚑ CA-15 |
| M-D09 | Second trial | Blocked | P1 | |

### M-E · PAID (Pro/Max/Family)
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-E01 | Pro entitlement | Haiku, no Sonnet | P0 | ✅ |
| M-E03 | Max entitlement | Incl. Sonnet | P0 | |
| M-E04 | Model routing | Complex→Sonnet | P1 | ✅ chat / ⚑ CA-10 photo-vision |
| M-E06 | Restore purchases | After reinstall | P0 | |
| M-E09 | Lapsed payment | Graceful downgrade | P0 | |
| M-E11 | Stripe checkout external | Returns via deep link | P0 | ⚑ **CA-15 — Apple 3.1.1** |
| M-E12 | Pricing matches env | No hardcoded prices | P0 | |

### M-F · FAMILY
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-F01 | Parent home | Children + reports | P0 | |
| M-F02 | Add child | Created/linked | P0 | |
| M-F03 | <13 child added | Consent via family link | P0 | |
| M-F11 | Child can't see siblings | Enforced | P0 | |

### M-G · CENTRE student
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-G01 | Join via code | Correct class/org | P0 | |
| M-G02 | Invalid code | Clear error | P0 | |
| M-G03 | Entitlement | Pro/Haiku, never Max/Sonnet | P0 | ✅ audit |
| M-G04 | Attempt Max/Sonnet | Gated | P0 | ✅ chat / ⚑ CA-10 |
| M-G06 | Org lapses | No Max leak | P0 | ✅ audit |
| M-G08 | Roster/heatmap | Counted as STUDENT | P1 | ✅ |
| M-G10 | Assignments | Received/completable | P1 | |

### M-H · Core study
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-H01 | Chat/tutor | Responds; Mochi present | P0 | |
| M-H02 | Ask question | Grounded in own notes | P0 | ✅ chat consent-gated |
| M-H05 | Upload notes | Progress shown | P0 | ✅ upload consent-gated |
| M-H06 | Photo question | Camera, answers | P0 | ✅ CA-2 consent-gated |
| M-H08 | View wiki | Renders from notes | P0 | ✅ CA-2 (compile gated) |
| M-H10 | Generate flashcards | Created | P1 | ✅ CA-2 |
| M-H11 | Review flashcards (SM-2) | Schedule advances | P1 | ✅ CA-6 (rate ownership) |
| M-H12 | Quiz | Renders, scoring | P1 | ✅ CA-2 (daily-quiz gated) |
| M-H15 | Modules player | Progress saved | P1 | ✅ CA-5 (access) + CA-2 |
| M-H16 | Progress screen | No brain-map leftover | P1 | ✅ removed |
| M-H21 | Teach Mochi | Works | P2 | ✅ CA-2 |
| M-H30 | Cost guardrails | Routing respects tier | P1 | ✅ chat / ⚑ CA-10 |

### M-I · Engagement
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-I01 | Streak daily | SGT midnight | P0 | ✅ |
| M-I05 | Open-box/odds | No minor gambling | P0 | |
| M-I14 | Minor shop exposure | No dark patterns | P0 | |
| M-I15 | Notifications fire | FCM/APNs, not 3am | P1 | ⚑ CA-14 (APNs dev env) |

### M-J · Permissions & device
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-J01 | iOS camera | Correct string | P0 | ✅ |
| M-J03 | iOS notifications | Token in Firebase | P0 | ⚑ **CA-14** (flip to production) |
| M-J04 | iOS FaceID | Works | P1 | ✅ CA-13 (key added) — confirm on device |
| M-J05 | Deny each permission | Graceful | P0 | |
| M-J07 | iOS app icon | Apalchi brand | P0 | ✅ |
| M-J08 | iOS display name | "Apalchi" | P1 | ✅ |

### M-K · Cross-cutting mobile
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| M-K01 | iOS+Android same flow | Parity, no crash | P0 | |
| M-K10 | Account deletion `DELETE /me` | Removes data per PDPA | P0 | |
| M-K11 | Data export (DSAR) | Supported | P1 | |
| M-K12 | Timezone travel | SG-anchored | P1 | ✅ |

---

## PART 3 — BACKEND / CROSS-CUTTING (X)

### X-A · Entitlement & billing
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| X-A01 | Stripe webhook success | Entitlement granted | P0 | |
| X-A02 | Webhook fail/refund | Revoked | P0 | |
| X-A03 | Lapsed centre org | All 3 roles downgraded | P0 | ✅ audit — confirm runtime |
| X-A04 | Centre student vs Max | Haiku-only | P0 | ✅ chat / ⚑ CA-10 |
| X-A05 | Webhook replay | Idempotent | P1 | |
| X-A06 | Caps + streaks | SGT | P0 | ✅ |
| X-A08 | Price source | Stripe/env only | P0 | |
| X-A09 | Seat/class limits | Server-enforced | P0 | |

### X-B · Security, authz, PDPA
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| X-B01 | Forged JWT | Rejected | P0 | ✅ / ⚑ CA-9 |
| X-B02 | Expired JWT | 401 | P0 | ✅ |
| X-B03 | Cross-tenant A→B | 403/404, no data | P0 | ✅ CA-4/5/6/7 fixed — re-verify all |
| X-B04 | Role escalation → admin | 403 | P0 | ✅ |
| X-B05 | Hammer endpoints | Rate-limited | P1 | |
| X-B07 | `DELETE /me` | Cascades; PII removed | P0 | |
| X-B08 | Under-13 records | Consent before processing | P0 | ✅ CA-2 (server-enforced) |
| X-B11 | Malicious file upload | Validated server-side | P0 | ⚑ image moderation (Y-F) |
| X-B12 | Secrets | Env only | P0 | ✅ / ⚑ CA-8/9 env must be set |
| X-B15 | Static authz audit | Every `/{id}` scoped | P0 | ✅ DONE (CA-4/5/6/7) |

### X-C · AI cost & safety
| ID | Steps | Expected | Pri | Result |
|----|----|----|----|----|
| X-C02 | Centre student any AI | Never Sonnet | P0 | ✅ chat / ⚑ CA-10 |
| X-C03 | Flagged content | Enters `/admin/safety` | P0 | |
| X-C04 | Harmful prompt | Moderated, logged | P0 | |
| X-C05 | Minor + AI | Age-appropriate | P0 | ✅ CA-2 consent |
| X-C07 | `/cost-summary` | Accurate per-org | P1 | |

---

## PART 4 — NEW LAUNCH GATES (Y)

### Y-A · App Store / Play submission
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-A01 | IAP vs external pay | Apple IAP, OR no in-app upgrade CTA | P0 | ⚑ **CA-15 unresolved** |
| Y-A02 | Apple age rating | Correct for AI chat + UGC | P0 | |
| Y-A03 | Apple App Privacy labels | Accurate | P0 | |
| Y-A04 | Play Data Safety form | Matches flows | P0 | |
| Y-A05 | Kids/Families policy | Apple Kids + Google Families | P0 | |
| Y-A06 | Privacy Policy + ToS URLs | Live + linked | P0 | |
| Y-A07 | App Review demo account | Working + notes | P1 | |

### Y-B · Payments integrity
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-B01 | Stripe webhook signature | Forged rejected | P0 | |
| Y-B02 | IAP receipt validation | Server-side | P0 | |
| Y-B03 | Currency | SGD, no USD leak | P0 | |
| Y-B04 | Refund → revoke | Reaches mobile | P0 | |

### Y-C · Push / APNs
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-C01 | APNs `.p8` in Firebase | Uploaded | P0 | |
| Y-C02 | `aps-environment=production` | Release build | P0 | ⚑ **CA-14** |
| Y-C03 | Real-device push | Via `/admin/smoke/push` | P0 | |

### Y-D · Observability
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-D01 | Sentry prod errors | Web+mobile+backend | P0 | |
| Y-D03 | Uptime + alerting | Pages on error spike | P0 | |
| Y-D04 | No PII in errors | Scrubbed | P1 | |

### Y-E · AI cost runaway
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-E01 | Per-user/org spend cap | Hard server cap | P0 | |
| Y-E02 | Cost-spike alert | Fires | P0 | |
| Y-E03 | Remote AI kill switch | Disable in incident | P1 | |

### Y-F · Content safety at scale
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-F01 | Uploaded image moderation | Photo-question images scanned | P0 | |
| Y-F02 | CSAM detection/reporting | Legal pathway | P0 | |

### Y-G · Data lifecycle & DR
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-G01 | Postgres backups on | Verified in Railway | P0 | |
| Y-G02 | Restore drill | A backup restores | P0 | |
| Y-G03 | PDPA retention/purge | Per policy | P1 | |

### Y-H · Release engineering
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-H01 | Forced-update gate | Min client version | P1 | ⚑ CA-16 (missing) |
| Y-H02 | Flyway on prod-like data | Clean; rollback plan | P0 | |
| Y-H04 | CI gates | Tests on PR | P1 | |

### Y-I · Email & recovery
| ID | Check | Expected | Pri | Result |
|----|----|----|----|----|
| Y-I01 | SPF/DKIM/DMARC | Not spam (`/admin/smoke/email`) | P0 | |
| Y-I02 | Forgot-password | Single-use link | P0 | |
| Y-I03 | Login lockout | After N failures | P1 | |

---

## GO / NO-GO
**No-Go if any P0 fails.** The two existential clusters — age-gate/consent (CA-1/2) and cross-tenant/IDOR
(CA-4/5/6/7) — are **fixed in code**; re-verify at runtime. Remaining hard gates before launch:
**CA-15** (Apple IAP decision), **CA-14** (APNs production), **CA-8/CA-9** (Railway env vars),
plus all other P0s. Then owner sign-off on P1; P2 as fast-follow.
