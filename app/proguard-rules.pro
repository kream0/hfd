# R8 shrinks and optimises; names are kept, so the diagnostics' stack traces read as the source.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The JNI (whisper_jni.c) finds its natives by name: the classes holding them stay whole.
-keepclasseswithmembers class app.hfd.** { native <methods>; }

# ONNX Runtime's native side finds its Java classes, fields and constructors by name.
-keep class ai.onnxruntime.** { *; }
