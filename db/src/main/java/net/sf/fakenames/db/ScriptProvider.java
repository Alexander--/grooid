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
package net.sf.fakenames.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import internal.GentleContextWrapper;
import org.codehaus.groovy.runtime.ResourceGroovyMethods;

import java.io.File;

public final class ScriptProvider extends ScriptProviderProto {
    @Override
    protected void onPerformCleanupBeforeDeleted(Uri uri, String selection, String[] selectionArgs) {
        try (Cursor data = query(uri, null, selection, selectionArgs, null)) {
            while (data.moveToNext()) {
                final String name = data.getString(data.getColumnIndex(ScriptContract.Scripts.HUMAN_NAME));

                if (!GentleContextWrapper.cleanup(getContext(), name)) {
                    throw new IllegalStateException("Failed to remove script data for " + name);
                }
            }
        }
    }
}

class ScriptHelper extends SQLiteOpenHelper {
    private final Context context;

    public ScriptHelper(Context context) {
        super(context, ScriptSchema.DB_NAME, null, ScriptSchema.DB_VERSION);

        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        ScriptSchema.onCreate(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        ScriptSchema.onDrop(db);
        onCreate(db);
        doChores();
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        ScriptSchema.onDrop(db);
        onCreate(db);
        doChores();
    }

    private void doChores() {
        ResourceGroovyMethods.deleteDir(new ContextCompat().getCodeCacheDir(context));

        ResourceGroovyMethods.deleteDir(new File(context.getCacheDir().getAbsolutePath() + "/sandbox/"));

        final File externalCaches = context.getExternalCacheDir();
        if (externalCaches != null)
            ResourceGroovyMethods.deleteDir(new File(externalCaches, "/sandbox/"));
    }
}