# JugglucoNGmod

**Unofficial build of [JugglucoNG](https://github.com/ctqvva/JugglucoNG)** for Android phones and Wear OS watches.
It is not made by the author of JugglucoNG. Do not report problems of this build to the JugglucoNG or Juggluco authors.

> [!WARNING]
> **Not a medical device.** The app is not certified. Readings and alarms may be wrong or may not arrive.
> Base treatment decisions on your own glucose meter and on your doctor's advice. Use at your own risk
> (GPL-3.0, sections 15–16).

## What it is
The same JugglucoNG plus a few changes of our own. The author's code is not rewritten: everything of ours lives in our
own files (the folder [`mod/`](/mod) and the resources of the `mod` build type), so that JugglucoNG updates can be
merged easily. Not a single line of the author's files is modified at the moment ([registry of hooks](/mod/HOOKS.md)).

Based on JugglucoNG 1.3.2-Alpha. Latest version: 1.3.2-2 of 2026-11-15 (built under the former name, see [mod/CHANGES.md](/mod/CHANGES.md)).
What the app itself can do is in the [author's README](/README.md).

## How it differs from the official app

| What | JugglucoNG 1.3.2-Alpha | JugglucoNGmod | Since |
|---|---|---|---|
| Wear OS watch face | cannot be added: "String resource ID #0x0" | can be added and works (checked on a watch, 2026-11-15) | 1.3.2-2 |
| Install | — | own package name `tk.glucodata.ng.mod`: installs next to the official app, not over it | 1.3.2-2 |
| Name and icon | JugglucoNG | "JugglucoNGmod" in 16 languages, the author's icon on another background color, watch face "JugglucoNGmod" | 1.3.2-2 |
| Signing | upstream key | own key, fingerprint in [`mod/mod-cert.sha256`](/mod/mod-cert.sha256) | 1.3.2-2 |
| In-app update | — | from the releases of this repository (none published yet) | 1.3.2-2 |
| Clone mode, BloodControl service (for AiDEX) | present | do not work: they need secrets of the JugglucoNG author | 1.3.2-2 |

Two apps on one sensor interfere with each other: connect a sensor to one of them only.

A row is added to the table when the change has been checked on a phone or a watch and merged into `main`.
All changes by version and with dates, including the build and checks, are in [mod/CHANGES.md](/mod/CHANGES.md).

## Download
There are no ready releases yet.

## Questions, bugs and ideas
Write them in [Issues](https://github.com/lbarmen/JugglucoNGmod/issues) of this repository. If a problem also happens
in the official JugglucoNG app, report it to [JugglucoNG Issues](https://github.com/ctqvva/JugglucoNG/issues), checked
with the official app.

Issues are public. Do not post glucose values, insulin doses, sensor serial numbers or your Nightscout address: cut
them out of logs and screenshots.

## More
- [mod/CHANGES.md](/mod/CHANGES.md) — changes by version.
- [mod/README.md](/mod/README.md) — build, version number, closed-source vendor libraries in the APK.
- [mod/HOOKS.md](/mod/HOOKS.md) — changes in the author's files (none at the moment).

## Credits and license
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) and contributors.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG grew out of it.
- Thanks to them for the open code.

License: GPL-3.0, as the original ([LICENSE.txt](/LICENSE.txt)). The sources of each version are the tag `mod-…`
in this repository.
