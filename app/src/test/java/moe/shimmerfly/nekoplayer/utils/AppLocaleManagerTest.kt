package moe.shimmerfly.nekoplayer.utils

import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import moe.shimmerfly.nekoplayer.data.preferences.AppLanguage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Resources.updateConfiguration is deprecated, but it is what AppLocaleManager calls.
@Suppress("DEPRECATION")
class AppLocaleManagerTest {

    private val originalDefaultLocale: Locale = Locale.getDefault()
    private var storedTag: String? = null

    private val editor = mockk<SharedPreferences.Editor> {
        every { putString(any(), any()) } answers {
            storedTag = secondArg()
            self as SharedPreferences.Editor
        }
        every { apply() } just Runs
    }
    private val preferences = mockk<SharedPreferences> {
        every { edit() } returns editor
        every { getString(any(), any()) } answers { storedTag ?: secondArg() }
    }
    private val applicationResources = mockk<Resources>(relaxed = true)
    private val callbacks = slot<ComponentCallbacks>()

    // The ViewModels get the application context; setup passes the activity, whose
    // applicationContext is the same application.
    private val applicationContext = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns preferences
        every { resources } returns applicationResources
        every { registerComponentCallbacks(capture(callbacks)) } just Runs
    }

    @BeforeEach
    fun setUp() {
        every { applicationContext.applicationContext } returns applicationContext
        mockkStatic(AppCompatDelegate::class)
        every { AppCompatDelegate.getApplicationLocales() } returns LocaleListCompat.getEmptyLocaleList()
        every { AppCompatDelegate.setApplicationLocales(any()) } just Runs

        val systemLocales = mockk<LocaleList> {
            every { get(0) } returns Locale.US
        }
        val systemConfiguration = mockk<Configuration> {
            every { locales } returns systemLocales
        }
        mockkStatic(Resources::class)
        every { Resources.getSystem() } returns mockk {
            every { configuration } returns systemConfiguration
        }
    }

    @AfterEach
    fun tearDown() {
        Locale.setDefault(originalDefaultLocale)
        unmockkAll()
    }

    @Test
    fun `applying a language updates the application resources and default locale right away`() {
        Locale.setDefault(Locale.US)

        AppLocaleManager.applyLanguage(applicationContext, "tr")

        assertEquals("tr", storedTag)
        assertEquals(Locale.forLanguageTag("tr"), Locale.getDefault())
        verify(exactly = 1) { applicationResources.updateConfiguration(any(), any()) }
        verify { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("tr")) }
    }

    @Test
    fun `applying the system language restores the device locale on the application resources`() {
        storedTag = "tr"
        Locale.setDefault(Locale.forLanguageTag("tr"))

        AppLocaleManager.applyLanguage(applicationContext, AppLanguage.SYSTEM.tag)

        assertEquals(AppLanguage.SYSTEM.tag, storedTag)
        assertEquals(Locale.US, Locale.getDefault())
        verify(exactly = 1) { applicationResources.updateConfiguration(any(), any()) }
    }

    @Test
    fun `a later configuration change re-applies the chosen language to the application resources`() {
        AppLocaleManager.applyLanguage(applicationContext, "de")
        // A rotation resets the process configuration, and with it the default locale.
        Locale.setDefault(Locale.US)

        callbacks.captured.onConfigurationChanged(Configuration())

        assertEquals(Locale.GERMAN, Locale.getDefault())
        verify(exactly = 2) { applicationResources.updateConfiguration(any(), any()) }
    }

    @Test
    fun `changing the language again does not register another configuration listener`() {
        AppLocaleManager.applyLanguage(applicationContext, "de")
        AppLocaleManager.applyLanguage(applicationContext, "fr")

        verify(exactly = 1) { applicationContext.registerComponentCallbacks(any()) }
        callbacks.captured.onConfigurationChanged(Configuration())
        assertEquals(Locale.FRENCH, Locale.getDefault())
    }
}
