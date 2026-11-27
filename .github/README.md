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
merged easily. The author's files have only short marked hooks: 3 lines in 2 files at the moment ([registry of hooks](/mod/HOOKS.md)).

Based on JugglucoNG 1.3.3-Alpha. Latest version: 1.3.3-11 of 2026-11-24 (tag `mod-1.3.3-11`, [release](https://github.com/lbarmen/JugglucoNGmod/releases/latest), see [mod/CHANGES.md](/mod/CHANGES.md)).
What the app itself can do is in the [author's README](/README.md).

## What JugglucoNGmod adds

### MiniMed 7xx pumps over Bluetooth
For the MiniMed 7xx pumps (740G, 770G, 780G). Checked on a 740G with a Samsung phone on Android 14; the 770G and 780G
only by what other projects (OpenMinimed) report - not checked by us. The phone reads the pump directly over
Bluetooth, in place of the MiniMed Mobile app: no cloud and no sign-in. Read only - the app sends the pump no doses
and no settings, only read requests (the pairing adds the phone to the pump's list of devices); make dose decisions
by the pump's own screen.
- the journal gets the pump boluses and the carbs from the pump's bolus calculator;
- the chart shows the real pump basal, normal and temporary; a pump stop is a gap with the note "Pump stopped";
- the "IoB" chip on the main screen and our watch face show the pump's own active insulin;
- the records go to Nightscout through the author's code, like any journal entry (his "send insulin and carbs"
  setting);
- the "Pump" window (in the settings next to Nightscout, and a shortcut on the app icon): a pairing wizard with the
  steps on the pump, the link state, the pump's reservoir, battery, active insulin and basal with their age,
  "Permanent operation", how soon to warn (20, 30 or 60 min), diagnostics with the link journal;
- notifications: "No pump data for over N min" (30 by default), "Pump records are not saved", "The phone forgot the
  pump".

MiniMed Mobile has to be unpaired from the pump: then the CareLink cloud (reports for the doctor, family following the
data) gets no more data and the pump's notifications no longer come to the phone - the pump beeps by itself. The
pump's key database is downloaded from OpenMinimed's public page when the pairing starts, or loaded from a file.
SmartGuard auto mode and filling the reservoir are not checked yet: their records may not reach the journal, or may
come in as boluses. Without a paired pump nothing changes except the "Pump" row in the settings. The earlier CareLink
cloud data source is removed; what it left on the phone is removed by itself after the update.
For now do not delete pump records by hand, neither in the app's journal nor in Nightscout: the basal may come back as
a second record, or sending treatments to Nightscout may stop.

### Galaxy Watch (Wear OS)
For a Wear OS watch with JugglucoNGmod on both the phone and the watch (checked on a Galaxy Watch 5 Pro).
- Own watch face "JugglucoNGmod" on the whole circle: glucose in range color, trend arrow and change over 5 min,
  reading age in seconds, a 1, 3 or 6 h chart with hour lines and the forecast, time, date, phone and watch battery;
  active insulin from the pump. A new reading is on the face 0–1 s after the phone has it. The chart
  length is set in the "Graph hours" app entry. The author's face stays, named "JugglucoNGmod classic".
- "Snooze alarm": an app entry without a screen, meant for a watch button; it snoozes the alarm going on, on the
  watch and on the phone.
- The phone's alarm settings come to the watch (duration, repeat, vibration, snooze, active hours, mode,
  thresholds). On the watch an alarm only vibrates; own alarm screen with large buttons one above the other.
- Glucose missed while the watch was away from the phone is filled in from the phone (up to 72 h).
- Heart rate (a per-minute average) and steps from the watch go to the phone: the "JugglucoNGmod watch" screen, a
  shortcut on the app icon.

The watch is updated by hand: install the watch APK as a file or via adb; the watch has no in-app update.

## How it differs from the official app

| What | JugglucoNG 1.3.2-Alpha | JugglucoNGmod | Since |
|---|---|---|---|
| Wear OS watch face | cannot be added: "String resource ID #0x0" | can be added and works (checked on a watch, 2026-11-15) | 1.3.2-2 |
| Install | — | own package name `tk.glucodata.ng.mod`: installs next to the official app, not over it | 1.3.2-2 |
| Name | JugglucoNG | "JugglucoNGmod" in 16 languages, watch face "JugglucoNGmod" | 1.3.2-2 |
| Icon (phone and watch) | the author's icon | the author's icon with an orange "M" badge in the corner: easy to tell apart from the official app (checked on a phone and a watch, 2026-11-18 – 2026-11-23) | 1.3.3-11 |
| Signing | upstream key | own key, fingerprint in [`mod/mod-cert.sha256`](/mod/mod-cert.sha256) | 1.3.2-2 |
| In-app update | — | from the releases of this repository (the first is 1.3.3-11): Settings → "App updates" → "Check automatically"; phone only, the watch is updated by hand | 1.3.2-2 |
| Clone mode, BloodControl service (for AiDEX) | present | do not work: they need secrets of the JugglucoNG author | 1.3.2-2 |
| Home-screen widget | two widgets: a card with the number, arrow and a 6 h chart (not transparent), and the number with arrow; no change over 5 min, no reading age | a third widget "JugglucoNGmod" in the xDrip look: transparent, number in range color, arrow, change over 5 min, "N min ago", 3 h of 5-minute dots; own settings window with a live preview (checked on a phone, 2026-11-21) | 1.3.3-10 |
| MiniMed 7xx pump (740G, 770G, 780G) | — | checked on a 740G only (770G, 780G - by what OpenMinimed reports, not checked by us); read directly over Bluetooth, read only, no cloud: pump boluses and carbs in the journal, the real basal on the chart, the pump's active insulin in "IoB", sent to Nightscout; own "Pump" window with a pairing wizard, and warnings | |
| Own watch face | the author's face | own face "JugglucoNGmod" on the whole circle: glucose, arrow, change, age in seconds, 1/3/6 h chart with hour lines and forecast, both batteries, active insulin from the pump; the author's face is "JugglucoNGmod classic" (checked on a watch, 2026-11-18 – 2026-11-23) | 1.3.3-11 |
| Alarms on the watch | only on/off of each alarm comes from the phone; buttons 52 dp | the phone's alarm settings come to the watch; vibration only; own alarm screen with large buttons; "Snooze alarm" for a watch button snoozes on the watch and the phone (checked on a watch, 2026-11-21) | 1.3.3-11 |
| Heart rate and steps | — | from the watch to the phone: the "JugglucoNGmod watch" screen (checked on a watch, 2026-11-20) | 1.3.3-11 |

Two apps on one sensor interfere with each other: connect a sensor to one of them only.

A row is added to the table when the change has been checked on a phone or a watch and merged into `main`.
All changes by version and with dates, including the build and checks, are in [mod/CHANGES.md](/mod/CHANGES.md).

## Download
[The latest release](https://github.com/lbarmen/JugglucoNGmod/releases/latest): `JugglucoNGmod-<version>.apk` for the phone, `JugglucoNGmod-<version>-wear.apk` for
a Wear OS watch. Each release is built in GitHub Actions from the tagged commit; the SHA-256 of the signing
certificate is in the release text and in [`mod/mod-cert.sha256`](/mod/mod-cert.sha256).
- Phone: open the APK on the phone and allow installing from that app. It installs next to the official
  JugglucoNG, not over it. Later versions: Settings → "App updates" → "Check automatically" - the app tells you
  about a new release; nothing downloads or installs on its own.
- Watch: there is no in-app update on the watch. Install the watch APK with adb over "Wireless debugging" of the
  watch (`adb install -r JugglucoNGmod-<version>-wear.apk`); the phone and the watch should run the same version.

## Questions, bugs and ideas
Write them in [Issues](https://github.com/lbarmen/JugglucoNGmod/issues) of this repository. If a problem also happens
in the official JugglucoNG app, report it to [JugglucoNG Issues](https://github.com/ctqvva/JugglucoNG/issues), checked
with the official app.

Issues are public. Do not post glucose values, insulin doses, sensor serial numbers or your Nightscout address: cut
them out of logs and screenshots.

## More
- [mod/CHANGES.md](/mod/CHANGES.md) — changes by version.
- [mod/README.md](/mod/README.md) — build, version number, closed-source vendor libraries in the APK.
- [mod/HOOKS.md](/mod/HOOKS.md) — changes in the author's files (2 hooks, 3 lines at the moment).

## Credits and license
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) and contributors.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG grew out of it.
- Thanks to them for the open code.

License: GPL-3.0, as the original ([LICENSE.txt](/LICENSE.txt)). The sources of each version are the tag `mod-…`
in this repository.
