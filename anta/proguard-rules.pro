# osmdroid picks its tile providers and overlays by class name.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# OkHttp's optional platform integrations are not on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Google sign-in: Credential Manager finds its Play services provider by class name.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
