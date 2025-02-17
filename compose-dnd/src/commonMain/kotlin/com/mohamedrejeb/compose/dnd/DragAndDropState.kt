/*
 * Copyright 2023, Mohamed Ben Rejeb and the Compose Dnd project contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mohamedrejeb.compose.dnd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import com.mohamedrejeb.compose.dnd.drag.DraggableItem
import com.mohamedrejeb.compose.dnd.drag.DraggableItemState
import com.mohamedrejeb.compose.dnd.drag.DraggedItemState
import com.mohamedrejeb.compose.dnd.drop.DropTargetState
import com.mohamedrejeb.compose.dnd.utils.MathUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Remember [DragAndDropState]
 * @param dragAfterLongPress if true, drag will start after long press, otherwise drag will start after simple press
 * This parameter is applied to all [DraggableItem]s. If you want to change it for a specific item, use [DraggableItem] parameter.
 * @param T type of the data that is dragged
 * @return [DragAndDropState]
 * @see DragAndDropState
 */
@Composable
fun <T> rememberDragAndDropState(
    dragAfterLongPress: Boolean = false,
): DragAndDropState<T> {
    return remember {
        DragAndDropState(
            dragAfterLongPress = dragAfterLongPress,
        )
    }
}

/**
 * State of the drag and drop
 * @param dragAfterLongPress if true, drag will start after long press, otherwise drag will start after simple press
 * This parameter is applied to all [DraggableItem]s. If you want to change it for a specific item, use [DraggableItem] parameter.
 * @param T type of the data that is dragged
 */
@Stable
class DragAndDropState<T>(
    internal val dragAfterLongPress: Boolean = false,
) {
    // Drop Target

    /**
     * Map of [DropTargetState] by key
     */
    private val dropTargetMap = mutableMapOf<Any, DropTargetState<T>>()

    /**
     * Key of the [DropTargetState] that is currently hovered
     */
    var hoveredDropTargetKey by mutableStateOf<Any>("")
        internal set

    /**
     * Add or update [DropTargetState] in [dropTargetMap]
     */
    internal fun addDropTarget(dropTargetState: DropTargetState<T>) {
        dropTargetMap[dropTargetState.key] = dropTargetState
    }

    internal fun removeDropTarget(key: Any) {
        dropTargetMap.remove(key)
    }

    // Draggable Item

    /**
     * Map of [DraggableItemState] by key
     */
    internal val draggableItemMap = mutableMapOf<Any, DraggableItemState<T>>()

    /**
     * Item that is currently dragged [DraggableItemState], null if no item is dragged
     */
    internal var currentDraggableItem by mutableStateOf<DraggableItemState<T>?>(null)

    /**
     * State of the item that is currently dragged [DraggedItemState], null if no item is dragged
     */
    var draggedItem by mutableStateOf<DraggedItemState<T>?>(null)
        internal set

    internal var pointerId by mutableStateOf<PointerId?>(null)

    /**
     * Add or update [DraggableItemState]
     *
     * @param state - new state
     */
    internal fun addOrUpdateDraggableItem(
        state: DraggableItemState<T>
    ) {
        val key = state.key
        val oldState = draggableItemMap[key]

        if (oldState != null) {
            updateDraggableItem(key, state)
        } else {
            draggableItemMap[key] = state
        }
    }

    internal fun removeDraggableItem(key: Any) {
        draggableItemMap.remove(key)
    }

    /**
     * Update [DraggableItemState]
     *
     * @param key - key of [DraggableItemState] that is dragged
     * @param state - new state
     */
    private fun updateDraggableItem(
        key: Any,
        state: DraggableItemState<T>
    ) {
        val oldState = draggableItemMap[key] ?: return

        oldState.size = state.size
        oldState.positionInRoot = state.positionInRoot
        oldState.dropTargets = state.dropTargets
        oldState.data = state.data
        oldState.key = state.key
    }

    private var dragStartPositionInRoot: Offset = Offset.Zero
    private var dragStartOffset: Offset = Offset.Zero

    internal val dragPosition: MutableState<Offset> = mutableStateOf(Offset.Zero)
    internal val dragPositionAnimatable: Animatable<Offset, AnimationVector2D> = Animatable(Offset.Zero, Offset.VectorConverter)

    /**
     * Handle drag start method is called when drag starts
     * - It updates [DragAndDropState.currentDraggableItem]
     * - It updates [DragAndDropState.draggedItem]
     * @param offset - offset of the drag start position
     */
    internal suspend fun handleDragStart(
        offset: Offset,
    ) = coroutineScope {
        val draggableItemState = draggableItemMap.values.find {
            MathUtils.isPointInRectangle(
                point = offset,
                topLeft = it.positionInRoot,
                size = it.size,
            )
        } ?: return@coroutineScope

        launch {
            dragPositionAnimatable.snapTo(Offset.Zero)
        }

        dragPosition.value = draggableItemState.positionInRoot

        dragStartPositionInRoot = draggableItemState.positionInRoot
        dragStartOffset = offset
        currentDraggableItem = draggableItemState
        draggedItem = DraggedItemState(
            key = draggableItemState.key,
            data = draggableItemState.data,
            dragAmount = Offset.Zero,
        )
    }

    /**
     * Handle drag method is called when drag is in progress
     * - It updates [DragAndDropState.draggedItem]
     * - It updates [hoveredDropTargetKey] if needed
     * - It calls [DropTargetState.onDragEnter] and [DropTargetState.onDragExit] if needed
     * @param offset - offset of the drag position
     */
    internal suspend fun handleDrag(
        offset: Offset,
    ) = coroutineScope {
        val currentDraggableItem = currentDraggableItem ?: return@coroutineScope
        val dropTargetIds = currentDraggableItem.dropTargets

        val dragAmount = offset - dragStartOffset
        val newTopLeft = dragStartPositionInRoot + dragAmount
        val hoveredDropTarget = dropTargetMap.values
            .filter {
                MathUtils.isRectangleIntersected(
                    topLeft1 = newTopLeft,
                    size1 = currentDraggableItem.size,
                    topLeft2 = it.topLeft,
                    size2 = it.size,
                ) &&
                (dropTargetIds.isEmpty() || it.key in dropTargetIds)
            }
            .maxByOrNull {
                val maxOverlappingArea = currentDraggableItem.size.width * currentDraggableItem.size.height
                MathUtils.overlappingArea(
                    topLeft1 = newTopLeft,
                    size1 = currentDraggableItem.size,
                    topLeft2 = it.topLeft,
                    size2 = it.size,
                ) + it.zIndex * maxOverlappingArea
            }

        val newDraggedItemState = draggedItem?.copy(
            dragAmount = dragAmount,
        )

        if (hoveredDropTarget?.key != hoveredDropTargetKey && newDraggedItemState != null) {
            dropTargetMap.values.find { it.key == hoveredDropTargetKey }?.onDragExit?.invoke(newDraggedItemState)
            hoveredDropTarget?.onDragEnter?.invoke(newDraggedItemState)
        }

        dragPosition.value = newTopLeft

        hoveredDropTargetKey = hoveredDropTarget?.key ?: ""
        draggedItem = newDraggedItemState
    }

    /**
     * Handle drop method is called when drag is finished
     * - It performs animation to the final position
     * - It calls [DropTargetState.onDrop] if the item is dropped on [DropTargetState]
     * - It clears the drag state
     */
    internal suspend fun handleDragEnd() = coroutineScope {
        val currentDraggableItem = currentDraggableItem ?: return@coroutineScope

        val dropTarget = dropTargetMap.values.find { it.key == hoveredDropTargetKey }

        if (dropTarget == null || dropTarget.dropAnimationEnabled) {
            launch {
                val dropTopLeft = dropTarget?.getDropTopLeft(currentDraggableItem.size) ?: currentDraggableItem.positionInRoot

                val animateToPosition = dropTopLeft - dragPosition.value

                dragPositionAnimatable.animateTo(
                    targetValue = animateToPosition,
                    animationSpec = currentDraggableItem.dropAnimationSpec,
                )
            }.join()
        }

        draggedItem?.let {
            dropTarget?.onDrop?.invoke(it)
        }
        clearDragState()
    }

    /**
     * Handle drop method is called when drag is canceled
     * - It performs animation to the dragged item original position
     * - It clears the drag state
     */
    internal suspend fun handleDragCancel() = coroutineScope {
        val currentDraggableItem = currentDraggableItem ?: return@coroutineScope

        val animateToPosition = currentDraggableItem.positionInRoot - dragPosition.value

        launch {
            dragPositionAnimatable.animateTo(
                targetValue = animateToPosition,
                animationSpec = currentDraggableItem.dropAnimationSpec,
            )
        }.join()

        clearDragState()
    }

    /**
     * Clear the drag state after drag is finished
     */
    private suspend fun clearDragState() {
        currentDraggableItem = null
        draggedItem = null
        hoveredDropTargetKey = ""
        dragStartOffset = Offset.Zero
        dragStartPositionInRoot = Offset.Zero
        dragPosition.value = Offset.Zero
        dragPositionAnimatable.snapTo(Offset.Zero)
    }
}
