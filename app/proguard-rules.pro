# heycam R8 rules (PLAN D7). Libraries ship their own consumer rules; these cover what theirs miss.

# ML Kit creates its component registrars reflectively from manifest meta-data. It pins firebase-components 16.1.0,
# whose rule keeps the classes but not their no-arg constructors, and AGP 9's strict full mode
# (android.r8.strictFullModeForKeepRules) strips them: every ML Kit call then crashes in release builds
# (NoSuchMethodException at startup, NullPointerException on first use). Verified on API 37 by the toolchain report.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }

# PDFBox-Android refers to an optional JPEG 2000 decoder and to BouncyCastle; heycam ships neither.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
