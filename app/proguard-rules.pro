# ONNX Runtime's native layer finds its Java types by name through JNI — TensorInfo, the value
# classes it constructs on the way back from a session, their constructors and fields. R8 sees no
# Java caller for them and removes them, and the first inference then aborts the process from
# native code: "JNI DETECTED ERROR IN APPLICATION: java_class == null", after a pending
# ClassNotFoundException nothing in Kotlin can catch. No crash dialog, no stack trace in the app —
# the label reader simply closes the app on a release build while a debug build reads fine.
#
# The package is small; keeping all of it costs a few kilobytes against a class of failure that
# only appears in a signed build.
-keep class ai.onnxruntime.** { *; }

# Glance builds every widget tap's ActionCallback by reflection, from the class name the tap's
# PendingIntent carries: Class.forName(name).getDeclaredConstructor().newInstance(). Glance's own
# consumer rule keeps the class and nothing in it, and R8's full mode keeps no constructor it is
# not told to — with no `new` anywhere in the code, it takes the constructor, onAction and the
# interface out of every callback, and every widget button (the work timer's Start, the water
# portions, the mood faces, the cigarette, the route's Stop) dies in NoSuchMethodException inside
# Glance's receiver. The constructor is enough: a class R8 is told can be instantiated keeps what
# its interface is called through.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Glance tells one kind of widget from another by the class name of its GlanceAppWidget: each
# receiver is filed under `javaClass.canonicalName` of the widget it hands out, and updateAll()
# redraws every widget filed under the caller's name. Unkept, R8 merges six of the widget classes
# into one (steps, water, mood, quick add, smoking, route) and the work timer's into the multi
# widget's, so one kind's update draws its face into the other kinds' slots: a Stop on the work
# widget leaves it wearing the multi widget's water, meal and activity buttons. Kept, each class
# stays itself.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
