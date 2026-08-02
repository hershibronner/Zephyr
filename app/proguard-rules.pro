# Room generates code reflectively referenced at runtime.
-keep class app.zephyr.fitness.data.db.** { *; }

# kotlinx.serialization keeps generated serializers on the companion.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.zephyr.fitness.** {
    *** Companion;
}
-keepclasseswithmembers class app.zephyr.fitness.** {
    kotlinx.serialization.KSerializer serializer(...);
}
