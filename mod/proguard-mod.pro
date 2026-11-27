# JugglucoNGmod: R8 rules for build type `mod`, added by mod/mod-init.gradle after upstream's own files.
#
# Drawable XML <drawable class="..."> is inflated by class name (DrawableInflater) and needs the public
# no-arg constructor. Upstream keeps only the class (Common/proguard-rules.my: -keep class ...ComplicationDrawable);
# with AGP's optimized resource shrinking R8 also keeps the constructor itself, but wear `mod` is built with the
# previous shrinker (mod/gradlew-mod.sh), which does not: the watch face crashed with
# NoSuchMethodException: ComplicationDrawable.<init> [] on complication_*_style.xml.
# mod/check-apk.py checks every class named in drawable XML of the wear sources against the wear dex.
-keep class androidx.wear.watchface.complications.rendering.ComplicationDrawable { public <init>(); }
