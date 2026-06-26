#!/usr/bin/env python3
"""
smoke_recompile_idempotency.py — REAL-API smoke: compiling the SAME corpus twice
must be idempotent — same page count, same slug set, no duplicate pages, no 4xx.

The bug class: a re-compile that re-inserts pages instead of upserting on
(avatar_id, slug) → duplicate wiki pages, drifting page counts, and a unique-
constraint 500 on the second run. This pins the invariant: compile is idempotent.

Makes REAL Gemini/Claude calls (costs tokens). NOT for CI. DO NOT point at prod.

Config (env):
  BASE_URL    backend base (default http://localhost:8080). Refuses prod-looking
              hosts unless ALLOW_PROD=1.
  ALLOW_PROD  set to 1 to allow a *.railway.app / production host.
  EVAL_TOKEN  JWT to use. If unset, registers a throwaway account.
  EVAL_SUBJECT subject enum for the throwaway avatar (default SCIENCE).

Usage:
  BASE_URL=http://localhost:8080 python3 tools/eval/smoke_recompile_idempotency.py
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
    "Photosynthesis Overview\n\n"
    "Photosynthesis is the process by which green plants convert light energy into "
    "chemical energy stored in glucose. It occurs in the chloroplasts, mainly in the "
    "leaf mesophyll. The light-dependent reactions happen in the thylakoid membranes and "
    "produce ATP and NADPH. The light-independent reactions (the Calvin cycle) occur in "
    "the stroma and fix carbon dioxide into glucose. The overall equation is "
    "6CO2 + 6H2O -> C6H12O6 + 6O2. Chlorophyll absorbs red and blue light most strongly "
    "and reflects green light, which is why leaves appear green.\n"
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


def compile_and_poll(api, avatar, label):
    """Fire compile, poll to terminal state, return (http, slug_list)."""
    try:
        rc = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=240)
        http = rc.status_code
        (OUT / f"compile_{label}.json").write_text(rc.text)
    except requests.RequestException as e:
        http = f"EXC:{type(e).__name__}"
        (OUT / f"compile_{label}.json").write_text(json.dumps({"exception": str(e)}))
    log(f"  compile[{label}] → {http}")
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
    slugs = sorted(p.get("slug", "") for p in page_list)
    (OUT / f"pages_{label}.json").write_text(json.dumps(page_list, indent=2))
    return http, slugs


def main():
    if ("railway.app" in BASE or "production" in BASE) and not ALLOW_PROD:
        die(f"BASE_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")
    OUT.mkdir(parents=True, exist_ok=True)
    log(f"BASE={BASE}  out={OUT}")

    api = Api(BASE, register_or_token(BASE))

    r = api.post("/api/v1/avatars", {"name": "Idempo Mochi", "subject": SUBJECT, "characterType": "MOCHI"})
    avatar = (unwrap(r) or {}).get("id")
    if not avatar:
        die(f"avatar create failed: {r.status_code} {r.text[:200]}")
    log(f"avatar={avatar}")

    p = pathlib.Path(tempfile.gettempdir()) / f"photosynthesis-{int(time.time()*1000)}.txt"
    p.write_text(DOC)
    ur = api.upload(f"/api/v1/avatars/{avatar}/files", p)
    (OUT / "upload.json").write_text(ur.text)
    log(f"  upload → {ur.status_code}")
    if ur.status_code not in (200, 201):
        die(f"upload failed: {ur.status_code} {ur.text[:200]}")

    http1, slugs1 = compile_and_poll(api, avatar, "1")
    http2, slugs2 = compile_and_poll(api, avatar, "2")

    set1, set2 = set(slugs1), set(slugs2)
    count_stable = len(slugs1) == len(slugs2)
    slugs_identical = set1 == set2
    dup_in_2 = len(slugs2) != len(set2)  # duplicate slugs in second run
    no_4xx = all(str(h)[:1] != "4" for h in (http1, http2))

    passed = no_4xx and count_stable and slugs_identical and not dup_in_2

    report = {
        "base": BASE, "avatar": avatar,
        "compile_http_run1": http1, "compile_http_run2": http2,
        "page_count_run1": len(slugs1), "page_count_run2": len(slugs2),
        "count_stable": count_stable, "slug_set_identical": slugs_identical,
        "duplicate_slugs_run2": dup_in_2,
        "added_slugs": sorted(set2 - set1), "removed_slugs": sorted(set1 - set2),
        "result": "PASS" if passed else "FAIL",
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2))

    log("\n" + "=" * 70)
    log(f"RECOMPILE IDEMPOTENCY SMOKE  ({BASE})")
    log(f"  run1: HTTP {http1}, {len(slugs1)} pages")
    log(f"  run2: HTTP {http2}, {len(slugs2)} pages")
    log(f"  count stable={count_stable}  slug set identical={slugs_identical}  dup slugs={dup_in_2}")
    if set2 - set1:
        log(f"  + appeared on recompile: {sorted(set2 - set1)}")
    if set1 - set2:
        log(f"  - vanished on recompile: {sorted(set1 - set2)}")
    log(f"  {'PASS' if passed else 'FAIL'}")
    log(f"  artifacts → {OUT}")
    log("=" * 70)

    if not passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
