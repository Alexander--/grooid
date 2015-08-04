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
package groovy.grape

import android.content.Context
import android.os.Environment
import android.support.v4.content.ContextCompat
import android.support.v4.os.EnvironmentCompat
import groovy.transform.CompileStatic
import net.sf.fakenames.app.BuildConfig
import org.apache.ivy.Ivy
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager
import org.apache.ivy.core.cache.ResolutionCacheManager
import org.apache.ivy.core.event.IvyEvent
import org.apache.ivy.core.event.IvyListener
import org.apache.ivy.core.event.download.PrepareDownloadEvent
import org.apache.ivy.core.event.resolve.StartResolveEvent
import org.apache.ivy.core.module.descriptor.Artifact
import org.apache.ivy.core.module.descriptor.Configuration
import org.apache.ivy.core.module.descriptor.DefaultArtifact
import org.apache.ivy.core.module.descriptor.DefaultDependencyArtifactDescriptor
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor
import org.apache.ivy.core.module.descriptor.DefaultExcludeRule
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ArtifactId
import org.apache.ivy.core.module.id.ArtifactRevisionId
import org.apache.ivy.core.module.id.ModuleId
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.ArtifactDownloadReport
import org.apache.ivy.core.report.ResolveReport
import org.apache.ivy.core.resolve.IvyNode
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.matcher.ExactPatternMatcher
import org.apache.ivy.plugins.matcher.PatternMatcher
import org.apache.ivy.plugins.parser.ModuleDescriptorParser
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry
import org.apache.ivy.plugins.parser.m2.BarebonePomParser
import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorParser
import org.apache.ivy.plugins.resolver.ChainResolver
import org.apache.ivy.plugins.resolver.IBiblioResolver
import org.apache.ivy.util.DefaultMessageLogger
import org.apache.ivy.util.Message
import org.codehaus.groovy.plugin.GroovyRunner
import org.codehaus.groovy.reflection.CachedClass
import org.codehaus.groovy.reflection.ClassInfo
import org.codehaus.groovy.runtime.metaclass.MetaClassRegistryImpl
import org.w3c.dom.Element

import javax.xml.parsers.DocumentBuilderFactory
import java.util.jar.JarFile
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

@CompileStatic
final class NastyGrapes implements GrapeEngine {
    static void init(Context context) {
        if (!Grape.@instance) {
            def nasty = new NastyGrapes(context)

            Grape.@instance = nasty
        }
    }

    private final Map<Object, Set> exclusiveGrabArgs = [
            ['group', 'groupId', 'organisation', 'organization', 'org'],
            ['module', 'artifactId', 'artifact'],
            ['version', 'revision', 'rev'],
            ['conf', 'scope', 'configuration'],
    ].inject([:], {m, g -> g.each {a -> m[a] = (g - a) as Set};  m}) as Map<Object, Set>

    private @Lazy Set<String> resolvedDependencies = []
    private @Lazy Set<String> downloadedArtifacts = []

    private @Lazy Map<ClassLoader, Set<IvyGrabRecord>> loadedDeps = new WeakHashMap<ClassLoader, Set<IvyGrabRecord>>()

    // set that stores the IvyGrabRecord(s) for all the dependencies in each grab() call
    private @Lazy Set<IvyGrabRecord> grabRecordsForCurrDependencies = new LinkedHashSet<IvyGrabRecord>()

    // we keep the settings so that addResolver can add to the resolver chain
    private @Lazy IvySettings settings = {
        def result = new IvySettings()

        result.load(NastyGrapes.getResource("ivyderoid.xml"))

        result.setVariable("ivy.default.configuration.m2compatible", "true")

        result.defaultCache = grapeCacheDir

        result.defaultRepositoryCacheManager = new Cache()

        return result
    }()

    private @Lazy Ivy ivyInstance = {
        System.setProperty('android.ivy.home', "$grapeCacheDir")

        Ivy.newInstance(settings)
    }()

    private final Context context

    public NastyGrapes(Context context) {
        this.context = context

        int logLevel = BuildConfig.DEBUG ? Message.MSG_DEBUG : Message.MSG_WARN
        Message.defaultLogger = new DefaultMessageLogger(logLevel)
        System.setProperty('android.ivy.home', "$grapeCacheDir")
    }

    @Override
    public grab(String endorsedModule) {
        return grab(group:'groovy.endorsed', module:endorsedModule, version:GroovySystem.version)
    }

    @Override
    public grab(Map args) {
        return grab(args, args)
    }

    @Override
    public grab(Map args, Map... dependencies) {
        def loader = null
        grabRecordsForCurrDependencies.clear()
        try {
            // identify the target classloader early, so we fail before checking repositories
            loader = chooseClassLoader(
                    classLoader:args.remove('clazzLoader'),
                    refObject:args.remove('refObject')
            )

            // check for non-fail null.
            // If we were in fail mode we would have already thrown an exception
            if (!loader) return

            def uris = resolve(loader, args, dependencies)
            for (URI uri in uris) {
                loader.addURL(uri.toURL())
            }
            for (URI uri in uris) {
                //TODO check artifact type, jar vs library, etc
                File file = new File(uri)
                processCategoryMethods(loader, file)
                processOtherServices(loader, file)
            }
        } catch (Exception e) {
            // clean-up the state first
            Set<IvyGrabRecord> grabRecordsForCurrLoader = getLoadedDepsForLoader(loader)
            grabRecordsForCurrLoader.removeAll(grabRecordsForCurrDependencies)
            grabRecordsForCurrDependencies.clear()

            if (args.noExceptions) {
                return e
            }
            throw e
        }
        return null
    }

    @Override
    public Map[] listDependencies (ClassLoader classLoader) {
        if (classLoader in loadedDeps) {
            def loaded = loadedDeps[classLoader]

            def results = new Map[loaded.size()]

            for (int i = 0; i < loaded.size(); i++) {
                def grabbed = loaded[i]

                def dep =  [
                        group : grabbed.mrid.organisation,
                        module : grabbed.mrid.name,
                        version : grabbed.mrid.revision
                ]
                if (grabbed.conf != ['default']) {
                    dep.conf = grabbed.conf
                }
                if (grabbed.changing) {
                    dep.changing = grabbed.changing
                }
                if (!grabbed.transitive) {
                    dep.transitive = grabbed.transitive
                }
                if (!grabbed.force) {
                    dep.force = grabbed.force
                }
                if (grabbed.classifier) {
                    dep.classifier = grabbed.classifier
                }
                if (grabbed.ext) {
                    dep.ext = grabbed.ext
                }
                if (grabbed.type) {
                    dep.type = grabbed.type
                }

                results[i] = dep
            }

            return results
        }
        return null
    }

    @Override
    public void addResolver(Map<String, Object> args) {
        ChainResolver chainResolver = settings.getResolver('downloadGrapes') as ChainResolver

        IBiblioResolver resolver = new IBiblioResolver(name: args.name?.toString(), root: args.root?.toString(),
                m2compatible:(Boolean.valueOf((String) args.m2Compatible) ?: true), settings:settings)

        chainResolver.add(resolver)

        ivyInstance = Ivy.newInstance(settings)
        resolvedDependencies = []
        downloadedArtifacts = []
    }

    private static volatile junk

    public File getGrapeCacheDir() {
        def root

        def initialOptions = ContextCompat.getExternalCacheDirs(context)
        if (initialOptions && initialOptions[0] && Environment.MEDIA_MOUNTED == EnvironmentCompat.getStorageState(initialOptions[0])) {
            root = initialOptions[0]
        } else {
            root = context.cacheDir
        }

        def cache =  new File(root, 'grapes')

        if (!cache.exists() && !cache.mkdirs()) throw new IOException("failed to ensure $cache")

        return cache
    }

    public GroovyClassLoader chooseClassLoader(Map<?, ?> args) {
        def loader = args.classLoader as ClassLoader
        if (!isValidTargetClassLoader(loader)) {
            loader = args.refObject?.class?.classLoader

            while (loader && !isValidTargetClassLoader(loader)) {
                loader = loader.parent
            }

            if (!isValidTargetClassLoader(loader)) {
                loader = Thread.currentThread().contextClassLoader ?: NastyGrapes.class.classLoader
            }
        }
        return loader as GroovyClassLoader
    }

    private boolean isValidTargetClassLoader(loader) {
        return isValidTargetClassLoaderClass(loader?.class)
    }

    private boolean isValidTargetClassLoaderClass(Class loaderClass) {
        return loaderClass &&
                ((loaderClass.name == 'groovy.lang.GroovyClassLoader') || isValidTargetClassLoaderClass(loaderClass.superclass))
    }

    public static IvyGrabRecord createGrabRecord(Map<String, Object> deps) {
        // parse the actual dependency arguments
        String module =  deps.module ?: deps.artifactId ?: deps.artifact
        if (!module) {
            throw new RuntimeException('grab requires at least a module: or artifactId: or artifact: argument')
        }

        String groupId = deps.group ?: deps.groupId ?: deps.organisation ?: deps.organization ?: deps.org ?: ''
        String ext = deps.ext ?: deps.type ?: ''
        String type = deps.type ?: ''

        //TODO accept ranges and decode them?  except '1.0.0'..<'2.0.0' won't work in groovy
        String version = deps.version ?: deps.revision ?: deps.rev ?: '*'

        if ('*' == version) version = 'latest.default'

        ModuleRevisionId mrid = ModuleRevisionId.newInstance(groupId, module, version)

        boolean force      = deps.containsKey('force')      ? Boolean.valueOf("$deps.force")      : true
        boolean changing   = deps.containsKey('changing')   ? Boolean.valueOf("$deps.changing")   : false
        boolean transitive = deps.containsKey('transitive') ? Boolean.valueOf("$deps.transitive") : true

        def conf = Arrays.asList(deps?.conf?.toString() ?: deps?.scope?.toString() ?: deps?.configuration?.toString() ?: 'default') as List<String>
        String classifier = deps?.classifier?.toString() ?: null

        return new IvyGrabRecord(mrid:mrid, conf:conf, changing:changing, transitive:transitive, force:force, classifier:classifier, ext:ext, type:type)
    }

    private static processCategoryMethods(ClassLoader loader, File file) {
        // register extension methods if jar
        if (file.name.toLowerCase().endsWith('.jar')) {
            def mcRegistry = GroovySystem.metaClassRegistry
            if (mcRegistry instanceof MetaClassRegistryImpl) {
                try {
                    JarFile jar = new JarFile(file)
                    def entry = jar.getEntry(MetaClassRegistryImpl.MODULE_META_INF_FILE)
                    if (entry) {
                        Properties props = new Properties()
                        props.load(jar.getInputStream(entry))
                        Map<CachedClass, List<MetaMethod>> metaMethods = new HashMap<CachedClass, List<MetaMethod>>()
                        mcRegistry.registerExtensionModuleFromProperties(props, loader, metaMethods)
                        // add old methods to the map
                        metaMethods.each { CachedClass c, List<MetaMethod> methods ->
                            // GROOVY-5543: if a module was loaded using grab, there are chances that subclasses
                            // have their own ClassInfo, and we must change them as well!
                            Set<CachedClass> classesToBeUpdated = [c] as Set
                            ClassInfo.onAllClassInfo { ClassInfo info ->
                                if (c.theClass.isAssignableFrom(info.cachedClass.theClass)) {
                                    classesToBeUpdated << info.cachedClass
                                }
                            }
                            classesToBeUpdated*.addNewMopMethods(methods)
                        }
                    }
                }
                catch(ZipException zipException) {
                    throw new RuntimeException("Grape could not load jar '$file'", zipException)
                }
            }
        }
    }

    static void processOtherServices(ClassLoader loader, File f) {
        try {
            ZipFile zf = new ZipFile(f)
            ZipEntry serializedCategoryMethods = zf.getEntry("META-INF/services/org.codehaus.groovy.runtime.SerializedCategoryMethods")
            if (serializedCategoryMethods != null) {
                processSerializedCategoryMethods(zf.getInputStream(serializedCategoryMethods))
            }
            ZipEntry pluginRunners = zf.getEntry("META-INF/services/org.codehaus.groovy.plugins.Runners")
            if (pluginRunners != null) {
                processRunners(zf.getInputStream(pluginRunners), f.getName(), loader)
            }
        } catch(ZipException ignore) {
            // ignore files we can't process, e.g. non-jar/zip artifacts
            // TODO log a warning
        }
    }

    static void processSerializedCategoryMethods(InputStream is) {
        is.text.readLines().each {
            println it.trim() // TODO implement this or delete it
        }
    }

    static void processRunners(InputStream is, String name, ClassLoader loader) {
        is.text.readLines().each {
            GroovySystem.RUNNER_REGISTRY[name] = loader.loadClass(it.trim()).newInstance() as GroovyRunner
        }
    }

    public ResolveReport getDependencies(Map args, IvyGrabRecord... grabRecords) {
        ResolutionCacheManager cacheManager = ivyInstance.resolutionCacheManager

        def millis = System.currentTimeMillis()
        def md = new DefaultModuleDescriptor(ModuleRevisionId
                .newInstance("caller", "all-caller", "working" + millis.toString()[-2..-1]), "integration", null, true)
        md.addConfiguration(new Configuration('default'))
        md.setLastModified(millis)

        addExcludesIfNeeded(args, md)

        for (IvyGrabRecord grabRecord : grabRecords) {
            def conf = grabRecord.conf ?: ['*']
            DefaultDependencyDescriptor dd = md.dependencies.find {it.dependencyRevisionId.equals(grabRecord.mrid)} as DefaultDependencyDescriptor
            if (dd) {
                createAndAddDependencyArtifactDescriptor(dd, grabRecord, conf)
            } else {
                dd = new DefaultDependencyDescriptor(md, grabRecord.mrid, grabRecord.force,
                        grabRecord.changing, grabRecord.transitive)
                conf.each {dd.addDependencyConfiguration('default', it)}
                createAndAddDependencyArtifactDescriptor(dd, grabRecord, conf)
                md.addDependency(dd)
            }
        }

        // resolve grab and dependencies
        ResolveOptions resolveOptions = new ResolveOptions()
                .setConfs(['default'] as String[])
                .setOutputReport(false)
                .setValidate(args.containsKey('validate') ? Boolean.valueOf("$args.validate") : false)

        ivyInstance.settings.defaultResolver = args.autoDownload ? 'downloadGrapes' : 'cachedGrapes'
        if (args.disableChecksums) {
            ivyInstance.settings.setVariable('ivy.checksums', '')
        }
        boolean reportDownloads = System.getProperty('groovy.grape.report.downloads', 'false') == 'true'
        if (reportDownloads) {
            ivyInstance.eventManager.addIvyListener([progress:{ IvyEvent ivyEvent ->
                switch(ivyEvent) {
                case StartResolveEvent:
                    (ivyEvent as StartResolveEvent).moduleDescriptor.dependencies.each { it ->
                        def name = it.toString()
                        if (!resolvedDependencies.contains(name)) {
                            resolvedDependencies << name
                            System.err.println "Resolving " + name
                        }
                    }
                    break
                case PrepareDownloadEvent:
                    (ivyEvent as PrepareDownloadEvent).artifacts.each { it ->
                        def name = it.toString()
                        if (!downloadedArtifacts.contains(name)) {
                            downloadedArtifacts << name
                            System.err.println "Preparing to download artifact " + name
                        }
                    }
                    break
            } } ] as IvyListener)
        }

        ResolveReport report = null
        int attempt = 8 // max of 8 times
        while (true) {
            try {
                report = ivyInstance.resolve(md, resolveOptions)
                break
            } catch(IOException ioe) {
                if (attempt--) {
                    if (reportDownloads)
                        System.err.println "Grab Error: retrying..."
                    sleep attempt > 4 ? 350 : 1000
                    continue
                }
                throw new RuntimeException("Error grabbing grapes -- $ioe.message")
            }
        }

        if (report.hasError()) {
            throw new RuntimeException("Error grabbing Grapes -- $report.allProblemMessages")
        }
        if (report.downloadSize && reportDownloads) {
            System.err.println "Downloaded ${report.downloadSize >> 10} Kbytes in ${report.downloadTime}ms:\n  ${report.allArtifactsReports*.toString().join('\n  ')}"
        }
        md = report.moduleDescriptor

        if (!args.preserveFiles) {
            cacheManager.getResolvedIvyFileInCache(md.moduleRevisionId).delete()
            cacheManager.getResolvedIvyPropertiesInCache(md.moduleRevisionId).delete()
        }

        return report
    }

    private static void createAndAddDependencyArtifactDescriptor(DefaultDependencyDescriptor dd, IvyGrabRecord grabRecord, List<String> conf) {
        // TODO: find out "unknown" reason and change comment below - also, confirm conf[0] check vs conf.contains('optional')
        if (conf[0]!="optional" || grabRecord.classifier) {  // for some unknown reason optional dependencies should not have an artifactDescriptor
            def dad = new DefaultDependencyArtifactDescriptor(dd,
                    grabRecord.mrid.name, grabRecord.type ?: 'jar', grabRecord.ext ?: 'jar', null, grabRecord.classifier ? [classifier: grabRecord.classifier] : null)
            conf.each { dad.addConfiguration(it) }
            dd.addDependencyArtifact('default', dad)
        }
    }

    public void uninstallArtifact(String group, String module, String rev) {
        // TODO consider transitive uninstall as an option
        Pattern ivyFilePattern = ~/ivy-(.*)\.xml/ //TODO get pattern from ivy conf
        grapeCacheDir.eachDir { File groupDir ->
            if (groupDir.name == group) groupDir.eachDir { File moduleDir ->
                if (moduleDir.name == module) moduleDir.eachFileMatch(ivyFilePattern) { File ivyFile ->
                    def m = ivyFilePattern.matcher(ivyFile.name)
                    if (m.matches() && m.group(1) == rev) {
                        // TODO handle other types? e.g. 'dlls'
                        def jardir = new File(moduleDir, 'jars')
                        if (!jardir.exists()) return
                        def dbf = DocumentBuilderFactory.newInstance()
                        def db = dbf.newDocumentBuilder()
                        def root = db.parse(ivyFile).documentElement
                        def publis = root.getElementsByTagName('publications')
                        for (int i=0; i<publis.length;i++) {
                            def artifacts = (publis.item(i) as Element).getElementsByTagName('artifact')
                            for (int j=0; j<artifacts.length; j++) {
                                def artifact = artifacts.item(j)
                                def attrs = artifact.attributes
                                def name = attrs.getNamedItem('name').getTextContent() + "-$rev"
                                def classifier = attrs.getNamedItemNS("m", "classifier")?.getTextContent()
                                if (classifier) name += "-$classifier"
                                name += ".${attrs.getNamedItem('ext').getTextContent()}"
                                def jarfile = new File(jardir, name)
                                if (jarfile.exists()) {
                                    println "Deleting ${jarfile.name}"
                                    jarfile.delete()
                                }
                            }
                        }
                        ivyFile.delete()
                    }
                }
            }
        }
    }

    private static addExcludesIfNeeded(Map<?, Object> args, DefaultModuleDescriptor md) {
        if (!args.containsKey('excludes')) return

        args.excludes.each { Map map ->
            def excludeRule = new DefaultExcludeRule(new ArtifactId(
                    new ModuleId("${map['group']}", "${map['module']}"),
                    PatternMatcher.ANY_EXPRESSION,
                    PatternMatcher.ANY_EXPRESSION,
                    PatternMatcher.ANY_EXPRESSION),
                    ExactPatternMatcher.INSTANCE, null)
            excludeRule.addConfiguration('default')
            md.addExcludeRule(excludeRule)
        }
    }

    @Override
    public Map<String, Map<String, List<String>>> enumerateGrapes() {
        Map<String, Map<String, List<String>>> bunches = [:]
        Pattern ivyFilePattern = ~/ivy-(.*)\.xml/ //TODO get pattern from ivy conf
        grapeCacheDir.eachDir { File groupDir ->
            Map<String, List<String>> grapes = [:]
            bunches[groupDir.name] = grapes
            groupDir.eachDir { File moduleDir ->
                List<String> versions = []
                moduleDir.eachFileMatch(ivyFilePattern) {File ivyFile ->
                    def m = ivyFilePattern.matcher(ivyFile.name)
                    if (m.matches()) versions += m.group(1)
                }
                grapes[moduleDir.name] = versions
            }
        }
        return bunches
    }

    @Override
    public URI[] resolve(Map args, Map ... dependencies) {
        resolve(args, null, dependencies)
    }

    @Override
    public URI[] resolve(Map args, List depsInfo, Map ... dependencies) {
        // identify the target classloader early, so we fail before checking repositories
        def loader = chooseClassLoader(
                classLoader: args.remove('clazzLoader'),
                refObject: args.remove('refObject')
        )

        // check for non-fail null.
        // If we were in fail mode we would have already thrown an exception
        if (!loader) return null

        resolve(loader, args, depsInfo, dependencies)
    }

    URI[] resolve(ClassLoader loader, Map args, Map... dependencies) {
        return resolve(loader, args, null, dependencies)
    }

    URI[] resolve(ClassLoader loader, Map args, List depsInfo, Map<String, Object>... dependencies) {
        // check for mutually exclusive arguments
        Set keys = args.keySet()
        keys.each {a ->
            Set badArgs = exclusiveGrabArgs[a]
            if (badArgs && !badArgs.disjoint(keys)) {
                throw new RuntimeException("Mutually exclusive arguments passed into grab: ${keys.intersect((Iterable) badArgs) + a}")
            }
        }

        boolean populateDepsInfo = (depsInfo != null)

        Set<IvyGrabRecord> localDeps = getLoadedDepsForLoader(loader)

        dependencies.each { Map<String, Object> it ->
            IvyGrabRecord igr = createGrabRecord(it)
            grabRecordsForCurrDependencies.add(igr)
            localDeps.add(igr)
        }
        // the call to reverse ensures that the newest additions are in
        // front causing existing dependencies to come last and thus
        // claiming higher priority.  Thus when module versions clash we
        // err on the side of using the class already loaded into the
        // classloader rather than adding another jar of the same module
        // with a different version
        ResolveReport report = getDependencies(args, localDeps.asList().reverse() as IvyGrabRecord[])

        List<URI> results = []
        for (ArtifactDownloadReport adl in report.allArtifactsReports) {
            //TODO check artifact type, jar vs library, etc
            if (adl.localFile) {
                results += adl.localFile.toURI()
            }
        }

        if (populateDepsInfo) {
            def deps = report.dependencies
            deps.each { depNode ->
                def id = (depNode as IvyNode).id
                depsInfo << ['group' : id.organisation, 'module' : id.name, 'revision' : id.revision]
            }
        }

        return results as URI[]
    }

    private Set<IvyGrabRecord> getLoadedDepsForLoader(ClassLoader loader) {
        Set<IvyGrabRecord> localDeps = loadedDeps.get(loader)
        if (localDeps == null) {
            // use a linked set to preserve initial insertion order
            localDeps = new LinkedHashSet<IvyGrabRecord>()
            loadedDeps.put(loader, localDeps)
        }
        return localDeps
    }

    private static final class Cache extends DefaultRepositoryCacheManager {
        @Override
        protected ModuleDescriptorParser getModuleDescriptorParser(File moduleDescriptorFile) {
            return BarebonePomParser.instance
        }
    }
}