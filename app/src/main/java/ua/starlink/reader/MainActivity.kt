package ua.starlink.reader

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.launch
import ua.starlink.reader.ads.AdsConsent
import ua.starlink.reader.data.QrStep
import ua.starlink.reader.qr.QrScanActivity
import ua.starlink.reader.ui.AppActions
import ua.starlink.reader.ui.AppRoot
import ua.starlink.reader.ui.StarlinkReaderTheme
import ua.starlink.reader.util.Sharing

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var qrLauncher: ActivityResultLauncher<Intent>

    /** Запис, який зараз заповнюється. */
    private var captureUid: String? = null

    /** Крок, сканер якого зараз відкритий. */
    private var currentStep: QrStep? = null

    /** Кроки сканування, що лишились у поточному ланцюжку. */
    private val remainingSteps = mutableListOf<QrStep>()

    /** Чи це повний прохід «Почати зчитування» (тоді в кінці буде опитування мережі). */
    private var chainMode = false

    /** Скільки кроків усього в поточному ланцюжку — для підпису «Крок 2 з 4». */
    private var totalSteps = 0
    private var doneSteps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreChain(savedInstanceState)

        qrLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uid = captureUid
            val step = currentStep
            currentStep = null

            val raw = result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)
            if (result.resultCode == RESULT_OK && raw != null && uid != null && step != null) {
                viewModel.applyScan(uid, step, Sharing.normalizeScan(step, raw))
            }
            // Пропуск або скасування не обриває ланцюжок — просто йдемо далі.
            advance()
        }

        // Застосунок завжди темний, тож іконки системних панелей теж світлі.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // Спершу згода, і лише потім SDK реклами — інакше він звернеться до
        // Google раніше, ніж користувач щось вирішив.
        AdsConsent.gather(this)

        lifecycleScope.launch {
            viewModel.reviewRequests.collect { requestReview() }
        }

        setContent {
            StarlinkReaderTheme {
                val state by viewModel.state.collectAsState()
                val actions = remember { buildActions() }
                AppRoot(state = state, actions = actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLink()
    }

    /**
     * Офіційний In-App Review API: показує рідну картку оцінки Google Play
     * без виходу із застосунку. Google сам вирішує, чи показати її саме
     * зараз (діє власний ліміт частоти) — колбека про фактичний показ нема,
     * тож помилки тут просто ігноруються як некритичні.
     */
    private fun requestReview() {
        val manager = ReviewManagerFactory.create(this)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                manager.launchReviewFlow(this, task.result)
            }
        }
    }

    // ------------------------------------------------------------------ ланцюжок кроків

    /**
     * Ланцюжок кроків живе в Activity, тож його треба пережити поворот екрана —
     * інакше результат сканування нікуди не запишеться.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_UID, captureUid)
        outState.putString(KEY_STEP, currentStep?.name)
        outState.putStringArrayList(KEY_REMAINING, ArrayList(remainingSteps.map { it.name }))
        outState.putBoolean(KEY_CHAIN, chainMode)
        outState.putInt(KEY_TOTAL, totalSteps)
        outState.putInt(KEY_DONE, doneSteps)
    }

    private fun restoreChain(saved: Bundle?) {
        if (saved == null) return
        captureUid = saved.getString(KEY_UID)
        currentStep = saved.getString(KEY_STEP)?.let { name ->
            QrStep.entries.firstOrNull { it.name == name }
        }
        remainingSteps.clear()
        saved.getStringArrayList(KEY_REMAINING)?.forEach { name ->
            QrStep.entries.firstOrNull { it.name == name }?.let(remainingSteps::add)
        }
        chainMode = saved.getBoolean(KEY_CHAIN)
        totalSteps = saved.getInt(KEY_TOTAL)
        doneSteps = saved.getInt(KEY_DONE)
    }

    private fun startCapture() {
        val settings = viewModel.state.value.settings
        if (!settings.anyEnabled) return

        val uid = viewModel.beginCapture()
        captureUid = uid
        chainMode = true
        remainingSteps.clear()
        remainingSteps.addAll(settings.qrSteps)
        doneSteps = 0
        totalSteps = settings.qrSteps.size + if (settings.needsNetwork) 1 else 0
        advance()
    }

    private fun advance() {
        val uid = captureUid
        if (uid == null) {
            remainingSteps.clear()
            return
        }

        if (remainingSteps.isNotEmpty()) {
            val step = remainingSteps.removeAt(0)
            doneSteps++
            currentStep = step
            qrLauncher.launch(
                QrScanActivity.intent(
                    context = this,
                    title = getString(step.titleRes),
                    progress = if (totalSteps > 1) {
                        getString(R.string.step_progress, doneSteps, totalSteps)
                    } else {
                        ""
                    },
                    demoValue = viewModel.demoScanValue(uid, step),
                )
            )
            return
        }

        captureUid = null
        if (chainMode) {
            chainMode = false
            viewModel.runNetworkStep(uid, isNewCapture = true)
        }
    }

    private fun scanSingle(uid: String, step: QrStep) {
        captureUid = uid
        chainMode = false
        remainingSteps.clear()
        totalSteps = 1
        doneSteps = 1
        currentStep = step
        qrLauncher.launch(
            QrScanActivity.intent(
                context = this,
                title = getString(step.titleRes),
                progress = "",
                demoValue = viewModel.demoScanValue(uid, step),
            )
        )
    }

    /** У демо кожне надсилання починається з позначки, що дані вигадані. */
    private fun share(text: String) {
        val marked = if (viewModel.state.value.demo) Sharing.markDemo(this, text) else text
        Sharing.share(this, marked)
    }

    private fun buildActions() = AppActions(
        onStartCapture = ::startCapture,
        onRefreshLink = viewModel::refreshLink,
        onOpenWifiSettings = {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        },
        onScanSingle = ::scanSingle,
        onFieldChange = viewModel::setField,
        onSettingsChange = viewModel::updateSettings,
        onRetryNetwork = viewModel::runNetworkStep,
        onDelete = viewModel::delete,
        onDeleteMany = viewModel::deleteAll,
        onMergeDuplicate = viewModel::mergeDuplicate,
        onKeepDuplicate = viewModel::keepDuplicateAsNew,
        onShare = ::share,
        onAllowRawProbe = viewModel::setAllowRawProbe,
        onDismissError = viewModel::dismissError,
        onRunSpeedTest = viewModel::runSpeedTest,
        onStartDemo = viewModel::startDemo,
        onExitDemo = viewModel::exitDemo,
    )

    private companion object {
        const val KEY_UID = "capture_uid"
        const val KEY_STEP = "capture_step"
        const val KEY_REMAINING = "capture_remaining"
        const val KEY_CHAIN = "capture_chain"
        const val KEY_TOTAL = "capture_total"
        const val KEY_DONE = "capture_done"
    }
}
