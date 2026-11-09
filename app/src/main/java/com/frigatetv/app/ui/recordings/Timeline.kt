package com.frigatetv.app.ui.recordings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.ui.Accent
import com.frigatetv.app.ui.FocusSurface
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.Motion
import com.frigatetv.app.ui.NumericStyle
import com.frigatetv.app.ui.Radius
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.Warn
import kotlinx.coroutines.flow.collectLatest

internal const val CURSOR_STEP_SECONDS = 300.0
private const val CURSOR_FAST_STEP_SECONDS = 900.0
private const val FAST_AFTER_REPEATS = 6
internal const val LABEL_EVERY_HOURS = 3

internal val CLOCK_STYLE = NumericStyle.merge(TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 58.sp))
internal val NUMERIC_SPAN = SpanStyle(fontFeatureSettings = NumericStyle.fontFeatureSettings)
private val HOUR_LABEL_STYLE = NumericStyle.merge(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextSecondary))
private val HOUR_LABELS = (0..24 step LABEL_EVERY_HOURS).map { "%02d:00".format(it % 24) }

private val SegmentColor = Accent
private val TrackColor = Color.White.copy(alpha = 0.06f)
private val TickColor = Color.White.copy(alpha = 0.14f)
private val MajorTickColor = Color.White.copy(alpha = 0.28f)
private val HourHighlight = Accent.copy(alpha = 0.10f)
private val FutureShade = Color.Black.copy(alpha = 0.35f)

/**
 * The whole day drawn on one Canvas: hour grid, merged recording ranges, event markers, the focused
 * hour and the cursor. The cursor animates through an [Animatable] read only in the draw phase,
 * so a key press triggers a redraw, not a recomposition.
 */
@Composable
internal fun Timeline(
    focusRequester: FocusRequester,
    dayStart: Double,
    nowEpoch: Double,
    model: DayModel,
    cursor: MutableDoubleState,
    onCursor: (Double) -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = remember(dayStart) { Animatable(((cursor.doubleValue - dayStart) / DAY_SECONDS).toFloat()) }
    LaunchedEffect(fraction) {
        snapshotFlow { ((cursor.doubleValue - dayStart) / DAY_SECONDS).toFloat() }
            .collectLatest { fraction.animateTo(it, tween(Motion.FocusMs)) }
    }
    val measurer = rememberTextMeasurer(cacheSize = HOUR_LABELS.size)
    val labels: List<TextLayoutResult> = remember(measurer) { HOUR_LABELS.map { measurer.measure(it, HOUR_LABEL_STYLE) } }
    val segFractions = remember(model, dayStart) {
        FloatArray(model.starts.size * 2) { i ->
            val t = if (i % 2 == 0) model.starts[i / 2] else model.ends[i / 2]
            ((t - dayStart) / DAY_SECONDS).toFloat()
        }
    }
    val eventFractions = remember(model, dayStart) {
        FloatArray(model.eventStarts.size) { ((model.eventStarts[it] - dayStart) / DAY_SECONDS).toFloat() }
    }
    val nowFraction = ((nowEpoch - dayStart) / DAY_SECONDS).toFloat()

    FocusSurface(
        onClick = onPlay,
        background = Ink,
        shape = RoundedCornerShape(Radius.md),
        focusRequester = focusRequester,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = if (event.nativeKeyEvent.repeatCount >= FAST_AFTER_REPEATS) CURSOR_FAST_STEP_SECONDS else CURSOR_STEP_SECONDS
                when (event.key) {
                    Key.DirectionLeft -> {
                        onCursor(cursor.doubleValue - step)
                        true
                    }
                    Key.DirectionRight -> {
                        onCursor(cursor.doubleValue + step)
                        true
                    }
                    else -> false
                }
            },
    ) { focused ->
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.md, vertical = Space.sm)
                .pointerInput(dayStart) {
                    detectTapGestures { offset -> onCursor(dayStart + offset.x / size.width * DAY_SECONDS) }
                },
        ) {
            drawTimeline(
                segFractions = segFractions,
                eventFractions = eventFractions,
                nowFraction = nowFraction,
                cursorFraction = fraction.value,
                labels = labels,
                focused = focused,
            )
        }
    }
}

private fun DrawScope.drawTimeline(
    segFractions: FloatArray,
    eventFractions: FloatArray,
    nowFraction: Float,
    cursorFraction: Float,
    labels: List<TextLayoutResult>,
    focused: Boolean,
) {
    val w = size.width
    val labelH = labels.first().size.height.toFloat()
    val markerZone = 14.dp.toPx()
    val trackTop = markerZone + 4.dp.toPx()
    val trackBottom = size.height - labelH - 8.dp.toPx()
    val trackH = (trackBottom - trackTop).coerceAtLeast(8.dp.toPx())
    val hourW = w / 24f
    val radius = CornerRadius(6.dp.toPx())

    // Focused hour column.
    val hour = (cursorFraction * 24f).toInt().coerceIn(0, 23)
    drawRoundRect(
        color = if (focused) HourHighlight else HourHighlight.copy(alpha = 0.05f),
        topLeft = Offset(hour * hourW, trackTop - 2.dp.toPx()),
        size = Size(hourW, trackH + 4.dp.toPx()),
        cornerRadius = radius,
    )

    // Track and hour grid.
    drawRoundRect(TrackColor, Offset(0f, trackTop), Size(w, trackH), radius)
    val tickW = 1.dp.toPx()
    for (h in 0..24) {
        val x = (h * hourW).coerceIn(0f, w - tickW)
        val major = h % LABEL_EVERY_HOURS == 0
        drawRect(
            color = if (major) MajorTickColor else TickColor,
            topLeft = Offset(x, if (major) trackTop else trackBottom - 6.dp.toPx()),
            size = Size(tickW, if (major) trackH + 4.dp.toPx() else 6.dp.toPx()),
        )
    }
    labels.forEachIndexed { i, label ->
        val x = i * LABEL_EVERY_HOURS * hourW - label.size.width / 2f
        drawText(label, topLeft = Offset(x.coerceIn(0f, w - label.size.width), size.height - labelH))
    }

    // Recorded ranges.
    val minW = 2.dp.toPx()
    val segAlpha = if (focused) 1f else 0.8f
    val inset = trackH * 0.18f
    var i = 0
    while (i < segFractions.size) {
        val x1 = segFractions[i] * w
        val x2 = maxOf(segFractions[i + 1] * w, x1 + minW)
        drawRoundRect(
            color = SegmentColor,
            alpha = segAlpha,
            topLeft = Offset(x1, trackTop + inset),
            size = Size(x2 - x1, trackH - inset * 2),
            cornerRadius = CornerRadius(minOf(radius.x, (x2 - x1) / 2)),
        )
        i += 2
    }

    // Future (today only).
    if (nowFraction in 0f..1f) {
        val x = nowFraction * w
        drawRect(FutureShade, Offset(x, trackTop), Size(w - x, trackH))
    }

    // Event markers.
    val dotR = 3.5.dp.toPx()
    for (f in eventFractions) {
        drawCircle(Warn, dotR, Offset(f * w, markerZone / 2))
    }

    // Cursor with a head.
    val cx = cursorFraction * w
    val cursorColor = if (focused) TextPrimary else TextSecondary
    drawRect(cursorColor, Offset(cx - 1.5.dp.toPx(), trackTop - 4.dp.toPx()), Size(3.dp.toPx(), trackH + 8.dp.toPx()))
    drawCircle(cursorColor, 5.dp.toPx(), Offset(cx, trackTop - 4.dp.toPx()))
}
