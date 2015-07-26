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

import android.app.Activity
import android.app.LoaderManager
import android.content.CursorLoader
import android.content.Intent
import android.content.Loader
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatDelegate
import android.support.v7.widget.Toolbar
import android.widget.CursorAdapter
import android.widget.ListView
import android.widget.SimpleCursorAdapter
import android.widget.Toast
import butterknife.Bind
import butterknife.ButterKnife
import butterknife.OnClick
import butterknife.OnItemClick
import com.stanfy.enroscar.goro.BoundGoro
import com.stanfy.enroscar.goro.FutureObserver
import com.stanfy.enroscar.goro.Goro
import groovy.transform.CompileStatic
import net.sf.fakenames.db.ScriptContract
import net.sf.fakenames.db.ScriptProvider

@CompileStatic
final class ScriptPicker extends Activity implements LoaderManager.LoaderCallbacks {
    @Delegate
    private AppCompatDelegate delegate

    private Uri scriptUri

    private String newScriptName

    private BoundGoro service

    @Bind(R.id.list)
    protected ListView list

    @Bind(R.id.toolbar)
    protected Toolbar toolbar

    private final MainThreadExecutor executor = new MainThreadExecutor()

    @Override
    void onCreate(Bundle savedInstanceState) {
        delegate = AppCompatDelegate.create(this, null)

        handleNativeActionModesEnabled = false

        installViewFactory()

        super.onCreate(savedInstanceState)

        contentView = R.layout.act_picker

        ButterKnife.bind(this)

        supportActionBar = toolbar

        service = Goro.bindWith(this)

        loaderManager.initLoader(R.id.ldr_act_picker_cursor, new Bundle(), this)
    }

    @OnClick(R.id.add_btn)
    protected void importScript() {
        def seed = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .setType('*/*')

        def chooser = Intent.createChooser(seed, getString(R.string.add_script))

        startActivityForResult(chooser, R.id.req_pick_script)
    }

    @OnItemClick(R.id.list)
    protected void itemClicked(int position) {
        def cursor = list.adapter.getItem(position) as Cursor
        def script = cursor.getString(cursor.getColumnIndex(ScriptContract.Scripts.HUMAN_NAME))
        startScript(null, script)
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent returned) {
        switch (requestCode) {
            case R.id.req_pick_script:
                if (resultCode != RESULT_OK) {
                    scriptUri = null
                    break
                }

                scriptUri = returned?.data

                if (scriptUri && returned.flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) {
                    applicationContext.contentResolver.takePersistableUriPermission(
                            scriptUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                break
            case R.id.req_create_with_name:
                if (resultCode != RESULT_OK) {
                    newScriptName = null
                    scriptUri = null
                    break
                }

                def name = returned?.data

                if (!name) break

                newScriptName = name as String

                break
            default:
                super.onActivityResult(requestCode, resultCode, returned)
        }
    }

    @Override
    protected void onResume() {
        super.onResume()

        if (newScriptName) {
            startScript(scriptUri, newScriptName)

            newScriptName = null
            scriptUri = null
        } else if (scriptUri) {
            def proposedName = Utils.deriveNameFromUri(this, scriptUri)

            if (proposedName) {
                def existingDir = DexGroovyClassloader.makeUnitFile(this, proposedName)

                if (!existingDir.exists()) {
                    startScript(scriptUri, proposedName)

                    scriptUri = null

                    return
                }
            }

            NameRequestDialog.create(proposedName).show(fragmentManager, null)
        }
    }

    void startScript(Uri sourceUri, String targetScript) {
        def futureResult = service.schedule(new ScriptBuilder.ParcelableTask(this, targetScript, sourceUri))

        futureResult.subscribe(executor, new FutureObserver<Void>() {
            @Override
            void onSuccess(Void aVoid) {
                Toast.makeText(ScriptPicker.this, "Teh success!", Toast.LENGTH_LONG).show()
            }

            @Override
            void onError(Throwable throwable) {
                Toast.makeText(ScriptPicker.this, "Teh failure: $throwable.message", Toast.LENGTH_LONG).show()

                throwable.printStackTrace()
            }
        })
    }

    @Override
    protected void onStart() {
        super.onStart()

        service.bind()
    }

    @Override
    protected void onStop() {
        service.unbind()

        super.onStop()
    }

    @Override
    Loader<?> onCreateLoader(int id, Bundle config) {
        switch (id) {
            case R.id.ldr_act_picker_cursor:
                return new CursorLoader(this, ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME),
                        [ScriptContract.Scripts.SCRIPT_ID, ScriptContract.Scripts.HUMAN_NAME] as String[],
                        null, null, null)

                break

            default:
                throw new UnsupportedOperationException('Unknown loader id')
        }
    }

    @Override
    void onLoadFinished(Loader loader, Object data) {
        switch (loader.id) {
            case R.id.ldr_act_picker_cursor:
                list.adapter = new SimpleCursorAdapter(this, R.layout.item_script, data as Cursor,
                        [ ScriptContract.Scripts.HUMAN_NAME ] as String[], [ android.R.id.text1 ] as int[], 0)

                break

            default:
                throw new UnsupportedOperationException('Unknown loader id')
        }
    }

    @Override
    void onLoaderReset(Loader loader) {
        switch (loader.id) {
            case R.id.ldr_act_picker_cursor:
                (list.adapter as CursorAdapter).swapCursor(null)

                break
        }
    }

}
