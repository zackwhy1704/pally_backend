#!/usr/bin/env python3
"""
seed_demo_and_screenshot.py — Apalchi automated demo seeder + screenshotter
============================================================================
Runs entirely headlessly. No human interaction required after launch.

WHAT IT DOES
  1.  Seeds a realistic demo centre ("Bright Minds Tuition") on your backend
      with real-looking Sec 2 Maths content.
  2.  Waits for the AI compile to complete (polls until brainState=READY).
  3.  Generates modules, creates a class assignment.
  4.  Enrols 3 demo students and has each submit quiz answers (varied scores
      so the analytics roster shows meaningful spread).
  5.  Uses Playwright to log into apalchi.com as the demo teacher, navigate
      to every key screen, and save a screenshot of each.
  6.  Saves all screenshots + a JSON manifest to ./demo_screenshots/.

PREREQUISITES
  pip install requests playwright
  playwright install chromium

CONFIG (set as env vars or edit the CONFIG block below)
  BACKEND_URL   Your backend. Defaults to prod Railway URL.
                Set to http://localhost:8080 for local dev.
  WEB_URL       The web app. Defaults to https://apalchi.com
  ALLOW_PROD    Set to "1" to allow running against prod backend.
                The script refuses prod by default to protect real data.
  SCREENSHOT_DIR  Where to save screenshots. Default: ./demo_screenshots

USAGE
  # Against local dev (safe, no confirmation needed):
  python3 seed_demo_and_screenshot.py

  # Against prod (explicit opt-in required):
  ALLOW_PROD=1 BACKEND_URL=https://pallybackend-production.up.railway.app \\
    python3 seed_demo_and_screenshot.py

OUTPUT
  demo_screenshots/
    00_manifest.json          — all seeded IDs, emails, passwords
    01_dashboard.png          — teacher dashboard / class list
    02_class_brain.png        — compiled wiki pages
    03_class_analytics.png    — roster with student scores
    04_student_detail.png     — per-student mastery breakdown
    05_assignment.png         — assignment view
    06_student_app_quiz.png   — student quiz screen (mobile viewport)
    07_student_app_home.png   — student home / Mochi screen
    08_pricing.png            — pricing page (B2B)
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
    sys.exit("pip install requests")

# ──────────────────────── CONFIG ────────────────────────
BACKEND_URL = os.environ.get(
    "BACKEND_URL", "https://pallybackend-production.up.railway.app"
).rstrip("/")
WEB_URL = os.environ.get("WEB_URL", "https://apalchi.com").rstrip("/")
ALLOW_PROD = os.environ.get("ALLOW_PROD") == "1"
SCREENSHOT_DIR = pathlib.Path(
    os.environ.get("SCREENSHOT_DIR", "./demo_screenshots")
)
COMPILE_TIMEOUT_S = int(os.environ.get("COMPILE_TIMEOUT_S", "600"))
COMPILE_POLL_S = 4

# ──────────────────────── DEMO CONTENT ────────────────────────
# Realistic Sec 2 / O-Level Maths content for the demo class brain.
# Three documents so the wiki has multiple pages and modules look populated.

CENTRE_NAME = "Bright Minds Tuition Centre"
CLASS_NAME  = "Sec 2 Mathematics — Express"
SUBJECT     = "MATHEMATICS"

DOC_1_NAME = "algebra_linear_equations.txt"
DOC_1 = """\
Secondary 2 Mathematics — Linear Equations and Inequalities

Chapter 1: Solving Linear Equations

A linear equation is an equation where the highest power of the unknown is 1.
The general form is ax + b = c, where a, b, c are constants and a ≠ 0.

Steps to solve a linear equation:
1. Expand any brackets.
2. Collect all terms with the unknown on one side.
3. Collect all constant terms on the other side.
4. Divide both sides by the coefficient of the unknown.

Example: Solve 3x + 7 = 22
  3x = 22 − 7
  3x = 15
  x = 5

Solving equations with fractions:
Multiply every term by the LCM of all denominators to clear fractions first.
Example: x/2 + x/3 = 5  → multiply by 6 → 3x + 2x = 30 → 5x = 30 → x = 6

Chapter 2: Linear Inequalities

An inequality uses symbols <, >, ≤, ≥.
The solution is a range of values, not a single value.

Key rule: When multiplying or dividing both sides by a NEGATIVE number,
the inequality sign REVERSES direction.
Example: −2x > 8 → x < −4  (sign reversed because we divided by −2)

Representing solutions on a number line:
  • Open circle ○ for strict inequalities (< or >)
  • Closed circle ● for ≤ or ≥
"""

DOC_2_NAME = "geometry_pythagoras_trigonometry.txt"
DOC_2 = """\
Secondary 2 Mathematics — Pythagoras' Theorem and Introduction to Trigonometry

Chapter 3: Pythagoras' Theorem

In a right-angled triangle with hypotenuse c and shorter sides a and b:
  a² + b² = c²

The hypotenuse is always opposite the right angle and is the longest side.

Finding the hypotenuse:
  c = √(a² + b²)
  Example: a = 3, b = 4 → c = √(9 + 16) = √25 = 5

Finding a shorter side:
  a = √(c² − b²)
  Example: c = 13, b = 5 → a = √(169 − 25) = √144 = 12

Pythagorean triples (common sets to memorise):
  3, 4, 5     5, 12, 13     8, 15, 17     7, 24, 25

Converse of Pythagoras: If a² + b² = c², then the triangle is right-angled.

Chapter 4: Introduction to Trigonometry (SOH CAH TOA)

For a right-angled triangle with angle θ:
  sin θ = Opposite / Hypotenuse   (SOH)
  cos θ = Adjacent / Hypotenuse   (CAH)
  tan θ = Opposite / Adjacent     (TOA)

Finding an unknown side:
  Example: hyp = 10, θ = 30° → opposite = 10 × sin 30° = 10 × 0.5 = 5

Finding an unknown angle:
  Use inverse trig: θ = sin⁻¹(opp/hyp)
  Example: opp = 6, hyp = 10 → θ = sin⁻¹(0.6) ≈ 36.9°

Angles of elevation and depression:
  Elevation: angle measured upward from the horizontal.
  Depression: angle measured downward from the horizontal.
  They are alternate angles and therefore equal.
"""

DOC_3_NAME = "statistics_data_handling.txt"
DOC_3 = """\
Secondary 2 Mathematics — Statistics and Data Handling

Chapter 5: Mean, Median and Mode

Mean = Sum of all values ÷ Number of values
  A data set: 4, 7, 7, 9, 12, 14 → Mean = 53 ÷ 6 ≈ 8.83

Median = Middle value when data is arranged in order.
  Odd count: middle term.
  Even count: mean of the two middle terms.
  Data set above (n=6): median = (7 + 9) ÷ 2 = 8

Mode = Value that appears most frequently.
  Data set above: mode = 7

When to use which:
  Mean — best for symmetric distributions with no extreme outliers.
  Median — best when there are extreme values (outliers) skewing the data.
  Mode — best for categorical data or to find the most popular value.

Chapter 6: Dot Diagrams, Stem-and-Leaf and Histograms

Dot diagram: each data point plotted as a dot above a number line. Good for small
data sets to see the shape of distribution.

Stem-and-leaf plot: stems are leading digits, leaves are trailing digits.
Preserves the original data while showing distribution.
Back-to-back stem-and-leaf compares two data sets side by side.

Histogram: bars with no gaps, used for continuous grouped data.
The y-axis shows frequency (or frequency density for unequal class widths).

Key vocabulary:
  Range = Largest value − Smallest value
  Interquartile range (IQR) = Q3 − Q1 (measure of spread, resistant to outliers)
"""

# ──────────────────────── STUDENTS ────────────────────────
# Three realistic student profiles with different performance patterns
STUDENTS = [
    {"name": "Wei Jie Tan",    "tag": "weijie",  "profile": "strong"},
    {"name": "Priya Nair",     "tag": "priya",   "profile": "average"},
    {"name": "Marcus Lim",     "tag": "marcus",  "profile": "struggling"},
]

# ──────────────────────── API CLIENT ────────────────────────
class Api:
    def __init__(self, base, token=None):
        self.base = base
        self.token = token

    def _headers(self, extra=None):
        h = {"accept": "application/json"}
        if self.token:
            h["authorization"] = f"Bearer {self.token}"
        if extra:
            h.update(extra)
        return h

    def get(self, path, timeout=60):
        return requests.get(self.base + path, headers=self._headers(), timeout=timeout)

    def post(self, path, body=None, timeout=120):
        return requests.post(
            self.base + path,
            headers=self._headers({"content-type": "application/json"}),
            data=json.dumps(body or {}),
            timeout=timeout,
        )

    def upload(self, path, filename, content, content_type="text/plain", timeout=240):
        return requests.post(
            self.base + path,
            headers=self._headers(),
            files={"file": (filename, content.encode(), content_type)},
            timeout=timeout,
        )


def unwrap(resp):
    try:
        j = resp.json()
    except Exception:
        return {}
    return j.get("data", j) if isinstance(j, dict) and "data" in j else j


def as_list(data, *keys):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for k in keys:
            v = data.get(k)
            if isinstance(v, list):
                return v
    return []


# ──────────────────────── HELPERS ────────────────────────
def log(msg):
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


def die(msg):
    log(f"FATAL: {msg}")
    sys.exit(1)


def rand_email(tag):
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
    return f"demo+{tag}-{suffix}@apalchi-demo.com"


def register(tag, role=None, birth_year=1990):
    email = rand_email(tag)
    body = {
        "email": email,
        "password": "DemoPass123!",
        "displayName": tag.replace("-", " ").title(),
        "birthYear": birth_year,
    }
    if role:
        body["role"] = role
    r = Api(BACKEND_URL).post("/api/v1/auth/register", body)
    d = unwrap(r)
    token = d.get("token")
    uid   = d.get("userId")
    if not token:
        die(f"register({tag}) HTTP {r.status_code}: {r.text[:200]}")
    log(f"  ✓ registered {tag} ({email})")
    return token, uid, email


def poll_compile(api, avatar_id, label="brain"):
    """Poll until brainState is READY. Returns True on READY, False on timeout.

    BrainState enum has three values: READY, PENDING_RECOMPILE, COMPILING.
    There is no FAILED brainState — a failed compile triggers PENDING_RECOMPILE
    (retry). Terminal condition is brainState == READY.

    The compile/status endpoint uses key "state" (JobState: RUNNING, DONE, FAILED);
    we log it for diagnostics but don't gate on it since brainState is authoritative.
    """
    log(f"  ⏳ polling {label} compile (up to {COMPILE_TIMEOUT_S}s)…")
    deadline = time.time() + COMPILE_TIMEOUT_S
    while time.time() < deadline:
        try:
            av = unwrap(api.get(f"/api/v1/avatars/{avatar_id}"))
            st = unwrap(api.get(f"/api/v1/avatars/{avatar_id}/wiki/compile/status"))
            brain = av.get("brainState") if isinstance(av, dict) else None
            job_state = st.get("state") if isinstance(st, dict) else None  # RUNNING/DONE/FAILED
            log(f"    brainState={brain} jobState={job_state}")
            if brain == "READY":
                log(f"  ✓ {label} compile READY")
                return True
        except Exception as e:
            log(f"    poll error: {e}")
        time.sleep(COMPILE_POLL_S)
    log(f"  ✗ {label} compile timed out after {COMPILE_TIMEOUT_S}s")
    return False


# ──────────────────────── MAIN SEED LOGIC ────────────────────────
def seed():
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    manifest = {"seeded_at": datetime.datetime.now().isoformat(), "backend": BACKEND_URL}

    # ── Safety check ──
    is_prod = "prod" in BACKEND_URL or "railway.app" in BACKEND_URL
    if is_prod and not ALLOW_PROD:
        die(
            "BACKEND_URL looks like prod. Set ALLOW_PROD=1 to proceed.\n"
            "This will create real demo accounts on your prod DB.\n"
            "They use @apalchi-demo.com addresses and can be cleaned up later."
        )

    log("═" * 60)
    log("Apalchi demo seeder starting")
    log(f"  Backend : {BACKEND_URL}")
    log(f"  Web     : {WEB_URL}")
    log(f"  Output  : {SCREENSHOT_DIR.resolve()}")
    log("═" * 60)

    # ── 1. Register teacher ──
    log("\n[1/8] Registering demo teacher…")
    t_token, t_uid, t_email = register("teacher-demo", role="TEACHER")
    teacher_api = Api(BACKEND_URL, t_token)
    manifest["teacher"] = {"email": t_email, "password": "DemoPass123!", "userId": t_uid}

    # ── 2. Onboard centre + create class ──
    log("\n[2/8] Creating centre and class…")
    ob = unwrap(teacher_api.post("/api/v1/centre/onboard", {"centreName": CENTRE_NAME}))
    # onboard returns: orgId, orgName, alreadyOwned
    org_id = ob.get("orgId")
    if not org_id:
        die(f"onboard failed: {ob}")
    log(f"  ✓ org: {org_id}")

    cls_r = unwrap(teacher_api.post(
        f"/api/v1/centre/organizations/{org_id}/classes",
        {"name": CLASS_NAME, "subject": SUBJECT},
    ))
    # class create returns: id, joinCode, corpusAvatarId (not classId)
    cls_id       = cls_r.get("id")
    corpus_avatar= cls_r.get("corpusAvatarId")
    join_code    = cls_r.get("joinCode")
    if not cls_id:
        die(f"class create failed: {cls_r}")
    log(f"  ✓ class: {cls_id}  corpus: {corpus_avatar}  joinCode: {join_code}")
    manifest.update({
        "org_id": org_id,
        "class_id": cls_id,
        "corpus_avatar": corpus_avatar,
        "join_code": join_code,
        "class_name": CLASS_NAME,
    })

    # ── 3. Upload documents ──
    log("\n[3/8] Uploading 3 study documents…")
    docs = [
        (DOC_1_NAME, DOC_1),
        (DOC_2_NAME, DOC_2),
        (DOC_3_NAME, DOC_3),
    ]
    for fname, content in docs:
        r = teacher_api.upload(f"/api/v1/avatars/{corpus_avatar}/files", fname, content)
        fid = unwrap(r).get("fileId", "?")
        log(f"  ✓ uploaded {fname}  → fileId={fid}  HTTP {r.status_code}")

    # ── 4. Wait for compile ──
    log("\n[4/8] Waiting for AI compile (this takes 1-3 min)…")
    compiled = poll_compile(teacher_api, corpus_avatar, label="class brain")
    if not compiled:
        log("  ⚠ compile did not finish cleanly — screenshots may show partial state")

    # ── 5. Generate modules ──
    log("\n[5/8] Generating modules…")
    gen_r = teacher_api.post(f"/api/v1/avatars/{corpus_avatar}/modules/generate", {}, timeout=240)
    log(f"  module generate: HTTP {gen_r.status_code}")
    time.sleep(3)

    # module list returns a bare List (unwrapped from ApiResponse.data) — each item has "id"
    mods_r = as_list(unwrap(teacher_api.get(f"/api/v1/avatars/{corpus_avatar}/modules")),
                     "modules", "items")
    module_ids = [m.get("id") for m in mods_r if m and m.get("id")]
    log(f"  ✓ {len(module_ids)} modules: {module_ids[:4]}")
    manifest["module_ids"] = module_ids

    # ── 6. Create assignment ──
    log("\n[6/8] Creating class assignment…")
    assign_resp = teacher_api.post(
        f"/api/v1/centre/organizations/{org_id}/classes/{cls_id}/assignments",
        {
            "title": "Chapter 1-3 Practice",
            "type": "REVISION",
            "moduleIds": module_ids[:3] if module_ids else [],
            "dueDate": (
                datetime.datetime.now() + datetime.timedelta(days=7)
            ).strftime("%Y-%m-%d"),  # bare date; backend LocalDate.parse handles YYYY-MM-DD
        },
    )
    assign_r = unwrap(assign_resp)
    # assignment create returns HTTP 201 with data: {id, title, type, moduleIds, ...}
    assign_id = assign_r.get("id") if isinstance(assign_r, dict) else None
    if not assign_id:
        log(f"  ⚠ assignment create HTTP {assign_resp.status_code}: {assign_resp.text[:200]}")
    else:
        log(f"  ✓ assignment: {assign_id}")
    manifest["assignment_id"] = assign_id

    # ── 7. Enrol students + submit quizzes ──
    log("\n[7/8] Enrolling 3 students and submitting quiz answers…")
    manifest["students"] = []

    for stu in STUDENTS:
        log(f"\n  Student: {stu['name']} ({stu['profile']})")
        s_token, s_uid, s_email = register(stu["tag"], birth_year=2008)
        student_api = Api(BACKEND_URL, s_token)

        # Join class
        join_r = unwrap(student_api.post(
            "/api/v1/centre/redeem-class-code", {"code": join_code}
        ))
        s_avatar = join_r.get("avatarId")
        log(f"    joined class → student avatar: {s_avatar}")

        if not s_avatar:
            log(f"    ⚠ could not get student avatar, skipping quiz submission")
            manifest["students"].append({
                "name": stu["name"], "email": s_email,
                "userId": s_uid, "avatar": None,
            })
            continue

        # Start assignment
        student_api.post(
            f"/api/v1/avatars/{s_avatar}/assignments/{assign_id}/start", {}
        )

        # Get quiz questions — daily returns List<QuizQuestionResponse> (bare list in data)
        quiz_resp = unwrap(student_api.get(f"/api/v1/avatars/{s_avatar}/quiz/daily"))
        questions = as_list(quiz_resp, "questions", "items")
        log(f"    quiz has {len(questions)} questions")

        # Build answer map based on profile (to create varied analytics)
        correct_map = {}
        for i, q in enumerate(questions):
            # QuizQuestionResponse field is "id" (not "questionId")
            q_id = q.get("id")
            options = q.get("options", [])
            n_opts = len(options)
            if not q_id or n_opts == 0:
                continue
            # Strong: get 80%+ right, Average: ~60%, Struggling: ~30%
            rand_val = random.random()
            thresholds = {"strong": 0.8, "average": 0.6, "struggling": 0.3}
            if rand_val < thresholds[stu["profile"]]:
                # Pick the correct answer (index 0 is our best guess without key)
                correct_map[q_id] = 0
            else:
                # Pick a wrong answer
                correct_map[q_id] = (1 % n_opts)

        # Submit quiz.
        # answers: questionId → selectedIndex (what the student picked).
        # correctMap: questionId → correctIndex (server uses its own answer-key;
        #   this is only the fallback for questions without a persisted key).
        submit_body = {
            "answers": {qid: idx for qid, idx in correct_map.items()},
            "correctMap": {qid: 0 for qid in correct_map},
        }
        sub_r = unwrap(student_api.post(
            f"/api/v1/avatars/{s_avatar}/quiz/answers", submit_body
        ))
        score = sub_r.get("score", "?")
        xp    = sub_r.get("xpEarned", "?")
        log(f"    ✓ quiz submitted → score={score}/{len(questions)}  xp={xp}")

        manifest["students"].append({
            "name": stu["name"],
            "email": s_email,
            "password": "DemoPass123!",
            "userId": s_uid,
            "avatar": s_avatar,
            "profile": stu["profile"],
            "quiz_score": score,
        })

    # ── Save manifest ──
    manifest_path = SCREENSHOT_DIR / "00_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    log(f"\n✓ Manifest saved: {manifest_path}")
    log(f"\n  Teacher login: {manifest['teacher']['email']} / DemoPass123!")
    log(f"  Join code:     {join_code}")
    log(f"  Org:           {org_id}")
    log(f"  Class:         {cls_id}")

    return manifest


# ──────────────────────── SCREENSHOT LOGIC ────────────────────────
def take_screenshots(manifest):
    import sys
    # Playwright installed to user site-packages on this machine
    sys.path.insert(0, "/Users/zackwhye/Library/Python/3.9/lib/python/site-packages")
    try:
        from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout
    except ImportError:
        log("\n⚠  playwright not installed — skipping screenshots.")
        log("   Run:  pip3 install playwright && /Users/zackwhye/Library/Python/3.9/bin/playwright install chromium")
        log("   Then re-run this script with SKIP_SEED=1 to just take screenshots.")
        log(f"   Teacher login: {manifest['teacher']['email']} / DemoPass123!")
        return

    t_email = manifest["teacher"]["email"]
    t_pass  = "DemoPass123!"

    log("\n[8/8] Taking screenshots with Playwright…\n")

    # Force light-mode localStorage before every page navigation so the
    # ThemeProvider (memoly_theme key) starts in light rather than default dark.
    LIGHT_MODE_INIT = """
        localStorage.setItem('memoly_theme', 'light');
        document.documentElement.setAttribute('data-theme', 'light');
    """

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)

        def make_ctx(width=1440, height=900, mobile=False):
            kwargs = dict(
                viewport={"width": width, "height": height},
                color_scheme="light",
            )
            if mobile:
                kwargs["user_agent"] = (
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                )
            ctx = browser.new_context(**kwargs)
            # Inject light-mode before EVERY page load in this context.
            ctx.add_init_script(LIGHT_MODE_INIT)
            return ctx

        # ── Teacher session (desktop 1440×900) ──
        ctx = make_ctx()
        page = ctx.new_page()

        idx = [1]  # mutable counter for filenames

        def ss(slug, description):
            filename = f"{idx[0]:02d}_{slug}.png"
            path = SCREENSHOT_DIR / filename
            # Re-apply theme attribute in case React hydration reset it.
            try:
                page.evaluate("document.documentElement.setAttribute('data-theme','light')")
            except Exception:
                pass
            page.screenshot(path=str(path), full_page=False)
            log(f"  📸 {filename} — {description}")
            log(f"     URL: {page.url}")
            idx[0] += 1

        def go(url, wait_ms=2500):
            page.goto(url, wait_until="networkidle", timeout=30000)
            page.wait_for_timeout(wait_ms)
            # Ensure light mode survives React hydration.
            try:
                page.evaluate("document.documentElement.setAttribute('data-theme','light')")
            except Exception:
                pass

        def click_tab(label, wait_ms=2000):
            """Click a tab button by its visible text label."""
            try:
                page.locator(f"button:has-text('{label}')").first.click()
                page.wait_for_timeout(wait_ms)
                return True
            except Exception:
                return False

        def click_link_text(text, wait_ms=2500):
            try:
                page.locator(f"a:has-text('{text}'), button:has-text('{text}')").first.click()
                page.wait_for_timeout(wait_ms)
                return True
            except Exception:
                return False

        def click_nav_item(label, wait_ms=2000):
            """Click sidebar nav item containing label."""
            try:
                page.get_by_text(label, exact=False).first.click()
                page.wait_for_timeout(wait_ms)
                return True
            except Exception:
                return False

        try:
            # ── Login ──
            log("  Logging in as teacher…")
            go(f"{WEB_URL}/login", wait_ms=1500)
            page.fill("input[type='email'], input[name='email']", t_email)
            page.fill("input[type='password'], input[name='password']", t_pass)
            page.keyboard.press("Enter")
            page.wait_for_timeout(4000)
            try:
                page.evaluate("document.documentElement.setAttribute('data-theme','light')")
            except Exception:
                pass

            # ── 01 Dashboard overview ──
            go(f"{WEB_URL}/dashboard")
            ss("dashboard_overview", "Teacher dashboard — home")

            # ── 02 Classes list ──
            go(f"{WEB_URL}/dashboard/classes")
            ss("classes_list", "Classes list")

            # ── 03 Class detail — Roster tab (default) ──
            cls_id   = manifest.get("class_id")
            class_url = f"{WEB_URL}/dashboard/classes/{cls_id}"
            go(class_url)
            ss("class_roster", "Class — Roster tab")

            # ── 04 Content tab (upload + wiki pages) ──
            go(class_url)
            click_tab("Content")
            ss("class_content", "Class — Content tab (uploaded docs + wiki)")

            # ── 05 Review tab (AI-generated content approval) ──
            go(class_url)
            click_tab("Review")
            ss("class_review", "Class — Review tab (approve / regenerate AI content)")

            # ── 06 Modules tab (LEARN→TEST→PROVE progress) ──
            go(class_url)
            click_tab("Modules")
            ss("class_modules", "Class — Modules tab (mastery progress)")

            # ── 07 Heatmap tab ──
            go(class_url)
            click_tab("Heatmap")
            ss("class_heatmap", "Class — Heatmap tab (who's behind)")

            # ── 08 Concept Mastery tab ──
            go(class_url)
            click_tab("Concept Mastery")
            ss("class_concepts", "Class — Concept Mastery tab")

            # ── 09 Assignments tab ──
            go(class_url)
            click_tab("Assignments")
            ss("class_assignments", "Class — Assignments tab")

            # ── 10 Class Brief tab ──
            go(class_url)
            click_tab("Class Brief")
            ss("class_brief", "Class — Class Brief (AI pre-class summary)")

            # ── 11 Students dashboard overview ──
            go(f"{WEB_URL}/dashboard/students")
            ss("students_overview", "Students dashboard — all students")

            # ── 12 Individual student detail — click first student ──
            stu = next((s for s in manifest.get("students", []) if s.get("name")), None)
            if stu:
                first_name = stu["name"].split()[0]
                clicked = click_link_text(first_name)
                if not clicked:
                    click_link_text("Wei")
                ss("student_detail", f"Student detail — {stu['name']}")

        except PlaywrightTimeout as e:
            log(f"  ⚠ Timed out: {e}")
        except Exception as e:
            import traceback
            log(f"  ⚠ Error: {e}")
            traceback.print_exc()

        ctx.close()

        # ── Marketing pages (no login, light mode) ──
        pub_ctx = make_ctx()
        pub_page = pub_ctx.new_page()
        for path_suffix, slug, desc in [
            ("/",        "marketing_home",    "Marketing homepage"),
            ("/demo",    "marketing_demo",    "Demo request page"),
            ("/login",   "marketing_login",   "Login page"),
            ("/pricing", "marketing_pricing", "Pricing page"),
        ]:
            try:
                pub_page.goto(f"{WEB_URL}{path_suffix}", wait_until="networkidle", timeout=20000)
                pub_page.wait_for_timeout(2000)
                try:
                    pub_page.evaluate("document.documentElement.setAttribute('data-theme','light')")
                except Exception:
                    pass
                filename = f"{idx[0]:02d}_{slug}.png"
                pub_page.screenshot(path=str(SCREENSHOT_DIR / filename), full_page=False)
                log(f"  📸 {filename} — {desc}")
                log(f"     URL: {pub_page.url}")
                idx[0] += 1
            except Exception as e:
                log(f"  ⚠ {path_suffix} error: {e}")
        pub_ctx.close()

        browser.close()

    log(f"\n✓ Screenshots saved to {SCREENSHOT_DIR.resolve()}")
    log("  Open the folder and pick the best ones.")


# ──────────────────────── ENTRY POINT ────────────────────────
if __name__ == "__main__":
    skip_seed = os.environ.get("SKIP_SEED") == "1"

    if skip_seed:
        # Re-use existing manifest (re-run screenshots only)
        manifest_path = SCREENSHOT_DIR / "00_manifest.json"
        if not manifest_path.exists():
            die("SKIP_SEED=1 but no manifest found. Run without SKIP_SEED first.")
        manifest = json.loads(manifest_path.read_text())
        log("Loaded existing manifest — skipping seed, taking screenshots only.")
    else:
        manifest = seed()

    take_screenshots(manifest)

    log("\n" + "═" * 60)
    log("Done.")
    log(f"  Screenshots : {SCREENSHOT_DIR.resolve()}")
    log(f"  Manifest    : {SCREENSHOT_DIR / '00_manifest.json'}")
    log(f"  Teacher     : {manifest['teacher']['email']} / DemoPass123!")
    log(f"  Join code   : {manifest.get('join_code', '—')}")
    log("═" * 60)
