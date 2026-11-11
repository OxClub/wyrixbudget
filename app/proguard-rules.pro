# Backup files are serialised with Gson using these classes' field names: they must survive R8.
-keep class com.wyrix.budget.data.local.entity.** { *; }
-keep class com.wyrix.budget.data.backup.BackupSnapshot { *; }
-keepclassmembers enum com.wyrix.budget.** { *; }
# Gson
-keepattributes Signature, *Annotation*
-dontwarn sun.misc.**
