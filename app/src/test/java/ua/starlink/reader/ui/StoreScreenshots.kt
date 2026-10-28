package ua.starlink.reader.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.starlink.reader.R
import ua.starlink.reader.SpeedTestPhase
import ua.starlink.reader.SpeedTestUiState
import ua.starlink.reader.UiState
import ua.starlink.reader.data.DemoData
import ua.starlink.reader.data.Reading
import ua.starlink.reader.net.LinkState
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Знімки екрана для сторінки в Google Play: справжній інтерфейс застосунку,
 * відмальований Robolectric без телефона, з демо-даними (у значеннях — DEMO).
 *
 * Звичайний прогін тестів їх пропускає. Оновити знімки:
 *   gradlew testDebugUnitTest -Pscreenshots --tests "*StoreScreenshots*"
 * Результат — PNG у play/screenshots/uk, 1080×1920 (9:16, як радить Play).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "uk-w411dp-h731dp-420dpi")
class StoreScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File("../play/screenshots/uk")

    private val noActions = AppActions(
        onStartCapture = {}, onRefreshLink = {}, onOpenWifiSettings = {},
        onScanSingle = { _, _ -> }, onFieldChange = { _, _, _ -> }, onSettingsChange = {},
        onRetryNetwork = {}, onDelete = {}, onDeleteMany = {}, onMergeDuplicate = {},
        onKeepDuplicate = {}, onShare = {}, onAllowRawProbe = {}, onDismissError = {},
        onRunSpeedTest = {}, onStartDemo = {}, onExitDemo = {},
        onStartWarehouse = {}, onExportCsv = {},
    )

    /** П'ять комплектів: три сьогодні, два вчора — щоб історія мала що групувати. */
    private val kits: List<Reading> by lazy {
        val app = RuntimeEnvironment.getApplication()
        val today = LocalDate.now()
        fun at(day: LocalDate, h: Int, m: Int) =
            day.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        listOf(
            Triple(5, at(today, 12, 5), "Офіс на Шевченка, 12 — тарілка на даху"),
            Triple(4, at(today, 10, 47), app.getString(R.string.demo_note_2)),
            Triple(3, at(today, 9, 12), ""),
            Triple(2, at(today.minusDays(1), 16, 30), app.getString(R.string.demo_note_1)),
            Triple(1, at(today.minusDays(1), 11, 20), ""),
        ).map { (seq, time, note) ->
            DemoData.reading(uid = "kit-$seq", seq = seq, timestamp = time, note = note)
        }
    }

    private val state by lazy {
        UiState(link = LinkState.READY, history = kits, currentUid = null)
    }

    @Before
    fun onlyOnRequest() {
        assumeTrue(
            "screenshots are rendered only with -Pscreenshots",
            System.getProperty("screenshots") == "true",
        )
        outDir.mkdirs()
    }

    /** Головний: меню згорнуте, щоб у кадр влізли кнопка й щойно зчитаний комплект. */
    @Test
    fun home() {
        compose.setContent {
            StarlinkReaderTheme { AppRoot(state.copy(currentUid = kits.first().uid), noActions) }
        }
        compose.onNodeWithText("згорнути", substring = true).performClick()
        save("01-home")
    }

    /** Меню «Що зчитувати» повністю, прокручене до кнопки «Почати зчитування». */
    @Test
    fun captureMenu() {
        compose.setContent { StarlinkReaderTheme { AppRoot(state, noActions) } }
        compose.onNodeWithText("Почати зчитування").performScrollTo()
        save("05-menu")
    }

    @Test
    fun capturedKit() = shot("02-kit") {
        AppRoot(state, noActions, initialScreen = Screen.Detail(kits.first().uid))
    }

    @Test
    fun history() = shot("03-history") {
        AppRoot(state, noActions, initialScreen = Screen.History)
    }

    @Test
    fun historyKitsOnly() {
        compose.setContent {
            StarlinkReaderTheme { AppRoot(state, noActions, initialScreen = Screen.History) }
        }
        compose.onNodeWithText("Вибрати").performClick()
        compose.onNodeWithText("Обрати всі").performClick()
        save("04-kits-only")
    }

    @Test
    fun speedTest() = shot("06-speed") {
        val done = SpeedTestUiState(
            phase = SpeedTestPhase.DONE,
            pingMs = 38,
            downloadMbps = 186.4,
            uploadMbps = 24.7,
        )
        AppRoot(state.copy(speedTest = done), noActions, initialScreen = Screen.SpeedTest)
    }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { StarlinkReaderTheme { content() } }
        save(name)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
