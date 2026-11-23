# BouncyCastle + SPAKE2 are used through reflection/JNI-style entry points.
-keep class org.bouncycastle.** { *; }
-keep class com.flyfish233.crypto.spake2.** { *; }
# Conscrypt keying-material export is invoked reflectively for ADB pairing.
-keep class org.conscrypt.** { *; }
-dontwarn org.bouncycastle.**
