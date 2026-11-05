-keep class io.kton.connect.** { *; }
-keepclassmembers class io.kton.connect.** {
    *** Companion;
}
-if class io.kton.connect.** {
    static ** serializer(...);
}
-keepclassmembers class io.kton.connect.** {
    kotlinx.serialization.KSerializer serializer(...);
}
