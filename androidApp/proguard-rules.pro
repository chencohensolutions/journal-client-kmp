# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ML Kit's code scanner (the QR codes at enrollment and for swaps) finds its component registrars by name and makes
# them by reflection: without their constructors it logs NoSuchMethodException and the scanner doesn't start.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
# MediaPipe's image embedder reads its options protos by field name (protobuf-lite reflection): renamed fields fail
# with "Field platform_ ... not found" and similarity falls back to dHashes only.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# ONNX Runtime's native library binds its Java classes and methods by name over JNI.
-keep class ai.onnxruntime.** { *; }
# Unused MediaPipe framework APIs reference protos that aren't shipped.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
# MediaPipe logs with Flogger, which finds its caller on the stack by the logger's class name: renamed, Graph's
# static initializer throws "no caller found on the stack".
-keep class com.google.common.flogger.** { *; }
