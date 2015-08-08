package com.stanfy.enroscar.goro

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcelable
import android.os.Process
import groovy.transform.CompileStatic
import net.sf.fakenames.app.IGoro

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

    int getTaskCount() {
        assert Looper.myLooper() == Looper.mainLooper

        return delegate.taskCount
    }

    public static IPCGoro from(IBinder binder) {
        new IPCGoro(IGoro.Stub.asInterface(binder))
    }

    @Override
    IBinder asBinder() {
        return delegate.asBinder()
    }
}
