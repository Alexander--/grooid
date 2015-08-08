package net.sf.fakenames.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.os.Process
import android.support.annotation.NonNull
import android.support.annotation.Nullable
import com.stanfy.enroscar.goro.ServiceContextAware
import groovy.transform.CompileStatic
import internal.DexGroovyClassloader
import internal.GentleContextWrapper
import net.sf.fakenames.api.ContextAwareScript
import net.sf.fakenames.db.ScriptContract
import net.sf.fakenames.db.ScriptProvider
import net.sf.fakenames.dispatcher.Utils
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

import java.nio.channels.Channels
import java.util.concurrent.Callable
import java.util.concurrent.Executor

@CompileStatic
final class ParcelableTask implements Callable<Void>, Parcelable, ServiceContextAware {
    private final Uri sourceUri
    private final String targetScript

    private volatile Executor runner
    private volatile Context base

    public ParcelableTask(@NonNull String targetScript, @Nullable Uri sourceUri) {
        this.sourceUri = sourceUri
        this.targetScript = targetScript
    }

    public ParcelableTask(@NonNull Context context, @NonNull String targetScript, @Nullable Uri sourceUri) {
        this.base = context

        this.sourceUri = sourceUri
        this.targetScript = targetScript
    }

    @Override
    void injectServiceContext(Context context) {
        this.base = context
    }

    @Override
    Void call() throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)

        def scriptSource = sourceUri

        def config = new CompilerConfiguration()

        config.scriptBaseClass = ContextAwareScript.class.name

        config.addCompilationCustomizers(
                new ImportCustomizer()
                        .addImports('android.util.Log', 'android.widget.Toast')
                        .addStarImports('android.content', 'android.app', 'android.os', 'net.sf.fakenames.api'),
                new PackageCustomizer())
        // new ASTTransformationCustomizer(CompileStatic),

        def scriptCodeFile = DexGroovyClassloader.makeUnitFile(base.applicationContext, targetScript)

        if (scriptSource) {
            def optimized = new File(DexGroovyClassloader.optimizedPathFor(scriptCodeFile, scriptCodeFile.parentFile))

            optimized.delete()
            scriptCodeFile.delete()
            scriptCodeFile.parentFile.mkdirs()
        } else if (!scriptCodeFile.exists()) {
            def scriptSourceStr = base.contentResolver.query(
                    ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME),
                    [ScriptContract.Scripts.SCRIPT_ORIGIN_URI] as String[],
                    "$ScriptContract.Scripts.HUMAN_NAME = ?", [targetScript] as String[],
                    null).withCloseable {
                it.moveToNext()
                it.getString(0)
            }
            scriptSource = Uri.parse(scriptSourceStr)
        }

        def groovyClassLoader = DexGroovyClassloader.getInstance(base.applicationContext, scriptCodeFile, config)
        if (groovyClassLoader.closed)
            throw new IllegalStateException("Restart the process to unload $targetScript!")

        def thread = Thread.currentThread()
        def oldContextCl = thread.contextClassLoader

        thread.contextClassLoader = groovyClassLoader

        def lock = null
        try {
            def powerMgr = base.getSystemService(Context.POWER_SERVICE) as PowerManager

            lock = powerMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$targetScript-partial-wakelock")

            lock.acquire()

            groovyClassLoader.withCloseable {
                def scriptClass

                if (scriptSource) {
                    try {
                        def channel = Channels.newChannel(Utils.openStreamForUri(base, scriptSource))

                        scriptClass = Channels.newReader(channel, 'UTF-8').withCloseable {
                            groovyClassLoader.parseClass(new GroovyCodeSource(it, targetScript, 'whatever'))
                        }

                        def cv = new ContentValues(3)
                        cv.put(ScriptContract.Scripts.HUMAN_NAME, targetScript)
                        cv.put(ScriptContract.Scripts.CLASS_NAME, scriptClass.canonicalName)
                        cv.put(ScriptContract.Scripts.SCRIPT_ORIGIN_URI, sourceUri as String)
                        base.contentResolver.insert(ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME), cv)
                    } catch (Throwable t) {
                        GentleContextWrapper.wipeCaches(base, targetScript)

                        throw t
                    }
                } else {
                    def className = base.contentResolver.query(ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME),
                            [ScriptContract.Scripts.CLASS_NAME] as String[],
                            "$ScriptContract.Scripts.HUMAN_NAME = ?",
                            [targetScript] as String[],
                            null).withCloseable {
                        it.moveToNext()
                        it.getString(0)
                    }

                    scriptClass = groovyClassLoader.loadClass(className)
                }

                def groovyScript = scriptClass.newInstance() as Script

                def appContext = new GentleContextWrapper(base.applicationContext, groovyClassLoader, targetScript)

                groovyScript.binding = new Binding(context: appContext, executor: runner)

                if (groovyScript instanceof ContextAwareScript) {
                    def delegatingScript = groovyScript as ContextAwareScript

                    if (!delegatingScript.delegate)
                        delegatingScript.delegate = appContext

                    delegatingScript.context = appContext
                    delegatingScript.executor = runner
                }

                if (Thread.currentThread().interrupted)
                    throw new InterruptedException()

                groovyScript.run()
            }
        } finally {
            if (lock.held) lock.release()

            thread.contextClassLoader = oldContextCl
        }

        return null
    }

    @Override
    int describeContents() {
        return 0
    }

    @Override
    void writeToParcel(Parcel dest, int flags) {
        dest.writeString(targetScript)
        dest.writeParcelable(sourceUri, 0)
    }

    public static final Parcelable.Creator CREATOR = new Parcelable.Creator<ParcelableTask>() {
        @Override
        ParcelableTask createFromParcel(Parcel source) {
            return new ParcelableTask(source.readString(), source.<Uri> readParcelable(ParcelableTask.classLoader))
        }

        @Override
        ParcelableTask[] newArray(int size) {
            return new ParcelableTask[size]
        }
    }
}
