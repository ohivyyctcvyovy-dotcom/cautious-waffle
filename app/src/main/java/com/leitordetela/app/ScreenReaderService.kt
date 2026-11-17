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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenReaderService : Service(), TextToSpeech.OnInitListener {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var overlay: FrameLayout? = null
    private var selection: SelectionOverlay? = null
    private var backButton: TextView? = null
    private var readButton: TextView? = null
    private var backButtonParams: WindowManager.LayoutParams? = null
    private var readButtonParams: WindowManager.LayoutParams? = null
    private var tts: TextToSpeech? = null
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var translating = false
    private var lastSpoken = ""
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
            val src = TranslateLanguage.fromLanguageTag(selectedLanguage)
            if (src != null) translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
            )
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
        val b = TextView(this).apply {
            text = "455"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(0xff7152dc.toInt()); cornerRadius = dp(32).toFloat(); setStroke(dp(2), Color.WHITE) }
            setOnClickListener { openSelector() }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dp(62), dp(62), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(10) }
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
        addMovableControl("read", if (translating) "PARAR" else "LER TELA") {
            if (translating) stopLiveTranslation() else startLiveTranslation()
            readButton?.text = if (translating) "PARAR" else "LER TELA"
        }
    }

    private fun addMovableControl(key: String, label: String, action: () -> Unit) {
        val view = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(0xff6346bd.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(2), Color.WHITE) }
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
        stopLiveTranslation()
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        overlay = null; selection = null
        removeFloatingControls()
        showBubble()
    }

    private fun startLiveTranslation() {
        if (projection == null || selection == null) { openSelector(); return }
        if (selectedLanguage == "th") {
            Toast.makeText(this, "O modo contínuo para tailandês ainda não está disponível.", Toast.LENGTH_LONG).show()
            return
        }
        if (translator == null) { Toast.makeText(this, "Pacote de tradução não disponível. Instale o idioma no app.", Toast.LENGTH_LONG).show(); return }
        translating = true
        lastSpoken = ""
        readButton?.text = "PARAR"
        Toast.makeText(this, "Leitura por voz ativada somente na área marcada.", Toast.LENGTH_SHORT).show()
        scheduleFrame(250)
    }

    private fun stopLiveTranslation() {
        translating = false
        handler.removeCallbacks(frameTask)
        tts?.stop()
        readButton?.text = "LER TELA"
        busy.set(false)
    }

    private val frameTask = object : Runnable {
        override fun run() {
            if (!translating) return
            processNextFrame()
            if (translating) handler.postDelayed(this, 1400)
        }
    }
    private fun scheduleFrame(delay: Long) { handler.removeCallbacks(frameTask); handler.postDelayed(frameTask, delay) }

    private fun processNextFrame() {
        if (!busy.compareAndSet(false, true)) return
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) { busy.set(false); return }
        try {
            val full = imageToBitmap(image)
            val r = selection?.selectionRect()
            if (r == null) { full.recycle(); busy.set(false); return }
            val left = r.left.toInt().coerceIn(0, full.width - 1)
            val top = r.top.toInt().coerceIn(0, full.height - 1)
            val right = r.right.toInt().coerceIn(left + 1, full.width)
            val bottom = r.bottom.toInt().coerceIn(top + 1, full.height)
            val crop = Bitmap.createBitmap(full, left, top, right-left, bottom-top)
            full.recycle()
            val client = recognizer
            if (client == null) { crop.recycle(); busy.set(false); return }
            client.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { result ->
                    crop.recycle()
                    val raw = result.text
                    if (raw.isNotBlank()) {
                        translator?.translate(raw)?.addOnSuccessListener { translated ->
                            val clean = translated.replace(Regex("[\\p{P}\\p{S}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
                            if (clean.isNotBlank() && clean != lastSpoken) {
                                lastSpoken = clean
                                tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "455-live")
                            }
                            busy.set(false)
                        }?.addOnFailureListener { busy.set(false) } ?: run { busy.set(false) }
                    } else busy.set(false)
                }
                .addOnFailureListener { crop.recycle(); busy.set(false) }
        } catch (_: Exception) { image.close(); busy.set(false); return }
        image.close()
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
        private val rect = RectF(screenW * .08f, screenH * .20f, screenW * .92f, screenH * .68f)
        private var lastX = 0f
        private var lastY = 0f
        private var activeCorner = -1
        private val handleRadius get() = dp(24).toFloat()
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffb7a5ff.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val handleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff7152dc.toInt(); style = Paint.Style.FILL }
        private fun corners() = arrayOf(
            PointF(rect.left, rect.top), PointF(rect.right, rect.top),
            PointF(rect.left, rect.bottom), PointF(rect.right, rect.bottom)
        )
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val outside = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRect(rect, Path.Direction.CW)
            }
            canvas.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
            canvas.drawRect(rect, border)
            corners().forEach { p ->
                canvas.drawCircle(p.x, p.y, handleRadius, handleFill)
                canvas.drawCircle(p.x, p.y, dp(8).toFloat(), handleDot)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX=e.x; lastY=e.y
                    activeCorner = corners().indexOfFirst { kotlin.math.hypot(e.x-it.x, e.y-it.y) <= handleRadius * 1.7f }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=e.x-lastX; val dy=e.y-lastY
                    if (activeCorner >= 0) {
                        when (activeCorner) {
                            0 -> { rect.left=(rect.left+dx).coerceIn(0f, rect.right-dp(80)); rect.top=(rect.top+dy).coerceIn(0f, rect.bottom-dp(60)) }
                            1 -> { rect.right=(rect.right+dx).coerceIn(rect.left+dp(80), width.toFloat()); rect.top=(rect.top+dy).coerceIn(0f, rect.bottom-dp(60)) }
                            2 -> { rect.left=(rect.left+dx).coerceIn(0f, rect.right-dp(80)); rect.bottom=(rect.bottom+dy).coerceIn(rect.top+dp(60), height.toFloat()) }
                            3 -> { rect.right=(rect.right+dx).coerceIn(rect.left+dp(80), width.toFloat()); rect.bottom=(rect.bottom+dy).coerceIn(rect.top+dp(60), height.toFloat()) }
                        }
                    } else if (rect.contains(e.x, e.y)) {
                        val w=rect.width(); val h=rect.height()
                        val l=(rect.left+dx).coerceIn(0f, width-w)
                        val t=(rect.top+dy).coerceIn(0f, height-h)
                        rect.set(l,t,l+w,t+h)
                    }
                    lastX=e.x; lastY=e.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP -> { performClick(); return true }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        fun selectionRect() = RectF(rect)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) { tts?.language = Locale("pt", "BR"); tts?.setSpeechRate(.94f) }
    }

    private fun stopProjection() {
        stopLiveTranslation()
        closeOverlayOnly()
        removeBubble()
        display?.release(); display=null
        reader?.close(); reader=null
        val active=projection; projection=null; active?.stop()
        recognizer?.close(); recognizer=null
        translator?.close(); translator=null
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun closeOverlayOnly() { overlay?.let { try { wm?.removeView(it) } catch (_: Exception) { } }; overlay=null; selection=null }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        stopLiveTranslation(); closeOverlayOnly(); removeBubble()
        display?.release(); reader?.close(); projection?.stop()
        recognizer?.close(); translator?.close(); tts?.shutdown()
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
