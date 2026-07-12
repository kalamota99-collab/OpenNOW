// CustomTouchOverlay.kt
// Drop into: android/app/src/main/java/com/opencloudgaming/opennow/
// Same package as OpenNowScreens.kt.

package com.opencloudgaming.opennow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Theme - Minimalist Translucent Glass Style
// ---------------------------------------------------------------------------

private val ButtonFill = Color(0xFFE2E2E2)
private val ButtonBorder = Color.White
private val EditAccent = Color(0xFFFF3D9A)

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

enum class CustomButtonKind { NORMAL, TRIGGER_LEFT, TRIGGER_RIGHT }

@Serializable
data class CustomButtonSpec(
    val id: String,
    val label: String,
    val mask: Int = 0,
    val kind: CustomButtonKind = CustomButtonKind.NORMAL,
    val xPct: Float,
    val yPct: Float,
    val sizeDp: Float = 56f,
    val shape: String = "circle",
)

@Serializable
data class CustomStickSpec(
    val id: String,
    val isLeft: Boolean,
    val centerXPct: Float,
    val centerYPct: Float,
    val radiusDp: Float = 70f,
    val knobRadiusDp: Float = 30f,
)

fun defaultCustomButtons(): List<CustomButtonSpec> = listOf(
    CustomButtonSpec("a", "A", mask = 0x1000, xPct = 88f, yPct = 78f, sizeDp = 60f),
    CustomButtonSpec("b", "B", mask = 0x2000, xPct = 95f, yPct = 63f, sizeDp = 60f),
    CustomButtonSpec("x", "X", mask = 0x4000, xPct = 81f, yPct = 63f, sizeDp = 60f),
    CustomButtonSpec("y", "Y", mask = 0x8000, xPct = 88f, yPct = 48f, sizeDp = 60f),
    CustomButtonSpec("lb", "LB", mask = 0x0100, xPct = 12f, yPct = 10f, sizeDp = 48f, shape = "wide_rect"),
    CustomButtonSpec("rb", "RB", mask = 0x0200, xPct = 88f, yPct = 10f, sizeDp = 48f, shape = "wide_rect"),
    CustomButtonSpec("lt", "LT", kind = CustomButtonKind.TRIGGER_LEFT, xPct = 12f, yPct = 24f, sizeDp = 48f, shape = "wide_rect"),
    CustomButtonSpec("rt", "RT", kind = CustomButtonKind.TRIGGER_RIGHT, xPct = 88f, yPct = 24f, sizeDp = 48f, shape = "wide_rect"),
    CustomButtonSpec("dup", "\u2191", mask = 0x0001, xPct = 20f, yPct = 64f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("ddown", "\u2193", mask = 0x0002, xPct = 20f, yPct = 82f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("dleft", "\u2190", mask = 0x0004, xPct = 14f, yPct = 73f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("dright", "\u2192", mask = 0x0008, xPct = 26f, yPct = 73f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("back", "Back", mask = 0x0020, xPct = 40f, yPct = 8f, sizeDp = 40f, shape = "square"),
    CustomButtonSpec("start", "Start", mask = 0x0010, xPct = 60f, yPct = 8f, sizeDp = 40f, shape = "square"),
    CustomButtonSpec("l3", "L3", mask = GamepadButtonMapping.LEFT_THUMB, xPct = 15f, yPct = 50f, sizeDp = 36f, shape = "square"),
    CustomButtonSpec("r3", "R3", mask = GamepadButtonMapping.RIGHT_THUMB, xPct = 85f, yPct = 50f, sizeDp = 36f, shape = "square"),
)

fun defaultCustomSticks(): List<CustomStickSpec> = listOf(
    CustomStickSpec("left", isLeft = true, centerXPct = 20f, centerYPct = 40f),
    CustomStickSpec("right", isLeft = false, centerXPct = 80f, centerYPct = 70f),
)

val REBINDABLE_MASKS: List<Pair<String, Int>> = listOf(
    "A" to 0x1000, "B" to 0x2000, "X" to 0x4000, "Y" to 0x8000,
    "LB" to 0x0100, "RB" to 0x0200,
    "Back" to 0x0020, "Start" to 0x0010,
    "D-Up" to 0x0001, "D-Down" to 0x0002, "D-Left" to 0x0004, "D-Right" to 0x0008,
    "L3" to GamepadButtonMapping.LEFT_THUMB, "R3" to GamepadButtonMapping.RIGHT_THUMB,
)

private fun clampToRadius(offset: Offset, maxRadius: Float): Offset {
    val d = sqrt(offset.x * offset.x + offset.y * offset.y)
    if (d <= maxRadius || d == 0f) return offset
    val scale = maxRadius / d
    return Offset(offset.x * scale, offset.y * scale)
}

// ---------------------------------------------------------------------------
// Main overlay
// ---------------------------------------------------------------------------

@Composable
fun CustomTouchOverlay(
    client: NativeStreamClient,
    touch: AndroidTouchSettings,
    onButtonTone: (() -> Unit)? = null,
    onLayoutChange: (List<CustomButtonSpec>, List<CustomStickSpec>) -> Unit,
) {
    var editMode by remember { mutableStateOf(false) }
    var buttons by remember(touch.customButtons) { mutableStateOf(touch.customButtons) }
    var sticks by remember(touch.customSticks) { mutableStateOf(touch.customSticks) }

    var editPanelXPct by remember { mutableStateOf(45f) }
    var editPanelYPct by remember { mutableStateOf(5f) }
    var editPanelDrag by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    fun pushButtons(next: List<CustomButtonSpec>) {
        buttons = next
        onLayoutChange(next, sticks)
    }
    fun pushSticks(next: List<CustomStickSpec>) {
        sticks = next
        onLayoutChange(buttons, next)
    }

    val editPassthroughId = "custom-edit-panel"
    DisposableEffect(editPassthroughId) {
        onDispose { NativeStreamInputRouter.clearTouchControllerPassthroughBound(editPassthroughId) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (editMode) Modifier.border(3.dp, EditAccent) else Modifier),
    ) {
        val maxW = maxWidth
        val maxH = maxHeight

        sticks.forEach { spec ->
            CustomStick(
                spec = spec,
                editMode = editMode,
                client = client,
                opacity = touch.opacity,
                maxW = maxW,
                maxH = maxH,
                onChange = { updated -> pushSticks(sticks.map { if (it.id == updated.id) updated else it }) },
            )
        }

        buttons.forEach { spec ->
            CustomButton(
                spec = spec,
                editMode = editMode,
                client = client,
                opacity = touch.opacity,
                maxW = maxW,
                maxH = maxH,
                onButtonTone = onButtonTone,
                onChange = { updated -> pushButtons(buttons.map { if (it.id == updated.id) updated else it }) },
                onDelete = { pushButtons(buttons.filterNot { it.id == spec.id }) },
            )
        }

        // --- Draggable Edit Toggle Panel ---
        val editPanelX = maxW * (editPanelXPct / 100f)
        val editPanelY = maxH * (editPanelYPct / 100f)

        Box(
            Modifier
                .offset { 
                    val xPx = editPanelX.toPx() + editPanelDrag.x
                    val yPx = editPanelY.toPx() + editPanelDrag.y
                    IntOffset(xPx.roundToInt(), yPx.roundToInt()) 
                }
                .zIndex(50f)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    NativeStreamInputRouter.setTouchControllerPassthroughBound(
                        editPassthroughId,
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = if (editMode) EditAccent else Color(0xFF222222).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, if (editMode) Color.White else ButtonBorder.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .pointerInput(editMode, maxW, maxH, density) {
                            if (editMode) {
                                detectDragGestures(
                                    onDragEnd = {
                                        val dxPct = (editPanelDrag.x / density.density) / maxW.value * 100f
                                        val dyPct = (editPanelDrag.y / density.density) / maxH.value * 100f
                                        editPanelXPct = (editPanelXPct + dxPct).coerceIn(1f, 90f)
                                        editPanelYPct = (editPanelYPct + dyPct).coerceIn(1f, 90f)
                                        editPanelDrag = Offset.Zero
                                    },
                                    onDragCancel = { editPanelDrag = Offset.Zero }
                                ) { change, dragAmount ->
                                    change.consume()
                                    editPanelDrag += dragAmount
                                }
                            }
                        }
                        .pointerInput(editMode) {
                            detectTapGestures(onTap = { editMode = !editMode })
                        },
                ) {
                    Text(
                        if (editMode) "DONE" else "EDIT",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }

                if (editMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFF222222).copy(alpha = 0.85f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, ButtonBorder.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .pointerInput(buttons) {
                                detectTapGestures(onTap = { pushButtons(addDefaultButton(buttons)) })
                            },
                    ) {
                        Text(
                            "+ Add",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

fun addDefaultButton(current: List<CustomButtonSpec>): List<CustomButtonSpec> {
    var n = 1
    var id = "custom$n"
    while (current.any { it.id == id }) { n += 1; id = "custom$n" }
    return current + CustomButtonSpec(id, "A", mask = 0x1000, xPct = 50f, yPct = 50f)
}

// ---------------------------------------------------------------------------
// Individual button implementation with Perfect Anti-Stutter Logic
// ---------------------------------------------------------------------------

@Composable
private fun BoxWithConstraintsScope.CustomButton(
    spec: CustomButtonSpec,
    editMode: Boolean,
    client: NativeStreamClient,
    opacity: Float,
    maxW: Dp,
    maxH: Dp,
    onButtonTone: (() -> Unit)?,
    onChange: (CustomButtonSpec) -> Unit,
    onDelete: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    
    // Defer visual state updates to prevent layout thrashing and stutters
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var resizeDrag by remember { mutableStateOf(0f) }
    var lastSpec by remember { mutableStateOf(spec) }

    // This completely stops the bouncing/shaking bug. It only resets local drag 
    // variables when the Master database successfully updates and passes a new spec down.
    if (spec != lastSpec) {
        lastSpec = spec
        dragOffset = Offset.Zero
        resizeDrag = 0f
    }

    val density = LocalDensity.current

    val shapeMod = when (spec.shape) {
        "circle" -> CircleShape
        "square" -> RoundedCornerShape(12.dp)
        "wide_rect" -> RoundedCornerShape(24.dp)
        else -> CircleShape
    }

    val activeSizeDp = (spec.sizeDp + resizeDrag).coerceIn(28f, 160f)
    val buttonWidth = if (spec.shape == "wide_rect") activeSizeDp.dp * 1.6f else activeSizeDp.dp
    val buttonHeight = if (spec.shape == "wide_rect") activeSizeDp.dp * 0.75f else activeSizeDp.dp

    val centerX = maxW * (spec.xPct / 100f)
    val centerY = maxH * (spec.yPct / 100f)
    val halfW = buttonWidth / 2
    val halfH = buttonHeight / 2

    val editHitMargin = 36.dp

    val passthroughId = "custom-btn-${spec.id}"
    DisposableEffect(passthroughId) {
        onDispose { NativeStreamInputRouter.clearTouchControllerPassthroughBound(passthroughId) }
    }

    @Composable
    fun VisualButton() {
        Box(
            Modifier
                .size(width = buttonWidth, height = buttonHeight)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    NativeStreamInputRouter.setTouchControllerPassthroughBound(
                        passthroughId,
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                }
                .clip(shapeMod)
                .background(ButtonFill.copy(alpha = if (pressed) opacity * 0.6f else opacity * 0.25f))
                .border(1.5.dp, ButtonBorder.copy(alpha = opacity.coerceAtLeast(0.35f)), shapeMod),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                spec.label,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = opacity.coerceAtLeast(0.6f))
            )

            if (editMode) {
                // Delete handle
                Box(
                    Modifier
                        .offset(x = buttonWidth - 10.dp, y = (-10).dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD32F2F))
                        .pointerInput(spec.id) { detectTapGestures(onTap = { onDelete() }) },
                    contentAlignment = Alignment.Center,
                ) { Text("x", color = Color.White, fontWeight = FontWeight.Bold) }

                // Resize handle
                Box(
                    Modifier
                        .offset(x = buttonWidth - 14.dp, y = buttonHeight - 14.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(EditAccent)
                        .pointerInput(spec.id, density) {
                            detectDragGestures(
                                onDragEnd = { onChange(spec.copy(sizeDp = activeSizeDp)) },
                                onDragCancel = { resizeDrag = 0f }
                            ) { change, dragAmount ->
                                change.consume()
                                val deltaDp = ((dragAmount.x + dragAmount.y) / 2f) / density.density
                                resizeDrag += deltaDp
                            }
                        },
                )
            }
        }
    }

    if (editMode) {
        Box(
            Modifier
                .offset {
                    val xPx = centerX.toPx() - halfW.toPx() - editHitMargin.toPx() + dragOffset.x
                    val yPx = centerY.toPx() - halfH.toPx() - editHitMargin.toPx() + dragOffset.y
                    IntOffset(xPx.roundToInt(), yPx.roundToInt())
                }
                .size(width = buttonWidth + editHitMargin * 2, height = buttonHeight + editHitMargin * 2)
                .pointerInput(spec.id, maxW, maxH) {
                    detectTapGestures(onDoubleTap = { if (spec.kind == CustomButtonKind.NORMAL) showPicker = true })
                }
                .pointerInput(spec.id, maxW, maxH, density) {
                    detectDragGestures(
                        onDragEnd = {
                            val dxPct = (dragOffset.x / density.density) / maxW.value * 100f
                            val dyPct = (dragOffset.y / density.density) / maxH.value * 100f
                            onChange(
                                spec.copy(
                                    xPct = (spec.xPct + dxPct).coerceIn(2f, 98f),
                                    yPct = (spec.yPct + dyPct).coerceIn(2f, 98f),
                                )
                            )
                            // DragOffset NOT reset to Zero here. Let spec trigger the state flush.
                        },
                        onDragCancel = { dragOffset = Offset.Zero }
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            VisualButton()
        }
    } else {
        Box(
            Modifier
                .offset {
                    val xPx = centerX.toPx() - halfW.toPx()
                    val yPx = centerY.toPx() - halfH.toPx()
                    IntOffset(xPx.roundToInt(), yPx.roundToInt())
                }
                .size(width = buttonWidth, height = buttonHeight)
                .pointerInput(client, spec.id, spec.mask, spec.kind) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id
                            pressed = true
                            when (spec.kind) {
                                CustomButtonKind.NORMAL -> client.setVirtualButton(spec.mask, true)
                                CustomButtonKind.TRIGGER_LEFT -> client.setVirtualTrigger(true, true)
                                CustomButtonKind.TRIGGER_RIGHT -> client.setVirtualTrigger(false, true)
                            }
                            onButtonTone?.invoke()

                            var event = awaitPointerEvent()
                            var change = event.changes.firstOrNull { it.id == pointerId }
                            while (change != null && change.pressed) {
                                change.consume() 
                                event = awaitPointerEvent()
                                change = event.changes.firstOrNull { it.id == pointerId }
                            }

                            pressed = false
                            when (spec.kind) {
                                CustomButtonKind.NORMAL -> client.setVirtualButton(spec.mask, false)
                                CustomButtonKind.TRIGGER_LEFT -> client.setVirtualTrigger(true, false)
                                CustomButtonKind.TRIGGER_RIGHT -> client.setVirtualTrigger(false, false)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            VisualButton()
        }
    }

    if (showPicker && spec.kind == CustomButtonKind.NORMAL) {
        RebindPicker(
            onPick = { newMask, newLabel ->
                onChange(spec.copy(mask = newMask, label = newLabel))
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun RebindPicker(onPick: (Int, String) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(Color.Black.copy(alpha = 0.65f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color(0xFF1B1B1B),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, ButtonBorder.copy(alpha = 0.4f)),
            modifier = Modifier
                .padding(24.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Rebind button",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    items(REBINDABLE_MASKS) { (label, mask) ->
                        Box(
                            Modifier
                                .padding(6.dp)
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2E2E2E))
                                .border(1.dp, ButtonBorder.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .pointerInput(mask) {
                                    detectTapGestures(onTap = { onPick(mask, label) })
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Joystick - Flawless Edit Drag & Instant Anti-Deadzone Input
// ---------------------------------------------------------------------------

@Composable
private fun BoxWithConstraintsScope.CustomStick(
    spec: CustomStickSpec,
    editMode: Boolean,
    client: NativeStreamClient,
    opacity: Float,
    maxW: Dp,
    maxH: Dp,
    onChange: (CustomStickSpec) -> Unit,
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var baseOffset by remember { mutableStateOf(Offset.Zero) }
    
    // Defer visual state updates to prevent layout thrashing and stutters
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var resizeDrag by remember { mutableStateOf(0f) }
    var lastSpec by remember { mutableStateOf(spec) }

    // Stops shaking/random relocations on save
    if (spec != lastSpec) {
        lastSpec = spec
        dragOffset = Offset.Zero
        resizeDrag = 0f
    }
    
    val density = LocalDensity.current

    val centerX = maxW * (spec.centerXPct / 100f)
    val centerY = maxH * (spec.centerYPct / 100f)
    val activeRadiusDp = (spec.radiusDp + resizeDrag).coerceIn(40f, 160f)
    val outerDiameter = activeRadiusDp.dp * 2
    val knobDiameter = spec.knobRadiusDp.dp * 2
    val editHitMargin = 24.dp

    val zoneRadiusDp = spec.radiusDp * 1.8f
    val zoneDiameter = zoneRadiusDp.dp * 2

    val passthroughId = "custom-stick-${spec.id}"
    DisposableEffect(passthroughId) {
        onDispose { NativeStreamInputRouter.clearTouchControllerPassthroughBound(passthroughId) }
    }

    @Composable
    fun VisualStick() {
        Box(
            Modifier.size(outerDiameter),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(outerDiameter)
                    .clip(CircleShape)
                    .background(ButtonFill.copy(alpha = opacity * 0.18f))
                    .border(1.5.dp, ButtonBorder.copy(alpha = opacity.coerceAtLeast(0.3f)), CircleShape),
            )
            Box(
                Modifier
                    .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                    .size(knobDiameter)
                    .clip(CircleShape)
                    .background(ButtonFill.copy(alpha = opacity * 0.55f))
                    .border(1.5.dp, ButtonBorder.copy(alpha = opacity.coerceAtLeast(0.5f)), CircleShape),
            )

            if (editMode) {
                // Resize Handle
                Box(
                    Modifier
                        .offset(x = outerDiameter - 14.dp, y = outerDiameter - 14.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(EditAccent)
                        .pointerInput(spec.id, density) {
                            detectDragGestures(
                                onDragEnd = { onChange(spec.copy(radiusDp = activeRadiusDp)) },
                                onDragCancel = { resizeDrag = 0f }
                            ) { change, dragAmount ->
                                change.consume()
                                val deltaDp = ((dragAmount.x + dragAmount.y) / 2f) / density.density
                                resizeDrag += deltaDp / 2f 
                            }
                        },
                )
            }
        }
    }

    if (editMode) {
        Box(
            Modifier
                .offset {
                    val xPx = centerX.toPx() - (outerDiameter / 2).toPx() - editHitMargin.toPx() + dragOffset.x
                    val yPx = centerY.toPx() - (outerDiameter / 2).toPx() - editHitMargin.toPx() + dragOffset.y
                    IntOffset(xPx.roundToInt(), yPx.roundToInt())
                }
                .size(outerDiameter + editHitMargin * 2)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    NativeStreamInputRouter.setTouchControllerPassthroughBound(
                        passthroughId,
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                }
                .pointerInput(spec.id, maxW, maxH, density) {
                    detectDragGestures(
                        onDragEnd = {
                            val dxPct = (dragOffset.x / density.density) / maxW.value * 100f
                            val dyPct = (dragOffset.y / density.density) / maxH.value * 100f
                            onChange(
                                spec.copy(
                                    centerXPct = (spec.centerXPct + dxPct).coerceIn(5f, 95f),
                                    centerYPct = (spec.centerYPct + dyPct).coerceIn(5f, 95f),
                                )
                            )
                        },
                        onDragCancel = { dragOffset = Offset.Zero }
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            VisualStick()
        }
    } else {
        Box(
            Modifier
                .offset {
                    val xPx = centerX.toPx() - (zoneDiameter / 2).toPx()
                    val yPx = centerY.toPx() - (zoneDiameter / 2).toPx()
                    IntOffset(xPx.roundToInt(), yPx.roundToInt())
                }
                .size(zoneDiameter)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    NativeStreamInputRouter.setTouchControllerPassthroughBound(
                        passthroughId,
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                }
                .pointerInput(client, spec.id, spec.isLeft, spec.radiusDp) {
                    val ringRadiusPx = spec.radiusDp.dp.toPx()
                    val zoneRadiusPx = zoneRadiusDp.dp.toPx()
                    val maxBaseOffsetPx = (zoneRadiusPx - ringRadiusPx).coerceAtLeast(0f)
                    val zoneCenterPx = Offset(zoneRadiusPx, zoneRadiusPx)

                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id

                            val downRaw = down.position - zoneCenterPx
                            baseOffset = clampToRadius(downRaw, maxBaseOffsetPx)

                            var event = awaitPointerEvent()
                            var change = event.changes.firstOrNull { it.id == pointerId }
                            while (change != null && change.pressed) {
                                change.consume()
                                val ringCenterPx = zoneCenterPx + baseOffset
                                val knobRaw = change.position - ringCenterPx
                                val clamped = clampToRadius(knobRaw, ringRadiusPx)
                                knobOffset = clamped

                                // ABSOLUTE 0 DEADZONE w/ ANTI-DEADZONE MULTIPLIER
                                var nx = (clamped.x / ringRadiusPx).coerceIn(-1f, 1f)
                                var ny = (clamped.y / ringRadiusPx).coerceIn(-1f, 1f)
                                
                                val distance = sqrt(nx * nx + ny * ny)

                                // If the finger moves even slightly, instantly bypass game deadzones
                                if (distance > 0.001f) {
                                    val antiDeadzone = 0.20f // Instantly send 20% input to bypass engine ignores
                                    val sensitivity = 1.35f  // Reach 100% output with less physical travel
                                    
                                    val adjustedDistance = (antiDeadzone + (distance * sensitivity)).coerceIn(0f, 1f)
                                    val factor = adjustedDistance / distance
                                    
                                    nx = (nx * factor).coerceIn(-1f, 1f)
                                    ny = (ny * factor).coerceIn(-1f, 1f)
                                }

                                if (spec.isLeft) {
                                    client.setVirtualLeftStick(nx, ny)
                                } else {
                                    client.setVirtualRightStick(nx, ny)
                                }
                                event = awaitPointerEvent()
                                change = event.changes.firstOrNull { it.id == pointerId }
                            }

                            knobOffset = Offset.Zero
                            baseOffset = Offset.Zero 
                            if (spec.isLeft) {
                                client.setVirtualLeftStick(0f, 0f)
                            } else {
                                client.setVirtualRightStick(0f, 0f)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.offset { IntOffset(baseOffset.x.roundToInt(), baseOffset.y.roundToInt()) },
            ) {
                VisualStick()
            }
        }
    }
}
