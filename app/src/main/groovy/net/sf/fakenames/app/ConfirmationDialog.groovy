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
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.widget.TextView
import butterknife.Bind
import butterknife.ButterKnife

final class ConfirmationDialog  extends DialogFragment implements DialogInterface.OnClickListener, DialogInterface.OnShowListener {
    private static final String ARG_URI = 'uri'

    @Bind(R.id.frag_dialog_confirm_tv)
    protected TextView scriptText

    private PendingIntent callback
    private String requestedScript

    @Deprecated
    ConfirmationDialog() {}

    @Override
    void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState)

        requestedScript = arguments.getString(ARG_URI)
        //def nameParts = requestedScript.split("/")
        //requestedScript = nameParts[nameParts.length - 1]

        //if (requestedScript.length() > 7)
        //    requestedScript = requestedScript.substring(0, 6) + '…'
    }

    @Override
    void onAttach(Activity activity) {
        super.onAttach(activity)

        callback = activity.createPendingResult(R.id.req_confirm_opening, new Intent(), PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Override
    Dialog onCreateDialog(Bundle savedInstanceState) {
        def dialog = new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.do_you_want_to_open, requestedScript))
                .setPositiveButton(android.R.string.yes, this)
                .setNegativeButton(android.R.string.cancel, this)
                .setView(R.layout.frag_dialog_confirm)
                .create()

        dialog.delegate.handleNativeActionModesEnabled = false
        dialog.onShowListener = this

        return dialog
    }

    @Override
    void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                callback.send(activity, Activity.RESULT_OK, new Intent())
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                callback.send(activity, Activity.RESULT_CANCELED, new Intent())
                break;
        }
    }

    @Override
    void onShow(DialogInterface dialogInterface) {
        ButterKnife.bind(this, dialog)

        scriptText.text = requestedScript
    }

    static DialogFragment create(String title) {
        def frag = new ConfirmationDialog()

        def args = new Bundle()

        args.putString(ARG_URI, title)

        frag.arguments = args

        return frag
    }
}
