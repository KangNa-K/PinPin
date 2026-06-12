# Default ProGuard rules for the app module.
# Add app-specific keep rules below.

# Hilt - generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp

# Room - generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Compose
-keep class androidx.compose.runtime.** { *; }
