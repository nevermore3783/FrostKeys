// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.LiquidGlass
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs

@Composable
fun LiquidGlassAdjustDialog(
    hazeState: HazeState,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.prefs()

    var activeProfile by remember {
        mutableStateOf(if (ResourceUtils.isNight(context.resources)) "dark" else "light")
    }
    var light by remember { mutableStateOf(LiquidGlass.readProfile(prefs, false)) }
    var dark by remember { mutableStateOf(LiquidGlass.readProfile(prefs, true)) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(LiquidGlass.PREF_ENABLED, false)) }
    var colorParam by remember { mutableStateOf<LiquidGlass.Param?>(null) }

    val current = if (activeProfile == "light") light else dark

    fun applyPreview(profile: LiquidGlass.Profile) {
        LiquidGlass.livePreview = profile
        LiquidGlass.notifyChanged()
        LatinIME.getInstance()?.requestFrostedLivePreviewRefresh()
    }

    fun update(param: LiquidGlass.Param, value: Int) {
        val updated = current.with(param, value)
        if (activeProfile == "light") light = updated else dark = updated
        applyPreview(updated)
    }

    // While the dialog is open the keyboard draws from these unsaved values. Nothing is written
    // to the preferences until "Save", so dismissing simply drops the preview.
    DisposableEffect(Unit) {
        applyPreview(current)
        onDispose {
            helium314.keyboard.settings.SettingsActivity.isTopBarHidden = false
            LiquidGlass.livePreview = null
            KeyboardTheme.themeOverride = null
            LiquidGlass.notifyChanged()
            LatinIME.getInstance()?.requestFrostedLivePreviewRefresh()
        }
    }

    BackHandler { onDismissRequest() }

    val testFieldState = rememberTextFieldState("", TextRange(0))
    val panelShape = MaterialTheme.shapes.extraLarge
    val surfaceColor = MaterialTheme.colorScheme.surface
    val panelTint = remember(surfaceColor) { surfaceColor.copy(alpha = 0.55f) }
    val dialogHazeStyle = remember(panelTint) { HazeStyle(blurRadius = 18.dp, tint = panelTint) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .systemBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .wrapContentHeight()
                    .padding(16.dp)
                    .clip(panelShape)
                    .then(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            Modifier
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .hazeChild(state = hazeState, shape = panelShape, style = dialogHazeStyle)
                        } else {
                            Modifier.background(surfaceColor)
                        }
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Column(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.liquid_glass_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = stringResource(R.string.liquid_glass_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.liquid_glass_enabled),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }

                        TabRow(
                            selectedTabIndex = if (activeProfile == "light") 0 else 1,
                            modifier = Modifier.padding(bottom = 8.dp),
                            containerColor = Color.Transparent,
                            divider = {}
                        ) {
                            Tab(
                                selected = activeProfile == "light",
                                onClick = {
                                    if (activeProfile != "light") {
                                        activeProfile = "light"
                                        KeyboardTheme.themeOverride = "light"
                                        applyPreview(light)
                                    }
                                },
                                text = { Text(stringResource(R.string.liquid_glass_profile_light)) }
                            )
                            Tab(
                                selected = activeProfile == "dark",
                                onClick = {
                                    if (activeProfile != "dark") {
                                        activeProfile = "dark"
                                        KeyboardTheme.themeOverride = "dark"
                                        applyPreview(dark)
                                    }
                                },
                                text = { Text(stringResource(R.string.liquid_glass_profile_dark)) }
                            )
                        }

                        OutlinedTextField(
                            state = testFieldState,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            placeholder = { Text(stringResource(R.string.frosted_glass_test_input_title)) },
                            lineLimits = TextFieldLineLimits.SingleLine
                        )

                        LiquidGlass.Param.entries.forEach { param ->
                            when {
                                param.isColor -> ColorRow(param, current[param]) { colorParam = param }
                                param == LiquidGlass.Param.BLEND_MODE ->
                                    BlendModeRow(current[param]) { update(param, it) }
                                else -> SliderRow(param, current[param]) { update(param, it) }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 1.dp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val reset = LiquidGlass.defaultProfile(activeProfile == "dark")
                                if (activeProfile == "light") light = reset else dark = reset
                                applyPreview(reset)
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(stringResource(R.string.button_reset), fontWeight = FontWeight.Medium)
                        }
                        FilledTonalButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(stringResource(android.R.string.cancel), fontWeight = FontWeight.Medium)
                        }
                        Button(
                            onClick = {
                                LiquidGlass.writeProfile(prefs, false, light)
                                LiquidGlass.writeProfile(prefs, true, dark)
                                prefs.edit { putBoolean(LiquidGlass.PREF_ENABLED, enabled) }
                                LiquidGlass.livePreview = null
                                KeyboardTheme.themeOverride = null
                                LiquidGlass.notifyChanged()
                                onDismissRequest()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(percent = 50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    colorParam?.let { param ->
        ColorPickerDialog(
            onDismissRequest = { colorParam = null },
            initialColor = current[param],
            title = stringResource(paramTitle(param)),
            showDefault = true,
            onDefault = { update(param, param.defaultValue(activeProfile == "dark")) },
            onConfirmed = { update(param, it) }
        )
    }
}

@Composable
private fun SliderRow(param: LiquidGlass.Param, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = "${stringResource(paramTitle(param))}: ${displayValue(param, value)}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(paramSummary(param)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = param.min.toFloat()..param.max.toFloat()
        )
    }
}

@Composable
private fun ColorRow(param: LiquidGlass.Param, value: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(paramTitle(param)), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(paramSummary(param)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(value or (0xFF shl 24)))
        )
    }
}

@Composable
private fun BlendModeRow(value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.liquid_glass_blend_mode),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            stringResource(R.string.liquid_glass_blend_mode_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                LiquidGlass.BLEND_NORMAL to R.string.liquid_glass_blend_normal,
                LiquidGlass.BLEND_SCREEN to R.string.liquid_glass_blend_screen,
                LiquidGlass.BLEND_PLUS to R.string.liquid_glass_blend_plus
            ).forEach { (mode, label) ->
                if (mode == value) {
                    Button(
                        onClick = { onChange(mode) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(percent = 50)
                    ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) }
                } else {
                    OutlinedButton(
                        onClick = { onChange(mode) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(percent = 50)
                    ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

private fun displayValue(param: LiquidGlass.Param, value: Int): String = when (param) {
    LiquidGlass.Param.RIM_THICKNESS -> "${value / 10}.${value % 10} dp"
    LiquidGlass.Param.LIGHT_ANGLE, LiquidGlass.Param.SHEEN_ANGLE -> "$value°"
    else -> "$value%"
}

private fun paramTitle(param: LiquidGlass.Param): Int = when (param) {
    LiquidGlass.Param.RIM_INTENSITY -> R.string.liquid_rim_intensity
    LiquidGlass.Param.RIM_THICKNESS -> R.string.liquid_rim_thickness
    LiquidGlass.Param.RIM_SOFTNESS -> R.string.liquid_rim_softness
    LiquidGlass.Param.LIGHT_ANGLE -> R.string.liquid_light_angle
    LiquidGlass.Param.RIM_SPREAD -> R.string.liquid_rim_spread
    LiquidGlass.Param.RIM_COLOR -> R.string.liquid_rim_color
    LiquidGlass.Param.COUNTER_RIM -> R.string.liquid_counter_rim
    LiquidGlass.Param.SIDE_FALLOFF -> R.string.liquid_side_falloff
    LiquidGlass.Param.LENS_INTENSITY -> R.string.liquid_lens_intensity
    LiquidGlass.Param.LENS_DEPTH -> R.string.liquid_lens_depth
    LiquidGlass.Param.EDGE_SHADOW -> R.string.liquid_edge_shadow
    LiquidGlass.Param.SHEEN_INTENSITY -> R.string.liquid_sheen_intensity
    LiquidGlass.Param.SHEEN_HEIGHT -> R.string.liquid_sheen_height
    LiquidGlass.Param.SHEEN_ANGLE -> R.string.liquid_sheen_angle
    LiquidGlass.Param.TINT_COLOR -> R.string.liquid_tint_color
    LiquidGlass.Param.TINT_OPACITY -> R.string.liquid_tint_opacity
    LiquidGlass.Param.BLEND_MODE -> R.string.liquid_glass_blend_mode
}

private fun paramSummary(param: LiquidGlass.Param): Int = when (param) {
    LiquidGlass.Param.RIM_INTENSITY -> R.string.liquid_rim_intensity_summary
    LiquidGlass.Param.RIM_THICKNESS -> R.string.liquid_rim_thickness_summary
    LiquidGlass.Param.RIM_SOFTNESS -> R.string.liquid_rim_softness_summary
    LiquidGlass.Param.LIGHT_ANGLE -> R.string.liquid_light_angle_summary
    LiquidGlass.Param.RIM_SPREAD -> R.string.liquid_rim_spread_summary
    LiquidGlass.Param.RIM_COLOR -> R.string.liquid_rim_color_summary
    LiquidGlass.Param.COUNTER_RIM -> R.string.liquid_counter_rim_summary
    LiquidGlass.Param.SIDE_FALLOFF -> R.string.liquid_side_falloff_summary
    LiquidGlass.Param.LENS_INTENSITY -> R.string.liquid_lens_intensity_summary
    LiquidGlass.Param.LENS_DEPTH -> R.string.liquid_lens_depth_summary
    LiquidGlass.Param.EDGE_SHADOW -> R.string.liquid_edge_shadow_summary
    LiquidGlass.Param.SHEEN_INTENSITY -> R.string.liquid_sheen_intensity_summary
    LiquidGlass.Param.SHEEN_HEIGHT -> R.string.liquid_sheen_height_summary
    LiquidGlass.Param.SHEEN_ANGLE -> R.string.liquid_sheen_angle_summary
    LiquidGlass.Param.TINT_COLOR -> R.string.liquid_tint_color_summary
    LiquidGlass.Param.TINT_OPACITY -> R.string.liquid_tint_opacity_summary
    LiquidGlass.Param.BLEND_MODE -> R.string.liquid_glass_blend_mode_summary
}
