package com.leitordetela.app

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

class CaptureActivity : Activity(), TextToSpeech.OnInitListener {
    companion object { const val EXTRA_FILE = "capture_file" }
    private lateinit var bitmap: Bitmap
    private lateinit var selection: SelectionView
    private lateinit var root: LinearLayout
    private var tts: TextToSpeech? = null
    private val prefs by lazy { getSharedPreferences("455", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15,16,29)
        window.navigationBarColor = Color.rgb(15,16,29)
        tts = TextToSpeech(this, this)
        val path = intent.getStringExtra(EXTRA_FILE)
        bitmap = BitmapFactory.decodeFile(path) ?: run { finish(); return }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(15,16,29)) }
        root.addView(TextView(this).apply { text="Selecione a região do texto"; textSize=18f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; setPadding(12,18,12,18) })
        selection = SelectionView()
        root.addView(selection, LinearLayout.LayoutParams(-1,0,1f))
        val actions = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(8,8,8,8) }
        actions.addView(button("Traduzir") { translateSelection() }, LinearLayout.LayoutParams(0,56,1f))
        actions.addView(button("Cancelar") { finish() }, LinearLayout.LayoutParams(0,56,1f))
        root.addView(actions)
        setContentView(root)
    }
    private fun button(label:String, action:()->Unit) = Button(this).apply {
        text=label; isAllCaps=false; setTextColor(Color.WHITE)
        background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(91,77,190)); cornerRadius=24f }
        setOnClickListener { action() }
    }
    private inner class SelectionView : View(this) {
        private val rect=RectF(.08f,.28f,.92f,.65f)
        private var x0=0f; private var y0=0f; private var resizing=false
        override fun onDraw(c:Canvas) {
            val scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            val left=(width-bitmap.width*scale)/2; val top=(height-bitmap.height*scale)/2
            c.drawColor(Color.BLACK)
            c.drawBitmap(bitmap,null,RectF(left,top,left+bitmap.width*scale,top+bitmap.height*scale),null)
            val r=RectF(left+rect.left*bitmap.width*scale,top+rect.top*bitmap.height*scale,left+rect.right*bitmap.width*scale,top+rect.bottom*bitmap.height*scale)
            c.drawColor(Color.TRANSPARENT)
            val shade=Paint().apply { color=0x99000000.toInt() }
            c.drawRect(0f,0f,width.toFloat(),height.toFloat(),shade)
            val clear=Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            c.drawRect(r,clear)
            c.drawRect(r,Paint(3).apply { color=0xff9b8cff.toInt(); style=Paint.Style.STROKE; strokeWidth=5f })
            c.drawCircle(r.right,r.bottom,18f,Paint().apply { color=Color.WHITE })
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { x0=e.x; y0=e.y; resizing=kotlin.math.abs(e.x-width*.92f)<75 && kotlin.math.abs(e.y-height*.65f)<75; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(e.x-x0)/width; val dy=(e.y-y0)/height
                    if(resizing) { rect.right=(rect.right+dx).coerceIn(rect.left+.08f,1f); rect.bottom=(rect.bottom+dy).coerceIn(rect.top+.08f,1f) }
                    else { rect.offset(dx,dy); if(rect.left<0)rect.offset(-rect.left,0f); if(rect.top<0)rect.offset(0f,-rect.top); if(rect.right>1)rect.offset(1-rect.right,0f); if(rect.bottom>1)rect.offset(0f,1-rect.bottom) }
                    x0=e.x; y0=e.y; invalidate(); return true
                }
            }; return true
        }
        fun crop():Bitmap {
            val l=(rect.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1); val t=(rect.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
            val r=(rect.right*bitmap.width).toInt().coerceIn(l+1,bitmap.width); val b=(rect.bottom*bitmap.height).toInt().coerceIn(t+1,bitmap.height)
            return Bitmap.createBitmap(bitmap,l,t,r-l,b-t)
        }
    }
    private fun translateSelection() {
        val crop=selection.crop(); val code=prefs.getString("source_language","en") ?: "en"
        if(!prefs.getBoolean("installed_$code",false)) { crop.recycle(); toast("Baixe primeiro o pacote do idioma."); return }
        if(code=="th") { thai(crop); return }
        val recognizer=when(code) {
            "zh"->TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja"->TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko"->TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "hi"->TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else->TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        recognizer.process(InputImage.fromBitmap(crop,0))
            .addOnSuccessListener { result -> recognizer.close(); crop.recycle(); if(result.text.isBlank())toast("Nenhum texto encontrado.") else translate(result.text,code) }
            .addOnFailureListener { e -> recognizer.close(); crop.recycle(); toast("Erro no OCR: ${e.localizedMessage}") }
    }
    private fun thai(b:Bitmap) {
        Thread {
            try {
                if(!File(filesDir,"tessdata/tha.traineddata").exists()) { b.recycle(); runOnUiThread { toast("Instale o OCR tailandês na tela inicial.") }; return@Thread }
                val api=TessBaseAPI()
                if(!api.init(filesDir.absolutePath,"tha")) { api.end(); b.recycle(); return@Thread }
                api.setImage(b); val value=api.getUTF8Text() ?: ""; api.end(); b.recycle()
                runOnUiThread { if(value.isBlank())toast("Nenhum texto encontrado.") else translate(value,"th") }
            } catch(e:Exception) { if(!b.isRecycled)b.recycle(); runOnUiThread { toast("Falha no OCR: ${e.localizedMessage}") } }
        }.start()
    }
    private fun translate(value:String,code:String) {
        val src=TranslateLanguage.fromLanguageTag(code) ?: return
        val client=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.PORTUGUESE).build())
        client.translate(value).addOnSuccessListener { result ->
            client.close()
            val clean=result.replace(Regex("[\\p{P}\\p{S}]+")," ").replace(Regex("\\s+")," ").trim()
            if(clean.isBlank()) { toast("A tradução ficou vazia."); return@addOnSuccessListener }
            showTranslation(clean)
        }.addOnFailureListener { e -> client.close(); toast("Falha na tradução: ${e.localizedMessage}") }
    }
    private fun showTranslation(value:String) {
        root.removeAllViews()
        root.addView(TextView(this).apply { text="Tradução"; textSize=24f; setTextColor(0xffc3bbff.toInt()); setPadding(20,24,20,12) })
        root.addView(ScrollView(this).apply { addView(TextView(this@CaptureActivity).apply { text=value; textSize=19f; setTextColor(Color.WHITE); setPadding(20,16,20,16); setTextIsSelectable(true) }) },LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Ouvir tradução") { tts?.speak(value,TextToSpeech.QUEUE_FLUSH,null,"455-tts") },LinearLayout.LayoutParams(-1,56))
        root.addView(button("Fechar tradução") { finish() },LinearLayout.LayoutParams(-1,56))
    }
    override fun onInit(status:Int) { if(status==TextToSpeech.SUCCESS) { tts?.language=Locale("pt","BR"); tts?.setSpeechRate(.94f) } }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onDestroy() { if(::bitmap.isInitialized && !bitmap.isRecycled)bitmap.recycle(); tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
