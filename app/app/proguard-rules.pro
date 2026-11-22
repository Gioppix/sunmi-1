# The "call" command looks printer methods up by name through reflection.
-keep class woyou.aidlservice.jiuiv5.** { *; }
-keep class com.sunmi.trans.TransBean { *; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
