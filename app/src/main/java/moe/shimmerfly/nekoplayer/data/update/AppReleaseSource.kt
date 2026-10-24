package moe.shimmerfly.nekoplayer.data.update

import android.os.Build
import moe.shimmerfly.nekoplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Looks up the newest published build of each channel. */
@Singleton
class AppReleaseSource @Inject constructor(
    baseClient: OkHttpClient,
) {
    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "NekoPlayer/${BuildConfig.VERSION_NAME} ($PROJECT_URL)")
                    .build()
            )
        }
        .build()

    /** Newest release of [channel], or null when the channel has nothing installable for this device. */
    suspend fun latest(channel: UpdateChannel): AppRelease? = withContext(Dispatchers.IO) {
        when (channel) {
            UpdateChannel.STABLE -> parseFdroidPackage(getJson("$FDROID_API/$PUBLISHED_PACKAGE"), PUBLISHED_PACKAGE)
            UpdateChannel.ALPHA -> parseLatestAlpha(
                JSONArray(getBody(GITHUB_RELEASES_API)),
                supportedAbis = Build.SUPPORTED_ABIS.toList()
            )
        }
    }

    private fun getJson(url: String): JSONObject = JSONObject(getBody(url))

    private fun getBody(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.string()
        }
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/hcy917/NekoPlayer"

        /** Release applicationId; debug builds add a suffix but should still see real releases. */
        internal const val PUBLISHED_PACKAGE = "moe.shimmerfly.nekoplayer"
        private const val FDROID_API = "https://f-droid.org/api/v1/packages"
        private const val FDROID_REPO = "https://f-droid.org/repo"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/hcy917/NekoPlayer/releases?per_page=30"

        fun fdroidPageUrl(packageName: String) = "https://f-droid.org/packages/$packageName/"

        /** F-Droid's suggested build; F-Droid publishes one universal APK per version code. */
        internal fun parseFdroidPackage(json: JSONObject, packageName: String): AppRelease? {
            val packages = json.optJSONArray("packages") ?: return null
            val versions = (0 until packages.length()).mapNotNull { packages.optJSONObject(it) }
            val suggestedCode = json.optLong("suggestedVersionCode", -1L)
            val chosen = versions.firstOrNull { it.optLong("versionCode", -1L) == suggestedCode }
                ?: versions.maxByOrNull { it.optLong("versionCode", -1L) }
                ?: return null
            val versionCode = chosen.optLong("versionCode", -1L).takeIf { it > 0 } ?: return null
            val versionName = chosen.optString("versionName").takeIf { it.isNotBlank() } ?: return null
            return AppRelease(
                channel = UpdateChannel.STABLE,
                versionName = versionName,
                versionCode = versionCode,
                apkUrl = "$FDROID_REPO/${packageName}_$versionCode.apk",
                apkSizeBytes = null,
                pageUrl = fdroidPageUrl(packageName),
            )
        }

        /**
         * Newest alpha prerelease with an APK for one of [supportedAbis] (in device preference
         * order). Alpha workflow assets are named `NekoPlayer-<version>-<abi>.apk`.
         */
        internal fun parseLatestAlpha(releases: JSONArray, supportedAbis: List<String>): AppRelease? {
            return (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .filter { it.optBoolean("prerelease") && !it.optBoolean("draft") }
                .mapNotNull { release ->
                    val tag = release.optString("tag_name")
                    val version = AlphaVersion.parse(tag) ?: return@mapNotNull null
                    val asset = pickAsset(release.optJSONArray("assets"), supportedAbis) ?: return@mapNotNull null
                    version to AppRelease(
                        channel = UpdateChannel.ALPHA,
                        versionName = tag.removePrefix("v"),
                        versionCode = null,
                        apkUrl = asset.optString("browser_download_url"),
                        apkSizeBytes = asset.optLong("size", -1L).takeIf { it > 0 },
                        pageUrl = release.optString("html_url").ifBlank { "$PROJECT_URL/releases/tag/$tag" },
                    )
                }
                .maxByOrNull { (version, _) -> version }
                ?.second
        }

        private fun pickAsset(assets: JSONArray?, supportedAbis: List<String>): JSONObject? {
            if (assets == null) return null
            val apks = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .filter {
                    it.optString("name").endsWith(".apk") && it.optString("browser_download_url").isNotBlank()
                }
            return supportedAbis.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.optString("name").endsWith("-$abi.apk") }
            }
        }
    }
}
