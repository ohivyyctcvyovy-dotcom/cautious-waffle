package com.leitordetela.app

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.nl.translate.*
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale
import kotlin.math.hypot

class ScreenReaderService : AccessibilityService(), TextToSpeech.OnInitListener {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var selector: View? = null
    private var tts: TextToSpeech? = null
    private val prefs by lazy { getSharedPreferences("455", MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createBubble()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
            tts?.setSpeechRate(0.94f)
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun createBubble() {
        val b = TextView(this).apply {
            text = "455"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(89, 75, 190))
            elevation = 12f
            setOnClickListener { showPanel() }
        }
        val p = WindowManager.LayoutParams(
            128, 128, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 250 }
        wm.addView(b, p)
        bubble = b
    }

    private fun showPanel() {
        removePanel()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.rgb(30, 27, 53))
        }
        box.addView(action("Selecionar área") {
            removePanel()
            capture { showSelector(it) }
        })
box.addView(action("Fechar") { removePanel() })
        val p = WindowManager.LayoutParams(
            270, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 390 }
        wm.addView(box, p)
        panel = box
    }

    private fun action(label: String, run: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { run() }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun capture(done: (Bitmap) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            sayToast("A captura exige Android 11 ou superior.")
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (wrapped == null) {
                        sayToast("Não foi possível preparar a captura.")
                        return
                    }
                    val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped.recycle()
                    done(bitmap)
                } catch (e: Exception) {
                    sayToast("Falha na captura: " + e.localizedMessage)
                }
            }
            override fun onFailure(errorCode: Int) {
                sayToast("A captura falhou. Confirme que o 455 está ativado na Acessibilidade.")
            }
        })
    }

    private fun showSelector(bitmap: Bitmap) {
        val root = FrameLayout(this)
        val frame = SelectionFrame(this, bitmap.width.toFloat(), bitmap.height.toFloat())
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.rgb(25, 23, 43))
        }
        controls.addView(action("Traduzir") {
            val r = frame.rect()
            closeSelector()
            val l = r.left.toInt().coerceIn(0, bitmap.width - 1)
            val t = r.top.toInt().coerceIn(0, bitmap.height - 1)
            val rr = r.right.toInt().coerceIn(l + 1, bitmap.width)
            val bb = r.bottom.toInt().coerceIn(t + 1, bitmap.height)
            readImage(Bitmap.createBitmap(bitmap, l, t, rr - l, bb - t))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(action("Cancelar") { closeSelector() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val p = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, p)
        selector = root
    }

    private fun closeSelector() {
        selector?.let { runCatching { wm.removeView(it) } }
        selector = null
    }

    private class SelectionFrame(context: android.content.Context, w: Float, h: Float) : View(context) {
        private val rect = RectF(w * .10f, h * .28f, w * .90f, h * .62f)
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(165, 0, 0, 0) }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(135, 120, 255); style = Paint.Style.STROKE; strokeWidth = 5f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var px = 0f
        private var py = 0f
        private var resize = false
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.TRANSPARENT)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            c.drawRoundRect(rect, 12f, 12f, clear)
            c.drawRoundRect(rect, 12f, 12f, line)
            c.drawCircle(rect.right, rect.bottom, 16f, dot)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER }
            c.drawText("Arraste para mover • canto para ajustar", width / 2f, (rect.top - 22f).coerceAtLeast(44f), p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    px = e.x; py = e.y
                    resize = hypot((e.x - rect.right).toDouble(), (e.y - rect.bottom).toDouble()) < 72
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - px; val dy = e.y - py
                    if (resize) {
                        rect.right = (rect.right + dx).coerceIn(rect.left + 100f, width.toFloat())
                        rect.bottom = (rect.bottom + dy).coerceIn(rect.top + 90f, height.toFloat())
                    } else {
                        rect.offset(dx, dy)
                        if (rect.left < 0) rect.offset(-rect.left, 0f)
                        if (rect.top < 0) rect.offset(0f, -rect.top)
                        if (rect.right > width) rect.offset(width - rect.right, 0f)
                        if (rect.bottom > height) rect.offset(0f, height - rect.bottom)
                    }
                    px = e.x; py = e.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return true
        }
        fun rect() = RectF(rect)
    }

    private fun readImage(bitmap: Bitmap) {
        val code = prefs.getString("source_language", "en") ?: "en"
        if (!prefs.getBoolean("installed_$code", false)) {
            sayToast("Instale primeiro o pacote do idioma escolhido.")
            return
        }
        if (code == "th") {
            readThai(bitmap)
            return
        }
        val recognizer = when (code) {
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                if (result.text.isBlank()) sayToast("Não encontrei texto. Ajuste a moldura e tente novamente.")
                else translateText(result.text, code)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                sayToast("Erro no reconhecimento: " + error.localizedMessage)
            }
    }

    private fun readThai(bitmap: Bitmap) {
        Thread {
            try {
                if (!File(filesDir, "tessdata/tha.traineddata").exists()) {
                    sayToast("Instale o pacote de OCR tailandês na tela inicial.")
                    return@Thread
                }
                val api = TessBaseAPI()
                if (!api.init(filesDir.absolutePath, "tha")) {
                    api.end()
                    sayToast("Não foi possível iniciar o OCR tailandês.")
                    return@Thread
                }
                api.setImage(bitmap)
                val text = api.getUTF8Text() ?: ""
                api.end()
                if (text.isBlank()) sayToast("Não encontrei texto tailandês legível.")
                else translateText(text, "th")
            } catch (e: Exception) {
                sayToast("Erro no OCR tailandês: " + e.localizedMessage)
            }
        }.start()
    }

    private fun translateText(text: String, code: String) {
        val source = TranslateLanguage.fromLanguageTag(code)
        if (source == null) {
            sayToast("Idioma de origem não disponível.")
            return
        }
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
        )
        translator.translate(text)
            .addOnSuccessListener { translated ->
                translator.close()
                val clean = translated.replace(Regex("[.。．…—–‐‑‒−-]+"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (clean.isBlank()) sayToast("A tradução ficou vazia.")
                else showTranslation(clean)
            }
            .addOnFailureListener { e ->
                translator.close()
                sayToast("Falha na tradução local: " + e.localizedMessage)
            }
    }


    private fun showTranslation(translated: String) {
        removePanel()
        closeSelector()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.rgb(30, 27, 53))
        }
        content.addView(TextView(this).apply {
            text = "Tradução"
            textSize = 22f
            setTextColor(Color.rgb(195, 187, 255))
            setPadding(0, 0, 0, 12)
        })
        content.addView(ScrollView(this).apply {
            addView(TextView(this@ScreenReaderService).apply {
                text = translated
                textSize = 18f
                setTextColor(Color.WHITE)
                setTextIsSelectable(true)
                setPadding(0, 8, 0, 16)
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(action("Ouvir tradução") {
            val result = tts?.speak(translated, TextToSpeech.QUEUE_FLUSH, null, "455-voice")
            if (result != TextToSpeech.SUCCESS) sayToast("A voz do aparelho ainda está iniciando.")
        })
        content.addView(action("Fechar tradução") { removePanel() })
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.65f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        wm.addView(content, params)
        panel = content
    }

    private fun sayToast(text: String) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        closeSelector()
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
