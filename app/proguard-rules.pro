# kotlinx.serialization : conserver les sérialiseurs générés.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Télémétrie Google retirée volontairement (voir app/build.gradle.kts) : le SDK Cast y fait
# référence pour ses statistiques d'usage. Ces classes sont absentes à dessein.
-dontwarn com.google.android.datatransport.**
-dontwarn com.google.firebase.encoders.**

# dadb (ADB) : classes chargées dynamiquement par okio / BouncyCastle optionnel.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
