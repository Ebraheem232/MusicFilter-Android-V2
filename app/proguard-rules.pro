# Required if the Android build is later minified.
-keep class ai.onnxruntime.** { *; }

-keep class org.jtransforms.** { *; }
-keep class pl.edu.icm.jlargearrays.** { *; }
-keep class androidx.media3.** { *; }

-dontwarn pl.edu.icm.jlargearrays.**
-dontwarn sun.misc.Cleaner
-dontwarn com.sun.xml.internal.ws.encoding.soap.SerializationException
-dontwarn org.apache.commons.math3.**
