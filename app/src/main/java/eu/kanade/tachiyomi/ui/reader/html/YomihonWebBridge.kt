package eu.kanade.tachiyomi.ui.reader.html

import android.content.Context
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class YomihonWebBridge(
    private val context: Context,
    private val onTriggerScan: () -> Unit,
    private val onOpenSafFolder: () -> Unit,
    private val onOpenCbzFile: () -> Unit,
) {
    private val ocrPreferences: OcrPreferences by lazy { Injekt.get() }
    private var ttsEngine: TextToSpeech? = null

    init {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale("ru", "RU")
            }
        }
    }

    @JavascriptInterface
    fun scanCurrentPage() {
        onTriggerScan()
    }

    @JavascriptInterface
    fun setOcrModel(modelName: String) {
        runCatching {
            val model = OcrModel.valueOf(modelName)
            ocrPreferences.ocrModel().set(model)
            context.toast("Модель OCR изменена на $modelName")
        }
    }

    @JavascriptInterface
    fun setScanRegion(regionName: String) {
        runCatching {
            val region = ScanRegion.valueOf(regionName)
            ocrPreferences.scanRegion().set(region)
            context.toast("Область сканирования изменена на $regionName")
        }
    }

    @JavascriptInterface
    fun openSafFolderPicker() {
        onOpenSafFolder()
    }

    @JavascriptInterface
    fun openCbzFilePicker() {
        onOpenCbzFile()
    }

    @JavascriptInterface
    fun getSystemVoices(): String {
        val array = JSONArray()
        runCatching {
            val voices = ttsEngine?.voices.orEmpty()
            for (voice in voices) {
                val obj = JSONObject().apply {
                    put("name", voice.name)
                    put("label", "${voice.locale.displayName} (${voice.name})")
                }
                array.put(obj)
            }
        }
        if (array.length() == 0) {
            array.put(JSONObject().apply {
                put("name", "ru-ru-x-dfa-network")
                put("label", "Русский (Нейросетевой голос)")
            })
            array.put(JSONObject().apply {
                put("name", "ru-ru-x-sfg-local")
                put("label", "Русский (Системный локальный)")
            })
        }
        return array.toString()
    }

    @JavascriptInterface
    fun speakText(text: String, voiceName: String, rate: Float) {
        runCatching {
            ttsEngine?.let { tts ->
                tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
                val targetVoice = tts.voices?.find { it.name == voiceName }
                if (targetVoice != null) {
                    tts.voice = targetVoice
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "html_tts")
            }
        }
    }

    @JavascriptInterface
    fun stopSpeech() {
        runCatching {
            ttsEngine?.stop()
        }
    }
}
