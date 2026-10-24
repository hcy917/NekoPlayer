package moe.shimmerfly.nekoplayer.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The format helpers pick real plural forms from the shipped resources, so languages with more
 * than one/other (Russian, Arabic) read correctly for every count.
 */
@RunWith(AndroidJUnit4::class)
class FormatsLocalizationTest {

    private val now = 1_700_000_000_000L

    private fun resourcesFor(languageTag: String): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return context.createConfigurationContext(configuration).resources
    }

    @Test
    fun russianSongCountsUseTheOneFewAndManyForms() {
        val ru = resourcesFor("ru")

        assertThat(formatSongCount(ru, 1)).isEqualTo("1 песня")
        assertThat(formatSongCount(ru, 2)).isEqualTo("2 песни")
        assertThat(formatSongCount(ru, 5)).isEqualTo("5 песен")
        assertThat(formatSongCount(ru, 21)).isEqualTo("21 песня")
    }

    @Test
    fun russianTimeAgoAndDurationsAreTranslated() {
        val ru = resourcesFor("ru")

        assertThat(formatTimeAgo(ru, now - 2 * MINUTE, nowMs = now)).isEqualTo("2 минуты назад")
        assertThat(formatTimeAgo(ru, now - 5 * MINUTE, nowMs = now)).isEqualTo("5 минут назад")
        assertThat(formatTimeAgo(ru, now - 21 * MINUTE, nowMs = now)).isEqualTo("21 минуту назад")
        assertThat(formatTimeAgo(ru, now, nowMs = now)).isEqualTo("Только что")
        assertThat(formatListeningDurationLong(ru, 3_660_000L)).isEqualTo("1 ч 01 м")
    }

    @Test
    fun arabicUsesTheDualForTwoAndThePluralForThreeToTen() {
        val ar = resourcesFor("ar")

        // Arabic may render the number itself in Arabic-Indic digits, so only the noun is checked.
        assertThat(formatSongCount(ar, 1)).endsWith("أغنية")
        assertThat(formatSongCount(ar, 2)).endsWith("أغنيتان")
        assertThat(formatSongCount(ar, 3)).endsWith("أغانٍ")
        assertThat(formatSongCount(ar, 11)).endsWith("أغنية")
        assertThat(formatTimeAgo(ar, now - 2 * HOUR, nowMs = now)).endsWith("ساعتين")
        assertThat(formatTimeAgo(ar, now - 3 * HOUR, nowMs = now)).endsWith("ساعات")
    }

    @Test
    fun turkishSongCountsTimeAgoAndDurationsAreTranslated() {
        val tr = resourcesFor("tr")

        assertThat(formatSongCount(tr, 1)).isEqualTo("1 Şarkı")
        assertThat(formatSongCount(tr, 3)).isEqualTo("3 Şarkı")
        assertThat(formatTimeAgo(tr, now - 5 * MINUTE, nowMs = now)).isEqualTo("5 dakika önce")
        assertThat(formatTimeAgo(tr, 0L, nowMs = now)).isEqualTo("Hiçbir zaman")
        assertThat(formatListeningDurationCompact(tr, 3_660_000L)).isEqualTo("1sa 01dk")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
