package com.lagradost.cloudstream3.linux

/**
 * Tracks provider-advertised search pagination. A failed request leaves the same page
 * available for retry; a successful response advances and trusts its hasNext flag.
 */
internal class SearchPagination(firstHasNext: Boolean) {
    private var currentPage = 1
    private var hasNext = firstHasNext
    private var loading = false

    val finished: Boolean
        get() = !hasNext && !loading

    fun nextPage(): Int? {
        if (!hasNext || loading) return null
        loading = true
        return currentPage + 1
    }

    fun pageLoaded(hasNext: Boolean) {
        check(loading) { "No search page request is in progress" }
        currentPage++
        this.hasNext = hasNext
        loading = false
    }

    fun pageFailed() {
        check(loading) { "No search page request is in progress" }
        loading = false
    }
}
