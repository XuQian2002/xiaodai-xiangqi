# 主包全部保留：对弈规则、引擎桥、UI、控制器、枚举与数据类均不被 R8 裁剪。
-keep class daidaixiangqi.app.** { *; }

# 内嵌 sherpa-onnx 离线 TTS 绑定类：native 方法与构造器不可被 R8 剥离/混淆。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
    <init>(...);
}

# 枚举的 values/valueOf 反射入口。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留注解与签名信息，避免反射/序列化相关元数据丢失。
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Kotlin 协程内部（AGP 通常附带消费规则，这里额外保留）。
-keepclassmembers class kotlinx.coroutines.** { *; }
