# Sports & Live — "beyond cards" view designs (2026-11-16)

Approved by the user ("I love the designs"). Three alternative views of the same games, shown as tabs/modes inside Sports & Live. Mockups only — not yet built. Issues: **#23 Scoreboard**, **#24 Matchday timeline**, **#25 Channel guide**. Depend on live scores v1 (#17, plan `docs/superpowers/plans/2026-11-17-sports-live-scores-v1.md`).

- Source of truth for layout: the `.dc.html` files in this folder (1920×1080 TV frames; static HTML with inline styles — open in a browser). `canvas.json` is the canvas index.
- Online canvas (private to the user): https://claude.ai/artifact/83uJQ6VNpczeeMhEvQhL8p
- All data in the mockups is placeholder sample data.
- **Aligned 2026-11-17** with what is built (scores v1 S1–S5, Results v2 R1, release live.9) and the queued decisions (#56, #18, #54, #55 part 2). Where this file and the app disagree, the app's behavior and labels win; see "Built components" below and "Decisions to confirm" at the end.

## Shared visual language
- Dark broadcast look. Background `#0A0D12`; panels `#121821` / `#0E131A`; row lines `#1E2632`.
- Text `#F2F4F7`, secondary `#C7CED8`, muted `#9AA4B2`.
- Live red `#FF4D57` (live block bg `#2A1416`/`#3A171B`, live text `#FFB3B8`); followed-team gold `#FFC107`; Replay pill `#7EE0A1` on `#0B1F12` text. Live red is for live games only, never for focus.
- Focus = 3px white border + shadow; upcoming = 2px `#2A3442` outline; finals = dimmed `#151B24`. Finals follow the **Scores** setting (below): with the default **Show** they carry their score.
- Fonts: Space Grotesk (titles, scores, tabular numerals), Inter (body). TV-size type (20–64px). 64px side padding.
- Header on every view: title ("Matchday" / "Channels"), date line in **local time**, a pill switcher **Timeline | Scoreboard | Channels**, filter chips (**★ My teams** in gold, **All**, then sports — the app's own filter chips).
- Footer hint line with remote keys.
- Score text is always the built chip format (see "Built components"): live `Q3 8:42 · 17–13` (ESPN's clock text as given, then the score), final `FT 2–1` / `Final 24–17` / `Final/OT 101–99`, postponed `Postponed` (no score), hidden `Score hidden`. Scores are in the **card's team order** (the team the title names first comes first), never re-sorted to home/away.

## Spoilers, delay and keys (all views)
- **Scores** (Settings → Sports & Live → Scores): **Show** (default) · **Hide until focused** · **Off**.
  - Show: every surface shows live and final scores.
  - Hide until focused: every score (live and final) reads "Score hidden" until that game has been focused for **1 s**; it then stays revealed while the screen lives. A live hidden chip keeps its red dot. Results rows hide the ending too (the note reads "FT", never "OT"/"Pens"). No eye icon, no "Rest to reveal" text: the reveal is implicit.
  - Off: no scores anywhere and no score requests at all (Results rows read "Score hidden").
- **Score delay**: Off · 15 · 30 · 60 · 90 seconds. Holds back every change to score, clock, state, linescore and scorers; a game first seen while live shows no score until the delay has passed. Views must take scores from the same delayed feed, never fetch their own.
- **OK on a game** (queued #56): plays the best stream (zap path: reliability order, user filters, failover). Today only merged multi-source cards do that; a single-source card opens the Stream screen. A setting "When I open a game: Play best stream / Let me choose" and a "Choose stream…" menu entry keep manual choice.
- **Hold OK / Menu** = the card menu: **Watch with another game** (Live Now and Channels only), **Follow ‹team›** / **Unfollow ‹team›** per side, **Details**, and with #56 **Choose stream…**. Every "watch two" in these designs is this dual screen.
- Resting on a card for **2 s** starts the muted hover preview (setting "Preview games on hover"; off under Data saver).

## 1 · Matchday timeline (`Main.dc.html`, #24)
- Horizontal time ruler (local hours) with a red **now** line + time tag.
- One row per league (label column 220px); games are blocks positioned by start/length. **Length is an estimate only**: the ESPN scoreboard we read has no duration or end time, so use a per-sport estimate (NFL ~3h15, soccer ~1h55, NBA ~2h30), and once a game is final its block ends at the last time it was seen live. Overlaps stack into lanes.
- Live blocks: red tint + border, "LIVE · Q3 8:42", big score. Upcoming: outline + "6:00 PM · in 1 h 44 m" (the countdown is new; the app shows the local kickoff only). Finals: dimmed, the final chip text ("FT 2–1"), or "Score hidden" under Hide until focused.
- Focused block enlarges (white border) and drives a bottom panel: muted live preview (left), the details-pane score block (scoreline with abbreviations + clock, linescore with "–" for unplayed periods), buttons **Watch / Watch with another game / Game stats** (Game stats = the Game page, #18).
- Tennis row: needs individual-sport scores (#55 part 2); today only team sports (basketball, American football, hockey, baseball, soccer) have scores.
- Remote: ← → move through time (window scrolls), ↑ ↓ change league, OK plays (best stream with #56), hold OK for the card menu.

## 2 · Scoreboard (`Scoreboard.dc.html`, #23)
- Left (1000px): stadium-board rows grouped **LIVE** (red header) → **STARTING SOON** → **FINAL** — the same rule as the in-player panel's groups (below).
  - Live row: league tag, two team lines (followed team gets gold star), stacked scores, clock.
  - Starting soon: league, matchup, "6:00 PM · in 1 h 44 m".
  - Final: league, matchup, the final chip text ("FT 2–1"; "Score hidden" under Hide until focused).
- Right: focused game — large muted preview with LIVE tag and "Preview · muted · OK for full screen", big scoreline, a status line (clock · source count; soccer adds the scorers line), buttons **Watch / Watch with another game / Game stats**.
- No "situation" line (possession, down and distance): the scoreboard fields we read do not carry it. Adding ESPN's `situation` is a small DTO change if wanted.

## 3 · Channel guide (`Guide.dc.html`, #25)
- Columns: **Channel** (logo chip + name, 300px) | **Now** | **Next**.
- Now cell: LIVE tag + program/game + live score (when a channel's game is matched) + progress bar + "until 6:15 PM"; Next cell: title + start time.
- A channel gets a score today only when its name carries the game in brackets ("Channel 1 [Team A vs Team B]"); every other Now/Next needs listings.
- Channels with no listing say "No listing · OK to watch the channel".
- Group chips: UK / US / Favourites (Favourites needs channel favorites, #22). Focused row = white border.
- Now/Next listings source is open: candidate is TheSportsDB TV listings (see #25 comment; prior art NuvioPlusIPTV `SportsTvListings.kt`).
- The tennis Now cell's set score needs #55 part 2.

## 4 · Results (`Results.dc.html`, #51 #52 #53 #54 #55)
Built in R1 (live.9) except where marked. The mockup shows **Hide until focused** with two rows already revealed.
- Today it is the **Results** tab of the section's tab row (Live Now · Upcoming · Channels · Replays · Results). The design's header line and pill are the future view switcher.
- **Date rail:** Yesterday · Sat 15 · Fri 14 … (7 ESPN/US-Eastern days; "Yesterday" only when that day is also the device's yesterday). Chips select on focus.
- **Sport row:** **★ My sports** first, then the user's sports **that have results that day**, labelled Basketball · American Football · Hockey · Baseball · Soccer; **Edit my sports** at the end opens Settings → Sports & Live → My sports. Tennis / F1 / MMA / golf arrive with #55 part 2.
- **League row (#52):** only once a sport is picked (never under ★ My sports): **All**, then that day's competitions by ESPN's short name (NBA, NCAAM; soccer: "UEFA Nations League", "Premier League"…).
- **List (880dp in the app; 1150px in the frame):** sections under the competition's name — **YOUR TEAMS** first (gold header), then by league; long tails collapse into a dashed "+ 48 more NCAAM games" row (OK lists them, focus moves to the first). Soccer comes from one `soccer/all` fetch, sectioned by ESPN league id (men's, women's and youth never merge).
- **Row:** gold star (followed) + league tag · two team lines with 24dp logos and ESPN's full names, **away first** (winner bold) · stacked scores · note **FT / OT / SO / AET / Pens** · green **Replay** pill only for a strict replay match (#51; it stays visible while a score is hidden).
- **Right panel (proposed, not built):** today the list has no pane and ← → do nothing on a row. Kept as the target for #18/#54: league · date · Final, logo circles, big score, a "Top" performers line, **Watch replay** (only when available), **Game stats**, **Follow ‹Team A›** / **Follow ‹Team B›**.
- Remote today: ↑ ↓ results and chip rows, ← → days / sports / leagues, **OK plays the replay** (a series replay opens its details) and does nothing without one. Queued: OK opens the Game page (#18), hold OK = Follow menu (#54).

## 5 · Game page (`GamePage.dc.html`, #18)
Queued (R3), not built.
- Header panel: both teams (logo circle, name, record), big final score, "Final · date · venue", buttons **Watch replay** (only with a strict replay match) and **Follow…** (Follow ‹team›; saves ESPN team id, abbreviation and league, #54).
- Section pill: **Game · Box Score · Standings · Highlights** (empty sections hidden).
- Game tab: linescore table (periods + T, the details-pane rules: OT / 2OT / SO / ET1, "–" for unplayed), **Top performers** 2×2 cards (team, player, stat line), **Team stats** comparison bars (FG%, 3P%, rebounds, assists, turnovers; the better side highlighted `#9AC2FF`, the other `#4B5A6E`).
- Spoilers follow the Scores setting: Show = all visible; Hide until focused = score, linescore and stats behind a **Reveal** (a game already revealed in Results opens revealed); Off = no score, linescore or stats until Reveal.
- Data from ESPN `summary` (cached per game; one request per opened game). Everything here except the linescore needs it: records, venue, leaders, team stats, box score, highlights.
- Remote: ← → sections, ↑ ↓ scroll, OK select, Back to Results (focus returns to the row).

## Built components (reference)
Reuse these in the views instead of drawing new ones.

**Drawn game card** (`GameArt.kt`, `SportsLiveGameArt.kt`). Nuvio draws every game card in place of the addon's poster; channels and replays keep addon art. Both teams in the title's order, logos, ESPN names and abbreviations from the matched score event (an unmatched game keeps the card's names and start, no logos), the league, and the kickoff in **local time** (time today, "Tomorrow 6:00 PM", else weekday/date; ESPN's time beats the addon's unless ESPN says TBD).
- Compact (list thumbnail): two lines of abbreviation + 18dp logo (logos only when both sides have one, else larger names), footer "6:00 PM · NBA".
- Large (details pane): league in caps, 72dp logos (or large names), "vs", the score chip, kickoff.
- Colors are this README's tokens (`#0A0D12`/`#121821` gradient, live block `#2A1416`, final block `#151B24`).

**Score chip** (`ScoreChips.kt`). Beside the card's state pill and on the large art; Home's Live now card prefixes "● ". Shown only for a strict match whose sides sit on the event one way round (a wrong score is worse than none). Live: red dot, `#FFB3B8` text, "Q3 8:42 · 17–13". Final: "FT 3–1", "Final 24–17". Postponed/cancelled/suspended: ESPN's status, muted, no score. Upcoming and unknown: no chip.

**Details pane score block** (`ScoreDetails.kt`, `SportsLiveScoreDetails.kt`). Scoreline "LV 17–13 SEA" (abbreviations, card order) with the clock beside it (red while live, "Final" else); a linescore table (period heads 1…n, OT/2OT, ET1/ET2 for soccer, SO for a hockey shootout, bold **T**; unplayed periods "–"; SO and "–" land with the S5 review fix); for soccer a scorers line per side, "ARS: Saka 23', 67' (pen)" ("(OG)" for own goals). All from the scoreboard snapshot the card already has; no extra request. Same spoiler rule as the chip.

**In-player games panel** (`SportsLiveZapPanel.kt`, `SportsLiveZapOverlay.kt`). Groups **Live now** (furthest-in game first) → **Starting soon** (soonest first, row leads with the local kickoff) → **More games** (channels, later or finished games, postponed). Followed teams' games first inside each group; rows carry the score chip. Starting soon means within 3 h, and under Hide until focused a finished game keeps its place by start time so its position never spoils it (both with the S5 review fix). This is the grouping rule for the Scoreboard view.

**Spoiler and delay settings** (`ScoreSettings.kt`, `ScoreDelay.kt`, `ResultSpoilerReveal`). Scores Show / Hide until focused / Off with the 1 s focus reveal; Score delay Off/15/30/60/90 s applied once in `LiveScoresRepository`, so every surface shows the same delayed state.

**My sports** (`MySports.kt`, Settings → Sports & Live → My sports). Sports Basketball, American Football, Hockey, Baseball, Soccer, each with leagues (NBA, College basketball, NFL, College football, NHL, MLB; soccer: All competitions or Internationals, Champions League, Europa League, Conference League, Premier League, LaLiga, Bundesliga, Serie A, Ligue 1, MLS). Nothing picked = every team sport with its default leagues. A followed team adds its sport. Drives which feeds Results fetches; the views can use it as their default filter.

## Related decisions
- Our own drawn game cards (team names, local time, league, logos, score chip) replace addon posters for games; channels keep addon art (#17 comment).
- In-player games panel: rows show score/clock, grouped Live now (by elapsed) → Starting soon → More games (#17 comments).
- OK on a game plays the best stream; manual choice in the menu and a setting (#56, queued).
- Results OK moves from "play the replay" to "open the Game page", which carries Watch replay (#18, queued).

## Decisions to confirm with the user
1. **Finals in the views show their score by default** (Scores = Show), not "score hidden by default" as first drawn. Keep, or make Hide until focused the default for finals only?
2. **Results right panel vs Game page.** The app has no panel; #18 makes OK open a Game page that already has Watch replay and Follow. Drop the panel (simpler, recommended) or build both?
3. **How the views are reached.** Today the section is tabs (Live Now · Upcoming · Channels · Replays · Results). Timeline/Scoreboard as a layout switch inside Live Now + Upcoming, or replace those tabs?
4. **Scoreboard situation line** ("SEA ball · 2nd & 6") was dropped: worth reading ESPN's `situation` for it?
5. **Game page under Scores = Off**: keep the score behind Reveal (as drawn) or show nothing at all?

## We could also
- **★ Recommended — Scoreboard first, from the in-player panel.** The panel's Live now / Starting soon / More games grouping, chips and spoiler rule are exactly the Scoreboard's left column; building #23 as a full-screen version of that panel (plus the details-pane score block on the right) reuses almost everything and gives one rule for "what's on" everywhere.
- **Reveal on demand**: under Hide until focused, a long-press "Reveal all scores" on a view, and a per-league "never hide" for leagues the user doesn't care about spoiling.
- **Delay-aware live blocks**: show a small "delayed 30 s" tag on live chips when Score delay is on, so a score that lags the feed is never mistaken for a stale one.
- **Timeline from My sports + ESPN**: draw upcoming blocks for followed teams straight from ESPN even before an addon lists the game, and light them up with streams once one appears (#54 idea).
- **Results → Timeline handoff**: from a Results day, "Show this day on the timeline" with finals as dimmed blocks and Replay blocks playable in place.
