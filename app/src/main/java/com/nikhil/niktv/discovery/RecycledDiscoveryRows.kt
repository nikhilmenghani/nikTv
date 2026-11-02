package com.nikhil.niktv.discovery

import android.content.Context
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

private class RecycledPosition(var row: String = "", var item: String = "") {
    val columns = mutableMapOf<String, String>()
    companion object {
        val Saver = listSaver<RecycledPosition, String>(
            save = { listOf(it.row, it.item) + it.columns.flatMap { e -> listOf(e.key, e.value) } },
            restore = { values -> RecycledPosition(values[0], values[1]).also { p ->
                values.drop(2).chunked(2).forEach { p.columns[it[0]] = it[1] }
            } })
    }
}

/** Full feed, but only viewport holders and a small shared card pool are materialized. */
@Composable
internal fun RecycledDiscoveryRows(rows: List<NativeDiscoveryRow>, modifier: Modifier,
    focusEntry: FocusRequester, up: () -> Unit, visible: (String) -> Unit = {}, animateFocus: Boolean = true,
    containedCaptions: Boolean = false, cardShadows: Boolean = false) {
    val position = rememberSaveable(saver = RecycledPosition.Saver) { RecycledPosition() }
    val host = LocalView.current
    key(containedCaptions, cardShadows) {
    AndroidView(factory = { RailRecycler(it, position, containedCaptions, cardShadows) }, modifier = modifier.focusRequester(focusEntry),
        onRelease = { it.release() }, update = { view ->
            view.animateFocus = animateFocus
            view.onUp = { view.leave(host, up) }; view.onVisible = visible; view.update(rows)
        })
    }
}

private fun NativeDiscoveryItem.samePresentation(other: NativeDiscoveryItem) =
    id == other.id && title == other.title && subtitle == other.subtitle &&
        artwork == other.artwork && progress == other.progress
private val rowDiff = object : DiffUtil.ItemCallback<NativeDiscoveryRow>() {
    override fun areItemsTheSame(a: NativeDiscoveryRow, b: NativeDiscoveryRow) = a.id == b.id
    override fun areContentsTheSame(a: NativeDiscoveryRow, b: NativeDiscoveryRow) =
        a.title == b.title && a.items.size == b.items.size &&
            a.items.indices.all { a.items[it].samePresentation(b.items[it]) }
}
private val cardDiff = object : DiffUtil.ItemCallback<NativeDiscoveryItem>() {
    override fun areItemsTheSame(a: NativeDiscoveryItem, b: NativeDiscoveryItem) = a.id == b.id
    override fun areContentsTheSame(a: NativeDiscoveryItem, b: NativeDiscoveryItem) = a.samePresentation(b)
}

private class RailRecycler(context: Context, private val position: RecycledPosition,
    private val containedCaptions: Boolean, private val cardShadows: Boolean) : RecyclerView(context) {
    var animateFocus = true
        set(value) {
            if (field && !value) findFocus()?.let { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f }
            field = value
        }
    var onUp: () -> Unit = {}
    var onVisible: (String) -> Unit = {}
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val images = Semaphore(2)
    private var visibleJob: Job? = null
    private var warmed = false
    private var lastInputAt = 0L
    private val pool = RecycledViewPool().apply { setMaxRecycledViews(0, 16) }
    private var models = emptyMap<String, NativeDiscoveryRow>()
    private var submitted: List<NativeDiscoveryRow>? = null
    private var pending: Pair<String, String>? = null
    private var epoch = 0L
    private val stableIds = mutableMapOf<String, Long>()
    private fun stableId(key: String) = stableIds.getOrPut(key) { stableIds.size.toLong() }
    private val vertical = LinearLayoutManager(context)
    private val motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private val rowAdapter = object : ListAdapter<NativeDiscoveryRow, RowHolder>(rowDiff) {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = stableId("row:${getItem(position).id}")
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = RowHolder()
        override fun onBindViewHolder(holder: RowHolder, index: Int) { holder.bind(getItem(index)) }
        override fun onViewRecycled(holder: RowHolder) { holder.cards.submitList(emptyList()) }
        override fun onViewDetachedFromWindow(holder: RowHolder) { holder.stopMotion() }
        override fun onFailedToRecycleView(holder: RowHolder): Boolean { holder.stopMotion(); return true }
        override fun onViewAttachedToWindow(holder: RowHolder) {
            val row = holder.rowId
            visibleJob?.cancel()
            visibleJob = work.launch { delay(400); if (holder.itemView.isAttachedToWindow && holder.rowId == row) onVisible(row) }
        }
        override fun onCurrentListChanged(previous: MutableList<NativeDiscoveryRow>, current: MutableList<NativeDiscoveryRow>) {
            if (hasFocus() && findFocus()?.tag !is String) {
                val row = current.firstOrNull { it.id == position.row && it.items.isNotEmpty() } ?: current.firstOrNull { it.items.isNotEmpty() }
                val target = pending ?: row?.let { it.id to (position.columns[it.id] ?: it.items.first().id) }
                target?.let { reveal(it.first, it.second) }
            }
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    init {
        layoutManager = vertical; adapter = rowAdapter; itemAnimator = null
        recycledViewPool.setMaxRecycledViews(0, 4)
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
        submitted = rows
        models = rows.associateBy { it.id }
        rowAdapter.submitList(rows.toList())
    }
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val row = models[position.row]?.takeIf { it.items.isNotEmpty() } ?: rowAdapter.currentList.firstOrNull { it.items.isNotEmpty() }
        val target = pending ?: row?.let { it.id to (position.columns[it.id] ?: it.items.first().id) }
        target?.let {
            val index = rowAdapter.currentList.indexOfFirst { row -> row.id == it.first }
            val holder = findViewHolderForAdapterPosition(index) as? RowHolder
            // Returning false after a successful child request would focus the
            // outer anchor again and erase the just-restored card's focus.
            if (holder?.focus(it.second, false) == true) { pending = null; return true }
            reveal(it.first, it.second)
        }
        return false // Outer native anchor owns entry until the recycled holder is laid out.
    }
    private fun reveal(row: String, item: String) {
        val index = rowAdapter.currentList.indexOfFirst { it.id == row }
        if (index < 0) return
        // A previous row's smooth reveal must not keep moving after a reversal.
        stopScroll()
        pending = row to item; val ticket = ++epoch
        val attached = findViewHolderForAdapterPosition(index) as? RowHolder
        if (attached?.focus(item, false) == true) { pending = null; return }
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
            else pending = null
        } }
        retry(0)
    }
    fun leave(host: View, action: () -> Unit) {
        epoch++; pending = null
        val policy = descendantFocusability
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; isFocusable = false
        clearFocus(); host.requestFocus(); action()
        isFocusable = true; descendantFocusability = policy
    }
    private fun adjacent(row: String, step: Int) {
        val list = rowAdapter.currentList
        var index = list.indexOfFirst { it.id == (pending?.first ?: row) } + step
        if (index < 0) onUp()
        else list.getOrNull(index)?.let { reveal(it.id, position.columns[it.id] ?: it.items.firstOrNull()?.id.orEmpty()) }
    }
    private fun selected(row: String, item: String) { position.row = row; position.item = item; position.columns[row] = item }
    private fun horizontal(row: String, item: String, direction: Int) {
        val items = models[row]?.items.orEmpty()
        items.getOrNull(items.indexOfFirst { it.id == item } + direction)?.let { reveal(row, it.id) }
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
    private inner class RowHolder : ViewHolder(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }) {
        var rowId = ""
        private val title = TextView(context).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(3), dp(5), 0, dp(6)); ViewCompat.setAccessibilityHeading(this, true)
        }
        private val horizontal = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, HORIZONTAL, false).apply {
                // Nested RV defaults prepare only two cards. Prepare one visible rail
                // within GapWorker's frame deadline instead of inflating it on Down.
                initialPrefetchItemCount = 9
            }
            setRecycledViewPool(pool); itemAnimator = null; setItemViewCacheSize(1)
            setPadding(dp(4), dp(5), dp(4), dp(10)); clipToPadding = false; isFocusable = false
        }
        val cards = object : ListAdapter<NativeDiscoveryItem, CardHolder>(cardDiff) {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = stableId("card:$rowId:${getItem(position).id}")
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = CardHolder()
            override fun onBindViewHolder(holder: CardHolder, index: Int) { holder.bind(rowId, getItem(index)) }
            override fun onViewRecycled(holder: CardHolder) { holder.cancel() }
            override fun onFailedToRecycleView(holder: CardHolder): Boolean { holder.cancel(); return true }
        }
        init {
            (itemView as LinearLayout).addView(title)
            (itemView as LinearLayout).addView(horizontal, LinearLayout.LayoutParams(-1, dp(172 + (48 * resources.configuration.fontScale).toInt())))
            horizontal.adapter = cards
            title.setOnFocusChangeListener { _, focused -> if (focused) { epoch++; pending = null; selected(rowId, "") } }
            title.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { adjacent(rowId, 1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { adjacent(rowId, -1); true }
                    else -> false
                }
            }
        }
        fun bind(row: NativeDiscoveryRow) {
            val wasLoadingFocus = title.isFocused
            if (rowId != row.id) { cards.submitList(emptyList()); horizontal.stopScroll(); horizontal.scrollToPosition(0) }
            rowId = row.id; title.text = row.title; title.tag = "${row.id}:loading"
            title.isFocusable = row.items.isEmpty(); cards.submitList(row.items.toList())
            if (wasLoadingFocus && row.items.isNotEmpty()) reveal(row.id, row.items.first().id)
            if (!warmed) {
                warmed = true
                work.launch {
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
        fun stopMotion() {
            for (index in 0 until horizontal.childCount) horizontal.getChildAt(index).apply {
                animate().cancel(); scaleX = 1f; scaleY = 1f
            }
        }
        fun focus(id: String, scroll: Boolean = true): Boolean {
            if (cards.currentList.isEmpty()) return title.requestFocus()
            val index = cards.currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
            val holder = horizontal.findViewHolderForAdapterPosition(index) as? CardHolder
            if (holder == null) { if (scroll) horizontal.scrollToPosition(index); return false }
            if (pending?.first == rowId) pending = rowId to cards.currentList[index].id
            val focused = holder.itemView.requestFocus()
            if (focused) holder.itemView.requestRectangleOnScreen(
                Rect(0, 0, holder.itemView.width, holder.itemView.height), false)
            return focused
        }
    }
    private inner class CardHolder : ViewHolder(FrameLayout(context).apply {
        isFocusable = true; isFocusableInTouchMode = false
        layoutParams = LayoutParams(dp(104), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) }
        setPadding(dp(4), dp(4), dp(4), dp(4)); isSoundEffectsEnabled = false
    }) {
        private var row = ""; private var identity = ""; private var imageJob: Job? = null
        private var artwork: String? = null
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(229,9,37))
        }
        private val title = TextView(context).apply { textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; minLines = 2; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val subtitle = TextView(context).apply { textSize = 10f; setTextColor(Color.LTGRAY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        init {
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
            }
            itemView.setOnClickListener { selected(row, identity); models[row]?.items?.firstOrNull { it.id == identity }?.onClick?.invoke() }
            itemView.setOnLongClickListener { selected(row, identity); models[row]?.items?.firstOrNull { it.id == identity }?.onLongClick?.let { it(); true } ?: false }
            itemView.setOnFocusChangeListener { _, focused ->
                frame(focused); itemView.animate().cancel()
                if (motion && animateFocus) itemView.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(320).withLayer().start()
                else { itemView.scaleX = 1f; itemView.scaleY = 1f }
                if (focused && (pending == null || pending == (row to identity))) { epoch++; pending = null; selected(row, identity) }
            }
            itemView.setOnKeyListener { _, key, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false else when (key) {
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
        fun cancel() { imageJob?.cancel(); imageJob = null; itemView.animate().cancel(); image.setImageDrawable(null); row = ""; identity = ""; artwork = null }
        fun bind(rowId: String, item: NativeDiscoveryItem) {
            val replacement = row != rowId || identity != item.id || artwork != item.artwork
            if (replacement) { cancel(); itemView.scaleX = 1f; itemView.scaleY = 1f }
            row = rowId; identity = item.id; artwork = item.artwork; itemView.tag = "$row:$identity"
            frame(itemView.isFocused)
            title.text = item.title; subtitle.text = item.subtitle; itemView.contentDescription = "${item.title}, ${item.subtitle}"
            progress.visibility = if (item.progress == null) View.INVISIBLE else View.VISIBLE
            progress.progress = ((item.progress ?: 0f).coerceIn(0f,1f) * 1000).toInt()
            if (!replacement) return
            item.artwork?.let { url -> imageJob = work.launch { images.withPermit {
                try {
                    val result = withContext(Dispatchers.IO) { context.imageLoader.execute(ImageRequest.Builder(context).data(thumbnailArtworkUrl(url, dp(104))).size(dp(104),dp(147)).build()) }
                    ensureActive(); if (result is SuccessResult) image.setImageDrawable(result.image.asDrawable(resources))
                } catch (c: CancellationException) { throw c } catch (_: Exception) { }
            } } }
        }
    }
    fun release() { epoch++; work.cancel(); adapter = null; pool.clear() }
}
