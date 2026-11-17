# Release (R8) keep rules. Manifest components (Application, Activity,
# MediaSessionService) are kept automatically by AGP. Everything below
# is referenced by reflection or by name and would otherwise be
# stripped or renamed.

# WorkManager instantiates workers by reflection. Keep every Worker
# and CoroutineWorker subclass in the app.
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# kotlinx-serialization: keep the generated serializers and the
# model classes they decode into. SyncService decodes server rows
# by reified type, and ContentRepository decodes the search index.
-keepattributes *Annotation*, InnerClasses, Signature
-keep class com.paymentstraininghub.app.data.model.** { *; }
-keep class com.paymentstraininghub.app.sync.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-dontnote kotlinx.serialization.**

# Enums read by name or by valueOf in billing and gating code.
-keepclassmembers enum com.paymentstraininghub.app.** { *; }

# Play Integrity: keep the Integrity API surface used by
# IntegrityCheck. The library ships consumer rules, but the token
# request/response types are named explicitly so an upstream
# rules change cannot strip them.
-keep class com.google.android.play.core.integrity.** { *; }

# Obfuscation stays ON for release builds (the default with
# proguard-android-optimize.txt): class and member names are
# renamed and packages repackaged, which raises the bar for
# patching the premium gate out of a modified build. Never add
# -dontobfuscate or -dontoptimize: the anti-piracy design depends
# on release obfuscation. The security package is deliberately
# NOT kept: its classes are reachable from BillingManager and
# AppNavGraph, so R8 keeps them but still renames them.

# OkHttp, BillingClient, Media3 and security-crypto ship their own
# consumer ProGuard rules; nothing extra is needed for them.
