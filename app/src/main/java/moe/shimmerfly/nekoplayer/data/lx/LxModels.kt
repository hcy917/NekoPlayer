package moe.shimmerfly.nekoplayer.data.lx

import kotlinx.serialization.Serializable

@Serializable
data class LxSongInfo(
    val id: String = "",
    val songmid: String = "",
    val hash: String = "",
    val name: String = "",
    val singer: String = "",
    /** 多个歌手时逗号分隔的歌手 ID（与 [singer] 按顺序一一对应），用于 JS 引擎的 musicInfo.artists 数组 */
    val artistIds: String = "",
    val albumName: String = "",
    val duration: Long = 0L,
    val pic: String = "",
    /** 播放时成功获取 URL 的音源标识（如 "wy", "tx", "kw" 等）。从媒体库播放时会用它来调用 JS 引擎。 */
    val source: String = "",
    /** 音源自定义持久化 URI，例如 bilibili://bvid/cid/aid。用于从历史记录/收藏播放时动态解析最新地址。 */
    val extra: String = ""
)

@Serializable
data class LxSearchResult(
    val isEnd: Boolean = true,
    val list: List<LxSongInfo> = emptyList(),
    val total: Int = 0
)

/** 落雪 userApi 的 lyric 返回结构（对齐 lx-music user-api-preload.js 的 verifyLyricInfo）。 */
data class LxLyricInfo(
    val lyric: String = "",
    val tlyric: String? = null,
    val rlyric: String? = null,
    val lxlyric: String? = null,
) {
    fun isUsable(): Boolean = lyric.isNotBlank()
}

/**
 * 网易云歌手搜索结果（/search?type=100）。
 */
@Serializable
data class LxArtistInfo(
    val id: String = "",
    val name: String = "",
    val alias: String = "",
    val picUrl: String = ""
)

@Serializable
data class LxArtistSearchResult(
    val isEnd: Boolean = true,
    val list: List<LxArtistInfo> = emptyList(),
    val total: Int = 0
)

/**
 * 在线歌单搜索结果条目（对齐落雪 ListInfoItem 的统一结构）。
 */
@Serializable
data class LxPlaylistInfo(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    val author: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val description: String = "",
    /** 来源标识：wy / tx / kg / kw / mg */
    val source: String = ""
)

@Serializable
data class LxPlaylistSearchResult(
    val isEnd: Boolean = true,
    val list: List<LxPlaylistInfo> = emptyList(),
    val total: Int = 0
)

@Serializable
data class LxSourceInfo(
    val name: String = "",
    val type: String = "",
    val actions: List<String> = emptyList(),
    val qualitys: List<String> = emptyList()
)

/**
 * [LxSongInfo] → JS 引擎 musicInfo。
 *
 * 各音源脚本读取的键名不一致（tx 用 songmid、kg/kw 用 hash/songmid、wy 用 id 等），
 * 因此把所有可能用到的键都填上；`artists` 必须是 `[{id, name}]` 数组，
 * 传字符串会让按协议读取 `musicInfo.artists` 的脚本取链失败。
 *
 * ⚡ 播放链路（LxMusicViewModel）与连通性测试（LxSourceTester）必须共用这一份映射，
 *   否则会出现"能播放但测试报失败"的不一致。
 */
fun LxSongInfo.toLxMusicInfoMap(): Map<String, Any?> {
    val idList = artistIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val nameList = moe.shimmerfly.nekoplayer.data.stream.CloudMusicUtils.parseArtistNames(singer)
    val artistsArray = nameList.mapIndexed { index, artistName ->
        mapOf("id" to idList.getOrNull(index).orEmpty(), "name" to artistName)
    }
    return mapOf(
        "id" to id,
        "vid" to id,
        "songmid" to songmid.ifBlank { id },
        "hash" to hash.ifBlank { id },
        "name" to name,
        "singer" to singer,
        "artist" to singer,
        "artists" to artistsArray,
        "artistIds" to idList,
        "album" to albumName,
        "albumName" to albumName,
        "duration" to duration,
        "cover" to pic,
        "pic" to pic
    )
}

/**
 * 单个 JS 音源脚本的头部简介（从 /*! ... */ 注释块解析）。
 */
data class LxScriptInfo(
    val fileName: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
    val lastUpdate: String = "",
    val md5: String = ""
)

@Serializable
data class LxLyricResult(
    val lyric: String = ""
)

data class LxInitInfo(
    val sources: Map<String, LxSourceInfo> = emptyMap()
)
