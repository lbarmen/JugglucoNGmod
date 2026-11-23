# Log of JugglucoNG merges (mod/sync-merge.sh)

| date | their release | version | commits | conflicts | risk flags | decision | review, min |
|---|---|---|---|---|---|---|---|
| 2026-11-21 | 1.3.3-Alpha | 1.3.3-Alpha | 272 | 0 (by hand) | sensor 1, alarms 11, forecast 5, journal and database 2, Nightscout 7, watch 10, build 9, native code 13, sensor (driver dependency) 6 | merge: 0 conflicts, no hooks touched; red 19.11 = their new Spanish locale with app_name JugglucoNG (fixed: our values-es in mod and wearMod, check-apk now checks watch_face_name too); red 20.11 = Maven repositories unreachable from the runner (not the code) | 25 |
