# R8 rules for the release build.

# kotlinx.serialization: keep generated serializers and @Serializable companions.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Exceptions, Signature, RuntimeVisibleAnnotations
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class **
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# sshj / BouncyCastle: algorithms are resolved by name through JCA/reflection.
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**
-dontwarn net.schmizz.**
-dontwarn com.jcraft.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# SLF4J simple binding is discovered at runtime.
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Ktor (CIO engine, WebSockets) + coroutines debug probes.
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**
