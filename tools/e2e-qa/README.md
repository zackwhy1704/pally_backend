# Apalchi prod-API QA harness (`tools/e2e-qa`)

Automates the data-layer half of the manual QA script (`apalchi-learning-loop-qa.md`)
against **live prod** — `https://pallybackend-production.up.railway.app`. There is no
staging, so safety is enforced in code, not by convention.

## Safety (enforced in `api.py`)
- **One self-registered throwaway account.** Registers a fresh 13+ student
  (`birthYear 2005` → no email-verify / no parental-consent wall) via
  `POST /api/v1/onboard/quick`. Never a real user's account. Creds are persisted
  to `.qa_state.json` (git-ignored) so `--phase day2` reuses the same account.
- **Spend guard:** ≤2 fixture compiles, ≤3 PROVE generations. Exceeding a cap
  raises `PhaseStop` (the phase ends cleanly).
- **5xx circuit breaker:** 3 consecutive 5xx on the same endpoint → `PhaseStop`.
  No retry-storming prod.
- **Railway is read-only** (logs only, via the `railway` CLI).

## Usage
```bash
# validate the call plan, fire nothing:
python3 qa_run.py --phase all --dry-run

# real runs (needs network + a fresh account):
python3 qa_run.py --phase gauntlet --report out.md   # QA-1.2 rejection gauntlet
python3 qa_run.py --phase kestrel  --report out.md   # QA-1.1,1.3-1.7,1.10,1.12-1.14,2.2,5.4,5.6
python3 qa_run.py --phase all      --report out.md   # gauntlet + kestrel
python3 qa_run.py --phase day2     --report out.md   # run TOMORROW; QA-3.4 weak-first quiz
```

Env: `QA_BASE_URL` (default prod), `QA_FIXTURES` (default `~/Downloads/Telegram Desktop`),
`QA_EMAIL`/`QA_PASSWORD` (optional — reuse an account instead of registering).

## Files
- `qa_run.py` — CLI + phase orchestration + report writer.
- `api.py` — prod client; auth, ApiResponse unwrap, spend/5xx guards, call trace.
- `rules.py` — checked-in Kestrel grading rules (LEGAL_TERMS, NUMBER_FACTS,
  BANNED_REAL_WORLD, CANARY) + the grounding/leak sweeps.
- `out.md` — generated report (QA-case table + REMAINS-MANUAL section). Committed.

## Known coverage limits (honesty)
- **QA-1.13 `promptChars>2000`** is not emitted on the Gemini happy path; the
  harness asserts the observable proxy (served PROVE items carry a real
  `targetConcept`, never `unknown:`) and greps `task=module-prove-gen` to prove
  gen fired. The literal prompt-length assert is reported `NOT COVERED`.
- **Day-2 weak-first** depends on the server-side `weakness.profile.enabled`
  Railway flag (not in `/me/flags`). If off, `WEAK_TOPIC` selectionReason is
  absent and the harness reports `INFO`, not a false PASS.
- **Home weak-concept nudge** has no dedicated API surfaced in recon → render
  layer, listed under REMAINS MANUAL.
- **Number-fact contradiction** detection is heuristic (natural-language numbers);
  tuned against false positives, flagged as best-effort.

Requires Python 3.9+ and `requests` (`pip install requests`).
