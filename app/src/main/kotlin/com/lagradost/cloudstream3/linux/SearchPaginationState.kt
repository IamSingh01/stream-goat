package com.lagradost.cloudstream3.linux

/**
 * Pure pagination state for a provider's paginated search response.
 *
 * A provider is queried for another page only when its previous response explicitly
 * advertises hasNext. The 100-page ceiling protects the UI from broken providers.
 */
data class SearchPaginationState(
    val hasNext: Boolean,
    val loading: Boolean = false,
    val pagesFetched: Int = 1,
) {
    val canLoadNext: Boolean
        get() = hasNext && !loading && pagesFetched < MAX_PAGES

    fun beginLoading(): SearchPaginationState =
        if (canLoadNext) copy(loading = true) else this

    fun pageLoaded(hasNext: Boolean): SearchPaginationState =
        if (!loading) this else copy(hasNext = hasNext, loading = false, pagesFetched = pagesFetched + 1)

    fun pageFailed(): SearchPaginationState =
        if (!loading) this else copy(loading = false)

    private companion object {
        const val MAX_PAGES = 100
    }
}
