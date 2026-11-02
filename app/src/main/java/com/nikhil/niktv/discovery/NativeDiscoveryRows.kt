package com.nikhil.niktv.discovery

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nikhil.niktv.data.thumbnailArtworkUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class NativeDiscoveryItem(
    val id: String, val title: String, val subtitle: String = "", val artwork: String? = null,
    val onClick: () -> Unit, val onLongClick: (() -> Unit)? = null, val progress: Float? = null,
)
internal data class NativeDiscoveryRow(val id: String, val title: String, val items: List<NativeDiscoveryItem>)

/** Bounded renderer experiment: native scrolling/focus, shared cached artwork, no provider requests. */
@Composable
internal fun NativeDiscoveryRows(rows: List<NativeDiscoveryRow>, modifier: Modifier = Modifier,
    focusEntry: FocusRequester, up: () -> Unit, animateFocus: Boolean = false) {
    val position = rememberSaveable(saver = NativeRowsPosition.Saver) { NativeRowsPosition() }
    val composeHost = LocalView.current
    AndroidView(factory = { context -> NativeDiscoveryScroll(context).apply {
        restoredFocus = position.focus; restoredTop = position.top
    } }, modifier = modifier.focusRequester(focusEntry), onReset = null,
        onRelease = { it.dispose() }, update = { native ->
            native.onUp = { native.releaseFocusToCompose(composeHost, up) }
            native.animateFocus = animateFocus
            native.onPosition = { focus, top -> position.focus = focus; position.top = top }
            native.render(rows)
        })
}

/** Restoration bookkeeping does not invalidate Compose while the native view scrolls. */
private class NativeRowsPosition(var focus: String? = null, var top: Int = 0) {
    companion object {
        val Saver = listSaver<NativeRowsPosition, Any>(
            save = { listOf(it.focus.orEmpty(), it.top) },
            restore = { NativeRowsPosition((it[0] as String).ifBlank { null }, it[1] as Int) })
    }
}

private class NativeDiscoveryScroll(context: Context) : ScrollView(context) {
    var onUp: () -> Unit = {}
    var onPosition: (String?, Int) -> Unit = { _, _ -> }
    var restoredFocus: String? = null
    var restoredTop = 0
    private var currentFocusId: String? = null
    private var initialFocusPending = true
    private var touchModeObserver: ViewTreeObserver? = null
    private val touchModeListener = ViewTreeObserver.OnTouchModeChangeListener { touchMode ->
        if (!touchMode) post {
            // Touch can leave the Compose AndroidView bridge focused while no card
            // owns platform focus. Restore only inside this still-focused native island.
            if (isAttachedToWindow && hasWindowFocus() && hasFocus() && findFocus()?.tag !is String) {
                val saved = currentFocusId ?: restoredFocus
                (cardRows.flatten().firstOrNull { it.tag == saved } ?: cardRows.firstOrNull()?.firstOrNull())?.requestFocus()
            }
        }
    }
    private var currentRows = emptyList<NativeDiscoveryRow>()
    private var signature: List<String> = emptyList()
    private var renderRevision = 0L
    private var parkingRebuild = false
    private var handingOffToCompose = false
    private val cardRows = mutableListOf<List<View>>()
    private val motionAllowed = Settings.Global.getFloat(context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var animateFocus: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) cardRows.flatten().forEach { card ->
                card.animate().cancel(); card.scaleX = 1f; card.scaleY = 1f
            }
        }
    private val lastColumn = mutableMapOf<String, Int>()
    private val images = Semaphore(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var imageJob: Job? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    init {
        isFillViewport = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isSmoothScrollingEnabled = true
        setBackgroundColor(Color.rgb(9, 11, 16))
        // AndroidView does not clip native drawing to its Compose layout bounds.
        // Keep scrolled cards inside this viewport, below the separate header.
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        setPadding(dp(18), dp(10), dp(18), dp(24))
        clipToPadding = false
        setOnScrollChangeListener { _, _, y, _, _ -> onPosition(currentFocusId ?: restoredFocus, y) }
    }

    fun releaseFocusToCompose(composeHost: View, focusHeader: () -> Unit) {
        val previousPolicy = descendantFocusability
        val previouslyFocusable = isFocusable
        handingOffToCompose = true
        try {
            // Compose focus and Android's focused View are distinct. Release both
            // native candidates before requesting the header's Compose focus target.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = false
            clearFocus()
            composeHost.requestFocus()
            focusHeader()
        } finally {
            isFocusable = previouslyFocusable
            descendantFocusability = previousPolicy
            handingOffToCompose = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        touchModeObserver = viewTreeObserver.also { it.addOnTouchModeChangeListener(touchModeListener) }
    }

    override fun onDetachedFromWindow() {
        touchModeObserver?.takeIf { it.isAlive }?.removeOnTouchModeChangeListener(touchModeListener)
        touchModeObserver = null
        super.onDetachedFromWindow()
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val saved = currentFocusId ?: restoredFocus
        val target = cardRows.flatten().firstOrNull { it.tag == saved }
            ?: if (initialFocusPending && restoredFocus != null) null else cardRows.firstOrNull()?.firstOrNull()
        if (target == null && initialFocusPending && restoredFocus != null) return false
        return target?.requestFocus(direction, previouslyFocusedRect)
            ?: super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && !parkingRebuild && !handingOffToCompose) post {
            // A queued bridge-entry request must not replace a descendant already focused.
            if (!isAttachedToWindow || !hasWindowFocus() || !isFocused) return@post
            val saved = currentFocusId ?: restoredFocus
            val target = cardRows.flatten().firstOrNull { it.tag == saved } ?: cardRows.firstOrNull()?.firstOrNull()
            target?.requestFocus()
        }
    }

    fun render(incoming: List<NativeDiscoveryRow>) {
        currentRows = incoming.take(4).map { it.copy(items = it.items.take(8)) }
        val nextSignature = currentRows.flatMap { row -> listOf(row.id, row.title) + row.items.flatMap { listOf(it.id, it.title, it.subtitle, it.artwork.orEmpty()) } }
        if (signature == nextSignature) return
        signature = nextSignature
        val revision = ++renderRevision
        val hadFocus = hasFocus()
        val top = if (childCount == 0) restoredTop else scrollY
        imageJob?.cancel()
        val batch = SupervisorJob(scope.coroutineContext[Job])
        imageJob = batch
        val imageScope = CoroutineScope(scope.coroutineContext + batch)
        cardRows.flatten().forEach { it.animate().cancel() }
        if (hadFocus) {
            // Keep native focus ownership while replacing its focused descendant.
            // Do not queue a competing entry restoration from this temporary anchor.
            parkingRebuild = true
            val previousPolicy = descendantFocusability
            try {
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                requestFocus()
            } finally { descendantFocusability = previousPolicy; parkingRebuild = false }
        }
        removeAllViews(); cardRows.clear()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        currentRows.forEachIndexed { rowIndex, row ->
            content.addView(TextView(context).apply {
                text = row.title; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), dp(5), 0, dp(6)); isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val horizontal = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; isSmoothScrollingEnabled = true
                clipToPadding = false; clipChildren = false; isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(3), dp(4), dp(5), dp(8))
            }
            horizontal.addView(line, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            content.addView(horizontal, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val cards = row.items.mapIndexed { column, item ->
                val identity = "${row.id}:${item.id}"
                val card = LinearLayout(context).apply {
                    // Touch should activate immediately; remote focus is still available outside touch mode.
                    orientation = LinearLayout.VERTICAL; isFocusable = true; isFocusableInTouchMode = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    ViewCompat.setScreenReaderFocusable(this, true)
                    isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false
                    tag = identity; contentDescription = listOf(item.title, item.subtitle).filter(String::isNotBlank).joinToString(", ")
                    setPadding(dp(3), dp(3), dp(3), dp(3)); background = frame(false)
                    fun rememberSelection() {
                        initialFocusPending = false
                        currentFocusId = identity; lastColumn[row.id] = column
                        onPosition(identity, scrollY)
                        // Keep a native touch-focus anchor while cards remain one-tap
                        // clickable. Leaving touch mode can then restore the selected card.
                        if (isInTouchMode) this@NativeDiscoveryScroll.requestFocus()
                    }
                    setOnClickListener { rememberSelection(); currentRows.getOrNull(rowIndex)?.items?.getOrNull(column)?.onClick?.invoke() }
                    setOnLongClickListener {
                        rememberSelection()
                        val action = currentRows.getOrNull(rowIndex)?.items?.getOrNull(column)?.onLongClick
                        action?.invoke(); action != null
                    }
                    setOnFocusChangeListener { _, focused ->
                        background = frame(focused)
                        animate().cancel()
                        if (animateFocus && motionAllowed) {
                            // Isolated experiment: cheap cached native layer, no layout or shadow animation.
                            val target = if (focused) 1.025f else 1f
                            animate().scaleX(target).scaleY(target).setDuration(320L).withLayer().start()
                        } else { scaleX = 1f; scaleY = 1f }
                        if (focused && !handingOffToCompose && !(initialFocusPending && restoredFocus != null && identity != restoredFocus)) {
                            // Platform attachment may provisionally focus the first child.
                            // It must not erase a saved return target farther down the feed.
                            initialFocusPending = false
                            currentFocusId = identity; lastColumn[row.id] = column
                            onPosition(identity, scrollY)
                            // ScrollView/HorizontalScrollView already reveal focused descendants.
                            // A second posted rectangle request would retarget their native scrolling.
                        }
                    }
                    setOnKeyListener { _, key, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) initialFocusPending = false
                        if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (rowIndex == 0) onUp() else focusRow(rowIndex - 1, column)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { focusRow(rowIndex + 1, column); true }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (column > 0) cardRows.getOrNull(rowIndex)?.getOrNull(column - 1)?.requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                cardRows.getOrNull(rowIndex)?.getOrNull(column + 1)?.requestFocus(); true
                            }
                            else -> false
                        }
                    }
                }
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(28, 34, 44))
                    isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                card.addView(image, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(147)))
                card.addView(TextView(context).apply {
                    text = item.title; textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                    maxLines = 2; minLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(3), dp(5), dp(3), 0); isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                card.addView(TextView(context).apply {
                    text = item.subtitle; textSize = 10f; setTextColor(Color.LTGRAY)
                    maxLines = 1; minLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(3), dp(2), dp(3), dp(4)); isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                line.addView(card, LinearLayout.LayoutParams(dp(104), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
                item.artwork?.let { artwork -> imageScope.launch {
                    images.withPermit {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                context.imageLoader.execute(ImageRequest.Builder(context)
                                    .data(thumbnailArtworkUrl(artwork, dp(104))).size(dp(104), dp(147)).build())
                            }
                            ensureActive()
                            if (result is SuccessResult) image.setImageDrawable(result.image.asDrawable(resources))
                        } catch (cancel: CancellationException) { throw cancel }
                          catch (_: Exception) { /* Missing artwork keeps a stable placeholder. */ }
                    }
                } }
                card
            }
            cardRows += cards
        }
        post {
            if (!isAttachedToWindow || revision != renderRevision) return@post
            // Focus may already have revealed its tile before this posted callback.
            // An older saved top must not move that focused tile back offscreen.
            if (findFocus()?.tag !is String) scrollTo(0, top)
            // Header/popup focus changes after the rebuild always win over restoration.
            if ((hadFocus || initialFocusPending) && hasWindowFocus() && hasFocus()) {
                val saved = if (initialFocusPending) restoredFocus else currentFocusId ?: restoredFocus
                val target = cardRows.flatten().firstOrNull { it.tag == saved } ?: cardRows.firstOrNull()?.firstOrNull()
                if (target?.tag != restoredFocus) initialFocusPending = false
                target?.requestFocus()
            }
        }
    }

    private fun focusRow(row: Int, fromColumn: Int) {
        val cards = cardRows.getOrNull(row).orEmpty()
        val column = (currentRows.getOrNull(row)?.id?.let(lastColumn::get) ?: fromColumn).coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        cards.getOrNull(column)?.requestFocus()
    }
    private fun frame(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(7).toFloat()
        setColor(if (focused) Color.rgb(42, 50, 64) else Color.rgb(21, 26, 34))
        setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.rgb(49, 57, 70))
    }
    fun dispose() { cardRows.flatten().forEach { it.animate().cancel() }; scope.cancel(); imageJob?.cancel() }
}
