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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*

class MainActivity : Activity() {
    data class Lang(val flag: String, val name: String, val code: String)
    private val langs = listOf(
        Lang("🇬🇧", "Inglês", "en"), Lang("🇨🇳", "Chinês", "zh"), Lang("🇯🇵", "Japonês", "ja"),
        Lang("🇰🇷", "Coreano", "ko"), Lang("🇮🇳", "Hindi", "hi"), Lang("🇹🇭", "Tailandês", "th"),
        Lang("🇻🇳", "Vietnamita", "vi")
    )
    private lateinit var picker: Spinner
    private lateinit var status: TextView
    private lateinit var installButton: Button
    private val projectionRequest = 455
    private val prefs by lazy { getSharedPreferences("455", MODE_PRIVATE) }
    private val bg = Color.rgb(15, 16, 29)
    private val card = Color.rgb(27, 29, 49)
    private val accent = Color.rgb(139, 124, 255)
    private val muted = Color.rgb(174, 177, 199)

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
        page.addView(TextView(this).apply {
            text = "455"
            textSize = 34f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(accent, 22)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
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
            background = rounded(Color.rgb(67, 48, 125), 14)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            if (Build.VERSION.SDK_INT >= 16) setPopupBackgroundDrawable(rounded(Color.rgb(47, 34, 88), 14))
            adapter = object : ArrayAdapter<Lang>(this@MainActivity, android.R.layout.simple_spinner_item, langs) {
                private fun row(position: Int, convertView: View?, dropdown: Boolean): View {
                    val label = (convertView as? TextView) ?: TextView(this@MainActivity)
                    val item = langs[position]
                    label.text = item.flag + "   " + item.name
                    label.textSize = 16f
                    label.setTextColor(Color.WHITE)
                    label.gravity = Gravity.CENTER_VERTICAL
                    label.setPadding(dp(14), dp(if (dropdown) 13 else 10), dp(14), dp(if (dropdown) 13 else 10))
                    label.setBackgroundColor(if (dropdown) Color.rgb(47, 34, 88) else Color.rgb(67, 48, 125))
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
        privacy.addView(text("O reconhecimento e a tradução são feitos no aparelho. Os pacotes de idioma precisam ser baixados antes do primeiro uso; esse download usa internet. O texto capturado não é enviado para um serviço de tradução. A leitura em voz alta usa o mecanismo de voz instalado no Android.", 13f, muted, false))
        page.addView(privacy)

        status = text("Pronto para configurar.", 13f, Color.WHITE, false)
        val statusCard = card()
        statusCard.addView(text("STATUS", 12f, accent, true))
        statusCard.addView(status)
        page.addView(statusCard)

        page.addView(text("455  •  Versão 1.2.4", 11f, muted, false, Gravity.CENTER).apply {
            setPadding(0, dp(18), 0, 0)
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
        updateInstallButton()
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
            background = rounded(Color.rgb(54, 49, 91), 12)
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
        val source = TranslateLanguage.fromLanguageTag(lang.code)
        if (source == null) {
            status.text = "Este idioma não está disponível para tradução."
            return
        }
        installButton.isEnabled = false
        status.text = "Baixando o pacote de ${lang.name}. Mantenha a internet ligada..."
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                if (lang.code == "th") downloadThaiModel()
                else {
                    prefs.edit().putBoolean("installed_${lang.code}", true).apply()
                    status.text = "Pacote de ${lang.name} instalado. A tradução será processada localmente."
                    installButton.isEnabled = true
                    updateInstallButton()
                }
            }
            .addOnFailureListener { e ->
                translator.close()
                status.text = "Não foi possível baixar o pacote: ${e.localizedMessage ?: "erro desconhecido"}"
                installButton.isEnabled = true
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
