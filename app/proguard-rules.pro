# Keep SQLCipher JNI entry points
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# Keep Google Tink primitives
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
