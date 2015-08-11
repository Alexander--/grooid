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
package com.stanfy.enroscar.goro

import android.app.Notification
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Messenger
import android.os.Parcelable
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.widget.Toast
import com.android.dx.util.IntSet
import com.android.dx.util.ListIntSet
import com.stanfy.enroscar.goro.GoroService.GoroBinder
import groovy.grape.NastyGrapes
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import internal.DexGroovyClassloader
import net.sf.fakenames.app.IGoro
import net.sf.fakenames.app.PackageCustomizer
import net.sf.fakenames.app.ParcelableTask
import net.sf.fakenames.app.R
import net.sf.fakenames.app.ScriptPicker
import net.sf.fakenames.db.ScriptContract
import net.sf.fakenames.db.ScriptProvider
import org.codehaus.groovy.runtime.ArrayUtil
import org.codehaus.groovy.runtime.metaclass.ConcurrentReaderHashMap
import org.codehaus.groovy.util.ArrayIterator

import java.lang.Thread.UncaughtExceptionHandler
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@CompileStatic
final class ScriptBuilder extends GoroService {
    public static final String EXTRA_PARCELABLE_TASK = 'net.sf.fakenames.app.TASK'

    static {
        System.setProperty('groovy.grape.report.downloads', 'true')
    }

    private static final String TAG = 'ScriptRunner'

    private static final String EXTRA_KILL = 'net.sf.fakenames.app.KILL'

    private static final ScriptRunner runner = new ScriptRunner()

    static {
        setDelegateExecutor(runner)
    }

    private int bindingsHad
    private boolean foreground

    private DelegateBinder binder

    @Override
    void onCreate() {
        super.onCreate()

        NastyGrapes.init(this)
    }

    @Override
    int onStartCommand(Intent intent, int flags, int startId) {
        if (flags ^ START_FLAG_RETRY && intent.hasExtra(EXTRA_KILL)) {
            Log.i TAG, 'Requesting termination'

            Process.killProcess(Process.myPid())
        }

        super.onStartCommand(intent, flags, startId)

        checkNf()

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
        def goroBinder = super.onBind(intent) as GoroBinder

        binder = new DelegateBinder(goroBinder, applicationContext)

        def exceptionHandler = ExceptionHandler.init(this, binder)

        runner.parentGroup.delegate = exceptionHandler

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
            Log.e TAG, 'Getting destroyed by system against all odds!'

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

    @Override
    protected boolean isActive() {
        binder.runningTasks.length
    }

    Notification createForegroundNf() {
        def intent = PendingIntent.getActivity(applicationContext, R.id.req_nf, new Intent(this, ScriptPicker), 0)

        new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_nf_foreground)
                .setContentTitle('Groovy Shell is running')
                .setContentText("Scripts in queue: ${binder.tasks.size()}...")
                .setContentIntent(intent)
                .setProgress(100, 0, true)
                .build()
    }

    public static bindIt(Context context, ServiceConnection connection) {
        context.bindService(getIntent(context), connection, BIND_AUTO_CREATE)
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

    @TupleConstructor
    static final class BogusIInterface implements IInterface {
        final ListenersHandler handler

        private final IBinder binder

        BogusIInterface(IBinder binder) {
            this.binder = binder

            handler = new ListenersHandler(new Messenger(binder))
        }

        @Override
        IBinder asBinder() {
            return binder
        }
    }

    static class DelegateBinder extends IGoro.Stub implements GoroBinder, GoroListener {
        private final GoroBinder delegate

        private final Set<Integer> tasks = Collections.newSetFromMap(new ConcurrentReaderHashMap<>())

        private final RemoteCallbackList<BogusIInterface> rcl = new RemoteCallbackList<>()

        private final Context context

        DelegateBinder(GoroBinder delegate, Context context) {
            this.delegate = delegate
            this.context = context

            delegate.goro().addTaskListener(this)
        }

        @Override
        Goro goro() {
            return delegate.goro()
        }

        @Override
        int getPid() throws RemoteException {
            return Process.myPid()
        }

        @Override
        int[] getRunningTasks() {
            synchronized (tasks) {
                return tasks as int[]
            }
        }

        @Override
        void schedule(Bundle taskBundle) {
            taskBundle.classLoader = ParcelableTask.classLoader

            def task = taskBundle.<ParcelableTask>getParcelable(EXTRA_PARCELABLE_TASK)

            def extras = new Bundle()

            def scriptCodeFile = DexGroovyClassloader.makeUnitFile(context, task.targetScript)

            def usedMemory = getFreeMemory()
            if (DexGroovyClassloader.classloadersCached && usedMemory < 0.5 && (usedMemory = getFreeMemory(true)) < 0.5) {
                Log.i TAG, "Used up $usedMemory% of memory with ${Runtime.runtime.freeMemory()} remaining"

                extras.putBoolean(EXTRA_KILL, true)
            } else if (!task.runExisting && DexGroovyClassloader.cachedClassLoader(scriptCodeFile)) {
                Log.i TAG, "Requested to recompile $scriptCodeFile, which is already loaded"

                extras.putBoolean(EXTRA_KILL, true)
            }

            if (!task.scriptUri) {
                def cv = new ContentValues(2)
                cv.put(ScriptContract.Scripts.HUMAN_NAME, task.targetScript)
                cv.put(ScriptContract.Scripts.SCRIPT_ORIGIN_URI, task.sourceUri as String)
                def scriptUri = context.contentResolver.insert(ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME), cv)

                task = new ParcelableTask(task.targetScript, task.sourceUri, scriptUri, task.runExisting)
            }

            extras.putBoolean(EXTRA_IGNORE_ERROR, true)

            def intent = taskIntent(context, task).putExtras(extras)

            context.startService(intent)
        }

        private static double getFreeMemory(boolean recheck = false) {
            def vm = Runtime.runtime

            if (recheck) {
                vm.gc()
                Thread.sleep(100)
                vm.runFinalization()
                Thread.sleep(50)
                vm.gc()
            }

            def used = vm.totalMemory()
            def max = vm.maxMemory()

            (max - used) / max
        }

        @Override
        void addTaskListener(Messenger messenger) {
            rcl.register(new BogusIInterface(messenger.binder))
        }

        @Override
        void removeTaskListener(Messenger messenger) {
            rcl.unregister(new BogusIInterface(messenger.binder))
        }

        @Override
        void removeTasksInQueue(String queueName) {
            goro().removeTasksInQueue(queueName)

            if (NastyGrapes.initialized) {
                NastyGrapes.interrupt()
            }

            runner.reset()
        }

        @Override
        void onTaskSchedule(Callable<?> task, String queue) {
            if (task instanceof Parcelable) {
                synchronized (tasks) {
                    tasks.add(Integer.parseInt(((ParcelableTask) task).scriptUri.lastPathSegment))
                }

                rcl.beginBroadcast()

                def callbacksCount = rcl.registeredCallbackCount
                for (int i = 0; i < callbacksCount; i++) {
                    rcl.getBroadcastItem(i).handler.postSchedule(task, queue)
                }

                rcl.finishBroadcast()
            }
        }

        @Override
        void onTaskStart(Callable<?> task) {
            if (task instanceof Parcelable) {
                rcl.beginBroadcast()

                def callbacksCount = rcl.registeredCallbackCount
                for (int i = 0; i < callbacksCount; i++) {
                    rcl.getBroadcastItem(i).handler.postStart(task)
                }

                rcl.finishBroadcast()
            }
        }

        @Override
        void onTaskFinish(Callable<?> task, Object result) {
            if (task instanceof Parcelable) {
                synchronized (tasks) {
                    tasks.remove(Integer.parseInt(((ParcelableTask) task).scriptUri.lastPathSegment))
                }

                rcl.beginBroadcast()

                def callbacksCount = rcl.registeredCallbackCount
                for (int i = 0; i < callbacksCount; i++) {
                    rcl.getBroadcastItem(i).handler.postFinish(task, result instanceof Parcelable ? result : null)
                }

                rcl.finishBroadcast()
            }
        }

        @Override
        void onTaskCancel(Callable<?> task) {
            if (task instanceof Parcelable) {
                synchronized (tasks) {
                    tasks.remove(Integer.parseInt(((ParcelableTask) task).scriptUri.lastPathSegment))
                }

                rcl.beginBroadcast()

                def callbacksCount = rcl.registeredCallbackCount
                for (int i = 0; i < callbacksCount; i++) {
                    rcl.getBroadcastItem(i).handler.postCancel(task)
                }

                rcl.finishBroadcast()
            }
        }

        @Override
        void onTaskError(Callable<?> task, Throwable error) {
            if (task instanceof Parcelable) {
                synchronized (tasks) {
                    tasks.remove(Integer.parseInt(((ParcelableTask) task).scriptUri.lastPathSegment))
                }

                rcl.beginBroadcast()

                def callbacksCount = rcl.registeredCallbackCount
                for (int i = 0; i < callbacksCount; i++) {
                    rcl.getBroadcastItem(i).handler.postError(task, error)
                }

                rcl.finishBroadcast()
            }
        }
    }

    private static class ExceptionHandler implements UncaughtExceptionHandler {
        private static UncaughtExceptionHandler defaultHandler

        private final Context context

        private final WeakReference<DelegateBinder> binder

        private ExceptionHandler(Context context, DelegateBinder binder) {
            this.context = context.applicationContext

            this.binder = new WeakReference<>(binder)
        }

        static ExceptionHandler init(Context context, DelegateBinder binder) {
            assert Looper.mainLooper == Looper.myLooper()

            def instance = new ExceptionHandler(context, binder)

            if (!defaultHandler) defaultHandler = Thread.currentThread().uncaughtExceptionHandler

            Thread.currentThread().uncaughtExceptionHandler = instance

            return instance
        }

        @Override
        void uncaughtException(Thread thread, Throwable ex) {
            if (ex instanceof Exception) {
                def suspect = ex.stackTrace?.find { StackTraceElement it ->
                    return it?.className?.indexOf('.') == -1 || it?.className?.startsWith(PackageCustomizer.PREFIX);
                }

                if (suspect) {
                    ex.printStackTrace()

                    Toast.makeText(context, "Teh failure: $ex.message", Toast.LENGTH_LONG).show()

                    return
                }
            }

            defaultHandler.uncaughtException(thread, ex)
        }
    }

    private static class ScriptRunner implements Executor, RejectedExecutionHandler, ThreadFactory {
        private final AtomicInteger threadCount = new AtomicInteger(1)

        final DelegatingThreadGroup parentGroup = new DelegatingThreadGroup(Looper.mainLooper.thread.threadGroup)

        private volatile ScheduledThreadPoolExecutor delegate

        @Override
        Thread newThread(Runnable r) {
            def thread = new Thread(parentGroup, r, "Groovy pool thread #${threadCount.incrementAndGet()}", 2000000)
            thread.priority = Thread.NORM_PRIORITY
            return thread
        }

        @Override
        void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            Log.e TAG, "failed to submit a task $r to $executor!"

            if (!executor.shutdown && !executor.terminating) {
                executor.shutdownNow()
            }

            throw new ThreadDeath()
        }

        synchronized void reset() {
            // TODO: better sync here?
            def delegateRef = delegate

            delegate = null

            delegateRef.shutdownNow()

            while (!delegateRef.awaitTermination(10, TimeUnit.SECONDS));
        }

        @Override
        void execute(Runnable command) {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        delegate = new ScheduledThreadPoolExecutor(Runtime.runtime.availableProcessors(), this, this)
                    }
                }
            }

            delegate.execute(command)
        }
    }
}