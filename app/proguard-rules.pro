# Shizuku 按类名反射实例化 UserService，类名和无参构造都不能被混淆或移除
-keep class com.elderguard.app.privileged.ShellService { <init>(); }
