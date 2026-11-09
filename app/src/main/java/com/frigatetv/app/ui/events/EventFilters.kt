package com.frigatetv.app.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frigatetv.app.ui.Accent
import com.frigatetv.app.ui.FocusSurface
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.PanelHi
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary

/** A small caption followed by its chips on one line. */
@Composable
internal fun FilterGroup(title: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = Space.xxs),
        )
        content()
    }
}

private val ChipShape = RoundedCornerShape(50)

/** Compact pill filter. Selected chips fill with the accent; focus adds the shared ring. */
@Composable
internal fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        background = if (selected) Accent else PanelHi,
        shape = ChipShape,
        contentPadding = PaddingValues(horizontal = Space.sm, vertical = 6.dp),
        // Hug the label: never stretched or squeezed by the parent row.
        modifier = Modifier.width(IntrinsicSize.Max).heightIn(min = 40.dp),
    ) { _ ->
        Text(
            text,
            color = if (selected) Ink else TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}
