# Optimizations: Adding optimization introduces
# certain risks, since for example not all optimizations performed by
# ProGuard works on all versions of Dalvik.  The following flags turn
# off various optimizations known to have issues, but the list may not
# be complete or up to date. (The "arithmetic" optimization can be
# used if you are only targeting Android 2.0 or later.)  Make sure you
# test thoroughly if you go this route.
# handy for debugging

#-dontobfuscate - please, don't use this

-dontoptimize
#-keepattributes
-keepattributes Exceptions,InnerClasses,Signature,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,Synthetic,MethodParameters,LocalVariableTable,LocalVariableTypeTable

# Note: inlining methods may allow for small number of codepoints
# without line numbers

# DO NOT USE - Proguard sucks at handling these:
#-keepattributes MethodParameters,LocalVariableTable,LocalVariableTypeTable

# code/simplification/cast - not recognized by verifier until 4.4
# field optimizations - VERY tricky to support, too much side-effects
# code/allocation/variable - can make parameter lists unsuitable for reflection ?!
# class merging - turns stack traces into crap
# interface merging - not supported
# see also http://osdir.com/ml/AndroidDevelopers/2009-07/msg03545.html
#-optimizations !code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable

#-optimizationpasses 5

-repackageclasses gsh

-allowaccessmodification

# no longer strictly necessary since 2.0 (see also https://code.google.com/p/android/issues/detail?id=2422)
# but makes stacktraces slightly better
-useuniqueclassmembernames

-adaptresourcefilenames
-adaptresourcefilecontents META-INF/services/**

# Obfuscate our own classes. Don't shrink them: lots of codegen make it troublesome,
# and we don't have much unused code in our repository anyway
#-keep,allowobfuscation,allowoptimization class net.sf.aria2.**,rx.**,dagger.** { *; }
#-keep,allowobfuscation,allowoptimization interface net.sf.aria2.**,rx.**,dagger.** { *; }
#-keep,allowobfuscation,allowoptimization enum net.sf.aria2.**,rx.**,dagger.** { *; }

# Do not touch anything else!
-keep class !android.support.**,!javax.xml.**,!org.apache.xerces.**,!org.apache.ivy.**,!javax.swing.event.EventListenerList,** { *; }

-keep,allowoptimization class org.apache.xerces.parsers.XIncludeAwareParserConfiguration,org.apache.xerces.impl.dv.dtd.DTDDVFactoryImpl

-keep,allowshrinking class android.support.** { *; }

-keep,allowobfuscation,allowoptimization class !org.apache.ivy.util.url.IvyAuthenticator,org.apache.ivy.** {
    public protected *;
}

-keepclassmembers class org.apache.ivy.** {
    public static final *** INSTANCE;
}

-keep,allowoptimization class !org.apache.ivy.util.url.IvyAuthenticator,org.apache.ivy.** {
    <methods> ;
}

#-whyareyoukeeping class org.apache.ivy.util.url.IvyAuthenticator

# Google Services (support has full Proguard config in aar)
# http://developer.android.com/google/play-services/setup.html#Proguard
# http://developer.fyber.com/content/android/basics/getting-started-sdk/
# and others
-keep class * extends java.util.ListResourceBundle {
    protected java.lang.Object[][] getContents();
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
    public static ** CREATOR;
    public static final android.os.Parcelable$Creator *;
    public static android.os.Parcelable$Creator *;
}

-keepnames class * implements android.os.Parcelable
-keepnames class !javax.xml.**,* implements java.lang.Exception

-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# XXX: an example of situation, when routinely relying on reflection bites in the ass
-assumenosideeffects public class org.apache.ivy.util.url.IvyAuthenticator {
    public static void install();
}