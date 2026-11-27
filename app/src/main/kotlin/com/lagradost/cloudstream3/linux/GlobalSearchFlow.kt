package com.lagradost.cloudstream3.linux

/** Maps the Global Search source dropdown to the provider set used by the search action. */
class GlobalSearchSourceSelection<T>(
    providers: List<T>,
    private val nameOf: (T) -> String,
) {
    private var providers: List<T> = providers
    var selectedName: String? = null
        private set

    val options: List<String>
        get() = listOf(ALL_SOURCES) + providers.map(nameOf)

    fun refresh(next: List<T>) {
        val previous = selectedName
        providers = next
        if (previous != null && providers.none { nameOf(it) == previous }) {
            selectedName = null
        }
    }

    fun selectIndex(index: Int) {
        selectedName = if (index <= 0) null else providers.getOrNull(index - 1)?.let(nameOf)
    }

    fun selectProvider(name: String?) {
        selectedName = name?.takeIf { candidate -> providers.any { nameOf(it) == candidate } }
    }

    fun targets(): List<T> =
        selectedName?.let { selected -> providers.filter { nameOf(it) == selected } } ?: providers

    companion object {
        const val ALL_SOURCES = "All sources"
    }
}

data class SearchPageResult<T>(val items: List<T>, val hasNext: Boolean)

/**
 * Provider-backed View All session. The same session is used by the UI and integration tests,
 * so paging, retry, deduplication and termination have one implementation.
 */
class ViewAllSearchSession<T, K>(
    firstPage: List<T>,
    firstHasNext: Boolean,
    private val keyOf: (T) -> K,
    private val fetchPage: suspend (page: Int) -> SearchPageResult<T>,
) {
    private val seen = HashSet<K>().apply { firstPage.forEach { add(keyOf(it)) } }
    private val mutableItems = firstPage.distinctBy(keyOf).toMutableList()
    val items: List<T> get() = mutableItems.toList()
    private var page = 1
    private var pagination = SearchPaginationState(hasNext = firstHasNext)
    val hasNext: Boolean get() = pagination.hasNext && !pagination.loading && pagination.pagesFetched < 100

    suspend fun loadNext(): Boolean {
        if (!pagination.canLoadNext) return false
        pagination = pagination.beginLoading()
        return try {
            val response = fetchPage(page + 1)
            page++
            response.items.forEach { item -> if (seen.add(keyOf(item))) mutableItems += item }
            pagination = pagination.pageLoaded(response.hasNext)
            true
        } catch (cancelled: CancellationException) {
            pagination = pagination.pageFailed()
            throw cancelled
        } catch (_: Throwable) {
            pagination = pagination.pageFailed()
            false
        }
    }
}
