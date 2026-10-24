package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.data.playlist.nlp.MixMoment
import moe.shimmerfly.nekoplayer.data.playlist.nlp.MixSuggestion
import moe.shimmerfly.nekoplayer.data.playlist.nlp.SmartMixRequest
import moe.shimmerfly.nekoplayer.presentation.components.subcomps.PlayingEqIcon
import moe.shimmerfly.nekoplayer.presentation.viewmodel.MixPreview
import moe.shimmerfly.nekoplayer.ui.theme.RoundedSans
import kotlin.math.roundToInt

private enum class MixComposerPage { Request, Refine, Preview }

/** A suggestion as the composer shows it; [label] is also the text a tap puts in the request. */
private data class MixSuggestionPill(
    val key: String,
    val label: String,
    val description: String?,
    val icon: ImageVector,
    val polygon: RoundedPolygon,
)

private val PromptMinHeight = 112.dp
private val PromptActionWidth = 68.dp
/** M3 Expressive medium FAB. */
private val PreviewFabSize = 80.dp
private val PreviewBarHeight = 64.dp
private val RequestBottomPadding = 12.dp

/**
 * Shared composer, hosted in a floating card. Sending a request expands the card to the full
 * screen; it stays expanded through thinking and the preview, and settles back on Edit.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun MixComposerSheet(
    isDailyMix: Boolean,
    preview: MixPreview?,
    isGenerating: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onGenerate: (String, Int) -> Unit,
    onEditRequest: () -> Unit,
    onClearError: () -> Unit,
    onPlay: () -> Unit,
    onSave: (String) -> Unit,
    onUseForToday: () -> Unit,
    /** Rows of suggestions built from the library and habits (see MixSuggestionEngine). */
    suggestions: List<List<MixSuggestion>> = emptyList(),
) {
    var request by rememberSaveable(isDailyMix) { mutableStateOf("") }
    var selectedCount by rememberSaveable(isDailyMix) { mutableStateOf<Int?>(null) }
    var selectedEnergy by rememberSaveable(isDailyMix) { mutableStateOf<Int?>(null) }
    var refining by rememberSaveable(isDailyMix) { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var lastPreview by remember { mutableStateOf<MixPreview?>(null) }
    SideEffect { preview?.let { lastPreview = it } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptics = rememberMixHaptics()
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val palette = neuralMixPalette()
    val motion = remember { MotionScheme.expressive() }
    val requestLabel = stringResource(R.string.mix_request_label)
    val actionLabel = stringResource(R.string.mix_preview_action)
    val thinkingLabel = stringResource(R.string.mix_thinking)
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceContainerHighest,
        unfocusedContainerColor = palette.panel,
        disabledContainerColor = palette.panel,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .94f
    val page = when { preview != null -> MixComposerPage.Preview; refining -> MixComposerPage.Refine; else -> MixComposerPage.Request }
    val composerTitle = stringResource(if (isDailyMix) R.string.mix_tune_today else R.string.mix_title)
    val requestScroll = rememberScrollState()
    val refineScroll = rememberScrollState()
    val stage = remember { NeuralMixStage() }
    val stageVisibility by animateFloatAsState(if (keyboardVisible) 0f else 1f, motion.defaultEffectsSpec(), label = "mix_stage")

    // Full-screen expansion: from Send until Edit. The standard slow spatial spring keeps the
    // container from overshooting the screen edges; the expressive scheme stays on its controls.
    val expanded = isGenerating || preview != null
    val expansionState = animateFloatAsState(if (expanded) 1f else 0f,
        remember { MotionScheme.standard().slowSpatialSpec() }, label = "mix_expansion")
    val expansion = { expansionState.value }
    val expandedLayout by remember { derivedStateOf { expansionState.value > 0f } }
    // The request page's header and body-content heights, reported by its arrangements: a
    // collapse settles exactly onto the page's natural height, even when the keyboard (and so
    // the layout) changed while the card was expanded.
    val requestMetrics = remember { IntArray(2) }
    val bodyBottomPx = with(density) { RequestBottomPadding.roundToPx() }
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val navBottomDp = with(density) { navBottom.toDp() }

    val promptStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = RoundedSans)
    val placeholder = stringResource(R.string.mix_request_placeholder)
    // Seeded from the window so the first frame already uses (almost) the fitted size.
    val windowWidth = LocalWindowInfo.current.containerSize.width
    var promptArea by remember(windowWidth) {
        mutableStateOf(with(density) {
            IntSize((windowWidth - (32 + 40 + 4 + 32).dp.roundToPx() - PromptActionWidth.roundToPx()).coerceAtLeast(0),
                (PromptMinHeight - 32.dp).roundToPx())
        })
    }
    val promptFontSize = rememberFittedFontSize(request.ifEmpty { placeholder }, promptStyle, promptArea,
        max = 30.sp, min = promptStyle.fontSize)
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    // Expressive shape morph: the connected action becomes a full pill while pressed or thinking.
    val sendRound = sendPressed || isGenerating
    val sendInner by animateDpAsState(if (sendRound) PromptActionWidth / 2 else 8.dp, motion.fastSpatialSpec(), label = "mix_send_inner")
    val sendOuter by animateDpAsState(if (sendRound) PromptActionWidth / 2 else 26.dp, motion.fastSpatialSpec(), label = "mix_send_outer")
    LaunchedEffect(preview?.requestId) { preview?.let { playlistName = it.suggestedName } }
    val generate = {
        if (request.isNotBlank() && !isGenerating && !isSaving) {
            haptics.perform(MixHapticLevel.Strong)
            keyboard?.hide()
            focus.clearFocus()
            onGenerate(SmartMixRequest.prompt(request.trim(), selectedEnergy, selectedCount), selectedCount ?: 25)
        }
    }
    val editRequest = { haptics.perform(MixHapticLevel.Light); keyboard?.hide(); focus.clearFocus(); onEditRequest() }

    BottomFloatingCard(
        page = page,
        parentPage = if (page == MixComposerPage.Request) null else MixComposerPage.Request,
        pageOrder = { it.ordinal },
        title = composerTitle,
        dismissEnabled = !isSaving,
        // Full screen, the sheet no longer reads as a card: no swipe-down to close.
        swipeDismissEnabled = !expandedLayout,
        expansion = expansion,
        restingHeight = if (expanded) null else ({
            requestMetrics[0] + requestMetrics[1] + bodyBottomPx + (navBottom * expansion().coerceIn(0f, 1f)).roundToInt()
        }),
        onNavigateBack = {
            keyboard?.hide(); focus.clearFocus(); refining = false
            if (preview != null) onEditRequest()
        },
        onDismissRequest = onDismiss,
    ) { currentPage, back, close ->
        // Minimum constraints carry the expanding card's height down to the page column.
        Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
            NeuralMixAtmosphere(Modifier.matchParentSize(), strength = .75f)
            // The request page reserves a 128 dp stage, but its lights paint across the card.
            if (currentPage == MixComposerPage.Request) {
                NeuralMixThinking(stage, Modifier.matchParentSize(), active = isGenerating, visibility = { stageVisibility })
            }
            Column(Modifier.fillMaxWidth().then(if (expandedLayout) Modifier else Modifier.heightIn(max = maxHeight)),
                verticalArrangement = remember(currentPage) {
                    TopReportingHeader(if (currentPage == MixComposerPage.Request) requestMetrics else null)
                }) {
                Row(
                    Modifier.fillMaxWidth()
                        .then(LocalBottomFloatingCardDragModifier.current)
                        .expansionInsets(expansion, top = statusTop)
                        .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (currentPage != MixComposerPage.Request) {
                        IconButton({ haptics.perform(MixHapticLevel.Light); back() }, enabled = !isSaving, modifier = Modifier.background(palette.panel, RoundedCornerShape(50))) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mix_back), Modifier.size(22.dp))
                        }
                    } else NeuralMixEmblem()
                    Text(
                        if (currentPage == MixComposerPage.Refine) stringResource(R.string.mix_refine) else composerTitle,
                        Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                        fontFamily = RoundedSans, fontWeight = FontWeight.Medium,
                    )
                    IconButton(close, enabled = !isSaving, modifier = Modifier.background(palette.panel, RoundedCornerShape(50))) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.mix_close), Modifier.size(22.dp))
                    }
                }

                when (currentPage) {
                    MixComposerPage.Request -> {
                        Column(
                            // Expanded, the body fills the card; its content keeps clear of the
                            // navigation bar and the spare height gathers around the lights.
                            Modifier.weight(1f, fill = expandedLayout)
                                .expansionInsets(expansion, bottom = navBottom)
                                .verticalScroll(requestScroll).padding(bottom = RequestBottomPadding),
                            verticalArrangement = remember { SlackAroundFirst(16.dp, requestMetrics) { !keyboardVisible } },
                        ) {
                            AnimatedVisibility(!keyboardVisible,
                                enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                                exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(tween(220, easing = FastOutSlowInEasing))) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                    Spacer(Modifier.fillMaxWidth().height(128.dp).neuralMixStage(stage))
                                    Text(requestLabel, style = MaterialTheme.typography.headlineMedium, fontFamily = RoundedSans,
                                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() })
                                    AnimatedContent(isGenerating, transitionSpec = {
                                        fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
                                    }, label = "mix_status") { working ->
                                        Text(if (working) thinkingLabel else stringResource(if (isDailyMix) R.string.mix_daily_subtitle else R.string.mix_library_subtitle),
                                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                                    }
                                }
                            }
                            // The input determines the row height. Its connected action fills that
                            // height even for wrapped requests and larger system text.
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Short requests read large; the type steps down as the request grows.
                                val fittedStyle = promptStyle.copy(fontSize = promptFontSize, lineHeight = promptFontSize * 1.2f)
                                TextField(request, onValueChange = { request = it; onClearError() },
                                    modifier = Modifier.weight(1f).heightIn(min = PromptMinHeight)
                                        .onSizeChanged {
                                            // TextField pads its text by 16 dp on every side.
                                            val inset = with(density) { 32.dp.roundToPx() }
                                            promptArea = IntSize((it.width - inset).coerceAtLeast(0),
                                                with(density) { (PromptMinHeight - 32.dp).roundToPx() })
                                        }
                                        .semantics { contentDescription = requestLabel },
                                    placeholder = { Text(placeholder, style = fittedStyle) },
                                    maxLines = 5,
                                    shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 8.dp, bottomEnd = 8.dp),
                                    colors = fieldColors, textStyle = fittedStyle,
                                    enabled = !isGenerating,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { generate() }))
                                NeuralMixButton({ generate() }, enabled = request.isNotBlank() && !isGenerating, busy = isGenerating,
                                    interactionSource = sendInteraction,
                                    modifier = Modifier.width(PromptActionWidth).fillMaxHeight().semantics {
                                        contentDescription = actionLabel
                                        if (isGenerating) {
                                            stateDescription = thinkingLabel
                                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                                        }
                                    },
                                    shape = RoundedCornerShape(topStart = sendInner.coerceAtLeast(0.dp), bottomStart = sendInner.coerceAtLeast(0.dp),
                                        topEnd = sendOuter.coerceAtLeast(0.dp), bottomEnd = sendOuter.coerceAtLeast(0.dp)),
                                    contentPadding = PaddingValues(0.dp)) {
                                    AnimatedContent(isGenerating, transitionSpec = {
                                        (fadeIn(motion.fastEffectsSpec()) + scaleIn(initialScale = 0.6f)) togetherWith
                                            (fadeOut(motion.fastEffectsSpec()) + scaleOut(targetScale = 0.6f))
                                    }, label = "mix_prompt_action") { working ->
                                        if (working) {
                                            // Same waveform the playing song rows use, in the 28 dp the icon occupied.
                                            PlayingEqIcon(Modifier.size(28.dp), color = LocalContentColor.current,
                                                bars = 4, minHeightFraction = 0.3f, phaseDurationMillis = 1800)
                                        } else {
                                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(28.dp))
                                        }
                                    }
                                }
                            }
                            MixSuggestionRows(suggestions, request, visible = !keyboardVisible, enabled = !isGenerating,
                                onChoose = { haptics.perform(MixHapticLevel.Medium); request = it; onClearError() })
                            error?.let { Box(Modifier.padding(horizontal = 20.dp)) { MixError(it) } }
                            MixRefineRow(selectedCount, selectedEnergy, enabled = !isGenerating,
                                onClick = { haptics.perform(MixHapticLevel.Medium); keyboard?.hide(); focus.clearFocus(); refining = true })
                        }
                    }
                    MixComposerPage.Refine -> {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(refineScroll).padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(request.ifBlank { requestLabel }, style = MaterialTheme.typography.headlineSmall,
                                fontFamily = RoundedSans, modifier = Modifier.padding(vertical = 8.dp))
                            Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.mix_length), style = MaterialTheme.typography.titleMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(null, 15, 25, 50).forEach { count ->
                                            NeuralMixChoice(if (count == null) stringResource(R.string.mix_auto) else stringResource(R.string.mix_up_to, count),
                                                selectedCount == count, true, onClick = { haptics.perform(MixHapticLevel.Tick); selectedCount = count; onClearError() })
                                        }
                                    }
                                }
                            }
                            Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.mix_energy), style = MaterialTheme.typography.titleMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(null to R.string.mix_auto, 85 to R.string.mix_energy_calm, 120 to R.string.mix_energy_steady, 165 to R.string.mix_energy_energetic).forEach { (bpm, label) ->
                                            NeuralMixChoice(stringResource(label), selectedEnergy == bpm, true,
                                                onClick = { haptics.perform(MixHapticLevel.Tick); selectedEnergy = bpm; onClearError() })
                                        }
                                    }
                                }
                            }
                            Text(stringResource(R.string.mix_refine_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        NeuralMixButton({ haptics.perform(MixHapticLevel.Light); back() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(stringResource(R.string.mix_done), fontFamily = RoundedSans)
                        }
                    }
                    MixComposerPage.Preview -> {
                        // Keep the outgoing page's content alive while it transforms away.
                        val preview = preview ?: lastPreview
                        if (preview != null) {
                            // The list runs to the screen's bottom edge; the floating toolbar and the
                            // navigation bar sit over its tail, which content padding scrolls clear of.
                            Box(Modifier.weight(1f, fill = expandedLayout).fillMaxWidth()) {
                                MixPreviewList(preview, isDailyMix, isSaving, error, playlistName, fieldColors,
                                    onNameChange = { playlistName = it; onClearError() },
                                    onNameDone = { keyboard?.hide(); focus.clearFocus() },
                                    bottomPadding = PreviewFabSize + navBottomDp + 32.dp,
                                    modifier = Modifier.fillMaxSize())
                                MixPreviewActionBar(
                                    isDailyMix = isDailyMix,
                                    saved = preview.savedName != null,
                                    saving = isSaving,
                                    saveEnabled = !isSaving && preview.savedName == null && playlistName.isNotBlank(),
                                    onEdit = editRequest,
                                    onSave = { haptics.perform(MixHapticLevel.Strong); keyboard?.hide(); focus.clearFocus(); onSave(playlistName) },
                                    onPrimary = { haptics.perform(MixHapticLevel.Strong); if (isDailyMix) onUseForToday() else onPlay() },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .padding(start = 16.dp, end = 16.dp, bottom = navBottomDp + 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Adds [top]/[bottom] pixels of inset scaled by the card's expansion, re-running only layout. */
private fun Modifier.expansionInsets(expansion: () -> Float, top: Int = 0, bottom: Int = 0) = layout { measurable, constraints ->
    val e = expansion().coerceIn(0f, 1f)
    val above = (top * e).roundToInt()
    val below = (bottom * e).roundToInt()
    val placeable = measurable.measure(constraints.offset(vertical = -(above + below)))
    layout(placeable.width, (placeable.height + above + below).coerceIn(constraints.minHeight, constraints.maxHeight)) {
        placeable.place(0, above)
    }
}

/** `Arrangement.Top` that also records the first child's (the header's) height in [metrics]. */
private class TopReportingHeader(private val metrics: IntArray?) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        metrics?.set(0, sizes.firstOrNull() ?: 0)
        var y = 0
        sizes.forEachIndexed { index, size -> outPositions[index] = y; y += size }
    }
}

/**
 * Spaced like `spacedBy`, but any spare height (from the expanding card) is split around the
 * first child: half above the lights, half between them and the prompt. Records the
 * content's natural height in `metrics[1]`.
 */
private class SlackAroundFirst(
    override val spacing: Dp,
    private val metrics: IntArray,
    private val hasHero: () -> Boolean,
) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        val used = sizes.sum() + gap * (sizes.size - 1).coerceAtLeast(0)
        metrics[1] = used
        val slack = (totalSize - used).coerceAtLeast(0)
        if (hasHero() && sizes.isNotEmpty()) {
            var y = slack / 2
            sizes.forEachIndexed { index, size ->
                outPositions[index] = y
                y += size + gap
                if (index == 0) y += slack - slack / 2
            }
        } else {
            var y = 0
            sizes.forEachIndexed { index, size ->
                outPositions[index] = y
                y += size + gap
            }
        }
    }
}

/** Icon, label with its two current values as tonal pills, and a chevron. No container. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MixRefineRow(selectedCount: Int?, selectedEnergy: Int?, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val length = selectedCount?.let { stringResource(R.string.mix_up_to, it) } ?: stringResource(R.string.mix_auto)
    val energy = stringResource(when (selectedEnergy) {
        85 -> R.string.mix_energy_calm; 120 -> R.string.mix_energy_steady; 165 -> R.string.mix_energy_energetic; else -> R.string.mix_auto
    })
    val summary = stringResource(R.string.mix_selection_summary, length, energy)
    // Anchored to the card's lower-left corner with the same 20 dp inset as the sides.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { stateDescription = summary }
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(remember { MixPolygonShape(MaterialShapes.Cookie4Sided) }).background(colors.tertiaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(22.dp), tint = colors.onTertiaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mix_refine), style = MaterialTheme.typography.titleSmall,
                fontFamily = RoundedSans, fontWeight = FontWeight.Medium, color = colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RefineValue(Icons.Rounded.FormatListNumbered, length)
                RefineValue(Icons.Rounded.Bolt, energy)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(end = 8.dp).size(22.dp), tint = colors.onSurface)
    }
}

@Composable
private fun RefineValue(icon: ImageVector, text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontFamily = RoundedSans, maxLines = 1)
        }
    }
}

@Composable
private fun MixPreviewList(
    preview: MixPreview, isDailyMix: Boolean, isSaving: Boolean, error: String?, playlistName: String,
    fieldColors: TextFieldColors, onNameChange: (String) -> Unit, onNameDone: () -> Unit, bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "summary") {
            // The summary keeps the page's 20 dp text inset; only the song rows run wider.
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(preview.request, style = MaterialTheme.typography.headlineMedium,
                    fontFamily = RoundedSans, fontWeight = FontWeight.Medium)
                val minutes = (preview.songs.sumOf { it.duration } / 60_000L).toInt()
                Surface(color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Text(pluralStringResource(R.plurals.mix_matching_songs, preview.songs.size, preview.songs.size, minutes),
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
                }
                if (preview.requestedCount != null && preview.songs.size < preview.requestedCount) {
                    Text(stringResource(R.string.mix_short_result), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (!isDailyMix && preview.savedName == null) {
                    TextField(playlistName, onValueChange = onNameChange,
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.mix_playlist_name)) },
                        shape = RoundedCornerShape(28.dp), colors = fieldColors, singleLine = true, enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onNameDone() }))
                }
                preview.savedName?.let { Text(stringResource(R.string.mix_saved, it), style = MaterialTheme.typography.bodyMedium, color = colors.primary) }
                error?.let { MixError(it) }
                Spacer(Modifier.height(4.dp))
            }
        }
        itemsIndexed(preview.songs, key = { _, song -> song.id }) { index, song ->
            val trackShape = RoundedCornerShape(
                topStart = if (index == 0) 24.dp else 8.dp, topEnd = if (index == 0) 24.dp else 8.dp,
                bottomStart = if (index == preview.songs.lastIndex) 24.dp else 8.dp,
                bottomEnd = if (index == preview.songs.lastIndex) 24.dp else 8.dp)
            Row(Modifier.fillMaxWidth().clip(trackShape).background(colors.surfaceContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OptimizedAlbumArt(song.albumArtUriString, song.title,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)), targetSize = coil.size.Size(156, 156))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.displayArtist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Floating action bar over the preview: one secondary-container pill holding Edit (a circular
 * icon action) and Save playlist (a full-width button that shows progress, then Saved), beside
 * the primary action (Play, or Use for today) as a medium mesh FAB. Full width, so Save always
 * has room for its label; the FAB is the one element that rises above the pill.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MixPreviewActionBar(
    isDailyMix: Boolean, saved: Boolean, saving: Boolean, saveEnabled: Boolean,
    onEdit: () -> Unit, onSave: () -> Unit, onPrimary: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val palette = neuralMixPalette()
    val motion = remember { MotionScheme.expressive() }
    val fabInteraction = remember { MutableInteractionSource() }
    val fabPressed by fabInteraction.collectIsPressedAsState()
    // Medium FAB corners (20 dp) round into a circle on press, on the expressive fast spatial spring.
    val fabCorner by animateDpAsState(if (fabPressed) PreviewFabSize / 2 else 20.dp, motion.fastSpatialSpec(), label = "mix_fab_shape")
    val fabShape = RoundedCornerShape(fabCorner.coerceAtLeast(0.dp))
    val primaryLabel = stringResource(if (isDailyMix) R.string.mix_use_today else R.string.mix_play)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.weight(1f).height(PreviewBarHeight),
            shape = CircleShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onEdit, enabled = !saving, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.onSecondaryContainer.copy(alpha = .10f),
                        contentColor = colors.onSecondaryContainer,
                        disabledContainerColor = colors.onSecondaryContainer.copy(alpha = .06f),
                        disabledContentColor = colors.onSecondaryContainer.copy(alpha = .38f),
                    )) {
                    Icon(Icons.Rounded.Edit, stringResource(R.string.mix_edit_request), Modifier.size(22.dp))
                }
                Button(onSave, enabled = saveEnabled, modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.secondary, contentColor = colors.onSecondary,
                        // Saved and saving stay legible rather than greying out.
                        disabledContainerColor = if (saved || saving) colors.secondary else colors.onSecondaryContainer.copy(alpha = .12f),
                        disabledContentColor = if (saved || saving) colors.onSecondary else colors.onSecondaryContainer.copy(alpha = .38f),
                    ),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp)) {
                    AnimatedContent(when { saving -> 0; saved -> 1; else -> 2 }, transitionSpec = {
                        fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                    }, label = "mix_save_icon") { stage ->
                        when (stage) {
                            0 -> CircularProgressIndicator(Modifier.size(18.dp), color = colors.onSecondary, strokeWidth = 2.dp)
                            1 -> Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                            else -> Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (saved) R.string.mix_saved_action else R.string.mix_save), fontFamily = RoundedSans,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // The mesh casts a glow in its own hue instead of a grey drop shadow.
        NeuralMixButton(onPrimary, enabled = !saving, interactionSource = fabInteraction,
            modifier = Modifier.size(PreviewFabSize)
                .shadow(10.dp, fabShape, ambientColor = palette.lights[0], spotColor = palette.lights[1])
                .semantics { contentDescription = primaryLabel },
            shape = fabShape, contentPadding = PaddingValues(0.dp)) {
            Icon(if (isDailyMix) Icons.Rounded.EventAvailable else Icons.Rounded.PlayArrow, null, Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MixSuggestionRows(
    suggestions: List<List<MixSuggestion>>, request: String, visible: Boolean, enabled: Boolean, onChoose: (String) -> Unit,
) {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val rows = remember(suggestions, configuration) {
        suggestions.map { row -> row.map { it.toPill(resources) } }.filter { it.isNotEmpty() }
    }
    val motion = remember { MotionScheme.expressive() }
    // Built off the main thread on open; the first time, the rows grow in rather than pop.
    // They also leave while the keyboard is up, the same way the hero above does, and return with it.
    AnimatedVisibility(visible && rows.isNotEmpty(),
        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
        exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(tween(220, easing = FastOutSlowInEasing))) {
        // Pills share one height and size to their labels; all rows start on the same line.
        StaggeredPillRows(rows, itemWidth = null, itemKey = { it.key },
            rowHeight = 48.dp, contentPadding = PaddingValues(horizontal = 20.dp)) { pill, modifier ->
            MixSuggestionChip(pill.label, pill.description, pill.icon, pill.polygon, request == pill.label, enabled,
                { onChoose(pill.label) }, modifier)
        }
    }
}

/** The request text a suggestion fills in, which is also its pill label. */
internal fun MixSuggestion.requestText(resources: android.content.res.Resources): String = when (this) {
    is MixSuggestion.Recent -> text
    is MixSuggestion.Artist -> name
    is MixSuggestion.Genre -> name
    is MixSuggestion.DecadeGenre -> resources.getStringArray(R.array.mix_decades).getOrNull((decade - 1950) / 10)
        ?.let { resources.getString(R.string.mix_suggest_decade_genre, it, genre) } ?: genre
    is MixSuggestion.Moment -> resources.getString(moment.label)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun MixSuggestion.toPill(resources: android.content.res.Resources): MixSuggestionPill {
    val label = requestText(resources)
    return when (this) {
        is MixSuggestion.Recent -> MixSuggestionPill(key, label, resources.getString(R.string.mix_suggest_recent, text),
            Icons.Rounded.History, MaterialShapes.Circle)
        is MixSuggestion.Artist -> if (rediscover) {
            MixSuggestionPill(key, label, resources.getString(R.string.mix_suggest_rediscover, name), Icons.Rounded.Explore, MaterialShapes.Cookie4Sided)
        } else {
            MixSuggestionPill(key, label, null, Icons.Rounded.Person, MaterialShapes.Circle)
        }
        is MixSuggestion.Genre -> MixSuggestionPill(key, label, null, Icons.Rounded.GraphicEq, MaterialShapes.PuffyDiamond)
        is MixSuggestion.DecadeGenre -> MixSuggestionPill(key, label, null, Icons.Rounded.Album, MaterialShapes.PuffyDiamond)
        is MixSuggestion.Moment -> MixSuggestionPill(key, label, null, moment.icon, MaterialShapes.Cookie4Sided)
    }
}

private val MixMoment.label: Int get() = when (this) {
    MixMoment.MORNING -> R.string.mix_moment_morning
    MixMoment.STUDY -> R.string.mix_moment_study
    MixMoment.WORKOUT -> R.string.mix_moment_workout
    MixMoment.FEEL_GOOD -> R.string.mix_moment_feel_good
    MixMoment.CHILL_EVENING -> R.string.mix_moment_chill_evening
    MixMoment.LATE_NIGHT -> R.string.mix_moment_late_night
    MixMoment.PARTY -> R.string.mix_moment_party
}

private val MixMoment.icon: ImageVector get() = when (this) {
    MixMoment.MORNING -> Icons.Rounded.WbSunny
    MixMoment.STUDY -> Icons.Rounded.Headphones
    MixMoment.WORKOUT -> Icons.AutoMirrored.Rounded.DirectionsRun
    MixMoment.FEEL_GOOD -> Icons.Rounded.Mood
    MixMoment.CHILL_EVENING -> Icons.Rounded.NightsStay
    MixMoment.LATE_NIGHT -> Icons.Rounded.Bedtime
    MixMoment.PARTY -> Icons.Rounded.Celebration
}

@Composable
private fun MixSuggestionChip(
    label: String, description: String?, icon: ImageVector, polygon: RoundedPolygon, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = remember(polygon) { MixPolygonShape(polygon) }
    val motion = remember { MotionScheme.expressive() }
    val container by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainer,
        motion.fastEffectsSpec(), label = "mix_suggestion_fill")
    val iconContainer by animateColorAsState(if (selected) colors.secondary else colors.surfaceContainerHighest,
        motion.fastEffectsSpec(), label = "mix_suggestion_icon")
    Surface(onClick, modifier = modifier.then(
        if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        enabled = enabled, shape = RoundedCornerShape(50), color = container) {
        Row(Modifier.fillMaxHeight().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(shape).background(iconContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (selected) colors.onSecondary else colors.onSurfaceVariant)
            }
            Text(label, Modifier.padding(end = 10.dp), style = MaterialTheme.typography.labelLarge,
                fontFamily = RoundedSans, color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun MixError(message: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Largest size (between [min] and [max]) at which [text] fits [area], like SongInfoBottomSheet's
 * AutoSizingTextToFill, but resolved synchronously for an editable field. Steps are eased.
 */
@Composable
private fun rememberFittedFontSize(text: String, style: TextStyle, area: IntSize, max: TextUnit, min: TextUnit): TextUnit {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val target = remember(text, style, area, max, min) {
        if (area.width <= 0 || area.height <= 0) return@remember null
        fun fits(size: Float) = !measurer.measure(text, style.copy(fontSize = size.sp, lineHeight = (size * 1.2f).sp),
            constraints = Constraints(maxWidth = area.width, maxHeight = area.height)).hasVisualOverflow
        if (fits(max.value)) return@remember max.value
        var low = min.value
        var high = max.value
        repeat(7) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        // Half-sp steps keep typing from nudging the size on every character.
        (kotlin.math.floor(low * 2) / 2).coerceAtLeast(min.value)
    }
    val size = remember { Animatable(0f) }
    val spec = remember { MotionScheme.expressive().defaultEffectsSpec<Float>() }
    LaunchedEffect(target) {
        val value = target ?: return@LaunchedEffect
        if (size.value == 0f) size.snapTo(value) else size.animateTo(value, spec)
    }
    return (if (size.value == 0f) target ?: max.value else size.value).sp
}
