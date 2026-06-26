#!/usr/bin/env python3
"""
smoke_adversarial_length.py — REAL-API smoke: feed the compiler content engineered
to produce LONG generated fields (long title/slug-driving heading, and a long
contradictory restatement to force a long conflict note), then assert the compile
does NOT 400 and pages persist.

This is the regression guard for the widened columns that prod incidents forced:
  V92  wiki_pages.title  → TEXT,  slug → VARCHAR(160)
  V93  wiki_pages.conflict_note → TEXT
A long generated title/slug/conflict_note used to overflow VARCHAR(100/255) and
fail the whole compile intermittently. If a future migration re-narrows any of
these, this smoke catches the overflow 400 before a student does.

Makes REAL Gemini/Claude calls (costs tokens). NOT for CI. DO NOT point at prod.

Config (env):
  BASE_URL    backend base (default http://localhost:8080). Refuses prod-looking
              hosts unless ALLOW_PROD=1.
  ALLOW_PROD  set to 1 to allow a *.railway.app / production host.
  EVAL_TOKEN  JWT to use. If unset, registers a throwaway account.
  EVAL_SUBJECT subject enum for the throwaway avatar (default SCIENCE).

Usage:
  BASE_URL=http://localhost:8080 python3 tools/eval/smoke_adversarial_length.py
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

# A deliberately long heading (drives a long title/slug) + body. The heading is a
# single run-on topic name so the compiler is tempted to emit a very long title.
LONG_HEADING = (
    "The Comprehensive and Exhaustively Detailed Study of Cellular Aerobic Respiration "
    "Including Glycolysis the Krebs Cycle Oxidative Phosphorylation and the Complete "
    "Electron Transport Chain Across the Inner Mitochondrial Membrane in Eukaryotic Cells"
)

DOC_A = (
    f"{LONG_HEADING}\n\n"
    "Aerobic respiration releases energy from glucose in the presence of oxygen. The net "
    "yield commonly stated in textbooks is 36 ATP molecules per glucose molecule. The "
    "process spans glycolysis in the cytoplasm, the link reaction, the Krebs cycle in the "
    "mitochondrial matrix, and oxidative phosphorylation along the cristae. Each stage "
    "transfers electrons via NADH and FADH2 to the electron transport chain.\n"
)

# Second upload: same long-heading topic, contradicts the ATP figure with a long,
# wordy restatement — engineered so any generated conflict note is also long.
DOC_B = (
    f"{LONG_HEADING}\n\n"
    "Contrary to the simplified figure, a careful accounting of cellular aerobic "
    "respiration that includes the cost of transporting cytoplasmic NADH into the "
    "mitochondrion and the proton-motive-force stoichiometry yields 38 ATP molecules per "
    "glucose molecule rather than 36, a discrepancy that depends on the shuttle mechanism "
    "used and the assumed P/O ratio, and which textbooks resolve differently depending on "
    "whether they round the ATP-synthase stoichiometry up or down. This is a frequently "
    "examined and frequently disputed numerical detail in the syllabus.\n"
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


def write_temp(name, body):
    p = pathlib.Path(tempfile.gettempdir()) / f"{name}-{int(time.time()*1000)}.txt"
    p.write_text(body)
    return p


def main():
    if ("railway.app" in BASE or "production" in BASE) and not ALLOW_PROD:
        die(f"BASE_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")
    OUT.mkdir(parents=True, exist_ok=True)
    log(f"BASE={BASE}  out={OUT}")

    api = Api(BASE, register_or_token(BASE))

    r = api.post("/api/v1/avatars", {"name": "LongField Mochi", "subject": SUBJECT, "characterType": "MOCHI"})
    avatar = (unwrap(r) or {}).get("id")
    if not avatar:
        die(f"avatar create failed: {r.status_code} {r.text[:200]}")
    log(f"avatar={avatar}  heading_len={len(LONG_HEADING)}")

    for label, body in (("A", DOC_A), ("B", DOC_B)):
        p = write_temp(f"long{label}", body)
        ur = api.upload(f"/api/v1/avatars/{avatar}/files", p)
        (OUT / f"upload_{label}.json").write_text(ur.text)
        log(f"  upload {label} ({len(body)} chars) → {ur.status_code}")
        if ur.status_code not in (200, 201):
            die(f"upload {label} failed: {ur.status_code} {ur.text[:200]}")

    try:
        rc = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=240)
        compile_http = rc.status_code
        (OUT / "compile.json").write_text(rc.text)
    except requests.RequestException as e:
        compile_http = f"EXC:{type(e).__name__}"
        (OUT / "compile.json").write_text(json.dumps({"exception": str(e)}))
    log(f"  wiki/compile → {compile_http}")

    deadline = time.time() + TIMEOUT_S
    state = "?"
    while time.time() < deadline:
        st = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/compile/status"))
        state = st.get("state", "?") if isinstance(st, dict) else "?"
        if state in ("COMPLETE", "COMPLETED", "DONE", "READY", "IDLE", "NONE"):
            break
        time.sleep(5)

    pages = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages"))
    page_list = pages.get("pages", pages) if isinstance(pages, dict) else pages
    page_list = page_list if isinstance(page_list, list) else []
    (OUT / "pages_index.json").write_text(json.dumps(page_list, indent=2))

    longest_title = 0
    longest_slug = 0
    longest_conflict = 0
    for p in page_list:
        slug = p.get("slug", "")
        full = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages/{slug}")) if slug else p
        (OUT / f"page_{slug or 'page'}.md").write_text(
            f"# {full.get('title','?')}\n\nslug={slug}\nhasConflict={full.get('hasConflict')}\n"
            f"conflictNote={full.get('conflictNote')!r}\n\n{full.get('content','')}")
        longest_title = max(longest_title, len(full.get("title") or ""))
        longest_slug = max(longest_slug, len(slug or ""))
        longest_conflict = max(longest_conflict, len(full.get("conflictNote") or ""))

    no_400 = str(compile_http) != "400"
    pages_ok = len(page_list) > 0
    passed = no_400 and str(compile_http) in ("200", "202") and pages_ok

    report = {
        "base": BASE, "avatar": avatar, "compile_http": compile_http,
        "compile_state": state, "page_count": len(page_list),
        "input_heading_len": len(LONG_HEADING),
        "longest_generated_title": longest_title,
        "longest_generated_slug": longest_slug,
        "longest_generated_conflict_note": longest_conflict,
        "result": "PASS" if passed else "FAIL",
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2))

    log("\n" + "=" * 70)
    log(f"ADVERSARIAL LENGTH SMOKE  ({BASE})")
    log(f"  compile HTTP={compile_http} state={state} pages={len(page_list)}")
    log(f"  longest generated → title={longest_title}  slug={longest_slug}  "
        f"conflict_note={longest_conflict}")
    log(f"  {'PASS' if passed else 'FAIL'}: compile did not 400 and pages persisted")
    log(f"  artifacts → {OUT}")
    log("=" * 70)

    if str(compile_http) == "400":
        die("compile 400 on long generated fields — a bounded column likely re-narrowed (V92/V93 regressed)")
    if not passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
