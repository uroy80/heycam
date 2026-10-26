# ML Kit instantiates its component registrars reflectively. Under AGP 9 / R8 full mode a plain -keep no longer keeps
# the no-argument constructor, and every ML Kit client then fails at runtime (verified on API 37; PLAN D7).
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
