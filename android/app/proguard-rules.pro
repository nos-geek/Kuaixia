# 快夏 Kuaixia — ProGuard 规则（Phase 1 未开启混淆，预留）
# kotlinx.serialization 需要保留 @Serializable 类的合成成员
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class com.kuaixia.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.kuaixia.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
