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

import android.content.Context;
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences;
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.util.Log
import groovy.transform.CompileStatic

/**
 * Relocates all file-related calls to $targetDir/sandbox/$uniqueId. Some hierarchy weirdness may happen, but that
 * should be ok
 *
 * Redirects calls to getSharedPrefrences($name) to sandbox-$uniqueId-$name
 *
 * Adds Intent.FLAG_NEW_TAKS to some startActivity calls
 *
 * Returns the script classloader from getClassLoader
 */
@CompileStatic
final class GentleContextWrapper extends ContextWrapper {
    private static final ContextCompat cc = new ContextCompat();

    private static final String TAG = "SandboxingDelegatingContextWrapper"

    private static final PREFIX = 'sandbox'

    private final ClassLoader clazzLoader
    private final String uniqueId

    GentleContextWrapper(Context base, ClassLoader clazzLoader, String uniqueId) {
        super(base.applicationContext)

        this.clazzLoader = clazzLoader
        this.uniqueId = uniqueId

        createDirLocked(filesDir)
        createDirLocked(cacheDir)
        createDirLocked(codeCacheDir)
    }

    @Override
    File getDir(String name, int mode) {
        def dir = "$filesDir/$name" as File

        if (!dir.exists() && !dir.mkdirs())
            Log.e TAG, "Failed to create a directory $dir"

        setFilePermissionsFromMode(dir, mode)

        return dir
    }

    @Override
    File getDatabasePath(String name) {
        return validateFilePath(name)
    }

    @Override
    String[] databaseList() {
        return databasesDir.list() ?: new String[0]
    }

    @Override
    File getExternalFilesDir(String type) {
        def externalFiles = super.getExternalFilesDir(null)

        if (externalFiles) {
            externalFiles = "$externalFiles/$PREFIX/$uniqueId" as File

            switch (type) {
                case null:
                    return externalFiles
                default:
                    def actualDir = super.getExternalFilesDir(type)
                    if (actualDir)
                        return new File(externalFiles, actualDir.name)
            }
        }

        return null
    }

    @Override
    File getExternalCacheDir() {
        def externalCache = super.getExternalCacheDir()

        return externalCache ? "$externalCache/$PREFIX/$uniqueId" as File : externalCache
    }

    @Override
    File getCodeCacheDir() {
        def codeCache = cc.getCodeCacheDir(this)

        return codeCache ? "$codeCache/$PREFIX/$uniqueId" as File : codeCache
    }

    @Override
    File getNoBackupFilesDir() {
        def noBackup = cc.getNoBackupFilesDir(this)

        return noBackup ? "$noBackup/$PREFIX/$uniqueId" as File : noBackup
    }

    @Override
    File getFilesDir() {
        def filesDir = super.filesDir

        return filesDir ? "$filesDir/$PREFIX/$uniqueId" as File : filesDir
    }

    @Override
    File getCacheDir() {
        def cacheDir = super.cacheDir

        return cacheDir ? "$cacheDir/$PREFIX/$uniqueId" as File : cacheDir
    }

    @Override
    File getFileStreamPath(String name) {
        def fsPath = filesDir

        return fsPath ? "$filesDir/$name" as File : fsPath
    }

    @Override
    String[] fileList() {
        def fileList = filesDir.listFiles()

        return fileList ?: new File[0]
    }

    @Override
    FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        def f = "$filesDir/$name" as File
        f.parentFile.mkdirs()

        boolean append = mode & MODE_APPEND

        def stream = new FileOutputStream(f, append)

        setFilePermissionsFromMode(f, mode)

        return stream
    }

    @Override
    FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream("$filesDir/$name")
    }

    @Override
    SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences("$PREFIX-$uniqueId-$name", mode)
    }

    @Override
    boolean deleteFile(String name) {
        return new File("$filesDir/$name").delete()
    }

    @Override
    void startActivity(Intent intent) {
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Override
    void startActivity(Intent intent, Bundle options) {
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
    }

    @Override
    SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        openOrCreateDatabase(name, mode, factory, null)
    }
    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        def f = validateFilePath(name)

        f.parentFile.mkdirs()

        int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
        if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
            flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        }

        def db = SQLiteDatabase.openDatabase(f.path, factory, flags, errorHandler)

        setFilePermissionsFromMode(f, mode)

        return db
    }

    @Override
    boolean deleteDatabase(String name) {
        return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
    }

    @Override
    ClassLoader getClassLoader() {
        return clazzLoader
    }

    @Override
    Context getApplicationContext() {
        return this
    }

    @Override
    Resources getResources() {
        return Resources.system
    }

    @Override
    Context getBaseContext() {
        return this
    }

    private File getOwnCodeCacheDir() {
        DexGroovyClassloader.makeUnitFile(baseContext, uniqueId).parentFile
    }

    private File getDatabasesDir() {
        filesDir ? "$filesDir/$PREFIX-databases/" as File : filesDir
    }

    private File validateFilePath(String name) {
        File dir;
        File f;

        if (name.charAt(0) == File.separatorChar) {
            String dirPath = name.substring(0, name.lastIndexOf('/'));
            dir = new File(dirPath);
            name = name.substring(name.lastIndexOf('/'));
            f = new File(dir, name);
        } else {
            dir = databasesDir
            f = makeFilename(dir, name);
        }

        return f;
    }

    @SuppressWarnings("GrDeprecatedAPIUsage")
    private static setFilePermissionsFromMode(File file, int mode) {
        if ((mode & MODE_WORLD_READABLE))
            file.setReadable(true, false)

        if ((mode & MODE_WORLD_WRITEABLE))
            file.setWritable(true, false)
    }

    private static File makeFilename(File base, String name) {
        if (name.indexOf('/') < 0) {
            return new File(base, name);
        }
        throw new IllegalArgumentException("File $name contains a path separator");
    }

    static boolean cleanup(Context context, String uniqueId) {
        try {
            def prefsDir = "$context.filesDir/shared_prefs/" as File
            if (prefsDir.exists()) {
                def pattern = ~/$PREFIX-$uniqueId-/

                def prefCleanup = prefsDir.listFiles().every { File it -> pattern.matcher(it.name) ? it.delete() : true }

                if (!prefCleanup)
                    Log.e TAG, "Failed to remove some shared preferences files for $uniqueId"
            }
        } catch (RuntimeException ignore) {}

        GentleContextWrapper wrapper =  new GentleContextWrapper(context, GentleContextWrapper.class.classLoader, uniqueId)

        wrapper.getExternalFilesDir(null)?.deleteDir()  ?: Log.e(TAG, "Failed to delete external files of $uniqueId")

        undoCaches(wrapper) &&
                wrapper.databasesDir.deleteDir() &&
                wrapper.noBackupFilesDir.deleteDir() &&
                wrapper.filesDir.deleteDir()
    }

    static boolean wipeCaches(Context context, String uniqueId) {
        undoCaches(new GentleContextWrapper(context, GentleContextWrapper.class.classLoader, uniqueId))
    }

    private static boolean undoCaches(GentleContextWrapper wrapper) {
        wrapper.externalCacheDir?.deleteDir() ?: Log.e(TAG, "Failed to delete external caches of $wrapper.uniqueId")

        wrapper.ownCodeCacheDir.deleteDir() &&
                wrapper.codeCacheDir.deleteDir() &&
                wrapper.cacheDir.deleteDir()
    }

    private static def createDirLocked(File file) {
        if (!file.exists()) {
            if (!file.mkdirs()) {
                if (file.exists()) {
                    // spurious failure; probably racing with another process for this app
                    return file;
                }

                Log.e TAG, "Failed to create a directory $file"

                return null;
            }
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(true, true)
        }
    }
}
