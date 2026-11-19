package com.leitordetela.app

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.LanguagePair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ScreenReaderService : Service(), TextToSpeech.OnInitListener {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var overlay: FrameLayout? = null
    private var selection: SelectionOverlay? = null
    // Snapshot da região ativa: a leitura continua mesmo quando a janela de seleção é fechada.
    private var activeSelectionRect: RectF? = null
    private var backButton: TextView? = null
    private var readButton: TextView? = null
    private var backButtonParams: WindowManager.LayoutParams? = null
    private var readButtonParams: WindowManager.LayoutParams? = null
    private var tts: TextToSpeech? = null
    private var recognizer: TextRecognizer? = null
    private var foxletClient: Foxlet? = null
    private val translationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translationJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var translating = false
    private var captureAttempts = 0
    private var selectedLanguage = "en"
    private var screenW = 0
    private var screenH = 0
    private val channel = "455_capture"
    private val notificationId = 455

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> { showBubble(); return START_STICKY }
            ACTION_CAPTURE -> { openSelector(); return START_STICKY }
            ACTION_TRANSLATE -> { startLiveTranslation(); return START_STICKY }
            ACTION_STOP_TRANSLATION -> { stopLiveTranslation(); return START_STICKY }
            ACTION_STOP -> { stopProjection(); return START_NOT_STICKY }
        }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                   else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_DATA))
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        startForeground(notificationId, buildNotification())
        try {
            selectedLanguage = getSharedPreferences("455", MODE_PRIVATE).getString("source_language", "en") ?: "en"
            recognizer = when (selectedLanguage) {
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                "th" -> null
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
            val metrics = resources.displayMetrics
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopProjection() }
            }, handler)
            display = projection?.createVirtualDisplay("455-live-screen", screenW, screenH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
            handler.postDelayed({ showBubble() }, 400)
        } catch (_: Exception) { stopProjection() }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channel, "455 — tradução de tela", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 2, Intent(this, ScreenReaderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_455_launcher)
            .setContentTitle("455 — leitor de tela ativo")
            .setContentText("Toque no botão 455 para selecionar a região.")
            .setOngoing(true).addAction(0, "Encerrar", stop).build()
    }

    private fun showBubble() {
        if (bubble != null || projection == null || !android.provider.Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("455_overlay_positions", MODE_PRIVATE)
        val size = dp(38)
        val b = TextView(this).apply {
            text = "455"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(0xff7152dc.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.WHITE) }
            elevation = dp(5).toFloat()
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("bubble_x", (resources.displayMetrics.widthPixels - size - dp(8)).coerceAtLeast(0)).coerceIn(0, (resources.displayMetrics.widthPixels-size).coerceAtLeast(0))
                y = prefs.getInt("bubble_y", dp(150)).coerceIn(0, (resources.displayMetrics.heightPixels-size).coerceAtLeast(0))
            }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        b.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX=event.rawX; downY=event.rawY; startX=lp.x; startY=lp.y; dragged=false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(event.rawX-downX).toInt(); val dy=(event.rawY-downY).toInt()
                    if (kotlin.math.abs(dx)>dp(3) || kotlin.math.abs(dy)>dp(3)) dragged=true
                    if (dragged) {
                        lp.x=(startX+dx).coerceIn(0,(resources.displayMetrics.widthPixels-size).coerceAtLeast(0))
                        lp.y=(startY+dy).coerceIn(0,(resources.displayMetrics.heightPixels-size).coerceAtLeast(0))
                        try { wm?.updateViewLayout(b,lp) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) prefs.edit().putInt("bubble_x",lp.x).putInt("bubble_y",lp.y).apply()
                    else openSelector()
                    true
                }
                else -> true
            }
        }
        try { wm?.addView(b, lp); bubble = b } catch (_: Exception) { }
    }

    private fun removeBubble() { bubble?.let { try { wm?.removeView(it) } catch (_: Exception) { } }; bubble = null }

    private fun openSelector() {
        if (overlay != null || projection == null) return
        removeBubble()
        val frame = FrameLayout(this)
        val selector = SelectionOverlay()
        selection = selector
        frame.addView(selector, FrameLayout.LayoutParams(-1, -1))
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm?.addView(frame, lp); overlay = frame; showFloatingControls() } catch (_: Exception) { overlay = null; showBubble() }
    }

    private fun showFloatingControls() {
        addMovableControl("back", "VOLTAR") { closeSelector() }
        addMovableControl("read", "TRADUÇÃO") { startLiveTranslation() }
    }

    private fun addMovableControl(key: String, label: String, action: () -> Unit) {
        val view = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                val color = when (key) { "back" -> 0xffd32f2f.toInt(); "read" -> 0xff2e9d50.toInt(); else -> 0xff6346bd.toInt() }
                setColor(color); cornerRadius = dp(24).toFloat(); setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
        }
        val prefs = getSharedPreferences("455_overlay_positions", MODE_PRIVATE)
        val defaultX = dp(12)
        val defaultY = if (key == "back") dp(110) else dp(170)
        val params = WindowManager.LayoutParams(dp(112), dp(50),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("${key}_x", defaultX).coerceAtLeast(0)
                y = prefs.getInt("${key}_y", defaultY).coerceAtLeast(0)
            }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).coerceIn(0, (resources.displayMetrics.widthPixels - dp(112)).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (resources.displayMetrics.heightPixels - dp(50)).coerceAtLeast(0))
                        try { wm?.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) prefs.edit().putInt("${key}_x", params.x).putInt("${key}_y", params.y).apply()
                    else action()
                    true
                }
                else -> true
            }
        }
        try {
            wm?.addView(view, params)
            if (key == "back") { backButton = view; backButtonParams = params }
            else { readButton = view; readButtonParams = params }
        } catch (_: Exception) { }
    }

    private fun removeFloatingControls() {
        backButton?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        readButton?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        backButton = null; readButton = null
        backButtonParams = null; readButtonParams = null
    }

    private fun control(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply { setColor(0xff6346bd.toInt()); cornerRadius = dp(14).toFloat() }
        val params = LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) }
        layoutParams = params
        setOnClickListener { action() }
    }

    private fun closeSelector() {
        // Fechar a marcação não cancela a tradução já iniciada.
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        overlay = null; selection = null
        removeFloatingControls()
        showBubble()
    }

    private fun startLiveTranslation() {
        if (translating) return
        if (projection == null || selection == null) { openSelector(); return }
        val marked = selection?.selectionRect()
        if (marked == null || marked.width() < dp(20) || marked.height() < dp(20)) {
            Toast.makeText(this, "Primeiro marque a área que deseja traduzir.", Toast.LENGTH_LONG).show()
            return
        }
        // Congela a seleção no instante do toque: nenhuma outra região será consultada.
        activeSelectionRect = RectF(marked)
        translating = true
        captureAttempts = 0
        busy.set(false)
        readButton?.text = "LENDO..."
        handler.removeCallbacks(captureTask)
        handler.postDelayed(captureTask, 160)
    }

    private fun stopLiveTranslation() {
        translating = false
        translationJob?.cancel()
        translationJob = null
        handler.removeCallbacks(captureTask)
        tts?.stop()
        activeSelectionRect = null
        busy.set(false)
        readButton?.text = "TRADUÇÃO"
    }

    // Uma única captura por toque; tentativas extras só aguardam o próximo quadro da tela.
    private val captureTask = object : Runnable {
        override fun run() { captureAndRecognizeOnce() }
    }

    private fun captureAndRecognizeOnce() {
        if (!translating) return
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            captureAttempts++
            if (captureAttempts <= 8) handler.postDelayed(captureTask, 120)
            else finishOneShot("Não consegui capturar a tela. Tente tocar em TRADUÇÃO novamente.")
            return
        }
        try {
            val full = imageToBitmap(image)
            val r = activeSelectionRect?.let { RectF(it) }
            if (r == null) {
                full.recycle()
                finishOneShot("A área selecionada não está mais disponível.")
                return
            }
            val left = r.left.toInt().coerceIn(0, full.width - 1)
            val top = r.top.toInt().coerceIn(0, full.height - 1)
            val right = r.right.toInt().coerceIn(left + 1, full.width)
            val bottom = r.bottom.toInt().coerceIn(top + 1, full.height)
            var crop = Bitmap.createBitmap(full, left, top, right-left, bottom-top)
            full.recycle()
            // Amplia a região quando as letras são pequenas. Limita o tamanho
            // para evitar consumo excessivo de memória em aparelhos modestos.
            val minSide = minOf(crop.width, crop.height).toFloat()
            val maxSide = maxOf(crop.width, crop.height).toFloat()
            val scale = minOf(2.5f, maxOf(1f, 1000f / minSide), 2600f / maxSide).coerceAtLeast(1f)
            if (scale > 1.01f) {
                val enlarged = Bitmap.createScaledBitmap(
                    crop,
                    (crop.width * scale).toInt().coerceAtLeast(1),
                    (crop.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                crop.recycle()
                crop = enlarged
            }
            val client = recognizer
            if (client == null && selectedLanguage == "th") {
                // O ML Kit não oferece OCR tailandês neste fluxo; usa o Tesseract
                // já instalado pelo pacote de idioma, sem enviar a captura à internet.
                translationScope.launch {
                    val recognized = try {
                        withContext(Dispatchers.IO) {
                            if (!File(filesDir, "tessdata/tha.traineddata").exists()) {
                                throw IllegalStateException("Instale o OCR tailandês na tela inicial.")
                            }
                            val api = TessBaseAPI()
                            try {
                                if (!api.init(filesDir.absolutePath, "tha")) {
                                    throw IllegalStateException("Não foi possível iniciar o OCR tailandês.")
                                }
                                api.setImage(crop)
                                api.getUTF8Text() ?: ""
                            } finally {
                                api.end()
                                if (!crop.isRecycled) crop.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        if (!crop.isRecycled) crop.recycle()
                        withContext(Dispatchers.Main) {
                            if (translating) finishOneShot(e.localizedMessage ?: "Falha no OCR tailandês.")
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        if (!translating) return@withContext
                        val clean = normalizeRecognizedText(recognized)
                        if (clean.count { it.isLetterOrDigit() } < 2) {
                            finishOneShot("Não encontrei texto legível dentro da área marcada.")
                        } else {
                            translateOneShot(clean, "th")
                        }
                    }
                }
                return
            }
            if (client == null) {
                crop.recycle()
                finishOneShot("O reconhecedor desse idioma não está disponível.")
                return
            }
            client.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { result ->
                    crop.recycle()
                    if (!translating) return@addOnSuccessListener
                    val raw = normalizeRecognizedText(
                        result.textBlocks.filter { it.text.isNotBlank() }
                            .joinToString("\n") { block ->
                                block.lines.joinToString("\n") { line -> line.text.trim() }
                            }
                    )
                    if (raw.count { it.isLetterOrDigit() } < 2) {
                        finishOneShot("Não encontrei texto legível dentro da área marcada.")
                        return@addOnSuccessListener
                    }
                    // O idioma escolhido pelo usuário é a fonte de verdade.
                    // Identificação automática costuma errar em palavras/frases curtas.
                    translateOneShot(raw, normalizeFoxletLanguage(selectedLanguage))
                }
                .addOnFailureListener {
                    crop.recycle()
                    finishOneShot("Não consegui reconhecer as letras dessa área. Tente aumentar a seleção.")
                }
        } catch (_: Exception) {
            finishOneShot("Ocorreu um erro ao ler a área selecionada.")
        } finally {
            image.close()
        }
    }

    private fun normalizeRecognizedText(value: String): String {
        return value
            .replace('\u00ad', ' ')
            .replace(Regex("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{L})"), "")
            .replace(Regex("[\\t\\u00A0]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizeFoxletLanguage(code: String): String = when (code.lowercase().substringBefore("-")) {
        "zh" -> "zh-Hans"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        "hi" -> "hi"
        "th" -> "th"
        "vi" -> "vi"
        else -> code
    }

    // Glossário contextual para expressões que o modelo pode traduzir literalmente.
    private fun applyEnglishGlossary(value: String): String {
        return value
            .replace(Regex("(?i)\\bI'm\\s+(?:currently\\s+)?lactating\\b"), "I'm producing milk")
            .replace(Regex("(?i)\\bI am\\s+(?:currently\\s+)?lactating\\b"), "I am producing milk")
            .replace(Regex("(?i)\\blactating\\b"), "producing milk")
            .replace(Regex("(?i)\\blactation\\b"), "milk production")
    }

    private fun applyPortugueseGlossary(value: String): String {
        return value
            .replace(Regex("(?i)\\bestou\\s+(?:atualmente\\s+)?lactando\\b"), "estou produzindo leite")
            .replace(Regex("(?i)\\bestou\\s+em\\s+lactação\\b"), "estou produzindo leite")
            .replace(Regex("(?i)\\blactating\\b"), "produzindo leite")
    }

    private fun translateOneShot(raw: String, sourceCode: String) {
        if (!translating) return
        if (sourceCode == "pt") { speakOnce(raw); return }
        if (sourceCode !in setOf("en", "zh-Hans", "ja", "ko", "hi", "th", "vi")) {
            finishOneShot("Idioma não compatível. Confira o idioma escolhido no aplicativo.")
            return
        }
        readButton?.text = "BAIXANDO..."
        translationJob = translationScope.launch {
            try {
                val engine = foxletClient ?: Foxlet.create(applicationContext).also { foxletClient = it }
                val portugueseModel = engine.models.prepare(LanguagePair("en", "pt"))
                val translationInput = if (sourceCode == "en") applyEnglishGlossary(raw) else raw
                val translated = if (sourceCode == "en") {
                    engine.translator.translate(translationInput, portugueseModel)
                } else {
                    val sourceModel = engine.models.prepare(LanguagePair(sourceCode, "en"))
                    if (!translating) return@launch
                    readButton?.post { if (translating) readButton?.text = "TRADUZINDO..." }
                    engine.translator.translatePivot(translationInput, sourceModel, portugueseModel)
                }
                if (!translating) return@launch
                val spoken = applyPortugueseGlossary(translated).replace(Regex("[\\p{P}\\p{S}\\p{N}]+"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                withContext(Dispatchers.Main) {
                    if (!translating) return@withContext
                    if (spoken.length < 2) finishOneShot("A tradução não retornou uma frase legível.")
                    else speakOnce(spoken)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (translating) finishOneShot("Falha ao traduzir. Verifique a conexão para baixar os modelos ou tente novamente.")
                }
            }
        }
    }

    private fun speakOnce(text: String) {
        if (!translating) return
        val spoken = text.replace(Regex("[\\p{P}\\p{S}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (spoken.isNotBlank()) tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "455-one-shot")
        finishOneShot(null)
    }

    private fun finishOneShot(message: String?) {
        translating = false
        handler.removeCallbacks(captureTask)
        activeSelectionRect = null
        busy.set(false)
        readButton?.text = "TRADUÇÃO"
        if (!message.isNullOrBlank()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val padding = rowStride - pixelStride * image.width
        val wide = Bitmap.createBitmap(image.width + padding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        val result = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
        wide.recycle()
        return result
    }

    private inner class SelectionOverlay : View(this) {
        private var rect: RectF? = null
        private var lastX = 0f
        private var lastY = 0f
        private var activeHandle = -1
        private var creating = false
        private var moving = false
        private val minSize get() = dp(36).toFloat()
        private val handleRadius get() = dp(5).toFloat()
        private val touchRadius get() = dp(25).toFloat()
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffb7a5ff.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff7152dc.toInt() }
        private fun points(r: RectF) = arrayOf(
            PointF(r.left,r.top), PointF((r.left+r.right)/2f,r.top), PointF(r.right,r.top),
            PointF(r.right,(r.top+r.bottom)/2f), PointF(r.right,r.bottom),
            PointF((r.left+r.right)/2f,r.bottom), PointF(r.left,r.bottom),
            PointF(r.left,(r.top+r.bottom)/2f)
        )
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = rect
            if (r == null) {
                canvas.drawColor(0x44000000)
                return
            }
            val outside = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f,0f,width.toFloat(),height.toFloat(),Path.Direction.CW)
                addRect(r,Path.Direction.CW)
            }
            canvas.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
            canvas.drawRect(r,border)
            points(r).forEach { p ->
                canvas.drawCircle(p.x,p.y,handleRadius,handleFill)
                canvas.drawCircle(p.x,p.y,dp(2).toFloat(),handleDot)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX=e.x; lastY=e.y; creating=false; moving=false
                    val r=rect
                    activeHandle = if (r == null) -1 else points(r).indexOfFirst {
                        kotlin.math.hypot(e.x-it.x,e.y-it.y) <= touchRadius
                    }
                    if (r == null || (activeHandle == -1 && !r.contains(e.x,e.y))) {
                        rect=RectF(e.x,e.y,e.x,e.y)
                        creating=true
                    } else if (activeHandle == -1) moving=true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=e.x-lastX; val dy=e.y-lastY
                    val r=rect
                    if (r != null) {
                        if (creating) {
                            r.right=e.x; r.bottom=e.y
                            normalize(r)
                        } else if (moving) {
                            val w=r.width(); val h=r.height()
                            val l=(r.left+dx).coerceIn(0f,(width-w).coerceAtLeast(0f))
                            val t=(r.top+dy).coerceIn(0f,(height-h).coerceAtLeast(0f))
                            r.set(l,t,l+w,t+h)
                        } else if (activeHandle >= 0) resize(r,activeHandle,e.x,e.y)
                    }
                    lastX=e.x; lastY=e.y; invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val r=rect
                    if (creating && (r == null || r.width()<minSize || r.height()<minSize)) {
                        rect=null
                        Toast.makeText(this@ScreenReaderService,"Arraste para marcar uma área maior.",Toast.LENGTH_SHORT).show()
                    }
                    creating=false; moving=false; activeHandle=-1
                    invalidate(); performClick()
                    return true
                }
            }
            return true
        }
        private fun normalize(r: RectF) {
            val l=minOf(r.left,r.right).coerceIn(0f,width.toFloat())
            val t=minOf(r.top,r.bottom).coerceIn(0f,height.toFloat())
            val rr=maxOf(r.left,r.right).coerceIn(0f,width.toFloat())
            val bb=maxOf(r.top,r.bottom).coerceIn(0f,height.toFloat())
            r.set(l,t,rr,bb)
        }
        private fun resize(r: RectF, h: Int, x: Float, y: Float) {
            when(h) {
                0 -> { r.left=x.coerceIn(0f,r.right-minSize); r.top=y.coerceIn(0f,r.bottom-minSize) }
                1 -> r.top=y.coerceIn(0f,r.bottom-minSize)
                2 -> { r.right=x.coerceIn(r.left+minSize,width.toFloat()); r.top=y.coerceIn(0f,r.bottom-minSize) }
                3 -> r.right=x.coerceIn(r.left+minSize,width.toFloat())
                4 -> { r.right=x.coerceIn(r.left+minSize,width.toFloat()); r.bottom=y.coerceIn(r.top+minSize,height.toFloat()) }
                5 -> r.bottom=y.coerceIn(r.top+minSize,height.toFloat())
                6 -> { r.left=x.coerceIn(0f,r.right-minSize); r.bottom=y.coerceIn(r.top+minSize,height.toFloat()) }
                7 -> r.left=x.coerceIn(0f,r.right-minSize)
            }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        fun selectionRect(): RectF? = rect?.let { RectF(it) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val result = engine.setLanguage(Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("pt"))
            }
            engine.setSpeechRate(1.0f)
        }
    }

    private fun stopProjection() {
        stopLiveTranslation()
        closeOverlayOnly()
        removeBubble()
        display?.release(); display=null
        reader?.close(); reader=null
        val active=projection; projection=null; active?.stop()
        recognizer?.close(); recognizer=null
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun closeOverlayOnly() { overlay?.let { try { wm?.removeView(it) } catch (_: Exception) { } }; overlay=null; selection=null }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        stopLiveTranslation(); closeOverlayOnly(); removeBubble()
        display?.release(); reader?.close(); projection?.stop()
        recognizer?.close()
        val engine = foxletClient
        foxletClient = null
        CoroutineScope(Dispatchers.IO).launch { try { engine?.shutdown() } catch (_: Exception) { } }
        translationScope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_RESULT_CODE="result_code"
        const val EXTRA_DATA="projection_data"
        const val ACTION_CAPTURE="com.leitordetela.app.CAPTURE"
        const val ACTION_STOP="com.leitordetela.app.STOP"
        const val ACTION_SHOW_OVERLAY="com.leitordetela.app.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY="com.leitordetela.app.HIDE_OVERLAY"
        const val ACTION_TRANSLATE="com.leitordetela.app.TRANSLATE"
        const val ACTION_STOP_TRANSLATION="com.leitordetela.app.STOP_TRANSLATION"
    }
}
