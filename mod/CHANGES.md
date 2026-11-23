# Changes in JugglucoNGmod

What differs from JugglucoNG, by version (GPL-3.0 requires marking changes with a date).
Changes in the JugglucoNG author's code: 0 files, unless stated otherwise (see HOOKS.md).
In short, how MOD differs for the user: the table on the [front page](/.github/README.md).

A version is a tag `mod-<JugglucoNG version>-<our number>` on `main`; it is set after a check on a phone or a watch.
Each version has two lists: "For the user" - what is visible in the app, "Build and checks" - everything else.
A new line goes into the "After …" section, in the same pull request (PR) as the change itself.

## After mod-1.3.3-10 (in `main`, no tag yet)
For the user:
- 2026-11-23 — CareLink for the MiniMed 740G pump: every 5 min (or 10, 15) the app takes the pump data from the
  Medtronic CareLink cloud (sent there by MiniMed Mobile). The journal gets the pump boluses and the carbs from the
  pump's bolus calculator, the chart the real basal (normal and temporary; a pump stop is a gap with a note), the
  "IoB" chip the pump's active insulin; the author's code sends the records to Nightscout. Own "CareLink" window from
  the settings: sign-in on the Medtronic page (the app does not see or keep the user name and password; the session
  is encrypted with an Android Keystore key and goes into no backup), reservoir, pump battery, active insulin, data
  age, "Update now", interval, warning delay. Notifications: sign in again, no pump data for N min, pump records not
  saved. Two hooks in the author's files, 3 lines (`carelink-settings`, `carelink-tempbasal`, see HOOKS.md). On a
  phone since 2026-11-18 (candidates 3 and 4, computer builds 6 and 8, candidates 9 and 10 with the 1.3.3-Alpha);
  the tag comes with the next candidate from `main`.

Build and checks:
- 2026-11-18 — CareLink JVM tests (231) on made-up cloud answers, no personal data: sign-in, parsing, journal and
  basal writers, poller, scheduler, the window model, a contract test of the `carelink-tempbasal` hook.
- 2026-11-16 — CareLink trial (`Common/src/modTrial/`): test builds `-mod0` carry a fake CareLink for emulator
  scenarios; `mod-build.yml` builds the phone once more like a candidate and checks the trial is not in it.
- 2026-11-16 — `mod/check-apk.py`: the phone APK has the CareLink components, the watch APK has none.
  `mod/check-secrets.sh`: pre-push patterns (JWT, e-mail, Windows home path, raw cloud folder).

## mod-1.3.3-10 — 2026-11-23 (based on JugglucoNG 1.3.3-Alpha; candidate 10, checked on a phone and a watch)
For the user:
- 2026-11-21 — home-screen widget "JugglucoNGmod" next to the author's two, in the xDrip look: transparent, the number
  in range color, trend arrow, change over 5 min, "N min ago", 3 h of dots where each dot is a 5-minute average; a
  stale reading is grey and struck through. One row of icons high by default, resizable. Its own settings window with
  a live preview, per widget, opened on placement and from the widget's "Settings"; an app-icon shortcut "Widget
  settings" while a widget is placed. The author's files are not changed. Checked on a phone with candidate 9 (on
  1.3.2-Alpha); merged into `main` after 1.3.3-Alpha, the tag comes with the first 1.3.3 candidate checked on a phone.
- 2026-11-17 — renamed to JugglucoNGmod (candidates 1-2 were built under the former name and package): app name
  "JugglucoNGmod" (watch face too), package `tk.glucodata.ng.mod` (installs as a new app), repository
  `lbarmen/JugglucoNGmod`; inside: folder `mod/`, build type `mod`, hook mark `MOD-HOOK`, tags `mod-X.Y.Z-N`. The
  versions below were built under the former name.
- 2026-11-17 — new signing key (certificate CN=JugglucoNGmod), fingerprint in `mod/mod-cert.sha256`. Builds
  signed with the former key (candidates 1 and 2) cannot be updated in place: install as a new app.
- 2026-11-16 — the author's code is exactly his JugglucoNG 1.3.2-Alpha release: 47 of his unreleased changes (alarms,
  Nightscout, watch) that were in candidate 2 are removed; they will return with his next release.

Build and checks:
- 2026-11-16 — `main` rebuilt once on the 1.3.2-Alpha release (6d8bb06f6): our commits are carried over on top, our
  files did not change, commit messages have no service marks. The former history (and the sources of candidate 2) is
  kept on the owner's computer.
- 2026-11-16 — `mod-sync.yml`: the author's test that is not yet in his code of the checked version is skipped with a
  note in the table instead of turning the check red (`tools/test-nightscout-backlog.py` appeared after 1.3.2-Alpha).
- 2026-11-16 — `mod/check-hooks.py`: every change in an author's file must be within 3 lines of a `MOD-HOOK` mark; a
  deleted author's file, a new file outside our paths, a binary file, a file mode change - red. Instead of "0 changes
  in the author's files", `check-apk.py` runs this check: the first hook will not turn every build red.
- 2026-11-15 — personal deny list for `mod/check-secrets.sh` (own Nightscout address, serial numbers): a file outside
  the repository, read only by the check on the owner's computer, values are not shown; entries are added with
  `mod/secrets-deny.py`. It also checks the texts of our commits and binary files; what is found by the general
  patterns is also printed masked.
- 2026-11-15 — the candidate number N is higher not only than the tags but than all earlier candidates of the same
  version (built and unfinished) and does not repeat when a build is restarted: otherwise two different APKs would
  have one version (`mod/candidate-number.sh`, checked also before the build with the key). `mod/apk-keep.py` finds
  candidates by their files on the build page and reports a repeated number as FAIL.
- 2026-11-15 — `mod/apk-keep.py`: the 5 latest candidate APKs on the owner's computer (downloads, checks the
  signature, version code and hashes).
- 2026-11-15 — tools for merging JugglucoNG updates: lists `mod/our-paths.txt` and `mod/risk-areas.txt`, a report on
  the author's new code (`mod/sync-report.py`), a hook check and a budget of changes in the author's files - at most
  15 files and 60 lines (`mod/check-hooks.py`), a secrets check only in our commits (`check-secrets.sh --ours`).
- 2026-11-15 — golden runs: 13 synthetic glucose series through the author's alarm and forecast code
  (`Common/src/testMobile/java/mod/`, expected decisions in `mod/golden/`).
- 2026-11-15 — daily check of the latest JugglucoNG release (`.github/workflows/mod-sync.yml`): merge inside the job,
  report, hooks, budget, secrets, build, the author's tests and our golden runs; no secrets, sends nothing.
- 2026-11-15 — repository front page `.github/README.md` with the table of differences (the author's README is not
  touched); this list is split by version.
- 2026-11-15 — merging a release through the PR `sync/<release>` (`mod/sync-merge.sh`, procedure in `mod/SYNC.md`, log
  in `mod/SYNC-LOG.md`), a Juggluco summary once a month (`mod/juggluco-summary.py`); a candidate for the phone only
  when the author's code in `main` is exactly his release older than 7 days; a reminder about blocks on test for
  longer than 7 days (`mod-remind.yml`); `main` is protected by a ruleset (`mod/ruleset-main.json`).
- 2026-11-15 — a light check of a PR made only of our `.md` files (except `mod/HOOKS.md` and `Common/`): hooks,
  budget and secrets - yes, build and tests - no, about a minute; a test build is not started for such branches.
- 2026-11-15 — fixes after the critics' review: a crashed hook or secrets check script no longer gives a green
  `report`; the build with the key takes exactly the block commits that the pre-check saw; the age of their release
  is also counted from the commit (a moved tag); the secrets check is wider (passwords without `=`, conflict
  resolutions); `sync-merge.sh`: `continue` after fixes, `finish` checks `report` and switches off the author's new
  jobs before and after the merge; reminders are more honest (red check, GitHub unavailable).
- 2026-11-15 — `mod/SYNC.md`: what to do in every red case (before and after sending the PR), `continue`, golden
  runs to the end, the Juggluco summary command for Windows.

## Candidate 2 — 2026-11-15 (based on JugglucoNG 1.3.2-Alpha and 47 of his unreleased changes from 14.11)
The first version with a tag. Candidate 1 did not get the tag: the watch face crashed on the watch (the second error
below).

For the user:
- 2026-11-14 — build type `mod`: package `tk.glucodata.ng.mod`, name "JugglucoNGmod" in 16 languages, an icon with
  another background, watch face "JugglucoNGmod", own version code (103201, watch 1103201), updates from this
  repository, the fallback update source is switched off.
- 2026-11-14 — watch face: in the official 1.3.2 it crashes with "String resource ID #0x0" - the names and icons of
  the colors get into the code as 0 and are thrown out by shrinking. The watch is built with real resource numbers
  and the former way of resource shrinking (`mod/gradlew-mod.sh`: `-Pandroid.nonFinalResIds=false`,
  `-Pandroid.r8.optimizedResourceShrinking=false`); the resources themselves are kept just in case by
  `Common/src/wearMod/res/raw/mod_keep.xml`.
- 2026-11-15 — watch face, the second error ("#0x0" on the watch after the fix): the `complication_*_style.xml` colors
  are created by the class name `ComplicationDrawable`, and the former way of resource shrinking does not keep its
  constructor - black screen, `NoSuchMethodException`. Our own rule `mod/proguard-mod.pro` and a check of constructors
  in the build. Checked on a watch on 15.11.2026.

Build and checks:
- 2026-11-14 — build and APK checks in GitHub Actions (`mod-build.yml`, `mod/check-apk.py`), a secrets check before
  a commit (`mod/check-secrets.sh`).
- 2026-11-15 — candidate build: `main` + block branches, signed with our own key (environment `mod-signing` with
  manual confirmation, `main` only), a check that "the branches bring no new code of the author", the certificate
  fingerprint is compared with `mod/mod-cert.sha256`, `update-manifest.json` (`mod/make-update-manifest.py`). APKs are
  on the build page; GitHub releases are not made yet (the `publish` job is switched off by the variable
  `MOD_PUBLISH_RELEASES`).
