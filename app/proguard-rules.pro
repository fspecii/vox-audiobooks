# JNI-bound / reflective libraries: keep them intact
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class org.libtorrent4j.** { *; }
-keep class com.swig.** { *; }
-keep class com.yausername.** { *; }
-keep class io.github.junkfood02.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.objenesis.**
-dontwarn javax.annotation.**
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# our own models are read via org.json, but UpdateReceiver is referenced from the manifest
-keep class dev.vox.audio.data.UpdateReceiver { *; }
-keep class dev.vox.audio.reader.NarrationService { *; }
