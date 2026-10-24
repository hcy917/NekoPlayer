package moe.shimmerfly.nekoplayer.data.lx

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 一个可测试的音源 */
data class LxSourceOption(
    val key: String,
    val displayName: String,
    /** true = 由已安装的 JS 脚本提供。本版本只有 JS 音源，因此恒为 true（字段保留以兼容调用方） */
    val jsDriven: Boolean
)

/**
 * 音源连通性测试：拿一首样歌，向该音源真实取一次播放地址。
 *
 * ⚡ 本版本只有**已安装 JS 脚本**提供的音源（没有内置源）：
 *   落雪音源脚本多数只声明 `actions: ['musicUrl']`，不实现搜索，所以样歌只能来自脚本自身的搜索；
 *   脚本不支持搜索时无法取到样歌，测试会如实报告失败（不再用内置源搜索兜底）。
 *
 * 结果分为三档（[Status.SEARCH_ONLY] 仅保留给 UI 兼容，本版本不会产生）：
 * - [Status.SUCCESS]：取到播放地址
 * - [Status.SEARCH_ONLY]：仅搜索可用、未测取链（内置源专用状态，本版本不再产生）
 * - [Status.FAILED]：拿不到样歌，或取播放地址失败
 */
@Singleton
class LxSourceTester @Inject constructor(
    private val engine: LxJsEngine
) {
    enum class Status { SUCCESS, SEARCH_ONLY, FAILED }

    data class Result(
        val source: String,
        val status: Status,
        val latencyMs: Long,
        val message: String,
        val sample: String = ""
    )

    companion object {
        /** 测试用的固定搜索词（流行度高，各平台都有结果） */
        private const val TEST_KEYWORD = "周杰伦"

        private const val TEST_QUALITY = "128k"

        /** 测试前等待脚本引擎就绪的上限（含首次加载脚本） */
        private const val ENGINE_READY_TIMEOUT_MS = 15_000L
    }

    /**
     * 可选音源列表（只包含已安装 JS 脚本注册的音源）。
     *
     * @param installedOnly 保留以兼容调用方签名：本版本没有内置源可补，
     *                      两种取值返回的列表完全相同。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun availableSources(installedOnly: Boolean = false): List<LxSourceOption> {
        // 首次进入时引擎可能尚未加载脚本，先触发一次加载（无脚本时立即返回）
        runCatching { engine.awaitReady(ENGINE_READY_TIMEOUT_MS) }
        val jsSources = runCatching { engine.getSources() }.getOrDefault(emptyMap())
        val out = LinkedHashMap<String, LxSourceOption>()
        jsSources.forEach { (key, info) ->
            out[key] = LxSourceOption(
                key = key,
                // 脚本没声明 name 时退回音源 key（与音源标签的展示规则一致）
                displayName = info.name.ifBlank { key },
                jsDriven = true
            )
        }
        return out.values.toList()
    }

    /** 测试单个音源的连通性 */
    suspend fun test(source: String): Result = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt

        // ⚡ 脚本引擎需要一点时间加载脚本，"刚进页面就点测试"时常因 getSources() 还是空的
        //   而把已安装的音源判成不可用。这里先等引擎就绪再取音源清单。
        runCatching { engine.awaitReady(ENGINE_READY_TIMEOUT_MS) }
        val jsSources = runCatching { engine.getSources() }.getOrDefault(emptyMap())
        val viaJs = jsSources.containsKey(source)
        if (!viaJs) {
            return@withContext Result(
                source = source,
                status = Status.FAILED,
                latencyMs = elapsed(),
                message = "音源未注册（本版本只支持已安装 JS 脚本提供的音源）"
            )
        }

        // ⚡ 落雪音源脚本只声明 actions: ['musicUrl'] 时**不实现搜索**，对 musicSearch 一律
        //    reject('action not support')；这种脚本拿不到样歌，无法验证取链，只能如实报失败。
        val first = runCatching { engine.search(TEST_KEYWORD, source, page = 1, pagesize = 5) }
            .onFailure { Timber.w(it, "LxSourceTester: JS search failed for $source") }
            .getOrNull()
            ?.list
            ?.firstOrNull()
            ?: return@withContext Result(
                source = source,
                status = Status.FAILED,
                latencyMs = elapsed(),
                message = "脚本不支持搜索，无法取到样歌"
            )

        // ⚡ 音质不写死：各脚本声明的档位不同（有的只有 320k / flac / hires），
        //   写死 "128k" 会让本来可用的音源一律判成"取播放地址失败"
        val declaredQuality = jsSources[source]?.qualitys
            ?.firstOrNull { it.equals(TEST_QUALITY, ignoreCase = true) }
            ?: jsSources[source]?.qualitys?.firstOrNull()
            ?: TEST_QUALITY
        var playError: String? = null
        val playUrl = runCatching {
            // 与真实播放链路共用同一份 musicInfo 映射，避免"能播放但测试失败"
            engine.getPlayUrl(source, first.toLxMusicInfoMap(), declaredQuality)
        }.onFailure {
            playError = it.message ?: it.javaClass.simpleName
        }.getOrNull()

        return@withContext Result(
            source = source,
            status = if (!playUrl.isNullOrBlank()) Status.SUCCESS else Status.FAILED,
            latencyMs = elapsed(),
            message = if (!playUrl.isNullOrBlank()) {
                "可用"
            } else {
                buildString {
                    append("搜索可用，取播放地址失败")
                    append("（$declaredQuality）")
                    if (!playError.isNullOrBlank()) append("：$playError")
                }
            },
            sample = first.name
        )
    }
}
