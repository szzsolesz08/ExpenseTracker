# Tesseract4Android: JNI looks up these classes and members by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# kotlinx.serialization (navigation routes and backup JSON)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class hu.kiadaskoveto.**$$serializer { *; }
-keepclassmembers class hu.kiadaskoveto.** { *** Companion; }
-keepclasseswithmembers class hu.kiadaskoveto.** { kotlinx.serialization.KSerializer serializer(...); }

# The receipt parser's bundled JSON configuration is decoded with generated serializers.
-keep class hu.kiadaskoveto.parser.config.** { *; }
-keep class hu.kiadaskoveto.parser.dictionary.** { *; }
