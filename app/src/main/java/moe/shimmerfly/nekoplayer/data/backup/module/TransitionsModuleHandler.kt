package moe.shimmerfly.nekoplayer.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import moe.shimmerfly.nekoplayer.data.backup.model.BackupSection
import moe.shimmerfly.nekoplayer.data.database.TransitionDao
import moe.shimmerfly.nekoplayer.data.database.TransitionRuleEntity
import moe.shimmerfly.nekoplayer.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transition rules, as an array of [TransitionRuleEntity] (the shape every version reads).
 *
 * Each pair rule also carries its songs' metadata under [FROM_SONG]/[TO_SONG]: rules name songs
 * by MediaStore id, which differs on another device, so without it a restore elsewhere left
 * every custom transition pointing at songs that aren't there. Older versions ignore the extra
 * fields, and backups without them restore their ids unchanged.
 */
@Singleton
class TransitionsModuleHandler @Inject constructor(
    private val transitionDao: TransitionDao,
    private val songResolver: BackupSongResolver,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.TRANSITIONS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val rules = transitionDao.getAllRulesOnce()
        val metadata = songResolver.metadataFor(rules.flatMap { listOfNotNull(it.fromTrackId, it.toTrackId) })
        val array = JsonArray()
        rules.forEach { rule ->
            val obj = gson.toJsonTree(rule).asJsonObject
            rule.fromTrackId?.let(metadata::get)?.let { obj.add(FROM_SONG, gson.toJsonTree(it)) }
            rule.toTrackId?.let(metadata::get)?.let { obj.add(TO_SONG, gson.toJsonTree(it)) }
            array.add(obj)
        }
        gson.toJson(array)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        transitionDao.getAllRulesOnce().size
    }

    // Rollback restores this device's own rules, so its ids need no matching.
    override suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        gson.toJson(transitionDao.getAllRulesOnce())
    }

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val array = JsonParser.parseString(payload).asJsonArray
        remapSongIds(array)
        val type = TypeToken.getParameterized(List::class.java, TransitionRuleEntity::class.java).type
        val rules: List<TransitionRuleEntity> = gson.fromJson(array, type)
        // Matching can fold two songs into one, or two rules onto the same pair.
        val valid = rules
            .filter { it.fromTrackId == null || it.fromTrackId != it.toTrackId }
            .distinctBy { Triple(it.playlistId, it.fromTrackId, it.toTrackId) }
        transitionDao.replaceAllRules(valid)
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    /**
     * Points each rule at this library's ids. A song the library can't match (not scanned yet,
     * or ambiguous) keeps its backup id: that is the old behaviour, and still right on the
     * device the backup came from.
     */
    private suspend fun remapSongIds(rules: JsonArray) {
        val objects = rules.filter { it.isJsonObject }.map { it.asJsonObject }
        if (objects.none { it.has(FROM_SONG) || it.has(TO_SONG) }) return
        val library = songResolver.library()
        objects.forEach { obj ->
            remap(obj, library, idField = "fromTrackId", songField = FROM_SONG)
            remap(obj, library, idField = "toTrackId", songField = TO_SONG)
        }
    }

    private fun remap(obj: JsonObject, library: BackupSongResolver.Library, idField: String, songField: String) {
        val meta = obj.get(songField)?.takeIf { it.isJsonObject }
            ?.let { runCatching { gson.fromJson(it, BackupSongMetadata::class.java) }.getOrNull() }
        obj.remove(songField)
        val id = obj.get(idField)?.takeIf { it.isJsonPrimitive }?.asString ?: return
        if (meta == null) return
        library.resolve(id, meta)?.let { obj.addProperty(idField, it) }
    }

    private companion object {
        const val FROM_SONG = "fromSong"
        const val TO_SONG = "toSong"
    }
}
