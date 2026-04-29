# Ktor
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }

# Serialization
-keepattributes *Annotation*, EnclosingMethod, Signature
-keepnames class kotlinx.serialization.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Hilt
-keep class com.google.dagger.hilt.** { *; }
-keep class dagger.hilt.** { *; }

# Bridge Specific
-keep class com.forge.bridge.data.model.** { *; }
-keep class com.forge.bridge.IForgeBridge { *; }
