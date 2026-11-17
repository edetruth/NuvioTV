# Sports & Live — "beyond cards" view designs (2026-11-16)

Approved by the user ("I love the designs"). Three alternative views of the same games, shown as tabs/modes inside Sports & Live. Mockups only — not yet built. Issues: **#23 Scoreboard**, **#24 Matchday timeline**, **#25 Channel guide**. Depend on live scores v1 (#17, plan `docs/superpowers/plans/2026-11-17-sports-live-scores-v1.md`).

- Source of truth for layout: the `.dc.html` files in this folder (1920×1080 TV frames; static HTML with inline styles — open in a browser). `canvas.json` is the canvas index.
- Online canvas (private to the user): https://claude.ai/artifact/83uJQ6VNpczeeMhEvQhL8p
- All data in the mockups is placeholder sample data.

## Shared visual language
- Dark broadcast look. Background `#0A0D12`; panels `#121821` / `#0E131A`; row lines `#1E2632`.
- Text `#F2F4F7`, secondary `#C7CED8`, muted `#9AA4B2`.
- Live red `#FF4D57` (live block bg `#2A1416`/`#3A171B`, live text `#FFB3B8`); followed-team gold `#FFC107`.
- Focus = 3px white border + shadow; upcoming = 2px `#2A3442` outline; finals = dimmed `#151B24`, score hidden by default.
- Fonts: Space Grotesk (titles, scores, tabular numerals), Inter (body). TV-size type (20–64px). 64px side padding.
- Header on every view: title ("Matchday" / "Channels"), date line in **local time**, a pill switcher **Timeline | Scoreboard | Channels**, filter chips (★ My teams in gold, All sports, groups).
- Footer hint line with remote keys.

## 1 · Matchday timeline (`Main.dc.html`, #24)
- Horizontal time ruler (local hours) with a red **now** line + time tag.
- One row per league (label column 220px); games are blocks positioned by start/length (length from score feed or per-sport estimate: NFL ~3h15, soccer ~1h55, NBA ~2h30). Overlaps stack into lanes.
- Live blocks: red tint + border, "LIVE · clock", big score. Upcoming: outline + "6:00 PM · in 1 h 44 m". Finals: dimmed, "Final · score hidden".
- Focused block enlarges (white border) and drives a bottom panel: muted live preview (left), big scoreline + abbreviations + clock, linescore table, buttons **Watch / Watch with another game / Game stats**.
- Remote: ← → move through time (window scrolls), ↑ ↓ change league, OK watch, hold OK for more.

## 2 · Scoreboard (`Scoreboard.dc.html`, #23)
- Left (1000px): stadium-board rows grouped **LIVE** (red header) → **STARTING SOON** → **FINAL**.
  - Live row: league tag, two team lines (followed team gets gold star), stacked scores, clock.
  - Starting soon: league, matchup, "6:00 PM · in 1 h 44 m".
  - Final: league, matchup, eye-off icon + "Score hidden" (spoiler setting).
- Right: focused game — large muted preview with LIVE tag and "Preview · muted · OK for full screen", big scoreline, situation line (e.g. "Q3 · 8:42 · SEA ball · 2nd & 6 · 2 sources"), buttons **Watch / Watch two / Stats**.

## 3 · Channel guide (`Guide.dc.html`, #25)
- Columns: **Channel** (logo chip + name, 300px) | **Now** | **Next**.
- Now cell: LIVE tag + program/game + live score (when a channel's game is matched) + progress bar + "until 6:15 PM"; Next cell: title + start time.
- Channels with no listing say "No listing · OK to watch the channel".
- Group chips: UK / US / Favourites. Focused row = white border.
- Now/Next listings source is open: candidate is TheSportsDB TV listings (see #25 comment; prior art NuvioPlusIPTV `SportsTvListings.kt`).

## 4 · Results (`Results.dc.html`, #51 #52 #53 #54 #55)
Added 2026-11-17 after the user tested the first Results tab (live.8).
- Header "Results", sub-line "Finals from the last 7 days · days follow US Eastern time"; view pill gains **Results**.
- **Date rail:** Yesterday (active) · Sat 15 · Fri 14 … (7 ESPN/US-Eastern days).
- **Chip row:** gold **★ My sports** first, then the user's sports (Basketball, Football, Soccer, Tennis, F1); after a divider, **league chips for the chosen sport** (NBA / College) (#52); "Edit my sports" link on the right (#55).
- **List (1150px):** sections with league headers — **YOUR TEAMS** first (gold header), then NBA, WORLD CUP QUALIFIERS · UEFA (#53), TENNIS · ATP FINALS (individual-sport row: players + set scores, #55)…; long tails collapse into a dashed "+ 48 college basketball games" row.
- **Row:** league tag · two team lines (winner bold) · stacked scores · status ("Final"/"FT") and a green **Replay** pill when a replay exists (#51). Spoiler-hidden rows show an eye-off icon "Hidden · Rest to reveal".
- **Right panel (focused result):** league · date · Final, team logo circles with abbreviations, big score, a "Top" performers line, buttons **Watch replay** (only when available), **Game stats**, **Follow ‹Team A›** / **Follow ‹Team B›** (#54).
- Remote: ↑ ↓ results, ← → days and chips, OK game page, hold OK to follow.

## 5 · Game page (`GamePage.dc.html`, #18)
- Header panel: both teams (logo circle, name, record), big final score, "Final · date · venue", buttons **Watch replay** and **Follow…**.
- Section pill: **Game · Box Score · Standings · Highlights** (empty sections hidden).
- Game tab: linescore table (periods + T), **Top performers** 2×2 cards (team, player, stat line), **Team stats** comparison bars (FG%, 3P%, rebounds, assists, turnovers; the better side highlighted `#9AC2FF`, the other `#4B5A6E`).
- Spoilers: when on, score/stats sit behind a Reveal. Data from ESPN `summary` (cached per game).
- Remote: ← → sections, ↑ ↓ scroll, OK select, Back to Results.

## Related decisions
- Our own drawn game cards (team names, local time, league, logos, score chip) replace addon posters for games; channels keep addon art (#17 comment).
- In-player games panel: rows show score/clock, grouped Live now (by elapsed) → Starting soon → rest (#17 comments).
