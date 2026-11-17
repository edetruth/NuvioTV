# Issue #60 phase 1: ended games, Just ended strip - report

Base 0913c58b5 (feature/sports-live-section). Not pushed.

## User-facing
- Live Now: games found over leave the main list and sit at its end under a "Just ended" heading, dimmed (full opacity while focused). The tab count leaves them out.
- ESPN-ended card: pill "Final" (dropped when the chip already reads "FT 2-1" / "Final 131-124"), chip per Scores setting (Hide until focused: uniform "Score hidden"; Off: no chip), green Replay tag when a strict replay match exists (Results' matcher). OK -> Game page (with the replay, Watch replay). Pane Watch still plays; hold OK keeps Choose stream.
- Time-estimated card: pill "Probably ended", no score; OK plays as usual (estimate may be wrong).
- Cards leave Live Now 30 min after they ended (ESPN: final first seen; estimate: start + length).
- Home Live now row: ended games dropped (no strip).
- Player games panel: ended games in a last "Just ended" group, chips from the remembered final; dropped after 30 min.

## Signals (core/sportslive/SportsLiveEndedGames.kt)
1. ESPN final on a strict match (ScoreChips.matchedEvent) from the delayed snapshot only; needs POST + isFinal + both scores (a final the delay still withholds does not count). Grace 10 min before the move. OFF (postponed/cancelled/suspended) never ended. Any strict-match state short of final = not ended and blocks the estimate (ESPN wins).
   - Final-seen time remembered per card (shared instance across section/Home/panel), so untracked cards keep their verdict; ESPN going back to IN/PRE clears it.
   - A final first seen without the game ever seen in play is dated min(now, start + length), so a long-over game at app start is not given a fresh 30 min.
2. Estimate for cards with no snapshot: timed start (merged card: latest timed source) + SportsLiveGameLength (one table: soccer 2h15, basketball/hockey 3h, football/baseball 3h45, unknown 4h). No estimate for channels, untimed/date-only cards, replays, or long-session sports (tennis, golf, cricket, motorsport, fight cards, darts...). A bare "Football" (no soccer word/competition) takes the longer American-football length.
- Channels never get a verdict (ESPN or estimate).
- Section: an estimated game whose links were found lately (repo.hasRecentLinks) stays in the strip past 30 min rather than vanishing.
- Section: a game ESPN shows in play is no longer cut by the existing overdue rule (SportsLiveLiveDuration).
- No new requests; no new setting.

## Files
New: SportsLiveEndedGames.kt, SportsLiveGameLength.kt (+ tests SportsLiveEndedGamesTest, SportsLiveJustEndedTest, SportsLiveHomeEndedTest).
Changed: SportsLiveViewModel (scores flow + 30 s clock, split), SportsLiveUiState (justEnded, endedOf, countOf, sportsLiveCardOk), SportsLiveScreen (strip heading, labels, OK routing, Replay tag, replay on Game request), SportsLiveCards (ended/hasReplay), SportsLiveResults (ReplayPill internal), SportsLiveScores (rememberSportsLiveClockMs), SportsLiveZapPanel/ZapViewModel/ZapOverlay (JUST_ENDED group), Home (HomeUiState.liveNowEnded/liveNowShown, setLiveNowEnded, HomeScreen), strings.xml (2 strings), SportsLiveViewModelTest (+2 tests).

## Tests / builds
- (a) sportslive + ui.sportslive + player + home: 1473/1473 pass (first attempt killed by host memory pressure; retry OK).
- (b) compilePlaystoreDebugKotlin: OK.
- (c) assembleFullDebug: OK.

## Notes
- Another agent's uncommitted work (game situation: EspnScoreProvider, EspnScoreboardDto, ScoreDelay, ScoreModels, GameSituations, EspnFixtures, fixtures) was in the tree; builds included it; not staged.
- Panel: the strip ignores the panel's older Hide-until-focused "keep finals by start" rule, consistent with the section (leak of "ended" accepted by the spec).
- Before the first ESPN fetch (or for a card in another filter, not tracked), an ESPN-coverable game past its estimate can sit in the strip until its live snapshot arrives; it is never hidden, and OK still plays it.

## TV-only checks
- Strip look at 1080p: heading spacing, dim level, focus ring on a dimmed card, Replay tag fit.
- D-pad across the live/strip boundary; focus when a focused card moves into the strip or drops out.
- A real game: final -> 10 min -> strip -> 30 min -> gone, with and without Score delay; OK opens the Game page.
- Home row shrink when a game ends (row rebuild, focus).
- Player panel Just ended header.

## Review fixes (commit after 30053de7c)
Principle: an estimate never hides a game; only an ESPN-confirmed final (after the grace) removes one.
1. Home drops only ESPN-confirmed finals past the grace (SportsLiveEndedGames.confirmedEndedKeys); with no scores (Hide/Off) nothing is dropped on an estimate.
2. Section/panel: estimated games stay in the strip (playable) until the addon drops them or 6 h past the estimated end (EndedGame.dropsAtMs); finals still drop at 30 min. The older overdue rule (SportsLiveLiveDuration) now moves a game to the strip as "Probably ended" instead of hiding it (still skipped when links were found lately or ESPN shows it in play).
3. Lengths: soccer 2h55, baseball 4h30, + 20 min late-start allowance on every estimate (SportsLiveGameLength.LATE_START_MS). Long-session list adds Le Mans, 24 Hours/24h, endurance, rally, Giro, Vuelta.
4. No estimate for a card ESPN was ever seen playing (seenUnfinished), tracked or not. Estimate gate (estimateGate): Scores Off, no ESPN feed covers the card, or LiveScoresRepository.unmatched (new: every routed feed answered/paused without the game, out of My sports, or no feed in scope; remembered while untracked, cleared on My sports change). The section now tracks the whole Live Now tab (every filter) so every card's feed answers.
5. Panel: 30 s tick while open (grace/drop updates) and the same gate. Section: a focused card that moves into the strip is scrolled back into view and refocused.
Tests: SportsLiveEndedGamesTest (estimate stays 6 h, finals drop at 30 min, seen-in-play never estimated, gate, Home confirmed-only, lengths), SportsLiveHomeEndedTest (final dropped; Hide/Off overdue not dropped), SportsLiveViewModelTest (gate holds a coverable NFL card past its estimate; overdue moves to strip), LiveScoresRepositoryTest (unmatched only after the feed answers, kept when untracked).
Builds: (a) 1480/1480, (b) OK, (c) OK, pre-push hook 0. Also fixed: unknown-sport estimate lacked the late-start allowance.
