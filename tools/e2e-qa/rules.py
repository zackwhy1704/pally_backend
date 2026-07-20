"""
Kestrel grounding rules + the grounding/leak sweeps.

These are the CHECKED-IN source of truth for the Kestrel fixture grading. The
Kestrel Method is a wholly invented study system (fictional legal-term
vocabulary) so that ANY overlap with a real-world productivity method proves
the model invented content instead of grounding on the uploaded notes.

The lists mirror the fixture pack README's legal-term list verbatim.
"""
import re

# Invented vocabulary that MUST be the only method language present.
LEGAL_TERMS = [
    "Kestrel Principle", "Hover", "Hover Questions", "False Hovering",
    "Perch Block", "Glide", "Long Glide", "one branch one bird",
    "branch-hopping", "Stoop", "Dive Card", "folding the wings",
    "Molt Review", "over-molting", "Wind Reading", "headwind", "tailwind",
    "crosswind", "wind chart",
]

# concept -> the acceptable numeric renderings for its number-fact. A served
# string that names the concept but pairs it with a DIFFERENT number is a
# grounding contradiction (invention / hallucinated figure).
NUMBER_FACTS = {
    "Hover": ["90 seconds", "90-second", "90 second"],
    "Perch Block": ["40"],
    "Glide": ["9"],
    "Long Glide": ["30"],
    "chained blocks max": ["three", "3"],
    "Stoop": ["2.5"],
    "Stoops/week max": ["two", "2"],
    "Molt": ["20"],
    "chart": ["14"],
}

# Real-world methods that DO NOT appear in the source. Presented as content =
# invention (QA-1.3 / 1.4).
BANNED_REAL_WORLD = [
    "Pomodoro", "Eisenhower", "GTD", "Getting Things Done", "time-boxing",
    "deep work", "Parkinson's Law", "Parkinson’s Law",
]

# The truncation canary. Planted past the 3000-char mark in the source; if it
# surfaces in ANY served string the compiler read past 3000 chars. Not a
# failure — an INFO finding for the truncation ledger.
CANARY = "violet anchor"

# Persona / grade leak — the tutor must never expose an internal learner model.
PERSONA_LEAK = re.compile(r"primary school|\bP5\b|young student", re.IGNORECASE)

# Rubric / answer-model leak on TEST content — internal grading language.
RUBRIC_LEAK = re.compile(
    r"student's misconception|the student believes|this assesses",
    re.IGNORECASE,
)

# Digit runs we treat as "a number near a concept" for the contradiction check.
_NUM = re.compile(r"\d+(?:\.\d+)?")


def collect_strings(obj, _acc=None):
    """Recursively gather every string leaf from a served payload.

    JSON-encoded strings (contentJson often arrives as a JSON string) are
    parsed one level so their inner text is swept too.
    """
    import json as _json
    if _acc is None:
        _acc = []
    if isinstance(obj, str):
        _acc.append(obj)
        s = obj.strip()
        if s and s[0] in "{[":
            try:
                collect_strings(_json.loads(s), _acc)
            except Exception:
                pass
    elif isinstance(obj, dict):
        for v in obj.values():
            collect_strings(v, _acc)
    elif isinstance(obj, list):
        for v in obj:
            collect_strings(v, _acc)
    return _acc


def banned_realworld_hits(strings):
    """Return [(term, excerpt)] for any real-world method named in content."""
    hits = []
    for s in strings:
        low = s.lower()
        for term in BANNED_REAL_WORLD:
            if term.lower() in low:
                hits.append((term, _excerpt(s, term)))
    return hits


def number_contradictions(strings):
    """Best-effort: a concept named in the same string as a number that is NOT
    one of its accepted renderings, with no accepted rendering also present.

    Heuristic by design (natural-language numbers are hard); flagged as such in
    the report. Tuned to avoid false positives: only fires when the string
    contains the concept AND a digit-number AND none of the accepted values.
    """
    out = []
    for s in strings:
        low = s.lower()
        for concept, accepted in NUMBER_FACTS.items():
            cname = concept.split("/")[0].split(" max")[0].strip().lower()
            if cname not in low:
                continue
            if any(a.lower() in low for a in accepted):
                continue  # an accepted rendering is present -> fine
            # accepted set is purely word-numbers (three/two) -> skip digit check
            if all(not _NUM.search(a) and a not in ("3", "2") for a in accepted):
                # still catch a wrong word-number could be added later; skip for now
                pass
            nums = _NUM.findall(s)
            if nums:
                out.append((concept, accepted, nums, _excerpt(s, cname)))
    return out


def canary_hits(strings):
    return [_excerpt(s, CANARY) for s in strings if CANARY.lower() in s.lower()]


def persona_leaks(strings):
    return [_excerpt(s, "") for s in strings if PERSONA_LEAK.search(s)]


def rubric_leaks(strings):
    return [_excerpt(s, "") for s in strings if RUBRIC_LEAK.search(s)]


def _excerpt(s, needle, width=90):
    s = " ".join(s.split())
    if needle:
        i = s.lower().find(needle.lower())
        if i >= 0:
            a = max(0, i - width // 2)
            return ("…" if a else "") + s[a:a + width] + ("…" if a + width < len(s) else "")
    return s[:width] + ("…" if len(s) > width else "")
