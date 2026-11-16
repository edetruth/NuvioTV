# Sports & Live: live scores v1 — design

Date: 2026-11-16
Status: approved scope (user: "lets start phase 1 after the first release"); details are controller rulings from `D:\Nuvio\tasks\research-live-scores.md`
Depends on: Sports & Live section, My Teams (`SportsLiveTeams.parse/normalise/matches/knownSport`), merged cards

## Intent

Show live and final scores on Sports & Live game cards and in the details pane, and let the user see yesterday's (and older) results — without spoiling games they plan to watch.

## Rulings

1. **Source:** ESPN's public site API (`site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates=YYYYMMDD`), keyless, behind a `ScoreProvider` interface so another provider can replace it. Fork-only feature; tolerant DTOs (every field optional); any failure → no score shown, never an error UI.
2. **Leagues v1:** NBA, NFL, NHL, MLB, college football/basketball only if the card's sport says so; soccer via a keyword → league map (Premier League `eng.1`, La Liga `esp.1`, Serie A `ita.1`, Bundesliga `ger.1`, Ligue 1 `fra.1`, Champions League `uefa.champions`, Europa `uefa.europa`, Nations League `uefa.nations`, MLS `usa.1`, AFCON `caf.nations` / qualifiers `caf.nations_qual`, World Cup qualifiers by confederation, friendlies `fifa.friendly`) and fallback `soccer/all` (polled no more than every 60 s; once a card matches, remember its league slug). Tennis, UFC, cricket: not in v1.
3. **Matching** (precision over recall): parse with `SportsLiveTeams.parse`; candidate events within the card's start ±3 h (or today/yesterday when no time); per competitor aliases = normalised displayName, shortDisplayName, name, location, location+name, abbreviation, plus a small alias table (LA ↔ Los Angeles, Man Utd ↔ Manchester United, Ivory Coast ↔ Côte d'Ivoire, DR Congo ↔ Congo DR, USA ↔ United States), diacritics stripped. Both sides must match (alias equality or the existing `matches` suffix rule); ties → closest start. Qualifiers (W), U21 etc. kept distinct. Channels never match unless `parseBracketed` finds a game. Match result cached per card per day. Misses: no chip, retried on the next refresh, logged in debug builds.
4. **Polling** (one request per league feed per interval, shared by all collectors, coalesced, ±3 s jitter): only while the Sports & Live screen (or Home's Live now row) is visible; 30 s when a matched game in that league is in progress; 5 min when only upcoming (30 s from 5 min before the first start); stop when all are final. Backoff 30 s → 60 s → 2 min → 5 min on 429/5xx/timeout; after 3 consecutive failures hide that league's chips until a success. Finals and past days cached on disk for 7 days (OkHttp cache + a small JSON cache for finals).
5. **UI:**
   - **Card chip** (top-right of the card art or next to the LIVE badge): live → red dot + `status.type.shortDetail` + score ("Q3 4:12 · 88–80", "67' · 2–1"); final → "FT 3–1" / "Final/OT 101–99"; upcoming → nothing new.
   - **Details pane:** scoreline with team abbreviations, a linescore row (quarters / halves / periods) and, for soccer, goal scorers from `competitions[].details` — all from the scoreboard (no extra call).
   - **Results:** a "Results" chip/tab entry on Sports & Live listing finished games from yesterday and earlier (date chips: Yesterday, 2 days ago, … up to 7) for leagues the user has watched or followed teams in (My Teams) — each row: teams, final score, league. Selecting a result opens Replays for that game if an addon has one, else nothing.
   - **Merged cards** use the primary's teams/time; one chip per card.
6. **Spoilers:** setting "Scores" in Settings → Sports & Live: **Show** (default) / **Hide until focused** / **Off**. Hide-until-focused blurs/replaces the score with "Score hidden" until the card is focused for 1 s; Results rows always respect it. Replays tab never shows scores in v1.
7. **Data minimisation:** requests carry only league and date; no user data; generic User-Agent.

## Non-goals (v1)

Game page (Box Score / Plays / Standings — v2/v3), UFC/tennis/cricket, push notifications for score changes, Home-row score chips beyond Live now (Live now gets the chip since it reuses cards).

## Risks

Unofficial API (terms forbid automated access; changes without notice) — fork-only, tolerant parsing, silent failure, provider interface. Mis-matches — both-sides rule, time window, qualifiers. Spoilers — setting.

## Verification

Unit: league map, alias/matcher (real trimmed ESPN fixtures for NBA, NFL, soccer incl. AFCON qualifier with abbreviations), polling cadence/backoff (virtual time), spoiler modes, cache. Emulator/TV: a live NBA/NFL/soccer card shows a chip updating within ~30 s; a finished game shows FT; Results lists yesterday's games; Hide-until-focused hides scores.
