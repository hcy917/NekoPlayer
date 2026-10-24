package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.utils.PlaybackSpeedUtils

const val PLAYBACK_SPEED_SLIDER_TEST_TAG = "playback_speed_slider"

/**
 * Playback speed picker: a slider with one stop per 0.05×, ± buttons, presets and an
 * exact-value dialog. Every value it reports is normalized to hundredths. A drag only
 * reports once, when it ends, so the preference is not rewritten on every frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedControls(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val locale = LocalConfiguration.current.locales[0]
    val colors = MaterialTheme.colorScheme
    // The last value reported through onSpeedChange that [speed] has not echoed back yet.
    // Each report is its own preference write, so echoes of older writes can still arrive
    // after a newer pick; while this is set they are ignored instead of rewinding the controls.
    var pending by remember { mutableStateOf<Float?>(null) }
    // The snapped slider position while a drag is in progress.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var showInputDialog by rememberSaveable { mutableStateOf(false) }
    val currentSpeed by rememberUpdatedState(speed)

    LaunchedEffect(speed) {
        val awaited = pending
        if (awaited != null && PlaybackSpeedUtils.matches(speed, awaited)) pending = null
    }

    // What the label, slider and chips show. A change made elsewhere, such as from the queue
    // sheet, shows straight away when nothing is in flight.
    val draft = dragValue ?: pending ?: PlaybackSpeedUtils.normalize(speed)

    // Read when a click is handled rather than captured in [draft]: two quick taps can both land
    // before the next recomposition, and stepping from the captured value would drop one of them.
    fun latestSpeed(): Float = dragValue ?: pending ?: PlaybackSpeedUtils.normalize(currentSpeed)

    val commit: (Float) -> Unit = { value ->
        val normalized = PlaybackSpeedUtils.normalize(value)
        // Compared with the newest pick rather than [speed], which may still be an older value.
        if (!PlaybackSpeedUtils.matches(normalized, pending ?: currentSpeed)) {
            pending = normalized
            onSpeedChange(normalized)
        }
    }

    val title = stringResource(R.string.setcat_playback_speed)
    val valueText = stringResource(
        R.string.playback_speed_value,
        PlaybackSpeedUtils.formatFixed(draft, locale)
    )
    val customActionLabel = stringResource(R.string.playback_speed_custom_action)
    val sliderStateDescription = stringResource(
        R.string.playback_speed_state_description,
        PlaybackSpeedUtils.formatCompact(draft, locale)
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                // Long single-word titles such as "Wiedergabegeschwindigkeit" break at a hyphen.
                style = titleStyle.copy(hyphens = Hyphens.Auto),
                color = colors.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = { showInputDialog = true },
                modifier = Modifier.semantics {
                    onClick(label = customActionLabel) {
                        showInputDialog = true
                        true
                    }
                    liveRegion = LiveRegionMode.Polite
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = { commit(PlaybackSpeedUtils.stepBy(latestSpeed(), -1)) },
                enabled = PlaybackSpeedUtils.canStep(draft, -1)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.playback_speed_decrease)
                )
            }
            Slider(
                value = draft,
                onValueChange = { dragValue = PlaybackSpeedUtils.snapToFineStep(it) },
                onValueChangeFinished = {
                    dragValue?.let(commit)
                    dragValue = null
                },
                valueRange = PlaybackSpeedUtils.MIN_SPEED..PlaybackSpeedUtils.MAX_SPEED,
                // One stop per 0.05×, so TalkBack and keyboard increments move exactly one step.
                steps = PlaybackSpeedUtils.SLIDER_STEPS,
                // 54 tick dots would only add noise to the track.
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        drawTick = { _, _ -> }
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(PLAYBACK_SPEED_SLIDER_TEST_TAG)
                    .semantics {
                        contentDescription = title
                        stateDescription = sliderStateDescription
                    }
            )
            FilledTonalIconButton(
                onClick = { commit(PlaybackSpeedUtils.stepBy(latestSpeed(), 1)) },
                enabled = PlaybackSpeedUtils.canStep(draft, 1)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.playback_speed_increase)
                )
            }
        }

        Text(
            text = stringResource(R.string.playback_speed_presets),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlaybackSpeedUtils.PRESETS.forEach { preset ->
                FilterChip(
                    selected = PlaybackSpeedUtils.matches(draft, preset),
                    onClick = { commit(preset) },
                    label = {
                        Text(
                            stringResource(
                                R.string.playback_speed_value,
                                PlaybackSpeedUtils.formatCompact(preset, locale)
                            )
                        )
                    }
                )
            }
        }

        Text(
            text = stringResource(R.string.playback_speed_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }

    if (showInputDialog) {
        PlaybackSpeedInputDialog(
            initialSpeed = draft,
            onConfirm = { value ->
                showInputDialog = false
                commit(value)
            },
            onDismiss = { showInputDialog = false }
        )
    }
}

/** Lets the user type an exact speed to 0.01×, with a comma or a dot as the separator. */
@Composable
fun PlaybackSpeedInputDialog(
    initialSpeed: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = PlaybackSpeedUtils.formatFixed(initialSpeed, locale)
        // Selected so typing replaces the current value straight away.
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(0, text.length)))
    }
    val parsed = PlaybackSpeedUtils.parse(fieldValue.text)
    val isError = fieldValue.text.isNotBlank() && parsed == null
    val minText = PlaybackSpeedUtils.formatCompact(PlaybackSpeedUtils.MIN_SPEED, locale)
    val maxText = PlaybackSpeedUtils.formatCompact(PlaybackSpeedUtils.MAX_SPEED, locale)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Wait a frame so the dialog window exists before asking the IME to show.
        withFrameNanos { }
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_speed_custom_title)) },
        text = {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { candidate ->
                    if (PlaybackSpeedUtils.isEditableInput(candidate.text)) fieldValue = candidate
                },
                label = { Text(stringResource(R.string.playback_speed_custom_field_label)) },
                suffix = { Text(stringResource(R.string.playback_speed_multiplier_suffix)) },
                supportingText = {
                    Text(
                        if (isError) {
                            stringResource(R.string.playback_speed_custom_error, minText, maxText)
                        } else {
                            stringResource(R.string.playback_speed_custom_supporting, minText, maxText)
                        }
                    )
                },
                isError = isError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
