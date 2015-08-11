package com.stanfy.enroscar.goro

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcelable
import android.os.Process
import android.os.RemoteException
import groovy.transform.CompileStatic
import net.sf.fakenames.app.IGoro
import net.sf.fakenames.app.ParcelableTask

import java.util.concurrent.Callable

@CompileStatic
final class IPCGoro implements IInterface {
    private final ListenersHandler handler = new ListenersHandler()

    private final IGoro delegate

    private final int pid

    private int listenerCount

    private IPCGoro(IGoro delegate) {
        this.delegate = delegate

        pid = delegate.pid
    }

    void addTaskListener(GoroListener listener) {
        assert Looper.myLooper() == Looper.mainLooper

        handler.addTaskListener(listener)

        if (listenerCount == 0)
            delegate.addTaskListener(handler.messenger)

        listenerCount++
    }

    void removeTaskListener(GoroListener listener) {
        assert Looper.myLooper() == Looper.mainLooper

        handler.removeTaskListener(listener)

        listenerCount--

        if (listenerCount == 0)
            delegate.removeTaskListener(handler.messenger)
    }

    int[] getRunningTasks() {
        assert Looper.myLooper() == Looper.mainLooper

        return delegate.runningTasks
    }

    void removeTasksInQueue(String queueName) {
        try {
            delegate.removeTasksInQueue(queueName)
        }
        catch (RemoteException ignore) {}
    }

    void schedule(ParcelableTask task) {
        def b = new Bundle()

        b.putParcelable(ScriptBuilder.EXTRA_PARCELABLE_TASK, task)

        delegate.schedule(b)
    }

    public static IPCGoro from(IBinder binder) {
        new IPCGoro(IGoro.Stub.asInterface(binder))
    }

    @Override
    IBinder asBinder() {
        return delegate.asBinder()
    }

    void killProcess(int signal) {
        Process.sendSignal(pid, signal)
    }
}
