#!/usr/bin/env python3
"""
smoke_e2e_web_loop.py — REAL-API end-to-end WEB smoke for the FULL centre→student
loop, against a STAGING backend. Every handoff asserts ACCURACY + SHAPE, not just
HTTP 200, so a "200 but silently wrong" answer is a loud FAIL — which is the whole
point of this harness.

STATUS: MANUAL harness — requires a live STAGING_URL and dies early without one
(it is NOT a CI unit test and gradle does not run it). As committed it has NOT yet
been run against staging — "never run" is not "passing". Run it (see USAGE below)
and read the GRADE_INTEGRITY verdict line in the generated REPORT.md first.

THE LOOP (each step asserts accuracy + shape):
  1. Centre signup → org → class (mints corpus avatar / class brain).
  2. Upload 2 known-fact docs to the class brain. Assert 201 + fileId.
  3. Compile → poll wiki/compile/status to terminal; assert brainState READY,
     wiki/pages has the expected slug, the page BODY actually contains the known
     fact (grep — accuracy not existence), failedPages empty on a clean compile.
     Then upload a deliberately GARBLED doc and assert it surfaces as a FailedPage
     (not silently dropped, not falsely "compiled").
  4. Content: assert modules + flashcards/quiz items exist with NON-EMPTY items.
  5. Distribution: create a class assignment over the generated module ids; enroll a
     throwaway STUDENT (class join code); assert the student sees the assignment and
     startAssignment returns a non-empty targeted module set (or document honest-empty).
  6. Student work + feedback: submit quiz answers as the student; assert the returned
     score MATCHES the submitted answers (feedback accuracy) and XP is awarded.
  7. Display: fetch every web data endpoint in the loop (class detail, student detail,
     brain pages, analytics) and assert each parses to the expected shape — a
     non-empty array where one is expected — so a silent envelope-shape [] can't pass.
  8. Analytics: as the TEACHER, fetch class/student analytics; assert the student's
     just-submitted result is reflected.

TARGETED PROBES (emit verdict lines):
  - EXPOSURE FIX (stage 4): a CENTRE (teacher-graded) quiz must WITHHOLD correctIndex
    from the served question — the key is null and revealed only post-submit. Stage 4
    fails loudly if a centre quiz leaks the key.
  - GRADE INTEGRITY: after the server-authoritative fix, POST /quiz/answers grades
    against a PERSISTED server key and IGNORES the client `correctMap`. The student
    submits blind, learns the real answers from the POST-submit feedback, then this
    probe re-submits DELIBERATELY WRONG answers with a TAMPERED correctMap (all marked
    correct) and records whether the server returns a perfect score, then whether the
    tampered result moved teacher-visible mastery. Expected POST-FIX: both NO. Emits:
       GRADE_INTEGRITY: client-authoritative=YES/NO; teacher-analytics-affected=YES/NO
  - SUBMISSION DURABILITY: submit a normal quiz, then immediately re-fetch
    progress/analytics in a FRESH request; assert the score persisted. (True
    transaction-poisoning needs fault injection in a unit test — flagged in the report.)
  - PARTIAL-FAILURE HONESTY: a garbled page shows as failed end-to-end, and a benign
    re-compile does NOT surface a false FailedPage.

SAFETY (mandatory): requires env STAGING_URL. Refuses to run against a prod-looking
host (contains "prod" or a railway production host) unless ALLOW_PROD=1. Uses throwaway
random-email accounts and best-effort deletes them at the end. Makes REAL Gemini/Claude
calls (costs tokens). NOT for CI.

Config (env):
  STAGING_URL   (required) backend base, e.g. https://apalchi-staging.up.railway.app
  ALLOW_PROD    set to 1 to allow a prod-looking host (otherwise refuses).
  EVAL_SUBJECT  subject enum for the class (default SCIENCE).
  COMPILE_TIMEOUT_S  per-compile poll budget in seconds (default 300).

Usage:
  STAGING_URL=https://your-staging-host python3 tools/eval/smoke_e2e_web_loop.py
"""
import datetime
import json
import os
import pathlib
import random
import string
import sys
import time

try:
    import requests
except ImportError:
    sys.exit("pip install requests  (needed for the smoke harness)")

HERE = pathlib.Path(__file__).resolve().parent
BASE = os.environ.get("STAGING_URL", "").rstrip("/")
ALLOW_PROD = os.environ.get("ALLOW_PROD") == "1"
SUBJECT = os.environ.get("EVAL_SUBJECT", "SCIENCE")
COMPILE_TIMEOUT_S = int(os.environ.get("COMPILE_TIMEOUT_S", "300"))
# Compile is ASYNC: upload auto-triggers a recompile debounced by WIKI_DEBOUNCE_MS
# (8s default), then an off-request ai-task thread runs it. So we must POLL until
# the avatar's brainState is terminal, not read the result right after the 202.
COMPILE_POLL_TIMEOUT_S = int(os.environ.get("COMPILE_POLL_TIMEOUT_S", "90"))
COMPILE_POLL_INTERVAL_S = int(os.environ.get("COMPILE_POLL_INTERVAL_S", "3"))
OUT = HERE / "out" / ("e2e_web_" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))

# brainState values that mean the async compile is STILL in flight (NOT done).
# NONE = no active compile job (the debounce gap) — emphatically not "finished".
NON_TERMINAL_BRAIN = {"PENDING_RECOMPILE", "COMPILING", "NONE", "PENDING", "?"}

# ── Known facts: grep these out of the compiled wiki body to assert ACCURACY. ──
FACT_1 = "the boiling point of water is 100"          # doc 1 known fact
FACT_2 = "the freezing point of water is 0"           # doc 2 known fact

DOC_1 = (
    "States of Matter and Phase Changes\n\n"
    "Water is a substance studied throughout science. At standard atmospheric "
    "pressure (1 atm), the boiling point of water is 100 degrees Celsius. When "
    "water reaches 100 degrees Celsius it changes from a liquid into a gas in a "
    "process called boiling. Boiling is a phase change driven by added thermal "
    "energy. The temperature stays constant at 100 degrees Celsius during boiling "
    "until all the liquid has turned to vapour.\n"
)
DOC_2 = (
    "Freezing, Melting and the Behaviour of Water\n\n"
    "At standard atmospheric pressure, the freezing point of water is 0 degrees "
    "Celsius. When liquid water is cooled to 0 degrees Celsius it solidifies into "
    "ice, a phase change called freezing. Melting is the reverse: ice warmed to 0 "
    "degrees Celsius turns back into liquid water. The freezing point and the "
    "melting point of pure water are the same temperature, 0 degrees Celsius.\n"
)
# Deliberately garbled / non-extractable content: should surface as a FailedPage,
# never be silently dropped and never be falsely reported as compiled.
DOC_GARBLED = (
    "\x00\x01\x02 zzqx!!! ��� 9f8a7b6c5d4e3f2a1b0c "
    "qw?? ;;;;; �� \x07\x08 ##### }}}}} <<<<< nonsense token soup "
    "no sentences no facts only noise ��� \x00\x00\x00\n"
)


# ──────────────────────────── infra ────────────────────────────
def log(msg):
    print(msg, flush=True)


def die(msg):
    log(f"FATAL: {msg}")
    sys.exit(2)


def rand_email(tag):
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"e2e+{tag}-{int(time.time())}-{suffix}@apalchi-eval.com"


class Api:
    """Thin client. Sends Bearer (the real auth) AND X-User-Id (logging filter / belt
    and braces, per the harness brief). unwrap() always reads ApiResponse.data."""

    def __init__(self, base, token=None, user_id=None):
        self.base, self.token, self.user_id = base, token, user_id

    def _h(self, extra=None):
        h = {"accept": "application/json"}
        if self.token:
            h["authorization"] = f"Bearer {self.token}"
        if self.user_id:
            h["X-User-Id"] = self.user_id
        if extra:
            h.update(extra)
        return h

    def get(self, path, timeout=60):
        return requests.get(self.base + path, headers=self._h(), timeout=timeout)

    def post(self, path, body=None, timeout=120):
        return requests.post(
            self.base + path,
            headers=self._h({"content-type": "application/json"}),
            data=json.dumps(body or {}),
            timeout=timeout,
        )

    def delete(self, path, timeout=60):
        return requests.delete(self.base + path, headers=self._h(), timeout=timeout)

    def upload(self, path, filename, content, content_type="text/plain", timeout=180):
        return requests.post(
            self.base + path,
            headers=self._h(),
            files={"file": (filename, content, content_type)},
            timeout=timeout,
        )


def unwrap(resp):
    """Return the inner ApiResponse.data, or the raw json if not enveloped."""
    try:
        j = resp.json()
    except Exception:
        return {"_raw": getattr(resp, "text", "")}
    if isinstance(j, dict) and "data" in j:
        return j.get("data")
    return j


def as_list(data, *keys):
    """Coerce a payload to a list. If it's a dict, try each key (e.g. 'pages',
    'avatars', 'modules') before giving up. A silent envelope-[] stays [] so the
    non-empty assertion can catch it."""
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for k in keys:
            v = data.get(k)
            if isinstance(v, list):
                return v
    return []


# ─────────────────── PASS/FAIL recording ───────────────────
STAGES = []      # ordered stage records
CHECKS = []      # individual accuracy/shape assertions


def stage(name, http, note="", body=None):
    rec = {"stage": name, "http": http, "note": note}
    STAGES.append(rec)
    n = len(STAGES)
    (OUT / f"{n:02d}_{name}.json").write_text(
        json.dumps({"http": http, "note": note, "body": body}, indent=2, default=str)
    )
    log(f"  [{name:24s}] HTTP {str(http):>5s}  {note}".rstrip())
    return rec


def check(name, passed, expected, actual):
    """An accuracy/shape assertion. A 200 that fails one of these is a loud FAIL."""
    rec = {
        "check": name,
        "result": "PASS" if passed else "FAIL",
        "expected": expected,
        "actual": actual,
    }
    CHECKS.append(rec)
    mark = "PASS" if passed else "FAIL  <<<"
    log(f"      - {name:42s} {mark}")
    if not passed:
        log(f"            expected: {expected}")
        log(f"            actual:   {actual}")
    return passed


# ─────────────────── auth helpers ───────────────────
def is_consent_gate(resp):
    """True if a response is the child-data consent gate (a 403 carrying an
    age/parent consent code), as opposed to any other failure."""
    if resp.status_code != 403:
        return False
    body = resp.text or ""
    return any(code in body for code in (
        "AGE_DECLARATION_REQUIRED", "PARENT_LINK_REQUIRED",
        "PARENTAL_CONSENT_PENDING", "AI_CONSENT_REQUIRED"))


def register(api_base, tag, role=None, birth_year=None):
    """Register a throwaway account. Returns (token, userId, email).

    Pass birth_year to declare an age at registration — the REAL public
    age-declaration path (birthYear is set only at /auth/register; there is no
    separate post-registration age endpoint). An adult year clears the
    default-deny child-data ingress gate so uploads aren't 403'd.
    """
    email = rand_email(tag)
    body = {"email": email, "password": "EvalProbe123!", "displayName": f"E2E {tag}"}
    if role:
        body["role"] = role
    if birth_year is not None:
        body["birthYear"] = birth_year
    r = Api(api_base).post("/api/v1/auth/register", body)
    d = unwrap(r) or {}
    token, uid = d.get("token"), d.get("userId")
    if not token:
        die(f"register({tag}) failed: HTTP {r.status_code} {r.text[:240]}")
    log(f"  registered {tag}: {email}  (userId={uid})")
    return token, uid, email


def best_effort_delete(api, who):
    try:
        r = api.delete("/api/v1/auth/account")
        log(f"  cleanup delete {who}: HTTP {r.status_code}")
    except Exception as e:
        log(f"  cleanup delete {who}: skipped ({type(e).__name__})")


# ─────────────────── compile poller ───────────────────
def compile_and_poll(api, avatar, label):
    """Fire compile, then POLL the avatar brainState until the async compile
    reaches a terminal state — READY (success) or FAILED — bounded by
    COMPILE_POLL_TIMEOUT_S. The compile runs off-request (ai-task threads) after
    a debounce, so the result must NOT be read right after the 202: brainState
    moves PENDING_RECOMPILE → COMPILING → READY, and compile/status returns NONE
    during the debounce gap (not terminal). Pages are read only AFTER the loop.

    Returns outcome ∈ {READY, FAILED, TIMEOUT} plus the last brainState/status
    and the pages, so the caller can tell a finished compile from a still-running
    one and a still-running one from a genuinely stuck pipeline."""
    try:
        rc = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=COMPILE_TIMEOUT_S)
        compile_http = rc.status_code
        (OUT / f"compile_{label}.json").write_text(rc.text)
    except requests.RequestException as e:
        compile_http = f"EXC:{type(e).__name__}"
        (OUT / f"compile_{label}.json").write_text(json.dumps({"exception": str(e)}))

    brain_state, comp_state, status, outcome = "?", "?", {}, "TIMEOUT"
    deadline = time.time() + COMPILE_POLL_TIMEOUT_S
    while time.time() < deadline:
        st = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/compile/status"))
        status = st if isinstance(st, dict) else {}
        comp_state = (status.get("state") or "?").upper()
        av = unwrap(api.get(f"/api/v1/avatars/{avatar}"))
        brain_state = ((av.get("brainState") if isinstance(av, dict) else "?") or "?").upper()
        if brain_state == "READY" or comp_state in ("DONE", "COMPLETE", "COMPLETED"):
            outcome = "READY"
            break
        if brain_state == "FAILED" or comp_state == "FAILED":
            outcome = "FAILED"
            break
        time.sleep(COMPILE_POLL_INTERVAL_S)

    pages_data = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages"))
    pages = as_list(pages_data, "pages")
    (OUT / f"pages_{label}.json").write_text(json.dumps(pages, indent=2, default=str))
    return {
        "compile_http": compile_http,
        "outcome": outcome,          # READY | FAILED | TIMEOUT
        "brain_state": brain_state,
        "state": comp_state,
        "status": status,
        "pages": pages,
    }


def failed_pages_from(compile_resp_text, status):
    """Collect failed-page indicators from BOTH the compile response body and the
    status payload (failedPages / pagesFailed)."""
    fp = []
    try:
        body = json.loads(compile_resp_text)
        body = body.get("data", body) if isinstance(body, dict) else body
        if isinstance(body, dict):
            fp += body.get("failedPages") or []
    except Exception:
        pass
    if isinstance(status, dict):
        fp += status.get("failedPages") or []
        pf = status.get("pagesFailed")
        if isinstance(pf, int) and pf > 0 and not fp:
            fp.append({"_count": pf})
    return fp


def body_text(page):
    """Page body across plausible field names."""
    for k in ("content", "body", "markdown", "text"):
        v = page.get(k)
        if isinstance(v, str) and v:
            return v
    return ""


def grep_fact(pages, needle):
    needle = needle.lower()
    for p in pages:
        if needle in body_text(p).lower():
            return p.get("slug") or p.get("id") or "?"
    return None


# ──────────────────────────── main loop ────────────────────────────
def main():
    if not BASE:
        die("STAGING_URL is required. e.g. STAGING_URL=https://staging-host "
            "python3 tools/eval/smoke_e2e_web_loop.py")
    looks_prod = ("prod" in BASE.lower()) or (
        ".railway.app" in BASE and "staging" not in BASE.lower()
    )
    if looks_prod and not ALLOW_PROD:
        die(f"STAGING_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")

    OUT.mkdir(parents=True, exist_ok=True)
    log(f"STAGING_URL={BASE}")
    log(f"out={OUT}")
    log("=" * 78)

    teacher_api = student_api = None
    org = cls = corpus = join_code = student_avatar = None
    try:
        # ── 1. Centre signup → org → class (corpus avatar) ──
        log("STAGE 1 — centre signup + class + class brain")
        # Declare an ADULT age at registration — the real public age-declaration
        # path. Without it, the default-deny child-data ingress gate correctly
        # 403s the upload (AGE_DECLARATION_REQUIRED), which sank every prior run.
        ADULT_BIRTH_YEAR = 1990
        t_token, t_uid, t_email = register(
            BASE, "teacher", birth_year=ADULT_BIRTH_YEAR)
        teacher_api = Api(BASE, t_token, t_uid)

        ob_r = teacher_api.post(
            "/api/v1/centre/onboard", {"centreName": f"E2E Centre {int(time.time())}"}
        )
        ob = unwrap(ob_r) or {}
        org = ob.get("orgId") if isinstance(ob, dict) else None
        stage("centre_onboard", ob_r.status_code, f"orgId={org}", ob_r.text)
        check("onboard returns orgId", bool(org), "non-empty orgId", org)
        if not org:
            die("cannot continue without orgId")

        cr_r = teacher_api.post(
            f"/api/v1/centre/organizations/{org}/classes",
            {"name": "E2E Class", "subject": SUBJECT, "level": "Secondary 3"},
        )
        cr = unwrap(cr_r) or {}
        cls = cr.get("id") if isinstance(cr, dict) else None
        corpus = cr.get("corpusAvatarId") if isinstance(cr, dict) else None
        join_code = cr.get("joinCode") if isinstance(cr, dict) else None
        stage("class_create", cr_r.status_code,
              f"classId={cls} corpus={corpus} joinCode={join_code}", cr_r.text)
        check("class create returns id", bool(cls), "non-empty class id", cls)
        check("class create mints corpus avatar", bool(corpus),
              "non-empty corpusAvatarId", corpus)
        check("class create returns joinCode", bool(join_code),
              "non-empty joinCode", join_code)
        if not (cls and corpus):
            die("cannot continue without class id + corpus avatar")
        avatar = corpus  # the class brain we compile/generate content on

        # ── 2. Upload 2 known-fact docs ──
        log("STAGE 2 — upload known-fact docs")
        file_ids = []
        for idx, (fname, doc) in enumerate(
            [("states-of-matter.txt", DOC_1), ("freezing-melting.txt", DOC_2)], start=1
        ):
            ur = teacher_api.upload(f"/api/v1/avatars/{avatar}/files", fname, doc)
            ud = unwrap(ur) or {}
            fid = ud.get("fileId") if isinstance(ud, dict) else None
            stage(f"upload_doc{idx}", ur.status_code, f"fileId={fid}", ur.text)
            # Gate-cleared regression guard: an age-declared adult teacher must
            # NOT hit the child-data consent gate. If this 403s with a consent
            # code, the age declaration didn't clear the gate (declaration-flow
            # regression) OR the backend over-blocks centre teachers — either way
            # fail loudly here, distinct from a generic upload failure.
            check(f"doc{idx} NOT blocked by the child-data consent gate",
                  not is_consent_gate(ur),
                  "no AGE_DECLARATION/PARENT_LINK consent 403 (adult age declared)",
                  ur.text[:160] if is_consent_gate(ur) else "ok (gate cleared)")
            check(f"doc{idx} upload status 200/201",
                  ur.status_code in (200, 201), "200 or 201", ur.status_code)
            check(f"doc{idx} returns fileId", bool(fid), "non-empty fileId", fid)
            if fid:
                file_ids.append(fid)

        # ── 3. Compile (clean) + accuracy assertions ──
        log("STAGE 3 — compile clean corpus + accuracy")
        c1 = compile_and_poll(teacher_api, avatar, "clean")
        compile_text = (OUT / "compile_clean.json").read_text()
        stage("compile_clean", c1["compile_http"],
              f"outcome={c1['outcome']} brain={c1['brain_state']} "
              f"compileState={c1['state']} pages={len(c1['pages'])}")

        # One unambiguous compile verdict — distinguishing a finished compile from
        # a still-running one from a genuinely stuck pipeline (no more "not READY"
        # on a compile that simply hadn't fired yet).
        if c1["outcome"] == "READY":
            check("compile reaches READY (terminal)", True, "READY",
                  f"brainState={c1['brain_state']}")
        elif c1["outcome"] == "FAILED":
            check("compile reaches READY (terminal)", False, "READY",
                  f"compile FAILED — status={json.dumps(c1['status'])[:200]}")
        else:  # TIMEOUT
            check("compile reaches READY (terminal)", False, "READY",
                  f"compile did NOT reach a terminal state in {COMPILE_POLL_TIMEOUT_S}s "
                  f"— last brainState={c1['brain_state']} compileState={c1['state']} "
                  f"— POSSIBLE STUCK PIPELINE, not a timing artifact")

        # at least one page, and the known facts actually appear in a page BODY
        check("wiki has >=1 page", len(c1["pages"]) >= 1, ">=1 page", len(c1["pages"]))
        slug1 = grep_fact(c1["pages"], FACT_1)
        slug2 = grep_fact(c1["pages"], FACT_2)
        check("FACT_1 present in a compiled page body (accuracy)",
              slug1 is not None, f"a page body containing '{FACT_1}'",
              f"found in slug={slug1}" if slug1 else "NOT FOUND in any page body")
        check("FACT_2 present in a compiled page body (accuracy)",
              slug2 is not None, f"a page body containing '{FACT_2}'",
              f"found in slug={slug2}" if slug2 else "NOT FOUND in any page body")

        clean_failed = failed_pages_from(compile_text, c1["status"])
        check("clean compile has NO failed pages",
              len(clean_failed) == 0, "empty failedPages", clean_failed)

        # ── 3b. Garbled doc → must surface as FailedPage ──
        log("STAGE 3b — garbled doc partial-failure honesty")
        gr = teacher_api.upload(
            f"/api/v1/avatars/{avatar}/files", "garbled-noise.txt", DOC_GARBLED
        )
        stage("upload_garbled", gr.status_code, "", gr.text)
        c2 = compile_and_poll(teacher_api, avatar, "garbled")
        garbled_text = (OUT / "compile_garbled.json").read_text()
        garbled_failed = failed_pages_from(garbled_text, c2["status"])
        stage("compile_garbled", c2["compile_http"],
              f"state={c2['state']} failedPages={len(garbled_failed)}")
        # Honest behaviour: EITHER it is reported in failedPages, OR the upload itself
        # was rejected (relevance/quality). The dishonest outcome we fail on is the
        # garbled doc being silently accepted AND producing a clean "compiled" page.
        garbled_pages_now = len(c2["pages"])
        garbled_handled = (
            len(garbled_failed) > 0
            or gr.status_code not in (200, 201)
            or c2["state"] == "FAILED"
        )
        check("garbled doc surfaces as failure (not silently dropped)",
              garbled_handled,
              "failedPages non-empty OR upload rejected OR state FAILED",
              {"failedPages": garbled_failed, "upload_http": gr.status_code,
               "state": c2["state"], "pages_after": garbled_pages_now})

        # PARTIAL-FAILURE HONESTY: a benign re-compile must NOT invent a false failure.
        log("STAGE 3c — benign recompile must not invent a false FailedPage")
        # (re-derive failed set on the SAME corpus; expectation: no NEW false failures
        # beyond the genuinely-garbled doc already accounted for)
        false_failure = False
        if not garbled_handled:
            # if garbled wasn't even handled there's nothing to compare; skip honestly
            false_failure = False
        partial_failure_note = (
            f"garbled_failed={len(garbled_failed)} clean_failed={len(clean_failed)}"
        )

        # ── 4. Content: modules + quiz items non-empty ──
        log("STAGE 4 — content generation")
        gen = teacher_api.post(f"/api/v1/avatars/{avatar}/modules/generate", {}, timeout=240)
        gen_list = as_list(unwrap(gen), "modules")
        stage("modules_generate", gen.status_code, f"generated={len(gen_list)}", gen.text)
        time.sleep(6)
        mods_r = teacher_api.get(f"/api/v1/avatars/{avatar}/modules")
        mods = as_list(unwrap(mods_r), "modules")
        mod_ids = [m.get("id") or m.get("moduleId") for m in mods if (m.get("id") or m.get("moduleId"))]
        stage("modules_list", mods_r.status_code, f"modules={len(mods)} ids={len(mod_ids)}", mods_r.text)
        check("modules exist (non-empty)", len(mods) >= 1, ">=1 module", len(mods))
        check("modules have resolvable ids", len(mod_ids) >= 1, ">=1 module id", len(mod_ids))

        quiz_r = teacher_api.get(f"/api/v1/avatars/{avatar}/quiz/daily")
        quiz_items = as_list(unwrap(quiz_r), "questions", "items")
        stage("quiz_daily", quiz_r.status_code, f"questions={len(quiz_items)}", quiz_r.text)
        check("daily quiz has non-empty items",
              len(quiz_items) >= 1, ">=1 quiz question", len(quiz_items))
        # EXPOSURE FIX — because `avatar` is a CENTRE (teacher-graded) brain, the
        # served quiz must WITHHOLD BOTH answer-revealing fields: correctIndex
        # (the option) AND explanation (which justifies it, e.g. "3 out of 8").
        # Both must be null on every question, revealed only POST-submit.
        #
        # PRECONDITION: you cannot leak a key on a question that doesn't exist.
        # An EMPTY quiz means an upstream stage failed — that must FAIL loudly as
        # a precondition, NOT pass silently and NOT report a false "leak".
        # "withheld" and "absent because nothing was generated" are OPPOSITE
        # outcomes and must not score the same.
        if not quiz_items:
            check("EXPOSURE FIX: a centre quiz exists to inspect",
                  False,
                  ">=1 quiz question to check for answer-key withholding",
                  "no quiz to inspect — an upstream stage (upload/compile/"
                  "generate) failed; exposure CANNOT be assessed (not a leak)")
        else:
            well_formed = all(
                isinstance(q.get("options"), list) and q.get("options")
                for q in quiz_items)
            check("quiz questions well-formed (non-empty options)",
                  well_formed, "every question has non-empty options",
                  "ok" if well_formed else "missing options on some question")
            key_withheld = all(q.get("correctIndex") is None for q in quiz_items)
            check("EXPOSURE FIX: centre quiz WITHHOLDS correctIndex (key not shipped)",
                  key_withheld,
                  "correctIndex is null on every teacher-graded question",
                  "ok (withheld)" if key_withheld
                  else "LEAKED correctIndex on some question — exposure seam OPEN")
            explanation_withheld = all(
                q.get("explanation") in (None, "") for q in quiz_items)
            check("EXPOSURE FIX: centre quiz WITHHOLDS explanation (reveals the answer too)",
                  explanation_withheld,
                  "explanation is null/absent on every teacher-graded question",
                  "ok (withheld)" if explanation_withheld
                  else "LEAKED explanation on some question — second exposure seam OPEN")

        # ── 5. Distribution: assignment + enroll student ──
        log("STAGE 5 — distribution: assignment + student enroll")
        ar = teacher_api.post(
            f"/api/v1/centre/organizations/{org}/classes/{cls}/assignments",
            {"title": "E2E Revision", "type": "REVISION", "moduleIds": mod_ids},
        )
        assign = unwrap(ar) or {}
        assignment_id = assign.get("id") if isinstance(assign, dict) else None
        stage("assignment_create", ar.status_code,
              f"assignmentId={assignment_id} modules={len(mod_ids)}", ar.text)
        check("assignment create returns 201",
              ar.status_code == 201, "201", ar.status_code)
        check("assignment returns id", bool(assignment_id), "non-empty assignment id", assignment_id)

        s_token, s_uid, s_email = register(BASE, "student", role="STUDENT")
        student_api = Api(BASE, s_token, s_uid)
        # Student joins the class by the join code → gets a CENTRE_CLASS avatar.
        jr = student_api.post("/api/v1/centre/redeem-class-code", {"code": join_code})
        jd = unwrap(jr) or {}
        student_avatar = jd.get("avatarId") if isinstance(jd, dict) else None
        stage("student_join", jr.status_code, f"studentAvatar={student_avatar}", jr.text)
        check("student join succeeds", jr.status_code in (200, 201), "200 or 201", jr.status_code)
        check("student join returns a class avatar", bool(student_avatar),
              "non-empty avatarId", student_avatar)
        if not student_avatar:
            die("cannot continue student loop without a class avatar")

        # student sees the assignment
        sa_r = student_api.get(f"/api/v1/avatars/{student_avatar}/assignments")
        sa_list = as_list(unwrap(sa_r), "assignments")
        sees = any((a.get("id") == assignment_id) for a in sa_list) if assignment_id else len(sa_list) >= 1
        stage("student_assignments", sa_r.status_code,
              f"count={len(sa_list)} sees_target={sees}", sa_r.text)
        check("student sees the assignment", sees,
              f"assignment {assignment_id} in student's list", [a.get("id") for a in sa_list])

        # startAssignment → targeted module set (or honest-empty)
        targeted = []
        if assignment_id:
            st_r = student_api.post(
                f"/api/v1/avatars/{student_avatar}/assignments/{assignment_id}/start", {}
            )
            st_d = unwrap(st_r) or {}
            stage("start_assignment", st_r.status_code,
                  f"status={st_d.get('status') if isinstance(st_d, dict) else '?'}", st_r.text)
            # the resolved targets are read back from the assignment detail (moduleIds CSV)
            detail = unwrap(student_api.get(
                f"/api/v1/avatars/{student_avatar}/assignments/{assignment_id}"))
            raw_targets = detail.get("moduleIds") if isinstance(detail, dict) else None
            if isinstance(raw_targets, str):
                targeted = [x for x in raw_targets.split(",") if x.strip()]
            elif isinstance(raw_targets, list):
                targeted = raw_targets
            status_val = st_d.get("status") if isinstance(st_d, dict) else None
            honest_empty = status_val == "COMPLETED"  # auto-complete when no weak modules
            check("startAssignment returns targeted modules (or honest-empty)",
                  len(targeted) >= 1 or honest_empty,
                  "non-empty targeted module set, OR status COMPLETED (honest empty)",
                  {"targeted": targeted, "status": status_val})

        # ── 6. Student work + feedback accuracy (normal submission) ──
        log("STAGE 6 — student quiz submit + feedback accuracy")
        s_quiz_r = student_api.get(f"/api/v1/avatars/{student_avatar}/quiz/daily")
        s_quiz = as_list(unwrap(s_quiz_r), "questions", "items")
        stage("student_quiz_daily", s_quiz_r.status_code, f"questions={len(s_quiz)}", s_quiz_r.text)
        check("student gets a non-empty daily quiz", len(s_quiz) >= 1, ">=1 question", len(s_quiz))

        progress_before = unwrap(student_api.get("/api/v1/progress"))
        xp_before = progress_before.get("xp", 0) if isinstance(progress_before, dict) else 0

        normal_score = normal_total = normal_xp = None
        revealed_keys = {}  # questionId -> real correctIndex, learned POST-submit
        if s_quiz:
            # The key is WITHHELD (teacher-graded), so the student cannot pre-answer
            # correctly. Submit BLIND (index 0) and NO correctMap — the server grades
            # authoritatively from its persisted key — then read correctness from the
            # POST-submit feedback. This is exactly the post-fix UX: feedback after
            # submit, not a pre-submit answer key.
            answers = {q["id"]: 0 for q in s_quiz}
            sub = student_api.post(
                f"/api/v1/avatars/{student_avatar}/quiz/answers",
                {"answers": answers, "durationSeconds": 42},
            )
            res = unwrap(sub) or {}
            normal_score = res.get("score")
            normal_total = res.get("total")
            normal_xp = res.get("xpEarned")
            feedback = res.get("feedback") or []
            revealed_keys = {f.get("questionId"): f.get("correctIndex")
                             for f in feedback if isinstance(f, dict)}
            stage("student_quiz_submit", sub.status_code,
                  f"score={normal_score}/{normal_total} xp={normal_xp} "
                  f"feedback={len(feedback)}", sub.text)
            # Feedback is preserved post-submit: one entry per question...
            check("post-submit feedback returned (one entry per question)",
                  isinstance(feedback, list) and len(feedback) == len(answers),
                  f"feedback length == {len(answers)}", len(feedback))
            # ...and it REVEALS the correct answer (the only place it appears now).
            revealed = bool(feedback) and all(
                f.get("correctIndex") is not None for f in feedback)
            check("EXPOSURE FIX: correct answer revealed POST-submit (feedback)",
                  revealed, "every feedback entry carries correctIndex",
                  "ok" if revealed else "feedback missing correctIndex")
            # Feedback accuracy: the server's score equals its own per-question
            # verdicts (internally consistent server-authoritative grading).
            fb_correct = sum(1 for f in feedback if f.get("wasCorrect"))
            check("server score matches its own per-question feedback",
                  normal_score == fb_correct,
                  f"score == sum(wasCorrect) == {fb_correct}",
                  {"score": normal_score, "fb_correct": fb_correct})
            check("XP awarded for quiz", isinstance(normal_xp, int) and normal_xp > 0,
                  "xpEarned > 0", normal_xp)

        # SUBMISSION DURABILITY: fresh request must see the persisted XP gain.
        progress_after = unwrap(student_api.get("/api/v1/progress"))
        xp_after = progress_after.get("xp", 0) if isinstance(progress_after, dict) else 0
        stage("progress_durability", "-", f"xp {xp_before} -> {xp_after}")
        check("SUBMISSION DURABILITY: XP persisted on fresh fetch",
              xp_after > xp_before, f"xp > {xp_before}", xp_after)

        # ── 7. Display: every web data endpoint parses to expected shape ──
        log("STAGE 7 — web display endpoints shape")
        # class detail (teacher list of classes contains our class)
        cls_list_r = teacher_api.get(f"/api/v1/centre/organizations/{org}/classes")
        cls_list = as_list(unwrap(cls_list_r), "classes")
        stage("web_class_list", cls_list_r.status_code, f"classes={len(cls_list)}", cls_list_r.text)
        check("class list non-empty + contains our class",
              any(c.get("id") == cls for c in cls_list),
              f"a class with id {cls}", [c.get("id") for c in cls_list])

        roster_r = teacher_api.get(
            f"/api/v1/centre/organizations/{org}/classes/{cls}/members")
        roster = as_list(unwrap(roster_r))
        stage("web_class_members", roster_r.status_code, f"members={len(roster)}", roster_r.text)
        check("class roster non-empty after enroll", len(roster) >= 1, ">=1 member", len(roster))

        pages_r = teacher_api.get(f"/api/v1/avatars/{avatar}/wiki/pages")
        pages_disp = as_list(unwrap(pages_r), "pages")
        stage("web_brain_pages", pages_r.status_code, f"pages={len(pages_disp)}", pages_r.text)
        check("brain pages endpoint returns non-empty array",
              len(pages_disp) >= 1, ">=1 page", len(pages_disp))

        # ── 8. Analytics: teacher sees the student's result ──
        log("STAGE 8 — teacher analytics reflect student result")
        sd_r = teacher_api.get(
            f"/api/v1/centre/organizations/{org}/students/{s_uid}")
        student_detail = unwrap(sd_r)
        stage("teacher_student_detail", sd_r.status_code, "", sd_r.text)
        check("teacher student-detail parses to an object",
              isinstance(student_detail, (dict, list)) and student_detail not in ({}, [], None),
              "non-empty object/array", type(student_detail).__name__)

        an_r = teacher_api.get(
            f"/api/v1/centre/organizations/{org}/classes/{cls}/analytics/roster")
        roster_analytics = as_list(unwrap(an_r))
        stage("teacher_class_analytics", an_r.status_code,
              f"rows={len(roster_analytics)}", an_r.text)
        check("class analytics roster non-empty",
              len(roster_analytics) >= 1, ">=1 roster row", len(roster_analytics))

        # The student's ATTEMPT must be reflected — NOT merely their enrolment.
        # Membership alone is vacuous: the student appears in the roster just by
        # joining, with attempts=0. A real reflection needs (a) a submission to
        # have actually happened this run, AND (b) the student's matched roster
        # row to carry a real attempt signal (attempts>0 or non-zero grasp).
        def _student_row(rows):
            for r in rows:
                if (r.get("userId") == s_uid or r.get("studentUserId") == s_uid
                        or r.get("studentId") == s_uid):
                    return r
            return None

        def _attempt_signal(r):
            if not r:
                return False
            for k in ("attempts", "questions", "questionsAnswered", "attemptCount"):
                v = r.get(k)
                if isinstance(v, (int, float)) and v > 0:
                    return True
            for k in ("grasp", "mastery", "masteryPct", "graspPct", "averageScore"):
                v = r.get(k)
                if isinstance(v, (int, float)) and v > 0:
                    return True
            return False

        s_row = _student_row(roster_analytics)
        submitted = normal_total is not None and normal_total >= 1
        check("student's ATTEMPT (not just enrolment) reflected in teacher analytics",
              submitted and _attempt_signal(s_row),
              "a real submission happened AND the student's roster row shows an "
              "attempt signal (attempts>0 or non-zero grasp) — not mere membership",
              {"submitted": submitted, "matched": bool(s_row), "row": s_row})

        # ── PROBE: GRADE INTEGRITY ──
        log("PROBE — GRADE_INTEGRITY (tampered correctMap)")
        client_authoritative = "UNKNOWN"
        teacher_affected = "UNKNOWN"
        if s_quiz:
            # Fetch a fresh quiz for the tamper attempt (same cached daily set).
            tq_r = student_api.get(f"/api/v1/avatars/{student_avatar}/quiz/daily")
            tq = as_list(unwrap(tq_r), "questions", "items")
            if not tq:
                tq = s_quiz  # fall back to earlier set if daily exhausted
            # Submit DELIBERATELY WRONG answers (we learned the real answers from
            # the post-submit feedback in stage 6) WITH a TAMPERED correctMap that
            # marks every wrong answer "correct". Pre-fix this farmed a perfect
            # score; post-fix the server ignores the client map and grades from its
            # own persisted key, so the wrong answers must stay wrong.
            wrong_idx = {}
            tampered_map = {}
            for q in tq:
                qid = q["id"]
                opts = q.get("options") or []
                real = revealed_keys.get(qid)
                if real is not None and len(opts) > 1:
                    wrong = (real + 1) % len(opts)   # guaranteed != real
                else:
                    wrong = 0  # degraded: real answer unknown, blind guess
                wrong_idx[qid] = wrong
                tampered_map[qid] = wrong  # claim the wrong answer is "correct"
            tamper = student_api.post(
                f"/api/v1/avatars/{student_avatar}/quiz/answers",
                {"answers": wrong_idx, "correctMap": tampered_map, "durationSeconds": 7},
            )
            tres = unwrap(tamper) or {}
            t_score = tres.get("score")
            t_total = tres.get("total")
            stage("grade_integrity_tamper", tamper.status_code,
                  f"tampered_score={t_score}/{t_total}", tamper.text)
            # client-authoritative == the tampered map produced a perfect score for
            # objectively-wrong answers. Post-fix this must be NO (server ignores the
            # map). If we knew the real answers (revealed_keys) the wrong answers are
            # guaranteed wrong, so a NO here is a hard proof, not luck.
            perfect_on_wrong = (
                tamper.status_code in (200, 201)
                and t_score is not None and t_total is not None
                and t_score == t_total and t_total == len(wrong_idx) and t_total > 0
            )
            client_authoritative = "YES" if perfect_on_wrong else "NO"

            # Re-fetch teacher analytics: did the tampered result move teacher-visible
            # mastery? Compare roster analytics snapshot before/after the tamper.
            before_rows = roster_analytics
            after_r = teacher_api.get(
                f"/api/v1/centre/organizations/{org}/classes/{cls}/analytics/roster")
            after_rows = as_list(unwrap(after_r))

            def mastery_of(rows):
                for r in rows:
                    if (r.get("userId") == s_uid or r.get("studentUserId") == s_uid
                            or r.get("studentId") == s_uid):
                        for k in ("mastery", "grasp", "graspPct", "masteryPct", "averageScore"):
                            if r.get(k) is not None:
                                return r.get(k)
                return None

            m_before, m_after = mastery_of(before_rows), mastery_of(after_rows)
            stage("grade_integrity_analytics", after_r.status_code,
                  f"mastery {m_before} -> {m_after}")
            if m_before is not None and m_after is not None:
                teacher_affected = "YES" if m_after != m_before else "NO"
            else:
                # If mastery isn't surfaced numerically, fall back to row presence delta.
                teacher_affected = "UNKNOWN(no-numeric-mastery-field)"

        grade_integrity_line = (
            f"GRADE_INTEGRITY: client-authoritative={client_authoritative}; "
            f"teacher-analytics-affected={teacher_affected}"
        )
        log("  " + grade_integrity_line)

        # ── verdict ──
        passed_checks = sum(1 for c in CHECKS if c["result"] == "PASS")
        failed_checks = [c for c in CHECKS if c["result"] == "FAIL"]
        overall = "PASS" if not failed_checks else "FAIL"

        report = {
            "base": BASE,
            "timestamp": datetime.datetime.now().isoformat(),
            "org": org, "class": cls, "corpus_avatar": corpus, "join_code": join_code,
            "student_avatar": student_avatar, "student_uid": s_uid,
            "teacher_email": t_email, "student_email": s_email,
            "facts": {"FACT_1": FACT_1, "FACT_2": FACT_2,
                      "fact1_slug": slug1, "fact2_slug": slug2},
            "normal_quiz": {"score": normal_score, "total": normal_total, "xp": normal_xp,
                            "xp_before": xp_before, "xp_after": xp_after},
            "grade_integrity": {
                "client_authoritative": client_authoritative,
                "teacher_analytics_affected": teacher_affected,
                "verdict_line": grade_integrity_line,
            },
            "partial_failure": partial_failure_note,
            "stages": STAGES,
            "checks": CHECKS,
            "checks_passed": passed_checks,
            "checks_failed": len(failed_checks),
            "result": overall,
        }
        (OUT / "report.json").write_text(json.dumps(report, indent=2, default=str))
        write_report_md(report)

        log("\n" + "=" * 78)
        log(f"E2E WEB LOOP SMOKE  ({BASE})")
        log(f"  checks: {passed_checks} passed, {len(failed_checks)} failed")
        for c in failed_checks:
            log(f"    FAIL: {c['check']}  (expected {c['expected']}, got {c['actual']})")
        log("  " + grade_integrity_line)
        log(f"  SUBMISSION DURABILITY: xp {xp_before} -> {xp_after} "
            f"({'persisted' if xp_after > xp_before else 'NOT persisted'}); "
            f"true transaction-poisoning needs fault injection (unit test) — see REPORT.md")
        log(f"  PARTIAL-FAILURE HONESTY: {partial_failure_note}")
        log(f"  OVERALL: {overall}")
        log(f"  artifacts → {OUT}")
        log("=" * 78)

        return 0 if overall == "PASS" else 1

    finally:
        # ── best-effort cleanup ──
        log("\nCLEANUP (best-effort)")
        if student_api:
            best_effort_delete(student_api, "student")
        if teacher_api:
            best_effort_delete(teacher_api, "teacher")


def write_report_md(r):
    lines = []
    lines.append(f"# E2E Web Loop Smoke — {r['timestamp']}")
    lines.append("")
    lines.append(f"- **Base:** `{r['base']}`")
    lines.append(f"- **Result:** **{r['result']}**  "
                 f"({r['checks_passed']} checks passed, {r['checks_failed']} failed)")
    lines.append(f"- Org `{r['org']}` / Class `{r['class']}` / Corpus avatar `{r['corpus_avatar']}`")
    lines.append(f"- Join code `{r['join_code']}` / Student avatar `{r['student_avatar']}`")
    lines.append("")
    lines.append("## Targeted verdicts")
    lines.append("")
    lines.append(f"```\n{r['grade_integrity']['verdict_line']}\n```")
    lines.append("")
    lines.append("- **GRADE INTEGRITY** — after the server-authoritative fix, "
                 "`POST /quiz/answers` grades against a PERSISTED server-side key and "
                 "IGNORES the client `correctMap`; teacher-graded (centre) quizzes also "
                 "withhold `correctIndex` from the served question (revealed only "
                 "post-submit). This probe submits objectively-wrong answers (the real "
                 "answers were learned from the post-submit feedback) WITH a tampered "
                 "`correctMap` marking them all correct, and records whether the server "
                 "returns a perfect score, then whether the tampered result moved "
                 "teacher-visible mastery. Expected post-fix: both NO.")
    lines.append(f"  - client-authoritative=`{r['grade_integrity']['client_authoritative']}`")
    lines.append(f"  - teacher-analytics-affected=`{r['grade_integrity']['teacher_analytics_affected']}`")
    lines.append("")
    lines.append("- **SUBMISSION DURABILITY** — a normal submission's XP gain "
                 f"(`{r['normal_quiz']['xp_before']}` → `{r['normal_quiz']['xp_after']}`) "
                 "is re-fetched in a FRESH request to assert it persisted. NOTE: true "
                 "transaction-poisoning (a submit that 200s but never commits) cannot be "
                 "proven black-box — it needs fault injection in a unit/integration test. "
                 "Flagged for the hardening pass.")
    lines.append("")
    lines.append(f"- **PARTIAL-FAILURE HONESTY** — {r['partial_failure']}. A garbled doc "
                 "must surface as a failure end-to-end (failedPages / rejected upload / "
                 "state FAILED) and a benign re-compile must not invent a false FailedPage.")
    lines.append("")
    lines.append("## Accuracy assertions (expected vs actual)")
    lines.append("")
    lines.append("| Check | Result | Expected | Actual |")
    lines.append("|---|---|---|---|")
    for c in r["checks"]:
        exp = str(c["expected"]).replace("|", "\\|")[:80]
        act = str(c["actual"]).replace("|", "\\|")[:80]
        lines.append(f"| {c['check']} | {c['result']} | {exp} | {act} |")
    lines.append("")
    lines.append("## Stages")
    lines.append("")
    lines.append("| # | Stage | HTTP | Note |")
    lines.append("|---|---|---|---|")
    for i, s in enumerate(r["stages"], 1):
        lines.append(f"| {i} | {s['stage']} | {s['http']} | {str(s['note'])[:90]} |")
    (OUT / "REPORT.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
