"""
Thin prod-API client for the Apalchi QA harness.

Safety is enforced HERE so no phase can bypass it:
  * consecutive-5xx circuit breaker (3 in a row on the same endpoint -> PhaseStop)
  * spend guards: compile-triggers and PROVE-generations are hard-capped
  * every call is logged to a trace the report can quote
Read-only against Railway logs is handled outside (railway CLI).
"""
import json
import os
import time
import uuid

import requests

DEFAULT_BASE = "https://pallybackend-production.up.railway.app"


class PhaseStop(Exception):
    """Raised to abort a phase cleanly (5xx storm or spend cap)."""


class SpendGuard:
    def __init__(self, max_compiles=2, max_prove_gens=3):
        self.max_compiles = max_compiles
        self.max_prove_gens = max_prove_gens
        self.compiles = 0
        self.prove_gens = 0

    def charge_compile(self):
        if self.compiles >= self.max_compiles:
            raise PhaseStop(
                f"spend guard: compile cap reached ({self.max_compiles})")
        self.compiles += 1

    def charge_prove_gen(self):
        if self.prove_gens >= self.max_prove_gens:
            raise PhaseStop(
                f"spend guard: PROVE-gen cap reached ({self.max_prove_gens})")
        self.prove_gens += 1


class ApiClient:
    def __init__(self, base=None, dry_run=False, spend=None, verbose=True):
        self.base = (base or os.environ.get("QA_BASE_URL") or DEFAULT_BASE).rstrip("/")
        self.dry_run = dry_run
        self.spend = spend or SpendGuard()
        self.verbose = verbose
        self.token = None
        self.user_id = None
        self.s = requests.Session()
        self.trace = []           # list of dicts, quotable in the report
        self._streak = {}         # endpoint -> consecutive 5xx count

    # ---- headers -------------------------------------------------------
    def _headers(self, json_body=True):
        h = {"Accept": "application/json"}
        if json_body:
            h["Content-Type"] = "application/json"
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    # ---- core request --------------------------------------------------
    def request(self, method, path, *, json_body=None, files=None, data=None,
                timeout=120, tag=None, expect_ok=False):
        url = self.base + path
        label = tag or f"{method} {path}"
        if self.dry_run:
            self.trace.append({"dry_run": True, "call": label,
                               "body": json_body or data})
            print(f"  [dry-run] {method} {path}"
                  + (f"  body={_short(json_body or data)}" if (json_body or data) else "")
                  + (f"  file={list(files)}" if files else ""))
            return _Resp(200, {"data": {}}, "{}")

        kw = {"timeout": timeout, "headers": self._headers(json_body=files is None and data is None or json_body is not None)}
        if files is not None:
            # multipart: let requests set the boundary; drop json content-type
            kw["headers"] = {k: v for k, v in self._headers(json_body=False).items()}
            kw["files"] = files
            if data:
                kw["data"] = data
        elif json_body is not None:
            kw["data"] = json.dumps(json_body)
        elif data is not None:
            kw["data"] = data

        t0 = time.time()
        try:
            r = self.s.request(method, url, **kw)
        except requests.RequestException as e:
            self.trace.append({"call": label, "error": str(e)})
            raise PhaseStop(f"network error on {label}: {e}")
        dt = int((time.time() - t0) * 1000)

        raw = r.text
        try:
            body = r.json()
        except ValueError:
            body = None

        # 5xx circuit breaker (per endpoint path, ignoring ids)
        key = _norm(path)
        if r.status_code >= 500:
            self._streak[key] = self._streak.get(key, 0) + 1
            if self._streak[key] >= 3:
                self.trace.append({"call": label, "status": r.status_code,
                                   "note": "3rd consecutive 5xx -> PhaseStop"})
                raise PhaseStop(
                    f"{label} 5xx'd {self._streak[key]}x consecutively (status "
                    f"{r.status_code}) — stopping phase, not retry-storming prod")
        else:
            self._streak[key] = 0

        rec = {"call": label, "status": r.status_code, "ms": dt,
               "resp": _short(body if body is not None else raw)}
        self.trace.append(rec)
        if self.verbose:
            print(f"  {method} {path} -> {r.status_code} ({dt}ms)")

        resp = _Resp(r.status_code, body, raw)
        if expect_ok and not (200 <= r.status_code < 300):
            raise PhaseStop(f"{label} expected 2xx, got {r.status_code}: {_short(raw)}")
        return resp

    # ---- convenience ---------------------------------------------------
    def unwrap(self, resp):
        """Return the inner ApiResponse<T> payload ({data:X} -> X)."""
        b = resp.body
        if isinstance(b, dict) and "data" in b:
            return b["data"]
        return b

    # ---- auth ----------------------------------------------------------
    def register(self, email, password, display_name, subject, level, birth_year):
        r = self.request("POST", "/api/v1/onboard/quick", json_body={
            "email": email, "password": password, "displayName": display_name,
            "subject": subject, "level": level, "birthYear": birth_year,
        }, tag="register(onboard/quick)")
        d = self.unwrap(r) or {}
        if not self.dry_run:
            self.token = d.get("token")
            self.user_id = d.get("userId")
        return r, d

    def login(self, email, password):
        r = self.request("POST", "/api/v1/auth/login",
                         json_body={"email": email, "password": password},
                         tag="login")
        d = self.unwrap(r) or {}
        if not self.dry_run:
            self.token = d.get("token")
            self.user_id = d.get("userId")
        return r, d


class _Resp:
    def __init__(self, status, body, raw):
        self.status_code = status
        self.body = body
        self.raw = raw

    @property
    def ok(self):
        return 200 <= self.status_code < 300


def _short(v, n=600):
    try:
        s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
    except Exception:
        s = str(v)
    return s if len(s) <= n else s[:n] + f"…(+{len(s)-n})"


def _norm(path):
    # collapse ids so streaks track the endpoint, not the resource
    import re
    return re.sub(r"/[0-9a-fA-F-]{8,}", "/{id}", path)


def new_qa_email():
    return f"qa-{int(time.time())}-{uuid.uuid4().hex[:6]}@qa.apalchi.local"
