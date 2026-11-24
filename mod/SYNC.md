# Merging JugglucoNG releases — procedure for the chat

**When.** A GitHub e-mail about a red run of `MOD sync`: the job "Release X waits for merge" is a reminder
("is checked and waits for the merge into main" - the check is green; "waits for the merge, but the check is red" - read the report first, see
below) or something is red (a failure). We take only their releases without the "pre-release" mark; another point
(`--point`) only by the owner's word.

**1. Read the report** — the page of the morning run (`gh run list -w mod-sync.yml`, the `report` result, the
`sync-report` file): summary, risk flags (sensor and alarms first), golden runs, their CI, their Issues of the last
7 days, their new features against `mod/CHANGES.md` and the open lines of the owner's private list of upstream inconveniences (spec 3.4).

**2. Merge** (Git Bash, from `code\`, a tree without uncommitted changes):
```
bash mod/sync-merge.sh prepare --decision "merge: <why>" --minutes <how long you spent reading>
bash mod/sync-merge.sh finish <PR number>
```
`prepare`: branch `sync/<release>`, merge, checks, a line in `mod/SYNC-LOG.md` (the first `prepare` creates the log),
a PR with the report. If the branch already exists, `prepare` does not touch it: continue on it (`continue`) or delete
it by the owner's word. A trial without sending - `MOD_SYNC_DRY=1 bash mod/sync-merge.sh prepare …`, then delete the
trial branch.
`finish`: waits for the PR checks and a green `report`, switches off their new jobs in the branch before and after
the merge (and cancels their runs), merges with a merge commit (no squash and no rebase). The needed libjuice commit is
not in our copy (section 11) - first `gh repo sync lbarmen/libjuice`.

**3. To the owner — 3–5 lines** "what changes for you": sensor, alarms, watch, Nightscout, what is noticeable in the
app; what to check on the phone before installing (risk flags, spec 3.6). A candidate for the phone - not earlier than
7 days after the release.

**Red - what to do.** Always fix on the branch `sync/<release>` with a new commit. Before sending (`prepare` said
"checks are red" or "CONFLICT") - commit, then `continue --decision … --minutes … [--conflicts N]`. After sending
(a red PR) - commit, `git push`, wait for a green `report`, then `finish`. Do not run `prepare` again.
- conflict — resolve the files by hand (hooks - by `mod/HOOKS.md`), `git add`, `git commit --no-edit`, then
  `continue … --conflicts N`; `rerere` will remember the resolution;
- hooks and budget (`check-hooks`: more than 15 files or 60 lines in their files, a hook without a row in HOOKS, a
  change in their file farther than 3 lines from a mark - often a trace of a manual conflict resolution) - return
  their files to their form, put ours into our files; the budget is too small for the job - ask the owner;
- secrets (`check-secrets`) — remove the line and rewrite the commit before sending; already on GitHub - tell the
  owner (a key or token must be changed); `personal deny list entry N` - entry N of the owner's personal list, the
  value is not shown and must not be requested in the chat: the file is named in the line, the rest is for the owner;
- our build (`check-apk`, inheritance of `mod` from their `release`) — the author changed the build: fix in our
  files; `Common/schemas changed` - the author did not commit the export of the DB schema: check whether it is the
  same on his side, the owner decides;
- their tests — are they red at the author's too (section 13)? Yes - not our fault, the owner decides; no - investigate;
- golden alarm runs — the difference table in the report. The decisions changed by their intent (PR in section 3) -
  accept: `gh run download <run> -n sync-tests`, copy `Common/build/mod-golden/actual/*.csv` to
  `mod/golden/expected/` on the branch `sync/…`, commit, in the SYNC-LOG line - "golden runs updated: <why>", then as
  above (before or after sending); unclear - ask the owner;
- the job `check` is red (`no upstream release found`, an API failure) or "tree differs" - restart the run later;
  if it repeats - investigate;
- "Mail probe" is red every morning - the variable `MOD_SYNC_TEST_FAIL` was not removed (`gh variable delete`);
- the release is broken at the author's and we skip it - only by the owner's word: the variable
  `MOD_SYNC_SKIP=<mark>` (`gh variable set`), the reminder stays quiet until the next release.

**Our code that copies theirs without a hook** — after a merge, if their file changed, check ours still matches:
- `WearSync2.kt` (request format `[u8 ver][i64 fromSec][u8 force]`, `VERSION`, the 14-day deep line in `onRequest`),
  `WearMessagePath.SYNC2_REQ`, `NotificationHistorySource.getRawHistory` — copied by our watch's history hole check
  `Common/src/wear/java/tk/glucodata/mod/watch/HistoryGapFill.kt`; the test `HistoryGapFillTest` turns red on a change.

**Our watch alarm screen** (`Common/src/wear/java/tk/glucodata/mod/watch/alarm/`, watch part 8) replaces their watch
`ui/AlarmActivity.kt` through `ui/AlarmActivityAccess.register` and copies its logic step by step. If either file,
`WearAlarmActivityHost.kt`, `Specific.registerBridges`, `AlarmActionReceiver`, `AlarmLaunchReceiver.launchFlags` or
their `.ui.AlarmActivity` manifest entries changed: compare with `ModAlarmActivity.kt` / `AlarmContent.kt` / our
`wearMod/AndroidManifest.xml` entry and carry the change over (new extras, a new lifecycle step, another way to
register); `ModAlarmContractTest` turns red on most such changes. Rollback to a watch build without our screen
(theirs, or ours before part 8) installed over it: an alarm notification already showing names `ModAlarmActivity`, so
tapping it opens nothing until the next alarm builds a new one - tell the owner, nothing to fix.

**Once a month** — `python -X utf8 mod/juggluco-summary.py` (the Juggluco summary; there is no `python3` in Git Bash
on this machine): carry over only a fix of the sensor, alarms or watch that JugglucoNG does not have, and by the
owner's word.

A red PR can be merged only by bypassing the "only through a PR" ruleset, and only by the owner's word.
