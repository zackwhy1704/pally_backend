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
    # Budgets (this run): <=3 compiles, <=3 PROVE-gens, <=2 chat-turns PER chat test.
    def __init__(self, max_compiles=3, max_prove_gens=3, max_chat_turns=2):
        self.max_compiles = max_compiles
        self.max_prove_gens = max_prove_gens
        self.max_chat_turns = max_chat_turns
        self.compiles = 0
        self.prove_gens = 0
        self.chat_turns = 0

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

    def charge_chat_turn(self):
        # Per-chat-test cap (reset by reset_chat_turns before each chat test).
        if self.chat_turns >= self.max_chat_turns:
            raise PhaseStop(
                f"spend guard: chat-turn cap reached ({self.max_chat_turns} per chat test)")
        self.chat_turns += 1

    def reset_chat_turns(self):
        self.chat_turns = 0


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
                timeout=120, tag=None, expect_ok=False, tolerate_5xx=False):
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

        # 5xx circuit breaker (per endpoint path, ignoring ids). tolerate_5xx
        # is for the rejection gauntlet, where a 5xx on a DISTINCT bad-input
        # probe is the finding under test, not a retry-storm to guard against.
        key = _norm(path)
        if r.status_code >= 500 and not tolerate_5xx:
            self._streak[key] = self._streak.get(key, 0) + 1
            if self._streak[key] >= 3:
                self.trace.append({"call": label, "status": r.status_code,
                                   "note": "3rd consecutive 5xx -> PhaseStop"})
                raise PhaseStop(
                    f"{label} 5xx'd {self._streak[key]}x consecutively (status "
                    f"{r.status_code}) — stopping phase, not retry-storming prod")
        elif r.status_code < 500:
            self._streak[key] = 0

        rec = {"call": label, "status": r.status_code, "ms": dt,
               "resp": _short(_redact(body) if body is not None else _redact(raw))}
        self.trace.append(rec)
        if self.verbose:
            print(f"  {method} {path} -> {r.status_code} ({dt}ms)")

        resp = _Resp(r.status_code, body, raw)
        if expect_ok and not (200 <= r.status_code < 300):
            raise PhaseStop(f"{label} expected 2xx, got {r.status_code}: {_short(raw)}")
        return resp

    # ---- SSE chat ------------------------------------------------------
    def chat_sse(self, avatar_id, message, module_id=None, timeout=120, tag=None):
        """POST an SSE chat turn and drain the event stream.

        Returns (status_code, joined_delta_text, events) where events is a list
        of (event_type, data). The chat endpoint streams `delta`/`done`/`error`
        events; a 4xx/403 gate writes a JSON error body BEFORE the stream, so we
        surface that raw too. Best-effort — a partial stream is captured, not
        an exception."""
        label = tag or f"chat {avatar_id}"
        if self.dry_run:
            self.trace.append({"dry_run": True, "call": label, "body": {"message": message}})
            print(f"  [dry-run] POST /api/v1/avatars/{avatar_id}/chat  msg={_short(message,60)}")
            return 200, "(dry-run reply)", [("done", "")]
        url = f"{self.base}/api/v1/avatars/{avatar_id}/chat"
        headers = {"Accept": "text/event-stream", "Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        body = {"message": message}
        if module_id:
            body["moduleId"] = module_id
        t0 = time.time()
        deltas, events, raw_err = [], [], None
        try:
            with self.s.post(url, data=json.dumps(body), headers=headers,
                             stream=True, timeout=timeout) as r:
                status = r.status_code
                ctype = r.headers.get("Content-Type", "")
                if "text/event-stream" not in ctype:
                    # a gate / error — JSON body, not a stream
                    raw_err = r.text
                else:
                    cur_event = "message"
                    for line in r.iter_lines(decode_unicode=True):
                        if line is None:
                            continue
                        if line.startswith("event:"):
                            cur_event = line[len("event:"):].strip()
                        elif line.startswith("data:"):
                            data = line[len("data:"):]
                            if data.startswith(" "):
                                data = data[1:]
                            events.append((cur_event, data))
                            if cur_event in ("message", "delta"):
                                deltas.append(data)
                        elif line == "":
                            cur_event = "message"
        except requests.RequestException as e:
            self.trace.append({"call": label, "error": str(e)})
            return 0, "", []
        dt = int((time.time() - t0) * 1000)
        text = "".join(deltas)
        self.trace.append({"call": label, "status": status, "ms": dt,
                           "resp": _short(_redact(raw_err if raw_err else text))})
        if self.verbose:
            print(f"  POST /api/v1/avatars/{avatar_id}/chat -> {status} "
                  f"({dt}ms, {len(text)} chars, {len(events)} events)")
        return status, (raw_err if raw_err else text), events

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


_SECRET_KEYS = {"token", "password", "devicesecret", "refreshtoken",
                "accesstoken", "deviceSecret", "idToken"}


def _redact(obj):
    """Mask credential values so the committed trace/report never carries a
    live token or password (defence-in-depth; the report also trims to the last
    N calls, but redaction is the guarantee)."""
    if isinstance(obj, dict):
        return {k: ("***" if k.lower() in _SECRET_KEYS else _redact(v))
                for k, v in obj.items()}
    if isinstance(obj, list):
        return [_redact(v) for v in obj]
    if isinstance(obj, str):
        # mask anything that looks like a JWT (eyJ...) regardless of key
        import re
        return re.sub(r"eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+",
                      "***JWT***", obj)
    return obj


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
