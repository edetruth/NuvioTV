# Sports & Live — view designs (2026-11-16, as-built refresh 2026-11-17)

Started as three "beyond cards" views (approved: "I love the designs"). This folder now covers the whole Sports & Live section: the three layouts of Live Now / Upcoming, Results, the Game page, and the planned screens (Team page, Player card, alerts and reminders, replay jumps), plus a component sheet.

- Source of truth for layout: the `.dc.html` files in this folder (1920×1080 TV frames; static HTML with inline styles; open in a browser). `canvas.json` is the canvas index.
- Online canvas (private to the user): https://claude.ai/artifact/83uJQ6VNpczeeMhEvQhL8p
- All data in the mockups is placeholder sample data ("Addon A", "Sports Channel 1").
- **As-built refresh 2026-11-17** against what landed since c96437055: Scoreboard SB1/SB2 (#23), Timeline TL1 (#24), Just ended (#60), Game page G1 (#18, #54), individual sports R2 (#55 part 2), dual-screen stream hand-off (#65), live scores for every game on screen and fast logos (#66). Where this file and the app disagree, the app's behavior and labels win.
- **Status legend:** **Built** = in the app on `feature/sports-live-section`. **In batch** = pulled into the current batch, not built yet (#67, #68, #61, #42 + #44, #43). **Deferred** = not in this batch (#25, #62, #63, #64).

| Board | What | Status |
|---|---|---|
| `Main.dc.html` | Timeline layout | **Built** (TL1, #24) |
| `Scoreboard.dc.html` | Scoreboard layout | **Built** (SB1 + SB2, #23) |
| `LiveNow.dc.html` | Cards layout, Just ended strip, Most exciting sort | Cards + strip **Built** (#60); sort **In batch** (#43) |
| `Guide.dc.html` | Channel guide | **Deferred** (#25), board unchanged |
| `Results.dc.html` | Results tab | **Built** (R1, R2); Today / Earlier ▸ / "N games live now →" **In batch** (#67) |
| `GamePage.dc.html` | Game page | **Built** (G1); playable highlights **In batch** (#61) |
| `PlayerPanel.dc.html` | In-player Live now panel | logos + scores **Built** (#66); chips (#68, #79); Scoreboard rows, preview corner, dimmed video **Built** (#83) |
| `Prompts.dc.html` | Alerts and reminders | **In batch** (#42 + #44) |
| `TeamPage.dc.html` | Team page | **Deferred** (#62, queued after the batch) |
| `PlayerCard.dc.html` | Player card | **Deferred** (#63, after the Team page) |
| `ReplayJumps.dc.html` | Quarter jumps in replays | **Deferred** experiment (#64, last) |
| `Components.dc.html` | Component sheet | reference; every new board links it |

## V2 (approved 2026-11-18, building)

From the 1080p design review of 2026-11-18 (§4 one view per tab, §5 top 10). Nothing here is built; the boards above stay the record of what is.

**Decision proposed: one view per tab.** Live Now becomes **Scoreboard + Stage**; Upcoming becomes **Agenda + Stage**. The Cards and Timeline layouts, the View button and menu, Sort, per-row thumbnails, the LIVE pill on channels, the repeated title/meta lines and the "N sources didn't respond" text line go away. The Timeline code is kept for a future full-screen Guide (#25).

- **Frame:** collapsed rail 0–160; safe area left 256, right 1824, top 54, bottom 1026.
- **Header:** one 80 px line (y 54–134): 34 px text tabs, selected = white + 4 px underline; refresh at the right; source failures as one amber "⚠ 3" icon. Upcoming (and Results) add one sports filter as a single focus stop; Results puts the date in the title ("Results · Yesterday ‹ ›").
- **List:** x 256–1056 (800 px), y 150–1026, 80 px edge fades. Fixed columns: league 96 · names 440 · scores 96 · status 150. Rows: live 112 px (stacked 40 px scores, red dot + clock, period, 4 px progress bar, gold ★ and gold left bar for followed teams, blank logo slot when logos are missing), starting soon 96, channel 72, just ended 80 (60 % until focused, Replay pill).
- **Stage:** x 1104–1824, fixed: 720×405 media (preview or drawn matchup art; the mini player replaces it), 64 px scoreline, status line, linescore, one button row: Watch / Remind me (primary) + More….
- **Focus:** focused = 4 px white ring + 1.03 lift; selected = underline, tinted fill or a white left bar; never a white fill for selected, never red for focus.
- **Type scale:** tags 22, metadata 26, names 30, row scores 40, stage score 64.
- **Upcoming keys:** ↑ ↓ rows (headers skipped), ← → time slots, CH +/− day.

| Board | What |
|---|---|
| [`V2-LiveNow.dc.html`](V2-LiveNow.dc.html) | Live Now · Scoreboard + Stage (at these row sizes Channels 2–3 and Just ended sit below the fold; all row types are on the sheet) |
| [`V2-Upcoming.dc.html`](V2-Upcoming.dc.html) | Upcoming · Agenda + Stage |
| [`V2-Header-and-Focus.dc.html`](V2-Header-and-Focus.dc.html) | Header per tab, focus language, type scale, row types, More… menu |
| [`V2-Before-After.dc.html`](V2-Before-After.dc.html) | Today's Live Now (schematic) vs V2, the five biggest differences |

## Shared visual language
- Dark broadcast look. Background `#0A0D12`; panels `#121821` / `#0E131A`; row lines `#1E2632`.
- Text `#F2F4F7`, secondary `#C7CED8`, muted `#9AA4B2`.
- Live red `#FF4D57` (live block bg `#2A1416`/`#3A171B`, live text `#FFB3B8`); followed-team gold `#FFC107`; Replay pill `#7EE0A1` with `#0A0D12` text. Live red is for live games only, never for focus.
- New in this pass: **why tags** and **reminder** chips are blue (`#CFE1FF` on `#13233A`, border `#2B4A75`), so they never read as live (red) or followed (gold). "≈ Approximate" is amber (`#FFD58A` on `#2A2410`).
- Focus = 3px white border/ring + shadow; upcoming = 2px `#2A3442` outline; finals = dimmed `#151B24`.
- Fonts: Space Grotesk (titles, scores, tabular numerals), Inter (body). TV-size type (20–64px). 64px side padding.
- **Header as built:** "Sports & Live", the tab row **Live Now · Upcoming · Channels · Replays · Results**, then **Refresh**, then on Live Now and Upcoming only the **View** button ("View · Scoreboard · Exciting", #79): OK opens the View menu, **Layout** (Cards · Scoreboard · Timeline, remembered per tab per profile) and, on Live Now outside the Timeline, **Sort** (Start time · Most exciting); a pick applies and closes the menu. Then the filter chips with counts (**★ My teams** in gold, **All 60**, then the sports present; empty ones hidden; focus highlights, OK selects), with a clear gap after View so its focus ring never touches the first chip. `Components.dc.html` draws the View button, menu and chips.
- Footer: remote keys, a **Components** link on new boards, and a **Scores:** legend naming the three spoiler states for that board (the drawn one is filled).
- Score text is always the built chip format: live `Q3 8:42 · 17–13`, final `FT 2–1` / `Final 24–17` / `Final/OT 101–99`, postponed `Postponed`, hidden `Score hidden` (a live hidden chip keeps its red dot). Scores are in the **card's team order**; the Game page keeps the order of the card it was opened from (QA N10), and away–home when opened from Results.

## Spoilers, delay and keys (all views)
- **Scores** (Settings → Sports & Live → Scores): **Show** (default) · **Hide until focused** · **Off**.
  - Show: every surface shows live and final scores.
  - Hide until focused: every score reads "Score hidden" until that game has been focused for **1 s**; it then stays revealed while the screen lives. Results rows hide the ending too (note "FT", never "OT"/"Pens"); individual-sport rows read "Result hidden". No eye icon, no "Rest to reveal" text.
  - Off: no scores anywhere and no score requests at all.
- **Score delay**: Off · 15 · 30 · 60 · 90 s, applied once in `LiveScoresRepository`; it holds back score, clock, state, linescore, scorers and the situation line. New surfaces (toasts, sort, Team page live rows) must read the same delayed feed.
- **OK on a game** plays the best stream (#56, built: setting "When I open a game: Play best stream / Let me choose", **Choose stream…** in the menu). OK on an ESPN final (Just ended, Scoreboard FINAL, Results) opens its Game page.
- **Hold OK / Menu** = the card menu: **Watch with another game** (Live Now), **Follow ‹team›** / **Unfollow ‹team›**, **Game stats** (strict match only), **Details**, **Choose stream…**; with #44 **Remind me** / **Cancel reminder** on upcoming games.
- Resting on a card or row for **2 s** starts the muted hover preview (setting "Preview games on hover"; off under Data saver).

## 1 · Timeline (`Main.dc.html`, #24) — **Built** (TL1)
- Third layout of the View menu on Live Now and Upcoming. Live Now opens on now − 1 h … now + 5 h; Upcoming on now … now + 12 h; ← → reach every game and the window follows focus.
- Ruler: local hours (device 12/24 h; a midnight tick shows the day), faint hour lines, red now line with a time tag.
- Rows: one per league (ESPN league name, else the card's sport), 220px label column, ★ when a followed team plays. Order: followed rows, rows with a live game, then earliest start.
- Blocks: placed by start; length = the `SportsLiveGameLength` estimate (live blocks run to max(now, estimate); finals end at ESPN's first-seen final; minimum 15 min). Overlaps stack into lanes; text slides to stay on screen.
  - Live: red tint + border, "LIVE · Q3 8:42", big score ("Score hidden" when hidden; the block is never hidden).
  - Upcoming: outline, "6:00 PM · in 1 h 44 m" (countdown only for a timed start).
  - Over: dimmed, "FT 2–1" / "Final" / "Score hidden" / "Probably ended".
  - Tennis and other unmatched games: a LIVE block with no score (individual sports have results, not live scores).
- **"No time" row** (last, reached with ↓): channels and date-only games as fixed-width cards, still playable.
- **Bottom panel** (168 dp): muted preview (or the drawn card), LIVE tag, big scoreline + clock, the linescore and scorers, the **situation line** ("LV ball · 2nd & 6 · SEA 34"), and vertical buttons **Watch / Watch with another game / Game stats**.
- Remote: ← → previous/next game in the row; ↑ ↓ the adjacent row's game nearest in time; ↑ from the first row → filters/tabs; ↓ from the last row → Watch. OK = the card's OK; hold OK = card menu.

## 2 · Scoreboard (`Scoreboard.dc.html`, #23) — **Built** (SB1 + SB2)
- Left (1000px): rows under **LIVE** (red) → **STARTING SOON** (within 3 h) → **MORE GAMES** (channels, later games, date-only, postponed) → **FINAL** (the Just ended set, dimmed until focused). Same grouping code as the in-player panel; followed first in each group; under Hide until focused finals keep their start-time place.
- Row: league tag · two team lines (logo, GameArt names, card order, gold ★ on the followed side) · stacked scores · right column: red dot + clock, or "6:00 PM · in 1 h 44 m", "FT"/"Final", "Probably ended" (no score), "Score hidden", "Postponed".
- Right: preview ("Preview · muted · OK for full screen") or the large drawn card, big scoreline, clock line (clock · sport · addon · sources), soccer scorers, then the **situation line** (SB1): football possession, down & distance, red zone; baseball "Bot 7 · 2 out · 1st & 3rd"; nothing for other sports (hockey power play and soccer: ESPN sends nothing).
- Buttons **Watch** · **Watch with another game** (Live now, not FINAL) · **Game stats** (strict match). If a focused button disappears, focus moves to Watch.
- Remote: ↑ ↓ rows, → to Watch, ← back. OK on an ESPN-final row opens its Game page.

## 3 · Live Now cards (`LiveNow.dc.html`) — cards + Just ended **Built**; sort **Built** (#43)
- **Shape (decided 2026-11-18, QA pass 3):** a single-column **list** on the left (about 45%) with a **details pane** on the right (large drawn card or the muted preview, scoreline, situation line, then Watch · Watch with another game · Game stats · Choose stream · Details), not a grid. With the mini player playing, the pane ends above it, so no button sits under the video.
- The Cards layout: drawn game cards (logos with abbreviations when both teams have a logo, else short names alone; footer "Q4 · NFL" / "6:00 PM · NBA"), title, state pill + score chip. Channels keep addon art. Unmatched games (tennis here) keep the card's names, no logos, no chip.
- **Just ended strip (#60, built):** at the end of the list, dimmed until focused, left out of the tab count. ESPN finals: chip ("FT 2–1", the "Final" pill dropped when the chip already says it), Replay tag on a strict replay match, OK → Game page; they leave 30 min after the final. Estimates: pill "Probably ended", no score, OK plays; they stay up to 6 h.
- **Sort (#43, built):** in the View menu, **Start time** (default) · **Most exciting**. Most exciting ranks live games by closeness × time left, overtime, recent lead changes and late goals, from the delayed feed. Each ranked card carries one short **why tag** top-right: "Close · 2 min left", "OT", "Late equaliser", "Lead changes · 9". Upcoming, channels and unmatched games follow in start order.
- Spoilers: Show = as drawn. Hide until focused = chips "Score hidden", **no why tags** and Most exciting falls back to start order (decision to confirm). Off = no chips, the sort is hidden.

## 4 · Channel guide (`Guide.dc.html`, #25) — **Deferred**
Deferred by the user (2026-11-18); not in the next batch. The board is unchanged. Open question: the Now/Next listings source (candidate TheSportsDB TV listings, see #25).

## 5 · Results (`Results.dc.html`, #51–#55, #67) — **Built**; Today/Earlier **In batch**
The board shows **Hide until focused** with the Celtics row not yet focused.
- **No right panel** (decided): OK on a row opens the Game page; hold OK / Menu = **Game stats** + **Follow / Following** per side.
- **Date rail (#67, in batch):** **Today** (default once today's first game is final) · **Yesterday** · **Earlier ▸** (opens the 5 older US-Eastern days). Before today's first final: opens on Yesterday and Today reads "No finals yet". Built today: Yesterday · Sat 15 · Fri 14 … (7 days).
- **"N games live now →"** (#67, built): a line above Today's list that jumps to Live Now; live games are not listed in Results. It counts Live Now's own live games (ended-games rules and delay applied), so it never shows while Live Now has none.
- **Sport row:** **★ My sports**, then the sports with results that day: Basketball · American Football · Hockey · Baseball · Soccer · Tennis · MMA · Motorsport · Golf; **Edit my sports** at the end. League row (NBA, NCAAM, …) only once a sport is picked.
- **List** (880 dp): **YOUR TEAMS** first (gold), then sections by competition; long tails collapse into "+ 48 more NCAAM games".
- **Team row (about 70 px at 1080p, QA pass 3):** star · two team lines (logo, ESPN full name, away first, winner bold) · stacked scores · note **FT / OT / SO / AET / Pens** · **Replay** pill (strict match; stays visible while a score is hidden). No league tag in the row: the section header names it. About nine rows fit.
- **Individual-sport rows (R2, built):** tennis: two player lines (winner bold) + set columns, the set loser's tiebreak points small, "Ret."/"W/O"; UFC: "K. Duarte vs J. Hale" then "K. Duarte · KO/TKO R2 3:14" (decisions without round); F1: "1. Norris · 2. Verstappen · 3. Hadjar" (podium only); golf: winner + to par and "+ 4 more", OK toggles the top 5 (ties "T4"). Singles only, no replays, no Follow menu, OK does nothing on tennis/UFC/F1. Hidden: "Result hidden".

## 6 · Game page (`GamePage.dc.html`, #18, #54, #61) — **Built**; playable highlights **In batch**
- Header: logo circles (only when both teams have a logo, #130), names, records, big score in the opening card's order (away–home from Results), "Final · date · venue" (or red live clock / kickoff). Buttons: **Reveal score and stats** (hidden modes only; the page reads "Score and stats are hidden"), **Watch replay** (strict match only), **Follow ‹team›** / **★ Following ‹team›** per side (OK unfollows).
- Sections **Game · Box Score · Standings · Highlights**, empty ones hidden. Game = linescore (+ soccer scorers), Top performers 2×2, Team stats bars (`#9AC2FF` better, `#4B5A6E` other; lower-is-better for turnovers, fouls, PIM). Box score compact columns; Standings with both teams highlighted.
- **Highlights (#61, in batch):** playable cards (thumbnail, length, "ESPN highlight" label, title); OK plays in the app player; best working link; failed, expired or geo-blocked clips are dropped; no playable clip → no tab; **Watch all highlights** plays them in a row. Built today: titles only, not playable ("Highlight titles from ESPN, for information").
- Spoilers: Show = all visible; Hide until focused = score, records, ending, sections and highlights behind Reveal (a row revealed in Results opens revealed); Off = no request until Reveal.
- Data: ESPN `summary`, cached per game (finals on disk, max 60); 30 s refresh only while visible and live. Remote: ← → sections, ↑ ↓ scroll, Back returns (focus back on the row).
- With #62/#63: OK on a team logo opens the Team page; OK on a top performer opens the Player card.

## 7 · In-player Live now panel (`PlayerPanel.dc.html`, #66, #68, #83)
- **Docks on the left** (decided 2026-11-18), about 680 px wide; the video stays visible on the right.
- **Built:** groups **Live now** (furthest-in first) → **Starting soon** (row leads with the local kickoff) → **More games** → **Just ended**; followed teams first in each group; score chips; two **logos** per row from the saved team → logo map (#66), only when both teams have one and both loaded (#130; no letter circles, no empty slots); scores fetched as soon as the panel opens.
- **In batch (#68):** the panel opens on **All**, whatever filter the game was started from; a chip row **★ My teams · All · ‹sports present›** at the top (one focus stop, ← → pick, ↓ into the games); the chip also orders channel up/down for the rest of the playback session.
- The playing game is marked "NOW PLAYING". Keys: ↑ ↓ games, OK switch, hold OK watch both, Back closes.
- **Built (#83):** the panel is 680 dp wide and its rows are the **Scoreboard row** (`SportsLiveScoreboardRow`, model from `scoreboardRowOf`): league tag, two team lines with logos and short names, stacked scores, the clock or kickoff (and countdown) on the right, and the **situation line** under the teams only while the live score shows. Chips with counts, groups, NOW PLAYING, starred followed sides, frozen order under focus and the spoiler rules are unchanged.
- **Preview corner (#83):** top right of the panel, 240 × 135. After 1 s on a row that is not the game playing, its muted preview plays in the hover-preview player, from a cached resolve or the C1 budgeted one; sources cooling down are skipped. It stops when focus moves, the panel closes, a game is picked, the app or screensaver goes to the background, or 20 s after the row took focus. It asks for the lowest bitrate at most 480p and never touches the main stream's audio. Data saver, the hover-preview setting off, a low-RAM device (`isLowRamDevice`) or a decoder limit seen (dual screen, or a preview that got no decoder) keep the corner on the game's logos art.
- **Dimmed video (#83):** no opaque panel; a left-to-right scrim (85 % → 0 % black) keeps the game playing visible on the right. Rows keep their solid backgrounds for legibility.

## 8 · Alerts and reminders (`Prompts.dc.html`, #42 + #44) — **In batch**
Overlays on video, drawn 1:1 on crops of the screen. One at a time; Back dismisses; never in the first 10 s of a stream.
- **Kickoff "Watch now?"** (#44): LIVE tag, "KICKING OFF NOW", ★ when followed, both logos and the matchup, "NFL · 1:25 PM · you're watching Bucks @ Bulls", buttons **Watch** (focused) and **Later**; "Hold OK: watch both · closes in 15 s". In the background: a system notification with the same two actions.
- **Full-time toast** (#42): Show → "FULL TIME", "LV 27–24 SEA", "Final/OT · OK for the Game page"; auto-hides after 8 s. Hide until focused → "GAME OVER", names only, "Score hidden · OK for the Game page (Reveal there)", no winner bold, no "OT". Off → no toast.
- **Score-change toast** (#42 comment): followed teams only, while watching something else; "★ Raiders score", "LV 24–21 SEA", live clock; OK switch, hold OK both. Scores = Show only.
- **Remind me** (#44): hold OK on an upcoming game → **Remind me** (also a button in the Cards pane and the Timeline's bottom panel, and the action line of a Team page's next-game tile, with the bell state); toast "Reminder set · Pacers @ 76ers · today 6:00 PM · We'll ask 'Watch now?' at kickoff", or "Reminder removed · Pacers @ 76ers". It is the app's dark toast card with the bell, bottom left, never an Android system toast. The card then shows a blue bell chip with the time, and the menu offers **Cancel reminder**. Reminders are per profile.
- Uses the existing score pollers for followed teams' leagues only; no new requests.

## 9 · Team page (`TeamPage.dc.html`, #62) — **Deferred**
- Header: logo, league + conference, team name, **record**, standings position ("3rd in the East"), streak, home record; **★ Following** / **Follow**.
- **Last 5:** date, opponent with logo, W/L + score, **Replay** pill (strict match); OK opens that Game page. Small standings snippet (the team ± one place).
- **Next games:** live game with LIVE pill + **Watch** (best stream, once an addon lists it); later games with **Remind me** / "Reminder set".
- **Leaders:** points, rebounds, assists (sport-specific); OK opens the Player card.
- Data: ESPN team, schedule and standings, fetched on open and cached. Pro leagues first.
- Spoilers: Show = as drawn; Hide until focused = each last-5 row's W/L and score reveal on focus; Off = no W/L, scores or record.

## 10 · Player card (`PlayerCard.dc.html`, #63) — **Deferred**
- One screen: headshot circle (only when ESPN has a photo; else none, the name moves left, #130), name, position and number, team (★ when followed), bio line; season averages as six tiles (PTS, REB, AST, FG%, 3P%, MIN; sport-specific sets listed on the board); **Last 5 games** table (date, opponent, result, MIN/PTS/REB/AST); **Next game** strip with Watch.
- Spoilers: last-5 rows follow the Scores setting (result and stat line together); Off shows season averages only.

## 11 · Replay quarter jumps (`ReplayJumps.dc.html`, #64) — **Deferred** experiment
- Opt-in setting. First replay of a game: a card "Press OK when the ball goes up" with **Mark tip-off** and **Skip 10 min**.
- After the mark: a **Jump to** bar **Q1 · Q2 · Q3 · Q4 · OT**, tagged **≈ Approximate**, with **−1 min / +1 min** nudges (they shift every jump) and **Re-mark tip-off**; the seek bar shows period ticks. The mark and nudges are saved per replay.
- Offsets come from ESPN play-by-play wall-clock period starts. The bar never shows a score.

## Built components (reference) — see `Components.dc.html`
Reuse these instead of drawing new ones.
- **View button and menu** (`SportsLiveViewMenu.kt`, #79), replacing the old segmented layout switch and Sort control; **filter chips with counts**.
- **Drawn game card** (`GameArt.kt`, `SportsLiveGameArt.kt`): both teams in title order, logos (only when both teams have one, #130: never letter circles; without logos the names are stacked with the league), ESPN names and abbreviations, league, kickoff in local time. States on the sheet: upcoming, upcoming with reminder (new), live, live hidden, final (+ Replay), just ended (dimmed), probably ended.
- **Score chip** (`ScoreChips.kt`): strict match only. Live red dot + `#FFB3B8`; finals "FT 3–1" / "Final 24–17"; postponed muted; Home prefixes "● ".
- **Details-pane score block** (`ScoreDetails.kt`, `SportsLiveScoreDetails.kt`): scoreline in abbreviations, linescore (OT/2OT, ET1/ET2, SO, "–" unplayed), soccer scorers.
- **Situation line** (`GameSituations.kt`, SB1): shown only when the chip is Live (so never when hidden, Off, upcoming or final).
- **Scoreboard row / Timeline block** (`SportsLiveScoreboardView.kt`, `SportsLiveTimelineView.kt`) and the shared **board buttons** (`SportsLiveBoardButtons`).
- **In-player panel row** (`SportsLiveZapPanel.kt`, `SportsLiveZapOverlay.kt`): the Scoreboard row (#83, `panelBoards` → `SportsLiveScoreboardRow`), with NOW PLAYING and the situation line; groups as above. Preview corner: `SportsLivePanelPreview.kt`.
- **Ended games** (`SportsLiveEndedGames.kt`, `SportsLiveGameLength.kt`): ESPN final + 10 min grace → strip; estimate → "Probably ended".
- **Spoiler and delay** (`ScoreSettings.kt`, `ScoreDelay.kt`, `ResultSpoilerReveal`), **My sports** (`MySports.kt`, now with Tennis, MMA, Motorsport, Golf), **Follow** (`TeamFollows`, ESPN id + abbreviation + league).
- **New (designed here):** why tag, reminder chip and bell badge, "≈ Approximate" tag, prompt toasts (score change, full time, reminder set, kickoff).

## Decisions (confirmed by the user 2026-11-17)
1. **Finals keep their score by default** (Scores = Show). No finals-only hiding.
2. **Results drops the right panel; OK on a row opens the Game page** (#18). Built.
3. **Timeline and Scoreboard are layouts** (Cards · Scoreboard · Timeline) inside Live Now and Upcoming, picked in the View menu (#79). Built. Channels guide (#25) stays its own tab, now deferred.
4. **Situation line: yes.** Built (SB1) for football and baseball; ESPN sends none for soccer or hockey power plays.
5. Game page under Scores = Off: score behind **Reveal**, no request until then. Built that way.

## Decisions (approved by the user 2026-11-18 — the new layouts and these picks)
1. Most exciting under Hide until focused: keep the order, hide the "why" tags; followed teams stay pinned first.
2. Reminders: automatic for followed teams; "Later" asks again once after 5 min.
3. Full-time toast under Hide until focused: names only ("Celtics vs Knicks · Full time").
4. Score-change toasts: followed teams only, max one per game per 2 min.
5. Team page under Hide until focused: record + standings shown; last-5 results behind Reveal.
6. Team page entry: Game page + hold-OK menus on cards and Results rows.
7. Replay jumps: always list OT; under Scores Off fetch play-by-play only after Mark tip-off.
8. Player card: no "Follow player" yet.
9. Watch all highlights: auto-play next clip; Back stops.

## Decisions (user, 2026-11-18, after QA pass 3)
1. **Live Now stays a list with a details pane** (not the grid the board first drew); the board now draws it.
2. **The in-player panel stays docked on the left.**
3. **Results rows are compact** (about 70 px at 1080p): no league tag in the row; Replay pill, winner bold, spoilers and individual-sport rows unchanged.
4. **Remind me** also on the Team page's next-game tiles (a game no card plays yet too) and in the Timeline's bottom panel; "Reminder set" / "Reminder removed" is the app's toast card.

## Original open questions (superseded by the decisions above)
1. **Most exciting (#43):** under Hide until focused, fall back to start order with no why tags (drawn), or keep the excitement order without tags? (The order alone hints that a game is close.) And should followed teams stay pinned first in this sort?
2. **Kickoff prompt (#44):** turn on automatic reminders for followed teams by default? What does **Later** do: dismiss, or ask again in 5 min? Show the prompt when you are already watching the other followed game?
3. **Full-time toast under Hide until focused (#42):** show the names-only "GAME OVER" toast (drawn), or nothing, since knowing a game ended early can itself spoil?
4. **Score-change toasts:** followed teams only (drawn) or also any game you set a reminder for? Cap them (e.g. at most one per game every 2 min)?
5. **Team page record and standings (#62)** under Hide until focused: keep them visible (drawn; a streak or record can hint at last night) or put them behind Reveal like the Game page?
6. **Team page entry points:** OK on a team logo on the Game page only, or also a **Team page** entry in the hold-OK menu on Results, Scoreboard and cards?
7. **Replay jumps (#64):** always list **OT** so its presence never spoils (drawn), or only when the game had overtime? Under Scores = Off, may we fetch play-by-play for period times (it carries scores we never show)?
8. **Player card (#63):** include a disabled-for-now "Follow player", or leave following players out until alerts for players exist?
9. **Playable highlights (#61):** should **Watch all highlights** auto-play the next clip by default?

## We could also
- **★ Recommended — Crunch-time alerts.** Reuse the Most exciting ranking (#43) as a trigger for the #42/#44 toast: when any game turns "Close · 2 min left" or goes to OT, offer the same Watch / Later prompt, even for teams you don't follow. Same delayed feed, same toast, no new requests; off under Hide until focused and Off.
- **Season catch-up from the Team page.** "Catch up" plays the Team page's last-5 replays in a row; with #64 it can start each one at Q4 ("Fourth quarters only").
- **Remind me for a whole team.** A "Remind me for every game" switch on the Team page that creates #44 reminders as games appear, per profile.
- **Player highlights on the Player card.** Filter the game's ESPN clips (#61) by the player's name and show "Highlights" on the card, with Watch all.

### Earlier ideas (still open)
- Scoreboard first, from the in-player panel: done (SB2 reuses the panel's grouping).
- Reveal on demand: a "Reveal all scores" long-press and a per-league "never hide".
- Delay-aware live blocks: a small "delayed 30 s" tag on live chips when Score delay is on.
- Timeline from My sports + ESPN: draw followed teams' upcoming games before an addon lists them.
- Results → Timeline handoff: "Show this day on the timeline", Replay blocks playable in place.
