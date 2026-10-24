package moe.shimmerfly.nekoplayer.data.playlist.nlp

/** Only user-selected controls become constraints. An untouched energy slider is automatic. */
object SmartMixRequest {
    fun prompt(query: String, selectedEnergyBpm: Int? = null, selectedLength: Int? = null): String = buildString {
        append(query)
        selectedEnergyBpm?.let { append("\n[Target BPM: $it BPM]") }
        selectedLength?.let { append("\n[Target Length: $it songs]") }
    }
}
