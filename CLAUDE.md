# Apalchi Backend — Claude Code Rules

> Rules file. Not a build script. Read fully before changing code.
> Product: **Apalchi** — an AI study tutor for students (B2C) and tuition centres (B2B).
> Mascot: **Mochi**. Learning loop: **LEARN → TEST → PROVE** (stage-enforced server-side).
> North star: a study partner that knows the student's OWN uploaded notes, not a generic textbook.

## Stack
- Spring Boot 3.3.4, Java 21, PostgreSQL, Flyway, Gradle (Kotlin DSL).
- Hexagonal/clean architecture: `api` (web) → `domain` (logic + ports) → `infrastructure` (adapters).
- AI: Claude Haiku (OCR/chat/quiz micro-calls), Gemini Flash (wiki compilation). Deployed on Railway.

## MANDATORY WORKFLOW (every change, no exceptions)
1. `./gradlew compileJava` — must compile.
2. `./gradlew test` — all green.
3. Only report "done" after both pass. If anything fails, fix and re-run from step 1.

## TESTING IS NOT OPTIONAL
Every new piece of code ships with tests. No "later", no "existing tests cover it".
- New use case / service / domain logic → JUnit + Mockito unit test: happy path + ≥1 failure path.
- New controller / endpoint → integration test (Testcontainers) proving status code AND JSON shape.
- Money / XP / stars / any atomic or concurrency-sensitive path → concurrency harness test (parallel
  threads) proving the invariant under contention. Mandatory, not optional.
- New code ≥90% covered. Test names describe the invariant in plain English
  (`claim_whenParentAtChildCap_returnsUpgradeRequired`, not `test1`).
- Before refactoring a controller that lacks tests, add a characterization test FIRST (capture current
  response) so the change is provably behaviour-preserving.

## ARCHITECTURE — MANDATORY
**Controllers are thin.** A `@RestController` method only: read path/body/principal → call ONE service →
wrap the result in `ApiResponse`. ZERO direct repository calls in a controller. ZERO business logic in a
controller (no loops, cascades, `ObjectMapper`, `save`/`deleteById`). Reference the clean
`api/challenge/ChallengeController`.

**Logic lives in domain services / use cases.** Single responsibility per service. A service over ~400
lines or ~8 dependencies must be split by responsibility (e.g. generation vs progression vs evaluation).

**The domain depends on nothing outward.** No imports from `infrastructure.*` or `api.*`, no
`jakarta.persistence`, no Spring web inside `domain`. Persistence is reached through a domain PORT
interface; the JPA adapter implementing it lives in `infrastructure/persistence` and maps JPA ↔ domain
types. Reference the clean `WikiRepository` port + adapter.

**JPA entities never leave `infrastructure`.** Map to domain/DTO types at the boundary.

**Constructor injection only.** Never `@Autowired` on fields.

**Authorisation goes through a guard service** (e.g. `CentreAccessService.ensureOwner(userId, orgId)`),
never re-implemented inline in handlers.

**One global exception handler** owns error → HTTP mapping. Unmapped routes return 404 (not 500).

## API & DATA CONVENTIONS
- All responses wrapped in `ApiResponse`. A refactor must NEVER change a response's status/shape/fields.
- All scheduling, caching, daily-reset, and streak logic uses timezone **Asia/Singapore** — never UTC.
- Schema changes → a NEW Flyway migration. NEVER edit a migration that has shipped.
- Secrets come from environment only. Never hardcode keys, never commit credentials.

## DON'T
- business logic in `@RestController` · direct repo calls from a controller · JPA entity escaping
  `infrastructure` · domain importing `infrastructure`/`api`/`jakarta.persistence` · field injection ·
  god service/controller (>400 lines / >8 deps) · silent failure (always structured error) · UTC for
  user-facing time · editing a shipped migration · changing an API response shape during a refactor ·
  hardcoded secrets.

## API ERROR CONTRACT
- **SSE endpoints** use `ResponseType.stream` on the Flutter client — Dio never decodes the error body
  as JSON. Write error JSON with `response.setStatus(403)` + `response.getWriter().write(json)` BEFORE
  the SSE Flux starts. Never throw inside the Flux (the client won't see it).
- **All non-SSE endpoints**: throw a mapped domain exception — the global `@ControllerAdvice` handler
  converts it to `ApiResponse` with the correct HTTP status. Never `write()` raw JSON to
  `HttpServletResponse` outside the global handler.
- **Railway idle timeout is 60 s**. Streaming endpoints must emit an SSE heartbeat or first event within
  55 s; never block the response thread past 55 s.

## DON'T (additions)
- SSE error written inside the Flux body · raw JSON to `HttpServletResponse` outside the global handler ·
  thread blocked >55 s without streaming · UTC for any user-facing day/streak/report boundary.

## Common commands
```
./gradlew compileJava      # compile
./gradlew test             # unit + integration (Testcontainers)
./gradlew bootRun          # local run
```

## Hard-won lessons (enforce these)
- **Fix the FAMILY, not the instance.** Every parser/prompt/guard fix must be applied to ALL sibling
  methods the same day, and grep for the siblings. History: the robust JSON parser lived only in the LEARN
  generator while PROVE/TEST used the fragile `extractJson` → 0 PROVE items → NO module could COMPLETE →
  every student blocked. The marking prompt was duplicated across the Gemini + Claude compilers and drifted.
- **Domain never imports `infrastructure.persistence`.** Depend on domain repository interfaces (Ports).
  Enforced by `DomainLayeringGuardTest` (allow-list only ever shrinks — never add to it).
- **A generator must never return empty on model prose/truncation.** Use `robustJsonArray`/`robustJsonObject`
  (retry + salvage) + a fallback item. Empty output that gates a state machine = a launch blocker.
- **Externalized safety gates must be MEASURED, not hidden.** If a gate (e.g. GroundednessVerifier) logs
  over its own ceiling, fix the over-firing (coverage precision / hard-fact classifier) and log a sample of
  what's flagged — never relax the ceiling to silence the warning.
- **CONSISTENCY over cleverness.** The recurring root cause of every bug here is a good pattern applied in
  one place but not its siblings. When you adopt/fix a pattern, grep all siblings AND add a guard test to
  hold the line.
