package ua.starlink.reader.qr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ua.starlink.reader.R
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Прямокутник зони у координатах екрана разом із розміром самого екрана. */
private class ZoneSnapshot(
    val viewW: Float,
    val viewH: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Сканування QR / Data Matrix / штрихкоду. Розпізнавання офлайн, через ML Kit.
 *
 * Дві речі, які варто знати про поведінку:
 *
 * 1. ML Kit ніколи не кидає виняток із process() — він повертає *провалений* Task.
 *    Тому тут обов'язковий addOnFailureListener: без нього будь-яка його помилка
 *    зникала беззвучно, і виглядало це як «камера працює, коди не читаються».
 *
 * 2. InputImage ігнорує cropRect, тож звузити зону аналізу на рівні кадру не можна.
 *    Замість цього аналізуємо весь кадр, а зону застосовуємо як фільтр: беремо лише
 *    ті коди, чий центр потрапив у рамку. Розпізнавання від цього не гіршає, зате
 *    на етикетці, де поруч Data Matrix і Code 128, оператор сам вирішує, який брати.
 *
 * 3. Кожен другий кадр аналізується з інвертованою яскравістю. На коробках Starlink
 *    трапляється Data Matrix, надрукований світлим по чорному, а всі декодери —
 *    і ML Kit теж — шукають темні модулі на світлому тлі й такий код не бачать.
 *    Перевертання яскравості робить його звичайним. Кадри чергуються, щоб не
 *    подвоювати роботу: затримка розпізнавання від цього непомітна.
 */
class QrScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var zoneView: ZoneView
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var torchButton: Button
    private lateinit var prefs: SharedPreferences

    private lateinit var confirmPanel: LinearLayout
    private lateinit var confirmValue: TextView
    private lateinit var confirmMeta: TextView

    private val delivered = AtomicBoolean(false)

    /** Код знайдено, чекаємо підтвердження — нові кадри не аналізуємо. */
    private val paused = AtomicBoolean(false)
    private var pendingValue: String? = null

    private val frames = AtomicLong()

    @Volatile
    private var zoneSnapshot: ZoneSnapshot? = null

    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Адресований Lazy: щоб onDestroy не створював сканер, якщо його так і не торкнулись.
    private val scannerLazy: Lazy<BarcodeScanner> = lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                // Усі 13 символогій, які вміє ML Kit: QR, Data Matrix, Aztec,
                // PDF417 і вся лінійка штрихкодів. На етикетках Starlink уже
                // трапляються щонайменше три різні, тож обмежувати список немає
                // сенсу — зайвий формат коштує лише трохи часу на кадр, а хибне
                // спрацювання відсікає рамка зони та підтвердження перед записом.
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }
    private val scanner: BarcodeScanner by scannerLazy

    /**
     * Короткий сигнал у момент знаходження коду.
     *
     * Через STREAM_MUSIC, а не STREAM_NOTIFICATION: на монтажі телефон часто
     * стоїть на вібрації, а підтвердження сканування має бути чутним. Гучність
     * усе одно підпорядкована системному повзунку медіа.
     *
     * Створення ToneGenerator на деяких пристроях кидає виняток, якщо аудіо
     * зайняте, тому загорнуто в runCatching — без звуку сканер працює далі.
     */
    private val toneLazy: Lazy<ToneGenerator?> = lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }
            .onFailure { Log.w(TAG, "ToneGenerator unavailable", it) }
            .getOrNull()
    }
    private val tone: ToneGenerator? by toneLazy

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_needed), Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val progress = intent.getStringExtra(EXTRA_PROGRESS).orEmpty()

        previewView = PreviewView(this).apply {
            // Оператор крутить коробку в руках — екран не має гаснути.
            keepScreenOn = true
            // FILL_CENTER лишаємо: аналіз іде в 4:3 і бачить ширше, ніж показано,
            // тож усе видиме на екрані гарантовано потрапляє в кадр розпізнавача.
            layoutParams = matchParent()
        }

        zoneView = ZoneView(this).apply {
            layoutParams = matchParent()
            sizeFraction = prefs.getFloat(KEY_ZONE_SIZE, 0.55f)
            centerXFraction = prefs.getFloat(KEY_ZONE_CX, 0.5f)
            centerYFraction = prefs.getFloat(KEY_ZONE_CY, 0.5f)
            onTap = { x, y -> focusAt(x, y) }
            onZoneChanged = { snapshot ->
                zoneSnapshot = snapshot
                prefs.edit()
                    .putFloat(KEY_ZONE_SIZE, sizeFraction)
                    .putFloat(KEY_ZONE_CX, centerXFraction)
                    .putFloat(KEY_ZONE_CY, centerYFraction)
                    .apply()
            }
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(INK)
                addView(previewView)
                addView(zoneView)
                addView(buildHeader(title, progress))
                addView(buildFooter())
                addView(buildConfirmPanel())
            }
        )

        // Створюємо сканер тут, на головному потоці, щоб збій ініціалізації ML Kit
        // був видимий одразу, а не ховався всередині фонового аналізатора.
        try {
            Log.i(TAG, "scanner ready: ${scanner.javaClass.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "BarcodeScanning.getClient FAILED", t)
            Toast.makeText(
                this,
                getString(R.string.scanner_unavailable, t.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------ інтерфейс

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun buildHeader(title: String, progress: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(SCRIM)
        setPadding(PAD, PAD, PAD, PAD)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP }

        if (progress.isNotBlank()) addView(label(progress.uppercase(), 12f, ACCENT))
        addView(label(title.ifBlank { getString(R.string.scan_title_default) }, 21f, INK_TEXT))
    }

    private fun buildFooter() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(SCRIM)
        setPadding(PAD, PAD / 2, PAD, PAD / 2)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM }

        addView(label(getString(R.string.scan_hint), 13f, MUTED))

        addView(
            sliderRow(getString(R.string.scan_zone), (prefs.getFloat(KEY_ZONE_SIZE, 0.55f) - ZONE_MIN) / ZONE_SPAN) { v ->
                zoneView.sizeFraction = ZONE_MIN + v * ZONE_SPAN
            }
        )
        addView(
            sliderRow(getString(R.string.scan_zoom), prefs.getFloat(KEY_ZOOM, 0f)) { v ->
                camera?.cameraControl?.setLinearZoom(v)
                prefs.edit().putFloat(KEY_ZOOM, v).apply()
            }
        )

        torchButton = Button(this@QrScanActivity).apply {
            text = getString(R.string.torch)
            setTextColor(INK_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        addView(
            LinearLayout(this@QrScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    torchButton,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    Button(this@QrScanActivity).apply {
                        text = getString(R.string.action_skip)
                        setTextColor(INK_TEXT)
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener {
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        )
    }

    /** Рядок «підпис + повзунок». Значення нормалізоване 0..1. */
    private fun sliderRow(
        name: String,
        initial: Float,
        onChange: (Float) -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            label(name, 13f, MUTED).apply { minWidth = PAD * 2 },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        addView(
            SeekBar(this@QrScanActivity).apply {
                max = 100
                progress = (initial.coerceIn(0f, 1f) * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        onChange(value / 100f)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun buildConfirmPanel(): LinearLayout {
        confirmValue = label("", 22f, INK_TEXT).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        confirmMeta = label("", 13f, MUTED).apply { gravity = Gravity.CENTER }

        confirmPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(CONFIRM_SCRIM)
            setPadding(PAD, PAD, PAD, PAD)
            // Панель перекриває все: поки не підтвердили, до камери торкатись нічого.
            isClickable = true
            visibility = View.GONE
            layoutParams = matchParent()

            addView(label(getString(R.string.scan_found), 13f, ACCENT).apply { gravity = Gravity.CENTER })
            addView(confirmValue)
            addView(confirmMeta)
            addView(
                label(getString(R.string.scan_verify), 13f, MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, PAD / 2, 0, PAD)
                }
            )
            addView(
                LinearLayout(this@QrScanActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        Button(this@QrScanActivity).apply {
                            text = getString(R.string.action_again)
                            setTextColor(MUTED)
                            setBackgroundColor(Color.TRANSPARENT)
                            setOnClickListener { resumeScanning() }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        Button(this@QrScanActivity).apply {
                            text = getString(R.string.action_confirm)
                            setTextColor(ACCENT)
                            setBackgroundColor(Color.TRANSPARENT)
                            setOnClickListener { pendingValue?.let { deliver(it) } }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
            )
        }
        return confirmPanel
    }

    private fun label(text: String, sizeSp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(0, 4, 0, 4)
    }

    // ------------------------------------------------------------------ зона

    /**
     * Затемнення поза рамкою + кутики. Рамку можна тягнути пальцем;
     * короткий тап без руху — це запит фокуса.
     */
    private class ZoneView(context: Context) : View(context) {

        var sizeFraction = 0.55f
            set(value) {
                field = value.coerceIn(ZONE_MIN, 1f)
                invalidate()
                publish()
            }

        var centerXFraction = 0.5f
        var centerYFraction = 0.5f

        var onTap: ((Float, Float) -> Unit)? = null
        var onZoneChanged: ((ZoneSnapshot) -> Unit)? = null

        private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            color = ACCENT
        }
        private val shade = Paint().apply { color = ZONE_SHADE }

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var dragging = false
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f

        fun zoneRect(): RectF {
            val side = min(width, height) * sizeFraction
            val half = side / 2f
            val cx = (centerXFraction * width).coerceIn(half, max(half, width - half))
            val cy = (centerYFraction * height).coerceIn(half, max(half, height - half))
            return RectF(cx - half, cy - half, cx + half, cy + half)
        }

        private fun publish() {
            if (width == 0 || height == 0) return
            val r = zoneRect()
            onZoneChanged?.invoke(
                ZoneSnapshot(width.toFloat(), height.toFloat(), r.left, r.top, r.right, r.bottom)
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            publish()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val box = zoneRect()
            val w = width.toFloat()
            val h = height.toFloat()

            // Затемнюємо все, що поза рамкою, — видно, куди дивиться фільтр.
            canvas.drawRect(0f, 0f, w, box.top, shade)
            canvas.drawRect(0f, box.bottom, w, h, shade)
            canvas.drawRect(0f, box.top, box.left, box.bottom, shade)
            canvas.drawRect(box.right, box.top, w, box.bottom, shade)

            val arm = box.width() * 0.14f
            canvas.drawLine(box.left, box.top, box.left, box.top + arm, bracket)
            canvas.drawLine(box.left, box.top, box.left + arm, box.top, bracket)
            canvas.drawLine(box.right, box.top, box.right, box.top + arm, bracket)
            canvas.drawLine(box.right, box.top, box.right - arm, box.top, bracket)
            canvas.drawLine(box.left, box.bottom, box.left, box.bottom - arm, bracket)
            canvas.drawLine(box.left, box.bottom, box.left + arm, box.bottom, bracket)
            canvas.drawLine(box.right, box.bottom, box.right, box.bottom - arm, bracket)
            canvas.drawLine(box.right, box.bottom, box.right - arm, box.bottom, bracket)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    moved = false
                    dragging = zoneRect().contains(event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(event.x - downX, event.y - downY) > slop) moved = true
                    if (dragging && moved && width > 0 && height > 0) {
                        centerXFraction += (event.x - lastX) / width
                        centerYFraction += (event.y - lastY) / height
                        lastX = event.x
                        lastY = event.y
                        invalidate()
                        publish()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        performClick()
                        onTap?.invoke(event.x, event.y)
                    }
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** Чи потрапив центр коду в рамку. Кадр ML Kit уже розвернутий «як на екрані». */
    private fun inZone(barcode: Barcode, imageW: Int, imageH: Int): Boolean {
        val snap = zoneSnapshot ?: return true
        val box = barcode.boundingBox ?: return true
        if (imageW <= 0 || imageH <= 0 || snap.viewW <= 0f || snap.viewH <= 0f) return true

        // PreviewView у FILL_CENTER масштабує кадр «на заповнення» й обрізає надлишок —
        // повторюємо це перетворення, щоб екранна рамка лягла на координати кадру.
        val scale = max(snap.viewW / imageW, snap.viewH / imageH)
        val dx = (snap.viewW - imageW * scale) / 2f
        val dy = (snap.viewH - imageH * scale) / 2f

        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        return cx >= (snap.left - dx) / scale &&
            cx <= (snap.right - dx) / scale &&
            cy >= (snap.top - dy) / scale &&
            cy <= (snap.bottom - dy) / scale
    }

    // ------------------------------------------------------------------ камера

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Типово CameraX віддає аналізу 640x480 — на такій картинці дрібний
                // Data Matrix з етикетки просто не має достатньо пікселів. 1280x960 у 4:3:
                // ширше за 16:9 і не виходить за гарантовані комбінації потоків на
                // слабких камерах, на відміну від 1600x1200.
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .build()
                    .also {
                        imageAnalysis = it
                        it.setAnalyzer(analysisExecutor, BarcodeAnalyzer())
                    }

                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                camera = cam

                Log.i(
                    TAG,
                    "bound: analysisRes=${analysis.resolutionInfo?.resolution} " +
                        "rot=${analysis.resolutionInfo?.rotationDegrees} " +
                        "outFmt=${analysis.outputImageFormat} " +
                        "maxZoom=${cam.cameraInfo.zoomState.value?.maxZoomRatio}"
                )

                // Зум, збережений з минулого разу: дрібний Data Matrix інакше не взяти.
                cam.cameraControl.setLinearZoom(prefs.getFloat(KEY_ZOOM, 0f))

                setUpTorch(cam)

                // Один поштовх автофокусу, коли з'явиться матриця перетворення.
                // До STREAMING фабрика точок повертає недійсну точку й запит фокуса
                // мовчки нічого не робить.
                previewView.previewStreamState.observe(this) { state ->
                    if (state == PreviewView.StreamState.STREAMING) {
                        focusAt(previewView.width / 2f, previewView.height / 2f)
                    }
                }

                // Відповідає на питання «чи аналізатор узагалі працює» без adb.
                previewView.postDelayed({
                    if (!isFinishing && frames.get() == 0L) {
                        Log.e(TAG, "WATCHDOG: no frames after 3s — ImageAnalysis is not delivering")
                    }
                }, 3_000L)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                if (isFinishing || isDestroyed) return@addListener
                Toast.makeText(
                    this,
                    getString(R.string.camera_unavailable, e.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (previewView.width == 0 || previewView.height == 0) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener(
            { runCatching { future.get() }.onFailure { Log.w(TAG, "focus failed", it) } },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun setUpTorch(cam: Camera) {
        if (!cam.cameraInfo.hasFlashUnit()) {
            torchButton.visibility = View.GONE
            return
        }
        torchButton.visibility = View.VISIBLE
        cam.cameraInfo.torchState.observe(this) { state ->
            torchButton.text = getString(
                if (state == TorchState.ON) R.string.torch_on else R.string.torch
            )
        }
        torchButton.setOnClickListener {
            cam.cameraControl.enableTorch(cam.cameraInfo.torchState.value != TorchState.ON)
        }
    }

    // ------------------------------------------------------------------ результат

    /** Код знайдено — зупиняємось і показуємо його оператору на звірку. */
    private fun proposeResult(value: String, format: Int) {
        if (!paused.compareAndSet(false, true)) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            pendingValue = value
            confirmValue.text = value
            confirmMeta.text = getString(R.string.scan_type, formatName(format))
            confirmPanel.visibility = View.VISIBLE

            // Звук і вібрація разом: у шумі чути сигнал, у тиші відчутно поштовх.
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) }
            zoneView.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            )
        }
    }

    private fun resumeScanning() {
        pendingValue = null
        confirmPanel.visibility = View.GONE
        paused.set(false)
    }

    /** Назви символогій — власні імена, не перекладаються. */
    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        else -> getString(R.string.code_generic)
    }

    private fun deliver(value: String) {
        if (!delivered.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
        finish()
    }

    override fun onDestroy() {
        // Спершу прибираємо аналізатор, щоб нові кадри не прилетіли до вже
        // зупиненого виконавця й закритого сканера.
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        camera = null
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        if (scannerLazy.isInitialized()) scannerLazy.value.close()
        if (toneLazy.isInitialized()) toneLazy.value?.release()
    }

    // ------------------------------------------------------------------ аналіз кадру

    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val closed = AtomicBoolean(false)
            fun closeOnce() {
                if (closed.compareAndSet(false, true)) imageProxy.close()
            }

            try {
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    Log.w(TAG, "analyze: imageProxy.image == null, fmt=${imageProxy.format}")
                    closeOnce()
                    return
                }
                if (paused.get() || delivered.get()) {
                    closeOnce()
                    return
                }

                val n = frames.incrementAndGet()
                val rotation = imageProxy.imageInfo.rotationDegrees
                val loud = n <= 2L || n % 30L == 0L

                // Після повороту ML Kit бачить кадр «як на екрані», тож для порівняння
                // з рамкою сторони треба поміняти місцями.
                val upright = rotation == 90 || rotation == 270
                val imageW = if (upright) mediaImage.height else mediaImage.width
                val imageH = if (upright) mediaImage.width else mediaImage.height

                if (loud) {
                    Log.i(
                        TAG,
                        "frame #$n ${mediaImage.width}x${mediaImage.height} " +
                            "fmt=${mediaImage.format} rot=$rotation"
                    )
                }

                // Парні кадри — інвертовані: так ловляться і звичайні коди,
                // і надруковані світлим по чорному.
                val useInverted = n % 2L == 0L
                val image = if (useInverted) {
                    val nv21 = invertedLuma(mediaImage)
                    if (nv21 == null) {
                        closeOnce()
                        return
                    }
                    InputImage.fromByteArray(
                        nv21,
                        mediaImage.width,
                        mediaImage.height,
                        rotation,
                        InputImage.IMAGE_FORMAT_NV21,
                    )
                } else {
                    InputImage.fromMediaImage(mediaImage, rotation)
                }

                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val accepted = barcodes.filter { inZone(it, imageW, imageH) }
                        if (barcodes.isNotEmpty() || loud) {
                            // Самі значення не логуємо — це серійні номери комплектів.
                            Log.d(
                                TAG,
                                "ok #$n${if (useInverted) " inv" else ""} -> " +
                                    "${barcodes.size} (in zone ${accepted.size}) " +
                                    barcodes.joinToString {
                                        "fmt=${it.format} rawLen=${it.rawValue?.length}"
                                    }
                            )
                        }
                        for (barcode in accepted) {
                            val value = barcode.rawValue ?: barcode.decodeFallback()
                            if (!value.isNullOrBlank()) {
                                proposeResult(value, barcode.format)
                                break
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        val code = (e as? MlKitException)?.errorCode
                        Log.e(TAG, "FAIL #$n code=$code ${e.message}", e)
                    }
                    .addOnCompleteListener { closeOnce() }
            } catch (t: Throwable) {
                // CameraX не ловить винятки з analyze() — виняток звідси вбив би процес.
                Log.e(TAG, "analyze() threw", t)
                closeOnce()
            }
        }
    }

    /**
     * Копія кадру з інвертованою яскравістю у форматі NV21.
     *
     * Декодеру колір не потрібен, тому беремо лише Y-площину (яскравість) і
     * заповнюємо хрому нейтральним сірим. Так само надійно, але вдвічі менше
     * роботи, ніж повне перетворення кольору.
     *
     * @return null, якщо кадр не в очікуваному форматі — тоді просто пропускаємо
     *   цей кадр, наступний піде звичайним шляхом.
     */
    private fun invertedLuma(image: android.media.Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null

        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        val row = ByteArray(rowStride)

        var offset = 0
        for (y in 0 until height) {
            val position = y * rowStride
            if (position >= buffer.limit()) break
            buffer.position(position)
            val available = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, available)

            var x = 0
            var src = 0
            while (x < width && src < available) {
                out[offset++] = (255 - (row[src].toInt() and 0xFF)).toByte()
                x++
                src += pixelStride
            }
            // Якщо рядок несподівано обірвався — добиваємо нейтральним.
            while (x < width) {
                out[offset++] = NEUTRAL
                x++
            }
        }

        out.fill(NEUTRAL, ySize)
        return out
    }

    /** rawValue порожній, якщо вміст не UTF-8. Байти при цьому є — витягуємо їх. */
    private fun Barcode.decodeFallback(): String? {
        val bytes = rawBytes ?: return null
        if (bytes.isEmpty()) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    companion object {
        const val EXTRA_RESULT = "scan_result"
        private const val EXTRA_TITLE = "scan_title"
        private const val EXTRA_PROGRESS = "scan_progress"
        private const val PAD = 44
        private const val TAG = "QrScan"

        /** Нейтральний сірий: ним заповнюємо хрому й «хвости» рядків. */
        private const val NEUTRAL: Byte = -128

        /** Гучність сигналу, 0..100 від системної гучності медіа. */
        private const val TONE_VOLUME = 85

        /** Тривалість сигналу, мс. Довший заважає, коротший губиться в шумі. */
        private const val BEEP_MS = 160

        private const val PREFS = "qr_scanner"
        private const val KEY_ZONE_SIZE = "zone_size"
        private const val KEY_ZONE_CX = "zone_cx"
        private const val KEY_ZONE_CY = "zone_cy"
        private const val KEY_ZOOM = "zoom"

        /** Найменша рамка — 20% меншої сторони екрана. */
        private const val ZONE_MIN = 0.2f
        private const val ZONE_SPAN = 0.8f

        private const val INK = 0xFF06080D.toInt()
        private const val SCRIM = 0xE60A0E16.toInt()
        private const val CONFIRM_SCRIM = 0xF20A0E16.toInt()
        private const val ZONE_SHADE = 0x800A0E16.toInt()
        private const val ACCENT = 0xFF4C8DFF.toInt()
        private const val INK_TEXT = 0xFFF3F6FB.toInt()
        private const val MUTED = 0xFF8A93A6.toInt()

        fun intent(context: Context, title: String, progress: String): Intent =
            Intent(context, QrScanActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PROGRESS, progress)
    }
}
