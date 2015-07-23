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
package net.sf.fakenames.app;

import android.content.Context
import android.support.v4.content.ContextCompat
import com.android.dx.Version;
import com.android.dx.dex.DexFormat;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.cf.CfTranslator;
import com.android.dx.dex.code.PositionList;
import com.android.dx.dex.file.DexFile;
import dalvik.system.DexClassLoader
import groovy.lang.GroovyClassLoader.ClassCollector;
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.BytecodeProcessor;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;

import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

@CompileStatic  @PackageScope
final class DexGroovyClassloader extends GroovyClassLoader {
    static final Attributes.Name CREATED_BY = new Attributes.Name('Created-By')
    static final Attributes.Name MANIFEST_VERSION = new Attributes.Name('Manifest-Version')

    final Context context
    final File unitFile

    final DexOptions dexOptions = new DexOptions()

    final CfOptions cfOptions = new CfOptions()

    DexFile dexFile
    Set<String> classNames

    public DexGroovyClassloader(Context context, File unitFile) {
        this(context, unitFile, new CompilerConfiguration())
    }

    public DexGroovyClassloader(Context context, File unitFile, CompilerConfiguration configuration) {
        super(DexGroovyClassloader.class.classLoader, configuration) // XXX: revise is case of multidex etc.

        configuration.bytecodePostprocessor = new BytecodeProcessor() {
            @Override
            public byte[] processBytecode(String name, byte[] bytes) {
                def classDefItem = CfTranslator.translate("${name.replace('.', '/')}.class",
                        bytes, cfOptions, dexOptions)

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

    @Override
    Class loadClass(String name, boolean lookupScriptFiles, boolean preferClassOverScript, boolean resolve) throws ClassNotFoundException, CompilationFailedException {
        return getClassCacheEntry(name) ?: parent.loadClass(name)
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
    Enumeration<URL> getResources(String resName) throws IOException {
        return parent.getResources(resName)
    }

    @Override
    URL getResource(String resName) {
        return parent.getResource(resName)
    }

    @Override
    public Class defineClass(String name, byte[] dalvikBytecode) {
        // called by ClassCollector, when processing of bytecode is complete
        return Void.class;
    }

    public static DexClassLoader createParent(Context context, File unitFile, ClassLoader parent) {
        return new DexClassLoader(unitFile as String, unitFile.parent,
                context.applicationInfo.nativeLibraryDir, parent);
    }

    static File makeUnitFile(Context context, String unitId) {
        def unitDir = new File(new ContextCompat().getCodeCacheDir(context), unitId)

        unitDir.mkdirs()

        return "${unitDir}/${unitId}.jar" as File
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
                new FileOutputStream(cl.unitFile).withCloseable { fos ->
                    new JarOutputStream(fos, makeManifest()).withCloseable {
                        byte[] dalvikBytecode = cl.dexFile.toDex(null, false)

                        def classes = new JarEntry(DexFormat.DEX_IN_JAR_NAME);
                        classes.size = dalvikBytecode.length
                        it.putNextEntry(classes)
                        it.write(dalvikBytecode)
                        it.closeEntry()
                        it.finish()
                        it.flush()
                        fos.flush()
                        fos.close()
                        it.close()
                    }
                }


                def dexCl = createParent(cl.context, cl.unitFile, cl)

                for (String className : cl.classNames) {
                    def tehClass = dexCl.loadClass(className)

                    loadedClasses.add(tehClass)

                    if (className == generatedClassName) {
                        generatedClass = tehClass
                    }
                }
            } finally {
                cl.classNames = null
                cl.dexFile = null
            }

            return loadedClasses;
        }

        private static Manifest makeManifest() {
            def manifest = new Manifest()

            def attribs = manifest.mainAttributes
            attribs.put(MANIFEST_VERSION, '1.0')
            attribs.put(CREATED_BY, 'dx ' + Version.VERSION)
            attribs.putValue("Dex-Location", DexFormat.DEX_IN_JAR_NAME)

            return manifest;
        }
    }
}