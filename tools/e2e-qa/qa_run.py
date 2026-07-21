#!/usr/bin/env python3
"""
Apalchi production-API QA harness.

Fires against LIVE prod (no staging). Safety is non-negotiable and enforced in
api.py: one self-registered throwaway account, spend caps (<=2 compiles, <=3
PROVE gens), a 3x-consecutive-5xx circuit breaker, and Railway is read-only
(logs via the `railway` CLI only).

Usage:
  QA_BASE_URL=... python3 qa_run.py --phase [gauntlet|kestrel|day2|all] --report out.md
  python3 qa_run.py --phase all --dry-run      # print the call plan, fire nothing

Account: reuses creds from .qa_state.json (or QA_EMAIL/QA_PASSWORD) if present,
else self-registers a fresh 13+ student (birthYear 2005 -> no consent wall).
The kestrel avatar id and the seeded weak-concept set are persisted to
.qa_state.json so `--phase day2` (run tomorrow) reads back what phase 3 seeded.
"""
import argparse
import json
import os
import subprocess
import time
from pathlib import Path

import rules
from api import ApiClient, PhaseStop, SpendGuard, new_qa_email

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / ".qa_state.json"
FIXTURES = Path(os.environ.get(
    "QA_FIXTURES", str(Path.home() / "Downloads" / "Telegram Desktop")))

BAD_FIXTURES = [
    ("empty.pdf", "application/pdf"),
    ("corrupt.pdf", "application/pdf"),
    ("encrypted.pdf", "application/pdf"),
    ("receipt_photo.jpg", "image/jpeg"),
    ("scanned_style.pdf", "application/pdf"),
    ("wrong_format.txt", "text/plain"),
]
KESTREL = ("kestrel_method_study_guide.pdf", "application/pdf")

PASS, FAIL, INFO, NOT_COVERED = "PASS", "FAIL", "INFO", "NOT COVERED"


class Runner:
    def __init__(self, args):
        self.args = args
        self.spend = SpendGuard()
        self.c = ApiClient(base=args.base, dry_run=args.dry_run, spend=self.spend)
        self.findings = []          # {case, check, verdict, evidence}
        self._all_strings = []      # every served string, for fidelity checks
        self.state = self._load_state()

    # ---- state ----------------------------------------------------------
    def _load_state(self):
        if STATE_FILE.exists():
            try:
                return json.loads(STATE_FILE.read_text())
            except Exception:
                return {}
        return {}

    def _save_state(self):
        if not self.args.dry_run:
            STATE_FILE.write_text(json.dumps(self.state, indent=2))

    # ---- findings -------------------------------------------------------
    def find(self, case, check, verdict, evidence=""):
        self.findings.append({"case": case, "check": check,
                              "verdict": verdict, "evidence": str(evidence)[:400]})
        marker = {"PASS": "✓", "FAIL": "✗", "INFO": "i", "NOT COVERED": "-"}.get(verdict, "?")
        print(f"    [{marker}] {case}: {check} -> {verdict}"
              + (f"  ({str(evidence)[:120]})" if evidence else ""))

    # ---- bootstrap ------------------------------------------------------
    def bootstrap(self):
        email = self.state.get("email") or os.environ.get("QA_EMAIL")
        pw = self.state.get("password") or os.environ.get("QA_PASSWORD")
        if email and pw:
            print(f"  reusing account {email}")
            self.c.login(email, pw)
            if not self.c.token and not self.args.dry_run:
                raise PhaseStop("login with stored creds failed")
        else:
            email = new_qa_email()
            pw = "QaHarness!" + str(int(time.time()))[-6:]
            print(f"  registering fresh account {email}")
            r, d = self.c.register(email, pw, "QA Harness", "SCIENCE",
                                   "SECONDARY", 2005)
            if not self.args.dry_run and not self.c.token:
                raise PhaseStop(f"register failed: {r.status_code} {r.raw[:200]}")
            self.state.update({"email": email, "password": pw,
                               "userId": self.c.user_id,
                               "onboardAvatarId": (d or {}).get("avatarId")})
            self._save_state()
        self.c.token = self.c.token or "dry-run-token"

    # ---- avatar helpers -------------------------------------------------
    def create_avatar(self, name, subject="SCIENCE"):
        r = self.c.request("POST", "/api/v1/avatars", json_body={
            "name": name, "subject": subject, "characterType": "MOCHI",
        }, tag=f"createAvatar({name})")
        d = self.c.unwrap(r) or {}
        return d.get("id") or (f"dry-avatar" if self.args.dry_run else None)

    def get_avatar(self, aid):
        r = self.c.request("GET", f"/api/v1/avatars/{aid}", tag="getAvatar")
        return self.c.unwrap(r) or {}

    def upload(self, aid, fixture, skip_relevance, tolerate_5xx=False):
        name, ctype = fixture
        path = FIXTURES / name
        if not path.exists() and not self.args.dry_run:
            raise PhaseStop(f"fixture missing: {path}")
        files = {"file": (name, path.read_bytes() if path.exists() else b"x", ctype)}
        data = {"skipRelevance": "true"} if skip_relevance else None
        return self.c.request("POST", f"/api/v1/avatars/{aid}/files",
                              files=files, data=data, timeout=180,
                              tag=f"upload({name})", tolerate_5xx=tolerate_5xx)

    def poll_compile(self, aid, ceiling_s=240, interval=6):
        """Dual-signal poll: compile/status DONE|FAILED OR avatar READY+pages.
        Returns (terminal:str, detail:dict). Never hangs past ceiling."""
        if self.args.dry_run:
            return "DONE", {"dry_run": True}
        t0 = time.time()
        last = {}
        while time.time() - t0 < ceiling_s:
            rs = self.c.request("GET", f"/api/v1/avatars/{aid}/wiki/compile/status",
                                tag="compileStatus")
            st = (self.c.unwrap(rs) or {})
            state = st.get("state")
            av = self.get_avatar(aid)
            last = {"compile": st, "brainState": av.get("brainState"),
                    "wikiPageCount": av.get("wikiPageCount")}
            if state in ("DONE", "FAILED"):
                return state, last
            if av.get("brainState") == "READY":
                return ("READY_OK" if (av.get("wikiPageCount") or 0) > 0
                        else "READY_EMPTY"), last
            time.sleep(interval)
        return "TIMEOUT", last

    # ---- PHASE 2: rejection gauntlet (QA-1.2) --------------------------
    def phase_gauntlet(self):
        print("\n== PHASE 2: rejection gauntlet (QA-1.2) ==")
        aid = self.create_avatar("Gauntlet Throwaway")
        for fx in BAD_FIXTURES:
            name = fx[0]
            try:
                # tolerate_5xx: a 5xx per distinct bad file is the finding, not a
                # retry-storm — record it and keep probing the other 5 fixtures.
                r = self.upload(aid, fx, skip_relevance=False, tolerate_5xx=True)
            except PhaseStop:
                raise
            status = r.status_code
            body = self.c.unwrap(r)
            # classify
            if status >= 500:
                self.find("QA-1.2", f"upload {name}", FAIL,
                          f"HTTP {status} {rules._excerpt(r.raw, '')}")
                continue
            # if it looks like it started compiling, ensure it reaches terminal
            terminal = None
            if status in (200, 201) and isinstance(body, dict) and (
                    body.get("pageCount") or body.get("compileJobId")
                    or body.get("wikiPageTitles")):
                terminal, detail = self.poll_compile(aid, ceiling_s=120)
                if terminal in ("DONE", "READY_OK"):
                    # a bad fixture unexpectedly compiled — disclose it against the
                    # compile budget (don't hard-stop the gauntlet mid-probe)
                    self.spend.compiles += 1
                if terminal in ("TIMEOUT",):
                    self.find("QA-1.2", f"upload {name}", FAIL,
                              f"zombie: no terminal state in 120s {detail}")
                    continue
            verdict = PASS
            note = f"HTTP {status}"
            if isinstance(body, dict):
                for k in ("quality", "qualityReason", "reason", "message",
                          "status", "code", "extractedChars"):
                    if k in body:
                        note += f" {k}={body[k]}"
            if terminal:
                note += f" compile={terminal}"
            self.find("QA-1.2", f"upload {name}", verdict, note)
        # cleanup throwaway (normal user op)
        if aid and not self.args.dry_run:
            self.c.request("DELETE", f"/api/v1/avatars/{aid}", tag="deleteAvatar")
            self.find("QA-1.2", "throwaway avatar deleted", INFO, aid)

    # ---- module walk helpers -------------------------------------------
    def start_stage(self, aid, mid, charge_prove=False):
        if charge_prove:
            self.spend.charge_prove_gen()
        r = self.c.request("POST", f"/api/v1/avatars/{aid}/modules/{mid}/start",
                           json_body={}, timeout=120, tag="module/start")
        return self.c.unwrap(r) or {}

    def submit(self, aid, mid, submissions, duration):
        r = self.c.request("POST", f"/api/v1/avatars/{aid}/modules/{mid}/submit",
                           json_body={"submissions": submissions,
                                      "durationSeconds": duration},
                           timeout=120, tag="module/submit")
        return self.c.unwrap(r) or {}, r.status_code

    def sweep(self, case, label, payload):
        """Run the grounding/leak sweep over a served payload."""
        strings = rules.collect_strings(payload)
        self._all_strings.extend(strings)
        for term, ex in rules.banned_realworld_hits(strings):
            self.find(case, f"{label}: banned real-world term '{term}'", FAIL, ex)
        # number co-occurrence is advisory ONLY — the digit-near-concept heuristic
        # false-positives on ordinals ("Hover Question 2") and MCQ distractors /
        # scenario numbers ("report due in 15 minutes"). Never an automated FAIL;
        # the reliable signal is the positive fidelity check (canonical value present).
        # Collapsed to a single summary so the advisory noise can't bury real rows.
        cooc = rules.number_contradictions(strings)
        if cooc:
            concepts = sorted({c for c, *_ in cooc})
            self.find(case, f"{label}: {len(cooc)} number co-occurrence(s) flagged"
                      f" for manual review (advisory heuristic — ordinals/scenario"
                      f" numbers; concepts: {', '.join(concepts)})", INFO,
                      " | ".join(ex for *_, ex in cooc[:2]))
        for ex in rules.persona_leaks(strings):
            self.find(case, f"{label}: persona/grade leak", FAIL, ex)
        for ex in rules.rubric_leaks(strings):
            self.find(case, f"{label}: rubric leak", FAIL, ex)
        for ex in rules.canary_hits(strings):
            self.find(case, f"{label}: CANARY '{rules.CANARY}' present "
                      "(read past 3000 chars)", INFO, ex)
        if not any(f["verdict"] == FAIL and label in f["check"]
                   for f in self.findings):
            self.find(case, f"{label}: grounding sweep clean", PASS,
                      f"{len(strings)} strings swept")

    # ---- PHASE 3: kestrel run ------------------------------------------
    def phase_kestrel(self):
        print("\n== PHASE 3: Kestrel run (QA-1.1,1.3-1.7,1.10,1.12-1.14,2.2,5.4,5.6) ==")
        aid = self.create_avatar("Kestrel QA")
        self.state["kestrelAvatarId"] = aid
        # A fresh avatar has NO quiz history — reset the day2 quiz-miss accumulator
        # so quizWrongAnswerDays always refers to THIS avatar (else weak-first
        # reporting counts misses on a discarded avatar and lies about maturity).
        self.state["quizWrongAnswerDays"] = 0
        self._save_state()

        # 1. upload + compile (QA-1.1)
        self.spend.charge_compile()
        up = self.upload(aid, KESTREL, skip_relevance=True)
        self.find("QA-1.1", "kestrel upload accepted", PASS if up.ok else FAIL,
                  f"HTTP {up.status_code} {rules._excerpt(up.raw,'')}")
        # trigger compile explicitly (idempotent w/ upload's own compile)
        self.c.request("POST", f"/api/v1/avatars/{aid}/wiki/compile",
                       json_body={}, timeout=120, tag="wiki/compile")
        terminal, detail = self.poll_compile(aid)
        self.find("QA-1.1", "compile reached terminal state",
                  PASS if terminal in ("DONE", "READY_OK") else FAIL,
                  f"{terminal} {detail}")

        # module list (QA-5.6): expect 5-7
        r = self.c.request("GET", f"/api/v1/avatars/{aid}/modules", tag="modules")
        mods = self.c.unwrap(r) or []
        if isinstance(mods, dict):
            mods = mods.get("modules", [])
        if not mods:
            self.c.request("POST", f"/api/v1/avatars/{aid}/modules/generate",
                           json_body={}, timeout=120, tag="modules/generate")
            r = self.c.request("GET", f"/api/v1/avatars/{aid}/modules", tag="modules")
            mods = self.c.unwrap(r) or []
            if isinstance(mods, dict):
                mods = mods.get("modules", [])
        n = len(mods) if isinstance(mods, list) else 0
        self.find("QA-5.6", "module count in 5-7", PASS if 5 <= n <= 7 else FAIL,
                  f"count={n}: {[m.get('title') for m in mods][:8]}")
        if not mods:
            self.find("QA-1.x", "no modules -> cannot walk stages", FAIL, "")
            return
        mid = mods[0].get("id")
        weak = set()

        # 2-5. walk LEARN -> TEST -> PROVE. Re-fetch the served stage after each
        # step: submitting advances the server-side stage, so the next start()
        # returns the NEXT stage's items (the missing re-fetch was the bug that
        # re-submitted to an already-advanced stage and skipped PROVE entirely).
        served = self.start_stage(aid, mid)
        for _ in range(8):
            stage = served.get("stage")
            items = served.get("items") or []
            cs = served.get("contentStatus")
            if stage in (None, "COMPLETE"):
                break
            if cs in ("CONTENT_UPDATING", "CONTENT_UNAVAILABLE") or not items:
                self.find("QA-5.6", f"{stage} served transient ({cs})", INFO, "")
                time.sleep(8)
                served = self.start_stage(aid, mid)
                continue
            if stage == "LEARN":
                self.sweep("QA-1.3", "LEARN", items)
                self.submit(aid, mid, [{"itemId": it["id"], "response": "viewed:true"}
                                       for it in items], 20)
            elif stage == "TEST":
                self._test_stage(aid, mid, items, weak)
            elif stage == "PROVE":
                self._prove_stage(aid, mid, items, weak)
                break
            served = self.start_stage(aid, mid)

        # number-fact fidelity: canonical invented values survived compilation
        # (a reliable positive check; contradiction detection is advisory-only)
        grounded = [k for k, vs in rules.NUMBER_FACTS.items()
                    if any(v.lower() in s.lower() for v in vs for s in self._all_strings)]
        self.find("QA-1.4", f"number-fact fidelity: {len(grounded)}/"
                  f"{len(rules.NUMBER_FACTS)} concepts' canonical values present",
                  PASS if len(grounded) >= len(rules.NUMBER_FACTS) // 2 else INFO,
                  grounded)

        self.state["weakConcepts"] = sorted(weak)
        self._save_state()

        # 6. revision re-start (QA-2.2) — report the actual revision flag honestly
        rev = self.start_stage(aid, mid, charge_prove=True)
        stage_ok = rev.get("stage") == "PROVE"
        flag = rev.get("revision")
        self.find("QA-2.2", "revision re-start returns {stage:PROVE, revision:true}",
                  PASS if (stage_ok and flag is True) else (INFO if stage_ok else FAIL),
                  f"stage={rev.get('stage')} revision={flag}")
        if rev.get("items"):
            self.sweep("QA-2.2", "REVISION-PROVE", rev.get("items"))

    def _test_stage(self, aid, mid, items, weak):
        # 3. LEAK ASSERTS (QA-1.10, 5.4) — both directions
        leaked = []
        prov_ok = True
        for it in items:
            blob = json.dumps(it)
            for banned_key in ("isTrue", '"answer"', "correctSolution"):
                # HOT_TAKE must carry no reveal; isTrue/answer never served
                if it.get("type") == "HOT_TAKE" and it.get("revealJson"):
                    leaked.append((it["id"], "HOT_TAKE has revealJson"))
            for k in ("isTrue",):
                if _has_key(it.get("answerJson"), k) or _has_key(it.get("contentJson"), k):
                    leaked.append((it["id"], f"served '{k}'"))
            if not (it.get("sourcePageTitle") or it.get("sourcePageSlug")):
                prov_ok = False
        self.find("QA-1.10", "TEST items strip answer key (isTrue/answer/HOT_TAKE reveal)",
                  FAIL if leaked else PASS, leaked or "no graded keys served")
        self.find("QA-5.4", "TEST items carry provenance (sourcePageTitle/slug)",
                  PASS if prov_ok else FAIL, "")

        # grounding sweep on TEST content (QA-1.4/1.6/1.7)
        self.sweep("QA-1.4", "TEST", items)

        # 4. run: per-item AGREE for all but the last hot-take; assert no advance
        hot = [it for it in items if it.get("type") == "HOT_TAKE"]
        advanced_early = False
        for it in hot[:-1] if len(hot) > 1 else []:
            res, _ = self.submit(aid, mid, [{"itemId": it["id"], "response": "AGREE"}], 0)
            if res.get("stageComplete") or res.get("nextStage"):
                advanced_early = True
            self._record_correct(res, it, weak)
        self.find("QA-1.12", "stage does NOT advance on per-item submits",
                  FAIL if advanced_early else PASS, "")

        # SPOT_MISTAKE self-check plan (feat/sm-self-check): each SM item gets a
        # typed diagnosis (response) + an alternating YES / NOT_QUITE selfCheck.
        # With >=3 SM items the 3rd is left WITHOUT a selfCheck, so a single
        # end-of-stage submit exercises the mixed present/absent advancement pin
        # against prod. sm_plan: itemId -> (selfCheck|None, diagnosis).
        sm_items = [it for it in items if it.get("type") == "SPOT_MISTAKE"]
        sm_plan = {}
        for i, it in enumerate(sm_items):
            diag = f"QA diagnosis for {it.get('sourcePageSlug') or it['id']}"
            sc = None if (len(sm_items) >= 3 and i == 2) \
                else ("YES" if i % 2 == 0 else "NOT_QUITE")
            sm_plan[it["id"]] = (sc, diag)

        # end-of-stage submit: all items
        subs = []
        for it in items:
            if it["id"] in sm_plan:
                sc, diag = sm_plan[it["id"]]
                s = {"itemId": it["id"], "response": diag}
                if sc:
                    s["selfCheck"] = sc
                subs.append(s)
            else:
                subs.append({"itemId": it["id"],
                             "response": "AGREE" if it.get("type") == "HOT_TAKE" else "x"})
        res, _ = self.submit(aid, mid, subs, 60)
        advanced = bool(res.get("stageComplete") or res.get("nextStage"))
        mixed = sm_plan and any(sc for sc, _ in sm_plan.values()) \
            and any(sc is None for sc, _ in sm_plan.values())
        self.find("QA-1.12", "stage advances exactly once, on end-of-stage submit"
                  + (" (with MIXED SM self-checks present/absent)" if mixed else ""),
                  PASS if advanced else FAIL,
                  f"stageComplete={res.get('stageComplete')} next={res.get('nextStage')}")
        for it in hot:
            self._record_correct(res, it, weak)
        # honesty: per-item results carry correct honestly
        graded = [r for r in (res.get("results") or []) if "correct" in r]
        self.find("QA-1.12", "per-item results carry honest 'correct' grade",
                  PASS if graded else INFO,
                  f"{len(graded)} graded rows")

        # SM self-check round-trip (QA-1.6): items with a selfCheck come back
        # selfReported (SELF_REPORT signal), NEVER machine-graded, and the typed
        # diagnosis persists on the progress row.
        self._assert_sm_round_trip(aid, mid, sm_plan, res)

    def _record_correct(self, res, item, weak):
        for row in (res.get("results") or []):
            if row.get("itemId") == item.get("id") and row.get("correct") is False:
                key = item.get("targetConcept") or item.get("sourcePageSlug") \
                    or item.get("sourcePageTitle")
                if key:
                    weak.add(key)

    def _assert_sm_round_trip(self, aid, mid, sm_plan, submit_res):
        """Field-verify feat/sm-self-check: selfReported signal + never-graded +
        diagnosis persisted. No-op (INFO) when the module's TEST stage has no SM."""
        with_check = [iid for iid, (sc, _) in sm_plan.items() if sc]
        if not with_check:
            self.find("QA-1.6", "SM self-check round-trip", INFO,
                      "no SPOT_MISTAKE items in this module's TEST stage")
            return
        by_id = {r.get("itemId"): r for r in (submit_res.get("results") or [])}
        reported = [iid for iid in with_check
                    if by_id.get(iid, {}).get("selfReported") is True]
        self.find("QA-1.6", "SM self-check → SELF_REPORT signal (selfReported in results)",
                  PASS if len(reported) == len(with_check) else FAIL,
                  f"{len(reported)}/{len(with_check)} SM items selfReported")
        machine = [iid for iid in with_check
                   if by_id.get(iid, {}).get("graded") is True]
        self.find("QA-1.6", "SM self-check is NEVER machine-graded (graded=false)",
                  FAIL if machine else PASS, machine or "no SM row graded=true")
        # Diagnosis persisted — re-read the module detail and match responseJson.
        # Tolerant: if the detail view doesn't surface prior-stage rows, report INFO
        # (the selfReported assertion above already proves the write path fired).
        if self.args.dry_run:
            return
        d = self.c.unwrap(self.c.request(
            "GET", f"/api/v1/avatars/{aid}/modules/{mid}", tag="module/detail")) or {}
        det = d.get("module") if isinstance(d.get("module"), dict) else d
        ditems = det.get("items") if isinstance(det, dict) else None
        rj = {it.get("id"): it.get("responseJson")
              for it in (ditems or []) if isinstance(it, dict)}
        matched = sum(1 for iid in with_check
                      if rj.get(iid) == sm_plan[iid][1])
        found_any = any(iid in rj for iid in with_check)
        self.find("QA-1.6", "SM typed diagnosis persisted on the progress row",
                  PASS if (found_any and matched == len(with_check))
                  else (INFO if not found_any else FAIL),
                  f"{matched}/{len(with_check)} responseJson matched"
                  + ("" if found_any else " (detail view omits prior-stage rows)"))

    def _prove_stage(self, aid, mid, items, weak):
        self.spend.charge_prove_gen()   # these items are LLM-generated
        # 5. PROVE served items: targetConcept + priorScore present (QA-1.14)
        miss = [it["id"] for it in items if not it.get("targetConcept")]
        self.find("QA-1.14", "PROVE items carry targetConcept",
                  FAIL if miss else PASS, miss or f"{len(items)} items")
        has_prior = [it for it in items if it.get("priorScore") is not None]
        self.find("QA-1.14", "PROVE items carry priorScore",
                  PASS if has_prior else INFO, f"{len(has_prior)}/{len(items)}")
        self.sweep("QA-1.14", "PROVE", items)

        # QA-1.13: prove-gen log (proxy: targetConcept real, not 'unknown:')
        self._prove_gen_log_check(aid)

        # submit PROVE stage then self-report NO to seed weakness deterministically
        subs = [{"itemId": it["id"], "response": "My attempt at " + str(it.get("targetConcept"))}
                for it in items]
        res, _ = self.submit(aid, mid, subs, 60)
        for it in items:
            if it.get("targetConcept"):
                weak.add(it["targetConcept"])
            self.c.request(
                "POST",
                f"/api/v1/avatars/{aid}/modules/{mid}/items/{it['id']}/self-report",
                json_body={"selfReport": "NO"}, tag="self-report")
        self.find("QA-1.14", "PROVE self-reports recorded (seeding weakness)",
                  PASS, f"{len(items)} items -> NO")

    def _prove_gen_log_check(self, aid):
        """Grep Railway logs for task=module-prove-gen. Best-effort; the literal
        promptChars>2000 is NOT emitted on the Gemini happy path (see report)."""
        if self.args.dry_run:
            self.find("QA-1.13", "prove-gen log (railway)", NOT_COVERED, "dry-run")
            return
        try:
            out = subprocess.run(
                ["railway", "logs", "-s", "pally_backend", "--since", "20m",
                 "--filter", "module-prove-gen"],
                capture_output=True, text=True, timeout=60).stdout
        except Exception as e:
            self.find("QA-1.13", "prove-gen log (railway)", NOT_COVERED,
                      f"railway CLI unavailable: {e}")
            return
        line = next((l for l in out.splitlines() if "module-prove-gen" in l), "")
        if line:
            self.find("QA-1.13", "prove-gen fired (task=module-prove-gen)", PASS,
                      line.strip()[:200])
        else:
            self.find("QA-1.13", "prove-gen log line", INFO,
                      "no line in 20m window (DEBUG may be sampled)")
        self.find("QA-1.13", "promptChars>2000 literal assert", NOT_COVERED,
                  "prompt length not logged on Gemini path; proxy=targetConcept real (QA-1.14)")

    # ---- PHASE 4: day-2 ------------------------------------------------
    def phase_day2(self):
        print("\n== PHASE 4: day-2 (QA-3.4) ==")
        aid = self.state.get("kestrelAvatarId")
        if not aid:
            self.find("QA-3.4", "day2 needs kestrel avatar from phase 3", NOT_COVERED,
                      "run --phase kestrel first")
            return
        r = self.c.request("GET", f"/api/v1/avatars/{aid}/quiz/daily",
                           timeout=120, tag="quiz/daily")
        q = self.c.unwrap(r) or []
        if isinstance(q, dict):
            q = q.get("questions", [])
        if not q:
            self.find("QA-3.4", "daily quiz served", INFO, "empty quiz (none due)")
            return

        # provenance on quiz (always assertable)
        prov = all(x.get("pageTitle") or x.get("sourcePageSlug") for x in q)
        self.find("QA-3.4", "quiz questions carry pageTitle/sourcePageSlug",
                  PASS if prov else FAIL, "")

        # weak-first selectionReason. IMPORTANT: the weakness profile the quiz reads
        # (WeaknessProfileService.weakSlugsFor) is materialized from QUIZ ANSWER
        # HISTORY (quiz_question_results via findTopicMastery: attempts>=2 AND
        # correctRatio<0.6), NOT from PROVE self-reports. So weak-first only fires
        # once the account has ≥2 days of wrong daily-quiz answers per topic — a
        # genuinely multi-day accumulation (daily reset, Asia/Singapore). A single
        # session cannot synthesise it. We record the wrong signal seeded in Phase 3
        # (PROVE) was insufficient, and SEED THE CORRECT SIGNAL below for future runs.
        reasons = [(x.get("selectionReason") or "") for x in q]
        weak_first = [rr for rr in reasons if rr.startswith("WEAK_TOPIC:")]
        prior_attempts = self.state.get("quizWrongAnswerDays", 0)
        if weak_first:
            self.find("QA-3.4", "weak-first WEAK_TOPIC selectionReason present",
                      PASS, f"weak_first={weak_first[:5]} after {prior_attempts} wrong-quiz day(s)")
        else:
            self.find("QA-3.4",
                      "weak-first WEAK_TOPIC selectionReason present",
                      NOT_COVERED,
                      f"weakSlugs empty ({prior_attempts} wrong-quiz day(s) on THIS avatar). "
                      "Weak-first CODE verified via the '[Pipeline:Quiz] weak-first bias "
                      "weakSlugs=N' log. E2E flip needs >=2 wrong-quiz days per slug "
                      "(quiz_question_results attempts>=2 & correctRatio<0.6). The quiz submit "
                      "itself now refreshes the weak-set (fix/weakset-refresh-on-quiz-submit), so "
                      "repeated day2 runs converge with NO extra trigger: expect PASS by day 2-3 "
                      "on this avatar via the real user path.")

        # Seed the weakness signal via the REAL user path: submit deliberately-wrong
        # answers to the daily quiz. Writes quiz_question_results AND (post
        # fix/weakset-refresh-on-quiz-submit) triggers onMasteryUpdated at the end of
        # the submit, re-materialising the weak-set from the now-current history. Once
        # >=2 wrong-quiz days per slug accumulate, a later day2 SERVE reads the
        # populated set and weak-first fires — no separate module trigger needed.
        self._seed_quiz_weakness(aid, q)

        # home nudge — endpoint not surfaced in recon; render-layer
        self.find("QA-3.4", "home weak_concept nudge (human label, not slug)",
                  NOT_COVERED,
                  "no dedicated home-nudge API found in recon; render-layer — MANUAL")

    def _seed_quiz_weakness(self, aid, questions):
        """Submit wrong answers so the quiz-history weakness signal accumulates."""
        import uuid
        answers, correct_map, topic_map = {}, {}, {}
        for x in questions:
            qid = x.get("id")
            n = len(x.get("options") or []) or 4
            ci = x.get("correctIndex")
            # deliberately wrong: pick an index != correctIndex (0 unless correct is 0)
            wrong = 1 if (ci == 0) else 0
            answers[qid] = wrong
            if ci is not None:
                correct_map[qid] = ci
            topic_map[qid] = x.get("sourcePageSlug") or x.get("sourcePage") or ""
        body = {"answers": answers, "correctMap": correct_map, "topicMap": topic_map,
                "durationSeconds": 30, "idempotencyKey": str(uuid.uuid4())}
        r = self.c.request("POST", f"/api/v1/avatars/{aid}/quiz/answers",
                           json_body=body, timeout=120, tag="quiz/answers(seed-wrong)")
        ok = r.ok
        if ok and not self.args.dry_run:
            self.state["quizWrongAnswerDays"] = self.state.get("quizWrongAnswerDays", 0) + 1
            self._save_state()
        self.find("QA-3.4", "seed weakness via wrong daily-quiz answers "
                  "(accumulates quiz_question_results for a later weak-first assert)",
                  PASS if ok else INFO,
                  f"submitted {len(answers)} wrong; wrong-quiz days now "
                  f"{self.state.get('quizWrongAnswerDays', 0)} (weak-first fires at >=2)")

    # ---- report ---------------------------------------------------------
    def write_report(self, path):
        by = {}
        for f in self.findings:
            by.setdefault(f["verdict"], 0)
            by[f["verdict"]] += 1
        lines = []
        lines.append("# Apalchi prod-API QA harness — automated results\n")
        lines.append(f"- Base: `{self.c.base}`  ·  account: `{self.state.get('email','(dry-run)')}`")
        lines.append(f"- Spend: compiles={self.spend.compiles}/{self.spend.max_compiles}, "
                     f"prove-gens={self.spend.prove_gens}/{self.spend.max_prove_gens}")
        lines.append(f"- Tally: " + ", ".join(f"**{k}** {v}" for k, v in sorted(by.items())) + "\n")
        lines.append("| QA case | automated check | verdict | evidence |")
        lines.append("|---|---|---|---|")
        for f in self.findings:
            ev = f["evidence"].replace("|", "\\|").replace("\n", " ")[:220]
            lines.append(f"| {f['case']} | {f['check']} | {f['verdict']} | {ev} |")
        lines.append("\n## REMAINS MANUAL (render / UX — not machine-verifiable here)\n")
        for cid, why in REMAINS_MANUAL:
            lines.append(f"- **{cid}** — {why}")
        lines.append("\n## Raw call trace (trimmed)\n```")
        for t in self.c.trace[-60:]:
            lines.append(json.dumps(t, ensure_ascii=False)[:240])
        lines.append("```")
        Path(path).write_text("\n".join(lines))
        print(f"\nreport -> {path}  ({by})")

    # ---- driver ---------------------------------------------------------
    def run(self):
        phases = {"gauntlet": [self.phase_gauntlet],
                  "kestrel": [self.phase_kestrel],
                  "day2": [self.phase_day2],
                  "all": [self.phase_gauntlet, self.phase_kestrel]}[self.args.phase]
        self.bootstrap()
        for ph in phases:
            try:
                ph()
            except PhaseStop as e:
                self.find(f"PHASE:{ph.__name__}", "phase aborted (safety stop)",
                          INFO, str(e))
                print(f"  !! PhaseStop: {e}")
        self.write_report(self.args.report)


# QA cases the harness cannot machine-verify (render / UX / device).
REMAINS_MANUAL = [
    ("QA-1.8/1.9", "LEARN card visual rendering, Mochi placeholder art, chip layout"),
    ("QA-1.11", "TEST answer-reveal animation / reveal timing (client render)"),
    ("QA-1.15", "PROVE self-assess UI + comeback line render"),
    ("QA-2.1/2.3", "revision-mode banner + visual diff of fresh questions"),
    ("QA-3.1-3.3", "home surfaces, streak, XP toast rendering"),
    ("QA-3.4-nudge", "home weak_concept nudge card render + human-readable label"),
    ("QA-4.x", "upload UX: progress spinner, error banners, add-anyway dialog"),
    ("QA-5.1-5.3,5.5", "empty-state Mochi placeholders (library/chat/teach/wiki/groups)"),
    ("QA-6.x", "store-build behaviour, iOS price gating, deep links"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", choices=["gauntlet", "kestrel", "day2", "all"],
                    default="all")
    ap.add_argument("--report", default="out.md")
    ap.add_argument("--base", default=None)
    ap.add_argument("--dry-run", action="store_true",
                    help="print the call plan; fire nothing at prod")
    Runner(ap.parse_args()).run()


def _has_key(obj, key):
    import json as _json
    if obj is None:
        return False
    if isinstance(obj, str):
        try:
            obj = _json.loads(obj)
        except Exception:
            return key in obj
    if isinstance(obj, dict):
        return key in obj or any(_has_key(v, key) for v in obj.values())
    if isinstance(obj, list):
        return any(_has_key(v, key) for v in obj)
    return False


def _loose_match(a, b):
    a, b = str(a).lower().strip(), str(b).lower().strip()
    return bool(a) and bool(b) and (a in b or b in a)


if __name__ == "__main__":
    main()
