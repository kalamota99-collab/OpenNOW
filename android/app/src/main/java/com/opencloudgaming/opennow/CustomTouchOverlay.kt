// CustomTouchOverlay.kt
// Drop into: android/app/src/main/java/com/opencloudgaming/opennow/
// Same package as OpenNowScreens.kt (com.opencloudgaming.opennow) -
// NativeStreamClient and GamepadButtonMapping are visible with no import.
//
// Replaces the fixed two-cluster layout with a fully data-driven one:
// every button independently draggable/resizable/rebindable in edit mode.
// Joysticks are fixed zones; the knob dynamically appears wherever you
// first touch inside the zone. Talks to the exact same real API the
// existing controls use - this only changes the UI layer.

package com.opencloudgaming.opennow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Data model - add these fields to AndroidTouchSettings in Models.kt (diff
// at the bottom of this file). @Serializable so they persist automatically
// through the existing settings save/load system - no new storage code.
// ---------------------------------------------------------------------------

enum class CustomButtonKind { NORMAL, TRIGGER_LEFT, TRIGGER_RIGHT }

@Serializable
data class CustomButtonSpec(
    val id: String,
    val label: String,
    val mask: Int = 0,               // ignored when kind != NORMAL
    val kind: CustomButtonKind = CustomButtonKind.NORMAL,
    val xPct: Float,                 // center position, 0-100 of screen width/height
    val yPct: Float,
    val sizeDp: Float = 56f,
    val shape: String = "circle",    // "circle" | "square"
)

@Serializable
data class CustomStickSpec(
    val id: String,
    val isLeft: Boolean,             // true -> setVirtualLeftStick, false -> setVirtualRightStick
    val zoneXPct: Float,
    val zoneYPct: Float,
    val zoneWPct: Float,
    val zoneHPct: Float,
    val knobRadiusDp: Float = 55f,
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
    CustomButtonSpec("dup", "↑", mask = 0x0001, xPct = 14f, yPct = 68f, sizeDp = 46f, shape = "square"),
    CustomButtonSpec("ddown", "↓", mask = 0x0002, xPct = 14f, yPct = 84f, sizeDp = 46f, shape = "square"),
    CustomButtonSpec("dleft", "←", mask = 0x0004, xPct = 6f, yPct = 76f, sizeDp = 46f, shape = "square"),
    CustomButtonSpec("dright", "→", mask = 0x0008, xPct = 22f, yPct = 76f, sizeDp = 46f, shape = "square"),
    CustomButtonSpec("back", "Back", mask = 0x0020, xPct = 40f, yPct = 6f, sizeDp = 40f, shape = "square"),
    CustomButtonSpec("start", "Start", mask = 0x0010, xPct = 60f, yPct = 6f, sizeDp = 40f, shape = "square"),
    CustomButtonSpec("l3", "L3", mask = GamepadButtonMapping.LEFT_THUMB, xPct = 15f, yPct = 50f, sizeDp = 36f, shape = "square"),
    CustomButtonSpec("r3", "R3", mask = GamepadButtonMapping.RIGHT_THUMB, xPct = 85f, yPct = 50f, sizeDp = 36f, shape = "square"),
)

fun defaultCustomSticks(): List<CustomStickSpec> = listOf(
    CustomStickSpec("left", isLeft = true, zoneXPct = 2f, zoneYPct = 52f, zoneWPct = 26f, zoneHPct = 38f),
    CustomStickSpec("right", isLeft = false, zoneXPct = 72f, zoneYPct = 52f, zoneWPct = 26f, zoneHPct = 38f),
)

// Rebind picker options (thumb-clicks excluded - those live on dedicated
// L3/R3 buttons above, kept separate since they're conceptually different
// from face/shoulder/dpad buttons)
val REBINDABLE_MASKS: List<Pair<String, Int>> = listOf(
    "A" to 0x1000, "B" to 0x2000, "X" to 0x4000, "Y" to 0x8000,
    "LB" to 0x0100, "RB" to 0x0200,
    "Back" to 0x0020, "Start" to 0x0010,
    "D-Up" to 0x0001, "D-Down" to 0x0002, "D-Left" to 0x0004, "D-Right" to 0x0008,
    "L3" to GamepadButtonMapping.LEFT_THUMB, "R3" to GamepadButtonMapping.RIGHT_THUMB,
)

private fun clampOffset(offset: Offset, maxRadius: Float): Offset {
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
    editMode: Boolean,
    onButtonTone: () -> Unit,
    onLayoutChange: (List<CustomButtonSpec>, List<CustomStickSpec>) -> Unit,
) {
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

        if (editMode) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.offset(x = 8.dp, y = 8.dp),
            ) {
                Text(
                    "+ Add Button",
                    color = MaterialTheme.colorScheme.onTertiary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(10.dp)
                        .pointerInput(buttons) {
                            detectTapAndAdd { pushButtons(addDefaultButton(buttons)) }
                        },
                )
            }
        }
    }
}

// Small helper: a plain single-tap detector (kept separate from drag so it
// doesn't fight with the drag gesture recognizer on the same modifier chain)
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapAndAdd(onTap: () -> Unit) {
    androidx.compose.foundation.gestures.detectTapGestures(onTap = { onTap() })
}

fun addDefaultButton(current: List<CustomButtonSpec>): List<CustomButtonSpec> {
    var n = 1
    var id = "custom$n"
    while (current.any { it.id == id }) { n += 1; id = "custom$n" }
    return current + CustomButtonSpec(id, "A", mask = 0x1000, xPct = 50f, yPct = 50f)
}

// ---------------------------------------------------------------------------
// Individual button
// ---------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.CustomButton(
    spec: CustomButtonSpec,
    editMode: Boolean,
    client: NativeStreamClient,
    opacity: Float,
    maxW: Dp,
    maxH: Dp,
    onButtonTone: () -> Unit,
    onChange: (CustomButtonSpec) -> Unit,
    onDelete: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val idleSurface = MaterialTheme.colorScheme.surfaceVariant
    val shapeMod = if (spec.shape == "circle") CircleShape else RoundedCornerShape(10.dp)

    val centerX = maxW * (spec.xPct / 100f)
    val centerY = maxH * (spec.yPct / 100f)
    val half = (spec.sizeDp / 2).dp

    Box(
        Modifier
            .offset(x = centerX - half, y = centerY - half)
            .size(spec.sizeDp.dp)
            .clip(shapeMod)
            .background((if (pressed) accent else idleSurface).copy(alpha = opacity))
            .border(1.dp, accent.copy(alpha = opacity), shapeMod)
            .then(
                if (editMode) {
                    Modifier.pointerInput(spec.id, maxW, maxH) {
                        var lastTapMs = 0L
                        detectDragGestures(
                            onDragEnd = {
                                val now = System.currentTimeMillis()
                                if (now - lastTapMs < 260) showPicker = true
                                lastTapMs = now
                            },
                        ) { change, dragAmount ->
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
                } else {
                    Modifier.pointerInput(client, spec.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val down = event.changes.any { it.pressed }
                                if (down != pressed) {
                                    when (spec.kind) {
                                        CustomButtonKind.NORMAL -> client.setVirtualButton(spec.mask, down)
                                        CustomButtonKind.TRIGGER_LEFT -> client.setVirtualTrigger(true, down)
                                        CustomButtonKind.TRIGGER_RIGHT -> client.setVirtualTrigger(false, down)
                                    }
                                    pressed = down
                                    if (down) onButtonTone()
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.label, fontWeight = FontWeight.Bold, color = if (pressed) MaterialTheme.colorScheme.onPrimary else Color.White)

        if (editMode) {
            // delete handle
            Box(
                Modifier
                    .offset(x = spec.sizeDp.dp - 10.dp, y = (-10).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD32F2F))
                    .pointerInput(spec.id) {
                        detectTapAndAdd { onDelete() }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("x", color = Color.White, fontWeight = FontWeight.Bold) }

            // resize handle
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
                            onChange(spec.copy(sizeDp = (spec.sizeDp + deltaDp).coerceIn(28f, 140f)))
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RebindPicker(onPick: (Int, String) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapAndAdd { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
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
                            .pointerInput(label) { detectTapAndAdd { onPick(mask, label) } },
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
// Joystick - fixed/editable zone, knob dynamically appears at first touch
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
    var dynamicCenter by remember { mutableStateOf<Offset?>(null) }

    val zoneX = maxW * (spec.zoneXPct / 100f)
    val zoneY = maxH * (spec.zoneYPct / 100f)
    val zoneW = maxW * (spec.zoneWPct / 100f)
    val zoneH = maxH * (spec.zoneHPct / 100f)

    Box(
        Modifier
            .offset(x = zoneX, y = zoneY)
            .size(zoneW, zoneH)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (editMode) 0.35f else 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (editMode) 0.8f else 0.25f), RoundedCornerShape(18.dp))
            .then(
                if (editMode) {
                    Modifier.pointerInput(spec.id, maxW, maxH) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dxPct = with(density) { dragAmount.x.toDp().value } / maxW.value * 100f
                            val dyPct = with(density) { dragAmount.y.toDp().value } / maxH.value * 100f
                            onChange(
                                spec.copy(
                                    zoneXPct = (spec.zoneXPct + dxPct).coerceIn(0f, 100f - spec.zoneWPct),
                                    zoneYPct = (spec.zoneYPct + dyPct).coerceIn(0f, 100f - spec.zoneHPct),
                                ),
                            )
                        }
                    }
                } else {
                    Modifier.pointerInput(client, spec.id) {
                        val maxRadiusPx = with(density) { spec.knobRadiusDp.dp.toPx() }
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change == null) {
                                    if (dynamicCenter != null) {
                                        if (spec.isLeft) client.setVirtualLeftStick(0f, 0f) else client.setVirtualRightStick(0f, 0f)
                                        dynamicCenter = null
                                        knobOffset = Offset.Zero
                                    }
                                    continue
                                }
                                if (dynamicCenter == null) dynamicCenter = change.position
                                val delta = clampOffset(change.position - dynamicCenter!!, maxRadiusPx)
                                knobOffset = delta
                                val nx = (delta.x / maxRadiusPx).coerceIn(-1f, 1f)
                                val ny = (delta.y / maxRadiusPx).coerceIn(-1f, 1f)
                                if (spec.isLeft) client.setVirtualLeftStick(nx, ny) else client.setVirtualRightStick(nx, ny)
                                change.consume()
                            }
                        }
                    }
                },
            ),
    ) {
        if (!editMode && dynamicCenter != null) {
            // dynamicCenter is in px, relative to this zone Box's own origin
            // (pointerInput coordinates are local to the element they're
            // attached to), so no extra zoneX/zoneY subtraction is needed.
            val knobSizePx = with(density) { 60.dp.toPx() }
            Box(
                Modifier
                    .offset(
                        x = with(density) { (dynamicCenter!!.x + knobOffset.x - knobSizePx / 2f).toDp() },
                        y = with(density) { (dynamicCenter!!.y + knobOffset.y - knobSizePx / 2f).toDp() },
                    )
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = opacity)),
            )
        }

        if (editMode) {
            Box(
                Modifier
                    .offset(x = zoneW - 12.dp, y = zoneH - 12.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .pointerInput(spec.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dwPct = with(density) { dragAmount.x.toDp().value } / maxW.value * 100f
                            val dhPct = with(density) { dragAmount.y.toDp().value } / maxH.value * 100f
                            onChange(
                                spec.copy(
                                    zoneWPct = (spec.zoneWPct + dwPct).coerceIn(10f, 60f),
                                    zoneHPct = (spec.zoneHPct + dhPct).coerceIn(10f, 60f),
                                ),
                            )
                        }
                    },
            )
        }
    }
}
