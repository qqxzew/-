# Add project specific ProGuard rules here.
-keepattributes *Annotation*, Signature, InnerClasses
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.activation.**
-dontwarn java.awt.**
