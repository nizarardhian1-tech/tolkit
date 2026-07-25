# ====================================================================
# MONTOOLKIT PROGUARD RULES - RELEASE BUILD PROTECTION
# ====================================================================

# --- PACKAGE UTAMA APP & NATIVE JNI ---
-keep class com.mondns.app.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn kotlin.**

# --- BOUNCY CASTLE (KEYSTORE GENERATOR & APK SIGNER) ---
# Wajib agar R8 tidak menghapus kelas algoritma SPI (SHA256WithRSA, RSA, dll)
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- APKSIG (GOOGLE APK SIGNER SCHEME V1/V2/V3/V4) ---
-keep class com.android.apksig.** { *; }
-keepclassmembers class com.android.apksig.** { *; }
-keepattributes *Annotation*
-dontwarn com.android.apksig.**

# --- MANIFEST-EDITOR (NPATCH / LSPOSED) ---
-keep class com.wind.meditor.** { *; }
-keep class com.wind.meditor.utils.** { *; }
-dontwarn com.wind.meditor.**

# --- LUAJ (LUA ENCRYPTOR) ---
-keep class org.luaj.vm2.** { *; }
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn java.awt.**

# --- SHIZUKU SERVICE & PROVIDER ---
-keep class rikka.shizuku.** { *; }
-keep class * extends rikka.shizuku.ShizukuProvider { *; }
-dontwarn rikka.shizuku.**

# --- ASM & BCEL ---
-keep class org.ow2.asm.** { *; }
-dontwarn org.ow2.asm.**
-keep class org.apache.bcel.** { *; }
-dontwarn org.apache.bcel.**

# --- RETROFIT, GSON & ROOM DATABASE ---
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# --- GLIDE ---
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**