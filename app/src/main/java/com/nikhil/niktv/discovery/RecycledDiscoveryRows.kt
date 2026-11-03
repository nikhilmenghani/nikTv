package com.nikhil.niktv.discovery

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.*
import com.nikhil.niktv.data.thumbnailArtworkUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val HERO_ROW = "__discovery_hero__"
private const val ROW_ALL = "__row_all__"
private const val ROW_REMOVE = "__row_remove__"
internal typealias RecycledHeroContent = @Composable (focused: (String) -> Unit, down: () -> Unit, up: () -> Unit) -> Unit

internal data class NativeRowViewport(var item: String, var offset: Int)
internal class RecycledPosition(var row: String = "", var item: String = "") {
    val columns = mutableMapOf<String, String>()
    var anchorRow = ""
    var anchorOffset = 0
    val horizontal = mutableMapOf<String, NativeRowViewport>()
    fun snapshot(): List<String> {
        fun <T> bounded(values: Map<String, T>): List<Map.Entry<String, T>> {
            val important = values.entries.filter { it.key == row || it.key == anchorRow }
            return values.entries.filterNot { it.key == row || it.key == anchorRow }.takeLast(256 - important.size) + important
        }
        val rememberedColumns = bounded(columns); val rememberedHorizontal = bounded(horizontal)
        return listOf("viewport-v2", row, item, anchorRow, anchorOffset.toString(), rememberedColumns.size.toString()) +
            rememberedColumns.flatMap { listOf(it.key, it.value) } + listOf(rememberedHorizontal.size.toString()) +
            rememberedHorizontal.flatMap { listOf(it.key, it.value.item, it.value.offset.toString()) }
    }
    companion object {
        fun restore(values: List<String>): RecycledPosition = runCatching {
            if (values.firstOrNull() != "viewport-v2") return@runCatching RecycledPosition(values[0], values[1]).also { p ->
                values.drop(2).chunked(2).forEach { p.columns[it[0]] = it[1] }
            }
            val v = values.iterator(); v.next()
            RecycledPosition(v.next(), v.next()).also { p ->
                p.anchorRow = v.next(); p.anchorOffset = v.next().toInt()
                repeat(v.next().toInt().coerceIn(0, 256)) { p.columns[v.next()] = v.next() }
                repeat(v.next().toInt().coerceIn(0, 256)) { p.horizontal[v.next()] = NativeRowViewport(v.next(), v.next().toInt()) }
            }
        }.getOrElse { RecycledPosition() }
        val Saver = listSaver<RecycledPosition, String>(
            save = { it.snapshot() }, restore = ::restore)
    }
}

/** Full feed, but only viewport holders and a small shared card pool are materialized. */
@Composable
internal fun RecycledDiscoveryRows(rows: List<NativeDiscoveryRow>, modifier: Modifier,
    focusEntry: FocusRequester, up: () -> Unit, visible: (String) -> Unit = {}, animateFocus: Boolean = true,
    containedCaptions: Boolean = false, cardShadows: Boolean = false, homeTileProportions: Boolean = false,
    hero: RecycledHeroContent? = null, heroFocusEntry: (String) -> Boolean = { false }, restoreSelection: Boolean = true, heroActivity: ((HeroActivity) -> Unit)? = null, heroHeightPx: Int? = null, cacheTileDrawing: Boolean = false, focusRestorer: NativeDiscoveryFocusRestorer? = null,
    popupAnchor: (androidx.compose.ui.unit.IntRect) -> Unit = {}, showSkeletons: Boolean = false) {
    val position = rememberSaveable(saver = RecycledPosition.Saver) { RecycledPosition() }
    val host = LocalView.current
    val parentComposition = rememberCompositionContext()
    val displayedRows = remember(rows, hero != null) {
        if (hero != null) listOf(NativeDiscoveryRow(HERO_ROW, "", emptyList(), isHero = true)) + rows else rows
    }
    val configuration = LocalConfiguration.current
    key(containedCaptions, cardShadows, homeTileProportions, heroHeightPx, cacheTileDrawing, showSkeletons,
        configuration.screenWidthDp, configuration.screenHeightDp, configuration.fontScale) {
    AndroidView(factory = { RailRecycler(it, position, containedCaptions, cardShadows, homeTileProportions, heroHeightPx, parentComposition, cacheTileDrawing, showSkeletons) }, modifier = modifier.focusRequester(focusEntry),
        onRelease = { focusRestorer?.detach(it); it.release() }, update = { view ->
            focusRestorer?.attach(view) { runCatching { focusEntry.requestFocus() }; view.requestSavedFocus() }
            view.animateFocus = animateFocus
            view.restoreSelection = restoreSelection
            view.heroContent = hero; view.heroFocusEntry = heroFocusEntry
            view.onHeroActivity = heroActivity
            view.onPopupAnchor = popupAnchor
            view.onUp = { view.leave(host, up) }; view.onVisible = visible; view.update(displayedRows)
        })
    }
}

private fun NativeDiscoveryItem.samePresentation(other: NativeDiscoveryItem) =
    id == other.id && title == other.title && subtitle == other.subtitle &&
        artwork == other.artwork && progress == other.progress && source == other.source
private val rowDiff = object : DiffUtil.ItemCallback<NativeDiscoveryRow>() {
    override fun areItemsTheSame(a: NativeDiscoveryRow, b: NativeDiscoveryRow) = a.id == b.id
    override fun areContentsTheSame(a: NativeDiscoveryRow, b: NativeDiscoveryRow) =
        a.isHero == b.isHero && a.title == b.title && a.loading == b.loading && a.actionLabel == b.actionLabel && (a.action != null) == (b.action != null) &&
            (a.viewAll != null) == (b.viewAll != null) && a.items.size == b.items.size &&
            a.items.indices.all { a.items[it].samePresentation(b.items[it]) }
}
private val cardDiff = object : DiffUtil.ItemCallback<NativeDiscoveryItem>() {
    override fun areItemsTheSame(a: NativeDiscoveryItem, b: NativeDiscoveryItem) = a.id == b.id
    override fun areContentsTheSame(a: NativeDiscoveryItem, b: NativeDiscoveryItem) = a.samePresentation(b)
}

private class RailRecycler(context: Context, private val position: RecycledPosition,
    private val containedCaptions: Boolean, private val cardShadows: Boolean, private val homeTileProportions: Boolean, private val heroHeightPx: Int?,
    private val parentComposition: CompositionContext, private val cacheTileDrawing: Boolean, private val showSkeletons: Boolean) : RecyclerView(context), ComponentCallbacks2 {
    private val tileCache = NativeTileCachePolicy(cacheTileDrawing)
    private var callbacksRegistered = false
    private var pressure = false
    private var warmPoolJob: Job? = null
    var onHeroActivity: ((HeroActivity) -> Unit)? = null
    private var touchingHeroFeed = false
    private val scrollingChildren = mutableSetOf<RecyclerView>()
    private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> reportHeroActivity() }
    private fun reportHeroActivity() {
        val report = onHeroActivity ?: return
        val header = findViewHolderForAdapterPosition(0)?.takeIf { models[rowAdapter.currentList.firstOrNull()?.id]?.isHero == true }?.itemView
        val visible = Rect()
        val shown = header != null && header.getGlobalVisibleRect(visible) && visible.height() >= header.height * .8f
        report(HeroActivity(shown, hasWindowFocus(), hasFocus(), scrollState != SCROLL_STATE_IDLE || scrollingChildren.isNotEmpty(), touchingHeroFeed, false))
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!callbacksRegistered) { context.applicationContext.registerComponentCallbacks(this); callbacksRegistered = true }
        tileCache.windowFocus(hasWindowFocus())
        viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
        if (callbacksRegistered) { context.applicationContext.unregisterComponentCallbacks(this); callbacksRegistered = false }
        tileCache.windowFocus(false); refreshTileLayers()
        touchingHeroFeed = false; scrollingChildren.clear()
        onHeroActivity?.invoke(HeroActivity(false, false, false, false, false, false))
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        tileCache.windowFocus(hasWindowFocus); refreshTileLayers()
        if (!hasWindowFocus) for (index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as? RowHolder)?.cancelRemoteInput()
        if (hasWindowFocus && hasFocus()) pending?.let { reveal(it.first, it.second) }
        reportHeroActivity()
    }
    private fun refreshTileLayers() {
        for (index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as? RowHolder)?.refreshTileLayers()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
    override fun onLowMemory() = trimTileCaches()
    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { tileCache.windowFocus(false); refreshTileLayers() }
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimTileCaches()
    }
    private fun trimTileCaches() {
        pressure = true; warmed = true; tileCache.memoryPressure(); warmPoolJob?.cancel()
        pool.clear(); recycledViewPool.clear(); setItemViewCacheSize(0)
        for (index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as? RowHolder)?.trimTileCaches()
        refreshTileLayers()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (restoringViewport) {
            restoringViewport = false
            val target = pending; val ticket = epoch
            postOnAnimation { if (target != null && ticket == epoch && pending == target && hasFocus()) reveal(target.first, target.second) }
        }
        reportHeroActivity()
    }
    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        if (viewportInitialized && !restoringViewport) {
            val index = vertical.findFirstVisibleItemPosition()
            rowAdapter.currentList.getOrNull(index)?.let { row ->
                vertical.findViewByPosition(index)?.let { view -> position.anchorRow = row.id; position.anchorOffset = view.top - paddingTop }
            }
        }
        reportHeroActivity()
    }
    override fun onScrollStateChanged(state: Int) { super.onScrollStateChanged(state); reportHeroActivity() }
    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean): Boolean {
        if (child.hasFocus() && rowAdapter.currentList.firstOrNull()?.isHero == true &&
            findViewHolderForAdapterPosition(0)?.itemView === child) {
            stopScroll()
            vertical.scrollToPositionWithOffset(0, 0)
            return true
        }
        if (restoringSelectionViewport) {
            val requested = Rect(rect)
            offsetDescendantRectToMyCoords(child, requested)
            if (requested.top >= 0 && requested.bottom <= height && requested.left >= 0 && requested.right <= width) return false
        }
        return super.requestChildRectangleOnScreen(child, rect, immediate)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchingHeroFeed = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchingHeroFeed = false
        }
        val result = super.dispatchTouchEvent(event)
        reportHeroActivity()
        return result
    }
    var heroContent by mutableStateOf<RecycledHeroContent?>(null)
    var heroFocusEntry: (String) -> Boolean = { false }
    var restoreSelection = true
    var animateFocus = true
        set(value) {
            if (field && !value) findFocus()?.let { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f }
            field = value
        }
    var onUp: () -> Unit = {}
    var onVisible: (String) -> Unit = {}
    var onPopupAnchor: (androidx.compose.ui.unit.IntRect) -> Unit = {}
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val images = Semaphore(2)
    private var visibleJob: Job? = null
    private var warmed = false
    private var lastInputAt = 0L
    private val pool = RecycledViewPool().apply { setMaxRecycledViews(0, 16) }
    private var models = emptyMap<String, NativeDiscoveryRow>()
    private var railItems = emptyMap<String, List<NativeDiscoveryItem>>()
    private var submitted: List<NativeDiscoveryRow>? = null
    private var pending: Pair<String, String>? = null
    private var loadingFocusRow: String? = null
    private var loadingEntryRow: String? = null
    private var parkingLoadingFocus = false
    private val savedAnchorRow = position.anchorRow
    private val savedAnchorOffset = position.anchorOffset
    private var viewportInitialized = false
    private var restoringViewport = false
    private var restoringSelectionViewport = false
    private var epoch = 0L
    private val stableIds = mutableMapOf<String, Long>()
    private fun stableId(key: String) = stableIds.getOrPut(key) { stableIds.size.toLong() }
    private val vertical = LinearLayoutManager(context)
    private val motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private val rowAdapter = object : ListAdapter<NativeDiscoveryRow, RowHolder>(rowDiff) {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = stableId("row:${getItem(position).id}")
        override fun getItemViewType(position: Int) = if (getItem(position).isHero) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = RowHolder(type == 1)
        override fun onBindViewHolder(holder: RowHolder, index: Int) { holder.bind(getItem(index)) }
        override fun onViewRecycled(holder: RowHolder) { holder.cards.submitList(emptyList()) }
        override fun onViewDetachedFromWindow(holder: RowHolder) { holder.stopMotion(); reportHeroActivity() }
        override fun onFailedToRecycleView(holder: RowHolder): Boolean { holder.stopMotion(); return true }
        override fun onViewAttachedToWindow(holder: RowHolder) {
            holder.refreshTileLayers()
            val row = holder.rowId
            visibleJob?.cancel()
            visibleJob = work.launch { delay(400); if (holder.itemView.isAttachedToWindow && holder.rowId == row) onVisible(row) }
        }
        override fun onCurrentListChanged(previous: MutableList<NativeDiscoveryRow>, current: MutableList<NativeDiscoveryRow>) {
            if (!viewportInitialized && current.isNotEmpty()) {
                val anchor = current.indexOfFirst { it.id == savedAnchorRow }
                if (!restoreSelection || anchor >= 0 || current.any { !it.isHero } || savedAnchorRow.isBlank()) {
                    viewportInitialized = true
                    if (restoreSelection && anchor >= 0) {
                        restoringViewport = true
                        restoringSelectionViewport = true
                        vertical.scrollToPositionWithOffset(anchor, savedAnchorOffset)
                    }
                }
            }
            if (hasFocus() && findFocus()?.tag !is String &&
                (position.row != HERO_ROW || findFocus() === this@RailRecycler)) {
                val row = current.firstOrNull { it.id == position.row && (it.isHero || it.items.isNotEmpty()) } ?: current.firstOrNull { it.isHero || it.items.isNotEmpty() }
                val target = pending ?: row?.let { it.id to selectionFor(it) }
                target?.let { reveal(it.first, it.second) }
            }
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun px(n: Float) = (n * resources.displayMetrics.density + .5f).toInt()
    private val polishedCaptions = containedCaptions && !homeTileProportions
    private val posterGeometry = resources.configuration.let { nativePosterGeometry(it.screenWidthDp.toFloat(), it.screenHeightDp.toFloat(), it.fontScale) }
    init {
        layoutManager = vertical; adapter = rowAdapter; itemAnimator = null
        recycledViewPool.setMaxRecycledViews(0, 4)
        recycledViewPool.setMaxRecycledViews(1, 1)
        // Retain a small neighborhood of prepared rows; avoid rebinding the same
        // posters during short direction reversals without materializing the feed.
        setItemViewCacheSize(4); clipToPadding = false
        setPadding(dp(18), dp(10), dp(18), dp(24))
        setBackgroundColor(Color.rgb(9, 11, 16)); outlineProvider = ViewOutlineProvider.BOUNDS; clipToOutline = true
        isFocusable = true; isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    fun update(rows: List<NativeDiscoveryRow>) {
        if (submitted === rows) return
        rows.firstOrNull { it.id == loadingFocusRow && it.items.isNotEmpty() }?.takeIf { hasFocus() }?.let { loaded ->
            pending = loaded.id to (position.columns[loaded.id] ?: loaded.items.first().id)
            loadingFocusRow = null
            loadingEntryRow = loaded.id
            (findViewHolderForAdapterPosition(rowAdapter.currentList.indexOfFirst { it.id == loaded.id }) as? RowHolder)?.enableControlsFocus(false)
            // Park before DiffUtil/layout disables or recycles the loading heading.
            parkingLoadingFocus = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; requestFocus()
        }
        val previous = rowAdapter.currentList.map { it.id }
        val replacement = nativeRowReplacement(previous, rows.map { it.id }, position.row)
        if (hasFocus() && position.row in previous && position.row != replacement && replacement != null) {
            // Park inside this island while DiffUtil removes its focused holder.
            val policy = descendantFocusability
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; requestFocus(); descendantFocusability = policy
            pending = replacement to (position.columns[replacement] ?: rows.first { it.id == replacement }.items.firstOrNull()?.id.orEmpty())
            position.row = replacement; position.item = pending!!.second
        }
        submitted = rows
        models = rows.associateBy { it.id }
        railItems = rows.associate { row -> row.id to buildList {
            addAll(row.items)
            // Loading categories have no selectable tail until media is ready.
            if (row.items.isNotEmpty()) {
                row.viewAll?.let { add(NativeDiscoveryItem(ROW_ALL, "View all", onClick = it)) }
                row.action?.let { add(NativeDiscoveryItem(ROW_REMOVE, "×", onClick = it)) }
            }
        } }
        rowAdapter.submitList(rows.toList())
    }
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val row = if (heroContent != null && !restoreSelection) models[HERO_ROW] else
            models[position.row]?.takeIf { it.isHero || it.items.isNotEmpty() } ?: rowAdapter.currentList.firstOrNull { it.isHero || it.items.isNotEmpty() }
        val target = if (heroContent != null && !restoreSelection) HERO_ROW to "play" else
            pending ?: row?.let { it.id to selectionFor(it) }
        target?.let {
            if (restoringViewport) { pending = it; epoch++; return false }
            // Compose hero focus cannot be committed from Android's descendant
            // search: the bridge would subsequently focus this outer anchor and
            // erase it. Complete that handoff on the next frame instead.
            if (it.first == HERO_ROW) {
                pending = it
                val ticket = ++epoch
                postOnAnimation {
                    if (ticket == epoch && pending == it && isAttachedToWindow && hasWindowFocus() && hasFocus()) reveal(it.first, it.second)
                }
                return false
            }
            val index = rowAdapter.currentList.indexOfFirst { row -> row.id == it.first }
            val holder = findViewHolderForAdapterPosition(index) as? RowHolder
            // Returning false after a successful child request would focus the
            // outer anchor again and erase the just-restored card's focus.
            if (holder?.focus(it.second, false) == true) { pending = null; finishViewportRestore(); return true }
            reveal(it.first, it.second)
        }
        return false // Outer native anchor owns entry until the recycled holder is laid out.
    }
    private fun reveal(row: String, item: String) {
        if (parkingLoadingFocus && row != loadingEntryRow) {
            parkingLoadingFocus = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val index = rowAdapter.currentList.indexOfFirst { it.id == row }
        if (index < 0) return
        if (restoringViewport) { pending = row to item; epoch++; return }
        // A previous row's smooth reveal must not keep moving after a reversal.
        stopScroll()
        if (row == HERO_ROW) vertical.scrollToPositionWithOffset(0, 0)
        pending = row to item; val ticket = ++epoch
        val attached = findViewHolderForAdapterPosition(index) as? RowHolder
        if (attached?.focus(item, false) == true) { pending = null; finishViewportRestore(); return }
        // Recycling a focused offscreen holder must not transfer focus outside this island.
        val policy = descendantFocusability
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        requestFocus()
        descendantFocusability = policy
        val current = findViewHolderForAdapterPosition(index) as? RowHolder
        if (current == null) vertical.scrollToPositionWithOffset(index, dp(8))
        fun retry(attempt: Int) { postOnAnimation {
            if (ticket != epoch || !isAttachedToWindow || !hasWindowFocus() || !hasFocus()) return@postOnAnimation
            // Async feed updates can insert/remove rows while the target is laying out.
            // Resolve the stable identity again rather than focusing an old numeric slot.
            val currentIndex = rowAdapter.currentList.indexOfFirst { it.id == row }
            if (currentIndex < 0) { pending = null; return@postOnAnimation }
            val holder = findViewHolderForAdapterPosition(currentIndex) as? RowHolder
            if (holder == null) vertical.scrollToPositionWithOffset(currentIndex, dp(8))
            if (holder == null || !holder.focus(item)) { if (attempt < 30) retry(attempt + 1) }
            else { pending = null; finishViewportRestore() }
        } }
        retry(0)
    }
    private fun finishViewportRestore() {
        if (restoringSelectionViewport) postOnAnimation { restoringSelectionViewport = false }
    }
    fun requestSavedFocus(): Boolean {
        if (!isAttachedToWindow || !hasWindowFocus()) return false
        val row = models[position.row] ?: rowAdapter.currentList.firstOrNull { it.items.isNotEmpty() } ?: return false
        val item = position.item.takeIf { position.row == row.id } ?: row.items.firstOrNull()?.id ?: return false
        val index = rowAdapter.currentList.indexOfFirst { it.id == row.id }
        val holder = findViewHolderForAdapterPosition(index) as? RowHolder
        if (holder?.focus(item, fromTouch = true) == true) { finishViewportRestore(); return true }
        restoreSelection = true
        requestFocusFromTouch()
        reveal(row.id, item)
        return false
    }
    fun leave(host: View, action: () -> Unit) {
        if (parkingLoadingFocus) { parkingLoadingFocus = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        epoch++; pending = null
        val policy = descendantFocusability
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; isFocusable = false
        clearFocus(); host.requestFocus(); action()
        isFocusable = true; descendantFocusability = policy
    }
    private fun selectionFor(row: NativeDiscoveryRow): String =
        position.item.takeIf { position.row == row.id && it.isNotBlank() }
            ?: position.columns[row.id] ?: if (row.isHero) "play" else row.items.firstOrNull()?.id.orEmpty()
    private fun adjacent(row: String, step: Int) {
        val list = rowAdapter.currentList
        val source = pending?.first ?: row
        val index = list.indexOfFirst { it.id == source } + step
        if (index < 0) onUp()
        else list.getOrNull(index)?.let {
            reveal(it.id, position.columns[it.id] ?: if (it.isHero) "play" else it.items.firstOrNull()?.id.orEmpty())
        }
    }
    private fun selected(row: String, item: String) {
        loadingEntryRow?.takeIf { it != row }?.let { old ->
            loadingEntryRow = null
            (findViewHolderForAdapterPosition(rowAdapter.currentList.indexOfFirst { it.id == old }) as? RowHolder)?.enableControlsFocus(true)
        }
        loadingFocusRow = row.takeIf { item.isBlank() && models[row]?.items.isNullOrEmpty() }
        position.row = row; position.item = item.ifBlank { position.columns[row].orEmpty() }
        if (item.isNotBlank() && item != ROW_ALL && item != ROW_REMOVE) position.columns[row] = item
    }
    private fun horizontal(row: String, item: String, direction: Int) {
        val items = railItems[row].orEmpty()
        val next = items.indexOfFirst { it.id == item } + direction
        items.getOrNull(next)?.let { reveal(row, it.id) }
    }
    private fun focusRowAction(row: String): Boolean {
        val model = models[row] ?: return false
        val item = if (model.action != null) ROW_REMOVE else if (model.viewAll != null) ROW_ALL else return false
        if (model.items.isEmpty()) return false
        reveal(row, item)
        return true
    }
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.action == KeyEvent.ACTION_DOWN) lastInputAt = android.os.SystemClock.uptimeMillis()
        val target = pending
        if (target != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { adjacent(target.first, 1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { adjacent(target.first, -1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { horizontal(target.first, target.second, 1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { horizontal(target.first, target.second, -1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    private inner class RowHolder(private val hero: Boolean = false) : ViewHolder(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }) {
        var rowId = ""
        private var restoreHorizontal = false
        private var horizontalLayoutPending = false
        private var awaitingSkeletonCards = false
        private val title = TextView(context).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(3), dp(5), 0, dp(6)); ViewCompat.setAccessibilityHeading(this, true)
        }
        private val horizontal = object : RecyclerView(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                horizontalLayoutPending = false
                rememberHorizontalViewport()
            }
        }.apply {
            layoutManager = LinearLayoutManager(context, HORIZONTAL, false).apply {
                // Nested RV defaults prepare only two cards. Prepare one visible rail
                // within GapWorker's frame deadline instead of inflating it on Down.
                initialPrefetchItemCount = 9
            }
            addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { rememberHorizontalViewport() }
                override fun onScrollStateChanged(recyclerView: RecyclerView, state: Int) {
                    if (state == SCROLL_STATE_IDLE) scrollingChildren.remove(recyclerView) else scrollingChildren.add(recyclerView)
                    reportHeroActivity()
                }
            })
            setRecycledViewPool(pool); itemAnimator = null; setItemViewCacheSize(1)
            setPadding(dp(4), dp(5), dp(4), dp(10)); clipToPadding = false; isFocusable = false
        }
        private val skeleton = if (showSkeletons) DiscoveryRowSkeleton(context,
            if (polishedCaptions) px(posterGeometry.width) else dp(104)).apply { visibility = View.GONE } else null
        val cards = object : ListAdapter<NativeDiscoveryItem, ViewHolder>(cardDiff) {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = stableId("card:$rowId:${getItem(position).id}")
            override fun getItemViewType(position: Int): Int {
                val id = getItem(position).id
                return if (id == ROW_ALL || id == ROW_REMOVE) 1 else 0
            }
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): ViewHolder = if (type == 1) ControlHolder() else CardHolder()
            override fun onBindViewHolder(holder: ViewHolder, index: Int) {
                when (holder) {
                    is CardHolder -> holder.bind(rowId, getItem(index))
                    is ControlHolder -> holder.bind(rowId, getItem(index))
                }
            }
            override fun onViewRecycled(holder: ViewHolder) { (holder as? CardHolder)?.cancel() }
            override fun onViewAttachedToWindow(holder: ViewHolder) {
                (holder as? CardHolder)?.let {
                    it.cacheLayer(tileCache.enabled)
                    if (awaitingSkeletonCards && (it.itemView.tag as? String)?.startsWith("$rowId:") == true) {
                        awaitingSkeletonCards = false; skeleton?.visibility = View.GONE
                    }
                }
            }
            override fun onViewDetachedFromWindow(holder: ViewHolder) { (holder as? CardHolder)?.apply { cacheLayer(false); cancelRemoteInput() } }
            override fun onFailedToRecycleView(holder: ViewHolder): Boolean { (holder as? CardHolder)?.cancel(); return true }
        }
        init {
            if (hero) {
                (itemView as LinearLayout).addView(ComposeView(context).apply {
                    setParentCompositionContext(parentComposition)
                    setContent { heroContent?.invoke({ item ->
                        if (pending == null || pending == (HERO_ROW to item)) selected(HERO_ROW, item)
                    }, { adjacent(HERO_ROW, 1) }, { onUp() }) }
                }, LinearLayout.LayoutParams(-1, heroHeightPx ?: -2))
            } else {
            (itemView as LinearLayout).addView(title, LinearLayout.LayoutParams(-1, -2))
            val rail: View = if (skeleton == null) horizontal else FrameLayout(context).apply {
                addView(horizontal, FrameLayout.LayoutParams(-1, -1))
                addView(skeleton, FrameLayout.LayoutParams(-1, -1))
            }
            (itemView as LinearLayout).addView(rail, LinearLayout.LayoutParams(-1,
                if (polishedCaptions) px(posterGeometry.railHeight) else dp(if (homeTileProportions) 167 else 172 + (48 * resources.configuration.fontScale).toInt())))
            horizontal.adapter = cards
            title.setOnFocusChangeListener { _, focused ->
                if (focused && (pending == null || pending?.first == rowId)) { epoch++; pending = null; selected(rowId, "") }
            }
            title.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { adjacent(rowId, 1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { adjacent(rowId, -1); true }
                    else -> false
                }
            }
            }
        }
        fun bind(row: NativeDiscoveryRow) {
            if (hero) { rowId = row.id; return }
            val wasLoadingFocus = title.isFocused
            if (rowId != row.id) { restoreHorizontal = true; horizontalLayoutPending = false; cards.submitList(emptyList()); horizontal.stopScroll(); horizontal.scrollToPosition(0) }
            rowId = row.id; title.text = row.title; title.tag = "${row.id}:loading"
            val pendingContent = showSkeletons && row.loading && row.items.isEmpty()
            skeleton?.tag = "${row.id}:skeleton"
            awaitingSkeletonCards = !pendingContent && row.items.isNotEmpty() && skeleton?.visibility == View.VISIBLE
            if (pendingContent) skeleton?.visibility = View.VISIBLE
            else if (!awaitingSkeletonCards) skeleton?.visibility = View.GONE
            title.contentDescription = if (pendingContent) "${row.title}. Loading titles" else row.title
            enableControlsFocus(loadingEntryRow != row.id)
            if (wasLoadingFocus && row.items.isNotEmpty()) {
                // Disabling the loading heading first lets Android choose a tail
                // button before the first asynchronous card is attached.
                pending = row.id to (this@RailRecycler.position.columns[row.id] ?: row.items.first().id)
                parkingLoadingFocus = true
                this@RailRecycler.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this@RailRecycler.requestFocus()
            }
            title.isFocusable = row.items.isEmpty()
            cards.submitList(railItems[row.id].orEmpty()) {
                if (restoreHorizontal && rowId == row.id) {
                    val saved = this@RailRecycler.position.horizontal[row.id]
                    val index = cards.currentList.indexOfFirst { it.id == saved?.item }
                    if (saved != null && index >= 0) {
                        horizontalLayoutPending = true
                        (horizontal.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, saved.offset)
                    }
                    restoreHorizontal = false
                }
            }
            if (wasLoadingFocus && row.items.isNotEmpty()) reveal(row.id, this@RailRecycler.position.columns[row.id] ?: row.items.first().id)
            if (!warmed) {
                warmed = true
                warmPoolJob = work.launch {
                    delay(500)
                    repeat(16) {
                        while (android.os.SystemClock.uptimeMillis() - lastInputAt < 400) delay(100)
                        if (pool.getRecycledViewCount(0) < 16) pool.putRecycledView(cards.createViewHolder(horizontal, 0))
                        delay(32)
                    }
                    repeat(3) {
                        while (android.os.SystemClock.uptimeMillis() - lastInputAt < 400) delay(100)
                        if (recycledViewPool.getRecycledViewCount(0) < 3)
                            recycledViewPool.putRecycledView(rowAdapter.createViewHolder(this@RailRecycler, 0))
                        delay(32)
                    }
                }
            }
        }
        fun enableControlsFocus(enabled: Boolean) {
            for (index in 0 until horizontal.childCount) (horizontal.getChildViewHolder(horizontal.getChildAt(index)) as? ControlHolder)?.button?.isFocusable = enabled
        }
        private fun rememberHorizontalViewport() {
            if (rowId.isBlank() || restoreHorizontal || horizontalLayoutPending) return
            val layout = horizontal.layoutManager as LinearLayoutManager
            val index = layout.findFirstVisibleItemPosition()
            val item = cards.currentList.getOrNull(index) ?: return
            val view = layout.findViewByPosition(index) ?: return
            this@RailRecycler.position.horizontal.getOrPut(rowId) { NativeRowViewport(item.id, 0) }.apply {
                this.item = item.id; offset = view.left - horizontal.paddingLeft
            }
        }
        fun refreshTileLayers() {
            for (index in 0 until horizontal.childCount) (horizontal.getChildViewHolder(horizontal.getChildAt(index)) as? CardHolder)?.cacheLayer(tileCache.enabled)
        }
        fun trimTileCaches() { horizontal.setItemViewCacheSize(0); stopMotion() }
        fun cancelRemoteInput() {
            for (index in 0 until horizontal.childCount) (horizontal.getChildViewHolder(horizontal.getChildAt(index)) as? CardHolder)?.cancelRemoteInput()
        }
        fun stopMotion() {
            cancelRemoteInput()
            if (onHeroActivity != null) { scrollingChildren.remove(horizontal); reportHeroActivity() }
            for (index in 0 until horizontal.childCount) horizontal.getChildAt(index).apply {
                animate().cancel(); scaleX = 1f; scaleY = 1f
                setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
        fun focus(id: String, scroll: Boolean = true, fromTouch: Boolean = false): Boolean {
            if (hero) return runCatching { heroFocusEntry(id) }.getOrDefault(false)
            if (restoreHorizontal || horizontalLayoutPending) return false
            if (cards.currentList.isEmpty()) {
                if (models[rowId]?.items?.isNotEmpty() == true) return false
                return title.requestFocus().also { focused ->
                if (focused) itemView.requestRectangleOnScreen(Rect(0, 0, itemView.width, itemView.height), false)
                }
            }
            val index = cards.currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
            val holder = horizontal.findViewHolderForAdapterPosition(index)
            if (holder == null) { if (scroll) horizontal.scrollToPosition(index); return false }
            if (pending?.first == rowId) pending = rowId to cards.currentList[index].id
            // A holder can be attached but only a few pixels visible after a fast
            // reversal. Resolve its horizontal visibility locally before focus;
            // a nested requestRectangleOnScreen alone can be rejected mid-reveal.
            horizontal.stopScroll()
            val card = holder.itemView
            val start = horizontal.paddingLeft + dp(3)
            val end = horizontal.width - horizontal.paddingRight - dp(3)
            val dx = when {
                card.left < start -> card.left - start
                card.right > end -> card.right - end
                else -> 0
            }
            if (dx != 0 && horizontal.canScrollHorizontally(if (dx > 0) 1 else -1)) horizontal.scrollBy(dx, 0)
            val target = (holder as? ControlHolder)?.button ?: holder.itemView
            val releaseParking = parkingLoadingFocus && loadingEntryRow == rowId
            if (releaseParking) { parkingLoadingFocus = false; this@RailRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
            if (fromTouch) target.isFocusableInTouchMode = true
            val focused = target.requestFocus()
            if (!focused && releaseParking) { parkingLoadingFocus = true; this@RailRecycler.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
            if (fromTouch && !focused) target.isFocusableInTouchMode = false
            // Horizontal visibility is already resolved above. Reveal the whole
            // category directly in the vertical owner, including its heading and
            // focus padding. A card's nested rectangle can leave the first rail
            // clipped beneath a tall hero after a vertical reversal.
            if (focused) itemView.requestRectangleOnScreen(
                Rect(0, 0, itemView.width, itemView.height), false)
            return focused
        }
    }
    /** Compact, image-free controls recycle with the rail after its last media. */
    private inner class ControlHolder : ViewHolder(FrameLayout(context).apply {
        layoutParams = LayoutParams(dp(88), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6) }
    }) {
        private var row = ""
        private var identity = ""
        val button = TextView(context).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; setTextColor(Color.WHITE)
            isFocusable = true; isSoundEffectsEnabled = false
            setOnClickListener {
                selected(row, identity)
                if (identity == ROW_ALL) models[row]?.viewAll?.invoke() else models[row]?.action?.invoke()
            }
            setOnFocusChangeListener { _, focused ->
                val valid = pending == null || pending == (row to identity)
                frame(focused && valid)
                if (focused && valid) { epoch++; pending = null; selected(row, identity) }
                if (!focused) isFocusableInTouchMode = false
            }
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { horizontal(row, identity, 1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { horizontal(row, identity, -1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { adjacent(row, -1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { adjacent(row, 1); true }
                    else -> false
                }
            }
        }
        init { (itemView as FrameLayout).addView(button, FrameLayout.LayoutParams(dp(76), dp(40), Gravity.CENTER)) }
        private fun frame(focused: Boolean) {
            button.background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat(); setColor(if (focused) Color.rgb(229,9,37) else Color.rgb(29,33,42))
                setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.rgb(55,61,73))
            }
        }
        fun bind(rowId: String, item: NativeDiscoveryItem) {
            row = rowId; identity = item.id
            val all = identity == ROW_ALL
            button.text = item.title; button.textSize = if (all) 12f else 20f
            button.tag = "$row:${if (all) "all" else "action"}"
            button.contentDescription = if (all) "View all ${models[row]?.title.orEmpty()}" else models[row]?.actionLabel
            button.isFocusable = loadingEntryRow != row
            itemView.layoutParams = itemView.layoutParams.apply { width = dp(if (all) 88 else 52) }
            button.layoutParams = button.layoutParams.apply { width = dp(if (all) 76 else 40) }
            frame(button.isFocused)
        }
    }
    private inner class CardHolder : ViewHolder(FrameLayout(context).apply {
        isFocusable = true; isFocusableInTouchMode = false
        layoutParams = LayoutParams(if (polishedCaptions) px(posterGeometry.width) else dp(104), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) }
        setPadding(dp(4), dp(4), dp(4), dp(4)); isSoundEffectsEnabled = false
    }) {
        private var row = ""; private var identity = ""; private var imageJob: Job? = null
        private var artwork: String? = null
        private val artworkPlaceholder = if (showSkeletons) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF293342.toInt(), 0xFF171D27.toInt())) else null
        private val activation = NativeTileActivation()
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(229,9,37))
        }
        private val title = TextView(context).apply {
            textSize = if (polishedCaptions) 10f * posterGeometry.textScale else if (homeTileProportions) 8.7f else 12f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            minLines = if (polishedCaptions) 1 else 2; maxLines = if (polishedCaptions) 6 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val subtitle = TextView(context).apply {
            textSize = if (polishedCaptions) 8.5f * posterGeometry.textScale else if (homeTileProportions) 7.4f else 10f
            setTextColor(Color.LTGRAY); maxLines = if (polishedCaptions) 4 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val source = TextView(context).apply {
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            setPadding(dp(5), dp(3), dp(5), dp(3)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            // Enable only attached foreground tiles; recycled/pooled cards retain no layer.
            if (!containedCaptions) (LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }).also { column ->
                (itemView as FrameLayout).addView(column, FrameLayout.LayoutParams(-1, -1))
                column.apply {
                addView(image, LinearLayout.LayoutParams(-1, dp(147)))
                addView(progress, LinearLayout.LayoutParams(-1, dp(3))); addView(title); addView(subtitle)
                }
            } else (itemView as FrameLayout).apply {
                val poster = FrameLayout(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(Color.rgb(21,26,34)) }
                    clipToOutline = true
                }
                addView(poster, FrameLayout.LayoutParams(-1, -1))
                poster.addView(image, FrameLayout.LayoutParams(-1, -1))
                poster.addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, 0x00000000, 0x8C000000.toInt(), 0xF5000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
                val caption = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(6), dp(3), dp(7))
                    addView(title); addView(subtitle)
                }
                poster.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(3) })
                poster.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
                if (polishedCaptions) poster.addView(source, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(6); marginStart = dp(6) })
            }
            itemView.setOnClickListener { selected(row, identity); models[row]?.items?.firstOrNull { it.id == identity }?.onClick?.invoke() }
            itemView.setOnLongClickListener {
                selected(row, identity)
                models[row]?.items?.firstOrNull { it.id == identity }?.onLongClick?.let {
                    val location = IntArray(2); itemView.getLocationInWindow(location)
                    onPopupAnchor(androidx.compose.ui.unit.IntRect(location[0], location[1], location[0] + itemView.width, location[1] + itemView.height))
                    it(); true
                } ?: false
            }
            itemView.setOnFocusChangeListener { _, focused ->
                if (!focused) { activation.cancel(); itemView.isFocusableInTouchMode = false }
                frame(focused); itemView.animate().cancel()
                if (motion && animateFocus && !pressure) {
                    val animation = itemView.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(320)
                    if (!tileCache.enabled) animation.withLayer()
                    animation.start()
                }
                else { itemView.scaleX = 1f; itemView.scaleY = 1f }
                if (focused && (pending == null || pending == (row to identity))) {
                    epoch++; pending = null; selected(row, identity)
                    if (loadingEntryRow == row) {
                        loadingEntryRow = null
                        (findViewHolderForAdapterPosition(rowAdapter.currentList.indexOfFirst { it.id == row }) as? RowHolder)?.enableControlsFocus(true)
                    }
                }
            }
            itemView.setOnKeyListener { _, key, e ->
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    when (e.action) {
                        KeyEvent.ACTION_DOWN -> activation.down(key, e.eventTime, e.isLongPress)
                        KeyEvent.ACTION_UP -> when (activation.up(key, e.eventTime, e.isCanceled, ViewConfiguration.getLongPressTimeout().toLong())) {
                            NativeTileActivation.Action.CLICK -> itemView.performClick()
                            NativeTileActivation.Action.LONG_CLICK -> if (!itemView.performLongClick()) itemView.performClick()
                            NativeTileActivation.Action.NONE -> Unit
                        }
                    }
                    true
                } else if (e.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_MENU -> focusRowAction(row)
                    KeyEvent.KEYCODE_DPAD_UP -> { adjacent(row, -1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { adjacent(row, 1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { horizontal(row, identity, -1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { horizontal(row, identity, 1); true }
                    else -> false
                }
            }
        }
        private fun frame(focused: Boolean) {
            itemView.elevation = if (cardShadows && focused) dp(4).toFloat() else 0f
            itemView.background = GradientDrawable().apply {
                cornerRadius = dp(if (cardShadows) 10 else 7).toFloat(); setColor(Color.rgb(21,26,34))
                setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else if (cardShadows) Color.rgb(43,48,58) else Color.rgb(49,57,70))
            }
        }
        fun cancelRemoteInput() { activation.cancel() }
        fun cacheLayer(enabled: Boolean) {
            if (!enabled) {
                itemView.animate().cancel()
                if (!itemView.isFocused) { itemView.scaleX = 1f; itemView.scaleY = 1f }
            }
            val type = if (enabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
            if (itemView.layerType != type) itemView.setLayerType(type, null)
        }
        fun cancel() { activation.cancel(); imageJob?.cancel(); imageJob = null; cacheLayer(false); image.setImageDrawable(null); image.background = null; row = ""; identity = ""; artwork = null }
        fun bind(rowId: String, item: NativeDiscoveryItem) {
            val replacement = row != rowId || identity != item.id || artwork != item.artwork
            if (replacement) { cancel(); itemView.scaleX = 1f; itemView.scaleY = 1f }
            row = rowId; identity = item.id; artwork = item.artwork; itemView.tag = "$row:$identity"
            frame(itemView.isFocused)
            title.text = item.title
            subtitle.text = if (polishedCaptions) item.subtitle else listOfNotNull(item.source?.label, item.subtitle.takeIf(String::isNotBlank)).joinToString(" · ")
            subtitle.visibility = if (subtitle.text.isBlank()) View.GONE else View.VISIBLE
            source.text = item.source?.label; source.visibility = if (item.source == null) View.GONE else View.VISIBLE
            item.source?.let { tag ->
                source.setTextColor(tag.foreground.toArgb())
                source.background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(tag.background.toArgb()); setStroke(dp(1), tag.foreground.copy(alpha = .45f).toArgb()) }
            }
            itemView.contentDescription = listOfNotNull(item.title, item.source?.label, item.subtitle.takeIf(String::isNotBlank)).joinToString(", ")
            progress.visibility = if (item.progress == null) View.INVISIBLE else View.VISIBLE
            progress.progress = ((item.progress ?: 0f).coerceIn(0f,1f) * 1000).toInt()
            if (!replacement) return
            image.background = artworkPlaceholder.takeIf { showSkeletons && !item.artwork.isNullOrBlank() }
            item.artwork?.let { url -> imageJob = work.launch { images.withPermit {
                try {
                    val w = if (polishedCaptions) px(posterGeometry.width - 8f) else dp(104)
                    val h = if (polishedCaptions) px(posterGeometry.posterHeight) else dp(147)
                    val result = withContext(Dispatchers.IO) { context.imageLoader.execute(ImageRequest.Builder(context).data(thumbnailArtworkUrl(url, w)).size(w,h).build()) }
                    ensureActive(); image.background = null
                    if (result is SuccessResult) image.setImageDrawable(result.image.asDrawable(resources))
                } catch (c: CancellationException) { throw c } catch (_: Exception) {
                    if (isActive && row == rowId && identity == item.id && artwork == url) image.background = null
                }
            } } }
        }
    }
    fun release() { epoch++; work.cancel(); adapter = null; pool.clear(); recycledViewPool.clear() }
}
