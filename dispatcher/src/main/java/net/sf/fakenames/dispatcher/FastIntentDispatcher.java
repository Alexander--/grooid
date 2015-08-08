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
package net.sf.fakenames.dispatcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public final class FastIntentDispatcher extends Activity {
    public static final int UNSUPPORTED_URI = RESULT_FIRST_USER;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!handleIntent(getIntent())) {
            setResult(UNSUPPORTED_URI);
        }

        finish();
    }

    private boolean handleIntent(Intent intent) {
        final String action;

        if (intent == null || (action = intent.getAction()) == null) return false;

        switch (action) {
            case Intent.ACTION_VIEW:
                final Uri data = intent.getData();

                if (data == null || !Utils.isSupportedScheme(data.getScheme())) break;

                startScriptPicker(intent, data);

                return true;

            case Intent.ACTION_SEND:
                final Uri extraStream = intent.getParcelableExtra(Intent.EXTRA_STREAM);

                if (extraStream == null || !Utils.isSupportedScheme(extraStream.getScheme())) break;

                startScriptPicker(intent, extraStream);

                return true;
            default:
                Toast.makeText(this, getString(R.string.unsupported_action), Toast.LENGTH_LONG).show();

                return false;
        }

        Toast.makeText(this, getString(R.string.invalid_arguments), Toast.LENGTH_LONG).show();

        return false;
    }

    private void startScriptPicker(Intent original, Uri data) {
        Intent intent2 = new Intent(Intent.ACTION_VIEW, data);
        intent2.setClassName(getPackageName(), "net.sf.fakenames.app.ScriptPicker");
        intent2.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        if (original.hasCategory(Intent.CATEGORY_BROWSABLE) || data.getScheme().startsWith("http")) {
            intent2.addCategory(Intent.CATEGORY_BROWSABLE);
        }

        startActivity(intent2);
    }
}