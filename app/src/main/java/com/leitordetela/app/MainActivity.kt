package com.leitordetela.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*

class MainActivity : Activity() {
    data class Lang(val name: String, val code: String)
    private val langs = listOf(
        Lang("Inglês", "en"), Lang("Chinês", "zh"), Lang("Japonês", "ja"),
        Lang("Coreano", "ko"), Lang("Hindi", "hi"), Lang("Tailandês", "th"),
        Lang("Vietnamita", "vi")
    )
    private lateinit var picker: Spinner
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("455", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 28)
            setBackgroundColor(Color.rgb(19, 18, 35))
        }
        page.addView(label("455  |  Leitor de Tela", 26f, true))
        page.addView(label("Reconhece o texto, traduz para português e fala somente quando você mandar.", 15f, false))
        page.addView(label("1. Escolha o idioma de origem", 18f, true))
        picker = Spinner(this)
        picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.name })
        picker.setSelection(langs.indexOfFirst { it.code == (prefs.getString("source_language", "en") ?: "en") }.coerceAtLeast(0))
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("source_language", langs[position].code).apply()
            }
        }
        page.addView(picker)
        page.addView(button("Baixar / instalar idioma neste celular") { installSelected() })
        page.addView(label("2. Ative o botão flutuante", 18f, true))
        page.addView(button("Abrir configurações de acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        page.addView(label("3. Como usar", 18f, true))
        page.addView(label("Toque no botão 455 sobre o aplicativo que deseja ler. Marque a região da tela, arraste a moldura para mover e use o canto inferior direito para redimensionar. Toque em Traduzir. Na tradução, use Fechar para sair ou Ouvir para escutar.", 14f, false))
        status = label("A captura só acontece quando você toca em Selecionar área. O OCR e a tradução são processados no aparelho. A internet é usada para baixar modelos. A voz depende do mecanismo de voz do Android.", 14f, false)
        page.addView(status)
        page.addView(button("Ver idiomas instalados") {
            val done = langs.filter { prefs.getBoolean("installed_${it.code}", false) }.joinToString("\\n") { "✓ ${it.name}" }
            status.text = if (done.isBlank()) "Nenhum pacote instalado." else "Instalados:\\n$done"
        })
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun label(text: String, size: Float, heading: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(if (heading) Color.rgb(195, 187, 255) else Color.WHITE)
        gravity = if (size >= 24f) Gravity.CENTER else Gravity.START
        setPadding(0, 12, 0, 12)
    }

    private fun button(text: String, run: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { run() }
    }

    private fun installSelected() {
        val lang = langs[picker.selectedItemPosition]
        val source = TranslateLanguage.fromLanguageTag(lang.code)
        if (source == null) {
            status.text = "Idioma indisponível para tradução."
            return
        }
        status.text = "Baixando o pacote de ${lang.name}..."
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                prefs.edit().putBoolean("installed_${lang.code}", true).apply()
                translator.close()
                if (lang.code == "th") downloadThaiModel()
                else status.text = "Pacote de ${lang.name} instalado. A tradução funciona localmente."
            }
            .addOnFailureListener { e ->
                translator.close()
                status.text = "Falha no download: ${e.localizedMessage}"
            }
    }

    private fun downloadThaiModel() {
        status.text = "Baixando reconhecedor tailandês..."
        Thread {
            try {
                val folder = java.io.File(filesDir, "tessdata").apply { mkdirs() }
                val target = java.io.File(folder, "tha.traineddata")
                val connection = java.net.URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/tha.traineddata").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                connection.disconnect()
                prefs.edit().putBoolean("thai_ocr_installed", true).apply()
                runOnUiThread { status.text = "Pacote tailandês instalado no aparelho." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Tradução instalada, mas falhou o pacote de OCR tailandês: ${e.localizedMessage}" }
            }
        }.start()
    }
}
