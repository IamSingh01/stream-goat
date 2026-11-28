package com.lagradost.cloudstream3.linux

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.extractorApis
import android.content.res.Resources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Same fields as upstream PluginData, plus the repository it came from. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
    val repositoryUrl: String? = null,
    val name: String? = null,
)

class LoadedPlugin(
    val data: PluginData,
    val manifest: BasePlugin.Manifest,
    val instance: BasePlugin,
    val loader: PluginClassLoader,
) {
    val providers: List<MainAPI> get() = APIHolder.allProviders.filter { it.sourcePlugin == instance.filename }
    val extractorCount: Int get() = extractorApis.count { it.sourcePlugin == instance.filename }
}

data class LoadProgress(val done: Int, val total: Int) {
    val running: Boolean get() = done < total
}

object PluginManager {
    private const val PLUGINS_KEY = "PLUGINS_KEY"
    private val prefs get() = Platform.context.getSharedPrefs()

    val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    val failures = ConcurrentHashMap<String, Throwable>()

    private val _progress = MutableStateFlow(LoadProgress(0, 0))
    val progress: StateFlow<LoadProgress> = _progress

    /** Bumped whenever the set of providers changes, so screens can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    // Home must not choose a fallback provider until the first installed-extension scan has finished.
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete

    private fun changed() {
        _version.value++
    }

    /** For other provider sources (Stremio add-ons, profile switches) to refresh screens too. */
    fun bumpVersion() = changed()

    /** register* calls in Plugin.load mutate global lists; translation and class loading stay parallel. */
    private val registerLock = Any()

    fun installed(): List<PluginData> =
        prefs.getString(PLUGINS_KEY, null)?.let { mapper.readValue<List<PluginData>>(it) } ?: emptyList()

    private fun saveInstalled(list: List<PluginData>) = synchronized(this) {
        prefs.edit().putString(PLUGINS_KEY, mapper.writeValueAsString(list)).apply()
    }

    private fun record(data: PluginData) = synchronized(this) {
        saveInstalled(installed().filter { it.internalName != data.internalName } + data)
    }

    /**
     * Loads every installed plugin, like upstream's loadAllOnlinePlugins + loadAllLocalPlugins,
     * but in parallel: first-run .cs3 translation is CPU bound and dominates startup.
     */
    suspend fun loadAll(): Map<String, Throwable> = coroutineScope {
        Platform.init()
        _initialLoadComplete.value = false
        val savedProvider = Settings.lastHomeProvider.orEmpty()
        val savedPlugin = Settings.lastHomePlugin
        // Restore the extension backing the saved Home source first; remaining extensions follow.
        val all = installed().sortedBy { data ->
            when {
                savedPlugin.isNotBlank() && data.internalName.equals(savedPlugin, true) -> 0
                savedProvider.isNotBlank() && (data.name.equals(savedProvider, true) || data.internalName.equals(savedProvider, true)) -> 1
                else -> 2
            }
        }
        _progress.value = LoadProgress(0, all.size)
        suspend fun loadOne(data: PluginData) = async(Engine.dispatcher) {
            val file = File(data.filePath)
            if (file.exists()) loadPlugin(file, data)
            else {
                // A restored backup (maybe from another device) lists extensions without their files: download them.
                runCatching { redownload(data) }
                    .onSuccess { (downloaded, fresh) -> failures.remove(data.internalName); loadPlugin(downloaded, fresh) }
                    .onFailure { failures[data.internalName] = IllegalStateException("Its file is missing and couldn't be downloaded again (${it.message ?: it}). Use Reinstall.") }
            }
            _progress.value = _progress.value.let { it.copy(done = it.done + 1) }
        }

        // Do not even schedule the other extensions until the saved Home extension has
        // registered its providers. This makes the selected source usable as early as possible.
        val saved = all.firstOrNull { data ->
            (savedPlugin.isNotBlank() && data.internalName.equals(savedPlugin, true)) ||
                (savedPlugin.isBlank() && savedProvider.isNotBlank() &&
                    (data.name.equals(savedProvider, true) || data.internalName.equals(savedProvider, true)))
        }
        saved?.let { loadOne(it).await() }
        all.filterNot { it === saved }.map { loadOne(it) }.awaitAll()
        System.err.println("I/plugins: loaded ${loaded.size}/${all.size} extensions")
        // The desktop marks initial loading complete only after built-in Stremio add-ons
        // have also been restored. A 0/0 progress value is never treated as readiness.
        changed()
        failures
    }

    /** Called by desktop startup after extension registration and Stremio add-on restore. */
    fun completeInitialLoad() {
        _initialLoadComplete.value = true
        changed()
    }

    /** Port of upstream PluginManager.loadPlugin (app/.../plugins/PluginManager.kt). */
    fun loadPlugin(file: File, data: PluginData): Result<LoadedPlugin> = runCatching {
        loaded[file.absolutePath]?.let { return@runCatching it }

        // .jar = cross-platform build, already JVM bytecode. .cs3 = Dalvik, translate first.
        val jar = if (file.extension == "jar") file else DexTranslator.translate(file)
        val loader = PluginClassLoader(jar, file)

        val manifest = loader.getResourceAsStream("manifest.json")?.use {
            mapper.readValue<BasePlugin.Manifest>(it.readBytes())
        } ?: BasePlugin.Manifest().apply {
            name = data.name ?: data.internalName
            version = data.version
            pluginClassName = loader.findAnnotatedPluginClass()
        }
        val className = manifest.pluginClassName ?: error("No plugin class found in ${file.name}")

        val instance = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
        instance.filename = file.absolutePath
        // Files packed under assets/ in the extension (read through resources.assets, as on Android).
        android.content.res.AssetManager.register(file)
        (instance as? Plugin)?.let { if (manifest.requiresResources || it.resources == null) it.resources = Resources(android.content.res.AssetManager(file)) }

        synchronized(registerLock) {
            if (instance is Plugin) instance.load(Platform.context) else instance.load()
        }

        LoadedPlugin(data.copy(version = manifest.version ?: data.version), manifest, instance, loader).also {
            loaded[file.absolutePath] = it
            failures.remove(data.internalName)
            changed()
        }
    }.onFailure {
        failures[data.internalName] = it
        System.err.println("Failed to load ${file.name}: $it")
    }

    /** For the official PluginManager.downloadPlugin: extensions that install other extensions. */
    suspend fun installFile(url: String, hash: String?, internalName: String, target: File, repositoryUrl: String?, load: Boolean): Boolean =
        runCatching {
            unloadPlugin(target.absolutePath)
            RepositoryManager.download(url, target, hash)
            val previous = installed().find { it.internalName == internalName }
            val data = PluginData(internalName, url, true, target.absolutePath, previous?.version ?: 0, repositoryUrl ?: previous?.repositoryUrl, previous?.name ?: internalName)
            record(data)
            if (load) loadPlugin(target, data).getOrThrow()
            changed()
        }.isSuccess

    /** For the official PluginManager.deletePlugin. */
    fun deleteFile(file: File): Boolean {
        unloadPlugin(file.absolutePath)
        saveInstalled(installed().filter { File(it.filePath).absolutePath != file.absolutePath })
        val ok = file.delete() || !file.exists()
        changed()
        return ok
    }

    /** Port of upstream unloadPlugin: removes everything the plugin registered. */
    fun unloadPlugin(absolutePath: String) {
        val plugin = loaded.remove(absolutePath) ?: return
        runCatching { plugin.instance.beforeUnload() }
        val owner = plugin.instance.filename
        APIHolder.apis.filter { it.sourcePlugin == owner }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == owner } }
        extractorApis.withLock { extractorApis.removeAll { it.sourcePlugin == owner } }
        com.lagradost.cloudstream3.actions.VideoClickActionHolder.removeFrom(owner)
        runCatching { plugin.loader.close() }
        android.content.res.AssetManager.unregister(java.io.File(absolutePath))
        changed()
    }

    /**
     * The file of an installed extension that isn't on this computer (a restored backup, perhaps from a phone):
     * from its repository's current list (the native .jar when there is one), else from the address it was
     * installed from. Returns the file and the updated record.
     */
    private suspend fun redownload(data: PluginData): Pair<File, PluginData> {
        val repo = RepositoryManager.getRepositories().firstOrNull { it.url == data.repositoryUrl }
        val w = repo?.let { r -> runCatching { RepositoryManager.getRepoPlugins(r) }.getOrNull()?.firstOrNull { it.plugin.internalName == data.internalName } }
        if (w != null) {
            val p = w.plugin
            val useJar = p.jarUrl != null
            val file = RepositoryManager.pluginPath(p.internalName, w.repositoryData.url, if (useJar) "jar" else "cs3")
            if (useJar) RepositoryManager.download(p.jarUrl!!, file, p.jarHash) else RepositoryManager.download(p.url, file, p.fileHash)
            val fresh = PluginData(p.internalName, p.url, true, file.absolutePath, p.version, w.repositoryData.url, p.name)
            record(fresh)
            return file to fresh
        }
        val url = data.url ?: throw IllegalStateException(if (repo == null) "its repository isn't added" else "${repo.name} no longer lists it")
        val file = RepositoryManager.pluginPath(data.internalName, data.repositoryUrl ?: "restored", "cs3")
        RepositoryManager.download(url, file, null)
        val moved = data.copy(filePath = file.absolutePath)
        record(moved)
        return file to moved
    }

    /** The "Reinstall" button: downloads [internalName] again (as after a restore) and loads it. */
    suspend fun reinstall(internalName: String): Result<Unit> = runCatching {
        val data = installed().first { it.internalName == internalName }
        unloadPlugin(File(data.filePath).absolutePath)
        File(data.filePath).delete()
        val (file, fresh) = redownload(data)
        failures.remove(internalName)
        loadPlugin(file, fresh).getOrThrow()
        changed()
    }

    /** Downloads (preferring the native .jar when the repo publishes one) and loads a plugin. */
    suspend fun install(wrapper: PluginWrapper): Result<LoadedPlugin> {
        val p = wrapper.plugin
        val useJar = p.jarUrl != null
        val target = RepositoryManager.pluginPath(p.internalName, wrapper.repositoryData.url, if (useJar) "jar" else "cs3")
        installed().find { it.internalName == p.internalName }?.let { uninstall(it.internalName) }
        return runCatching {
            if (useJar) RepositoryManager.download(p.jarUrl!!, target, p.jarHash)
            else RepositoryManager.download(p.url, target, p.fileHash)
        }.mapCatching { file ->
            val data = PluginData(p.internalName, p.url, true, file.absolutePath, p.version, wrapper.repositoryData.url, p.name)
            record(data)
            loadPlugin(file, data).getOrThrow()
        }
    }

    /** Installs a local .cs3 or .jar, like dropping a file into the Android "local plugins" folder. */
    fun sideload(source: File): Result<LoadedPlugin> = runCatching {
        require(source.extension in setOf("cs3", "jar")) { "Expected a .cs3 or .jar file" }
        val target = File(RepositoryManager.extensionsDir, "local/${source.name}")
        target.parentFile.mkdirs()
        // Unload first: Windows can't overwrite a file a class loader still has open.
        unloadPlugin(target.absolutePath)
        source.copyTo(target, overwrite = true)
        val data = PluginData(source.nameWithoutExtension, null, false, target.absolutePath, 0, null, source.nameWithoutExtension)
        record(data)
        loadPlugin(target, data).getOrThrow()
    }

    fun uninstall(internalName: String) {
        val data = installed().find { it.internalName == internalName } ?: return
        unloadPlugin(File(data.filePath).absolutePath)
        File(data.filePath).delete()
        saveInstalled(installed().filter { it.internalName != internalName })
    }

    /**
     * After a restore (or with "Install new extensions automatically" on): downloads installed extensions whose
     * file is missing, and with [includeNew] every extension of the added repositories that isn't installed yet
     * (not broken ones, not adult ones unless allowed, only the chosen languages). Returns their names.
     */
    suspend fun installMissing(includeNew: Boolean): List<String> {
        val have = installed().associateBy { it.internalName }
        val done = mutableListOf<String>()
        val langs = Settings.extensionLanguages
        for (repo in RepositoryManager.getRepositories()) {
            val available = RepositoryManager.getRepoPlugins(repo) ?: continue
            for (w in available) {
                val p = w.plugin
                val local = have[p.internalName]
                val missingFile = local != null && !File(local.filePath).exists()
                val wanted = local == null && includeNew && p.status != 0 &&
                    (p.tvTypes.orEmpty().none { it.equals("NSFW", true) } || ContentFilter.nsfwAllowed) &&
                    (langs.isEmpty() || p.language == null || p.language!!.lowercase() in langs)
                if ((missingFile || wanted) && install(w).isSuccess) done += p.name
            }
        }
        return done
    }

    /** Returns the plugins whose repository advertises a higher version, and installs them. */
    suspend fun updateAll(): List<String> {
        val byRepo = installed().filter { it.isOnline && it.repositoryUrl != null }.groupBy { it.repositoryUrl!! }
        val updated = mutableListOf<String>()
        for ((repoUrl, plugins) in byRepo) {
            val repoData = RepositoryManager.getRepositories().find { it.url == repoUrl } ?: continue
            val available = RepositoryManager.getRepoPlugins(repoData) ?: continue
            for (local in plugins) {
                val remote = available.find { it.plugin.internalName == local.internalName } ?: continue
                if (remote.plugin.version > local.version && install(remote).isSuccess) updated += local.internalName
            }
        }
        return updated
    }
}
