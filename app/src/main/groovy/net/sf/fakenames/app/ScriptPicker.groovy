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
import android.content.ComponentName
import android.content.Context
import android.content.CursorLoader
import android.content.Intent
import android.content.Loader
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.support.v7.app.AppCompatDelegate
import android.support.v7.widget.Toolbar
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Toast
import butterknife.Bind
import butterknife.ButterKnife
import butterknife.OnClick
import butterknife.OnItemClick
import butterknife.OnItemLongClick
import com.daimajia.swipe.adapters.SimpleCursorSwipeAdapter
import com.daimajia.swipe.util.Attributes
import com.stanfy.enroscar.goro.GoroListener
import com.stanfy.enroscar.goro.GoroService
import groovy.transform.CompileStatic
import net.sf.fakenames.db.ScriptContract
import net.sf.fakenames.db.ScriptProvider
import net.sf.fakenames.dispatcher.MaterialProgressDrawable
import net.sf.fakenames.dispatcher.Utils

import java.util.concurrent.Callable

@CompileStatic
final class ScriptPicker extends Activity implements LoaderManager.LoaderCallbacks, GoroListener, ServiceConnection {
    @Delegate
    private AppCompatDelegate delegate

    private ScriptAdapter adapter

    private boolean resumed

    private boolean cautious
    private Uri scriptUri
    private String newScriptName

    private int taskCount

    private ScriptBuilder.DelegateBinder service

    @Bind(R.id.list)
    protected ListView list

    @Bind(R.id.add_btn)
    protected ImageButton button

    @Bind(R.id.toolbar)
    protected Toolbar toolbar

    @Override
    void onCreate(Bundle savedInstanceState) {
        delegate = AppCompatDelegate.create(this, null)

        handleNativeActionModesEnabled = false

        installViewFactory()

        super.onCreate(savedInstanceState)

        if (!savedInstanceState) {
            handleIntent(intent);
        }

        contentView = R.layout.act_picker

        ButterKnife.bind(this)

        list.adapter = adapter = new ScriptAdapter(this, R.layout.item_script, null,
                [ ScriptContract.Scripts.HUMAN_NAME ] as String[], [ android.R.id.text1 ] as int[])
        list.addFooterView(layoutInflater.inflate(R.layout.footer, list, false), null, false)

        supportActionBar = toolbar

        loaderManager.initLoader(R.id.ldr_act_picker_cursor, new Bundle(), this)
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent)

        if (handleIntent(intent)) {
            cautious = intent.hasCategory(intent.CATEGORY_BROWSABLE)

            if (cautious) {
                ConfirmationDialog.create("$intent.data").show(fragmentManager, null)
            }

            if (resumed) processPendingActions()
        }
    }

    private boolean handleIntent(Intent intent) {
        if (intent?.action == Intent.ACTION_VIEW && Utils.isSupportedScheme(intent.data?.scheme)) {
            scriptUri = intent.data
            return true
        }

        return false
    }

    @OnClick(R.id.add_btn)
    protected void importScript() {
        def openSeed = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .setType('*/*')

        def chooser = Intent.createChooser(openSeed, getString(R.string.add_script))

        startActivityForResult(chooser, R.id.req_pick_script)
    }

    @OnItemClick(R.id.list)
    protected void itemClicked(int position) {
        if (adapter.openItems && !adapter.openItems[0] != -1) {
            adapter.closeAllItems()
        } else {
            def cursor = adapter.getItem(position) as Cursor
            def script = cursor.getString(cursor.getColumnIndex(ScriptContract.Scripts.HUMAN_NAME))
            startScript(null, script)
        }
    }

    @OnItemLongClick(R.id.list)
    protected boolean itemLongClicked(int position) {
        if (adapter.openItems && !adapter.openItems[0] != -1)
            adapter.closeAllItems()
        else
            adapter.openItem(position)

        return true
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
            case R.id.req_confirm_opening:
                cautious = false

                if (resultCode != RESULT_OK) {
                    newScriptName = null
                    scriptUri = null
                    break
                }

                break
            default:
                super.onActivityResult(requestCode, resultCode, returned)
        }

        if (resumed) processPendingActions()
    }

    @Override
    protected void onResume() {
        super.onResume()

        resumed = true

        processPendingActions()
    }

    @Override
    protected void onPause() {
        resumed = false

        super.onPause()
    }

    private void processPendingActions() {
        if (!service) return

        if (cautious) {
            // ok
        } else if (newScriptName) {
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
        service.goro().schedule(new ScriptBuilder.ParcelableTask(this, targetScript, sourceUri))

        updateState()
    }

    @Override
    protected void onStart() {
        super.onStart()

        GoroService.bind(this, this)
    }

    @Override
    protected void onStop() {
        if (service) {
            service.goro().removeTaskListener(this)
        }

        GoroService.unbind(this, this)

        super.onStop()
    }

    @Override
    Loader<?> onCreateLoader(int id, Bundle config) {
        switch (id) {
            case R.id.ldr_act_picker_cursor:
                return new CursorLoader(this, ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME),
                        [
                                ScriptContract.Scripts.SCRIPT_ID,
                                ScriptContract.Scripts.HUMAN_NAME,
                                ScriptContract.Scripts.SCRIPT_ORIGIN_URI
                        ] as String[],
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
                adapter.swapCursor(data as Cursor)

                updateState()

                break

            default:
                throw new UnsupportedOperationException('Unknown loader id')
        }
    }

    @Override
    void onLoaderReset(Loader loader) {
        switch (loader.id) {
            case R.id.ldr_act_picker_cursor:
                adapter.swapCursor(null)

                break
        }
    }

    void updateState() {
        if (taskCount) {
            if (!button.getDrawable()) {
                def newDrawable = new MaterialProgressDrawable(this, button)
                newDrawable.backgroundColor = resources.getColor(android.R.color.transparent)
                newDrawable.updateSizes(MaterialProgressDrawable.LARGE)

                button.setImageDrawable(newDrawable)

                newDrawable.setAlpha(255);
                newDrawable.start()

                button.setEnabled(false)
            }

            if (adapter?.enabled) {
                adapter.enabled = false
            }
        }
        else {
            button.setEnabled(true)
            button.setImageDrawable(null)

            if (adapter && !adapter.enabled) {
                adapter.enabled = true
            }
        };
    }

    @Override
    void onTaskSchedule(Callable<?> task, String queue) {
        taskCount++

        updateState()
    }

    @Override
    void onTaskStart(Callable<?> task) {}

    @Override
    void onTaskFinish(Callable<?> task, Object result) {
        taskCount--

        updateState()

        Toast.makeText(this, "Teh success!", Toast.LENGTH_LONG).show()
    }

    @Override
    void onTaskCancel(Callable<?> task) {
        taskCount--

        updateState()
    }

    @Override
    void onTaskError(Callable<?> task, Throwable error) {
        taskCount--

        updateState()

        Toast.makeText(this, "Teh failure: $error.message", Toast.LENGTH_LONG).show()
    }

    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = binder as ScriptBuilder.DelegateBinder

        taskCount = service.taskCount

        service.goro().addTaskListener(this)

        updateState()

        if (resumed) processPendingActions()
    }

    public void onServiceDisconnected(ComponentName name) {
        throw new AssertionError()
    }

    private static class ScriptAdapter extends SimpleCursorSwipeAdapter {
        boolean enabled

        ScriptAdapter(ScriptPicker context, int layout, Cursor c, String[] from, int[] to) {
            super(context, layout, c, from, to, 0)
        }

        @Override
        View newView(Context context, Cursor cursor, ViewGroup parent) {
            def view = super.newView(context, cursor, parent)

            def delBtn = view.findViewById(R.id.item_script_delete_img)
            delBtn.onClickListener = { View v ->
                def targetScript = view.getTag(R.id.tag_script) as String

                def scriptCodeFile = DexGroovyClassloader.makeUnitFile(context, targetScript)
                scriptCodeFile.parentFile.deleteDir()

                def uri = ScriptProvider.contentUri(ScriptContract.Scripts.TABLE_NAME)
                context.contentResolver.delete(uri, "$ScriptContract.Scripts.HUMAN_NAME = ?", [targetScript] as String[])
            }
            def editBtn = view.findViewById(R.id.item_script_edit_img)
            editBtn.setOnClickListener { View v ->
                def targetScript = view.getTag(R.id.tag_origin) as String

                def Intent intent = new Intent(Intent.ACTION_EDIT)
                intent.setDataAndType(Uri.parse(targetScript), 'text/plain')
                context.startActivity(intent)
            }

            return view
        }

        @Override
        Cursor swapCursor(Cursor c) {
            closeAllItems()

            return super.swapCursor(c)
        }

        @Override
        void bindView(View view, Context context, Cursor cursor) {
            super.bindView(view, context, cursor)

            view.setTag(R.id.tag_script, cursor.getString(cursor.getColumnIndex(ScriptContract.Scripts.HUMAN_NAME)))
            view.setTag(R.id.tag_origin, cursor.getString(cursor.getColumnIndex(ScriptContract.Scripts.SCRIPT_ORIGIN_URI)))
        }

        @Override
        boolean isEnabled(int position) {
            return enabled
        }

        @Override
        boolean areAllItemsEnabled() {
            return enabled
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled

            notifyDataSetChanged()
        }

        @Override
        int getSwipeLayoutResourceId(int i) {
            return R.id.swipe_layout
        }

        @Override
        void closeAllItems() {
            super.closeAllExcept(null)
        }
    }
}
