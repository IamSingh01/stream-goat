package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Keeps the remote's selection where you are.
 *
 * When the selected thing disappears (Install turns into a spinner, a list refreshes, the song list changes when a
 * song starts, a panel closes) Android drops the selection, and the next press of the remote picks the first thing on
 * the screen: the Home icon in the side bar. Here every card ([card]) says where it is; when the selection is lost,
 * it goes to the nearest card that's still there, or, when a panel closes, back to the button that opened it.
 *
 * Also: the side bar and the top bar ([navArea]) can only be entered by pressing towards them (← for the side bar,
 * ↑ for the top bar), so → at the end of a row no longer jumps to the profile or search button.
 */
object FocusKeeper {
    /** Where a card is: the page, a bar (side, top, bottom), or an open panel (each has its own). */
    class Area(val name: String, val nav: Boolean = false, val overlay: Boolean = false) {
        /** For a panel: the card that was selected when it opened (the selection goes back there when it closes). */
        internal var opener: Item? = null
        internal var openerBounds: Rect? = null
        override fun toString() = name
    }

    val PAGE = Area("page")

    class Item(val requester: FocusRequester, val area: Area) {
        internal var coords: LayoutCoordinates? = null
        internal var alive = false
        internal var focused = false
        fun bounds(): Rect? = coords?.takeIf { it.isAttached }?.let { runCatching { it.boundsInRoot() }.getOrNull() }
            ?.takeIf { it.width > 0f && it.height > 0f }
        override fun toString() = "Item($area)"
    }

    private val items = LinkedHashSet<Item>()
    private val overlays = ArrayList<Area>()

    /** The card selected last (it may be gone by now). */
    var current: Item? = null
        private set
    private var currentBounds: Rect? = null
    /** Where to go when the selection was lost because a panel closed. */
    private var returnTo: Area? = null

    /** Whether something on the screen has the selection (AppRoot watches it). */
    internal var rootHasFocus = false
    /** A container that only holds the selection when nothing else does (the player's picture) has it now. */
    internal var fallbackFocused = false
    /** The remote or a keyboard is in use: only then is a lost selection put back. */
    internal var active: () -> Boolean = { false }
    /** The app's window is in front (not behind a dialog, which has its own window). */
    internal var windowFocused: () -> Boolean = { true }
    /** Set by a screen while nothing should be selected (the player with its controls hidden). */
    var paused = false
    /** How long a screen gets to choose its own selection before this steps in. */
    internal var settle = 150L

    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /**
     * The selected card just went away (in this pass of the main thread). Android then selects the first thing on the
     * screen by itself (the side bar's Home icon: the jump people saw); a card selected while this is set is that
     * automatic choice, not a screen's own, so it doesn't count.
     */
    private var justLost = false
    private var autoPicked: Item? = null
    /** Where the lost card was, and in which area (kept apart from [currentBounds], which the jump overwrites). */
    private var lostBounds: Rect? = null
    private var lostArea: Area? = null

    private fun markLost() {
        if (!justLost) {
            lostBounds = currentBounds
            lostArea = current?.area
            autoPicked = null
        }
        justLost = true
        // Over once this pass of the main thread is done: a screen's own choice comes later.
        handler.post { justLost = false }
    }
    /** A lost selection waits for the window to come back to the front (a dialog was open). */
    private var waiting = false

    internal fun register(item: Item) { item.alive = true; items += item }

    /** The card is being taken off the screen (before Android drops its selection and picks another by itself). */
    internal fun detaching(item: Item) {
        // Its selection was dropped just before (and nothing else was chosen since): what Android selects next by
        // itself is the jump.
        if (item === current) { markLost(); lost() }
    }

    internal fun unregister(item: Item) {
        val wasCurrent = item === current
        item.alive = false
        item.focused = false
        items -= item
        if (wasCurrent) { markLost(); lost() }
    }

    internal fun focusChanged(item: Item, focused: Boolean) {
        item.focused = focused
        if (restoring) {
            if (focused) {
                current = item; currentBounds = item.bounds() ?: currentBounds
                justLost = false; autoPicked = null; lostBounds = null; lostArea = null; returnTo = null
            }
            return
        }
        if (focused) {
            if (justLost) { autoPicked = item; current = item; return }
            current = item
            currentBounds = item.bounds() ?: currentBounds
            returnTo = null
            waiting = false
            autoPicked = null
            lostBounds = null; lostArea = null
        } else if (item === current) {
            lostBounds = currentBounds; lostArea = item.area
            lost()
        }
    }

    internal fun placed(item: Item, coords: LayoutCoordinates) {
        item.coords = coords
        if (item === current && item.focused) item.bounds()?.let { currentBounds = it }
    }

    internal fun overlayOpened(area: Area) {
        // A panel opened from another panel (Subtitles → Sync subtitles) returns to what opened the first one.
        val cur = current
        val closing = returnTo?.takeIf { justLost && it.overlay }   // the panel this one replaces, in this same pass
        val from = closing ?: cur?.area?.takeIf { it.overlay && it !== area }
        when {
            from != null -> { area.opener = from.opener; area.openerBounds = from.openerBounds }
            // Opened in the pass that dropped the selection: Android's own pick isn't what opened it.
            justLost && cur === autoPicked -> { area.opener = null; area.openerBounds = lostBounds }
            else -> { area.opener = cur; area.openerBounds = currentBounds }
        }
        overlays += area
    }

    internal fun overlayClosed(area: Area) {
        overlays -= area
        returnTo = area
        markLost()
        lost()
    }

    internal fun windowFocusChanged(focused: Boolean) {
        if (focused && waiting) lost()
    }

    private val restoreLater = Runnable { restore() }

    private fun lost() {
        handler.removeCallbacks(restoreLater)
        handler.postDelayed(restoreLater, settle)
    }

    /** The selection was lost: put it back (if nothing else has taken it in the meantime). */
    internal fun restore() {
        if (!active() || paused) { waiting = false; return }
        if (returnBounds != null) return   // Back is bringing the remembered item back

        if (!windowFocused()) { waiting = true; return }
        waiting = false
        val closed = returnTo
        val cur = current
        val auto = autoPicked?.takeIf { it.alive && it.focused }
        // A card is selected (a screen chose its own selection): leave it. Not if Android picked it by itself.
        if (cur != null && cur.alive && cur.focused && cur !== auto) { returnTo = null; lostBounds = null; return }
        // Something that isn't a card took the selection on purpose (a search box, a slider): leave it. Only the
        // fallback (the container the selection falls to when the selected card disappears) doesn't count.
        if (rootHasFocus && !fallbackFocused && auto == null) { returnTo = null; return }
        val top = overlays.lastOrNull()
        val from = lostBounds ?: currentBounds
        val was = lostArea ?: cur?.area ?: PAGE
        returnTo = null; autoPicked = null; lostBounds = null; lostArea = null
        // A panel closed: back to the button that opened it, if it's still there.
        if (closed != null && top == null) {
            val opener = closed.opener
            if (opener != null && opener.alive && tryFocus(opener)) return
            nearest(closed.openerBounds ?: from, pool(null, opener?.area ?: PAGE))?.let { tryFocus(it) }
            return
        }
        nearest(from, pool(top, was))?.let { tryFocus(it) }
    }

    /** Where the selection may go: inside the open panel; else the page (and the bars only if it was in a bar). */
    private fun pool(top: Area?, was: Area): List<Item> =
        if (top != null) items.filter { it.area === top }
        else items.filter { !it.area.overlay && (!it.area.nav || was.nav) }

    private fun nearest(from: Rect?, pool: List<Item>): Item? {
        val withBounds = pool.mapNotNull { i -> i.bounds()?.let { i to it } }
        if (withBounds.isEmpty()) return null
        if (from == null) return withBounds.minWith(compareBy({ it.second.top }, { it.second.left })).first
        val c = from.center
        // Same row first: moving up or down counts more than sideways.
        return withBounds.minBy { (_, b) ->
            val dx = b.center.x - c.x; val dy = (b.center.y - c.y) * 2.5f
            dx * dx + dy * dy
        }.first
    }

    // ---------- Back: the same item selected again ----------

    /** Where the selected item was on each screen you left (by tab and depth, see Nav). */
    private val places = HashMap<String, Rect>()
    private var returnBounds: Rect? = null
    private var returnTry = 0
    private val returnDelays = longArrayOf(120, 300, 600, 1000, 1600)
    private val returnLater = Runnable { tryReturn() }

    /** Back is on its way to a screen that will get its selected item back: screens leave their own first choice. */
    val returning: Boolean get() = returnBounds != null

    /** Leaving a screen for another one on top: remember its selected item. */
    internal fun leaving(place: String) {
        val c = current
        val b = if (c != null && c.alive && c.focused && !c.area.nav && !c.area.overlay) c.bounds() else null
        if (b != null) places[place] = b else places.remove(place)
    }

    /** Back on a screen left before: select its item again once it's drawn (its scroll position comes back too). */
    internal fun returning(place: String) {
        handler.removeCallbacks(returnLater)
        returnBounds = places.remove(place)
        returnTry = 0
        if (returnBounds != null) handler.postDelayed(returnLater, returnDelays[0])
    }

    /** Another tab: the remembered places were for the screens just closed. */
    internal fun forgetPlaces() {
        places.clear()
        cancelReturn()
    }

    /** A key was pressed: you're moving yourself, so nothing is brought back any more. */
    internal fun keyPressed() { if (returnBounds != null) cancelReturn() }

    private fun cancelReturn() {
        returnBounds = null
        runCatching { handler.removeCallbacks(returnLater) }
    }

    private fun tryReturn() {
        val target = returnBounds ?: return
        if (!active()) { cancelReturn(); return }
        val pool = items.filter { !it.area.overlay && !it.area.nav }.mapNotNull { i -> i.bounds()?.let { i to it } }
        // The item at the same place (the screen and its scroll position are back as they were).
        val c = target.center
        val there = pool.filter { (_, b) -> b.contains(c) }.minByOrNull { (_, b) -> b.width * b.height }?.first
        val last = returnTry >= returnDelays.size - 1
        val pick = there ?: if (last) nearest(target, pool.map { it.first }) else null
        if (pick != null && windowFocused()) {
            returnBounds = null
            tryFocus(pick)
            return
        }
        if (last) { returnBounds = null; return }
        returnTry++
        handler.postDelayed(returnLater, returnDelays[returnTry] - returnDelays[returnTry - 1])
    }

    /** Set while this puts the selection back itself (that's not a jump, and the card it leaves isn't lost). */
    private var restoring = false

    private fun tryFocus(item: Item): Boolean {
        restoring = true
        try {
            runCatching { item.requester.requestFocus() }
        } finally { restoring = false }
        return item.focused
    }

    internal fun reset() {
        runCatching { handler.removeCallbacks(restoreLater) }; items.clear(); overlays.clear(); current = null; currentBounds = null; returnTo = null
        rootHasFocus = false; fallbackFocused = false; paused = false; waiting = false
        places.clear(); returnBounds = null; runCatching { handler.removeCallbacks(returnLater) }
        justLost = false; autoPicked = null; lostBounds = null; lostArea = null
    }
}

/** The area cards are in (the page unless a bar or a panel says otherwise). */
val LocalFocusArea = staticCompositionLocalOf { FocusKeeper.PAGE }

/**
 * The app's root: tells [FocusKeeper] whether anything is selected and whether the remote (or a keyboard) is in use.
 * Put once around everything (AppRoot, and the tests).
 */
@Composable
fun FocusKeeperRoot(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val inputMode = LocalInputModeManager.current
    val window = LocalWindowInfo.current
    DisposableEffect(Unit) {
        val tv = runCatching { isTv }.getOrDefault(false)
        FocusKeeper.active = { tv || inputMode.inputMode == InputMode.Keyboard }
        FocusKeeper.windowFocused = { window.isWindowFocused }
        onDispose { }
    }
    val inFront = window.isWindowFocused
    androidx.compose.runtime.LaunchedEffect(inFront) { FocusKeeper.windowFocusChanged(inFront) }
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var backDown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    content(modifier.onFocusChanged { FocusKeeper.rootHasFocus = it.hasFocus }
        .onPreviewKeyEvent { e ->
            if (e.key == Key.Back && dispatcher != null) {
                // The remote's Back goes straight to the app's Back (close the panel, leave the page…). Left to
                // itself, Android first uses Back to move the selection out of each group it's in (a panel, a list),
                // so it took 2, 3, 4 presses before a panel closed.
                when (e.type) {
                    KeyEventType.KeyDown -> backDown = true
                    KeyEventType.KeyUp -> if (backDown) { backDown = false; dispatcher.onBackPressed() }
                }
                return@onPreviewKeyEvent true
            }
            // Any other key means you're moving yourself: nothing is brought back for you any more.
            if (e.type == KeyEventType.KeyDown) FocusKeeper.keyPressed()
            false
        })
}

private val arrows = setOf(FocusDirection.Left, FocusDirection.Right, FocusDirection.Up, FocusDirection.Down)
private val moves = arrows + setOf(FocusDirection.Next, FocusDirection.Previous)

/**
 * A bar (the side bar, the top bar, the phone's bottom bar): its buttons are reached only by pressing towards it
 * ([from]: ← for a bar on the left, ↑ for one at the top), or from another bar. Programmatic selection is allowed.
 */
@Composable
fun NavArea(name: String, from: List<FocusDirection>, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val area = remember(name) { FocusKeeper.Area(name, nav = true) }
    val allowed = remember(from) { from.toSet() }
    CompositionLocalProvider(LocalFocusArea provides area) {
        content(modifier.focusProperties {
            onEnter = {
                if (requestedFocusDirection in arrows && requestedFocusDirection !in allowed && FocusKeeper.current?.area?.nav != true) cancelFocusChange()
            }
        }.focusGroup())
    }
}

/**
 * A panel over the screen (the player's Sources, Subtitles, Audio…, a song's menu): the remote stays inside it,
 * and when it closes the selection goes back to the button that opened it. [content] gets the modifier for the
 * panel's own box.
 */
@Composable
fun FocusOverlay(name: String, content: @Composable (Modifier) -> Unit) {
    val area = remember { FocusKeeper.Area(name, overlay = true) }
    DisposableEffect(area) {
        FocusKeeper.overlayOpened(area)
        onDispose { FocusKeeper.overlayClosed(area) }
    }
    CompositionLocalProvider(LocalFocusArea provides area) {
        content(Modifier.focusProperties {
            onExit = { if (requestedFocusDirection in moves) cancelFocusChange() }
        }.focusGroup())
    }
}

/** A row whose → at the last item stays put (instead of jumping to whatever is nearest on the right). */
fun Modifier.stopAtRowEnd(): Modifier = this.focusProperties {
    onExit = { if (requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
}.focusGroup()

/**
 * A focusable container that the selection falls back to when the selected card inside it disappears (the player's
 * picture): when it gets the selection that way, [FocusKeeper] still puts it back on a card.
 */
fun Modifier.focusFallback(): Modifier = this.onFocusChanged { FocusKeeper.fallbackFocused = it.isFocused }

/** Tells [FocusKeeper] when a card is taken off the screen, before its selection is dropped. */
internal fun Modifier.watchDetach(item: FocusKeeper.Item): Modifier = this.then(DetachWatchElement(item))

private class DetachWatch(var item: FocusKeeper.Item) : Modifier.Node() {
    override fun onDetach() { FocusKeeper.detaching(item) }
}

private data class DetachWatchElement(val item: FocusKeeper.Item) : androidx.compose.ui.node.ModifierNodeElement<DetachWatch>() {
    override fun create() = DetachWatch(item)
    override fun update(node: DetachWatch) { node.item = item }
}

/**
 * Back one step at a time: with the selection inside a page's content (a Settings section, the Downloads list),
 * Back first goes to the page's own tabs or section list ([header]); only from there does it leave the page.
 * Returns the modifier for the content.
 */
@Composable
fun backToHeader(header: FocusRequester): Modifier {
    var inside by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = inside) {
        runCatching { header.requestFocus() }
        if (inside) Nav.back()   // the header couldn't take it: leave as before
    }
    return Modifier.onFocusChanged { inside = it.hasFocus }
}
