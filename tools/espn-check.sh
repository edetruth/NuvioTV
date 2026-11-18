#!/usr/bin/env bash
# Manual check of ESPN's score feeds (#70): one scoreboard per supported league for today (US Eastern)
# and one game summary, each checked for the fields the app reads. Prints OK or MISSING FIELD lines and
# exits 1 if anything is missing. Run by hand when scores look wrong; not wired into CI.
#
#   bash tools/espn-check.sh
#
# Needs bash, curl and python. Sends only the league and date, as the app does.
set -u

BASE="https://site.api.espn.com/apis/site/v2/sports"
UA="Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko)"
PY="${PYTHON:-python}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ESPN files games under their US Eastern date.
DAY="$("$PY" -X utf8 -c 'from datetime import datetime, timedelta, timezone
try:
    from zoneinfo import ZoneInfo; print(datetime.now(ZoneInfo("America/New_York")).strftime("%Y%m%d"))
except Exception:
    print((datetime.now(timezone.utc) - timedelta(hours=5)).strftime("%Y%m%d"))')"

# path|extra query (college scoreboards list only featured games without a group).
LEAGUES="basketball/nba|
football/nfl|
hockey/nhl|
baseball/mlb|
football/college-football|&groups=80
basketball/mens-college-basketball|&groups=50
soccer/all|
tennis/atp|
tennis/wta|
mma/ufc|
racing/f1|
golf/pga|
golf/lpga|"

failed=0
summary_league=""
summary_event=""

fetch() { # url out
  curl -sS -L --max-time 20 -A "$UA" -o "$2" -w '%{http_code}' "$1" 2>"$TMP/curl.err" || echo "000"
}

while IFS='|' read -r path extra; do
  [ -z "$path" ] && continue
  out="$TMP/board.json"
  code="$(fetch "$BASE/$path/scoreboard?dates=$DAY&limit=1000$extra" "$out")"
  if [ "$code" != "200" ]; then
    echo "MISSING FIELD $path: HTTP $code $(head -c 200 "$TMP/curl.err")"
    failed=1
    continue
  fi
  result="$("$PY" -X utf8 - "$path" "$out" <<'PYEOF'
import json, sys
path, file = sys.argv[1], sys.argv[2]
sport = path.split("/")[0]
try:
    data = json.load(open(file, encoding="utf-8"))
except Exception as e:
    print(f"MISSING FIELD {path}: unreadable JSON ({e.__class__.__name__})"); sys.exit()
if not isinstance(data, dict) or "events" not in data:
    print(f"MISSING FIELD {path}: events"); sys.exit()
events = data.get("events") or []
if not events:
    print(f"OK {path} (no events today)"); sys.exit()
# A field is missing when no event has it: a whole-feed shape change, as the app counts it (one TBD
# side or a doubles pair without an athlete is not).
seen, asked = {}, []
def need(cond, field):
    if field not in seen:
        seen[field] = False
        asked.append(field)
    if cond:
        seen[field] = True
first_id = None
for ev in events:
    need(ev.get("id"), "events[].id")
    need(((ev.get("status") or {}).get("type") or {}).get("state"), "events[].status.type.state")
    if sport == "tennis":
        # The app reads the singles draws only.
        singles = [g for g in ev.get("groupings") or [] if ((g.get("grouping") or {}).get("slug") or "").endswith("-singles")]
        need(ev.get("groupings"), "events[].groupings")
        for g in singles:
            for c in g.get("competitions") or []:
                for p in c.get("competitors") or []:
                    need((p.get("athlete") or {}).get("displayName"), "singles competitions[].competitors[].athlete.displayName")
        continue
    comps = ev.get("competitions") or []
    need(comps, "events[].competitions")
    competitors = (comps[0].get("competitors") if comps else None) or []
    need(competitors, "events[].competitions[0].competitors")
    if sport in ("mma", "racing", "golf") or not competitors:
        continue
    need({"home", "away"} <= {c.get("homeAway") for c in competitors}, "competitors[].homeAway (home/away)")
    for c in competitors:
        team = c.get("team") or {}
        need(any(team.get(k) for k in ("abbreviation", "displayName", "shortDisplayName", "name", "location")),
             "competitors[].team names")
    if first_id is None and ev.get("id"):
        first_id = ev["id"]
missing = [f for f in asked if not seen[f]]
if missing:
    for field in missing:
        print(f"MISSING FIELD {path}: {field}")
else:
    print(f"OK {path} ({len(events)} events)")
if first_id:
    print(f"EVENT {first_id}")
PYEOF
)"
  while IFS= read -r line; do
    case "$line" in
      EVENT\ *) [ -z "$summary_event" ] && { summary_event="${line#EVENT }"; summary_league="$path"; } ;;
      MISSING*) echo "$line"; failed=1 ;;
      *) echo "$line" ;;
    esac
  done <<< "$result"
done <<< "$LEAGUES"

if [ -z "$summary_event" ]; then
  echo "OK summary (no team game today to check)"
else
  out="$TMP/summary.json"
  code="$(fetch "$BASE/$summary_league/summary?event=$summary_event" "$out")"
  if [ "$code" != "200" ]; then
    echo "MISSING FIELD $summary_league summary $summary_event: HTTP $code"
    failed=1
  else
    line="$("$PY" -X utf8 - "$summary_league" "$summary_event" "$out" <<'PYEOF'
import json, sys
path, event, file = sys.argv[1:4]
try:
    data = json.load(open(file, encoding="utf-8"))
except Exception as e:
    print(f"MISSING FIELD {path} summary {event}: unreadable JSON ({e.__class__.__name__})"); sys.exit()
missing = []
comps = ((data.get("header") or {}).get("competitions")) or []
if not comps:
    missing.append("header.competitions")
else:
    competitors = comps[0].get("competitors") or []
    if not competitors:
        missing.append("header.competitions[0].competitors")
    if {c.get("homeAway") for c in competitors} < {"home", "away"}:
        missing.append("header.competitions[0].competitors[].homeAway")
    if not ((comps[0].get("status") or {}).get("type") or {}).get("state"):
        missing.append("header.competitions[0].status.type.state")
if "boxscore" not in data:
    missing.append("boxscore")
if missing:
    print("\n".join(f"MISSING FIELD {path} summary {event}: {m}" for m in missing))
else:
    print(f"OK {path} summary {event}")
PYEOF
)"
    echo "$line"
    case "$line" in *MISSING*) failed=1 ;; esac
  fi
fi

exit "$failed"
