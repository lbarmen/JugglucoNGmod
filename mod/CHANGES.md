# Changes in JugglucoNGmod

What differs from JugglucoNG, by version (GPL-3.0 requires marking changes with a date).
Changes in the JugglucoNG author's code: 0 files, unless stated otherwise (see HOOKS.md).
In short, how MOD differs for the user: the table on the [front page](/.github/README.md).

A version is a tag `mod-<JugglucoNG version>-<our number>` on `main`; it is set after a check on a phone or a watch.
Each version has two lists: "For the user" - what is visible in the app, "Build and checks" - everything else.
A new line goes into the "After …" section, in the same pull request (PR) as the change itself.

## After mod-1.3.3-11 (in `main`, no tag yet)
For the user:
- 2026-11-26 — the phone's always-on display ("Lock screen (AOD)", the author's accessibility overlay) in the look of
  the watch face's dim mode (`Common/src/main/java/tk/glucodata/mod/aod/`): the number centred (with the chart on, the
  author moves it towards the chart's last point, so it sat off to the right), the arrow to its right, the change and
  the reading's clock time small under it; below, 3 h of 5-minute dots (white in range, the author's zone colour
  outside it), dashed low and high lines with their values, thin hour lines with labels, the author's forecast as a
  thin dashed line. No background: no target band, no grid, no grey forecast fill. The number and the arrow are always
  white (the owner's choice). The author's service keeps when to show, the burn-in steps, the light sensor, the
  refresh and the position; of the author's AOD settings chart on/off and size, text size, arrow on/off, the delta
  interval, position and opacity still act, font, weight and "show delta" do not. One hook in the author's files, 2
  lines (`aod-block`, see HOOKS.md); if our block fails, the author's own drawing is shown. Samsung's always-on
  display (One UI 6.1, measured 2026-11-26) keeps the screen suspended and takes a new frame only when the system
  wakes it for about 2 s, every one to a few minutes - the author's look included; so the block shows the time of the
  reading, not its age, and a refresh that would draw the same picture is skipped. On the owner's phone since
  2026-11-26 (computer build 12); the tag comes with the next candidate from `main`.

Build and checks:
- 2026-11-26 — a candidate reaches everyone only after the owner's "all ok" on the phone, as the very files checked
  there: `mod/publish-candidate.py mod-X.Y.Z-N --publish --checked "<what was checked>"` takes them from the owner's
  copy (`mod/apk-keep.py fetch`), checks them against the sha256 the candidate run printed in its log (GitHub keeps
  job logs 90 days, the artifact 14) and against our signature, uploads them into a draft, compares GitHub's own
  sha256 of each file, sets the annotated tag (its third line is the `--checked` text) and only then makes the
  release normal and Latest; afterwards it checks the release as an anonymous visitor. It publishes only the
  candidate the owner marked as the one on the phone (`mod/apk-keep.py installed`), built from `main` alone, newer
  than every release. `--probe` does the same into a draft under a probe tag and deletes it; `--verify` compares a
  published release with the copy. The `publish` job of `mod-build.yml` (it published right after the build;
  switched off since 2026-11-25) is removed.
- 2026-11-25 — `mod-sync.yml`, job `tests`: downloaded dependencies are cached between runs (Gradle modules, the
  wrapper, Robolectric's android-all images; no configuration cache, no build outputs, no secrets in that job), saved
  after red tests too. Their JVM command runs through `mod/jvm-tests.py`: a download failure (Maven 429, a plugin or
  an android-all image not fetched) runs it again, up to 3 attempts; up to 5 red classes of theirs, when nothing else
  failed, are run once more ("passed on retry" row in the report); our tests (`mod.*`) are never retried, and a test
  set (phone or watch) without our tests is red. Before, 11 of 59 runs were re-run by hand (17–24.11).

## mod-1.3.3-11 — 2026-11-24 (based on JugglucoNG 1.3.3-Alpha; candidate 11, the first GitHub release; the same
code as candidate 10, checked on a phone and a watch)
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
- 2026-11-23 — watch block for a Wear OS watch with JugglucoNGmod on the phone and the watch: own watch face
  "JugglucoNGmod" (whole circle; glucose, arrow, change over 5 min, age in seconds, 1/3/6 h chart with hour lines and
  the forecast, time, date, phone and watch battery, the pump's active insulin with CareLink; a reading on the face
  0–1 s after the phone; chart length in the "Graph hours" app entry; the author's face is "JugglucoNGmod classic");
  "Snooze alarm" app entry for a watch button (snoozes on the watch and the phone); the phone's alarm settings sent to
  the watch, on the watch vibration only, own watch alarm screen with large buttons; glucose missed while away from
  the phone filled in from the phone (up to 72 h); heart rate and steps from the watch (Health Services) on the
  phone's "JugglucoNGmod watch" screen. The author's files are not changed. On the owner's phone and watch since
  2026-11-18 (candidates 4 and 5, computer builds 6–8, candidates 9 and 10 with the 1.3.3-Alpha); the tag comes with
  the next candidate from `main`.
- 2026-11-18 — launcher icon of the phone and the watch: the author's icon with an orange "M" badge in the
  bottom-right corner, to tell JugglucoNGmod apart from the official JugglucoNG (`Common/src/mod/res/`: the author's
  foreground picture is layered under our badge, so a new author's icon comes through; the badge stays inside the
  round mask of the watch). On the owner's phone and watch since 2026-11-18 (candidates 4 to 10); the tag comes with
  the next candidate from `main`.

Build and checks:
- 2026-11-24 — GitHub releases are on (repository variable `MOD_PUBLISH_RELEASES`): a candidate of `main` alone
  becomes a normal, latest release with the phone APK, the watch APK and `update-manifest.json` for the in-app
  update, its tag set by the release; candidates with block branches stay on their run page only.
- 2026-11-23 — watch block JVM tests (about 480: shared code, watch, phone side); contract tests that turn red when
  the author changes code we copy (`ModAlarmContractTest`, `HistoryGapFillTest`), what to compare after a merge is in
  `mod/SYNC.md`. `mod/check-apk.py`: our watch components and permissions in the wear APK, both face names, our faint
  dot instead of the author's ongoing-activity icon; the watch APK carries our watch classes, never CareLink's or the
  watch block's phone side. `mod/mod-init.gradle`: Health Services for the watch `mod` build only.
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
