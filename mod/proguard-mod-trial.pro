# JugglucoNGmod: R8 rule of the CareLink trial (part 4p), added to build type `mod` by mod/mod-init.gradle always.
#
# The trial entry point lives in Common/src/modTrial/java, compiled into the phone `mod` build only with
# -PmodRelease=0 (test builds, emulator only). ModCareLinkStarter finds it by name (Class.forName + install(Context)),
# so its name and that method must stay. In a candidate (-PmodRelease=N, N >= 1) the class does not exist and this
# rule matches nothing; mod/check-apk.py checks that no trial marker string is left in its dex.
-keep class tk.glucodata.mod.carelink.trial.CareLinkTrialEntry {
    public static void install(android.content.Context);
}
