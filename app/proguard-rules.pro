# ============================================================
# Shizuku UserService 保护规则
# Shizuku 进程通过 Class.forName + getConstructor(Context) 反射实例化
# ShizukuShellService，R8 不允许移除/混淆该类及其构造函数。
# ============================================================

-keep class me.rerere.rikkahub.shizuku.ShizukuShellService {
    public <init>(android.content.Context);
    *;
}

-keep class me.rerere.rikkahub.shizuku.IShizukuShellService { *; }
-keep class me.rerere.rikkahub.shizuku.IShizukuShellService$Stub { *; }
-keep class me.rerere.rikkahub.shizuku.IShizukuShellService$Stub$Proxy { *; }
-keepclassmembers class * implements me.rerere.rikkahub.shizuku.IShizukuShellService { *; }

# Shizuku SDK（权限监听、bindUserService、UserServiceArgs 等全部需要反射/跨进程）
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }

# 保留注解与泛型签名（AIDL/Binder 跨进程依赖）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
