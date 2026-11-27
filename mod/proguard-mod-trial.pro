# JugglucoNGmod: R8 rules of the trial entry points (CareLink, part 4p; the made-up Bluetooth pump, series part 2,
# part P), added to build type `mod` by mod/mod-init.gradle always.
#
# The trial entry points live in Common/src/modTrial/java, compiled into the phone `mod` build only with
# -PmodRelease=0 (test builds, emulator only). ModCareLinkStarter and ModPumpStarter find them by name
# (Class.forName + install(Context)), so their names and that method must stay. In a candidate (-PmodRelease=N, N >= 1)
# the classes do not exist and these rules match nothing; mod/check-apk.py checks that no trial marker (strings, the
# pump's class descriptor) is left in its dex.
-keep class tk.glucodata.mod.carelink.trial.CareLinkTrialEntry {
    public static void install(android.content.Context);
}
-keep class tk.glucodata.mod.pumpbt.trial.PumpBtTrialEntry {
    public static void install(android.content.Context);
}
