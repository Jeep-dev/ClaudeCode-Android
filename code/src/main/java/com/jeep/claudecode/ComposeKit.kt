package com.jeep.claudecode

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * What the parts of the interface drawn with Compose share: the app's colours, its press, text, its buttons,
 * and Settings' tiles, each looking as its counterpart in views (Ui, Sheets, Settings.tile, Toggle) so the two
 * sit side by side unnoticed.
 */

/**
 * What a tap sets once its control has moved: on the main thread's queue, not in the composition's scope, so
 * it is set even when the page is filled again meanwhile (another setting changed), as the views' postDelayed.
 */
private val later = Handler(Looper.getMainLooper())

/** Material 3's emphasized easing, as Motion.EMPHASIZED. */
internal val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** The app's colours (Themes), read once as a window or page is drawn: the theme does not change under it. */
internal class AppColors(app: MainActivity) {
    private val read = { res: Int -> Color(Ui.color(app, res)) }
    val dark = Themes.dark(app)
    val text = read(R.color.text)
    val text2 = read(R.color.text2)
    val text3 = read(R.color.text3)
    val surface = read(R.color.surface)
    val surface2 = read(R.color.surface2)
    val border = read(R.color.border)
    val borderStrong = read(R.color.borderStrong)
    val accent = read(R.color.accent)
    val accentSoft = read(R.color.accentSoft)
    val accentPress = read(R.color.accentPress)
    val onAccent = read(R.color.onAccent)
    val warn = read(R.color.warn)
    val danger = read(R.color.danger)
    val dangerSoft = read(R.color.dangerSoft)
    val active = read(R.color.active)
}

/** Text as Ui.text makes it: no font padding (Compose's own default), medium where the views use Ui.MEDIUM. */
@Composable
internal fun Words(
    text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, medium: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size, lineHeight = lineHeight, fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal),
        maxLines = maxLines, overflow = overflow,
    )
}

/** A pressed state as the app's views have it (Ui.press): the press colour over the shape, in and out. */
internal data class Press(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color)
}

private class PressNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private val shown = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            source.interactions.collect {
                when (it) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = maxOf(0, pressed - 1)
                    else -> return@collect
                }
                val to = if (pressed > 0) 1f else 0f
                launch { shown.animateTo(to, tween(if (to > 0) 90 else 220)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (shown.value > 0f) drawRect(color.copy(alpha = color.alpha * shown.value))
    }
}

/** Clickable with the app's press, clipped to the shape it is drawn in (clip before this). */
@Composable
internal fun Modifier.pressable(c: AppColors, enabled: Boolean = true, source: MutableInteractionSource? = null, role: Role? = null, click: () -> Unit): Modifier {
    val interactions = source ?: remember { MutableInteractionSource() }
    val press = remember(c.active) { Press(c.active) }
    return clickable(interactions, press, enabled = enabled, role = role, onClick = click)
}

/** As Sheets.button: the answer filled with the accent, else outlined. */
@Composable
internal fun SheetButton(c: AppColors, label: String, accent: Boolean, enabled: Boolean = true, click: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.alpha(if (enabled) 1f else 0.5f).defaultMinSize(minHeight = 38.dp).clip(shape)
            .then(if (accent) Modifier.background(c.accent) else Modifier.background(c.surface).border(1.dp, c.borderStrong, shape))
            .pressable(c, enabled, role = Role.Button, click = click)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Words(label, 14.5.sp, if (accent) c.onAccent else c.text, medium = true)
    }
}

// ---- Settings' tiles ----

/** A group's name above it (Settings.section): close under the page's title, apart from a group above. */
@Composable
internal fun Section(c: AppColors, label: String, afterGroup: Boolean) {
    Words(label, 14.sp, c.accent, Modifier.padding(start = 12.dp, top = if (afterGroup) 22.dp else 4.dp, bottom = 10.dp), medium = true)
}

/** A group of Settings' rows, as tiles one under another (Settings.tile): the group's outer corners round, its inner ones nearly square. */
internal class TileRows(private val c: AppColors) {
    internal val rows = ArrayList<@Composable (Shape) -> Unit>()

    /** A row: its icon in a tinted circle, the label with the value under it (Sheets.option, tiled). */
    fun option(icon: Int, label: String, value: String? = null, danger: Boolean = false, click: (() -> Unit)? = null) {
        rows.add { shape -> OptionTile(c, shape, icon, label, value, danger, click) }
    }

    /** A setting that is on or off: the whole row flips it, and it is set once the thumb has moved (Sheets.toggle). */
    fun toggle(label: String, about: String?, on: Boolean, set: (Boolean) -> Unit) {
        rows.add { shape -> ToggleTile(c, shape, label, about, on, set) }
    }

    /** A choice of a few: its label, the buttons across the tile under it, the chosen one filled (Sheets.choice). */
    fun choice(label: String, labels: List<String>, chosen: Int, pick: (Int) -> Unit) {
        rows.add { shape -> ChoiceTile(c, shape, label, labels, chosen, pick) }
    }

    /** Anything else, on the tile's surface. */
    fun custom(content: @Composable (Shape) -> Unit) {
        rows.add(content)
    }
}

@Composable
internal fun Tiles(c: AppColors, modifier: Modifier = Modifier, build: TileRows.() -> Unit) {
    val rows = TileRows(c).apply(build).rows
    val big = 24.dp
    val small = 5.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) big else small
            val bottom = if (i == rows.size - 1) big else small
            row(RoundedCornerShape(top, top, bottom, bottom))
        }
    }
}

/** A tile's surface, pressed as a whole when it does something. */
@Composable
private fun Modifier.tile(c: AppColors, shape: Shape, source: MutableInteractionSource? = null, role: Role? = null, click: (() -> Unit)?): Modifier =
    fillMaxWidth().clip(shape).background(c.surface).then(if (click != null) Modifier.pressable(c, source = source, role = role, click = click) else Modifier)

/** A tile's row: icon, label and what is under it; whatever goes on its right after (Sheets.tileRow). */
@Composable
private fun TileRow(
    c: AppColors, modifier: Modifier, icon: Int, label: String, value: String?, danger: Boolean,
    end: @Composable () -> Unit = {},
) {
    Row(modifier.defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != 0) {
            Box(Modifier.padding(end = 16.dp).size(44.dp).clip(CircleShape).background(if (danger) c.dangerSoft else c.accentSoft), contentAlignment = Alignment.Center) {
                Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (danger) c.danger else c.accent))
            }
        }
        Column(Modifier.weight(1f)) {
            Words(label, 16.5.sp, if (danger) c.danger else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Line height: the views' 1.15 times the font's own (measured off their screenshots, CJK).
            if (!value.isNullOrEmpty()) {
                Words(value, 13.5.sp, c.text2, Modifier.padding(top = 5.dp), lineHeight = 1.63.em, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        end()
    }
}

@Composable
private fun OptionTile(c: AppColors, shape: Shape, icon: Int, label: String, value: String?, danger: Boolean, click: (() -> Unit)?) {
    TileRow(c, Modifier.tile(c, shape, click = click), icon, label, value, danger)
}

@Composable
private fun ToggleTile(c: AppColors, shape: Shape, label: String, about: String?, initial: Boolean, set: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    TileRow(
        c, Modifier.tile(c, shape, source, Role.Switch) {
            on = !on
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val now = on
            // Redrawn by what it sets: after the thumb has moved.
            later.postDelayed({ set(now) }, 260)
        }.semantics { stateDescription = if (on) "开" else "关" },
        0, label, about, false,
    ) {
        Toggle(c, on, source, Modifier.padding(start = 16.dp))
    }
}

/** A Material 3 switch (Toggle): off, an outlined track with a small thumb on the left; on, a filled track with a big thumb on the right. */
@Composable
private fun Toggle(c: AppColors, on: Boolean, source: InteractionSource, modifier: Modifier) {
    val at by animateFloatAsState(if (on) 1f else 0f, tween(250, easing = Emphasized), label = "toggle")
    val pressed by source.collectIsPressedAsState()
    val held by animateFloatAsState(if (pressed) 1f else 0f, tween(120, easing = Emphasized), label = "held")
    Canvas(modifier.size(52.dp, 32.dp)) {
        val w = size.width
        val h = size.height
        val r = h / 2
        val line = 2.dp.toPx()
        drawRoundRect(lerp(c.surface2, c.accent, at), cornerRadius = CornerRadius(r))
        if (at < 1) {
            drawRoundRect(
                lerp(c.text3, c.accent, at), Offset(line / 2, line / 2), Size(w - line, h - line),
                CornerRadius(r - line / 2), style = Stroke(line),
            )
        }
        // Radius: 16 dp across off, 24 on, 28 held either way.
        val rest = 8 + 4 * at
        drawCircle(lerp(c.text3, c.onAccent, at), (rest + (14 - rest) * held).dp.toPx(), Offset(r + (w - 2 * r) * at, r))
    }
}

@Composable
private fun ChoiceTile(c: AppColors, shape: Shape, label: String, labels: List<String>, chosen: Int, pick: (Int) -> Unit) {
    val n = labels.size
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // The fill and the round ends move from the chosen one to the one tapped, then it is set.
    var to by remember { mutableStateOf(chosen) }
    val moved = remember { Animatable(0f) }
    Column(Modifier.tile(c, shape, click = null).padding(16.dp)) {
        Words(label, 16.5.sp, c.text)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { i, text ->
                val on = when (i) {
                    to -> if (to == chosen) 1f else moved.value
                    chosen -> 1f - moved.value
                    else -> 0f
                }
                val big = 24.dp
                val small = 10.dp
                val l = if (i == 0) big else small + (big - small) * on
                val r = if (i == n - 1) big else small + (big - small) * on
                val buttonShape = RoundedCornerShape(l, r, r, l)
                Box(
                    Modifier.weight(1f).height(48.dp).clip(buttonShape).background(lerp(c.surface2, c.accent, on))
                        .pressable(c, role = Role.RadioButton) {
                            if (i == chosen || to != chosen) return@pressable
                            to = i
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            scope.launch { moved.animateTo(1f, tween(250, easing = Emphasized)) }
                            later.postDelayed({ pick(i) }, 250)
                        }
                        .semantics { if (i == chosen) stateDescription = "已选" },
                    contentAlignment = Alignment.Center,
                ) {
                    Words(text, 15.sp, lerp(c.text, c.onAccent, on), medium = on > 0.5f)
                }
            }
        }
    }
}
