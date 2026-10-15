# 无障碍服务需保留类名（系统通过反射实例化）
-keep class com.marvis.bandremote.service.RemoteAccessibilityService { *; }
-keep class com.marvis.bandremote.service.KeepAliveService { *; }

# 协议常量保留（若后续使用反射解析）
-keep class com.marvis.bandremote.protocol.** { *; }
