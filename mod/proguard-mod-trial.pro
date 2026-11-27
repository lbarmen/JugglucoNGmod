# JugglucoNGmod: R8 rule of the trial entry point (the made-up Bluetooth pump, series "pump over Bluetooth" part 2,
# part P), added to build type `mod` by mod/mod-init.gradle always.
#
# The trial entry point lives in Common/src/modTrial/java, compiled into the phone `mod` build only with
# -PmodRelease=0 (test builds, emulator only). ModPumpStarter finds it by name (Class.forName + install(Context)), so
# its name and that method must stay. In a candidate (-PmodRelease=N, N >= 1) the class does not exist and this rule
# matches nothing; mod/check-apk.py checks that no trial marker (strings, the pump's class descriptor) is left in its
# dex.
-keep class tk.glucodata.mod.pumpbt.trial.PumpBtTrialEntry {
    public static void install(android.content.Context);
}
