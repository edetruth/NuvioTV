# Sports & Live V2: the remaining screens (2026-11-19)

These boards bring every Sports & Live surface that is not yet V2 up to the V2 language (#134). They also add two new pieces: the **first-run setup** and **"+ Add a team"** (#149).

- **Status:** proposal, pending approval. Nothing here is built.
- **Format:** the same as `../2026-11-16-sports-live-views/`. Each board is one 1920×1080 `.dc.html` sheet: static HTML with inline styles, opened in a browser. Each frame on a sheet is a real 1920×1080 TV frame shown scaled down, so every px value in a frame is a board px. Board px ÷ 2 = dp/sp. `canvas.json` indexes the boards.
- **Language:** `SportsLiveTokens.kt` and `SportsLiveFocus.kt`, and the approved boards V2-LiveNow, V2-Upcoming, V2-Header-and-Focus and V2-Before-After.
  - Focused = 4 px white ring + 1.03 lift.
  - Selected = underline, SelectedTint fill or a white left bar.
  - One PRIMARY button + More….
  - Type scale 22 / 26 / 30 / 40 / 64.
  - No letter circles.
- **Sample data:** generic. ESPN team names are used; "Source A" stands for any addon. No addon names, hosts or URLs appear.

| Board | What |
|---|---|
| [`GamePage.dc.html`](GamePage.dc.html) | Game page, revealed and hidden |
| [`TeamPage-PlayerCard.dc.html`](TeamPage-PlayerCard.dc.html) | Team page and Player card |
| [`Settings.dc.html`](Settings.dc.html) | Settings → Sports & Live, My teams, My sports |
| [`TeamSearch.dc.html`](TeamSearch.dc.html) | Team search: "+ Add a team", also first-run step 3 |
| [`FirstRun.dc.html`](FirstRun.dc.html) | First-run setup, 5 steps |
| [`Player.dc.html`](Player.dc.html) | In the player: Live now panel, CH ± banner, app toast, starting card |
| [`Dual.dc.html`](Dual.dc.html) | Dual view: tiles, pane states, picker |
| [`Home.dc.html`](Home.dc.html) | Home: Live now row, Catch up row, hero |
| [`Menu-and-Toasts.dc.html`](Menu-and-Toasts.dc.html) | More… menu (matched and unmatched) and the one toast card |
| [`States.dc.html`](States.dc.html) | Empty, error, loading, unmatched events, mini player |

## 1. Inventory: what is not V2 yet, and what is wrong

Sources for this inventory:
- the code on `feature/sports-live-section` at f76de00d2;
- the screenshots from the 2026-11-18 review and the V2 sessions.

| # | Screen | Top problems |
|---|---|---|
| 1 | **Game page** (`SportsLiveGameScreen.kt`) | Uses its own NuvioTheme shell, not the tokens. Sections are **filled chips** (selected = solid fill). The header is a card with up to 6 buttons in a 280 dp grid. Focus is the theme ring with no lift. Body ← → always switches section. Hidden mode is one grey line on black (F9). Linescore table uses half its box. Top-performer stat lines are cut. Hint footer "← → sections · ↑ ↓ scroll · Back to return". Colours are hard-coded. |
| 2 | **Team page** (`SportsLiveTeamScreen.kt`) | 400 × 240 tiles with empty middles; the 5th tile is clipped at the edge. Leaders put the stat line above the player name. LIVE badge is a local red pill. Replay pill colours are hard-coded. Theme ring, no lift. Hint footer. |
| 3 | **Player card** (`SportsLivePlayerCardScreen.kt`) | An on-screen **Back** button plus "Back to return". The stat tiles sit in a plain Row that overflows. "vs DET" here, "@ Lions" on the Team page. Gap and colours differ from the other pages. |
| 4 | **Settings → Sports & Live** (`settings/SportsLive*`) | Generic settings cards: card-in-card. Rows are 125 px with the label pinned to the top. On and off toggles look alike. My sports takes 9 sports × 2 rows before Scores. **My teams can only remove**, and OK unfollows at once. The empty-state hint is stale. The Status dialog uses a default white-fill focus. Raw "Picture-in-picture: supported" text. Catalog rows look like debug output. |
| 5 | **First run** | Does not exist. There is no setup flag (only the one-time "new sports" hint). |
| 6 | **Team search** | Does not exist. `TeamDirectory` has only `teams(league)` and `resolve`, is filled lazily, and has no name search. `FollowedTeam` has no logo. |
| 7 | **In-player Live now panel** (`SportsLiveZapOverlay.kt`) | Mostly V2 since #140, but: the 3-option filter strip clips ("My sports 109 · All sports 117 · Basket…"). "● LIVE" repeats on every line. Unmatched lines show "OTHER" and "LIVE: …". Long names are cut before the score. |
| 8 | **CH ± banner, full-time card, behind-live chip** | Legacy styles (BackgroundElevated, titleLarge). Raw "Switching to LIVE: …". The source name appears with an emoji. Glyph hints instead of buttons ("▼ All games", "Stay for postgame · Next live game →"). |
| 9 | **Starting card** (`SportsLiveStartingCard.kt`) | A centred modal. In dual mode the selected button is a **white slab**. A "Back" button repeats the Back key. Buttons are pill-shaped. |
| 10 | **Dual view** (`dual/*`) | Theme-colour 3 dp border instead of the ring. Raw labels "title · ⚡ source (tv)". The error pane is key-glyph text, not buttons. 270 px black bands. The picker has white-fill focus and never gets row data (`rows = emptyMap()`), so it shows no logos or scores. A system Toast says "No other live game". |
| 11 | **Mini player** (`mini/SportsLiveMiniPlayerHost.kt`) | Theme ring, no lift. The caption changes between "Play/Pause to expand" and "OK to expand". It opens a generic "Switching to…" dialog. |
| 12 | **Home sports rows** (`home/SportsLiveHomeRow.kt`, Catch up) | Generic Home cards with addon art that has LIVE baked in. The row is titled with the addon's "Live now - Sport". The hero reads "Movie · 2026-11-18" plus the addon blurb. Continue watching centre-crops text art ("SKETB"). |
| 13 | **Alerts and toasts** (`SportsLiveAlertsHost.kt`, others) | Placed top-right, where the reminder toast covers the stage art and wraps to 3 lines. The reminder bell is gold (gold means followed). A separate `ArtColors` palette. **Android system Toasts in 7 places**: "Playing from …", "No stream for …", "No streams found …", rate-limited, "No other live game …". |
| 14 | **Empty, error, loading** | The generic `EmptyScreenState` says only "Nothing here right now". Results has 4 different empty styles. A lone spinner shows mid-screen. Nothing says what comes next. |
| 15 | **More… menu and stream picker** | A NuvioDialog centred over the list, with the title cut off. **Follow targets come from the raw title**, so "Follow LIVE: Bowls: New Zealand" is offered, and is stored as a team name. Stream picker rows use white-fill focus and show raw 2-line stream names. |
| 16 | **Unmatched rows (data gaps, #134.7)** | "LIVE NOW" used as a league tag; empty tags. Long sport words are shrunk ("BASKETBALL"). A "LIVE:" prefix. Two-line rows with an empty second team. A big empty name art on the stage. Talk shows listed as live sport. |

## 2. Design decisions

**Shared**
1. **One header for pushed pages** (Game, Team, Player): no card around it. It has logo(s), names, the big numbers, then one action row with one PRIMARY button + More…. Follow, Watch with another game, Team pages and Details live in More….
2. **No hint footers** on any Sports page.
3. **No Back button** where the Back key does the job.
4. **Text tabs** wherever sections exist (`SportsLiveTextTab`).
5. **Rows, not tiles.** Every list or table row can take focus, so ↓ always scrolls (fixes F8 and F10). Rows never re-order under focus.
6. **One tag column for every row type: 160 px (80 dp).** This is the width the Agenda already uses (#146). Sport words like BASKETBALL fit at 22 px, never shrunk, and names start at the same x on every row. Token change: `ColumnLeague` 48 → 80 dp, `ColumnNames` 220 → 188 dp.
7. **One toast card** (`SportsLiveToast`):
   - 640 px wide, Surface-raised fill, icon column, title 28 px / line 26 px; text ellipsises, never wraps;
   - bottom-left of the safe area, shown for 6 s; it never takes focus, except the kickoff prompt.
   - The icon's colour means something: blue bell = reminder, gold ★ = your team, amber ⚠ = a problem, FT = a final.
   - It replaces **every** Android system Toast in the section.
8. **One empty/error card** in the list column that always offers the next thing to do. Examples: "Nothing live right now · Next: … · See Upcoming / Remind me", or "Couldn't reach your sources · Try again". Loading uses skeleton rows, not a spinner.

**Per screen**

9. **More… opens in the stage**, as a 720 px sheet. The list keeps its place, and the row keeps its white "selected" bar. The menu title is the clean title plus one status line.
   - **Follow ‹team›** only for sides matched in the team directory (ESPN id), named with the ESPN short name.
   - New: **Hide from Live Now** for unmatched non-sport items.
10. **Unmatched events look intentional:**
    - the sport tag, one clean 30 px line, and a red dot + "Live" at tag size;
    - no "LIVE:" prefix and no empty second line;
    - items that fit no sport fold into one "Other live events · N ›" row.
11. **Game page:**
    - centred hero with a 96 px score (new token `Type.HeroScore`, 48 sp) and 110 px logos with no filled circle;
    - linescore at 34 px; Top performers as 2 × 2 fixed cards;
    - Team stats colours become tokens (`StatBetter`, `StatOther`);
    - hidden mode: **Reveal score** is the focused PRIMARY, Watch replay sits beside it, and the body is a calm card over greyed placeholders.
12. **Team page:**
    - Follow is the PRIMARY (★ Following becomes SECONDARY);
    - Next games and Last 5 are rows;
    - in Leaders, the name is the headline;
    - a 4-row standings snippet shows this team on SelectedTint.
13. **Player card:** 6 stat tiles at most; Last 5 as 64 px focusable rows with the same "@ / vs" wording as the Team page.
14. **Settings:** only the content pane changes; the app's Settings shell stays.
    - Order by use: Scores & spoilers → What I follow → Alerts → Playing → Home & rows → Setup → Status → More.
    - 72 px flat rows.
    - The focused row's explanation shows in **one fixed help line** at the bottom.
    - Switches show the word **On/Off** and a **blue** track when on (blue already means "you set this").
    - My sports: one row per sport; OK picks its leagues, and no league means off.
    - My teams starts with **+ Add a team**.
15. **Team search:**
    - an **in-app letter grid** (36 keys of 80 × 72 px, plus Space, ⌫ and Clear), because the system IME would cover the results;
    - search is local, over the directory's name, short name, abbreviation, city and nickname, plus `SportsLiveTeamAliases` (UConn → Connecticut);
    - line 2 says why a row matched;
    - the sport filter is one focus stop;
    - an empty field browses teams A–Z by league, so it works off-season;
    - OK follows in place;
    - a "Your teams" column keeps the running list.
16. **First run:** 5 steps: Welcome → Sports and leagues (list + stage) → Teams (the search) → Scores and spoilers (3 cards, each showing a real row) → Done (summary, then Go to Live Now).
    - A fixed footer: Skip setup · step dots · Back · Next.
    - Each step saves when you press Next.
    - A per-profile flag. Profiles that already changed My sports or follow a team are marked done silently.
    - Run it again from Settings → Setup.
17. **In the player:**
    - the panel filter becomes one dropdown;
    - matched lines use ESPN abbreviations with the leader bold, so the score and clock always fit;
    - the preview is the panel's fixed 528 × 297 stage;
    - the banner shows a clean title, a status line and "3 of 9 · Next: …";
    - the starting card is a bottom-left card (Try next link PRIMARY + Sources), not a modal.
18. **Dual view:**
    - tiles are 912 × 513, and the V2 ring marks the tile with sound;
    - a score strip sits under each tile;
    - pane states use real buttons;
    - the picker is the in-player panel ("Watch with…").
19. **Home:**
    - the Live now row uses drawn V2 cards (logos, red dot + clock, score), followed games first, and ends with "See all · 32 live";
    - the hero meta for a game reads "NBA · ● 4:12 · Q4 · BOS 91–88 NYK";
    - Catch up shows "Final · date" + Replay and never a score;
    - with no team followed, Catch up shows one "Follow a team" card.

**New or changed tokens:**
- `Type.HeroScore` 48 sp;
- `Type.PageTitle` 26 sp (52 px team or player name);
- `Colors.StatBetter` `#9AC2FF` / `Colors.StatOther` `#4B5A6E`;
- `Colors.SetBlue` (= WhyTagBlue) for switches;
- `Size.ColumnLeague` 80 dp / `ColumnNames` 188 dp;
- `Size.ToastWidth` 320 dp;
- `Size.SettingsRow` 36 dp;
- `Size.Key` 40 × 36 dp.

Retire `ArtColors` in favour of `SportsLiveTokens.Colors`.

## 3. Build order

Each phase is sized for one agent and ends with an emulator check. Following the visual-review rule, each phase is judged on the TV before merge.

| Phase | Scope | Emulator check |
|---|---|---|
| **1 · Toasts and states** | `SportsLiveToast` replaces every system Toast and the top-right alert toasts. One empty/error card on all 5 tabs and Results. Skeleton loading. | Profile with no addons; offline; an empty tab; CH+ onto a game with no stream; set and cancel a reminder. |
| **2 · Rows and More…** | 80 dp tag column on every row. Unmatched rows and the "Other live events" fold. More… as a stage sheet with a clean title. Follow only for directory-matched sides (parse the cleaned name). Hide from Live Now. | Live Now with addon noise; More… on a cricket item (no Follow), on an NBA game (Follow ×2). |
| **3 · Game page** | Header, action row, text tabs, focusable Box score and Standings rows, hidden state, tokens, no footer. | A final NFL game revealed and hidden; Box score ↓ to the second team; Standings; Highlights. |
| **4 · Team page + Player card** | Header, rows instead of tiles, Leaders, standings snippet; Player card tiles, scrollable Last 5, no Back button. | Open from the Game page; Hide-until-focused; Last 5 scroll; Back restores focus. |
| **5 · Team search + My teams (#149)** | `TeamDirectory.search(query, leagues)` with aliases and ranking. On-demand fill of a league's teams (weekly cache). Logos for `FollowedTeam`. The search screen. "+ Add a team". The new empty-state string. Follow from the panel's More…. | Search "uconn", "lakers", "LA"; a league in its off-season; follow, then unfollow, round trip. Unit tests for search and aliases. |
| **6 · Settings** | Content pane rows, the new order, the help line, On/Off switches, My sports one row per sport with a league picker, a Setup row. | D-pad through every row; toggles readable from the couch. |
| **7 · First-run setup** | Per-profile flag, 5 steps, the shared footer, save per step, Skip, re-run from Settings. | A fresh profile; skip at step 2; finish; re-run from Settings. |
| **8 · In the player** | Panel dropdown filter, abbreviated lines, preview stage. CH ± banner. Full-time card buttons. Starting card as a bottom-left card. | Play a game; open the panel; CH ±; a failing first link. |
| **9 · Dual + mini player** | Ring, score strips, pane-state buttons, the picker = panel; the mini player's tag, caption and ring. | Watch two games; force an error pane; mini player while browsing. |
| **10 · Home rows** | Drawn Live now cards, hero meta, See all, Catch up empty card; drawn art for sports replays in Continue watching. | Home with live games and one followed team; then no followed team. |

Phases 1 and 2 come first because every later phase reuses the toast, the states card and the row columns. Phase 5 must land before 6 and 7, which reuse the search.

## 4. Open questions for the user

1. **Game page:** Follow moves from two always-visible buttons into More…. OK, or keep the Follow buttons?
2. **Hidden Game page:** Reveal score as the PRIMARY (from the review, drawn), or Watch replay as the PRIMARY for spoiler-averse viewing?
3. **Team search:** the in-app letter grid (drawn) or the system keyboard?
4. **First run** for profiles that are already set up: skip silently (drawn), or show it once?
5. **80 dp tag column** on every row type: the names column loses 32 dp. OK?
6. **Hide from Live Now** for non-sport items: add it in phase 2?

## 5. We could also

- **Suggested teams.** In team search browse, show "From what you watch": teams whose replays or games you played most, with one OK to follow each.
- **Live tonight → sports.** First-run step 2 pre-ticks the sports your addons list live right now.
- **Voice.** The remote's mic fills the search field (drawn as a hint under the grid).
- **Remind me for every game** from the Team page's More…: reminders are created as games appear.
- **Off-season note** in My teams: "Season starts Thu 22 Oct" in muted text, so an empty Upcoming doesn't look broken.
- **Hide learns.** Titles hidden from Live Now feed the sport inference, so similar items fold into "Other" automatically.
