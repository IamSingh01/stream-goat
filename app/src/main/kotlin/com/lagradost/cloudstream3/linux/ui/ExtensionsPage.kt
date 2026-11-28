package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginData
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.PluginWrapper
import com.lagradost.cloudstream3.linux.RepositoryData
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.LiveTv
import com.lagradost.cloudstream3.linux.StremioAddons
import com.lagradost.cloudstream3.linux.StremioApi
import com.lagradost.cloudstream3.linux.statusName
import javafx.geometry.Pos
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One line in the extension list: something a repository offers, or a side-loaded file. */
data class ExtRow(val wrapper: PluginWrapper?, val installed: PluginData?) {
    val internalName get() = wrapper?.plugin?.internalName ?: installed!!.internalName
    val name get() = wrapper?.plugin?.name ?: installed?.name ?: installed!!.internalName
    val hasUpdate get() = installed != null && wrapper != null && wrapper.plugin.version > installed.version
}

/** Type filter chips, matched against a plugin's tvTypes. */
private val TYPE_FILTERS: List<Pair<String, Set<String>?>> = listOf(
    "All types" to null,
    "Movies" to setOf("Movie", "AnimeMovie", "Documentary"),
    "TV" to setOf("TvSeries", "AsianDrama", "Cartoon", "Documentary"),
    "Anime" to setOf("Anime", "AnimeMovie", "OVA"),
    "Live" to setOf("Live"),
    "Other" to setOf("Torrent", "Music", "AudioBook", "Audio", "Podcast", "CustomMedia", "Others", "NSFW"),
)

private fun typeName(type: String) = when (type) {
    "TvSeries" -> "TV"
    "AsianDrama" -> "Asian drama"
    "AnimeMovie" -> "Anime movie"
    "AudioBook" -> "Audiobook"
    "CustomMedia" -> "Custom"
    else -> type
}

class ExtensionsPage : StackPane(), Refreshable {
    private val repoField = TextField().apply { promptText = "Repository URL or shortcode (the same ones the Android app uses)" }
    private val repoList = VBox()
    private val filterField = TextField().apply { promptText = "Filter extensions" }
    private val language = ComboBox<String>().apply { items.setAll("All languages"); selectionModel.select(0) }
    private val repoFilter = ComboBox<String>().apply { items.setAll("All repositories"); selectionModel.select(0) }
    private val view = ChipGroup { applyFilter(resetPage = false) }
    private val types = ChipGroup { applyFilter(resetPage = false) }
    private var rows: List<ExtRow> = emptyList()
    private val grid = FlowPane(14.0, 14.0)
    private lateinit var scroll: ScrollPane
    // Reuse card nodes across provider-state updates instead of rebuilding dozens of controls.
    private val cardCache = mutableMapOf<String, VBox>()
    private val stats = label("", "muted")
    private val busy = mutableSetOf<String>()
    private val busyAction = mutableMapOf<String, String>()
    private var available: List<PluginWrapper> = emptyList()
    private var loadingRepos = false
    private var repositoriesLoaded = false
    private var repoFilterUrls: Map<String, String> = emptyMap()
    private var changingRepoFilter = false
    private var resettingFilters = false
    private var refreshGeneration = 0L

    private fun resetFiltersForRepositoryChange() {
        if (resettingFilters) return
        resettingFilters = true
        try {
            filterField.clear()
            language.value = "All languages"
            language.selectionModel.select("All languages")
            view.select(0, notify = false)
            types.select(0, notify = false)
            cardCache.clear()
        } finally {
            resettingFilters = false
        }
        applyFilter(resetPage = false)
    }

    private fun cardKey(row: ExtRow): String = listOf(
        row.internalName, row.wrapper?.repositoryData?.url.orEmpty(),
        row.wrapper?.plugin?.version?.toString().orEmpty(),
        row.installed?.version?.toString().orEmpty(),
        if (row.installed == null) "available" else "installed",
        busyAction[row.internalName].orEmpty(),
    ).joinToString("\u0000")

    private fun invalidateCards(internalName: String) {
        // Cache keys are composite (internal name + repository + version + state).
        // Match the first key segment rather than trying to remove a card by its bare name.
        cardCache.keys.removeIf { it.substringBefore('\u0000') == internalName }
    }

    init {
        val header = hbox(12.0,
            vbox(4.0, label("Extensions", "h2"), stats),
            spacer(),
            button("Update all", Icons.REFRESH, "btn-ghost") { updateAll() },
            button("Install from file", Icons.FILE, "btn-ghost") { sideload() },
        )
        growWide(repoField)
        val addRow = hbox(10.0, repoField, button("Add repository", Icons.ADD, "btn-accent") { addRepository(repoField.text) })
        repoField.setOnAction { addRepository(repoField.text) }
        val repoCard = vbox(10.0, label("REPOSITORIES", "section-label"), addRow, repoList).apply { styleClass += "card" }

        view.setItems(listOf("All", "Installed", "Updates"))
        types.setItems(TYPE_FILTERS.map { it.first })
        // Keep the current scroll position while filtering as the user types.
        filterField.textProperty().addListener { _, _, _ -> applyFilter(resetPage = false) }
        language.valueProperty().addListener { _, _, _ -> applyFilter(resetPage = false) }
        repoFilter.valueProperty().addListener { _, old, selected ->
            if (!changingRepoFilter && old != selected) resetFiltersForRepositoryChange()
        }
        growWide(filterField)
        val clearSearch = button("×", null, "btn-ghost", "btn-small") {
            // Clear the query only; leave the repository/language/type tabs untouched.
            // With an empty query the complete list for the selected tabs is restored.
            filterField.clear()
            filterField.requestFocus()
        }.apply {
            tooltip("Clear search")
            isVisible = false
            isManaged = false
            style = "-fx-background-radius: 18; -fx-min-width: 28; -fx-pref-width: 28; -fx-min-height: 28; -fx-pref-height: 28;"
        }
        filterField.textProperty().addListener { _, _, value ->
            val hasQuery = !value.isNullOrBlank()
            clearSearch.isVisible = hasQuery
            clearSearch.isManaged = hasQuery
        }
        val searchBox = StackPane(filterField, clearSearch).apply {
            alignment = Pos.CENTER_RIGHT
            StackPane.setMargin(clearSearch, Insets(0.0, 7.0, 0.0, 0.0))
        }
        filterField.padding = Insets(0.0, 42.0, 0.0, 12.0)
        growWide(searchBox)
        val filterRow = hbox(10.0, searchBox, repoFilter, language)
        // Keep the All / Installed / Updates tabs on their own full-width row.
        // Putting them at the end of the search row squeezed the search field and
        // made the controls appear displaced on narrower desktop windows.
        val statusRow = hbox(8.0, view).apply { alignment = Pos.CENTER_LEFT }
        // Cards share the row evenly: as many ~300px columns as fit.
        grid.widthProperty().addListener { _, _, _ -> sizeCards() }

        val extensions = VBox(18.0, header, repoCard, filterRow, statusRow, types, grid)
        val addons = StremioPane()
        val body = StackPane(extensions)
        // Two kinds of sources: CloudStream extensions (repositories) and Stremio add-ons (manifest URLs).
        val kind = ChipGroup { i -> body.children.setAll(if (i == 0) extensions else addons.also { it.render() }) }.apply {
            setItems(listOf("CloudStream extensions", "Stremio add-ons"), 0)
            id = "ext-kind"
        }
        val page = VBox(18.0, kind, body).pad(22.0, 36.0, 32.0, 36.0)
        scroll = pageScroll(page)
        children += scroll
        ui.launch { PluginManager.version.collect { applyFilter() } }
    }

    private fun cardWidth(): Double {
        val gap = grid.hgap
        val width = grid.width.takeIf { it > 0 } ?: 900.0
        val columns = ((width + gap) / (300.0 + gap)).toInt().coerceAtLeast(1)
        return Math.floor((width - gap * (columns - 1)) / columns) - 0.5
    }

    private fun sizeCards() {
        val w = cardWidth()
        grid.children.forEach { (it as? VBox)?.prefWidth = w }
    }

    private fun growWide(node: Node) = javafx.scene.layout.HBox.setHgrow(node, Priority.ALWAYS)

    // Re-read repositories whenever the Extensions page is opened. A cached catalogue can
    // otherwise keep showing a repository that was removed or a previous "All repositories" view.
    override fun onShow() = refresh(force = true)

    fun refresh(force: Boolean = false) {
        if (loadingRepos && !force) {
            applyFilter(resetPage = false)
            return
        }
        if (repositoriesLoaded && !force) {
            applyFilter(resetPage = false)
            return
        }
        val generation = ++refreshGeneration
        val repos = RepositoryManager.getRepositories()
        val validUrls = repos.mapTo(mutableSetOf()) { it.url }
        // Drop catalogue entries from repositories that no longer exist before rendering.
        available = available.filter { it.repositoryData.url in validUrls }
        repoFilterUrls = emptyMap()
        renderRepos(repos, emptyMap())
        loadingRepos = true
        stats.text = "Reading ${repos.size} repositories…"
        // Installed extension records are local and available immediately. Do not leave
        // the grid showing a stale "No extensions match" message while repo metadata loads.
        applyFilter()
        ui.launch {
            val duplicateNames = repos.groupBy { it.name }.filterValues { it.size > 1 }.keys
            val labelsByUrl = repos.associate { repo ->
                repo.url to if (repo.name in duplicateNames) {
                    "${repo.name} — ${repo.url.substringAfter("://").substringBefore("/")}"
                } else repo.name
            }
            if (generation != refreshGeneration) return@launch
            repoFilterUrls = labelsByUrl.entries.associate { it.value to it.key }
            val repositoryOptions = listOf("All repositories") + repos.map { labelsByUrl[it.url] ?: it.name }
            // Read the selection after the network requests finish; users may choose All repositories while loading.
            val selectedRepo = repoFilter.value
            changingRepoFilter = true
            try {
                repoFilter.items.setAll(repositoryOptions)
                val restoredRepo = selectedRepo?.takeIf { it in repositoryOptions } ?: "All repositories"
                repoFilter.selectionModel.clearSelection()
                repoFilter.value = restoredRepo
                repoFilter.selectionModel.select(restoredRepo)
            } finally {
                changingRepoFilter = false
            }

            // Fetch repositories concurrently and publish each result as soon as it arrives.
            // Waiting for associateWith { getRepoPlugins() } hid every available (not-installed)
            // extension until the slowest repository finished, leaving only iconless local rows.
            val perRepo = linkedMapOf<RepositoryData, List<PluginWrapper>?>()
            try {
                kotlinx.coroutines.coroutineScope {
                    val completed = kotlinx.coroutines.channels.Channel<Pair<RepositoryData, List<PluginWrapper>?>>(
                        kotlinx.coroutines.channels.Channel.UNLIMITED
                    )
                    repos.forEach { repo ->
                        launch(Engine.dispatcher) {
                            // One broken or unreachable repository must not cancel the other
                            // repository requests or leave the page permanently in loading state.
                            val result = runCatching { RepositoryManager.getRepoPlugins(repo) }.getOrNull()
                            completed.send(repo to result)
                        }
                    }
                    repeat(repos.size) {
                        val (repo, plugins) = completed.receive()
                        if (generation != refreshGeneration) {
                            completed.close()
                            return@coroutineScope
                        }
                        perRepo[repo] = plugins
                        available = perRepo.values.filterNotNull().flatten()
                        renderRepos(repos, perRepo)

                        val langs = available.mapNotNull { it.plugin.language }
                            .distinct().sortedBy { languageName(it) }
                        val selectedLanguage = language.value
                        val languageOptions = listOf("All languages") + langs.map { "${languageName(it)} ($it)" }
                        language.items.setAll(languageOptions)
                        val restoredLanguage = selectedLanguage?.takeIf { it in languageOptions } ?: "All languages"
                        language.selectionModel.clearSelection()
                        language.selectionModel.select(restoredLanguage)
                        language.value = restoredLanguage

                        // Invalidate only cards supplied by this repository. Clearing the
                        // whole cache here would cancel icon downloads for every earlier result.
                        plugins.orEmpty().forEach { invalidateCards(it.plugin.internalName) }
                        stats.text = "Loaded ${perRepo.size} of ${repos.size} repositories · ${available.size} available"
                        applyFilter()
                    }
                    completed.close()
                }
            } finally {
                if (generation == refreshGeneration) {
                    loadingRepos = false
                    repositoriesLoaded = true
                    applyFilter(resetPage = false)
                }
            }
        }
    }

    private fun renderRepos(repos: List<RepositoryData>, plugins: Map<RepositoryData, List<PluginWrapper>?>) {
        if (repos.isEmpty()) {
            repoList.children.setAll(label("No repositories yet. Paste a repository link above — any CloudStream repository works.", "muted").apply { isWrapText = true })
            return
        }
        repoList.children.setAll(repos.map { repo ->
            val list = plugins[repo]
            val count = when {
                repo !in plugins -> "loading…"
                list == null -> "unreachable"
                else -> "${list.size} extensions"
            }
            hbox(12.0,
                vbox(2.0, label(repo.name, "bold"), label(repo.url, "faint", "small")),
                spacer(),
                label(count, if (list == null && repo in plugins) "error-text" else "muted", "small"),
                button("Install all", Icons.DOWNLOAD, "btn-ghost", "btn-small") { installAll(list.orEmpty()) }.apply { isDisable = list.isNullOrEmpty() },
                button(null, Icons.DELETE, "btn-danger", "btn-small") { removeRepository(repo) }.tooltip("Remove repository and its extensions"),
            ).apply { styleClass += "card-row" }
        })
    }

    private fun applyFilter(resetPage: Boolean = false) {
        val installed = PluginManager.installed()
        val byName = installed.associateBy { it.internalName }
        val selectedRepoLabel = repoFilter.value
        val selectedRepoUrl = selectedRepoLabel
            ?.takeUnless { it == "All repositories" }
            ?.let { repoFilterUrls[it] }
        // In a specific repository view, use that repository's copy. In All repositories,
        // prefer the installed origin so Update does not silently switch sources.
        val rows = available
            .groupBy { it.plugin.internalName }
            .values
            .map { mirrors ->
                val local = byName[mirrors.first().plugin.internalName]
                val selected = mirrors.firstOrNull { it.repositoryData.url == selectedRepoUrl }
                    ?: mirrors.firstOrNull { it.repositoryData.url == local?.repositoryUrl }
                    ?: mirrors.first()
                ExtRow(selected, local)
            } +
            installed.filter { local -> available.none { it.plugin.internalName == local.internalName } }.map { ExtRow(null, it) }
        val query = filterField.text.trim().lowercase()
        // Treat the visible "All …" entries as explicit sentinels. ComboBox selection
        // indices can briefly be stale after repository metadata repopulates its items;
        // relying on selectedIndex here accidentally filters the aggregate view.
        val selectedLanguage = language.selectionModel.selectedItem ?: language.value
        val lang = selectedLanguage
            ?.takeUnless { it == "All languages" }
            ?.substringAfterLast('(')?.removeSuffix(")")
        // ComboBox.selectionModel.selectedItem can retain the previous repository while
        // its items are being replaced. The value property is the displayed selection;
        // using selectedItem here made "All repositories" keep filtering by the last repo.
        // "All repositories" is a deliberate aggregate view, never the previously selected URL.
        val repoUrl = selectedRepoUrl
        val typeSet = TYPE_FILTERS.getOrNull(types.selected)?.second
        // Adult (NSFW) extensions stay out of the list unless turned on in Settings → Profiles; installed ones stay manageable.
        val nsfw = com.lagradost.cloudstream3.linux.ContentFilter.nsfwAllowed
        val hiddenAdult = rows.count { it.installed == null && "NSFW" in it.wrapper?.plugin?.tvTypes.orEmpty() && !nsfw }
        this.rows = rows.filter { row ->
            (nsfw || row.installed != null || "NSFW" !in row.wrapper?.plugin?.tvTypes.orEmpty()) &&
            (query.isEmpty() || row.name.lowercase().contains(query) || row.wrapper?.plugin?.description.orEmpty().lowercase().contains(query)) &&
                (lang == null || row.wrapper?.plugin?.language == lang) &&
                (repoUrl == null || (row.wrapper?.repositoryData?.url ?: row.installed?.repositoryUrl) == repoUrl) &&
                (typeSet == null || row.wrapper?.plugin?.tvTypes.orEmpty().any { it in typeSet }) &&
                when (view.selected) {
                    1 -> row.installed != null
                    2 -> row.hasUpdate
                    else -> true
                }
        }.sortedWith(compareBy({ it.installed == null }, { it.name.lowercase() }))
        // Filter and state changes preserve the current viewport; only explicit navigation
        // should ever move the user to the top.
        // Keep existing card nodes while providers register. Recreating ImageViews on every
        // PluginManager.version event cancels their in-flight icon downloads, so icons never
        // finish loading during startup. Install/uninstall/update paths invalidate affected cards.
        renderGrid()
        val loaded = Engine.allProviders().size
        if (!loadingRepos) stats.text = "${available.size} available · ${installed.size} installed · $loaded providers loaded" +
            (PluginManager.failures.size.takeIf { it > 0 }?.let { " · $it failed" } ?: "") +
            (if (hiddenAdult > 0) " · $hiddenAdult adult (NSFW) hidden, see Settings → Profiles" else "")
    }

    /** Render the complete filtered extension catalogue; no hidden pages or Show more button. */
    private fun renderGrid() {
        val w = cardWidth()
        val nodes = rows.map { row ->
            cardCache.getOrPut(cardKey(row)) { card(row) }.apply { prefWidth = w }
        }.toMutableList<Node>()
        if (rows.isEmpty()) nodes += label(
            when {
                available.isEmpty() && loadingRepos -> "Loading extensions from repositories…"
                available.isEmpty() -> "No repository extensions are available. Check repository status above."
                else -> "No extensions match these filters"
            }, "muted"
        )
        // Provider progress can emit repeatedly while a user is scrolling. If the visible
        // nodes have not changed, don't detach/re-add them: that causes layout jitter.
        if (grid.children.size == nodes.size && grid.children.indices.all { grid.children[it] === nodes[it] }) return
        val content = scroll.content as? Region
        val viewport = scroll.viewportBounds.height
        val oldOffset = if (content != null) scroll.vvalue * (content.height - viewport).coerceAtLeast(0.0) else 0.0
        // Reconcile by node identity instead of setAll(): unchanged cards stay attached,
        // preserving image loads, focus and the user's current scroll interaction.
        nodes.forEachIndexed { index, node ->
            val currentIndex = grid.children.indexOf(node)
            when {
                currentIndex == index -> Unit
                currentIndex >= 0 -> {
                    grid.children.removeAt(currentIndex)
                    grid.children.add(index.coerceAtMost(grid.children.size), node)
                }
                index >= grid.children.size -> grid.children.add(node)
                else -> grid.children.add(index, node)
            }
        }
        while (grid.children.size > nodes.size) grid.children.removeAt(grid.children.lastIndex)
        javafx.application.Platform.runLater {
            val nextContent = scroll.content as? Region ?: return@runLater
            nextContent.applyCss()
            nextContent.layout()
            scroll.layout()
            val range = (nextContent.height - scroll.viewportBounds.height).coerceAtLeast(0.0)
            if (range > 0.0) scroll.vvalue = (oldOffset / range).coerceIn(0.0, 1.0)
            // Do not steal focus from the search field or the action the user just used.
        }
    }

    private fun languageName(code: String) =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    fun promptAddRepository(link: String) {
        val alert = Alert(Alert.AlertType.CONFIRMATION, "Add this extension repository?\n\n$link", ButtonType.OK, ButtonType.CANCEL).apply {
            title = "Add repository"
            headerText = null
            dialogPane.stylesheets += ExtensionsPage::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        }
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) addRepository(link)
    }

    private fun addRepository(input: String) {
        if (input.isBlank()) return
        ui.launch {
            try {
                val repo = withContext(Engine.dispatcher) { RepositoryManager.addRepository(input.trim()) }
                repoField.clear()
                Toasts.show("Added ${repo.name}")
                refresh(force = true)
            } catch (t: Throwable) {
                Toasts.show("Couldn't add repository: ${t.message ?: t}", seconds = 7.0)
            }
        }
    }

    private fun removeRepository(repo: RepositoryData) {
        val alert = Alert(Alert.AlertType.CONFIRMATION, "Remove ${repo.name} and uninstall its extensions?", ButtonType.OK, ButtonType.CANCEL).apply {
            headerText = null
            dialogPane.stylesheets += ExtensionsPage::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        }
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        RepositoryManager.removeRepository(repo.url)
        available = available.filterNot { it.repositoryData.url == repo.url }
        repoFilter.value = "All repositories"
        refresh(force = true)
    }

    private fun install(wrapper: PluginWrapper) {
        val name = wrapper.plugin.internalName
        if (!busy.add(name)) return
        busyAction[name] = "Installing"
        invalidateCards(name)
        applyFilter(resetPage = false)
        ui.launch {
            try {
                val result = withContext(Engine.dispatcher) { PluginManager.install(wrapper) }
                result.fold(
                    { loaded ->
                        Toasts.show("Installed ${wrapper.plugin.name}" + loaded.providers.takeIf { it.isNotEmpty() }?.let { p -> " · ${p.joinToString { a -> a.name }}" }.orEmpty())
                    },
                    { error -> Toasts.show("${wrapper.plugin.name} failed: ${error.message ?: error}", seconds = 8.0) },
                )
            } catch (t: Throwable) {
                Toasts.show("${wrapper.plugin.name} failed: ${t.message ?: t}", seconds = 8.0)
            } finally {
                busy.remove(name)
                busyAction.remove(name)
                invalidateCards(name)
                // Rebuild the row from the persisted installed records and current repository
                // metadata; don't leave an old Install/Uninstall control on the card.
                applyFilter()
            }
        }
    }

    private fun installAll(list: List<PluginWrapper>) {
        val candidates = list.distinctBy { it.plugin.internalName }.filter { wrapper ->
            wrapper.plugin.status != 0 && PluginManager.installed().none { it.internalName == wrapper.plugin.internalName }
        }
        if (candidates.isEmpty()) { Toasts.show("No installable extensions in this repository"); return }
        ui.launch {
            var installedCount = 0
            for (wrapper in candidates) {
                val name = wrapper.plugin.internalName
                if (!busy.add(name)) continue
                busyAction[name] = "Installing"
                invalidateCards(name)
                applyFilter(resetPage = false)
                try {
                    val result = withContext(Engine.dispatcher) { PluginManager.install(wrapper) }
                    if (result.isSuccess) installedCount++
                    else Toasts.show("${wrapper.plugin.name} failed: ${result.exceptionOrNull()?.message ?: "unknown error"}", seconds = 8.0)
                } catch (t: Throwable) {
                    Toasts.show("${wrapper.plugin.name} failed: ${t.message ?: t}", seconds = 8.0)
                } finally {
                    busy.remove(name)
                    busyAction.remove(name)
                    invalidateCards(name)
                    applyFilter(resetPage = false)
                }
            }
            Toasts.show("Installed $installedCount of ${candidates.size} extensions")
        }
    }

    private fun uninstall(row: ExtRow) {
        val name = row.internalName
        if (!busy.add(name)) return
        busyAction[name] = "Uninstalling"
        invalidateCards(name)
        applyFilter(resetPage = false)
        ui.launch {
            try {
                withContext(Engine.dispatcher) { PluginManager.uninstall(name) }
                val stillInstalled = PluginManager.installed().any { it.internalName == name }
                invalidateCards(name)
                if (stillInstalled) {
                    Toasts.show("Couldn't uninstall ${row.name}: it is still registered as installed", seconds = 8.0)
                } else {
                    Toasts.show("Uninstalled ${row.name}")
                }
            } catch (t: Throwable) {
                Toasts.show("Couldn't uninstall ${row.name}: ${t.message ?: t}", seconds = 8.0)
            } finally {
                busy.remove(name)
                busyAction.remove(name)
                invalidateCards(name)
                applyFilter(resetPage = false)
            }
        }
    }

    private fun updateAll() {
        ui.launch {
            Toasts.show("Checking for updates…")
            try {
                val updated = withContext(Engine.dispatcher) { PluginManager.updateAll() }
                cardCache.clear()
                applyFilter(resetPage = false)
                Toasts.show(if (updated.isEmpty()) "No extensions needed an update" else "Updated ${updated.joinToString()}")
            } catch (t: Throwable) {
                Toasts.show("Update failed: ${t.message ?: t}", seconds = 8.0)
            }
        }
    }

    private fun sideload() {
        val file: File = FileChooser().apply {
            title = "Install extension"
            extensionFilters += FileChooser.ExtensionFilter("CloudStream extension", "*.cs3", "*.jar")
        }.showOpenDialog(scene.window) ?: return
        ui.launch {
            val result = withContext(Engine.dispatcher) { PluginManager.sideload(file) }
            result.fold(
                { Toasts.show("Loaded ${it.manifest.name}: ${it.providers.joinToString { p -> p.name }.ifEmpty { "no providers" }}") },
                { Toasts.show("${file.name} failed: ${it.message ?: it}", seconds = 8.0) },
            )
            applyFilter()
        }
    }

    private fun card(row: ExtRow): VBox {
        val p = row.wrapper?.plugin
        val iconView = ImageView()
        Images.load(iconView, p?.iconUrl?.replace("%size%", "96"), 46.0, 46.0)
        val initials = label(row.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "bold", "muted")
        val iconBox = StackPane(initials, iconView).apply {
            styleClass += "poster-frame"
            setMinSize(46.0, 46.0); setMaxSize(46.0, 46.0)
            roundedClip(this, 12.0)
        }
        val version = (p?.version ?: row.installed?.version)?.let { "v$it" }
        // Keep the originating repository visible even when an installed extension is
        // no longer present in the current repository catalogue.
        fun repositoryLabel(url: String?): String? = url?.let { value ->
            RepositoryManager.getRepositories().firstOrNull { it.url == value }?.name
                ?: value.substringAfter("://").substringBefore("/").takeIf { it.isNotBlank() }
        }
        val source = when {
            row.installed != null && !row.installed.isOnline -> "Local install"
            row.installed != null -> repositoryLabel(row.installed.repositoryUrl)?.let { "Installed from: $it" }
                ?: "Installed · source unknown"
            else -> repositoryLabel(row.wrapper?.repositoryData?.url)?.let { "Source: $it" }
        }
        val wrapperRepoUrl = row.wrapper?.repositoryData?.url
        val availableFrom = row.wrapper?.repositoryData?.name
            ?.takeIf { row.installed?.repositoryUrl != null && row.installed.repositoryUrl != wrapperRepoUrl }
            ?.let { "Available in: $it" }
        val meta = listOfNotNull(p?.language?.let { languageName(it) }, version, source, availableFrom)
        val title = vbox(2.0,
            label(row.name, "bold").apply { isWrapText = false },
            label(meta.joinToString(" · "), "faint", "small"),
        ).apply { javafx.scene.layout.HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 }
        val head = hbox(12.0, iconBox, title)

        val card = VBox(10.0, head).apply { styleClass += "ext-card" }
        p?.description?.takeIf { it.isNotBlank() }?.let {
            card.children += label(it, "muted", "small").apply { isWrapText = true; maxHeight = 34.0 }
        }
        val tags = FlowPane(6.0, 6.0)
        p?.tvTypes?.take(4)?.forEach { tags.children += label(typeName(it), "tag") }
        if (p != null && p.status != 1) tags.children += label(statusName(p.status), "tag", "accent")
        if (tags.children.isNotEmpty()) card.children += tags

        val failure = PluginManager.failures[row.internalName]
        failure?.let { card.children += label("Failed to load: ${it.message ?: it}", "error-text", "small").apply { isWrapText = true } }

        val actions = hbox(8.0).apply { alignment = Pos.CENTER_RIGHT }
        when {
            row.internalName in busy -> actions.children += hbox(8.0,
                ProgressIndicator().apply { setPrefSize(18.0, 18.0) },
                label("${busyAction[row.internalName] ?: "Working"}…", "muted"))
            row.installed == null -> {
                actions.children += label("Available", "muted", "small")
                actions.children += spacer()
                actions.children += button("Install", Icons.DOWNLOAD, "btn-accent", "btn-small") { row.wrapper?.let(::install) }
                    .apply {
                        isDisable = p?.status == 0 || row.wrapper == null
                        if (p?.status == 0) tooltip("This repository marks the extension as unavailable")
                    }
            }
            else -> {
                actions.children += label(if (failure == null) "Installed" else "Error", if (failure == null) "good-text" else "error-text", "small")
                actions.children += spacer()
                if (row.hasUpdate) actions.children += button("Update", Icons.REFRESH, "btn-accent", "btn-small") { install(row.wrapper!!) }
                // A failed one (e.g. its file is missing after restoring a backup): download it again in one click.
                if (failure != null && !row.hasUpdate) actions.children += button("Reinstall", Icons.REFRESH, "btn-accent", "btn-small") {
                    val name = row.internalName
                    if (!busy.add(name)) return@button
                    busyAction[name] = "Reinstalling"
                    invalidateCards(name)
                    applyFilter(resetPage = false)
                    ui.launch {
                        try {
                            val r = withContext(Engine.dispatcher) { PluginManager.reinstall(name) }
                            r.onSuccess { Toasts.show("${row.wrapper?.plugin?.name ?: name} works again") }
                                .onFailure { Toasts.show("Couldn't reinstall: ${it.message ?: it}", seconds = 8.0) }
                        } catch (t: Throwable) {
                            Toasts.show("Couldn't reinstall: ${t.message ?: t}", seconds = 8.0)
                        } finally {
                            busy.remove(name)
                            busyAction.remove(name)
                            invalidateCards(name)
                            applyFilter(resetPage = false)
                        }
                    }
                }
                actions.children += button("Uninstall", Icons.DELETE, "btn-danger", "btn-small") { uninstall(row) }
            }
        }
        card.children += spacer()
        card.children += actions
        return card
    }

    private companion object {}
}

/** Installed Stremio add-ons: add by manifest URL, turn on/off, remove. */
class StremioPane : VBox(18.0) {
    private val field = TextField().apply { promptText = "Add-on address: https://…/manifest.json or stremio://…" }
    private val list = VBox(12.0)
    private val status = label("", "muted")

    init {
        javafx.scene.layout.HBox.setHgrow(field, Priority.ALWAYS)
        val install = button("Install add-on", Icons.ADD, "btn-accent") { install(field.text) }
        field.setOnAction { install(field.text) }
        val header = vbox(4.0, label("Stremio add-ons", "h2"), label(
            "Stremio community add-ons work here: their catalogs show on Home, in Search and in the catalog editor, and their " +
                "streams play in the built-in player. Add-ons that only offer torrents aren't supported.", "muted").apply { isWrapText = true; maxWidth = 900.0 })
        val add = vbox(10.0, label("ADD AN ADD-ON", "section-label"), hbox(10.0, field, install), status).apply { styleClass += "card" }
        children.addAll(header, add, list)
        StremioAddons.onChange { javafx.application.Platform.runLater { render() } }
    }

    private fun install(text: String) {
        if (text.isBlank()) return
        status.text = "Installing…"
        status.styleClass.setAll("label", "muted")
        ui.launch {
            try {
                val addon = withContext(Engine.dispatcher) { StremioAddons.install(text) }
                field.clear()
                status.text = "Installed ${addon.manifest.name} ${addon.manifest.version}"
                Toasts.show("${addon.manifest.name} added")
            } catch (t: Throwable) {
                status.text = "Couldn't install: ${t.message ?: t}"
                status.styleClass.setAll("label", "error-text")
            }
        }
    }

    fun render() {
        val addons = StremioAddons.all()
        val nodes = addons.map { card(it) }.toMutableList<Node>()
        if (addons.none { it.url == StremioAddons.CINEMETA }) {
            nodes += hbox(12.0, vbox(2.0, label("Cinemeta", "bold"),
                label("Stremio's official catalog of films and series: popular, new and featured lists with rich details. Add a streaming add-on to play them.", "faint", "small").apply { isWrapText = true }).apply { HBox.setHgrow(this, Priority.ALWAYS) },
                button("Add", Icons.ADD, "btn-ghost") { install(StremioAddons.CINEMETA) }).apply { styleClass += "card" }
        }
        if (addons.isEmpty()) nodes.add(0, label("No add-ons yet. Paste an add-on's manifest address above, or start with Cinemeta below.", "muted"))
        list.children.setAll(nodes)
    }

    private fun card(a: StremioAddons.Addon): Node {
        val m = a.manifest
        val logo = StackPane().apply {
            styleClass += "addon-logo"
            setMinSize(46.0, 46.0); setMaxSize(46.0, 46.0)
            if (m.logo != null) children += ImageView().also { Images.load(it, m.logo, 46.0, 46.0) }
            else children += label(m.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "bold")
            roundedClip(this, 12.0)
        }
        val catalogs = m.catalogs.count { !it.needsExtra && !LiveTv.isTvType(it.type) }
        val tv = m.catalogs.any { LiveTv.isTvType(it.type) }
        val tags = hbox(6.0, *listOfNotNull(
            if (catalogs > 0) label("$catalogs catalogs", "tag") else null,
            if (tv) label("Live TV channels", "tag", "accent").tooltip("Its TV channels are in Live TV") else null,
            if (m.catalogs.any { it.searchable }) label("Search", "tag") else null,
            if (m.resources.any { it.name == "meta" }) label("Details", "tag") else null,
            if (m.resources.any { it.name == "stream" }) label("Streams", "tag", "accent") else null,
            if (m.resources.any { it.name == "subtitles" }) label("Subtitles", "tag") else null,
            label(m.types.joinToString(" · "), "faint", "small"),
        ).toTypedArray())
        val text = vbox(4.0, hbox(8.0, label(m.name, "bold"), label("v${m.version}", "faint", "small")),
            label(m.description.ifBlank { a.base }, "muted", "small").apply { isWrapText = true; maxHeight = 36.0 }, tags).apply {
            HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0
        }
        val api = if (catalogs > 0) Engine.allProviders().firstOrNull { it is StremioApi && it.mainUrl == a.base } else null
        val tune = api?.let { button(null, Icons.TUNE, "btn-ghost") { Nav.window.showOverlay(HomeManager(Engine.providers().filter { p -> p.hasMainPage }, it)) }.tooltip("Choose its catalogs for Home") }
        val remove = button(null, Icons.DELETE, "btn-ghost") { StremioAddons.remove(a.url); Toasts.show("${m.name} removed") }.tooltip("Remove")
        val toggle = Switch(a.enabled) { StremioAddons.setEnabled(a.url, it) }
        return hbox(14.0, *listOfNotNull(logo, text, tune, remove, toggle).toTypedArray()).apply {
            styleClass += "card"
            if (!a.enabled) opacity = 0.6
        }
    }
}
