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
import android.app.Dialog
import android.app.DialogFragment
import android.app.PendingIntent
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatDialog
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import butterknife.Bind
import butterknife.ButterKnife
import butterknife.OnEditorAction
import groovy.transform.CompileStatic

@CompileStatic
final class NameRequestDialog extends DialogFragment implements DialogInterface.OnShowListener {
    private static final String ARG_URI = 'uri'

    @Bind(R.id.frag_dialog_enter_name_tv)
    protected TextView textInput

    private PendingIntent callback
    private String proposedName

    @Deprecated
    NameRequestDialog() {}

    @Override
    void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState)

        proposedName = arguments.getString(ARG_URI)
    }

    @Override
    void onAttach(Activity activity) {
        super.onAttach(activity)

        callback = activity.createPendingResult(R.id.req_create_with_name, new Intent(), PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Override
    Dialog onCreateDialog(Bundle savedInstanceState) {
        def dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.save_script_as)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(R.layout.frag_dialog_enter_name)
                .create()

        dialog.delegate.handleNativeActionModesEnabled = false

        dialog.onShowListener = this

        return dialog
    }

    @OnEditorAction(R.id.frag_dialog_enter_name_tv)
    boolean saveClicked(int actionId) {
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
            return submit()
        }

        return false
    }

    boolean submit() {
        if (!Utils.isValidScriptName("$textInput.text")) {
            textInput.error = getString(R.string.enter_valid_script_name)

            return true
        }

        def result = new Intent()
        result.data = Uri.parse("$textInput.text")

        callback.send(activity, Activity.RESULT_OK, result)

        dismissAllowingStateLoss()

        return false
    }

    @Override
    void onCancel(DialogInterface dialog) {
        callback.send(activity, Activity.RESULT_CANCELED, new Intent())
    }

    @Override
    void onShow(DialogInterface dialogInterface) {
        ButterKnife.bind(this, dialog)

        def okBtn = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
        def cancelBtn = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE)

        cancelBtn.onClickListener = {
            callback.send(activity, Activity.RESULT_CANCELED, new Intent())
            dismiss()
        }

        okBtn.onClickListener = { submit() }

        if (proposedName) textInput.text = proposedName
    }

    static DialogFragment create(String proposedName) {
        def frag = new NameRequestDialog()

        def args = new Bundle()

        args.putString(ARG_URI, proposedName)

        frag.arguments = args

        return frag
    }
}
