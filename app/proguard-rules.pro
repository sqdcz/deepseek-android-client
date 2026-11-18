# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.shipyard.deepchat.**$$serializer { *; }
-keepclassmembers class com.shipyard.deepchat.** {
    *** Companion;
}
-keepclasseswithmembers class com.shipyard.deepchat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.shipyard.deepchat.data.** { *; }

# ViewModel 由反射构造，必须保留类名与构造函数
-keep class com.shipyard.deepchat.vm.ChatViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class com.shipyard.deepchat.MainActivity { *; }
-keep class com.shipyard.deepchat.App { *; }

# Compose / Coil 反射入口
-keep class coil.** { *; }
-dontwarn coil.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-dontwarn coil.**
