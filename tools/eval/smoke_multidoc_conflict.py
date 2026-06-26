#!/usr/bin/env python3
"""
smoke_multidoc_conflict.py — REAL-API smoke: two overlapping docs that DISAGREE on
one number, compile, and verify the compile does NOT 400/500 on the conflict.

The bug class: when two uploaded notes contradict each other on the same topic, the
wiki compile has to reconcile them. We want to know (a) compile still succeeds
(200/202, pages exist), (b) which value "won", and (c) whether the page is flagged
(`hasConflict` / `conflictNote`). This tells us if reconciliation is last-write-wins
and whether the conflict is surfaced or silently swallowed.

Makes REAL Gemini/Claude calls (costs tokens). NOT for CI. DO NOT point at prod.

Config (env):
  BASE_URL    backend base (default http://localhost:8080). Refuses prod-looking
              hosts unless ALLOW_PROD=1.
  ALLOW_PROD  set to 1 to allow a *.railway.app / production host.
  EVAL_TOKEN  JWT to use. If unset, registers a throwaway account.
  EVAL_SUBJECT subject enum for the throwaway avatar (default SCIENCE).

Exit non-zero on a 400 compile (the regression we guard).

Usage:
  BASE_URL=http://localhost:8080 python3 tools/eval/smoke_multidoc_conflict.py
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

# Two docs about the SAME topic (mitochondria) that DISAGREE on the ATP number.
DOC_A = (
    "Cell Biology: The Mitochondria\n\n"
    "The mitochondria is the powerhouse of the cell, producing 36 ATP per glucose "
    "molecule during aerobic respiration. Mitochondria have a double membrane: a smooth "
    "outer membrane and a folded inner membrane called the cristae. The matrix inside "
    "hosts the Krebs cycle. Mitochondria contain their own DNA and are inherited "
    "maternally. They are the site of oxidative phosphorylation.\n"
)
DOC_B = (
    "Respiration Notes: Mitochondria\n\n"
    "Mitochondria produce 38 ATP per glucose molecule through cellular respiration. "
    "The mitochondria is the powerhouse of the cell. Its inner membrane folds form the "
    "cristae, increasing surface area for the electron transport chain. The fluid matrix "
    "contains enzymes for the Krebs cycle. Mitochondria are double-membraned organelles.\n"
)


def log(msg):
    print(msg, flush=True)


def die(msg):
    sys.exit(f"✗ {msg}")


# ── HTTP (mirrors content_quality_eval.py Api) ────────────────────────────────
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


def write_temp(name, body):
    p = pathlib.Path(tempfile.gettempdir()) / f"{name}-{int(time.time()*1000)}.txt"
    p.write_text(body)
    return p


def poll_compile(api, avatar):
    deadline = time.time() + TIMEOUT_S
    last = "?"
    while time.time() < deadline:
        st = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/compile/status"))
        last = st.get("state", "?") if isinstance(st, dict) else "?"
        if last in ("COMPLETE", "COMPLETED", "DONE", "READY", "IDLE", "NONE"):
            return last
        time.sleep(5)
    return last


def main():
    if ("railway.app" in BASE or "production" in BASE) and not ALLOW_PROD:
        die(f"BASE_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")
    OUT.mkdir(parents=True, exist_ok=True)
    log(f"BASE={BASE}  out={OUT}")

    api = Api(BASE, register_or_token(BASE))

    # throwaway avatar
    r = api.post("/api/v1/avatars", {"name": "Conflict Mochi", "subject": SUBJECT, "characterType": "MOCHI"})
    avatar = (unwrap(r) or {}).get("id")
    if not avatar:
        die(f"avatar create failed: {r.status_code} {r.text[:200]}")
    log(f"avatar={avatar}")

    # upload both contradictory docs
    pa, pb = write_temp("docA", DOC_A), write_temp("docB", DOC_B)
    for label, p in (("A(36 ATP)", pa), ("B(38 ATP)", pb)):
        ur = api.upload(f"/api/v1/avatars/{avatar}/files", p)
        (OUT / f"upload_{label[0]}.json").write_text(ur.text)
        log(f"  upload {label} → {ur.status_code}")
        if ur.status_code not in (200, 201):
            die(f"upload {label} failed: {ur.status_code} {ur.text[:200]}")

    # compile — the path under test
    try:
        rc = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=240)
        compile_http = rc.status_code
        (OUT / "compile.json").write_text(rc.text)
    except requests.RequestException as e:
        compile_http = f"EXC:{type(e).__name__}"
        (OUT / "compile.json").write_text(json.dumps({"exception": str(e)}))
    log(f"  wiki/compile → {compile_http}")

    state = poll_compile(api, avatar)
    log(f"  compile state → {state}")

    pages = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages"))
    page_list = pages.get("pages", pages) if isinstance(pages, dict) else pages
    page_list = page_list if isinstance(page_list, list) else []
    (OUT / "pages_index.json").write_text(json.dumps(page_list, indent=2))

    won = None  # "36" / "38" / "both" / None
    conflict_pages = []
    seen_36 = seen_38 = False
    for p in page_list:
        slug = p.get("slug", "")
        full = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages/{slug}")) if slug else p
        body = (full.get("content") or p.get("content") or "")
        (OUT / f"page_{slug or 'page'}.md").write_text(
            f"# {full.get('title','?')}\n\nhasConflict={full.get('hasConflict')} "
            f"conflictNote={full.get('conflictNote')!r}\n\n{body}")
        if "36 ATP" in body or "36 atp" in body.lower():
            seen_36 = True
        if "38 ATP" in body or "38 atp" in body.lower():
            seen_38 = True
        if full.get("hasConflict") or full.get("conflictNote"):
            conflict_pages.append({"slug": slug, "title": full.get("title"),
                                   "hasConflict": full.get("hasConflict"),
                                   "conflictNote": full.get("conflictNote")})

    if seen_36 and seen_38:
        won = "both"
    elif seen_36:
        won = "36 (doc A)"
    elif seen_38:
        won = "38 (doc B)"

    # compile must not have 4xx/5xx; pages must exist
    http_ok = str(compile_http) in ("200", "202")
    pages_ok = len(page_list) > 0
    passed = http_ok and pages_ok

    report = {
        "base": BASE, "avatar": avatar, "compile_http": compile_http,
        "compile_state": state, "page_count": len(page_list),
        "atp_value_won": won, "saw_36": seen_36, "saw_38": seen_38,
        "conflict_flagged_pages": conflict_pages,
        "reconciliation": ("last-write-wins (38/doc B)" if won == "38 (doc B)"
                           else "first-wins (36/doc A)" if won == "36 (doc A)"
                           else "both-retained" if won == "both" else "neither/unknown"),
        "result": "PASS" if passed else "FAIL",
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2))

    log("\n" + "=" * 70)
    log(f"MULTIDOC CONFLICT SMOKE  ({BASE})")
    log(f"  compile HTTP={compile_http} state={state} pages={len(page_list)}")
    log(f"  ATP value that won: {won}  → reconciliation: {report['reconciliation']}")
    if conflict_pages:
        for c in conflict_pages:
            log(f"  ⚑ conflict on '{c['title']}': note={c['conflictNote']!r}")
    else:
        log("  (no page had hasConflict/conflictNote set — conflict not surfaced)")
    log(f"  {'PASS' if passed else 'FAIL'}: compile did not 4xx/5xx and pages exist")
    log(f"  artifacts → {OUT}")
    log("=" * 70)

    # Exit non-zero specifically on a 400 (the regression we guard), or any non-pass.
    if str(compile_http) == "400":
        die("compile returned 400 on contradictory docs — conflict reconciliation regressed")
    if not passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
