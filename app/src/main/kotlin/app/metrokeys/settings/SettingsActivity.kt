package app.metrokeys.settings

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.metrokeys.settings.ui.DismissRegistry
import app.metrokeys.settings.ui.LocalDismissRegistry
import app.metrokeys.settings.ui.LocalOverlayHost
import app.metrokeys.settings.ui.MetroColors
import app.metrokeys.settings.ui.MetroTheme
import app.metrokeys.settings.ui.NavDirection
import app.metrokeys.settings.ui.OverlayHost
import app.metrokeys.settings.ui.Turnstile
import app.metrokeys.settings.ui.TurnstileSpec
import app.metrokeys.settings.ui.observeDowns

/**
 * The MetroKeys settings app (spec 07): one activity with an in-app back stack of pages (Navigation 3, saved
 * across configuration changes and process death), turnstile transitions with predictive back, overlays for
 * pickers and message boxes, edge-to-edge. It is the launcher activity and the input method's settings activity.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private val setup = mutableStateOf(SetupState(enabled = false, selected = false))

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        store.start()
        for (key in listOf(Settings.Secure.ENABLED_INPUT_METHODS, Settings.Secure.DEFAULT_INPUT_METHOD)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        }
        refresh()
        setContent { App() }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        store.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        store.checkContactsPermission()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the system input method chooser is a dialog that does not pause the activity (07 §9.3)
        if (hasFocus) refresh()
    }

    private fun refresh() {
        setup.value = SetupState.read(this)
        if (this::store.isInitialized) store.resumed()
    }

    @Composable
    private fun App() {
        val dark = isSystemInDarkTheme()
        store.resumes
        val colors = MetroColors.of(dark, store.appAccent())
        MetroTheme(colors, store.fonts()) {
            val backStack = rememberSaveable(saver = stackSaver) { mutableStateListOf(Pages.MAIN) }
            val direction = remember { NavDirection() }
            val overlays = remember { OverlayHost() }
            val dismiss = remember { DismissRegistry() }
            val navigator = remember(backStack) {
                Navigator { page ->
                    if (backStack.lastOrNull() != page) {
                        direction.pop = false
                        backStack.add(page)
                    }
                }
            }
            CompositionLocalProvider(
                LocalStore provides store,
                LocalNavigator provides navigator,
                LocalOverlayHost provides overlays,
                LocalDismissRegistry provides dismiss,
                LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.4f)),
            ) {
                Box(Modifier.fillMaxSize().background(colors.page).observeDowns(dismiss)) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = {
                            if (backStack.size > 1) {
                                direction.pop = true
                                backStack.removeAt(backStack.lastIndex)
                            }
                        },
                        transitionSpec = { TurnstileSpec.none },
                        popTransitionSpec = { TurnstileSpec.none },
                        predictivePopTransitionSpec = { TurnstileSpec.none },
                        entryProvider = { key ->
                            NavEntry(key) { k -> Turnstile(k, backStack, direction) { PageContent(k) } }
                        },
                    )
                    overlays.Render()
                }
            }
        }
    }

    @Composable
    private fun PageContent(key: String) {
        when (key) {
            Pages.LANGUAGES -> LanguagesPage()
            Pages.SUGGESTIONS -> SuggestionsPage()
            Pages.TYPING -> TypingPage()
            Pages.LAYOUT -> LayoutPage()
            Pages.APP_BAR_BUTTONS -> AppBarButtonsPage()
            Pages.SOUND -> SoundPage()
            Pages.SOUND_PACK -> SoundPackPage()
            Pages.THEME -> ThemePage()
            Pages.FONT -> FontPage()
            Pages.DATA -> DataPage()
            Pages.LICENSES -> LicensesPage()
            Pages.LEARNED_WORDS -> LearnedWordsPage()
            else -> MainPage(setup.value)
        }
    }

    companion object {
        private val stackSaver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { saved -> mutableStateListOf<String>().apply { addAll(saved.filter { it in Pages.ALL }.ifEmpty { listOf(Pages.MAIN) }) } },
        )
    }
}
