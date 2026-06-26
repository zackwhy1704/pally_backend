#!/usr/bin/env python3
"""
smoke_full_workflow.py — REAL-API smoke for the WHOLE centre pipeline, end to end:
  centre onboard → create class (mints corpus avatar) → upload → compile (poll) →
  modules/generate → GET modules → quiz/daily → create a REVISION assignment over
  the generated module IDs.

This runs the path a TEACHER actually drives (RUN_CENTRE in content_quality_eval.py):
content lives on the class-bound CORPUS avatar, so the modules generated from it
carry the class_id and a class REVISION assignment can resolve them.

The point: capture the EXACT status + body at EVERY stage so a failure is
attributable, e.g. "assignment 400: zero modules because modules/generate produced
0 because compile failed". PASS only if the assignment create returns 201.

Makes REAL Gemini/Claude calls (costs tokens). NOT for CI. DO NOT point at prod.

Config (env):
  BASE_URL    backend base (default http://localhost:8080). Refuses prod-looking
              hosts unless ALLOW_PROD=1.
  ALLOW_PROD  set to 1 to allow a *.railway.app / production host.
  EVAL_TOKEN  JWT to use. If unset, registers a throwaway account.
  EVAL_SUBJECT subject enum for the class/avatar (default SCIENCE).

Usage:
  BASE_URL=http://localhost:8080 python3 tools/eval/smoke_full_workflow.py
"""
import json
import os
import sys
import time
import datetime
import pathlib
import tempfile

try:
    import requests
except ImportError:
    sys.exit("pip install requests  (needed for the smoke harness)")

HERE = pathlib.Path(__file__).resolve().parent
BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
ALLOW_PROD = os.environ.get("ALLOW_PROD") == "1"
SUBJECT = os.environ.get("EVAL_SUBJECT", "SCIENCE")
OUT = HERE / "out" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
TIMEOUT_S = 180

DOC = (
    "Acids, Bases and Salts\n\n"
    "An acid is a substance that releases hydrogen ions (H+) in aqueous solution; a base "
    "releases hydroxide ions (OH-). The pH scale runs from 0 to 14: below 7 is acidic, 7 "
    "is neutral, above 7 is alkaline. Strong acids such as hydrochloric acid ionise "
    "completely, while weak acids such as ethanoic acid only partially ionise. "
    "Neutralisation is the reaction of an acid with a base to form a salt and water: "
    "HCl + NaOH -> NaCl + H2O. Universal indicator turns red in strong acid and purple "
    "in strong alkali. Salts can be prepared by reacting acids with metals, bases, or "
    "carbonates.\n"
)


def log(msg):
    print(msg, flush=True)


def die(msg):
    sys.exit(f"✗ {msg}")


class Api:
    def __init__(self, base, token=None):
        self.base, self.token = base, token

    def _h(self, extra=None):
        h = {"accept": "application/json"}
        if self.token:
            h["authorization"] = f"Bearer {self.token}"
        if extra:
            h.update(extra)
        return h

    def get(self, path):
        return requests.get(self.base + path, headers=self._h(), timeout=60)

    def post(self, path, body=None, timeout=120):
        return requests.post(self.base + path, headers=self._h({"content-type": "application/json"}),
                             data=json.dumps(body or {}), timeout=timeout)

    def delete(self, path):
        return requests.delete(self.base + path, headers=self._h(), timeout=60)

    def upload(self, path, filepath):
        with open(filepath, "rb") as fh:
            return requests.post(self.base + path, headers=self._h(),
                                 files={"file": (os.path.basename(filepath), fh, "text/plain")},
                                 timeout=180)


def unwrap(resp):
    try:
        j = resp.json()
    except Exception:
        return {"_raw": resp.text}
    return j.get("data", j) if isinstance(j, dict) else j


def register_or_token(base):
    token = os.environ.get("EVAL_TOKEN")
    if token:
        return token
    email = f"eval+{int(time.time())}@apalchi-eval.com"
    r = Api(base).post("/api/v1/auth/register",
                       {"email": email, "password": "EvalProbe123!", "displayName": "Eval Probe"})
    token = (unwrap(r) or {}).get("token")
    if not token:
        die(f"register failed: {r.status_code} {r.text[:200]}")
    log(f"registered throwaway: {email}")
    return token


# every stage record gets dumped so a failure is attributable
STAGES = []


def record(name, http, body, note=""):
    s = {"stage": name, "http": http, "body": (body or "")[:400], "note": note}
    STAGES.append(s)
    (OUT / f"{len(STAGES):02d}_{name}.json").write_text(
        json.dumps({"http": http, "body": body, "note": note}, indent=2, default=str))
    log(f"  [{name}] HTTP {http}  {note}".rstrip())
    return s


def main():
    if ("railway.app" in BASE or "production" in BASE) and not ALLOW_PROD:
        die(f"BASE_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")
    OUT.mkdir(parents=True, exist_ok=True)
    log(f"BASE={BASE}  out={OUT}")

    api = Api(BASE, register_or_token(BASE))

    # ── centre onboard → org ──
    ob_r = api.post("/api/v1/centre/onboard", {"centreName": f"Eval Centre {int(time.time())}"})
    ob = unwrap(ob_r)
    org = ob.get("orgId") if isinstance(ob, dict) else None
    record("centre_onboard", ob_r.status_code, ob_r.text, f"orgId={org}")
    if not org:
        die(f"centre onboard failed: {ob_r.status_code} {ob_r.text[:200]}")

    # ── create class → corpus avatar ──
    cr_r = api.post(f"/api/v1/centre/organizations/{org}/classes",
                    {"name": "Eval Class", "subject": SUBJECT, "level": "Secondary 3"})
    cr = unwrap(cr_r)
    cls = cr.get("id") if isinstance(cr, dict) else None
    corpus = cr.get("corpusAvatarId") if isinstance(cr, dict) else None
    record("class_create", cr_r.status_code, cr_r.text, f"classId={cls} corpusAvatarId={corpus}")
    if not (cls and corpus):
        die(f"class create failed (no id/corpusAvatarId): {cr_r.status_code} {cr_r.text[:200]}")
    avatar = corpus

    # ── upload doc to the corpus avatar ──
    p = pathlib.Path(tempfile.gettempdir()) / f"acids-{int(time.time()*1000)}.txt"
    p.write_text(DOC)
    ur = api.upload(f"/api/v1/avatars/{avatar}/files", p)
    record("upload", ur.status_code, ur.text)
    if ur.status_code not in (200, 201):
        die(f"upload failed: {ur.status_code} {ur.text[:200]}")

    # ── compile + poll ──
    try:
        rc = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=240)
        compile_http, compile_body = rc.status_code, rc.text
    except requests.RequestException as e:
        compile_http, compile_body = f"EXC:{type(e).__name__}", str(e)
    state = "?"
    deadline = time.time() + TIMEOUT_S
    while time.time() < deadline:
        st = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/compile/status"))
        state = st.get("state", "?") if isinstance(st, dict) else "?"
        if state in ("COMPLETE", "COMPLETED", "DONE", "READY", "IDLE", "NONE"):
            break
        time.sleep(5)
    pages = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages"))
    page_list = pages.get("pages", pages) if isinstance(pages, dict) else pages
    page_list = page_list if isinstance(page_list, list) else []
    record("compile", compile_http, compile_body, f"state={state} pages={len(page_list)}")

    # ── modules/generate → GET modules ──
    gen = api.post(f"/api/v1/avatars/{avatar}/modules/generate", {})
    record("modules_generate", gen.status_code, gen.text)
    time.sleep(6)
    mods_r = api.get(f"/api/v1/avatars/{avatar}/modules")
    mods = unwrap(mods_r)
    mod_list = mods if isinstance(mods, list) else (mods.get("modules", []) if isinstance(mods, dict) else [])
    mod_ids = [m.get("moduleId") or m.get("id") for m in mod_list if (m.get("moduleId") or m.get("id"))]
    record("modules_list", mods_r.status_code, mods_r.text, f"count={len(mod_list)} ids={len(mod_ids)}")

    # ── quiz/daily ──
    quiz_r = api.get(f"/api/v1/avatars/{avatar}/quiz/daily")
    quiz = unwrap(quiz_r)
    q_items = quiz if isinstance(quiz, list) else (quiz.get("questions", quiz.get("items", [])) if isinstance(quiz, dict) else [])
    record("quiz_daily", quiz_r.status_code, quiz_r.text, f"questions={len(q_items or [])}")

    # ── create REVISION assignment over generated module IDs ──
    ar = api.post(f"/api/v1/centre/organizations/{org}/classes/{cls}/assignments",
                  {"title": "Eval Revision (full-workflow)", "type": "REVISION", "moduleIds": mod_ids})
    assign = record("assignment_create", ar.status_code, ar.text, f"module_count={len(mod_ids)}")

    passed = ar.status_code == 201

    # ── failure attribution ──
    why = None
    if not passed:
        if len(mod_ids) == 0:
            if str(compile_http) not in ("200", "202") or not page_list:
                why = (f"assignment {ar.status_code}: zero modules because compile produced no pages "
                       f"(compile HTTP {compile_http}, state {state}, {len(page_list)} pages)")
            elif not mod_list:
                why = (f"assignment {ar.status_code}: zero modules because modules/generate produced 0 "
                       f"(generate HTTP {gen.status_code})")
            else:
                why = (f"assignment {ar.status_code}: modules exist ({len(mod_list)}) but none had a "
                       f"resolvable id")
        else:
            why = f"assignment create failed with {len(mod_ids)} modules → HTTP {ar.status_code}: {ar.text[:200]}"

    report = {
        "base": BASE, "org": org, "class": cls, "corpus_avatar": avatar,
        "stages": STAGES,
        "compile_http": compile_http, "compile_state": state, "pages": len(page_list),
        "module_count": len(mod_list), "module_id_count": len(mod_ids),
        "quiz_questions": len(q_items or []),
        "assignment_http": ar.status_code,
        "failure_attribution": why,
        "result": "PASS" if passed else "FAIL",
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2, default=str))

    log("\n" + "=" * 70)
    log(f"FULL WORKFLOW SMOKE  ({BASE})")
    for s in STAGES:
        log(f"  {s['stage']:18s} HTTP {str(s['http']):>5s}  {s['note']}")
    if why:
        log(f"  ► failure attribution: {why}")
    log(f"  {'PASS' if passed else 'FAIL'}: assignment create returned {ar.status_code} (need 201)")
    log(f"  artifacts → {OUT}")
    log("=" * 70)

    if not passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
