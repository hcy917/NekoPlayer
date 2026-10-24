package moe.shimmerfly.nekoplayer.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import moe.shimmerfly.nekoplayer.data.lx.LxFileStore
import moe.shimmerfly.nekoplayer.data.lx.LxJsEngine
import moe.shimmerfly.nekoplayer.data.lx.LxSearchResult
import moe.shimmerfly.nekoplayer.data.lx.LxSongInfo
import moe.shimmerfly.nekoplayer.data.lx.LxArtistInfo
import moe.shimmerfly.nekoplayer.data.lx.LxPlaylistInfo
import moe.shimmerfly.nekoplayer.data.lx.LxPlaylistSearchResult
import moe.shimmerfly.nekoplayer.data.lx.LxScriptInfo
import moe.shimmerfly.nekoplayer.data.lx.LxSourceInfo
import moe.shimmerfly.nekoplayer.data.lx.LxSourceTester
import moe.shimmerfly.nekoplayer.data.lx.toLxMusicInfoMap
import moe.shimmerfly.nekoplayer.data.repository.MusicRepository
import timber.log.Timber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LxUiState(
    val engineReady: Boolean = false,
    val version: String = "unknown",
    val sources: Map<String, LxSourceInfo> = emptyMap(),
    /** 已导入的所有 JS 脚本简介列表（多 JS 管理用） */
    val scriptInfos: List<LxScriptInfo> = emptyList(),
    /** 在线音源播放音质值（来自音源脚本 qualitys，如 24bit / FLAC / 320k / 128k） */
    val musicQualityValue: String = "128k",
    val keyword: String = "",
    val selectedSource: String = "all",
    val searching: Boolean = false,
    val results: List<LxSongInfo> = emptyList(),
    val error: String? = null,
    val importError: String? = null,
    val initing: Boolean = false,
    val progress: Float? = null,
    val progressLabel: String? = null,
    /** 正在解析播放链接的歌曲 id（搜索页在歌曲名称右侧显示加载提示用） */
    val loadingSongId: String? = null,
    // ⚡ 分页相关字段
    val isEnd: Boolean = true,
    val isLoadingMore: Boolean = false,
    // ⚡ 歌手搜索相关字段
    val searchingArtists: Boolean = false,
    val artistResults: List<LxArtistInfo> = emptyList(),
    val artistIsEnd: Boolean = true,
    val artistError: String? = null,
    // ⚡ 歌单搜索相关字段
    val searchingPlaylists: Boolean = false,
    val playlistResults: List<LxPlaylistInfo> = emptyList(),
    val playlistIsEnd: Boolean = true,
    val playlistError: String? = null,
    /** 正在「保存到本地」的在线歌单 id（其列表项显示加载指示） */
    val savingPlaylistId: String? = null,
)

@HiltViewModel
class LxMusicViewModel @Inject constructor(
    app: Application,
    private val engine: LxJsEngine,
    private val store: LxFileStore,
    private val musicRepository: MusicRepository,
    private val playlistPreferencesRepository: moe.shimmerfly.nekoplayer.data.preferences.PlaylistPreferencesRepository,
    private val sourceTester: LxSourceTester,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(LxUiState())
    val uiState: StateFlow<LxUiState> = _uiState.asStateFlow()

    // ── 「已安装音源」连通性测试（音源设置页）──────────────────────────────
    /** 单个已安装音源的测试状态 */
    data class SourceTestUi(
        val key: String,
        val displayName: String,
        val testing: Boolean = false,
        val result: LxSourceTester.Result? = null
    )

    private val _sourceTests = MutableStateFlow<List<SourceTestUi>>(emptyList())
    val sourceTests: StateFlow<List<SourceTestUi>> = _sourceTests.asStateFlow()

    private val _testingAllSources = MutableStateFlow(false)
    val testingAllSources: StateFlow<Boolean> = _testingAllSources.asStateFlow()

    /** 刷新待测列表：只列**已安装** JS 脚本提供的音源（本版本没有内置源） */
    fun refreshSourceTestList() {
        viewModelScope.launch {
            val options = runCatching { sourceTester.availableSources(installedOnly = true) }
                .getOrDefault(emptyList())
            val previous = _sourceTests.value.associateBy { it.key }
            _sourceTests.value = options.map { option ->
                previous[option.key]?.copy(displayName = option.displayName)
                    ?: SourceTestUi(option.key, option.displayName)
            }
        }
    }

    fun testInstalledSource(key: String) {
        viewModelScope.launch {
            _sourceTests.update { rows ->
                rows.map { if (it.key == key) it.copy(testing = true) else it }
            }
            val result = runCatching { sourceTester.test(key) }.getOrNull()
            _sourceTests.update { rows ->
                rows.map { if (it.key == key) it.copy(testing = false, result = result) else it }
            }
        }
    }

    /** 全部测试：串行执行，避免同时打多个第三方接口触发风控 */
    fun testAllInstalledSources() {
        if (_testingAllSources.value) return
        viewModelScope.launch {
            _testingAllSources.value = true
            try {
                _sourceTests.value.map { it.key }.forEach { key ->
                    _sourceTests.update { rows ->
                        rows.map { if (it.key == key) it.copy(testing = true) else it }
                    }
                    val result = runCatching { sourceTester.test(key) }.getOrNull()
                    _sourceTests.update { rows ->
                        rows.map { if (it.key == key) it.copy(testing = false, result = result) else it }
                    }
                }
            } finally {
                _testingAllSources.value = false
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 注意：不要再清理"内置音源残留"——内置 JS 早已从 APK 移除，
            // 该清理名单（v4.1 / v9.3 等）与用户自己导入的文件同名，
            // 每次启动会把用户的音源插件删掉 → 直链解析失败。
            autoInitIfPresent()
        }
        // 说明：在线音源音质只保存在内存里（[LxUiState.musicQualityValue]），
        // 本版本没有可持久化的音质设置项，因此这里不再收集 DataStore flow。
    }

    var showImportUrl: Boolean = false
    var showInfo: Boolean = false

    var keyword: String
        get() = _uiState.value.keyword
        set(v) { _uiState.value = _uiState.value.copy(keyword = v) }

    var selectedSource: String
        get() = _uiState.value.selectedSource
        set(v) { _uiState.value = _uiState.value.copy(selectedSource = v) }

    fun refreshDisplayOnly() {
        val hasJs = store.hasAnyJs()
        _uiState.value = if (hasJs) {
            _uiState.value.copy(engineReady = false, sources = emptyMap(), version = "custom")
        } else {
            _uiState.value.copy(engineReady = false, sources = emptyMap(), version = "none", importError = null)
        }
    }

    /** 设置在线音源播放音质（仅内存生效：音源脚本的 qualitys 由 JS 引擎在取链时使用） */
    fun setMusicQuality(qualityValue: String) {
        // 只同步内存状态：本版本没有音质持久化设置项（UserPreferencesRepository 无对应字段）
        _uiState.value = _uiState.value.copy(musicQualityValue = qualityValue)
    }

    // ⚡ 音源临时开关（运行时生效，不持久化）：
    // sourceToggles: source -> enabled；UI 可通过 toggleSource 单独启用/禁用每个音源
    val sourceToggles: StateFlow<Map<String, Boolean>> = engine.sourceToggles

    /** 临时启用/禁用某个音源（禁用后该音源的搜索与播放链接获取都会跳过） */
    fun toggleSource(source: String, enabled: Boolean) {
        engine.setSourceEnabled(source, enabled)
    }

    /** 查询某个 JS 脚本注册了哪些音源（用于音源管理界面展示） */
    fun getInstanceSources(fileName: String): Map<String, LxSourceInfo> =
        engine.instanceSources(fileName)

    private suspend fun loadScriptInfos(): List<LxScriptInfo> =
        runCatching { engine.scriptInfos() }.getOrDefault(emptyList())

    fun ensureEngineStarted() {
        viewModelScope.launch(Dispatchers.IO) { 
            // 如果引擎已就绪，直接更新 UI 状态反映引擎当前状态
            if (engine.isReady()) {
                _uiState.value = _uiState.value.copy(
                    engineReady = true,
                    sources = runCatching { engine.getSources() }.getOrDefault(emptyMap()),
                    version = runCatching { engine.versionName() }.getOrDefault("custom"),
                    scriptInfos = loadScriptInfos(),
                    initing = false,
                    importError = null
                )
                return@launch
            }
            val hasJs = store.hasAnyJs()
            if (!hasJs) {
                _uiState.value = _uiState.value.copy(
                    initing = false, engineReady = false,
                    scriptInfos = loadScriptInfos(),
                    importError = "请先导入一个 JS 音源文件（点右上角 +）"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(initing = true, importError = null)
            val ok = runCatching { engine.ready() }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                initing = false,
                engineReady = ok,
                sources = if (ok) runCatching { engine.getSources() }.getOrDefault(emptyMap()) else emptyMap(),
                version = runCatching { engine.versionName() }.getOrDefault("custom"),
                scriptInfos = loadScriptInfos(),
                importError = if (!ok) engine.lastError ?: "JS 执行时报错" else null
            )
        }
    }

    fun autoInitIfPresent() {
        ensureEngineStarted()
    }

    fun importFromUri(uri: Uri) {
        _uiState.value = _uiState.value.copy(initing = true, importError = null, engineReady = false)
        viewModelScope.launch(Dispatchers.IO) { 
            val fileName = store.writeFromUri(uri)
            if (fileName == null) {
                _uiState.value = _uiState.value.copy(initing = false, importError = "读取文件失败")
                return@launch
            }
            val ready = engine.reload()
            _uiState.value = _uiState.value.copy(
                initing = false,
                engineReady = ready,
                sources = engine.getSources(),  
                version = engine.versionName(), 
                scriptInfos = loadScriptInfos(),
                importError = if (!ready) engine.lastError ?: "JS 执行时报错" else null
            )
        }
    }

    fun importFromUrl(url: String) {
        _uiState.value = _uiState.value.copy(initing = true, importError = null, engineReady = false)
        viewModelScope.launch(Dispatchers.IO) { 
            val fileName = store.writeFromUrl(url)
            if (fileName == null) {
                _uiState.value = _uiState.value.copy(initing = false, importError = "下载失败 (超时或非 JS)")
                return@launch
            }
            val ready = engine.reload()
            _uiState.value = _uiState.value.copy(
                initing = false,
                engineReady = ready,
                sources = engine.getSources(),  
                version = engine.versionName(), 
                scriptInfos = loadScriptInfos(),
                importError = if (!ready) engine.lastError ?: "JS 执行时报错" else null
            )
        }
    }

    /** 删除单个 JS 脚本文件并重新加载引擎 */
    fun removeJs(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteByName(fileName)
            val remaining = store.hasAnyJs()
            if (remaining) {
                val ok = engine.reload()
                _uiState.value = _uiState.value.copy(
                    initing = false,
                    engineReady = ok,
                    sources = engine.getSources(),
                    version = engine.versionName(),
                    scriptInfos = loadScriptInfos(),
                    importError = if (!ok) engine.lastError ?: "JS 重新加载失败" else null
                )
            } else {
                engine.close()
                _uiState.value = LxUiState(version = "none")
            }
        }
    }

    /** 删除全部 JS 脚本并关闭引擎 */
    fun removeAllJs() {
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteAll()
            engine.close()
            _uiState.value = LxUiState(version = "none")
        }
    }

    /**
     * ⚡ 导出全部 JS 音源：把所有已导入的脚本打包成一个 zip 写到 [target]
     * （位置由调用方通过 SAF 的文件选择器拿到）。
     */
    fun exportAllJs(target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = store.listFiles()
            if (files.isEmpty()) {
                _uiState.value = _uiState.value.copy(importError = "还没有导入任何 JS 音源")
                return@launch
            }
            _uiState.value = _uiState.value.copy(progress = 0f, progressLabel = "正在导出音源…")
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(target)?.use { out ->
                    java.util.zip.ZipOutputStream(out).use { zip ->
                        files.forEachIndexed { index, file ->
                            zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                            zip.write(file.readBytes())
                            zip.closeEntry()
                            _uiState.value = _uiState.value.copy(
                                progress = (index + 1).toFloat() / files.size
                            )
                        }
                    }
                } ?: error("无法写入所选位置")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    progress = null,
                    progressLabel = null,
                    importError = null,
                )
                Timber.tag("LxMusicViewModel").i("Exported %d JS sources", files.size)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    progress = null,
                    progressLabel = null,
                    importError = "导出失败：${error.message}",
                )
            }
        }
    }

    fun reloadEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(initing = true)
            val ok = engine.reload()
            _uiState.value = _uiState.value.copy(
                initing = false,
                engineReady = ok,
                sources = engine.getSources(),
                version = engine.versionName(),
                scriptInfos = loadScriptInfos(),
                importError = if (!ok) "JS 重新加载失败" else null
            )
        }
    }

    // ⚡ 分页状态
    private val _pageSize = 20
    private var _currentPage = 1
    private var _lastKeyword: String? = null
    /** 最近一次搜索实际使用的音源（供分页加载更多时保持同一音源） */
    private var _lastSource: String = "all"

    /** ⚡ 点击搜索结果后的「后台自动补拉后续页入队」任务（新点击/新搜索时取消，避免任务叠加） */
    private var _autoQueueJob: Job? = null

    companion object {
        /** 点击搜索结果后，后台最多自动补拉的页数（防止无限跑飞） */
        private const val AUTO_QUEUE_MAX_PAGES = 10
        /** 后台补拉相邻页之间的让步间隔，避免瞬时爆发网络请求 */
        private const val AUTO_QUEUE_PAGE_DELAY_MS = 250L
        /** 搜索页一次性排入播放队列的歌曲数上限（含点击歌曲自身），避免整页灌入造成卡顿 */
        const val AUTO_QUEUE_MAX_SONGS = 100

        /** 「全部」标签一次最多合并多少个音源，避免一次打太多接口。 */
        private const val MAX_ALL_SOURCES = 8
        /** 「保存到本地」不可用时的用户可见提示（本版本没有云端曲库） */
        private const val SAVE_REQUIRES_CLOUD_LIBRARY = "保存到本地需要云端曲库，本版本未启用"
        /** 在线歌单搜索不可用时的用户可见提示（本版本没有歌单接口） */
        private const val PLAYLIST_SEARCH_UNSUPPORTED = "本版本不支持在线歌单搜索（仅支持 JS 音源歌曲搜索）"
    }

    fun search(source: String? = null) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val effectiveSource = source ?: selectedSource
        // ⚡ 新搜索：取消上一次的后台自动补拉任务
        _autoQueueJob?.cancel()
        _autoQueueJob = null
        // ⚡ 新搜索重置分页状态
        _currentPage = 1
        _lastKeyword = kw
        _lastSource = effectiveSource
        _uiState.value = _uiState.value.copy(
            searching = true,
            error = null,
            isEnd = false,
            isLoadingMore = false,
            results = emptyList()
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 仅落雪 JS 音源需要引擎就绪：本版本没有内置源可走，全部经过 JS 引擎
                if (!engine.isReady()) {
                    if (!store.hasAnyJs()) {
                        _uiState.value = _uiState.value.copy(
                            searching = false,
                            error = "请先在设置中导入 JS 音源"
                        )
                        return@launch
                    }
                    engine.ready()
                }
                // 选中落雪音源时按落雪方案走 JS 引擎搜索；网易云内置搜索已不在本版本中。
                val result = searchBySource(effectiveSource, kw, page = 1, pageSize = _pageSize)
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    results = result.list,
                    isEnd = result.isEnd,
                    error = if (result.list.isEmpty()) "无结果（请换关键词）" else null
                )
                // 搜索 API 不返回 picUrl，批量补充封面
                fillMissingCovers(result.list)
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    results = emptyList(),
                    isEnd = true,
                    error = "搜索失败: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * ⚡ 加载下一页搜索结果（无限滚动）
     * - 追加到现有结果列表
     * - 仅在非搜索中、非最后一页时生效
     * - 自动切换 isLoadingMore 状态
     */
    fun loadMore() {
        val kw = _lastKeyword?.trim() ?: keyword.trim()
        if (kw.isBlank()) return
        // 防重复：正在搜索/加载更多时不触发；已到最后一页时不触发
        if (_uiState.value.searching || _uiState.value.isLoadingMore || _uiState.value.isEnd) return
        // 无现有结果时，交给普通 search()
        if (_uiState.value.results.isEmpty()) return

        val nextPage = _currentPage + 1
        _uiState.value = _uiState.value.copy(isLoadingMore = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = searchBySource(_lastSource, kw, page = nextPage, pageSize = _pageSize)
                // ⚡ 追加到现有结果列表，使用 LinkedHashSet 去重（避免重复歌曲）
                val existingIds = _uiState.value.results.mapTo(LinkedHashSet()) { it.id }
                val newItems = result.list.filterNot { it.id in existingIds }
                val merged = _uiState.value.results + newItems

                _currentPage = nextPage
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    results = merged,
                    isEnd = result.isEnd
                )
                // 批量补充新加载项的封面
                fillMissingCovers(newItems)
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    error = "加载更多失败: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * ⚡ 歌手搜索。原实现调用网易云 /search?type=100；落雪 JS 音源协议没有歌手搜索动作，
     * 本版本没有可用的歌手接口，因此返回空结果（保留分页状态字段以维持 public API 不变）。
     */
    private var _artistCurrentPage = 1
    private var _lastArtistKeyword: String? = null

    fun searchArtists() {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        // ⚡ 新搜索重置分页状态
        _artistCurrentPage = 1
        _lastArtistKeyword = kw
        // NetEase built-in sources are not part of this build.
        _uiState.value = _uiState.value.copy(
            searchingArtists = false,
            artistResults = emptyList(),
            artistIsEnd = true,
            artistError = "本版本不支持歌手搜索（仅支持 JS 音源歌曲搜索）"
        )
    }

    /**
     * ⚡ 加载下一页歌手搜索结果。歌手结果恒为空，这里只做状态收敛（幂等，不发请求）。
     */
    fun loadMoreArtists() {
        val kw = _lastArtistKeyword?.trim() ?: keyword.trim()
        if (kw.isBlank()) return
        // 防重复：正在搜索/加载更多时不触发；已到最后一页时不触发
        if (_uiState.value.searchingArtists || _uiState.value.isLoadingMore || _uiState.value.artistIsEnd) return
        // 无现有结果时，交给普通 searchArtists()
        if (_uiState.value.artistResults.isEmpty()) return

        // NetEase built-in sources are not part of this build.（无歌手接口可翻页）
        _artistCurrentPage += 1
        _uiState.value = _uiState.value.copy(
            isLoadingMore = false,
            artistResults = emptyList(),
            artistIsEnd = true
        )
    }

    /**
     * ⚡ 在线歌单搜索。原实现走网易云官方歌单搜索（type=1000）与 tx/kg/mg/kw 内置源；
     * 落雪 JS 音源协议没有 songlist 搜索动作 → 两者都不在本版本中，只能返回空结果。
     * 独立的分页状态（_playlistCurrentPage / _lastPlaylistKeyword），
     * 不影响歌曲搜索与歌手搜索的状态。
     */
    private var _playlistCurrentPage = 1
    private var _lastPlaylistKeyword: String? = null
    /** 最近一次歌单搜索实际使用的音源（供分页加载更多时保持同一音源） */
    private var _lastPlaylistSource: String = "all"
    private val _playlistPageSize = 15

    fun searchPlaylists(source: String? = null) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val effectiveSource = source ?: selectedSource
        // ⚡ 新搜索重置分页状态
        _playlistCurrentPage = 1
        _lastPlaylistKeyword = kw
        _lastPlaylistSource = effectiveSource
        _uiState.value = _uiState.value.copy(
            searchingPlaylists = true,
            playlistError = null,
            playlistIsEnd = false,
            playlistResults = emptyList()
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = searchPlaylistsBySource(effectiveSource, kw, page = 1, pageSize = _playlistPageSize)
                _uiState.value = _uiState.value.copy(
                    searchingPlaylists = false,
                    playlistResults = result.list,
                    playlistIsEnd = result.isEnd,
                    playlistError = if (result.list.isEmpty()) PLAYLIST_SEARCH_UNSUPPORTED else null
                )
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    searchingPlaylists = false,
                    playlistResults = emptyList(),
                    playlistIsEnd = true,
                    playlistError = "歌单搜索失败: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * ⚡ 加载下一页歌单搜索结果。歌单结果恒为空（[searchPlaylistsBySource] 只返回空结果），
     * 因此翻页会立即收敛到"已到最后一页"。
     */
    fun loadMorePlaylists() {
        val kw = _lastPlaylistKeyword?.trim() ?: keyword.trim()
        if (kw.isBlank()) return
        // 防重复：正在搜索/加载更多时不触发；已到最后一页时不触发
        if (_uiState.value.searchingPlaylists || _uiState.value.isLoadingMore || _uiState.value.playlistIsEnd) return
        if (_uiState.value.playlistResults.isEmpty()) return

        val nextPage = _playlistCurrentPage + 1
        _uiState.value = _uiState.value.copy(isLoadingMore = true, playlistError = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = searchPlaylistsBySource(
                    _lastPlaylistSource,
                    kw,
                    page = nextPage,
                    pageSize = _playlistPageSize
                )
                // ⚡ 追加到现有结果列表，使用 LinkedHashSet 去重
                val existingIds = _uiState.value.playlistResults.mapTo(LinkedHashSet()) { it.id }
                val newItems = result.list.filterNot { it.id in existingIds }

                _playlistCurrentPage = nextPage
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    playlistResults = _uiState.value.playlistResults + newItems,
                    playlistIsEnd = result.isEnd
                )
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    playlistError = "加载更多失败: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * 拉取在线歌单歌曲的统一入口（供「保存到本地」与「整队入列」共用）。
     *
     * 原实现走网易云官方歌单详情（type=1000 详情）与 tx/kg/mg/kw 内置源；落雪 JS 音源协议
     * 没有歌单详情动作，所以本版本没有可拉取的歌单歌曲。
     * NetEase built-in sources are not part of this build.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun fetchPlaylistSongs(
        playlist: LxPlaylistInfo,
        page: Int = 1,
        pageSize: Int = 1000
    ): LxSearchResult = LxSearchResult(list = emptyList(), isEnd = true, total = 0)

    // ── 在线歌单：保存到本地 / 整队入列 ─────────────────────────────────

    /**
     * ⚡ 把在线歌单保存为本地歌单：拉取歌单歌曲 → 逐首写入统一媒体库（生成可播放的 Song）
     * → 创建本地歌单，之后可从「本地歌单」进入 playlist_detail 路由查看/播放。
     *
     * ⚡ 逐首落库依赖 `MusicRepository.saveCloudSong`，本版本没有云端曲库，该 API 不存在，
     *    因此不假装保存成功：只把原因写进 UI 并如实回调失败。
     */
    fun savePlaylistToLocal(
        playlist: LxPlaylistInfo,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (_uiState.value.savingPlaylistId != null) return
        // 保存到本地需要云端曲库，本版本未启用
        _uiState.value = _uiState.value.copy(error = SAVE_REQUIRES_CLOUD_LIBRARY)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(savingPlaylistId = playlist.id)
            var savedCount = 0
            try {
                val result = fetchPlaylistSongs(playlist)
                if (result.list.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult(false, SAVE_REQUIRES_CLOUD_LIBRARY)
                    }
                    return@launch
                }

                // ⚡ 逐首落库需要 musicRepository.saveCloudSong(...)，本版本没有该 API
                //   （没有云端曲库）→ 没有可落库的歌曲 id，交给下方分支统一报错。
                val songIds = ArrayList<String>(result.list.size)
                savedCount = songIds.size
                if (songIds.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult(false, SAVE_REQUIRES_CLOUD_LIBRARY)
                    }
                    return@launch
                }

                playlistPreferencesRepository.createPlaylist(
                    name = playlist.name.ifBlank { "在线歌单" },
                    songIds = songIds,
                    coverImageUri = playlist.cover.ifBlank { null },
                    source = "ONLINE"
                )
                withContext(Dispatchers.Main) {
                    onResult(true, "已保存到本地歌单「${playlist.name}」($savedCount 首)")
                }
            } catch (t: Throwable) {
                Timber.e(t, "savePlaylistToLocal failed")
                withContext(Dispatchers.Main) {
                    onResult(false, "保存失败: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                _uiState.value = _uiState.value.copy(savingPlaylistId = null)
            }
        }
    }

    /**
     * 在线歌单搜索：原实现走网易云官方歌单搜索与 tx/kg/mg/kw 内置源；
     * 落雪 JS 音源协议没有 songlist 搜索动作 → 统一返回空结果。
     * NetEase built-in sources are not part of this build.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun searchPlaylistsBySource(
        source: String,
        kw: String,
        page: Int,
        pageSize: Int
    ): LxPlaylistSearchResult = LxPlaylistSearchResult(list = emptyList(), isEnd = true, total = 0)

    /**
     * 按所选音源搜索（模仿落雪原版单源搜索方案）：
     * - 落雪 JS 音源：走 JS 引擎 engine.search 搜索该源，脚本不支持搜索时返回空结果
     * - all（默认「全部」标签）：并发搜索所有已启用的 JS 音源并合并去重
     */
    private suspend fun searchBySource(source: String, kw: String, page: Int, pageSize: Int): LxSearchResult {
        if (source == "all" || source == "wy") {
            // 本版本没有内置源，「全部」= 所有已安装并启用的 JS 音源
            return searchAllJsSources(kw, page, pageSize)
        }
        // 落雪源：直接走 JS 引擎搜索（点击哪个源就搜哪个源）
        val result = engine.search(kw, source, page, pageSize)
        // 统一为每条结果标记音源，供播放时选对音源、UI 提示用
        return result.copy(list = result.list.map { it.copy(source = source) })
    }

    /** 搜索全部已启用 JS 音源，合并并去重。任一源还有下一页时 isEnd=false。 */
    private suspend fun searchAllJsSources(kw: String, page: Int, pageSize: Int): LxSearchResult {
        val keys = engine.getSources().keys.filter { engine.isSourceEnabled(it) }.take(MAX_ALL_SOURCES)
        if (keys.isEmpty()) return LxSearchResult(list = emptyList(), isEnd = true, total = 0)
        var isEnd = true
        val merged = mutableListOf<LxSongInfo>()
        keys.forEach { key ->
            val partial = runCatching { engine.search(kw, key, page, pageSize) }.getOrNull()
            if (partial == null) {
                isEnd = false
            } else {
                merged += partial.list.map { song -> song.copy(source = key) }
                if (!partial.isEnd) isEnd = false
            }
        }
        val deduped = merged.distinctBy { "${it.source}|${it.id}" }
        return LxSearchResult(list = deduped, isEnd = isEnd, total = deduped.size)
    }

    /**
     * 批量补充搜索结果中缺失封面的歌曲。
     * 搜索响应通常不带 picUrl，需要再走一次音源的 pic 动作补全。
     */
    private fun fillMissingCovers(songs: List<LxSongInfo>) {
        val missing = songs.filter { it.pic.isBlank() }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // 封面只走 JS 音源的 pic 动作（尽力而为）。
            // NetEase built-in sources are not part of this build.
            val jsMissing = missing.filter { engine.getSources().containsKey(it.source) }

            val covers = mutableMapOf<String, String>()
            jsMissing.take(30).forEach { song ->
                runCatching { engine.getPic(song.source, song.toInfoMap()) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { covers[song.id] = it }
            }
            if (covers.isEmpty()) return@launch

            val currentResults = _uiState.value.results
            val updated = currentResults.map { existing ->
                if (existing.pic.isBlank() && covers.containsKey(existing.id)) {
                    existing.copy(pic = covers[existing.id]!!)
                } else existing
            }
            if (updated != currentResults) {
                _uiState.value = _uiState.value.copy(results = updated)
            }
        }
    }

    fun playSong(
        song: LxSongInfo,
        onOpenPlayer: (url: String, title: String, artist: String, cover: String, songId: String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("LxPlaySong", "=== playSong called ===")
                android.util.Log.d("LxPlaySong", "Song name: ${song.name}, singer: ${song.singer}, id: ${song.id}, cover: ${song.pic}")
                _uiState.value = _uiState.value.copy(
                    progress = 0.2f,
                    progressLabel = "获取播放链接…",
                    loadingSongId = song.id
                )
                val resolved = resolvePlayableSong(song)
                android.util.Log.d("LxPlaySong", "Resolved URL: ${resolved?.url}, cover: ${resolved?.cover}")

                _uiState.value = _uiState.value.copy(progressLabel = "正在打开播放器…")

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(progress = null, progressLabel = null, loadingSongId = null)
                    if (resolved == null) {
                        android.util.Log.w("LxPlaySong", "URL is null, showing error (song=${song.id})")
                        _uiState.value = _uiState.value.copy(
                            error = "无法获取播放链接（音源: ${resolved?.source ?: "unknown"}），请换一首或换音源"
                        )
                        return@withContext
                    }
                    android.util.Log.d("LxPlaySong", "Calling onOpenPlayer with URL length: ${resolved.url.length}, songId: ${resolved.savedSongId}")
                    onOpenPlayer(resolved.url, song.name, song.singer, resolved.cover, resolved.savedSongId)
                }
            } catch (t: Throwable) {
                android.util.Log.e("LxPlaySong", "Error: ${t.message}", t)
                _uiState.value = _uiState.value.copy(
                    progress = null, progressLabel = null, loadingSongId = null,
                    error = "播放失败: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    private data class LxResolvedPlayable(
        val url: String,
        val cover: String,
        val savedSongId: String,
        val source: String
    )

    /**
     * 解析一首落雪歌曲的可播放直链（音源选择 → 封面补全 → URL 解析）。
     * 不更新 UI 状态，供 [playSong] 与 [playSongListWithQueue] 共用（静默批量解析）。
     *
     * ⚡ 全部走 JS 引擎：本版本没有内置源/网易云代理，`MusicRepository` 也没有 saveCloudSong，
     *    所以这里不落库，队列与播放统一使用稳定 id。
     */
    private suspend fun resolvePlayableSong(song: LxSongInfo): LxResolvedPlayable? {
        val songMap = song.toInfoMap()
        // ⚡ 与媒体库播放（DualPlayerEngine.resolveCloudLxUriAsync）对齐：先确保 JS 音源引擎就绪，
        //    冷启动瞬间 getSources() 为空会导致目标音源选错。
        runCatching { engine.awaitReady(15_000) }
        // 只认已安装 JS 脚本注册的音源（本版本没有内置源可兜底）
        val availableSources = runCatching { engine.getSources().keys.toList() }
            .getOrDefault(emptyList())
        // 优先用歌曲自己携带的音源（搜索结果逐条标记），避免切了音源后点播其他源的结果仍走错音源
        val targetSource = when {
            song.source.isNotBlank() && availableSources.contains(song.source) -> song.source
            selectedSource != "all" && availableSources.contains(selectedSource) -> selectedSource
            song.source.isNotBlank() -> song.source
            else -> availableSources.firstOrNull() ?: "all"
        }
        android.util.Log.d("LxPlaySong", "Target source: $targetSource")

        // 音质只用内存中的设置（本版本没有音质持久化设置项）
        val preferredQuality = _uiState.value.musicQualityValue.ifBlank { "128k" }

        // 如果 song.pic 为空，在播放前用 JS 音源的 pic 动作取一次封面（尽力而为）。
        // NetEase built-in sources are not part of this build.
        val coverToUse = if (song.pic.isBlank()) {
            runCatching { engine.getPic(song.source.ifBlank { targetSource }, songMap) }
                .getOrNull()
                .orEmpty()
        } else song.pic

        // 解析核心：首选音源按音质链取链 → 失败再按「歌名 + 歌手」换源重试。
        // 目标音源：歌曲自身 source > 用户当前选中的源。
        var resolvedUrl = resolvePlayUrlWithQualityChain(targetSource, songMap, preferredQuality)
        var effectiveSong = song.copy(source = targetSource)
        if (resolvedUrl.isNullOrBlank()) {
            val switched = resolveViaOtherSource(
                song = song,
                quality = preferredQuality,
                availableSources = availableSources,
                excludeSource = targetSource
            )
            if (switched != null) {
                effectiveSong = switched.song
                resolvedUrl = switched.url
            }
        }
        val playUrl = resolvedUrl?.takeIf { it.isNotBlank() } ?: run {
            android.util.Log.w("LxPlaySong", "All sources failed for '${song.name}'")
            return null
        }

        // ── 歌曲标识
        // 本版本没有云端曲库（musicRepository.saveCloudSong 不存在）：点播不再写入统一媒体库，
        // 一律用稳定 id 作为队列/播放标识，与搜索结果、收藏校验保持同一套 id。
        val finalCover = coverToUse.ifBlank { effectiveSong.pic }
        val savedSongId = getStableSongId(effectiveSong)
        android.util.Log.d("LxPlaySong", "song ID: $savedSongId, source: ${effectiveSong.source}")
        return LxResolvedPlayable(
            url = playUrl,
            cover = finalCover,
            savedSongId = savedSongId,
            source = effectiveSong.source
        )
    }

    /** 换源命中结果：命中的那首歌（带新 source / id）与解析出的直链 */
    private data class SwitchedSource(val song: LxSongInfo, val url: String)

    /**
     * 换源解析（对齐 lx-music 的 `getOtherSource`）：
     * 用「歌名 + 歌手」在**其它已安装 JS 音源**里搜索同一首歌，再用那个音源自己的解析能力取链。
     *
     * 命中必须通过同名校验（时长 ±3s + 歌手重叠），避免换源后播成翻唱/同名歌；
     * 所有音源都失败时返回 null（调用方如实报解析失败）。
     */
    private suspend fun resolveViaOtherSource(
        song: LxSongInfo,
        quality: String,
        availableSources: List<String>,
        excludeSource: String
    ): SwitchedSource? {
        val keyword = "${song.name} ${song.singer}".trim()
        if (keyword.isBlank()) return null

        // 只用已安装 JS 脚本提供的音源（本版本没有内置源可换），排除当前已失败的那个源
        val candidates = availableSources
            .distinct()
            .filter { it != excludeSource }

        for (source in candidates) {
            val hits = runCatching {
                engine.search(keyword, source, page = 1, pagesize = 5).list
            }.getOrDefault(emptyList())

            val matched = hits.firstOrNull { isSameSong(song, it) } ?: continue
            val url = runCatching {
                resolvePlayUrlWithQualityChain(source, matched.toInfoMap(), quality)
            }.getOrNull()
            if (!url.isNullOrBlank()) {
                return SwitchedSource(song = matched.copy(source = source), url = url)
            }
        }
        return null
    }

    /** 同名歌校验：时长接近（±3s）且歌手信息可对齐 */
    private fun isSameSong(target: LxSongInfo, candidate: LxSongInfo): Boolean {
        if (candidate.id.isBlank()) return false
        val artistOk = artistTokensOverlap(target.singer, candidate.singer)
        val durationOk = target.duration > 0L && candidate.duration > 0L &&
            kotlin.math.abs(candidate.duration - target.duration) <= 3L
        return when {
            target.duration > 0L -> durationOk &&
                (artistOk || target.singer.isBlank() || candidate.singer.isBlank())
            else -> artistOk
        }
    }

    /** 歌手串按常见分隔符拆分后做去空格包含匹配 */
    private fun artistTokensOverlap(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        fun tokens(s: String): List<String> = s
            .split('/', '、', ',', '，', '&', ';', '；', '|')
            .map { it.trim().replace(" ", "").lowercase() }
            .filter { it.isNotBlank() }
        val aTokens = tokens(a)
        val bTokens = tokens(b)
        if (aTokens.isEmpty() || bTokens.isEmpty()) return false
        val ca = a.replace(" ", "").lowercase()
        val cb = b.replace(" ", "").lowercase()
        return aTokens.any { cb.contains(it) } || bTokens.any { ca.contains(it) }
    }

    /**
     * 搜索整队播放：点击某首结果后，把当前搜索结果的其余歌曲逐首静默解析并追加到播放队列。
     * 自动切下一曲时即可按搜索结果顺序依次播放。
     */
    fun enqueueAllSearchResults(
        clickedSongId: String,
        onEnqueue: (url: String, title: String, artist: String, cover: String, songId: String) -> Unit
    ) {
        enqueueSongsList(_uiState.value.results, clickedSongId, onEnqueue)
    }

    /**
     * 把给定歌曲列表（除点击的 [excludeId] 外）全部排入播放队列。
     * ⚡ 不立即解析 URL，改为构造 cloud://lx/{json} 占位 URI，
     *    由 DualPlayerEngine 的 ResolvingDataSource 在歌曲实际播放时才解析新鲜直链。
     *    避免搜索结果批量入队时链接过期导致后续歌曲无法播放。
     */
    fun enqueueSongsList(
        songs: List<LxSongInfo>,
        excludeId: String,
        onEnqueue: (url: String, title: String, artist: String, cover: String, songId: String) -> Unit
    ) {
        if (songs.size <= 1) return
        viewModelScope.launch(Dispatchers.IO) {
            songs.filter { getStableSongId(it) != excludeId }.forEach { song ->
                val placeholderUrl = buildLxPlaceholderUri(song, pickSourceForSong(song))
                val stableId = getStableSongId(song)
                withContext(Dispatchers.Main) {
                    onEnqueue(placeholderUrl, song.name, song.singer, song.pic, stableId)
                }
            }
        }
    }

    /** 为歌曲选定解析音源：歌曲自带 → 用户所选 → 引擎已注册源兜底（只认 JS 脚本注册的音源） */
    private fun pickSourceForSong(song: LxSongInfo): String {
        val availableSources = runCatching { engine.getSources().keys.toList() }
            .getOrDefault(emptyList())
        return when {
            song.source.isNotBlank() && availableSources.contains(song.source) -> song.source
            selectedSource != "all" && availableSources.contains(selectedSource) -> selectedSource
            song.source.isNotBlank() -> song.source
            else -> availableSources.firstOrNull() ?: "all"
        }
    }

    /** 构造 cloud://lx/{json} 占位 URI：由播放引擎在实际播放时才解析新鲜直链 */
    private fun buildLxPlaceholderUri(song: LxSongInfo, targetSource: String): String {
        val songJson = org.json.JSONObject().apply {
            put("id", song.id)
            put("songmid", song.songmid)
            put("hash", song.hash)
            put("name", song.name)
            put("singer", song.singer)
            put("artistIds", song.artistIds)
            put("album", song.albumName)
            put("pic", song.pic)
            put("duration", song.duration)
            put("source", targetSource)
        }
        val encoded = java.net.URLEncoder.encode(songJson.toString(), "UTF-8")
            .replace("+", "%20")
        return "cloud://lx/$encoded"
    }

    /** 队列种子：url 可为真实直链或 cloud://lx 占位 URI，由 PlayerViewModel.buildCloudSong 转为 Song */
    data class CloudQueueSeed(
        val url: String,
        val title: String,
        val artist: String,
        val cover: String,
        val songId: String,
        val bilibiliBvid: String? = null,
        // ⚡ 平台信息透传：真实直链种子会丢失音源/平台 id，播放器需要它们识别
        //    在线平台（下载/评论按钮、网易云红心同步、收藏落库音源）
        val source: String? = null,
        val platformSongId: String? = null
    )

    /**
     * ⚡ 供「在线歌单详情页」按需拉取歌曲：返回 cloud://lx 占位种子（不落库、不产生媒体库副作用）。
     * 统一封装「选音源 + 占位 URI + 稳定 id」逻辑。
     */
    suspend fun fetchPlaylistSeeds(playlist: LxPlaylistInfo): List<CloudQueueSeed> {
        val songs = runCatching { fetchPlaylistSongs(playlist) }.getOrNull()?.list.orEmpty()
        if (songs.isEmpty()) return emptyList()
        return songs.map { song ->
            val source = pickSourceForSong(song)
            CloudQueueSeed(
                url = buildLxPlaceholderUri(song, source),
                title = song.name,
                artist = song.singer,
                cover = song.pic,
                songId = getStableSongId(song),
                source = source,
                platformSongId = song.id
            )
        }
    }

    /**
     * ⚡ 搜索结果点击播放 + 整列入队（原子建队，修复竞态）：
     * 解析 [song] 的真实直链后，把「点击歌曲 + 其余搜索结果占位」一次性交给 [onPlayQueue]
     * 构建完整队列。此前 playSong（playUrl 会重置队列）与 enqueueAllSearchResults（逐首
     * 追加）并发执行，先后顺序不定导致播放列表时而只剩单曲、时而丢失部分结果。
     *
     * ⚡ 传入 [onMoreSeeds] 时，还会在后台**按页续拉**尚未加载的搜索结果并增量追加到队列，
     *    使用户点一首歌即可顺序播放"全部搜索结果"，而不止当前已加载的那一页。
     */
    fun playSearchResultWithQueue(
        song: LxSongInfo,
        onPlayQueue: (List<CloudQueueSeed>, Int) -> Unit,
        onMoreSeeds: ((List<CloudQueueSeed>) -> Unit)? = null
    ) {
        val results = _uiState.value.results
        val index = results.indexOfFirst { getStableSongId(it) == getStableSongId(song) }.takeIf { it >= 0 } ?: 0
        playSongListWithQueue(results, index, onPlayQueue)
        if (onMoreSeeds != null) {
            // ⚡ 首次建队已排入的歌曲数（受上限约束），剩余配额再交给后台续拉补齐到上限为止
            val queuedInFirstBatch = results.size.coerceAtMost(AUTO_QUEUE_MAX_SONGS)
            scheduleAutoQueueFill(song, (AUTO_QUEUE_MAX_SONGS - queuedInFirstBatch).coerceAtLeast(0), onMoreSeeds)
        }
    }

    /**
     * ⚡ 后台「自动补拉后续分页并追加进队列」：
     * 点击搜索结果后，从当前已加载的下一页开始，逐页拉取后续搜索结果并增量交给 [onMoreSeeds]，
     * 直到补齐 [remainingSlots] 首或搜索结果耗尽为止。
     *
     * 性能优化：
     * - 独立 [Job]，新点击/新搜索时取消旧任务，避免任务叠加
     * - 逐页串行 + 页间让步延迟，避免瞬时爆发网络请求造成卡顿
     * - 以 stable id 去重（含已加载结果 + 已追加结果），不产生重复歌曲
     * - [remainingSlots] + [AUTO_QUEUE_MAX_PAGES] 双重限制，队列总数不超过 [AUTO_QUEUE_MAX_SONGS]
     */
    private fun scheduleAutoQueueFill(
        clicked: LxSongInfo,
        remainingSlots: Int,
        onMoreSeeds: (List<CloudQueueSeed>) -> Unit
    ) {
        if (remainingSlots <= 0) return
        val kw = _lastKeyword?.trim()?.takeIf { it.isNotBlank() }
            ?: keyword.trim().takeIf { it.isNotBlank() }
        if (kw.isNullOrBlank() || _uiState.value.isEnd) return
        val source = _lastSource
        val startPage = _currentPage + 1
        _autoQueueJob?.cancel()
        _autoQueueJob = viewModelScope.launch(Dispatchers.IO) {
            // 去重集合：已加载结果 + 点击歌曲自身，避免把已入队的歌曲重复追加
            val seen = _uiState.value.results.mapTo(LinkedHashSet()) { getStableSongId(it) }
            seen.add(getStableSongId(clicked))
            var page = startPage
            var fetchedPages = 0
            var appended = 0
            while (fetchedPages < AUTO_QUEUE_MAX_PAGES && appended < remainingSlots) {
                delay(AUTO_QUEUE_PAGE_DELAY_MS)
                val result = runCatching {
                    searchBySource(source, kw, page = page, pageSize = _pageSize)
                }.getOrElse { return@launch }
                val fresh = result.list
                    .filter { seen.add(getStableSongId(it)) }
                    .take(remainingSlots - appended)
                if (fresh.isNotEmpty()) {
                    val seeds = fresh.map { s ->
                        val seedSource = pickSourceForSong(s)
                        CloudQueueSeed(
                            url = buildLxPlaceholderUri(s, seedSource),
                            title = s.name,
                            artist = s.singer,
                            cover = s.pic,
                            songId = getStableSongId(s),
                            source = seedSource,
                            platformSongId = s.id
                        )
                    }
                    appended += seeds.size
                    withContext(Dispatchers.Main) { onMoreSeeds(seeds) }
                }
                fetchedPages++
                page++
                if (result.isEnd) break
            }
        }
    }

    /**
     * 解析 [songs] 中 [startIndex] 这一首后原子建队：
     * 点击歌曲用真实直链，其余歌曲用 cloud://lx 占位懒解析。
     * 由搜索结果、在线歌单详情页共用。
     *
     * ⚡ 本版本没有云端曲库：点击歌曲也不落库（`MusicRepository` 无 saveCloudSong），
     *    播放队列统一用稳定 id 标识歌曲。
     * ⚡ 队列长度受 [AUTO_QUEUE_MAX_SONGS] 约束，避免一次性排入过多歌曲造成卡顿。
     */
    fun playSongListWithQueue(
        songs: List<LxSongInfo>,
        startIndex: Int,
        onPlayQueue: (List<CloudQueueSeed>, Int) -> Unit
    ) {
        val clicked = songs.getOrNull(startIndex) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                progress = 0.2f,
                progressLabel = "获取播放链接…",
                loadingSongId = clicked.id
            )
            val resolved = resolvePlayableSong(clicked)
            if (resolved == null) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        progress = null, progressLabel = null, loadingSongId = null,
                        error = "无法获取播放链接（音源: ${clicked.source.ifBlank { "unknown" }}），请换一首或换音源"
                    )
                }
                return@launch
            }
            val clickedKey = getStableSongId(clicked)
            val seeds = ArrayList<CloudQueueSeed>(songs.size.coerceAtMost(AUTO_QUEUE_MAX_SONGS))
            // 点击的歌曲：真实直链（不落库，savedSongId 即稳定 id）；透传音源与平台歌曲 id
            seeds.add(
                CloudQueueSeed(
                    resolved.url, clicked.name, clicked.singer, resolved.cover, resolved.savedSongId,
                    source = resolved.source,
                    platformSongId = clicked.id
                )
            )
            for ((index, s) in songs.withIndex()) {
                if (seeds.size >= AUTO_QUEUE_MAX_SONGS) break
                if (index == startIndex || getStableSongId(s) == clickedKey) continue
                val source = pickSourceForSong(s)
                seeds.add(
                    CloudQueueSeed(
                        buildLxPlaceholderUri(s, source), s.name, s.singer, s.pic, getStableSongId(s),
                        source = source,
                        platformSongId = s.id
                    )
                )
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(progress = null, progressLabel = null, loadingSongId = null)
                onPlayQueue(seeds, 0)
            }
        }
    }

    /**
     * 落雪音质档位（由高到低）。脚本自己声明的 qualitys 顺序不一定可靠，这里统一排序。
     * 本版本没有 MusicQualityCatalog，音质链在本地按这份顺序推导。
     */
    private val lxQualityOrder = listOf(
        "master", "hires", "atmos", "atmos_plus", "dolby",
        "flac24bit", "24bit", "flac", "320k", "192k", "128k", "64k",
    )

    /**
     * 按用户选择的音质**向下递减**生成尝试链，动态识别音源脚本注册的 qualitys。
     * 严格遵守音质设置（128k 不自动抬音质，避免用户选 128k 却被拉到高音质）。
     * rank 越大表示音质越低；未识别的档位按 320k 处理，避免把未知值当成无损。
     */
    private fun resolveQualityChain(preferred: String, available: List<String>): List<String> {
        fun rank(q: String): Int = lxQualityOrder.indexOfFirst { it.equals(q, ignoreCase = true) }
            .let { if (it < 0) lxQualityOrder.indexOf("320k") else it }

        val declared = available.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (declared.isEmpty()) return listOf(preferred)
        val downward = declared
            .filter { rank(it) >= rank(preferred) }
            .sortedBy { rank(it) }
        return (listOf(preferred) + downward).distinct()
    }

    /**
     * 按用户选择的音质**向下递减**尝试落雪播放链接，动态识别音源脚本注册的 qualitys，
     * 严格遵守音质设置（128k 不自动抬音质，避免用户选 128k 却被拉到高音质）。
     */
    private suspend fun resolvePlayUrlWithQualityChain(
        source: String,
        songMap: Map<String, Any?>,
        preferred: String
    ): String? {
        val chain = resolveQualityChain(
            preferred = preferred,
            available = engine.getSources()[source]?.qualitys.orEmpty()
        )
        for (q in chain) {
            val url = engine.getPlayUrl(source, songMap, q)
            if (!url.isNullOrBlank()) {
                // ⚡ 加密容器（.mflac/.mgg/.mmp4）无法播放：丢弃后继续下一档
                val encrypted = url.substringBefore('?').substringBefore('#').lowercase().let { path ->
                    listOf(".mflac", ".mflac0", ".mflac2", ".mgg", ".mgg0", ".mgg1", ".mggl", ".mmp4")
                        .any { path.endsWith(it) }
                }
                if (encrypted) {
                    android.util.Log.w("LxPlaySong", "skip encrypted stream: $source/$q ${url.take(80)}")
                    continue
                }
                android.util.Log.d("LxPlaySong", "quality chain hit: $source/$q")
                return url
            }
        }
        return null
    }

    // ── Favorite support for cloud songs ─────────────────────────────────────

    /** Flow of all favorited song IDs from the main database. */
    val favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>
        get() = musicRepository.getFavoriteSongIdsFlow()

    /**
     * 收藏一首在线歌曲。
     *
     * ⚡ 原实现先 `musicRepository.saveCloudSong(song)` 落库、再 `toggleFavoriteStatus(songId)`；
     *    本版本没有云端曲库（`MusicRepository` 无 saveCloudSong），落库这一环不存在，
     *    因此如实提示用户，不再假装收藏成功（控制流不变：失败回调 false）。
     */
    fun toggleFavoriteForSong(song: LxSongInfo, onResult: (Boolean) -> Unit = {}) {
        // 保存到本地需要云端曲库，本版本未启用
        _uiState.value = _uiState.value.copy(error = SAVE_REQUIRES_CLOUD_LIBRARY)
        viewModelScope.launch(Dispatchers.Main) { onResult(false) }
    }

    /** Checks whether a cloud song is currently favorited. */
    fun isSongFavorited(song: LxSongInfo, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songId = computeStableId(song)
                val favIds = musicRepository.getFavoriteSongIdsOnce()
                val isFav = favIds.contains(songId.toString())
                withContext(Dispatchers.Main) {
                    onResult(isFav)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun getStableSongId(song: LxSongInfo): String {
        var hash = 1125899906842597L
        val input = "lx_song_" + song.id + "|" + song.name + "|" + song.singer
        for (c in input) {
            hash = (hash * 31 + c.code.toLong())
        }
        val result = hash and Long.MAX_VALUE
        return (if (result == 0L) 1L else result).toString()
    }

    private fun computeStableId(song: LxSongInfo): Long {
        return getStableSongId(song).toLong()
    }

    // 多歌手支持：artists/artistIds 按 lx-music 协议传给 JS 引擎（数组），
    // 避免脚本读取 musicInfo.artists 时拿到字符串导致失败。
    // ⚡ 统一走 data.lx 的共用映射：连通性测试（LxSourceTester）也用同一份，
    //   否则会出现"能播放但测试报不可用"的不一致。
    private fun LxSongInfo.toInfoMap(): Map<String, Any?> = toLxMusicInfoMap()
}
