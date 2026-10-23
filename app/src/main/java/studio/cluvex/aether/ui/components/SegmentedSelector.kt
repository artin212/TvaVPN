package studio.cluvex.aether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.Navy850
import studio.cluvex.aether.ui.theme.Navy700
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkDim
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * A single-choice control shown as a drop-down list.
 *
 * It replaces the grid of option cards that used to sit here (the name is kept so
 * every call site stays as it was). The field shows the current choice; tapping it
 * opens a list anchored under the field, and picking an item closes the list.
 *
 * For a screen reader the field is one "drop-down list" element that reads the
 * current choice, and every item in the open list is a radio button that says
 * whether it is the selected one.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (options.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Navy800)
                .border(0.5.dp, Navy700, shape)
                .clickable(enabled = enabled, role = Role.DropdownList, onClick = { open = true })
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label(selected),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary else OnDark.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = OnDarkDim,
                modifier = Modifier.size(28.dp),
            )
        }
        OptionMenu(
            expanded = open && enabled,
            onDismiss = { open = false },
            options = options,
            selected = selected,
            label = label,
            onSelect = onSelect,
        )
    }
}

/**
 * The open list of a drop-down: one item per option, the current one ticked.
 *
 * Shared by [SegmentedSelector] and the settings choice rows so every picker in
 * the app is the same control. Long lists (the exit-country picker has 56 items)
 * scroll inside the list.
 *
 * @param description optional second line under an option.
 */
@Composable
fun <T> OptionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: (@Composable (T) -> String?)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
            .background(Navy850)
            .heightIn(max = 360.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val detail = description?.invoke(option)
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else OnDark,
                        )
                        if (!detail.isNullOrBlank()) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnDarkMuted,
                            )
                        }
                    }
                },
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
                trailingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.RadioButton
                    semanticsSelected = isSelected
                },
            )
        }
    }
}
