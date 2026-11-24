# JugglucoNGmod: R8 rules for build type `mod`, added by mod/mod-init.gradle after upstream's own files.
#
# Drawable XML <drawable class="..."> is inflated by class name (DrawableInflater) and needs the public
# no-arg constructor. Upstream keeps only the class (Common/proguard-rules.my: -keep class ...ComplicationDrawable);
# with AGP's optimized resource shrinking R8 also keeps the constructor itself, but wear `mod` is built with the
# previous shrinker (mod/gradlew-mod.sh), which does not: the watch face crashed with
# NoSuchMethodException: ComplicationDrawable.<init> [] on complication_*_style.xml.
# mod/check-apk.py checks every class named in drawable XML of the wear sources against the wear dex.
-keep class androidx.wear.watchface.complications.rendering.ComplicationDrawable { public <init>(); }

# CareLink (phone): our exception classes keep their names, so the class written to upstream's debug log and shown in
# the CareLink window stays readable (critic 4e, N3). Names only - the classes may still be removed when unused; the
# watch has none of them (mod/check-apk.py: no tk.glucodata.mod.carelink classes in the wear dex).
-keepnames class tk.glucodata.mod.carelink.**Exception
