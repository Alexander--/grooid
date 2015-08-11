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

import android.content.Context
import android.os.Process
import android.support.v4.content.ContextCompat
import android.support.v4.util.CircularArray
import android.util.Log
import android.util.ArrayMap
import com.android.dex.DexFormat
import com.android.dx.Version
import com.android.dx.cf.direct.DirectClassFile
import com.android.dx.cf.direct.StdAttributeFactory;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions
import com.android.dx.dex.cf.CfTranslator
import com.android.dx.dex.code.PositionList;
import com.android.dx.dex.file.DexFile
import com.android.dx.util.ByteArray
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import groovy.grape.Grape
import groovy.lang.GroovyClassLoader.ClassCollector
import groovy.lang.GroovyClassLoader.InnerLoader
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.transform.TupleConstructor
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.BytecodeProcessor;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilationUnit.ClassgenCallback
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.runtime.metaclass.ConcurrentReaderHashMap

import java.security.CodeSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.Attributes;
import java.util.jar.JarEntry
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest

import dalvik.system.DexFile as LoadedDex

import java.util.zip.ZipFile

/**
 * A mix of {@link DexClassLoader} and {@link GroovyClassLoader}. This class does the following:
 *
 * <li>
 *     <ul> Maintains dynamic expandable list of {@link DexFile}s (unlike one in {@link BaseDexClassLoader})
 *     <ul> Converts any added JARs to Dex format, using it's own copy of dx, and adds those to the search path
 *     <ul> Allows seamless compilation of Groovy scripts by converting to Dex format on fly
 * </li>
 *
 * <p>
 *
 * There is currently no "recompilation", neither for Dex files nor for Groovy scripts. Using the class loader to
 * load classes of a previously compiled script will run associated Dex without regard to it's {@link GroovyCodeSource}.
 * Parsing the script goes on without care for any existing compiled classes, those have to be removed externally.
 * Any already existing Dex files (e.g. from compilation of third-party jars) will be added to class path without
 * any checks for staleness or modifications.
 *
 * <p>
 *
 * The mechanics of class-to-dex conversion are a lot simpler, compared to dx. There are no threads. The splitting
 * in multiple dex files is done following two rules:
 *
 * <li>
 *     <ul> Each .jar file must fit wholly inside single .dex file
 *     <ul> If the amount of classes in single .dex exceeds some arbitrary (small) number, another dex file is created
 * </li>
 *
 * Due to the way Android VMs work there will be N memory-mapped files per DexGroovyClassloader instance, where N is
 * at least as big as number of extra JARs + 1 (for the main script file). Close the class loader to unmap those...
 *
 * wait, there is a catch!
 *
 * Closing this class loader currently DOES NOT work: it is supposed to, but as of Android KitKat (and probably every
 * other version up to modern days) there is no class unloading support at VM level. In practice, that means,
 * that killing the process is your only bet at releasing resources. If you try to overwrite the main jar/dex
 * file or any of additional dex files, that have been used to load at least one class, the VM will crash with SIGBUS
 * (because the mapped memory is still in use).
 *
 * This said, killing the process is the best way to unload classes in EVERY other JVM container I am aware of.
 *
 * Perhaps, it is actually better this way.
 */
@CompileStatic @PackageScope
final class DexGroovyClassloader extends GroovyClassLoader implements Closeable {
    private static final String TAG = "DexGroovyClassloader"

    private static final String DEX_SUFFIX = '.dex'

    private static final Attributes.Name CREATED_BY = new Attributes.Name('Created-By')
    private static final Attributes.Name MANIFEST_VERSION = new Attributes.Name('Manifest-Version')

    private static final Map<File, DexGroovyClassloader> cache = new ConcurrentReaderHashMap()

    private static volatile junk

    final Context context
    final File unitFile

    final DexOptions dexOptions = new DexOptions()

    final CfOptions cfOptions = new CfOptions()

    DexFile dexFile = null
    Set<String> classNames = null

    private volatile boolean closed

    private final CircularArray<LoadedDex> dexClassPath = new CircularArray<>()
    private final Set<String> pendingClasspath = new HashSet<>()

    private final ThreadLocal<Set<String>> locallyWanted = new ThreadLocal<>();

    { locallyWanted.set(new HashSet<>()) }

    public static DexGroovyClassloader getInstance(Context context,
                                                   File unitFile,
                                                   CompilerConfiguration configuration = new CompilerConfiguration())
    {
        DexGroovyClassloader classLoader

        synchronized (cache) {
            classLoader = cache.get(unitFile)

            if (classLoader) return classLoader

            classLoader = new DexGroovyClassloader(context, unitFile, configuration)

            cache.put(unitFile, classLoader)
        }

        if (unitFile.parentFile.exists()) {
            unitFile.parentFile.listFiles().each { File it ->
                if (it.name.endsWith('.jar'))
                    classLoader.dexClassPath.addLast(LoadedDex.loadDex(it.path, optimizedPathFor(it, unitFile.parentFile), 0))
            }
        }

        return classLoader
    }

    public static boolean cachedClassLoader(File unitFile) {
        return cache.containsKey(unitFile)
    }

    public static int getClassloadersCached() {
        return cache.size()
    }

    private DexGroovyClassloader(Context context, File unitFile, CompilerConfiguration configuration) {
        super(DexGroovyClassloader.class.classLoader, configuration) // XXX: revise is case of multidex etc.

        configuration.bytecodePostprocessor = new BytecodeProcessor() {
            @Override
            public byte[] processBytecode(String name, byte[] bytes) {
                def dexerFile = new DirectClassFile(bytes, "${name.replace('.', '/')}.class", false)
                dexerFile.attributeFactory = StdAttributeFactory.THE_ONE

                def classDefItem = CfTranslator.translate(dexerFile, bytes, cfOptions, dexOptions, dexFile)

                dexFile.add(classDefItem)
                classNames.add(name)

                return null
            }
        }

        configuration.optimizationOptions = [indy: false, int: true]

        configuration.targetBytecode = CompilerConfiguration.JDK6

        this.context = context.applicationContext

        this.unitFile = unitFile

        cfOptions.positionInfo = PositionList.LINES
        cfOptions.localInfo = true
        cfOptions.strictNameCheck = true
        cfOptions.optimize = false
        cfOptions.optimizeListFile = null
        cfOptions.dontOptimizeListFile = null
        cfOptions.statistics = false

        dexOptions.targetApiLevel = DexFormat.API_NO_EXTENDED_OPCODES
    }

    // from dalvik/system/DexPathList.java
    static String optimizedPathFor(File path, File optimizedDirectory) {
        /*
         * Get the filename component of the path, and replace the
         * suffix with ".dex" if that's not already the suffix.
         *
         * We don't want to use ".odex", because the build system uses
         * that for files that are paired with resource-only jar
         * files. If the VM can assume that there's no classes.dex in
         * the matching jar, it doesn't need to open the jar to check
         * for updated dependencies, providing a slight performance
         * boost at startup. The use of ".dex" here matches the use on
         * files in /data/dalvik-cache.
         */
        String fileName = path.name
        if (!fileName.endsWith(DEX_SUFFIX)) {
            int lastDot = fileName.lastIndexOf('.')
            if (lastDot < 0) {
                fileName += DEX_SUFFIX;
            } else {
                StringBuilder sb = new StringBuilder(lastDot + 4)
                sb.append(fileName, 0, lastDot)
                sb.append(DEX_SUFFIX)
                fileName = sb.toString()
            }
        }
        File result = new File(optimizedDirectory, fileName);
        return result.getPath();
    }

    // from dalvik/system/BaseDexClassLoader.java
    /**
     * Returns package information for the given package.
     * Unfortunately, instances of this class don't really have this
     * information, and as a non-secure {@code ClassLoader}, it isn't
     * even required to, according to the spec. Yet, we want to
     * provide it, in order to make all those hopeful callers of
     * {@code myClass.getPackage().getName()} happy. Thus we construct
     * a {@code Package} object the first time it is being requested
     * and fill most of the fields with dummy values. The {@code
     * Package} object is then put into the {@code ClassLoader}'s
     * package cache, so we see the same one next time. We don't
     * create {@code Package} objects for {@code null} arguments or
     * for the default package.
     *
     * <p>There is a limited chance that we end up with multiple
     * {@code Package} objects representing the same package: It can
     * happen when when a package is scattered across different JAR
     * files which were loaded by different {@code ClassLoader}
     * instances. This is rather unlikely, and given that this whole
     * thing is more or less a workaround, probably not worth the
     * effort to address.
     *
     * @param name the name of the class
     * @return the package information for the class, or {@code null}
     * if there is no package information available for it
     */
    @Override
    protected synchronized Package getPackage(String name) {
        if (name) {
            Package pack = super.getPackage(name);
            if (pack == null) {
                pack = definePackage(name, 'Unknown', '0.0', 'Unknown', 'Unknown', '0.0', 'Unknown', null)
            }
            return pack;
        }
        return null;
    }

    @Override
    Class<?> loadClass(String name) throws ClassNotFoundException {
        loadClass(name, true, true, false);
    }

    @Override
    Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
        loadClass(name, true, true, resolve);
    }

    @Override
    Class loadClass(String name, boolean lookupScriptFiles, boolean preferClassOverScript) throws ClassNotFoundException, CompilationFailedException {
        loadClass(name, lookupScriptFiles, preferClassOverScript, false)
    }

    @Override
    Class loadClass(String name, boolean lookupScriptFiles, boolean preferClassOverScript, boolean resolve) throws ClassNotFoundException, CompilationFailedException {
        if (name.startsWith('java.')) return parent.loadClass(name)

        def found = findLoadedClass(name)
        if (found) return found

        found = findClass(name)
        if (found) return found

        try {
            return parent.loadClass(name)
        } finally {
            locallyWanted.get().remove(name)
        }
    }

    private static final int CLASSES_IN_DEX_OPTIMAL = 70

    private final Lock lock = new ReentrantLock()

    @SuppressWarnings("GrDeprecatedAPIUsage")
    private Class makeDexFilesForSakeOf(String name) {
        def foundClass = null

        def basedOn = new StringBuilder()
        def createdDexFiles = new ArrayList<LoadedDex>()

        DexFile inProgress = null
        int classesWritten = 0

        def reusableByteBuffer = new byte[4096]
        def reusableByteStream = new ByteArrayOutputStream(4096)

        def urlsIterator = pendingClasspath.iterator()

        while (urlsIterator.hasNext()) {
            def anURL = urlsIterator.next()
            urlsIterator.remove()

            def file = anURL as File

            // check if we have already dealt with this one in the past
            def encodedDependencyName = nameUpTo(file, 4)

            def alreadyDexed = unitFile.parentFile.listFiles().find { File dexOrJar ->
                if (dexOrJar.name.endsWith('.jar')) {
                    return new ZipFile(dexOrJar).withCloseable {
                        return it.comment.indexOf(encodedDependencyName) != -1
                    }
                }

                return false
            }

            if (alreadyDexed) continue

            try {
                JarInputStream zipStream
                if (anURL.endsWith('.jar')) {
                    zipStream = new JarInputStream(new FileInputStream(file), false)
                } else {
                    zipStream = new JarInputStream(new URL("jar:" + file.toURL() + "!/classes.jar").openStream(), false)
                }

                zipStream.withCloseable { zip ->
                    JarEntry jarEntry
                    while ((jarEntry = zip.nextJarEntry)) {
                        if (jarEntry.directory || !jarEntry.name.endsWith('.class')) continue

                        reusableByteStream.reset()

                        int read
                        while ((read = zip.read(reusableByteBuffer)) != -1) {
                            reusableByteStream.write(reusableByteBuffer, 0, read)
                        }

                        try {
                            def bytes = reusableByteStream.toByteArray()
                            def dexerFile = new DirectClassFile(bytes, jarEntry.name, true)
                            dexerFile.attributeFactory = StdAttributeFactory.THE_ONE

                            if (!inProgress) inProgress = new DexFile(dexOptions)

                            def classDefItem = CfTranslator.translate(dexerFile, bytes, cfOptions, dexOptions, inProgress)

                            inProgress.add(classDefItem)
                            classesWritten++
                        } catch (RuntimeException ditchTheClass) {
                            // not PrintStackTrace, because the classes in the trace may not be "loaded" yet

                            Log.e(TAG, "Failed to dex $jarEntry.name: $ditchTheClass")
                        }
                    }
                }

                basedOn << encodedDependencyName

                // flush the dex, if necessary
                if (classesWritten >= CLASSES_IN_DEX_OPTIMAL) {
                    def randomFile = "$unitFile.parent/${UUID.randomUUID()}.jar" as File

                    def bytes = inProgress.toDex(null, false); inProgress = null;

                    def androidDex = addToDexFiles(bytes, randomFile, basedOn)

                    createdDexFiles << androidDex

                    classesWritten = 0
                    basedOn = new StringBuilder()
                }
            } catch (RuntimeException ditchTheJar) {
                Log.e(TAG, "Failed to jar some files: $ditchTheJar")
            }
        }

        if (!foundClass && inProgress && !inProgress.empty) {
            def randomFile = "$unitFile.parent/${UUID.randomUUID()}.jar" as File

            def bytes = inProgress.toDex(null, false); inProgress = null;

            def androidDex = addToDexFiles(bytes, randomFile, basedOn)

            createdDexFiles << androidDex
        }

        // add all created files atomically at once to prevent any kind of class loading recursion from busting us
        createdDexFiles.each {
            dexClassPath.addLast(it)
        }

        foundClass = findClass(name)

        junk = lock // flush teh caches

        return foundClass
    }

    private static String nameUpTo(File file, int depth) {
        final StringBuilder name = new StringBuilder()

        for (int i = 0; i < depth; i++) {
            name.insert(0, '/').insert(0, file.name)

            if (!file.parent)
                break

            file = file.parentFile
        }

        return name.toString()
    }

    private LoadedDex addToDexFiles(byte[] classesDex, File file, CharSequence metadata) {
        def backupFile = "${file.path}.bak" as File
        if (backupFile.exists()) {
            assert backupFile.delete()
        }

        try {
            def manifest = new Manifest()

            def attrs = manifest.mainAttributes
            attrs.put(MANIFEST_VERSION, '1.0')
            attrs.put(CREATED_BY, 'dx ' + Version.VERSION)
            attrs.putValue("Dex-Location", DexFormat.DEX_IN_JAR_NAME)

            new FileOutputStream(backupFile).withCloseable { fos ->
                new JarOutputStream(fos, manifest).withCloseable {
                    it.comment = metadata

                    def classes = new JarEntry(DexFormat.DEX_IN_JAR_NAME)
                    classes.size = classesDex.length
                    it.putNextEntry(classes)
                    it.write(classesDex)
                    it.closeEntry()
                    it.finish()
                    it.flush()
                    fos.flush()
                    fos.close()
                    it.close()
                }
            }
        } catch (Exception e) {
            assert backupFile.delete()

            throw e
        }

        assert backupFile.renameTo(file)

        def resultDex = LoadedDex.loadDex(file.path, optimizedPathFor(file, unitFile.parentFile), 0)

        return resultDex
    }

    @Override
    Class parseClass(GroovyCodeSource codeSource) throws CompilationFailedException {
        classNames = [] as Set
        dexFile = new DexFile(dexOptions)

        Class result = super.parseClass(codeSource)

        dexFile = null;
        classNames = null;

        return result
    }

    @Override
    protected ClassCollector createCollector(CompilationUnit unit, SourceUnit su) {
        return new DexFileCollector(this, unit, su)
    }

    @Override
    protected CompilationUnit createCompilationUnit(CompilerConfiguration config, CodeSource source) {
        return new TwistedCompilationUnit(config, source, this)
    }

    @Override
    Enumeration<URL> getResources(String resName) throws IOException {
        return parent.getResources(resName)
    }

    @Override
    URL getResource(String resName) {
        return parent.getResource(resName)
    }

    @Override
    URL findResource(String name) {
        throw new IllegalStateException('Must not be called')
    }

    @Override
    void addURL(URL newUrl) {
        super.addURL(newUrl)

        assert newUrl.file?.endsWith('.jar') || newUrl.file?.endsWith('.aar') && !newUrl.protocol || 'file' == newUrl.protocol,
                "$newUrl has unsupported type: only local .jar and .aar files are supported!"

        pendingClasspath.add(newUrl.file)
    }

    @Override
    public Class defineClass(String name, byte[] dalvikBytecode) {
        throw new IllegalStateException('Must not be called')
    }

    @Override
    Class defineClass(ClassNode classNode, String file, String newCodeBase) {
        throw new IllegalStateException('Must not be called')
    }

    @Override
    protected Class<?> findClass(String className) throws ClassNotFoundException {
        def wanted = locallyWanted.get()

        if (className in wanted) return null

        def found
        lock.lockInterruptibly()
        try {
            // plz, be already loaded
            if ((found = findLoadedClass(className)))
                return found

            // asshole...

            wanted.add(className)

            def dexCount = dexClassPath.size()
            dexCount.times {
                def got = dexClassPath.popLast()

                dexClassPath.addFirst(got)

                if (!found) found = got.loadClass(className, this)
            }

            if (!pendingClasspath.isEmpty()) {
                found = makeDexFilesForSakeOf(className)
            }

            return found
        } finally {
            if (found) wanted.remove(className)

            lock.unlock()
        }
    }

    static File makeUnitFile(Context context, String unitId) {
        def unitDir = new File(new ContextCompat().getCodeCacheDir(context), unitId)

        unitDir.mkdirs()

        return "${unitDir}/${unitId}.jar" as File
    }

    @Override
    void close() throws IOException {
        classCache.clear()

        def dexCount = dexClassPath.size()
        dexCount.times {
            dexClassPath.popLast().close()
        }

        closed = true
    }

    boolean isClosed() {
        return closed
    }

    private static class DexFileCollector extends ClassCollector {
        private final Collection<Class> loadedClasses = new ArrayList<>();

        private final SourceUnit su;
        private final CompilationUnit unit;
        private final DexGroovyClassloader cl

        private String generatedClassName
        private Class generatedClass

        protected DexFileCollector(DexGroovyClassloader cl, CompilationUnit unit, SourceUnit su) {
            super(new InnerLoader(cl), unit, su);

            this.cl = cl
            this.unit = unit
            this.su = su
        }

        @Override
        protected Class createClass(byte[] code, ClassNode classNode) {
            def className = classNode.name

            if (!unit.configuration || !unit.configuration.bytecodePostprocessor) {
                Thread.sleep(10000)
            }

            unit.configuration.bytecodePostprocessor.processBytecode(className, code)

            if (generatedClassName == null) {
                SourceUnit msu = null
                ClassNode main = null

                ModuleNode mn = classNode.module

                if(mn != null) {
                    msu = mn.context
                    main = (ClassNode) mn.classes.get(0)
                }

                if(msu == su && main == classNode) {
                    this.generatedClassName = className;
                }
            }

            return null
        }

        @Override
        public Collection<Class> getLoadedClasses() {
            if (!loadedClasses.isEmpty())
                return loadedClasses;

            try {
                def androidDexFile = cl.addToDexFiles(cl.dexFile.toDex(null, false), cl.unitFile, su.name)

                try {
                    for (String className : cl.classNames) {
                        def tehClass = androidDexFile.loadClass(className, cl)

                        loadedClasses.add(tehClass)

                        if (className == generatedClassName) {
                            generatedClass = tehClass
                        }
                    }
                } finally {
                    androidDexFile.close()
                }
            } finally {
                cl.classNames = null
                cl.dexFile = null
            }

            return loadedClasses;
        }
    }

    private static class TwistedCompilationUnit extends CompilationUnit {
        TwistedCompilationUnit(CompilerConfiguration d, CodeSource g, GroovyClassLoader z) {
            super(d, g, z)
        }

        @Override
        void setClassgenCallback(ClassgenCallback visitor) {
            Log.e('OMGWTF!!!!', visitor.class.toString())

            super.setClassgenCallback(visitor)
        }
    }
}