package moe.shimmerfly.nekoplayer.presentation.viewmodel

import androidx.compose.runtime.Immutable
import moe.shimmerfly.nekoplayer.data.stats.PlaybackStatsRepository.PlaybackStatsSummary

/**
 * What the Home listening stats card shows. The card is always on Home, so "no history yet"
 * is its own state instead of hiding the card, which left Stats with no visible entry point.
 */
@Immutable
sealed interface HomeStatsOverviewState {
    /** The first summary has not been loaded yet. */
    data object Loading : HomeStatsOverviewState

    /** Nothing has been listened to in any of the home overview ranges. */
    data object Empty : HomeStatsOverviewState

    /** The shortest range with listening activity. */
    data class Ready(val summary: PlaybackStatsSummary) : HomeStatsOverviewState
}
