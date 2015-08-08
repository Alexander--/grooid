package net.sf.fakenames.app;

import android.os.Messenger;
import android.os.Bundle;

interface IGoro {
    int getPid();

    int getTaskCount();

    void addTaskListener(in Messenger messenger);

    void removeTaskListener(in Messenger messenger);
}
