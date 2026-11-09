package com.frigatetv.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private val CornerShape = RoundedCornerShape(Radius.md)

/** Focus scale-up. Applied in the graphics layer, so it never changes layout. */
internal const val FOCUS_SCALE = 1.05f
private val FocusRingWidth = 3.dp
private val FocusGlowWidth = 6.dp
private val SelectedRingWidth = 2.dp

/**
 * Draws the focus ring, an outer glow ring, and the selected ring around the content.
 *
 * [focus] and [selected] are read only inside the draw lambda, so animating them invalidates the
 * draw pass alone (no recomposition or relayout). Outlines are cached until the size changes.
 * No shadows or elevation: those are costly on TV GPUs.
 */
private fun Modifier.focusRing(shape: Shape, focus: () -> Float, selected: () -> Boolean): Modifier =
    drawWithCache {
        val ring = FocusRingWidth.toPx()
        val glow = FocusGlowWidth.toPx()
        val sel = SelectedRingWidth.toPx()
        // Each stroke is centred on its path, so outlines are inset/outset by half a stroke.
        val ringOutline = shape.createOutline(Size(size.width - ring, size.height - ring), layoutDirection, this)
        val selOutline = shape.createOutline(Size(size.width - sel, size.height - sel), layoutDirection, this)
        val glowOutline = shape.createOutline(Size(size.width + glow, size.height + glow), layoutDirection, this)
        val ringStroke = Stroke(ring)
        val selStroke = Stroke(sel)
        val glowStroke = Stroke(glow)
        onDrawWithContent {
            drawContent()
            val f = focus()
            if (f > 0.01f) {
                translate(-glow / 2, -glow / 2) {
                    drawOutline(glowOutline, Accent.copy(alpha = 0.22f * f), style = glowStroke)
                }
                translate(ring / 2, ring / 2) {
                    drawOutline(ringOutline, Accent.copy(alpha = f), style = ringStroke)
                }
            } else if (selected()) {
                translate(sel / 2, sel / 2) {
                    drawOutline(selOutline, Accent.copy(alpha = 0.55f), style = selStroke)
                }
            }
        }
    }

/**
 * A focusable, clickable surface. Focus shows as an accent ring with a soft outer glow and a
 * 5% scale-up, so the focused target is obvious at TV distance. [selected] (e.g. the current tab
 * or chosen camera) shows a thinner, dimmer ring that stays visible without focus, so selected
 * and focused read differently. All focus motion runs in the draw/graphics-layer phases.
 * Works with touch on phones.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    shape: Shape = CornerShape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    selected: Boolean = false,
    focusScale: Float = FOCUS_SCALE,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FocusMs), label = "focus")
    val currentSelected by rememberUpdatedState(selected)
    Box(
        modifier
            // Draw above siblings while focused so the scaled-up surface and glow are not overlapped.
            .zIndex(if (focused) 1f else 0f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocus?.invoke(it.isFocused)
            }
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * progress.value
                scaleX = s
                scaleY = s
            }
            .focusRing(shape, focus = { progress.value }, selected = { currentSelected })
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

/**
 * Button used for every action. [selected] marks the primary action of a group (accent fill);
 * [destructive] tints the label for irreversible actions such as delete. Disabled buttons stay
 * focusable (so D-pad navigation never skips over them) but do not scale up or react to clicks.
 */
@Composable
fun FocusButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val bg by animateColorAsState(
        when {
            !enabled -> Panel
            selected -> Accent
            else -> PanelHi
        },
        tween(Motion.FocusMs),
        label = "buttonBg",
    )
    val fg = when {
        !enabled -> TextSecondary.copy(alpha = 0.6f)
        selected -> Ink
        destructive -> Danger
        else -> TextPrimary
    }
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        focusRequester = focusRequester,
        background = bg,
        shape = RoundedCornerShape(Radius.md),
        contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs),
        focusScale = if (enabled) FOCUS_SCALE else 1f,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}
