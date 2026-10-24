package moe.shimmerfly.nekoplayer.utils

import android.content.Context
import android.provider.MediaStore

/**
 * Reads the current MediaStore generation of every external volume. The generation grows on
 * each MediaStore change, so comparing two snapshots tells whether anything changed in between,
 * even while no ContentObserver was registered. Makes binder calls: never call it on the main
 * thread. A volume whose generation can't be read is left out.
 */
internal fun readExternalMediaStoreGenerations(context: Context): Map<String, Long> =
    runCatching { MediaStore.getExternalVolumeNames(context) }
        .getOrDefault(emptySet())
        .mapNotNull { volume ->
            runCatching { volume to MediaStore.getGeneration(context, volume) }.getOrNull()
        }
        .toMap()

/**
 * True when [after] differs from [before]. A lower value (MediaStore was reset) and an added or
 * removed volume count as changes. Without a [before] snapshot, or when [after] could not be read
 * at all, nothing is known to have changed.
 */
internal fun mediaStoreGenerationsChanged(
    before: Map<String, Long>?,
    after: Map<String, Long>
): Boolean = before != null && after.isNotEmpty() && before != after
