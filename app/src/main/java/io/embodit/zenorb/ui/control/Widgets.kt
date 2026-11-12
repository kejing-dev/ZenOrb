package io.embodit.zenorb.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import androidx.compose.foundation.selection.toggleable
import com.pico.spatial.ui.design.Text
import io.embodit.zenorb.platform.TAG
import io.embodit.zenorb.ui.theme.ZenColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    minLines: Int = 1,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, minLines = minLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
            Box(Modifier.width(6.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 88.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) ZenColors.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) ZenColors.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (danger) ZenColors.OnError else PicoTheme.colorScheme.labelPrimary)
            Box(Modifier.width(6.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) ZenColors.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 88.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) ZenColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        }
        Box(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 112.dp, rowHeight: Dp = 56.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false, height: Dp = 64.dp) {
    // B19: the control itself is >= 48 dp tall (the default track is ~37 dp).
    SegmentControl(modifier = modifier.height(height)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = if (small) PicoTheme.typography.labelMedium else PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                },
            )
        }
    }
}

/** A whole >= 48 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (QA q1-4, D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit, rowHeight: Dp = 56.dp) {
    Row(
        Modifier.fillMaxWidth().height(rowHeight).clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A slider that snaps to [stops] positions (B19: >= 100 dp wide, 48 dp tall) with the current step's label on the right.
 * Used for speed / brightness / colour (5 stops) and volume (3 stops).
 */
@Composable
internal fun StepSlider(value: Int, stops: Int, label: String, tag: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier, labelWidth: Dp = 92.dp) {
    // design 6.1.9 Slider keeps the first onValueChange closure (pico-cli-feedback): read value / onChange through state
    val cur = androidx.compose.runtime.rememberUpdatedState(value)
    val cb = androidx.compose.runtime.rememberUpdatedState(onChange)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> val snapped = kotlin.math.round(v).toInt().coerceIn(0, stops - 1); if (snapped != cur.value) cb.value(snapped) },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..(stops - 1).toFloat(),
        )
        Box(Modifier.width(12.dp))
        Txt(label, "$tag/value", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(labelWidth))
    }
}
