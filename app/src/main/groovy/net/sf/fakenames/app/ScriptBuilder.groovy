/*
 * GroovyShell - Android harness for running Groovy programs
 *
 * Copyright © 2015 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * In addition, as a special exception, the copyright holders give
 * permission to link the code of portions of this program with independent
 * modules ("scripts") to produce an executable program, regardless of the license
 * terms of these independent modules, and to copy and distribute the resulting
 * script under terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that module.
 * An independent module is a module which is not derived from or based on this library.
 * If you modify this library, you may extend this exception to your version of
 * the library, but you are not obligated to do so.  If you do not wish to do
 * so, delete this exception statement from your version.
 */
package net.sf.fakenames.app

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.support.annotation.NonNull
import android.support.annotation.Nullable
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.widget.Toast
import com.stanfy.enroscar.goro.GoroService
import com.stanfy.enroscar.goro.ServiceContextAware
import groovy.transform.CompileStatic
import net.sf.fakenames.db.ScriptContract
import net.sf.fakenames.db.ScriptProvider
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@CompileStatic
final class ScriptBuilder extends GoroService implements UncaughtExceptionHandler {
    private static final String TAG = 'ScriptRunner'

    private static final ScriptRunner runner = new ScriptRunner()

    static {
        setDelegateExecutor(runner)
    }

    private int bindingsHad
    private boolean foreground

    private UncaughtExceptionHandler defaultHandler

    @Override
    void onCreate() {
        super.onCreate()

        runner.parentGroup.delegate = this

        def mainThread = Thread.currentThread()
        def handler = mainThread.uncaughtExceptionHandler

        if (handler instanceof ScriptBuilder)
            return

        defaultHandler = handler
        mainThread.uncaughtExceptionHandler = this
    }

    @Override
    int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId)

        return START_NOT_STICKY
    }

    @Override
    boolean onUnbind(Intent intent) {
        super.onUnbind(intent)

        bindingsHad--;

        checkNf()

        return true
    }

    @Override
    IBinder onBind(Intent intent) {
        IBinder binder = super.onBind(intent)

        bindingsHad++

        checkNf()

        return binder
    }

    @Override
    void onRebind(Intent intent) {
        super.onRebind(intent)

        bindingsHad++

        checkNf()
    }

    @Override
    protected void stop() {
        checkNf()

        super.stop()
    }

    @Override
    void onDestroy() {
        if (isActive())
            Log.e '', 'Getting destroyed by system against all odds!'

        super.onDestroy()
    }

    void checkNf() {
        if (!bindingsHad) {
            if (isActive()) {
                startForeground(R.id.nf_foreground, createForegroundNf())
                foreground = true
            }
        } else if (foreground) {
            stopForeground(true)
            foreground = false
        }
    }

    Notification createForegroundNf() {
        new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_nf_foreground)
                .setContentText("Running scripts...")
                .setProgress(100, 0, true)
                .build()
    }

    @Override
    void uncaughtException(Thread thread, Throwable ex) {
        if (ex instanceof Exception) {
            def suspect = ex.stackTrace?.find { StackTraceElement it ->
                return it?.className?.indexOf('.') == -1 || it?.className?.startsWith(PackageCustomizer.PREFIX);
            }

            if (suspect) {
                ex.printStackTrace()

                Toast.makeText(applicationContext, "Teh failure: $ex.message", Toast.LENGTH_LONG).show()

                return
            }
        }

        defaultHandler.uncaughtException(thread, ex)
    }

    private static class DelegatingThreadGroup extends ThreadGroup {
        UncaughtExceptionHandler delegate

        DelegatingThreadGroup(ThreadGroup parent) {
            super(parent, 'Groovy script threads')
        }

        @Override
        void uncaughtException(Thread t, Throwable e) {
            delegate.uncaughtException(t, e)

            super.uncaughtException(t, e)
        }
    }

    private static class ScriptRunner extends ScheduledThreadPoolExecutor implements RejectedExecutionHandler, ThreadFactory {
        private final AtomicInteger threadCount = new AtomicInteger(1)

        final DelegatingThreadGroup parentGroup = new DelegatingThreadGroup(Looper.mainLooper.thread.threadGroup)

        ScriptRunner() {
            super(Runtime.runtime.availableProcessors())

            threadFactory = this
            rejectedExecutionHandler = this
        }

        @Override
        Thread newThread(Runnable r) {
            def thread = new Thread(parentGroup, r, "Groovy pool thread #${threadCount.incrementAndGet()}")
            thread.priority = Thread.NORM_PRIORITY
            return thread
        }

        @Override
        void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            Log.e TAG, "failed to submit a task $r to $executor!"

            if (!executor.shutdown && !executor.terminating) {
                executor.shutdownNow()
            }
            executor.awaitTermination(1, TimeUnit.SECONDS)

            System.exit(-99)
        }
    }

    @CompileStatic
    public static class ParcelableTask implements Callable<Void>, Parcelable, ServiceContextAware {
        private final Uri sourceUri
        private final String targetScript

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
            def config = new CompilerConfiguration()

            config.scriptBaseClass = ContextAwareScript.class.name

            config.addCompilationCustomizers(
                    new ImportCustomizer()
                            .addImports('android.util.Log', 'android.widget.Toast')
                            .addStarImports('android.content', 'android.app', 'android.os', 'net.sf.sandbox'),
                    new PackageCustomizer())

            def scriptCodeFile = DexGroovyClassloader.makeUnitFile(base.applicationContext, targetScript)
            if (sourceUri) {
                scriptCodeFile.parentFile.deleteDir()
                scriptCodeFile.parentFile.mkdir()
            }

            def thread = Thread.currentThread()
            def oldContextCl = thread.contextClassLoader

            def groovyClassLoader = new DexGroovyClassloader(base.applicationContext, scriptCodeFile, config)
            thread.contextClassLoader = groovyClassLoader

            try {
                def scriptClass

                if (sourceUri) {
                    scriptClass = new BufferedReader(new InputStreamReader(base.contentResolver.openInputStream(sourceUri))).withCloseable {
                        def codeSource = new GroovyCodeSource(it, targetScript, 'UTF-8')

                        return groovyClassLoader.parseClass(codeSource)
                    }

                    def cv = new ContentValues(2)
                    cv.put(ScriptContract.Scripts.HUMAN_NAME, targetScript)
                    cv.put(ScriptContract.Scripts.CLASS_NAME, scriptClass.canonicalName)
                    base.contentResolver.insert(ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME), cv)
                } else {
                    def className = base.contentResolver.query(ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME),
                            [ScriptContract.Scripts.CLASS_NAME] as String[],
                            "$ScriptContract.Scripts.HUMAN_NAME = ?",
                            [ targetScript ] as String[],
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

                def lock = null
                try {
                    def powerMgr = appContext.getSystemService(POWER_SERVICE) as PowerManager

                    lock = powerMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$targetScript-partial-wakelock")

                    lock.acquire()

                    groovyScript.run()
                } finally {
                    if (lock.held) lock.release()
                }
            } finally {
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
            dest.writeParcelable(sourceUri, 0)
            dest.writeSerializable(targetScript)
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
}
