# Kotlin serialization and PowerSync publish their consumer rules.
# Keep Android entry points used by the manifest and reflection.
-keep class com.daymark.todo.DaymarkApp { *; }
-keep class com.daymark.todo.reminders.ReminderReceiver { *; }
-keep class com.daymark.todo.reminders.BootReceiver { *; }
