// CustomTouchOverlay.kt
// Drop into: android/app/src/main/java/com/opencloudgaming/opennow/
// Same package as OpenNowScreens.kt - no extra imports needed for
// NativeStreamClient / GamepadButtonMapping.
//
// v2 changes from the first version:
//  - Edit mode is now fully self-contained (own visible toggle button) -
//    it no longer depends on the app's existing layoutEditing flag, which
//    was the reason drag/resize/add-button appeared broken before.
//  - Joysticks are now classic fixed-base: a visible ring always sits at
//    one fixed spot, and the knob's offset is always measured from that
//    fixed center - not from wherever you first touched.
//  - Button press detection uses detectTapGestures(onPress=...), which is
//    the standard low-latency Compose press API (fixes the input delay).
//  - Resize is now pinch-to-zoom (two-finger) via detectTransformGestures,
//    in addition to the drag-handle for single-finger resize.

package com.opencloudgaming.opennow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Data model - unchanged from v1, still goes into AndroidTouchSettings
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
    val centerXPct: Float,   // fixed center of the visible base ring
    val centerYPct: Float,
    val radiusDp: Float = 70f,   // outer ring radius
    val knobRadiusDp: Float = 30f,
)

fun defaultCustomButtons(): List<CustomButtonSpec> = listOf(
    CustomButtonSpec("a", "A", mask = 0x1000, xPct = 88f, yPct = 78f, sizeDp = 60f),
    CustomButtonSpec("b", "B", mask = 0x2000, xPct = 95f, yPct = 63f, sizeDp = 60f),
    CustomButtonSpec("x", "X", mask = 0x4000, xPct = 81f, yPct = 63f, sizeDp = 60f),
    CustomButtonSpec("y", "Y", mask = 0x8000, xPct = 88f, yPct = 48f, sizeDp = 60f),
    CustomButtonSpec("lb", "LB", mask = 0x0100, xPct = 6f, yPct = 10f, sizeDp = 52f, shape = "square"),
    CustomButtonSpec("rb", "RB", mask = 0x0200, xPct = 94f, yPct = 10f, sizeDp = 52f, shape = "square"),
    CustomButtonSpec("lt", "LT", kind = CustomButtonKind.TRIGGER_LEFT, xPct = 6f, yPct = 22f, sizeDp = 52f, shape = "square"),
    CustomButtonSpec("rt", "RT", kind = CustomButtonKind.TRIGGER_RIGHT, xPct = 94f, yPct = 22f, sizeDp = 52f, shape = "square"),
    CustomButtonSpec("dup", "up", mask = 0x0001, xPct = 20f, yPct = 68f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("ddown", "dn", mask = 0x0002, xPct = 20f, yPct = 84f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("dleft", "lt", mask = 0x0004, xPct = 12f, yPct = 76f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("dright", "rt", mask = 0x0008, xPct = 28f, yPct = 76f, sizeDp = 42f, shape = "square"),
    CustomButtonSpec("back", "Back", mask = 0x0020, xPct = 40f, yPct = 6f, sizeDp = 40f, shape = "square"),
    CustomButtonSpec("start", "Start", mask = 0x0010, xPct = 60f, yPct = 6f, sizeDp = 40f, shape = "square"),
    CustomButtonSpec("l3", "L3", mask = GamepadButtonMapping.LEFT_THUMB, xPct = 15f, yPct = 50f, sizeDp = 36f, shape = "square"),
    CustomButtonSpec("r3", "R3", mask = GamepadButtonMapping.RIGHT_THUMB, xPct = 85f, yPct = 50f, sizeDp = 36f, shape = "square"),
)

fun defaultCustomSticks(): List<CustomStickSpec> = listOf(
    CustomStickSpec("left", isLeft = true, centerXPct = 15f, centerYPct = 72f),
    CustomStickSpec("right", isLeft = false, centerXPct = 85f, centerYPct = 72f),
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
// Main overlay - now owns its own edit-mode state
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

    fun pushButtons(next: List<CustomButtonSpec>) {
        buttons = next
        onLayoutChange(next, sticks)
    }
    fun pushSticks(next: List<CustomStickSpec>) {
        sticks = next
        onLayoutChange(buttons, next)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
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

        // --- Self-contained edit toggle - always visible, always tappable ---
        Surface(
            color = if (editMode) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
            shape = CircleShape,
            modifier = Modifier
                .offset(x = 10.dp, y = 10.dp)
                .size(44.dp)
                .pointerInput(editMode) {
                    detectTapGestures(onTap = { editMode = !editMode })
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (editMode) "Done" else "Edit", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (editMode) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .offset(x = 62.dp, y = 10.dp)
                    .pointerInput(buttons) {
                        detectTapGestures(onTap = { pushButtons(addDefaultButton(buttons)) })
                    },
            ) {
                Text(
                    "+ Add Button",
                    color = MaterialTheme.colorScheme.onTertiary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(10.dp),
                )
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
// Individual button - drag to move, pinch OR corner-drag to resize,
// double-tap to rebind, trash icon to delete. Play mode uses
// detectTapGestures(onPress=...) for immediate, low-latency press state.
// ---------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.CustomButton(
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
    val density = LocalDensity.current
    val shapeMod = if (spec.shape == "circle") CircleShape else RoundedCornerShape(10.dp)

    val centerX = maxW * (spec.xPct / 100f)
    val centerY = maxH * (spec.yPct / 100f)
    val half = (spec.sizeDp / 2).dp

    Box(
        Modifier
            .offset(x = centerX - half, y = centerY - half)
            .size(spec.sizeDp.dp)
            .clip(shapeMod)
            .background((if (pressed) Color(0xFF3F8CFF) else Color.White).copy(alpha = if (pressed) opacity.coerceAtLeast(0.5f) else opacity * 0.6f))
            .border(1.5.dp, Color.White.copy(alpha = opacity), shapeMod)
            .then(
                if (editMode) {
                    Modifier
                        .pointerInput(spec.id, maxW, maxH) {
                            detectTapGestures(onDoubleTap = { if (spec.kind == CustomButtonKind.NORMAL) showPicker = true })
                        }
                        .pointerInput(spec.id, maxW, maxH) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dxPct = with(density) { dragAmount.x.toDp().value } / maxW.value * 100f
                                val dyPct = with(density) { dragAmount.y.toDp().value } / maxH.value * 100f
                                onChange(
                                    spec.copy(
                                        xPct = (spec.xPct + dxPct).coerceIn(2f, 98f),
                                        yPct = (spec.yPct + dyPct).coerceIn(2f, 98f),
                                    ),
                                )
                            }
                        }
                        .pointerInput(spec.id) {
                            // Two-finger pinch to resize, in addition to the
                            // single-finger corner handle below.
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) {
                                    onChange(spec.copy(sizeDp = (spec.sizeDp * zoom).coerceIn(28f, 160f)))
                                }
                            }
                        }
                } else {
                    Modifier.pointerInput(client, spec.id, spec.mask, spec.kind) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                when (spec.kind) {
                                    CustomButtonKind.NORMAL -> client.setVirtualButton(spec.mask, true)
                                    CustomButtonKind.TRIGGER_LEFT -> client.setVirtualTrigger(true, true)
                                    CustomButtonKind.TRIGGER_RIGHT -> client.setVirtualTrigger(false, true)
                                }
                                onButtonTone?.invoke()
                                tryAwaitRelease()
                                pressed = false
                                when (spec.kind) {
                                    CustomButtonKind.NORMAL -> client.setVirtualButton(spec.mask, false)
                                    CustomButtonKind.TRIGGER_LEFT -> client.setVirtualTrigger(true, false)
                                    CustomButtonKind.TRIGGER_RIGHT -> client.setVirtualTrigger(false, false)
                                }
                            },
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.label, fontWeight = FontWeight.Bold, color = Color.White)

        if (editMode) {
            Box(
                Modifier
                    .offset(x = spec.sizeDp.dp - 10.dp, y = (-10).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD32F2F))
                    .pointerInput(spec.id) { detectTapGestures(onTap = { onDelete() }) },
                contentAlignment = Alignment.Center,
            ) { Text("x", color = Color.White, fontWeight = FontWeight.Bold) }

            Box(
                Modifier
                    .offset(x = spec.sizeDp.dp - 10.dp, y = spec.sizeDp.dp - 10.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .pointerInput(spec.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaDp = with(density) { (dragAmount.x + dragAmount.y).toDp().value } / 2f
                            onChange(spec.copy(sizeDp = (spec.sizeDp + deltaDp).coerceIn(28f, 160f)))
                        }
                    },
            )
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
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                REBINDABLE_MASKS.forEach { (label, mask) ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(label) { detectTapGestures(onTap = { onPick(mask, label) }) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Joystick - classic fixed base ring, knob offset always measured from the
// FIXED center (not from first-touch point). Drag the whole ring to
// reposition it in edit mode; pinch or corner-drag to resize.
// ---------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.CustomStick(
    spec: CustomStickSpec,
    editMode: Boolean,
    client: NativeStreamClient,
    opacity: Float,
    maxW: Dp,
    maxH: Dp,
    onChange: (CustomStickSpec) -> Unit,
) {
    val density = LocalDensity.current
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    val centerX = maxW * (spec.centerXPct / 100f)
    val centerY = maxH * (spec.centerYPct / 100f)
    val outerHalf = spec.radiusDp.dp
    val knobHalf = (spec.knobRadiusDp).dp

    // Fixed base ring - always visible, always in the same spot.
    Box(
        Modifier
            .offset(x = centerX - outerHalf, y = centerY - outerHalf)
            .size(outerHalf * 2)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = opacity * 0.18f))
            .border(2.dp, Color.White.copy(alpha = opacity * 0.55f), CircleShape)
            .then(
                if (editMode) {
                    Modifier
                        .pointerInput(spec.id, maxW, maxH) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dxPct = with(density) { dragAmount.x.toDp().value } / maxW.value * 100f
                                val dyPct = with(density) { dragAmount.y.toDp().value } / maxH.value * 100f
                                onChange(
                                    spec.copy(
                                        centerXPct = (spec.centerXPct + dxPct).coerceIn(5f, 95f),
                                        centerYPct = (spec.centerYPct + dyPct).coerceIn(5f, 95f),
                                    ),
                                )
                            }
                        }
                        .pointerInput(spec.id) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) {
                                    onChange(spec.copy(radiusDp = (spec.radiusDp * zoom).coerceIn(40f, 130f)))
                                }
                            }
                        }
                } else {
                    Modifier.pointerInput(client, spec.id) {
                        val maxRadiusPx = with(density) { spec.radiusDp.dp.toPx() }
                        detectDragGestures(
                            onDragEnd = {
                                knobOffset = Offset.Zero
                                if (spec.isLeft) client.setVirtualLeftStick(0f, 0f) else client.setVirtualRightStick(0f, 0f)
                            },
                            onDragCancel = {
                                knobOffset = Offset.Zero
                                if (spec.isLeft) client.setVirtualLeftStick(0f, 0f) else client.setVirtualRightStick(0f, 0f)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val next = clampToRadius(knobOffset + dragAmount, maxRadiusPx)
                            knobOffset = next
                            val nx = (next.x / maxRadiusPx).coerceIn(-1f, 1f)
                            val ny = (next.y / maxRadiusPx).coerceIn(-1f, 1f)
                            if (spec.isLeft) client.setVirtualLeftStick(nx, ny) else client.setVirtualRightStick(nx, ny)
                        }
                    }
                },
            ),
    ) {
        // Knob - offset is always relative to the fixed center above, so a
        // touch anywhere in the ring moves the knob from center toward it,
        // it never "teleports" its base to your finger.
        Box(
            Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (outerHalf.toPx() - knobHalf.toPx() + knobOffset.x).toInt(),
                        (outerHalf.toPx() - knobHalf.toPx() + knobOffset.y).toInt(),
                    )
                }
                .size(knobHalf * 2)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = (opacity * 0.9f).coerceAtMost(1f))),
        )

        if (editMode) {
            Box(
                Modifier
                    .offset(x = outerHalf * 2 - 12.dp, y = outerHalf * 2 - 12.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .pointerInput(spec.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaDp = with(density) { (dragAmount.x + dragAmount.y).toDp().value } / 2f
                            onChange(spec.copy(radiusDp = (spec.radiusDp + deltaDp).coerceIn(40f, 130f)))
                        }
                    },
            )
        }
    }
}
