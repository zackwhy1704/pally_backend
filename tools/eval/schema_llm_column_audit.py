#!/usr/bin/env python3
"""
schema_llm_column_audit.py — STATIC audit (no API, no backend, no network).

List every bounded VARCHAR column that an LLM / generation path can populate,
so we stop discovering overflow 400s one prod incident at a time. The class of
bug this targets: a generated title/slug/conflict_note that overflows a
VARCHAR(n) and fails the whole compile intermittently (content-dependent).

What it does:
  1. Parse all  src/main/java/**/*JpaEntity.java  for @Column fields → resolve
     table, column name, and the declared bound (length=N → VARCHAR(N);
     columnDefinition="TEXT" → TEXT; no length → VARCHAR(255) default).
  2. Parse all  src/main/resources/db/migration/*.sql  for the MIGRATED final
     column types (CREATE TABLE / ADD COLUMN / ALTER COLUMN ... TYPE). The
     migration state WINS over the entity annotation when they differ (entity
     annotations don't always track ALTERs).
  3. Cross-file grep for  set<Field>(  call sites; mark llm_derived=True when any
     calling file lives under infrastructure/ai/ or matches a generation pattern
     (*Generator, *Compiler, Compile*UseCase, WikiPagePersistenceService,
     Module*Service, *FlashcardGenerator, *HintTree*).
  4. Classify free-text vs identifier by column/field name keywords.
  5. RISK: HIGH = bounded VARCHAR + llm_derived + free-text.
           MEDIUM = bounded VARCHAR + llm_derived + identifier-ish.
           LOW = everything else.

Output: a sorted table (HIGH first) to stdout + tools/eval/out/schema_audit_<ts>.md,
and a final one-line summary `N HIGH, M MEDIUM`.

Resilient regex parsing — tolerates multi-line annotations. It does NOT need to
be perfect; it needs to surface the HIGH free-text-VARCHAR candidates.

Usage:
  python3 tools/eval/schema_llm_column_audit.py
"""
import os
import re
import sys
import datetime
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
# repo root = two levels up from tools/eval/
ROOT = HERE.parent.parent
JAVA_ROOT = ROOT / "src" / "main" / "java"
MIG_ROOT = ROOT / "src" / "main" / "resources" / "db" / "migration"
OUT_DIR = HERE / "out"

DEFAULT_VARCHAR = 255

FREE_TEXT_TOKENS = ("note", "content", "title", "summary", "explanation", "question",
                    "answer", "example", "text", "body", "description", "front",
                    "back", "hint", "reason", "feedback")
IDENT_TOKENS = ("slug", "id", "type", "status", "code", "kind", "mode", "color",
                "key", "name")

# generation-path file patterns (basename-level, case-insensitive on the matchers below)
GEN_FILE_PATTERNS = [
    re.compile(r".*Generator\.java$"),
    re.compile(r".*Compiler\.java$"),
    re.compile(r"Compile.*UseCase\.java$"),
    re.compile(r"WikiPagePersistenceService\.java$"),
    re.compile(r"Module.*Service\.java$"),
    re.compile(r".*FlashcardGenerator\.java$"),
    re.compile(r".*HintTree.*\.java$"),
]


def log(msg):
    print(msg, flush=True)


# ── camelCase → snake_case ────────────────────────────────────────────────────
def camel_to_snake(name):
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    s2 = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1)
    return s2.lower()


# ── entity parsing ────────────────────────────────────────────────────────────
# A @Column annotation may span multiple lines. We grab the @Column(...) block
# (balanced-ish via a non-greedy match up to the next field declaration), plus
# the field name on the following declaration line.
COLUMN_BLOCK = re.compile(
    r"@Column\s*(\((?P<args>[^;]*?)\))?\s*"          # optional (...) args, multi-line tolerant
    r"(?:@[A-Za-z]\w*(?:\s*\([^)]*\))?\s*)*"          # tolerate trailing annotations (@Enumerated etc.)
    r"(?:private|protected|public)\s+"
    r"(?:final\s+)?"
    r"(?P<ftype>[\w.<>\[\]]+)\s+"
    r"(?P<fname>\w+)\s*[;=]",
    re.DOTALL,
)

TABLE_RE = re.compile(r"@Table\s*\([^)]*name\s*=\s*\"(?P<name>[^\"]+)\"", re.DOTALL)
CLASSNAME_RE = re.compile(r"\bclass\s+(\w+?)JpaEntity\b")


def parse_arg_value(args, key):
    """Pull `key = ...` out of an @Column args blob (string or int)."""
    if not args:
        return None
    m = re.search(key + r"\s*=\s*\"([^\"]*)\"", args)
    if m:
        return m.group(1)
    m = re.search(key + r"\s*=\s*(\d+)", args)
    if m:
        return int(m.group(1))
    return None


def derive_table_name(class_base):
    """Best-effort default table name from a *JpaEntity class basename when no
    @Table(name=...) is present. We pluralize/snake-case loosely; the migration
    parse will reconcile real names where they exist."""
    snake = camel_to_snake(class_base)
    return snake  # default Hibernate naming is the snake-cased entity name


def parse_entity(path):
    """Return (table_guess, list of column dicts) for one *JpaEntity.java file."""
    text = path.read_text(errors="ignore")
    tm = TABLE_RE.search(text)
    cm = CLASSNAME_RE.search(text)
    class_base = cm.group(1) if cm else path.stem.replace("JpaEntity", "")
    table = tm.group("name") if tm else derive_table_name(class_base)

    cols = []
    for m in COLUMN_BLOCK.finditer(text):
        args = m.group("args")
        fname = m.group("fname")
        # column name: explicit name= wins, else camel→snake of field
        col_name = parse_arg_value(args, "name") or camel_to_snake(fname)
        length = parse_arg_value(args, "length")
        coldef = parse_arg_value(args, "columnDefinition")

        if coldef and "text" in str(coldef).lower():
            ctype, bound = "TEXT", None
        elif length is not None:
            ctype, bound = "VARCHAR", int(length)
        else:
            # No length, no TEXT coldef. JPA String default is VARCHAR(255).
            # But non-String types (int/boolean/Instant/enum-with-no-length) are
            # not VARCHAR at all — skip the obvious non-text scalar types so we
            # don't flag booleans/timestamps as VARCHAR(255).
            ftype = m.group("ftype")
            if _is_stringish(ftype):
                ctype, bound = "VARCHAR", DEFAULT_VARCHAR
            else:
                ctype, bound = _scalar_type(ftype), None
        cols.append({
            "table": table,
            "column": col_name,
            "field": fname,
            "entity_type": ctype,
            "entity_bound": bound,
            "class_base": class_base,
            "file": str(path),
        })
    return table, cols


_STRING_TYPES = {"String", "string"}
_NON_STRING = {"int", "Integer", "long", "Long", "boolean", "Boolean", "double",
               "Double", "float", "Float", "Instant", "LocalDate", "LocalDateTime",
               "BigDecimal", "UUID"}


def _is_stringish(ftype):
    base = ftype.split(".")[-1].split("<")[0]
    if base in _NON_STRING:
        return False
    # enums with EnumType.STRING usually carry an explicit length; a bare enum
    # field with no length we treat conservatively as stringish (VARCHAR(255)).
    return base in _STRING_TYPES or base[0:1].isupper() and base not in _NON_STRING


def _scalar_type(ftype):
    base = ftype.split(".")[-1].split("<")[0]
    return f"({base})"  # non-text scalar marker — never a VARCHAR risk


# ── migration parsing (final state wins) ──────────────────────────────────────
# CREATE TABLE foo ( col TYPE ..., col2 TYPE ... );
CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?\"?(?P<table>\w+)\"?\s*\((?P<body>.*?)\)\s*;",
    re.IGNORECASE | re.DOTALL,
)
ADD_COLUMN_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?\"?(?P<table>\w+)\"?\s+"
    r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?\"?(?P<col>\w+)\"?\s+(?P<type>[A-Za-z]+(?:\s*\(\d+\))?)",
    re.IGNORECASE,
)
ALTER_TYPE_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?\"?(?P<table>\w+)\"?\s+"
    r"ALTER\s+COLUMN\s+\"?(?P<col>\w+)\"?\s+(?:SET\s+DATA\s+)?TYPE\s+(?P<type>[A-Za-z]+(?:\s*\(\d+\))?)",
    re.IGNORECASE,
)
# a single column line inside a CREATE TABLE body
COL_LINE_RE = re.compile(
    r"^\s*\"?(?P<col>\w+)\"?\s+(?P<type>[A-Za-z]+(?:\s*\(\s*\d+\s*\))?)",
)

# tokens that look like a column name but are really table-level constraints
NON_COL_KEYWORDS = {"primary", "foreign", "unique", "constraint", "check", "key", "index"}


def normalize_type(raw):
    """'VARCHAR(160)'/'varchar (160)'/'TEXT' → ('VARCHAR', 160) or ('TEXT', None)."""
    raw = raw.strip()
    m = re.match(r"(?i)(VARCHAR|CHARACTER\s+VARYING)\s*\(\s*(\d+)\s*\)", raw)
    if m:
        return "VARCHAR", int(m.group(2))
    if re.match(r"(?i)TEXT", raw):
        return "TEXT", None
    if re.match(r"(?i)(VARCHAR|CHARACTER\s+VARYING)", raw):
        return "VARCHAR", DEFAULT_VARCHAR
    return raw.upper(), None


def parse_migrations():
    """Return {(table, col): (type, bound)} reflecting the FINAL migrated state.
    Later migrations (sorted by version) override earlier ones."""
    final = {}

    def version_key(p):
        m = re.match(r"V(\d+)", p.name)
        return (int(m.group(1)) if m else 0, p.name)

    files = sorted([p for p in MIG_ROOT.glob("*.sql")], key=version_key)
    for p in files:
        sql = p.read_text(errors="ignore")
        # CREATE TABLE bodies
        for ct in CREATE_TABLE_RE.finditer(sql):
            table = ct.group("table").lower()
            body = ct.group("body")
            for line in body.split(","):
                lm = COL_LINE_RE.match(line)
                if not lm:
                    continue
                col = lm.group("col").lower()
                if col in NON_COL_KEYWORDS:
                    continue
                t, b = normalize_type(lm.group("type"))
                if t in ("VARCHAR", "TEXT"):
                    final[(table, col)] = (t, b)
        # ADD COLUMN
        for ac in ADD_COLUMN_RE.finditer(sql):
            t, b = normalize_type(ac.group("type"))
            if t in ("VARCHAR", "TEXT"):
                final[(ac.group("table").lower(), ac.group("col").lower())] = (t, b)
        # ALTER COLUMN ... TYPE  (the override that widened wiki title→TEXT etc.)
        for al in ALTER_TYPE_RE.finditer(sql):
            t, b = normalize_type(al.group("type"))
            final[(al.group("table").lower(), al.group("col").lower())] = (t, b)
    return final


# ── setter call-site grep (llm_derived) ───────────────────────────────────────
def build_setter_index():
    """Return {setterName: {file: file_text}} across all Java files. setterName is
    like 'setConflictNote'. We keep the caller's text so the attribution step can
    confirm the caller actually references the target entity — generic setters
    (setId/setUserId/setStatus) are shared by dozens of entities, so a name-only
    match would false-attribute one generator's setId to every table."""
    index = {}
    java_files = list(JAVA_ROOT.rglob("*.java"))
    call_re = re.compile(r"\.(set[A-Z]\w*)\s*\(")
    for jf in java_files:
        txt = jf.read_text(errors="ignore")
        seen = set(m.group(1) for m in call_re.finditer(txt))
        for setter in seen:
            index.setdefault(setter, {})[str(jf)] = txt
    return index


def caller_touches_entity(file_text, class_base):
    """Cheap confirmation that a calling file plausibly writes to THIS entity:
    it must reference the entity's class base (e.g. 'WikiPage', 'LearningModule',
    'Assignment', 'ModuleContentItem'). Filters out the setId/setUserId/setStatus
    cross-entity collisions while staying a simple text check."""
    if not class_base:
        return True
    # match the bare domain/entity name as a whole word (WikiPage, WikiPageJpaEntity…)
    return re.search(r"\b" + re.escape(class_base) + r"\b", file_text) is not None


def is_generation_file(path_str):
    base = os.path.basename(path_str)
    if "/infrastructure/ai/" in path_str.replace("\\", "/"):
        return True
    return any(p.match(base) for p in GEN_FILE_PATTERNS)


# ── classification ────────────────────────────────────────────────────────────
def classify_category(column, field):
    name = f"{column} {field}".lower()
    free = any(tok in name for tok in FREE_TEXT_TOKENS)
    ident = any(tok in name for tok in IDENT_TOKENS)
    if free and not (ident and not free):
        # free-text tokens win when present (a 'slug' is ident, but 'conflict_note' is free)
        if free:
            return "free-text"
    if ident:
        return "identifier"
    return "free-text" if free else "other"


def risk_of(final_type, final_bound, llm_derived, category):
    bounded_varchar = final_type == "VARCHAR" and final_bound is not None
    if bounded_varchar and llm_derived and category == "free-text":
        return "HIGH"
    if bounded_varchar and llm_derived:
        return "MEDIUM"
    return "LOW"


# ── main ──────────────────────────────────────────────────────────────────────
def main():
    if not JAVA_ROOT.is_dir():
        sys.exit(f"✗ java root not found: {JAVA_ROOT}")
    if not MIG_ROOT.is_dir():
        sys.exit(f"✗ migration root not found: {MIG_ROOT}")

    entity_files = sorted(JAVA_ROOT.rglob("*JpaEntity.java"))
    migrations = parse_migrations()
    setter_index = build_setter_index()

    rows = []
    for ef in entity_files:
        _, cols = parse_entity(ef)
        for c in cols:
            table = c["table"].lower()
            col = c["column"].lower()
            # final type: migration wins; else entity annotation
            if (table, col) in migrations:
                ftype, fbound = migrations[(table, col)]
            else:
                ftype, fbound = c["entity_type"], c["entity_bound"]

            # only care about text-ish columns (VARCHAR/TEXT); skip scalar markers
            if ftype not in ("VARCHAR", "TEXT"):
                continue

            # llm_derived: who calls set<Field>( — but only count a generation file
            # when it also references this entity's class base, so a generator's
            # generic setId/setStatus isn't attributed to every table that has one.
            setter = "set" + c["field"][0].upper() + c["field"][1:]
            callers = setter_index.get(setter, {})
            gen_callers = sorted({
                os.path.basename(path)
                for path, text in callers.items()
                if is_generation_file(path) and caller_touches_entity(text, c["class_base"])
            })
            llm_derived = len(gen_callers) > 0

            category = classify_category(col, c["field"])
            risk = risk_of(ftype, fbound, llm_derived, category)

            type_str = f"VARCHAR({fbound})" if ftype == "VARCHAR" else "TEXT"
            rows.append({
                "table": table, "column": col, "type": type_str,
                "written_by": gen_callers, "llm_derived": llm_derived,
                "category": category, "risk": risk,
            })

    # dedupe (table.column) keeping the highest-risk / most-informative row
    rank = {"HIGH": 3, "MEDIUM": 2, "LOW": 1}
    best = {}
    for r in rows:
        key = (r["table"], r["column"])
        cur = best.get(key)
        if cur is None or rank[r["risk"]] > rank[cur["risk"]]:
            best[key] = r
        elif rank[r["risk"]] == rank[cur["risk"]] and len(r["written_by"]) > len(cur["written_by"]):
            best[key] = r
    rows = list(best.values())

    rows.sort(key=lambda r: (-rank[r["risk"]], r["table"], r["column"]))

    n_high = sum(1 for r in rows if r["risk"] == "HIGH")
    n_med = sum(1 for r in rows if r["risk"] == "MEDIUM")

    # ── render table ──
    def written_str(files, width=46):
        s = ",".join(files) if files else "-"
        return (s[: width - 1] + "…") if len(s) > width else s

    header = f"{'table.column':40s} | {'type':14s} | {'written_by(files)':46s} | {'llm':3s} | {'category':10s} | RISK"
    sep = "-" * len(header)
    lines = [header, sep]
    for r in rows:
        tc = f"{r['table']}.{r['column']}"
        lines.append(
            f"{tc[:40]:40s} | {r['type']:14s} | {written_str(r['written_by']):46s} | "
            f"{('yes' if r['llm_derived'] else 'no'):3s} | {r['category']:10s} | {r['risk']}"
        )

    table_text = "\n".join(lines)
    log(table_text)
    summary = f"\n{n_high} HIGH, {n_med} MEDIUM"
    log(summary)

    # ── write markdown artifact ──
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    md = [
        f"# LLM-column schema audit — {ts}",
        "",
        "Bounded `VARCHAR` columns reachable from an LLM/generation path. **HIGH** = "
        "bounded VARCHAR that an LLM writes free text into → an overflow-400 waiting to happen.",
        "",
        f"**{n_high} HIGH, {n_med} MEDIUM**",
        "",
        "| table.column | type | written_by (files) | llm_derived | category | RISK |",
        "|---|---|---|---|---|---|",
    ]
    for r in rows:
        wb = ", ".join(r["written_by"]) if r["written_by"] else "-"
        md.append(
            f"| `{r['table']}.{r['column']}` | {r['type']} | {wb} | "
            f"{'yes' if r['llm_derived'] else 'no'} | {r['category']} | **{r['risk']}** |"
        )
    md.append("")
    out_path = OUT_DIR / f"schema_audit_{ts}.md"
    out_path.write_text("\n".join(md) + "\n")
    log(f"\nartifact → {out_path}")


if __name__ == "__main__":
    main()
