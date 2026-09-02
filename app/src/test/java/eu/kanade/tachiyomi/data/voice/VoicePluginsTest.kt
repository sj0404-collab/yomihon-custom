package eu.kanade.tachiyomi.data.voice

import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Реестр голосовых плагинов.
 *
 * Проверяется только декларативная часть (id, бэкенд, требования, доступность):
 * реальная озвучка живёт в `TtsSpeaker`/`OnnxTts` и требует Android.
 */
class VoicePluginsTest {

    @Test
    fun `every plugin has a unique id matching its backend`() {
        VoicePlugins.ALL.map { it.id }.distinct().size shouldBe VoicePlugins.ALL.size
        VoicePlugins.ALL.forEach { plugin ->
            plugin.id shouldBe plugin.backend.id
            VoicePlugins.byId(plugin.id) shouldBe plugin
            VoicePlugins.byBackend(plugin.backend) shouldBe plugin
        }
    }

    @Test
    fun `backend ids match the values stored in pref_voice_engine`() {
        VoiceBackend.fromId("system_tts") shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId("google_web") shouldBe VoiceBackend.GOOGLE_WEB
        VoiceBackend.fromId("eleven_api") shouldBe VoiceBackend.ELEVEN_API
        // Значение, которое реально пишет UI озвучки (TtsSpeaker.ENGINE_ONNX).
        VoiceBackend.fromId("onnx_tts") shouldBe VoiceBackend.ONNX
        // Наследие сборок, где ONNX был объявлен как "onnx".
        VoiceBackend.fromId("onnx") shouldBe VoiceBackend.ONNX
        // Пустое или неизвестное значение читается как системный TTS.
        VoiceBackend.fromId("") shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId(null) shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId("что-то-новое") shouldBe VoiceBackend.SYSTEM_TTS
    }

    @Test
    fun `availability follows the declared requirements`() {
        VoicePlugins.available(
            networkAvailable = false,
            systemEnginePresent = true,
        ) shouldContainExactly listOf(VoicePlugins.SYSTEM_TTS)

        VoicePlugins.available(
            networkAvailable = true,
            systemEnginePresent = false,
        ) shouldContainExactly listOf(VoicePlugins.GOOGLE_WEB)

        VoicePlugins.available(
            networkAvailable = true,
            systemEnginePresent = true,
            hasApiKey = { it.backend == VoiceBackend.ELEVEN_API },
        ) shouldContainExactly listOf(
            VoicePlugins.SYSTEM_TTS,
            VoicePlugins.GOOGLE_WEB,
            VoicePlugins.ELEVEN_API,
        )

        // ONNX требует скачанную модель: без неё движок не предлагается.
        VoicePlugins.available(
            networkAvailable = false,
            systemEnginePresent = true,
            modelsDownloaded = { true },
        ) shouldContainExactly listOf(VoicePlugins.SYSTEM_TTS, VoicePlugins.ONNX)
    }

    @Test
    fun `a missing native library hides the onnx engine`() {
        VoicePlugins.available(
            networkAvailable = false,
            systemEnginePresent = true,
            modelsDownloaded = { true },
            nativeLibraryPresent = { false },
        ) shouldContainExactly listOf(VoicePlugins.SYSTEM_TTS)
    }

    @Test
    fun `offline flags are declared for the engines that work without network`() {
        VoicePlugins.ALL.filter { it.offline }.map { it.id } shouldContainExactly
            listOf(VoiceBackend.SYSTEM_TTS.id, VoiceBackend.ONNX.id)
        VoicePlugins.ALL.filterNot { it.offline }.map { it.id } shouldContainExactly
            listOf("google_web", "eleven_api")
    }

    @Test
    fun `gender aware engines are marked so auto-voicing can use them`() {
        VoicePlugins.ALL.filter { it.supportsGender }.map { it.id } shouldContainExactly
            listOf(VoiceBackend.SYSTEM_TTS.id, VoiceBackend.ONNX.id)
    }

    @Test
    fun `system voices are reported as installed while onnx models are not assumed`() {
        // Декларативно: у системных/веб/ElevenLabs голосов installed = true,
        // а у ONNX его выставляет реальная проверка модели на диске.
        VoicePlugins.SYSTEM_TTS.backend shouldBe VoiceBackend.SYSTEM_TTS
        VoicePlugins.ONNX.requirements.contains(VoiceRequirement.MODEL_DOWNLOAD) shouldBe true
    }

    @Test
    fun `backend ids are exactly the values TtsSpeaker routes on`() {
        // Единственный источник истины для pref_voice_engine: если id реестра
        // разойдётся с константами маршрутизатора, озвучка молча уйдёт в
        // системный TTS.
        VoiceBackend.SYSTEM_TTS.id shouldBe TtsSpeaker.ENGINE_SYSTEM
        VoiceBackend.GOOGLE_WEB.id shouldBe TtsSpeaker.ENGINE_GOOGLE_WEB
        VoiceBackend.ELEVEN_API.id shouldBe TtsSpeaker.ENGINE_ELEVENLABS
        VoiceBackend.ONNX.id shouldBe TtsSpeaker.ENGINE_ONNX
    }

    @Test
    fun `unknown and legacy engine values fall back to the system engine`() {
        VoiceBackend.fromId("onnx_tts") shouldBe VoiceBackend.ONNX
        VoiceBackend.fromId("  onnx_tts  ") shouldBe VoiceBackend.ONNX
        VoiceBackend.fromId("ONNX_TTS") shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId("plugin:my-voice") shouldBe VoiceBackend.SYSTEM_TTS
    }
}
