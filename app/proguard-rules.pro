# Preserve the bundled scanner and its reflectively discovered component registrars.
# Keep these rules conservative until the minified build is verified on devices.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

# Optional dependencies referenced by the bundled vCard/template libraries.
# The application does not use their servlet, scripting or desktop integrations.
-dontwarn javax.servlet.**
-dontwarn jakarta.servlet.**
-dontwarn org.python.**
-dontwarn org.zeroturnaround.**
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.avalon.**
-dontwarn org.dom4j.**
-dontwarn org.jaxen.**
-dontwarn org.jdom.**
-dontwarn org.mozilla.javascript.**
-dontwarn com.sun.**
-dontwarn javax.xml.bind.**
-dontwarn freemarker.**
-dontwarn ezvcard.**

# FreeMarker loads implementation classes by name.
-keepnames class freemarker.** { *; }
