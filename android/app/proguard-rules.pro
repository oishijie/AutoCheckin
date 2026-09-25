# 保留无障碍服务（会被系统通过反射实例化，不能被混淆）
-keep class com.xiaoyao.autocheckin.CheckinAccessibilityService { *; }
-keep class com.xiaoyao.autocheckin.AlarmReceiver { *; }
-keep class com.xiaoyao.autocheckin.MainActivity { *; }
-keep class com.xiaoyao.autocheckin.Step { *; }
-keep class com.xiaoyao.autocheckin.Step$Companion { *; }
