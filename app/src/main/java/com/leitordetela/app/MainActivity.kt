package com.leitordetela.app

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.LanguagePair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    data class Lang(val flag: String, val name: String, val code: String)
    private val langs = listOf(
        Lang("🇺🇸", "Inglês americano", "en-US"), Lang("🇬🇧", "Inglês britânico", "en-GB"), Lang("🇨🇳", "Chinês", "zh"), Lang("🇯🇵", "Japonês", "ja"),
        Lang("🇰🇷", "Coreano", "ko"), Lang("🇮🇳", "Hindi", "hi"), Lang("🇹🇭", "Tailandês", "th"),
        Lang("🇻🇳", "Vietnamita", "vi")
    )
    private lateinit var picker: Spinner
    private lateinit var status: TextView
    private lateinit var installButton: Button
    private val projectionRequest = 455
    private val prefs by lazy { getSharedPreferences("455", MODE_PRIVATE) }
    // Japanese-inspired neon palette: midnight black, electric blue, sakura red and violet.
    private val bg = Color.rgb(9, 8, 18)
    private val card = Color.rgb(23, 19, 38)
    private val accent = Color.rgb(57, 189, 248)
    private val redAccent = Color.rgb(255, 77, 109)
    private val violet = Color.rgb(155, 92, 255)
    private val muted = Color.rgb(177, 174, 205)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(30))
            setBackgroundColor(bg)
        }
        page.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_455)
            contentDescription = "Logo japonês do tradutor 455"
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(violet, 22)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        })
        page.addView(text("LEITOR DE TELA", 12f, accent, true, Gravity.CENTER))
        page.addView(text("Leia. Traduza. Ouça.", 27f, Color.WHITE, true, Gravity.CENTER))
        page.addView(text("Tradução de textos da tela, com processamento no próprio aparelho.", 14f, muted, false, Gravity.CENTER).apply {
            setPadding(dp(8), dp(5), dp(8), dp(20))
        })

        val languageCard = card()
        languageCard.addView(text("IDIOMA DE ORIGEM", 12f, accent, true))
        languageCard.addView(text("Escolha o idioma que aparece na tela", 15f, Color.WHITE, true).apply {
            setPadding(0, dp(5), 0, dp(8))
        })
        picker = Spinner(this).apply {
            background = rounded(Color.rgb(55, 37, 91), 14)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            if (Build.VERSION.SDK_INT >= 16) setPopupBackgroundDrawable(rounded(Color.rgb(35, 24, 59), 14))
            adapter = object : ArrayAdapter<Lang>(this@MainActivity, android.R.layout.simple_spinner_item, langs) {
                private fun row(position: Int, convertView: View?, dropdown: Boolean): View {
                    val label = (convertView as? TextView) ?: TextView(this@MainActivity)
                    val item = langs[position]
                    label.text = item.flag + "   " + item.name
                    label.textSize = 16f
                    label.setTextColor(Color.WHITE)
                    label.gravity = Gravity.CENTER_VERTICAL
                    label.setPadding(dp(14), dp(if (dropdown) 13 else 10), dp(14), dp(if (dropdown) 13 else 10))
                    label.setBackgroundColor(if (dropdown) Color.rgb(35, 24, 59) else Color.rgb(55, 37, 91))
                    return label
                }
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) = row(position, convertView, false)
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup) = row(position, convertView, true)
            }
            setSelection(langs.indexOfFirst { it.code == (prefs.getString("source_language", "en") ?: "en") }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putString("source_language", langs[position].code).apply()
                    updateInstallButton()
                }
            }
        }
        languageCard.addView(picker)
        languageCard.addView(text("Inglês americano e britânico usam o mesmo modelo de tradução para português; a diferença é a variante selecionada.", 12f, muted, false).apply { setPadding(dp(2), dp(8), dp(2), 0) })
        installButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(accent, 16)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { installSelected() }
        }
        languageCard.addView(installButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        page.addView(languageCard)

        val accessCard = card()
        accessCard.addView(text("COMEÇAR A USAR", 12f, accent, true))
        accessCard.addView(text("Autorize a captura de tela", 18f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(5)) })
        accessCard.addView(text("Autorize a captura uma vez. Depois use o botão flutuante 455 sobre o aplicativo que deseja ler.", 14f, muted, false))
        accessCard.addView(primaryButton("Iniciar captura de tela") { requestCapture() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        page.addView(accessCard)

        val steps = card()
        steps.addView(text("PASSO A PASSO", 12f, accent, true))
        addStep(steps, "01", "Autorize a captura", "Confirme a permissão de captura e de sobreposição do botão 455.")
        addStep(steps, "02", "Capture a tela", "Toque no botão flutuante 455 e ajuste a região transparente.")
        addStep(steps, "03", "Selecione e traduza", "Toque em TRADUÇÃO uma vez. O 455 lê somente a área marcada e para ao terminar.")
        page.addView(steps)

        val privacy = card()
        privacy.addView(text("PRIVACIDADE E FUNCIONAMENTO", 12f, accent, true))
        privacy.addView(text("O reconhecimento e a tradução são feitos no aparelho, com os modelos instalados. A internet é usada para baixar esses modelos. O texto e a imagem capturados não são enviados pelo aplicativo a uma API de tradução. A leitura em voz alta usa o mecanismo de voz do Android; algumas vozes do sistema podem usar internet.", 13f, muted, false))
        page.addView(privacy)

        status = text("Pronto para configurar.", 13f, Color.WHITE, false)
        val statusCard = card()
        statusCard.addView(text("STATUS", 12f, accent, true))
        statusCard.addView(status)
        page.addView(statusCard)

        page.addView(text("455  •  Versão 1.2.5", 11f, muted, false, Gravity.CENTER).apply {
            setPadding(0, dp(18), 0, 0)
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
        updateInstallButton()
    }

    override fun onDestroy() {
        setupScope.cancel()
        super.onDestroy()
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            status.text = "Autorize o botão flutuante 455 na próxima tela e depois volte ao aplicativo."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 456)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 456) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) requestCapture()
            else status.text = "Permita as notificações para usar o botão Capturar agora."
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val service = Intent(this, ScreenReaderService::class.java)
                    .putExtra(ScreenReaderService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenReaderService.EXTRA_DATA, data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                status.text = "Captura autorizada. O botão flutuante 455 ficará disponível sobre outros aplicativos."
            } else status.text = "A captura não foi autorizada."
        }
    }

    private fun addStep(parent: LinearLayout, number: String, title: String, detail: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = number
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(redAccent, 12)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text(title, 15f, Color.WHITE, true))
            addView(text(detail, 12f, muted, false))
        }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = rounded(card, 20)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean, gravityValue: Int = Gravity.START) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = rounded(accent, 16)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateInstallButton() {
        if (!::installButton.isInitialized || !::picker.isInitialized) return
        val lang = langs[picker.selectedItemPosition]
        installButton.text = if (prefs.getBoolean("installed_${lang.code}", false)) {
            "Pacote instalado  ·  Baixar novamente"
        } else {
            "Baixar pacote de ${lang.name}"
        }
    }

    private fun installSelected() {
        val lang = langs[picker.selectedItemPosition]
        val sourceCode = when { lang.code == "zh" -> "zh-Hans"; lang.code.startsWith("en-") -> "en"; else -> lang.code }
        installButton.isEnabled = false
        status.text = "Preparando os modelos Bergamot de ${lang.name}. Mantenha a internet ligada..."
        setupScope.launch {
            var engine: Foxlet? = null
            try {
                val client = withContext(Dispatchers.IO) { Foxlet.create(applicationContext) }
                engine = client
                if (sourceCode != "en") {
                    status.text = "Baixando o modelo ${lang.name} para inglês..."
                    withContext(Dispatchers.IO) { client.models.prepare(LanguagePair(sourceCode, "en")) }
                }
                status.text = "Baixando o modelo inglês para português..."
                withContext(Dispatchers.IO) { client.models.prepare(LanguagePair("en", "pt")) }
                prefs.edit().putBoolean("installed_${lang.code}", true).apply()
                if (lang.code == "th") downloadThaiModel()
                else {
                    status.text = "Modelos Bergamot de ${lang.name} instalados. Tradução local pronta."
                    installButton.isEnabled = true
                    updateInstallButton()
                }
            } catch (e: Exception) {
                status.text = "Falha ao preparar os modelos: ${e.localizedMessage ?: "erro desconhecido"}"
                installButton.isEnabled = true
            } finally {
                try { withContext(NonCancellable + Dispatchers.IO) { engine?.shutdown() } } catch (_: Exception) { }
            }
        }
    }

    private fun downloadThaiModel() {
        status.text = "Baixando o reconhecedor de texto tailandês..."
        Thread {
            try {
                val folder = java.io.File(filesDir, "tessdata").apply { mkdirs() }
                val target = java.io.File(folder, "tha.traineddata")
                val connection = java.net.URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/tha.traineddata").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                connection.disconnect()
                prefs.edit().putBoolean("thai_ocr_installed", true)
                    .putBoolean("installed_th", true).apply()
                runOnUiThread {
                    status.text = "Pacote tailandês instalado no aparelho."
                    installButton.isEnabled = true
                    updateInstallButton()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pacote de tradução baixado, mas o OCR tailandês falhou: ${e.localizedMessage}"
                    installButton.isEnabled = true
                }
            }
        }.start()
    }
}
