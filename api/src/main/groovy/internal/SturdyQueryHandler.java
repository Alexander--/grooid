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
package internal;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;

public class SturdyQueryHandler extends AsyncQueryHandler {
    public SturdyQueryHandler(ContentResolver cr) {
        super(cr);
    }

    /**
     * Thin wrapper around {@link android.content.AsyncQueryHandler.WorkerHandler} that catches any <code>RuntimeException</code>
     * thrown and passes them back in a reply message. The exception that occurred is
     * in the <code>result</code> field.
     */
    protected class SturdyWorkerHandler extends WorkerHandler {
        public SturdyWorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            try {
                super.handleMessage(msg);
            } catch (RuntimeException x) {
                // Pass the exception back to the calling thread (will be in args.result)
                AsyncQueryHandler.WorkerArgs args = (AsyncQueryHandler.WorkerArgs) msg.obj;
                Message reply = args.handler.obtainMessage(msg.what);
                args.result = x;
                reply.obj = args;
                reply.arg1 = msg.arg1;
                reply.sendToTarget();
            }
        }
    }

    @Override
    protected Handler createHandler(@NonNull Looper looper) {
        return new SturdyWorkerHandler(looper);
    }

    /**
     * Called when a runtime exception occurred during the asynchronous operation.
     * <p>
     * The default re-throws the exception
     * @param token - The token that was passed into the operation
     * @param cookie - The cookie that was passed into the operation
     * @param error - The <code>RuntimeException</code> that was thrown during
     * the operation
     */
    public void onError(int token, Object cookie, RuntimeException error) {
        throw error;
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        if (msg.obj instanceof WorkerArgs) {
            WorkerArgs args = (WorkerArgs) msg.obj;
            if (args.result instanceof RuntimeException) {
                onError(msg.what, args.cookie, (RuntimeException) args.result);
                return;
            }
        }
        super.handleMessage(msg);
    }
}
