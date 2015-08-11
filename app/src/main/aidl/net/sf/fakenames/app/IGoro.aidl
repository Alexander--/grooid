package net.sf.fakenames.app;

import android.os.Messenger;
import android.os.Bundle;

interface IGoro {
    int getPid();

    int[] getRunningTasks();

    void addTaskListener(in Messenger messenger);

    void removeTaskListener(in Messenger messenger);

    void schedule(in Bundle taskBundle);

    oneway void removeTasksInQueue(in String queueName);
}
