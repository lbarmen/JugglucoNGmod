# JugglucoNGmod

**Unofficial build of [JugglucoNG](https://github.com/ctqvva/JugglucoNG).** It is not made by the author of JugglucoNG.
Do not report problems of JugglucoNGmod to the JugglucoNG or Juggluco authors. Discussions (Issues) are switched off in
this repository.

This page is about the build and the structure. What it is and how it differs from the official app is on the
[front page](/.github/README.md), changes by version are in [CHANGES.md](CHANGES.md).

**Not a medical device.** The app is not certified. Readings and alarms may be wrong or may not arrive.
Base treatment decisions on your own glucose meter and on your doctor's advice. Use at your own risk (GPL-3.0,
sections 15–16).

## What it is
The same JugglucoNG for an Android phone and a Wear OS watch, plus a few changes of our own. The author's code is not
rewritten: everything of ours lives in our own files, so that JugglucoNG updates can be merged easily.

There is one table of differences from the official app, on the [front page](/.github/README.md): a new difference is
added there.

## Versions
- A version is a tag `mod-<JugglucoNG version>-<our number>` on `main`, for example `mod-1.3.2-3`; it is set after a
  check on a phone or a watch.
- The version code in the APK is theirs × 100 + our number (1.3.2 → 103201, 103202…), for the watch + 1 000 000.
- Own signature, the certificate fingerprint is [mod-cert.sha256](mod-cert.sha256). In-app updates look at the
  releases of this repository. A candidate becomes a release only after a check on the owner's phone, with the
  very files of its build run (`mod/publish-candidate.py`, no rebuild).

## Closed-source libraries
The phone APK contains 34 closed-source vendor libraries (`.so`, ≈ 44 MB; for example, for FreeStyle Libre 3).
Their sources are not available, each has its own license, and it is not GPL. As with the JugglucoNG author, they are
not in git: the build downloads his published APK
[1.2.3-Alpha](https://github.com/ctqvva/JugglucoNG/releases/tag/1.2.3-Alpha), checks its hash and takes only the needed
files out of it (`scripts/restore-build-inputs.sh`, the list and hashes are in `scripts/dist/build-inputs.json`).
Details and the author's permission: `docs/distribution.md`, section "Private JNI inputs". The Sibionics driver is
written in Kotlin, and the author's build does not put vendor libraries next to it.

## Build
Only through our own wrapper - it attaches the `mod` build type (`mod/mod-init.gradle`), the author's files are not
changed:

    bash mod/gradlew-mod.sh :Common:assembleMobileMod
    bash mod/gradlew-mod.sh :Common:assembleWearMod

The phone and the watch are built in separate runs: the watch needs a parameter for the watch face, the wrapper adds it
by itself.

The build and APK checks are the GitHub Actions job `.github/workflows/mod-build.yml`, the checks are
`mod/check-apk.py`. Before every commit - `bash mod/check-secrets.sh`. Hooks into the author's files (none at the
moment) are in [HOOKS.md](HOOKS.md).
The check knows the owner's own Nightscout address and serial numbers from a personal list - a file outside the
repository, `../secrets-deny.txt`; only the check on the owner's computer reads it, it is not there in GitHub Actions.
Entries are added with `python -X utf8 mod/secrets-deny.py add` (the value is shown neither when typed nor in the
report).

Merging JugglucoNG releases: every morning `.github/workflows/mod-sync.yml` checks their latest release (merge inside
the job, report, build, their tests, our golden alarm runs); the merge itself is done by a chat through the PR
`sync/<release>` - [SYNC.md](SYNC.md). Into `main` - only through a PR with a green `report` check (the ruleset
`mod/ruleset-main.json`). A PR made only of our `.md` files (except `mod/HOOKS.md` and files in `Common/`) is checked
without a build and tests, in about a minute.

## Credits and license
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) and contributors.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG grew out of it.
- Thanks to them for the open code.

License: GPL-3.0, as the original ([LICENSE.txt](../LICENSE.txt)). The sources of each release are the tag `mod-…`
in this repository.
