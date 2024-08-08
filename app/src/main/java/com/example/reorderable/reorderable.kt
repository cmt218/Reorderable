package com.example.reorderable

import android.os.Parcelable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@Composable
fun ReorderLazyColumn(state: LazyListState, modifier: Modifier = Modifier, content: ReorderLazyListScope.() -> Unit) {
    val keyToContentMap by remember(content) { mutableStateOf(assembleKeyToContentMap { content() }) }
    var items by remember { mutableStateOf(keyToContentMap.keys.toList()) }
    val dragDropState = rememberDragDropState(lazyListState = state) { fromIndex, toIndex ->
        items = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }.toImmutableList()
    }

    LazyColumn(
        modifier = modifier.dragContainer(dragDropState),
        state = state,
    ) {
        itemsIndexed(items = items, key = { _, key -> key }) { index, reorderLazyColumnKey ->
            when (reorderLazyColumnKey) {
                is ReorderLazyColumnKey.ReorderKey -> {
                    DraggableItem(dragDropState = dragDropState, index = index) {
                        keyToContentMap[reorderLazyColumnKey]?.invoke(this@itemsIndexed)
                    }
                }

                is ReorderLazyColumnKey.FixedKey -> {
                    keyToContentMap[reorderLazyColumnKey]?.invoke(this@itemsIndexed)
                }
            }
        }
    }
}

@Parcelize
sealed class ReorderLazyColumnKey(val actualKey: String) : Parcelable {
    data class ReorderKey(val key: String) : ReorderLazyColumnKey(key)
    data class FixedKey(val key: String) : ReorderLazyColumnKey(key)
}

fun assembleKeyToContentMap(
    content: ReorderLazyListScope.() -> Unit
): Map<ReorderLazyColumnKey, @Composable LazyItemScope.() -> Unit> {
    val keys = mutableMapOf<ReorderLazyColumnKey, @Composable LazyItemScope.() -> Unit>()

    object : ReorderLazyListScope {
        override fun reorderItem(key: Any?, content: @Composable (LazyItemScope.() -> Unit)) {
            if (key != null && key is String) {
                keys[ReorderLazyColumnKey.ReorderKey(key)] = content
            } else {
                throw IllegalArgumentException("key must not be null and must be a String")
            }
        }

        override fun fixedItem(key: Any?, content: @Composable() (LazyItemScope.() -> Unit)) {
            if (key != null && key is String) {
                keys[ReorderLazyColumnKey.FixedKey(key)] = content
            } else {
                throw IllegalArgumentException("key must not be null and must be a String")
            }
        }
    }.content()

    return keys
}

@Composable
fun DraggableItem(
    dragDropState: DragDropState,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(isDragging: Boolean) -> Unit
) {
    val dragging = index == dragDropState.draggingItemIndex
    val draggingModifier =
        if (dragging) {
            Modifier
                .zIndex(1f)
                .graphicsLayer { translationY = dragDropState.draggingItemOffset }
        } else if (index == dragDropState.previousIndexOfDraggedItem) {
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    translationY = dragDropState.previousItemOffset.value
                }
        } else {
            Modifier
        }
    Column(modifier = modifier.then(draggingModifier)) { content(dragging) }
}

@Composable
fun rememberDragDropState(lazyListState: LazyListState, onMove: (Int, Int) -> Unit): DragDropState {
    val scope = rememberCoroutineScope()
    val state = remember(lazyListState) { DragDropState(state = lazyListState, onMove = onMove, scope = scope) }
    LaunchedEffect(state) {
        while (true) {
            val diff = state.scrollChannel.receive()
            lazyListState.scrollBy(diff)
        }
    }
    return state
}

class DragDropState internal constructor(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (Int, Int) -> Unit
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val scrollChannel = Channel<Float>()

    private var draggingItemDraggedDelta by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableIntStateOf(0)
    internal val draggingItemOffset: Float
        get() = draggingItemLayoutInfo?.let { item ->
            draggingItemInitialOffset + draggingItemDraggedDelta - item.offset
        } ?: 0f

    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    internal var previousIndexOfDraggedItem by mutableStateOf<Int?>(null)
        private set

    internal var previousItemOffset = Animatable(0f)
        private set

    internal fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                offset.y.toInt() in item.offset..(item.offset + item.size) &&
                        item.key is ReorderLazyColumnKey.ReorderKey
            }
            ?.also {
                draggingItemIndex = it.index
                draggingItemInitialOffset = it.offset
            }
    }

    internal fun onDragInterrupted() {
        if (draggingItemIndex != null) {
            previousIndexOfDraggedItem = draggingItemIndex
            val startOffset = draggingItemOffset
            scope.launch {
                previousItemOffset.snapTo(startOffset)
                previousItemOffset.animateTo(
                    0f,
                    spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f)
                )
                previousIndexOfDraggedItem = null
            }
        }
        draggingItemDraggedDelta = 0f
        draggingItemIndex = null
        draggingItemInitialOffset = 0
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset.y

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset + draggingItemOffset
        val endOffset = startOffset + draggingItem.size
        val middleOffset = startOffset + (endOffset - startOffset) / 2f

        val targetItem =
            state.layoutInfo.visibleItemsInfo.find { item ->
                middleOffset.toInt() in item.offset..item.offsetEnd &&
                        draggingItem.index != item.index
            }
        if (targetItem != null) {
            if (
                draggingItem.index == state.firstVisibleItemIndex ||
                targetItem.index == state.firstVisibleItemIndex
            ) {
                scope.launch {
                    state.scrollToItem(
                        state.firstVisibleItemIndex,
                        state.firstVisibleItemScrollOffset
                    )
                }
            }
            onMove.invoke(draggingItem.index, targetItem.index)
            draggingItemIndex = targetItem.index
        } else {
            val overscroll =
                when {
                    draggingItemDraggedDelta > 0 ->
                        (endOffset - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)

                    draggingItemDraggedDelta < 0 ->
                        (startOffset - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)

                    else -> 0f
                }
            if (overscroll != 0f) {
                scrollChannel.trySend(overscroll)
            }
        }
    }

    private val LazyListItemInfo.offsetEnd: Int
        get() = this.offset + this.size
}

fun Modifier.dragContainer(dragDropState: DragDropState): Modifier {
    return then(
        pointerInput(dragDropState) {
            detectDragGesturesAfterLongPress(
                onDrag = { change, offset ->
                    change.consume()
                    dragDropState.onDrag(offset = offset)
                },
                onDragStart = { offset -> dragDropState.onDragStart(offset) },
                onDragEnd = { dragDropState.onDragInterrupted() },
                onDragCancel = { dragDropState.onDragInterrupted() }
            )
        }
    )
}

interface ReorderLazyListScope : LazyListScope {
    fun reorderItem(key: Any?, content: @Composable (LazyItemScope.() -> Unit))

    fun fixedItem(key: Any?, content: @Composable (LazyItemScope.() -> Unit))

    @Deprecated("Use reorderItem or fixedItem in ReorderLazyListScope", level = DeprecationLevel.HIDDEN)
    override fun item(key: Any?, content: @Composable (LazyItemScope.() -> Unit)) {
        throw NotImplementedError("Use reorderItem or fixedItem in ReorderLazyListScope")
    }

    @Deprecated("Use reorderItem or fixedItem in ReorderLazyListScope", level = DeprecationLevel.HIDDEN)
    override fun item(key: Any?, contentType: Any?, content: @Composable (LazyItemScope.() -> Unit)) {
        throw NotImplementedError("Use reorderItem or fixedItem in ReorderLazyListScope")
    }

    @Deprecated("Use reorderItem or fixedItem in ReorderLazyListScope", level = DeprecationLevel.HIDDEN)
    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable (LazyItemScope.(index: Int) -> Unit)
    ) {
        throw NotImplementedError("Use reorderItem or fixedItem in ReorderLazyListScope")
    }

    @Deprecated("Use reorderItem or fixedItem in ReorderLazyListScope", level = DeprecationLevel.HIDDEN)
    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        itemContent: @Composable (LazyItemScope.(index: Int) -> Unit)
    ) {
        throw NotImplementedError("Use reorderItem or fixedItem in ReorderLazyListScope")
    }

    @Deprecated("Use reorderItem or fixedItem in ReorderLazyListScope", level = DeprecationLevel.HIDDEN)
    @ExperimentalFoundationApi
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable (LazyItemScope.() -> Unit)) {
        throw NotImplementedError("Use reorderItem or fixedItem in ReorderLazyListScope")
    }
}
