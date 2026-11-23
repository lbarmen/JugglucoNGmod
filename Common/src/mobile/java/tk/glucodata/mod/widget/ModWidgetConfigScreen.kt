package tk.glucodata.mod.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.util.ConnectedButtonGroup

/** JugglucoNGmod widget: the settings window's drawing. State and actions come from [ModWidgetConfigActivity]. */
@Composable
fun ModWidgetConfigScreen(state: ModWidgetConfigState, actions: ModWidgetConfigActions) {
    Scaffold(
        topBar = {
            AppTopBar(
                stringResource(R.string.mod_widget_settings_title),
                actions.onDone,
                actions = { TextButton(onClick = actions.onDone) { Text(stringResource(R.string.mod_widget_done)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        ) {
            if (state.widgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                Picker(state.choices, actions.onPick)
            } else {
                Preview(state)
                Settings(state.settings, actions.onChange)
            }
        }
    }
}

@Composable
private fun Picker(choices: List<ModWidgetChoice>, onPick: (Int) -> Unit) {
    SectionLabel(stringResource(R.string.mod_widget_pick))
    choices.forEachIndexed { i, c ->
        SettingsItem(
            title = stringResource(R.string.mod_widget_item, c.number),
            subtitle = stringResource(R.string.mod_widget_item_size, c.widthDp, c.heightDp),
            position = position(i, choices.size),
            onClick = { onPick(c.id) },
        )
    }
}

/** The widget at its real proportions over a wallpaper-like gradient, scaled down if wider than the window. */
@Composable
private fun Preview(state: ModWidgetConfigState) {
    SectionLabel(stringResource(R.string.mod_widget_preview), topPadding = 8.dp)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A6073), Color(0xFF9DB8A0), Color(0xFFE6D3A3))))
            .padding(vertical = 20.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = state.previewWidthDp.coerceAtLeast(1f)
            val h = state.previewHeightDp.coerceAtLeast(1f)
            val shownW = min(maxWidth, w.dp)
            val shownH = shownW * (h / w)
            val bitmap = state.preview
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(shownW, shownH),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(Modifier.size(shownW, shownH))
            }
        }
    }
}

@Composable
private fun Settings(s: ModWidgetSettings, onChange: (ModWidgetSettings) -> Unit) {
    SectionLabel(stringResource(R.string.mod_widget_section_look))
    Choice(stringResource(R.string.mod_widget_opt_transparency)) {
        ConnectedButtonGroup(
            options = ModWidgetSettings.TRANSPARENCY_CHOICES,
            selectedOption = s.backgroundTransparency,
            onOptionSelected = { onChange(s.copy(backgroundTransparency = it)) },
            label = { Text(stringResource(R.string.mod_widget_percent, it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Choice(stringResource(R.string.mod_widget_opt_period)) {
        ConnectedButtonGroup(
            options = ModWidgetSettings.PERIOD_CHOICES.sorted(),
            selectedOption = s.periodHours,
            onOptionSelected = { onChange(s.copy(periodHours = it)) },
            label = { Text(stringResource(R.string.mod_widget_hours, it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Choice(stringResource(R.string.mod_widget_opt_size)) {
        ConnectedButtonGroup(
            options = ModWidgetNumberSize.entries.toList(),
            selectedOption = s.numberSize,
            onOptionSelected = { onChange(s.copy(numberSize = it)) },
            label = { Text(sizeLabel(it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Radio(
        stringResource(R.string.mod_widget_opt_dots),
        ModWidgetDots.entries.toList(), s.dots, { dotsLabel(it) },
    ) { onChange(s.copy(dots = it)) }
    Radio(
        stringResource(R.string.mod_widget_opt_layout),
        ModWidgetLayout.entries.toList(), s.layout, { layoutLabel(it) },
    ) { onChange(s.copy(layout = it)) }

    SectionLabel(stringResource(R.string.mod_widget_section_show))
    SettingsSwitchItem(
        title = stringResource(R.string.mod_widget_opt_delta), checked = s.showDelta,
        onCheckedChange = { onChange(s.copy(showDelta = it)) }, position = CardPosition.TOP,
    )
    SettingsSwitchItem(
        title = stringResource(R.string.mod_widget_opt_age), checked = s.showAge,
        onCheckedChange = { onChange(s.copy(showAge = it)) }, position = CardPosition.MIDDLE,
    )
    SettingsSwitchItem(
        title = stringResource(R.string.mod_widget_opt_color), checked = s.colorByRange,
        onCheckedChange = { onChange(s.copy(colorByRange = it)) }, position = CardPosition.MIDDLE,
    )
    SettingsSwitchItem(
        title = stringResource(R.string.mod_widget_opt_lines), checked = s.rangeLines,
        onCheckedChange = { onChange(s.copy(rangeLines = it)) }, position = CardPosition.BOTTOM,
    )
}

@Composable
private fun Choice(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun <T> Radio(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onPick(option) })
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun sizeLabel(size: ModWidgetNumberSize): String = when (size) {
    ModWidgetNumberSize.AUTO -> stringResource(R.string.mod_widget_size_auto)
    ModWidgetNumberSize.S -> "S"
    ModWidgetNumberSize.M -> "M"
    ModWidgetNumberSize.L -> "L"
}

@Composable
private fun dotsLabel(dots: ModWidgetDots): String = stringResource(
    when (dots) {
        ModWidgetDots.AVERAGE_5MIN -> R.string.mod_widget_dots_average
        ModWidgetDots.EACH -> R.string.mod_widget_dots_each
        ModWidgetDots.LINE -> R.string.mod_widget_dots_line
    }
)

@Composable
private fun layoutLabel(layout: ModWidgetLayout): String = stringResource(
    when (layout) {
        ModWidgetLayout.OVERLAY -> R.string.mod_widget_layout_overlay
        ModWidgetLayout.TOP -> R.string.mod_widget_layout_top
    }
)

private fun position(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == count - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}
