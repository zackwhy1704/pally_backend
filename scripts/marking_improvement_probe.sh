#!/usr/bin/env bash
# ── Marking-assistant IMPROVEMENT probe — RIGOROUS (multi-subject, repeated, controlled) ──
# Addresses the "n=1 / grep-not-scorer / no-control" critique:
#   • MANY SUBJECTS & marking types: Maths (numeric -2 units), Science (formula rule),
#     English (qualitative band phrase "Developing").
#   • REPEATED: N drafts per round (default 5) → adoption RATE, not a single 0/1.
#   • CONTROL: a rule NOT taught in the exemplars — must NOT change round1→round2
#     (proves the delta is the trained rule, not "more context").
#   • SCORED BY THE REAL RULER: collects each draft + ground truth; a JUnit reporter
#     (MarkingImprovementReportTest, run next) scores with the shipped AgreementScorer.
# Round 1 = minimal rubric (no centre rule) → generic marking. Train on the centre's
# marked exemplars → Round 2 must adopt the centre rule it could ONLY have learned.
# Requires prod + live model keys (compiles must yield pages, else it FAILS loudly).
set -uo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"
N="${N:-5}"
OUT="${OUT:-probe-out/marking-improvement}"; SAMP="$OUT/samples"; rm -rf "$SAMP"; mkdir -p "$SAMP"
api(){ local m="$1" p="$2" b="${3:-}"; local a=(-sS -X "$m" "$BASE$p" -H 'content-type: application/json'); [ -n "${TOKEN:-}" ] && a+=(-H "authorization: Bearer $TOKEN"); [ -n "$b" ] && a+=(--data "$b"); curl "${a[@]}"; }
uw(){ jq 'if type=="object" and has("data") then .data else . end'; }
: > "$OUT/manifest.tsv"   # subject \t gtGrade \t taughtRegex \t controlRegex \t gtComments

# ── per-subject fixtures (subject, backend subject enum, taught rule text, control) ──
setup_subject(){
  SUBJ="$1"
  case "$SUBJ" in
    maths)
      SUBJECT_ENUM="Maths"
      RUBRIC="Mark each question. Credit correct working and the correct final answer. Be encouraging."
      EX1="MARKED PAPER 6/8 — Q: car 150km in 2h, speed? speed=distance/time [+2 method] =150/2=75 [+4 accuracy] Answer 75 [NO UNITS: this centre deducts 2 marks]. Total 6/8. 'Our centre rule: -2 whenever units are missing (km/h).'"
      EX2="MARKED PAPER 4/8 — Q: tank 240L in 4min, rate? rate=volume/time [+2] =60 [+4] Answer 60 [units missing: -2 our centre rule]. Total 4/8."
      SUB="Q: A train travels 240 km in 3 hours. Find its speed. speed=distance/time = 240/3 = 80. Answer: 80"
      GT_GRADE="6/8"
      GT_COMMENTS="Correct method and answer (80), but -2 for missing units (km/h) per our centre rule. 6/8."
      TAUGHT_RE="\\-?2 ?(marks?|mark)|deduct.{0,14}2|two marks?"       # the -2 units centre rule
      CONTROL_RE="restate the question|state your assumptions|show a diagram"  # never taught
      ;;
    science)
      SUBJECT_ENUM="Science"
      RUBRIC="Mark each question. Credit correct science reasoning and the correct answer."
      EX1="MARKED PAPER 3/5 — Q: current in a 12V, 4ohm circuit? Used I=V/R [+1 formula: THIS CENTRE requires the FORMULA written to earn the mark] =3 [+1] Answer 3A [+1]. Missing formula would score 0 for that step. 3/5. 'Centre rule: no formula written = no method mark.'"
      EX2="MARKED PAPER 2/5 — Q: power? Wrote P=VI [+1 formula shown] =36 [+1] Answer 36W. Centre rule: formula MUST be written to earn the method mark."
      SUB="Q: A 9V battery drives 3A through a resistor. Find the resistance. R = 9/3 = 3. Answer: 3 ohms"
      GT_GRADE="3/3"
      GT_COMMENTS="Correct. You wrote the formula R=V/I which earns the method mark under our centre rule (formula must be shown). 3/3."
      TAUGHT_RE="formula.{0,30}(shown|written|earn|method)|no formula.{0,20}no|centre rule"
      # Genuinely-absent control: a circuit-DIAGRAM requirement is neither taught in
      # the exemplars nor a model default for a numeric resistance answer → must read ~0.
      CONTROL_RE="diagram|draw.{0,15}circuit"
      ;;
    english)
      SUBJECT_ENUM="English"
      # NON-GENERIC taught rule: an arbitrary, detectable, counter-intuitive centre
      # convention the model would NEVER emit unprompted — a discrete "+1 hook mark"
      # awarded ONLY for a question/statistic opening. (The old "comment on structure"
      # rule was something the model already does → no headroom → proved nothing.)
      RUBRIC="Give brief feedback on the paragraph."
      EX1="MARKED PARAGRAPH — hook mark: 0/1. THIS CENTRE awards a discrete +1 'hook mark' ONLY when the FIRST sentence is a QUESTION or a STATISTIC. This opening is a plain statement, so the hook mark is 0. Feedback: 'No hook mark — open with a question or a statistic to earn the +1 hook mark.'"
      EX2="MARKED PARAGRAPH — hook mark: 1/1. Opening is a question ('Did you know dogs sleep 12 hours?') so the centre's +1 hook mark is awarded. 'Great hook — a question opening earns the hook mark.'"
      SUB="Paragraph: Dogs are good. They run and play. My dog is brown and likes food."
      GT_GRADE=""
      GT_COMMENTS="No hook mark (0/1) — the opening is a plain statement; our centre awards the +1 hook mark only for a question or statistic opening."
      TAUGHT_RE="hook mark"     # arbitrary centre artifact; the model won't invent this phrase
      # Genuinely-absent control: adjective-counting is neither taught nor a model default.
      CONTROL_RE="adjective"
      ;;
  esac
  printf '%s\t%s\t%s\t%s\t%s\n' "$SUBJ" "$GT_GRADE" "$TAUGHT_RE" "$CONTROL_RE" "$GT_COMMENTS" >> "$OUT/manifest.tsv"
}

mkref(){ local f; f="$(mktemp)"; printf '%s\n' "$3" > "$f"; curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references" -H "authorization: Bearer $TOKEN" -F "files=@$f;type=text/plain" -F "kind=$1" -F "title=$2" >/dev/null; rm -f "$f"; }
poll_ready(){ for i in $(seq 1 36); do sleep 5; local b st pg; b="$(api GET "/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references/brain" | uw)"; st="$(echo "$b" | jq -r '.state//"?"')"; pg="$(echo "$b" | jq -r '.pageCount//0')"; [ "$st" = "READY" ] && [ "${pg:-0}" -ge 1 ] && return 0; done; return 1; }
draft(){ # $1=subject $2=round $3=rep ; writes flattened comments to samples/
  local sf; sf="$(mktemp)"; printf '%s\n' "$SUB" > "$sf"
  local sid; sid="$(curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/submissions" -H "authorization: Bearer $TOKEN" -F "files=@$sf;type=text/plain" -F "title=$1 r$2 n$3" | uw | jq -r '.id // empty')"; rm -f "$sf"
  [ -z "$sid" ] && { echo "" > "$SAMP/${1}__r${2}__${3}.txt"; return; }
  api POST "/api/v1/centre/organizations/$ORG/classes/$CLS/submissions/$sid/ai-draft" '{}' | uw \
    | jq -r '[.. | strings] | join(" ")' 2>/dev/null > "$SAMP/${1}__r${2}__${3}.txt" || echo "" > "$SAMP/${1}__r${2}__${3}.txt"
}

echo "== rigorous marking-improvement probe @ $BASE (N=$N reps/round) =="
for SUBJ in maths science english; do
  setup_subject "$SUBJ"
  echo "── subject: $SUBJ ──"
  TOKEN="$(api POST /api/v1/auth/register "$(jq -nc --arg e "mk-${SUBJ}-$(date +%s)@apalchi-test.com" '{email:$e,password:"Probe12345!",displayName:"Probe",birthYear:1990}')" | uw | jq -r '.token // empty')"
  [ -z "$TOKEN" ] && { echo "FAIL register $SUBJ"; exit 1; }
  ORG="$(api POST /api/v1/centre/onboard "$(jq -nc --arg n "MI-$SUBJ" '{centreName:$n}')" | uw | jq -r '.orgId // .id // empty')"
  CLS="$(api POST "/api/v1/centre/organizations/$ORG/classes" "$(jq -nc --arg s "$SUBJECT_ENUM" '{name:"C",subject:$s,level:"P5",characterType:"MOCHI"}')" | uw | jq -r '.id // empty')"
  [ -z "$CLS" ] && { echo "FAIL class $SUBJ"; exit 1; }
  mkref GUIDELINE "Minimal rubric" "$RUBRIC"; poll_ready || echo "  (r1 brain slow)"
  for n in $(seq 1 "$N"); do draft "$SUBJ" 1 "$n"; done
  mkref MARKED_PAPER "Centre exemplar 1" "$EX1"; mkref MARKED_PAPER "Centre exemplar 2" "$EX2"; poll_ready || echo "  (r2 brain slow)"
  for n in $(seq 1 "$N"); do draft "$SUBJ" 2 "$n"; done
  # quick inline adoption rate (human-readable; the JUnit reporter is authoritative)
  r1=$(grep -lEc "" "$SAMP/${SUBJ}__r1__"*.txt >/dev/null 2>&1; grep -lE "$TAUGHT_RE" "$SAMP/${SUBJ}__r1__"*.txt 2>/dev/null | wc -l | tr -d ' ')
  r2=$(grep -lE "$TAUGHT_RE" "$SAMP/${SUBJ}__r2__"*.txt 2>/dev/null | wc -l | tr -d ' ')
  c1=$(grep -lE "$CONTROL_RE" "$SAMP/${SUBJ}__r1__"*.txt 2>/dev/null | wc -l | tr -d ' ')
  c2=$(grep -lE "$CONTROL_RE" "$SAMP/${SUBJ}__r2__"*.txt 2>/dev/null | wc -l | tr -d ' ')
  echo "  taught-rule adoption: round1 ${r1}/$N → round2 ${r2}/$N | control: ${c1}/$N → ${c2}/$N"
done
echo
echo "Samples in $SAMP/ ; manifest $OUT/manifest.tsv"
echo "Next: ./gradlew test --tests com.pally.marking.MarkingImprovementReportTest -Dprobe.samples=$PWD/$SAMP -Dprobe.manifest=$PWD/$OUT/manifest.tsv"
