#!/usr/bin/env python3
"""
content_quality_eval.py — fire the REAL content-generation pipeline on mock docs
and dump every generated artifact + a quality REPORT.md so we can SEE where
centre-side content degrades (extraction → wiki → modules → quiz → assignment).

This is a manual eval/diagnostic. It makes REAL Gemini/Claude calls (costs tokens)
and does NOT mock the AI — that's the point. Not for CI.

Config (env):
  BASE_URL        backend base (default http://localhost:8080). Refuses prod-looking
                  hosts unless ALLOW_PROD=1.
  ALLOW_PROD      set to 1 to allow a *.railway.app / production host.
  EVAL_TOKEN      JWT to use. If unset, registers a throwaway account.
  EVAL_DOCS_DIR   dir of mock docs (default: alongside this script, ./docs).
  EVAL_AVATAR_ID  reuse an avatar instead of creating a throwaway one.
  EVAL_SUBJECT    subject enum for a new avatar (default SCIENCE).
  MAX_DOCS        cap docs processed (default: all).
  RUN_ASSIGNMENT  1 → also create a REVISION assignment to repro the create failure;
                  needs EVAL_ORG_ID + EVAL_CLASS_ID.
  EVAL_ORG_ID, EVAL_CLASS_ID   centre context for the assignment repro.
  CLEANUP         1 → delete the throwaway avatar + files at the end (default: keep).

Usage:
  BASE_URL=http://localhost:8080 python3 tools/eval/content_quality_eval.py
  ALLOW_PROD=1 BASE_URL=https://pallybackend-production.up.railway.app \
      EVAL_DOCS_DIR=tools/eval/docs python3 tools/eval/content_quality_eval.py
"""
import json
import os
import re
import sys
import time
import shutil
import datetime
import pathlib

try:
    import requests
except ImportError:
    sys.exit("pip install requests  (needed for the eval harness)")

HERE = pathlib.Path(__file__).resolve().parent
BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
ALLOW_PROD = os.environ.get("ALLOW_PROD") == "1"
DOCS_DIR = pathlib.Path(os.environ.get("EVAL_DOCS_DIR", HERE / "docs"))
SUBJECT = os.environ.get("EVAL_SUBJECT", "SCIENCE")
MAX_DOCS = int(os.environ.get("MAX_DOCS", "0")) or None
RUN_ASSIGNMENT = os.environ.get("RUN_ASSIGNMENT") == "1"
CLEANUP = os.environ.get("CLEANUP") == "1"

OUT = HERE / "out" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
TIMEOUT_S = 180  # max wait per polling stage

STOP = set("the a an and or of to in is are was were be been being for on with as at by from "
           "this that these those it its their your you we our they he she his her them also "
           "into than then so such not no can will would each more most some any when which what "
           "how why where who whom whose if else only just very much many both all".split())


def log(msg):
    print(msg, flush=True)


def die(msg):
    sys.exit(f"✗ {msg}")


# ── HTTP ──────────────────────────────────────────────────────────────────────
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


# ── quality checks ───────────────────────────────────────────────────────────
def words(text):
    return [w for w in re.findall(r"[a-zA-Z]{2,}", (text or "").lower()) if w not in STOP]


def sentences(text):
    # strip markdown headers/bullets, split into rough sentences
    clean = re.sub(r"[#*`>\-]", " ", text or "")
    return [s.strip() for s in re.split(r"(?<=[.!?])\s+|\n", clean) if len(s.strip()) > 25]


def groundedness(page_text, source_text):
    """Fraction of significant words per sentence that appear in the source.
    Returns (score 0..1, list of weakly-grounded sentences)."""
    src = set(words(source_text))
    weak = []
    scores = []
    for s in sentences(page_text):
        sw = words(s)
        if len(sw) < 4:
            continue
        contained = sum(1 for w in sw if w in src) / len(sw)
        scores.append(contained)
        if contained < 0.45:
            weak.append((round(contained, 2), s[:160]))
    return (round(sum(scores) / len(scores), 2) if scores else 0.0), weak


SAFE_MATH = re.compile(r"^[0-9+\-*/(). ]+$")


def math_checks(text):
    """Find '... = <number>' arithmetic and verify it. Returns list of (ok, expr)."""
    out = []
    for lhs, rhs in re.findall(r"([0-9][0-9+\-*/(). ]{2,}?)\s*=\s*([0-9.]+)", text or ""):
        lhs_s = lhs.strip()
        if not SAFE_MATH.match(lhs_s) or not any(op in lhs_s for op in "+-*/"):
            continue
        try:
            got = eval(lhs_s, {"__builtins__": {}}, {})  # safe: regex-restricted to math chars
            ok = abs(float(got) - float(rhs)) < 1e-6
            out.append((ok, f"{lhs_s} = {rhs}  (computed {got})"))
        except Exception:
            continue
    return out


# ── main flow ────────────────────────────────────────────────────────────────
def main():
    if ("railway.app" in BASE or "production" in BASE) and not ALLOW_PROD:
        die(f"BASE_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")

    if not DOCS_DIR.is_dir():
        die(f"EVAL_DOCS_DIR not found: {DOCS_DIR}")
    docs = sorted([p for p in DOCS_DIR.iterdir() if p.is_file() and not p.name.startswith(".")])
    if MAX_DOCS:
        docs = docs[:MAX_DOCS]
    if not docs:
        die(f"no docs in {DOCS_DIR}")

    OUT.mkdir(parents=True, exist_ok=True)
    for sub in ("01_extracted", "02_wiki", "03_modules", "04_quiz", "05_assignment", "INPUTS"):
        (OUT / sub).mkdir(exist_ok=True)
    for d in docs:
        shutil.copy(d, OUT / "INPUTS" / d.name)

    log(f"BASE={BASE}  docs={len(docs)}  out={OUT}")

    # auth
    token = os.environ.get("EVAL_TOKEN")
    created_account = False
    if not token:
        email = f"eval+{int(time.time())}@apalchi-eval.com"
        r = Api(BASE).post("/api/v1/auth/register",
                           {"email": email, "password": "EvalProbe123!", "displayName": "Eval Probe"})
        token = (unwrap(r) or {}).get("token")
        if not token:
            die(f"register failed: {r.status_code} {r.text[:200]}")
        created_account = True
        log(f"registered throwaway: {email}")
    api = Api(BASE, token)

    # avatar
    avatar = os.environ.get("EVAL_AVATAR_ID")
    created_avatar = False
    if not avatar:
        r = api.post("/api/v1/avatars", {"name": "Eval Mochi", "subject": SUBJECT, "characterType": "MOCHI"})
        avatar = (unwrap(r) or {}).get("id")
        if not avatar:
            die(f"avatar create failed: {r.status_code} {r.text[:200]}")
        created_avatar = True
    log(f"avatar={avatar}")

    report = {"base": BASE, "avatar": avatar, "stages": {}, "docs": [d.name for d in docs]}
    source_corpus = "\n".join(p.read_text(errors="ignore") for d in docs for p in [d])

    # ── stage 1: upload + extraction ─────────────────────────────────────────
    uploads = []
    for d in docs:
        r = api.upload(f"/api/v1/avatars/{avatar}/files", d)
        u = unwrap(r)
        (OUT / "01_extracted" / f"{d.name}.upload.json").write_text(json.dumps(u, indent=2))
        uploads.append((d.name, r.status_code, u))
        log(f"  upload {d.name} → {r.status_code} quality={u.get('quality')} pages={u.get('pageCount')}")
    ok_uploads = [u for u in uploads if u[1] in (200, 201)]
    report["stages"]["extraction"] = {
        "status": "PASS" if len(ok_uploads) == len(docs) else ("DEGRADED" if ok_uploads else "FAIL"),
        "uploaded": len(ok_uploads), "total": len(docs),
        "quality": [u[2].get("quality") for u in uploads],
        "low_quality": [u[0] for u in uploads if u[2].get("quality") not in ("GOOD", None)],
    }

    # ── stage 2: wiki compile ────────────────────────────────────────────────
    # The sync path can be slow; a slow/500 compile must NOT crash the run — the
    # generation may still finish, so we always fall through to polling + fetch.
    compile_http = None
    try:
        rc = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=240)
        compile_http = rc.status_code
        (OUT / "02_wiki" / "compile.json").write_text(rc.text)
    except requests.RequestException as e:
        compile_http = f"EXC:{type(e).__name__}"
        (OUT / "02_wiki" / "compile.json").write_text(json.dumps({"exception": str(e)}))
    log(f"  wiki/compile → {compile_http}")
    report["compile_http"] = compile_http
    deadline = time.time() + TIMEOUT_S
    while time.time() < deadline:
        st = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/compile/status"))
        state = st.get("state", "?")
        if state in ("COMPLETE", "COMPLETED", "DONE", "READY", "IDLE", "NONE"):
            break
        time.sleep(5)
    pages = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages"))
    page_list = pages.get("pages", pages) if isinstance(pages, dict) else pages
    page_list = page_list if isinstance(page_list, list) else []
    ground_scores, all_weak = [], []
    for p in page_list:
        slug = p.get("slug", "page")
        full = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages/{slug}")) if slug else p
        body = full.get("content", p.get("content", ""))
        (OUT / "02_wiki" / f"{slug}.md").write_text(
            f"# {full.get('title','?')}\n\n_certainty={full.get('certainty')} quality={full.get('qualityScore')}_\n\n{body}")
        g, weak = groundedness(body, source_corpus)
        ground_scores.append(g)
        for sc, sent in weak:
            all_weak.append({"page": full.get("title"), "score": sc, "sentence": sent})
    report["stages"]["wiki"] = {
        "status": "FAIL" if not page_list
                  else ("DEGRADED" if (str(compile_http) != "200" or min(ground_scores or [0]) < 0.5)
                        else "PASS"),
        "compile_http": compile_http,
        "pages": len(page_list),
        "titles": [p.get("title") for p in page_list],
        "avg_groundedness": round(sum(ground_scores) / len(ground_scores), 2) if ground_scores else 0.0,
        "weakly_grounded_claims": all_weak[:20],
    }
    # math determinism over wiki bodies
    math = []
    for p in page_list:
        for ok, expr in math_checks(p.get("content", "")):
            math.append({"ok": ok, "expr": expr, "page": p.get("title")})
    report["stages"]["math"] = {
        "checked": len(math),
        "wrong": [m for m in math if not m["ok"]],
        "status": "PASS" if math and all(m["ok"] for m in math) else ("DEGRADED" if any(not m["ok"] for m in math) else "N/A"),
    }

    # ── stage 3: modules ─────────────────────────────────────────────────────
    gen = api.post(f"/api/v1/avatars/{avatar}/modules/generate", {})
    (OUT / "03_modules" / "generate.json").write_text(gen.text)
    time.sleep(6)
    mods = unwrap(api.get(f"/api/v1/avatars/{avatar}/modules"))
    mod_list = mods if isinstance(mods, list) else mods.get("modules", [])
    for m in mod_list:
        (OUT / "03_modules" / f"{(m.get('title') or m.get('stage') or 'module')[:40]}.json").write_text(json.dumps(m, indent=2))
    report["stages"]["modules"] = {
        "status": "PASS" if mod_list else "FAIL",
        "count": len(mod_list),
        "titles": [m.get("title") for m in mod_list],
        "null_moduleId": sum(1 for m in mod_list if not m.get("moduleId") and not m.get("id")),
    }

    # ── stage 4: quiz + flashcards ───────────────────────────────────────────
    quiz = unwrap(api.get(f"/api/v1/avatars/{avatar}/quiz/daily"))
    (OUT / "04_quiz" / "quiz_daily.json").write_text(json.dumps(quiz, indent=2))
    q_items = quiz if isinstance(quiz, list) else quiz.get("questions", quiz.get("items", []))
    q_math = []
    for q in (q_items or []):
        for ok, expr in math_checks(json.dumps(q)):
            q_math.append({"ok": ok, "expr": expr})
    report["stages"]["quiz"] = {
        "status": "PASS" if q_items else "DEGRADED",
        "count": len(q_items or []),
        "math_wrong": [m for m in q_math if not m["ok"]],
    }

    # ── stage 5: assignment repro (optional, centre) ─────────────────────────
    if RUN_ASSIGNMENT:
        org, cls = os.environ.get("EVAL_ORG_ID"), os.environ.get("EVAL_CLASS_ID")
        if not (org and cls):
            report["stages"]["assignment"] = {"status": "SKIPPED", "reason": "EVAL_ORG_ID/EVAL_CLASS_ID not set"}
        else:
            ar = api.post(f"/api/v1/centre/organizations/{org}/classes/{cls}/assignments",
                          {"title": "Eval Revision", "type": "REVISION", "moduleIds": []})
            (OUT / "05_assignment" / "create.json").write_text(ar.text)
            report["stages"]["assignment"] = {
                "status": "PASS" if ar.status_code in (200, 201) else "FAIL",
                "http": ar.status_code, "body": ar.text[:300],
            }

    # ── failure attribution ──────────────────────────────────────────────────
    culprit = None
    for stage in ("extraction", "wiki", "modules", "quiz"):
        if report["stages"].get(stage, {}).get("status") in ("FAIL", "DEGRADED"):
            culprit = stage
            break
    report["most_likely_failure_point"] = culprit or "none — chain healthy"

    (OUT / "report.json").write_text(json.dumps(report, indent=2))
    write_report_md(report)

    if CLEANUP and created_avatar:
        d = api.delete(f"/api/v1/avatars/{avatar}")
        log(f"cleanup avatar → {d.status_code}")

    # one-screen summary
    log("\n" + "=" * 70)
    log(f"CONTENT-QUALITY EVAL  ({BASE})")
    for st in ("extraction", "wiki", "math", "modules", "quiz", "assignment"):
        s = report["stages"].get(st)
        if s:
            log(f"  {st:11s}: {s['status']:9s} {summary_line(st, s)}")
    log(f"  ► most likely failure point: {report['most_likely_failure_point']}")
    log(f"  artifacts + REPORT.md → {OUT}")
    log("=" * 70)


def summary_line(stage, s):
    if stage == "wiki":
        return f"compile HTTP {s.get('compile_http')}, {s['pages']} pages, groundedness={s['avg_groundedness']}, {len(s['weakly_grounded_claims'])} weak claims"
    if stage == "modules":
        return f"{s['count']} modules"
    if stage == "math":
        return f"{s['checked']} checked, {len(s['wrong'])} wrong"
    if stage == "quiz":
        return f"{s['count']} questions"
    if stage == "extraction":
        return f"{s['uploaded']}/{s['total']} uploaded, low-quality={s['low_quality']}"
    if stage == "assignment":
        return f"HTTP {s.get('http')}"
    return ""


def write_report_md(r):
    L = [f"# Content-quality eval — {r['avatar']}", "", f"Backend: `{r['base']}`  ",
         f"Docs: {', '.join(r['docs'])}", "",
         f"**Most likely failure point: {r['most_likely_failure_point']}**", "",
         "| Stage | Status | Notes |", "|---|---|---|"]
    for st in ("extraction", "wiki", "math", "modules", "quiz", "assignment"):
        s = r["stages"].get(st)
        if s:
            L.append(f"| {st} | {s['status']} | {summary_line(st, s)} |")
    w = r["stages"].get("wiki", {})
    if w.get("weakly_grounded_claims"):
        L += ["", "## ⚠ Weakly-grounded wiki claims (possible hallucination)", ""]
        for c in w["weakly_grounded_claims"]:
            L.append(f"- _(score {c['score']})_ **{c['page']}**: {c['sentence']}")
    m = r["stages"].get("math", {})
    if m.get("wrong"):
        L += ["", "## ✗ Math mismatches", ""] + [f"- {x['expr']} — {x.get('page','')}" for x in m["wrong"]]
    a = r["stages"].get("assignment")
    if a and a.get("status") != "SKIPPED":
        L += ["", "## Assignment create (repro)", "", f"HTTP {a.get('http')} — `{a.get('body','')}`"]
    (OUT / "REPORT.md").write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    main()
