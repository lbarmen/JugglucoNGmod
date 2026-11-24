# Hooks into JugglucoNG files (MOD-HOOK)

Everything of ours lives in our own files. If a change in the author's file cannot be avoided, it is 1–3 lines marked
`MOD-HOOK:<id>` in the code and a row here. The merge script compares the marks in the code with this table in both
directions.

| id | file | why | how to revert on conflict |
|----|------|-----|---------------------------|
| carelink-settings | `Common/src/mobile/java/tk/glucodata/ui/ExpressiveSettingsScreen.kt` | data sharing section (`sharing_group`): the "CareLink" row - our `ModCareLinkEntry(sharingColor)` draws their `SettingsItem` and opens the CareLink window; in their builds, without the window in the manifest, it draws nothing | take their version of the file and insert again the one line `tk.glucodata.mod.carelink.ModCareLinkEntry(sharingColor) // MOD-HOOK:carelink-settings` into the sharing section between items that are always visible (after the closing `}` of the `if (showLumiflex)` block or next to a neighbouring item), not inside someone else's `if`; if the section was renamed - where their Nightscout item is; `sharingColor` is their local `val` of the section |
| carelink-tempbasal | `Common/src/mobile/java/tk/glucodata/data/journal/JournalTreatmentUploader.kt` | function `isSendableType`: the pump basal from CareLink (`TEMP_BASAL` entries with our source `HEALTH_CONNECT`) goes to Nightscout as `Temp Basal`; AAPS and Nightscout entries are mirrors and still do not go. It lets through every `TEMP_BASAL` of a non-mirror source, not only ours: today only CareLink makes them (their interface creates no `TEMP_BASAL`, `JournalModels.kt`). On every merge of their release check that they did not add a manual or imported `TEMP_BASAL` (such rows of the last 30 days would go to Nightscout; one without a duration refused by the server stops the whole upload) - if they did, narrow the hook to our keys (`sourceRecordId` starting with `carelink:`) | take their version of the function and append to the list of types `\|\| type == JournalEntryType.TEMP_BASAL // MOD-HOOK:carelink-tempbasal`; if the function was renamed or removed - find where sending picks entry types and append it there; check - the contract test `CareLinkJournalContractTest` |

The `mod` build type is attached by an init script (`mod/mod-init.gradle`), resources live in our own folders
`Common/src/mod/` and `Common/src/wearMod/`: these are not hooks.

Check: `python3 mod/check-hooks.py`: marks and the table in both directions, a budget of at most 15 of their files and
60 lines (spec 3.3 item 5), and every changed line of their file must be within 3 lines of a `MOD-HOOK` mark; a deleted
file of theirs, a new file outside our paths, a binary file, a file mode change - red. Our paths are in
`mod/our-paths.txt`. `check-apk.py` runs it in every build (test and candidate), so a block branch is checked before it
reaches the phone.
