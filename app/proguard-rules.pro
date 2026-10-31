# Giữ nguyên các lớp JNI của ONNX Runtime khi build bản Release
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
