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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import net.sf.fdshare.internal.FdCompat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public enum Utils { ;

    public static boolean isValidScriptName(String s)
    {
        // an empty or null string cannot be a valid identifier
        if (s == null || s.length() == 0)
        {
            return false;
        }

        char[] c = s.toCharArray();
        if (!Character.isJavaIdentifierStart(c[0]))
        {
            return false;
        }

        for (int i = 1; i < c.length; i++)
        {
            if (!Character.isJavaIdentifierPart(c[i]))
            {
                return false;
            }
        }

        return true;
    }

    public static boolean isSupportedScheme(String scheme) {
        if (TextUtils.isEmpty(scheme)) return false;

        switch (scheme) {
            case "content":
            case "resource":
            case "file":
            case "http":
            case "https":
                return true;
            default:
                return false;
        }
    }

    public static String deriveNameFromUri(Context context, Uri sourceUri) {
        String scheme = sourceUri.getScheme();

        if (!TextUtils.isEmpty(scheme)) {
            String nameCandidate;

            switch (scheme) {
                case "content":
                    try (Cursor c = context.getContentResolver().query(sourceUri,
                            new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null) {
                            c.moveToNext();
                            nameCandidate = c.getString(0);

                            if (!TextUtils.isEmpty(nameCandidate)) {
                                String[] strings = nameCandidate.replaceAll(" ", "_").split("\\.");

                                if (isValidScriptName(strings[0]))
                                    return strings[0];
                            }
                        }
                    } catch (RuntimeException ignore) {}

                    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(sourceUri, "r")) {
                        nameCandidate = FdCompat.getFdPath(fd);

                        if (!TextUtils.isEmpty(nameCandidate)) {
                            String[] strings = nameCandidate.replaceAll(" ", "_").split("\\.");

                            if (isValidScriptName(strings[0]))
                                return strings[0];
                        }
                    } catch (RuntimeException | IOException ignore) {}
                default:
                    nameCandidate = sourceUri.getLastPathSegment();

                    if (!TextUtils.isEmpty(nameCandidate)) {
                        String[] strings = nameCandidate.replaceAll(" ", "_").split("\\.");

                        if (isValidScriptName(strings[0]))
                            return strings[0];
                    }
                    break;
            }
        }

        return "";
    }

    public static InputStream openStreamForUri(Context context, Uri uri) throws IOException {
        switch (uri.getScheme()) {
            case ContentResolver.SCHEME_CONTENT:
            case ContentResolver.SCHEME_ANDROID_RESOURCE:
                try {
                    return context.getContentResolver().openInputStream(uri);
                } catch (RuntimeException ignore) {}
            case "file":
            case "http":
            case "https":
                return new URL(uri.toString()).openStream();
        }

        throw new IOException("Unable to open " + uri.toString());
    }
}
