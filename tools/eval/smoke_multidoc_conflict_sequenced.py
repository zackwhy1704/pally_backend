#!/usr/bin/env python3
"""
smoke_multidoc_conflict_sequenced.py — exercise the REAL detectConflict path.

The batched smoke (smoke_multidoc_conflict.py) uploads both docs and compiles ONCE,
so Gemini merges them into a single page in one pass — no same-slug collision, so
detectConflict (which runs only on the UPDATE branch) never fires. This sequenced
variant forces the collision:

  1. upload doc A (36 ATP) → compile → a "mitochondria" page is persisted.
  2. upload doc B (38 ATP) → RECOMPILE → incremental compile feeds only the new
     file (B), producing a draft whose slug collides with the existing page →
     detectConflict(existingA, newB) runs → our deterministic fact diff can fire.

Then we read the page's hasConflict / conflictNote to see whether the 36-vs-38
contradiction is now surfaced with a concrete note.

CAVEAT (reported, not hidden): both pages are LLM-WRITTEN, so the contradicting
number may sit in DIFFERENT prose context across the two — the deterministic check
needs a matching context window, and Gemini's rephrasing can break it (in which case
the gray-band Haiku may or may not catch it). This probe reports the actual outcome.

Real Gemini/Claude calls (costs tokens). NOT for CI. Refuses prod unless ALLOW_PROD=1.

Usage:
  ALLOW_PROD=1 BASE_URL=<host> EVAL_SUBJECT=GENERAL python3 \
      tools/eval/smoke_multidoc_conflict_sequenced.py
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
OUT = HERE / "out" / ("seq-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
TIMEOUT_S = 200

DOC_A = (
    "Cell Biology: The Mitochondria\n\n"
    "The mitochondria is the powerhouse of the cell, producing 36 ATP per glucose "
    "molecule during aerobic respiration. Mitochondria have a double membrane: a smooth "
    "outer membrane and a folded inner membrane called the cristae. The matrix inside "
    "hosts the Krebs cycle. Mitochondria contain their own DNA and are inherited "
    "maternally. They are the site of oxidative phosphorylation.\n"
)
DOC_B = (
    # Same topic, DIFFERENT wording — must stay under the ~0.8 similarity/dup guard
    # (a near-identical doc is rejected 409), while still contradicting on the number.
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


def poll_state(api, avatar, want_pages=None):
    """Poll compile-status until terminal; optionally wait until pages exist."""
    deadline = time.time() + TIMEOUT_S
    last = "?"
    while time.time() < deadline:
        st = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/compile/status"))
        last = st.get("state", "?") if isinstance(st, dict) else "?"
        pages = fetch_pages(api, avatar)
        if last in ("COMPLETE", "COMPLETED", "DONE", "READY", "IDLE", "NONE"):
            if want_pages is None or len(pages) >= want_pages:
                return last, pages
        time.sleep(5)
    return last, fetch_pages(api, avatar)


def fetch_pages(api, avatar):
    pages = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages"))
    pl = pages.get("pages", pages) if isinstance(pages, dict) else pages
    return pl if isinstance(pl, list) else []


def page_details(api, avatar, page_list):
    out = []
    for p in page_list:
        slug = p.get("slug", "")
        full = unwrap(api.get(f"/api/v1/avatars/{avatar}/wiki/pages/{slug}")) if slug else p
        out.append({
            "slug": slug,
            "title": full.get("title"),
            "hasConflict": full.get("hasConflict"),
            "conflictNote": full.get("conflictNote"),
            "saw_36": "36 atp" in (full.get("content") or "").lower(),
            "saw_38": "38 atp" in (full.get("content") or "").lower(),
        })
    return out


def main():
    if ("railway.app" in BASE or "production" in BASE) and not ALLOW_PROD:
        die(f"BASE_URL looks like prod ({BASE}). Refusing. Set ALLOW_PROD=1 to override.")
    OUT.mkdir(parents=True, exist_ok=True)
    log(f"BASE={BASE}  out={OUT}")

    api = Api(BASE, register_or_token(BASE))
    r = api.post("/api/v1/avatars", {"name": "Seq Conflict Mochi", "subject": SUBJECT, "characterType": "MOCHI"})
    avatar = (unwrap(r) or {}).get("id")
    if not avatar:
        die(f"avatar create failed: {r.status_code} {r.text[:200]}")
    log(f"avatar={avatar}")

    # ── Step 1: doc A → compile → page persisted ─────────────────────────────
    pa = write_temp("docA", DOC_A)
    ua = api.upload(f"/api/v1/avatars/{avatar}/files", pa)
    log(f"  [1] upload A(36 ATP) → {ua.status_code}")
    if ua.status_code not in (200, 201):
        die(f"upload A failed: {ua.status_code}")
    ca = api.post(f"/api/v1/avatars/{avatar}/wiki/compile", {}, timeout=240)
    log(f"  [1] compile A → {ca.status_code}")
    state_a, pages_a = poll_state(api, avatar, want_pages=1)
    log(f"  [1] state={state_a} pages={len(pages_a)} (A's page persisted)")
    if not pages_a:
        die("doc A produced no page — cannot force a collision")

    # ── Step 2: doc B → RECOMPILE → collision → detectConflict runs ───────────
    pb = write_temp("docB", DOC_B)
    ub = api.upload(f"/api/v1/avatars/{avatar}/files", pb)
    log(f"  [2] upload B(38 ATP) → {ub.status_code}")
    if ub.status_code not in (200, 201):
        die(f"upload B failed: {ub.status_code}")
    rb = api.post(f"/api/v1/avatars/{avatar}/wiki/recompile", {}, timeout=240)
    log(f"  [2] recompile → {rb.status_code}")
    time.sleep(8)  # let the debounced recompile pick up the new file
    state_b, pages_b = poll_state(api, avatar)
    log(f"  [2] state={state_b} pages={len(pages_b)}")

    details = page_details(api, avatar, pages_b)
    (OUT / "pages_after_recompile.json").write_text(json.dumps(details, indent=2))

    flagged = [d for d in details if d["hasConflict"] or d["conflictNote"]]
    saw_36 = any(d["saw_36"] for d in details)
    saw_38 = any(d["saw_38"] for d in details)

    report = {
        "base": BASE, "avatar": avatar,
        "compile_a_http": ca.status_code, "recompile_http": rb.status_code,
        "pages_after": len(pages_b),
        "saw_36": saw_36, "saw_38": saw_38,
        "conflict_flagged_pages": flagged,
        "detectConflict_fired": bool(flagged),
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2))

    log("\n" + "=" * 70)
    log(f"SEQUENCED MULTIDOC CONFLICT SMOKE  ({BASE})")
    log(f"  pages after recompile={len(pages_b)}  saw_36={saw_36} saw_38={saw_38}")
    if flagged:
        for c in flagged:
            log(f"  ⚑ CONFLICT FLAGGED on '{c['title']}': hasConflict={c['hasConflict']} "
                f"note={c['conflictNote']!r}")
        log("  ✓ detectConflict fired and surfaced the contradiction with a note.")
    else:
        log("  (no page flagged hasConflict/conflictNote)")
        log("  → detectConflict either didn't run (Gemini re-merged into one pass) or the")
        log("    contradicting value landed in non-matching prose context across the two")
        log("    LLM-written pages. See report.json + pages_after_recompile.json.")
    log(f"  artifacts → {OUT}")
    log("=" * 70)

    # Diagnostic, not a gate: only hard-fail on a compile 400 (the V93 regression).
    if str(ca.status_code) == "400" or str(rb.status_code) == "400":
        die("compile/recompile returned 400 — regression")


if __name__ == "__main__":
    main()
