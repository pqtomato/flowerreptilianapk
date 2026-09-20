# 个人自用应用，release 也不做混淆；这里留空即可。
# 若将来开启 minifyEnabled，需要注意：
# - keep Jackson 的数据绑定类
# - keep WorkManager 的 Worker 子类（由反射实例化）
-keep class com.wanhong.tender.crawl.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn org.jsoup.**
