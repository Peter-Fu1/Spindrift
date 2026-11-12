# TEST APK ONLY: Error Prone annotation metadata references a javac enum unavailable on Android.
# No application or runtime classes are suppressed; production R8 configuration is unchanged.
-dontwarn javax.lang.model.element.Modifier
