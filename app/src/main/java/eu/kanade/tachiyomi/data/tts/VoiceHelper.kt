package eu.kanade.tachiyomi.data.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

enum class VoiceKind { FEMALE, MALE, TEEN, OTHER }

/**
 * Портировано из overlay-translator (VoiceHelper): классификация системных
 * голосов по полу/возрасту на основе имён (Svetlana, Dmitry, …) и автоподбор
 * лучшего голоса группы. Женские реплики — Svetlana и др., мужские — Dmitry
 * и др., подростковые — детские голоса, прочее — OTHER.
 */
object VoiceHelper {
    private val femaleHints = listOf(
        "female", "woman", "svetlana", "milena", "oksana", "irina", "jane", "ksenia",
        "alena", "yelena", "elena", "anna", "maria", "natalia", "natalya", "tatyana",
        "жен", "женск", "девуш",
    )
    private val maleHints = listOf(
        "male", "man", "dmitry", "dmitri", "ermil", "filipp", "zahar", "pavel",
        "alexander", "maxim", "andrey", "ivan", "sergey", "муж",
    )
    private val teenHints = listOf("child", "kid", "teen", "young", "дет", "подрост")
    private val blacklist = listOf("locale", "default", "test")

    fun russianVoices(tts: TextToSpeech?): List<Voice> {
        val all = try {
            tts?.voices
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "voices() failed" }
            null
        } ?: return emptyList()
        return all.filter {
            val t = (it.locale.language + " " + it.locale.toLanguageTag() + " " + it.name).lowercase(Locale.US)
            (t.contains("ru") || it.locale.language.equals("ru", true)) &&
                blacklist.none { b -> t.contains(b) }
        }.sortedBy { it.name }
    }

    fun classify(v: Voice): VoiceKind {
        val n = (v.name + " " + v.locale.toLanguageTag()).lowercase(Locale.US)
        return when {
            teenHints.any { n.contains(it) } -> VoiceKind.TEEN
            maleHints.any { n.contains(it) } -> VoiceKind.MALE
            femaleHints.any { n.contains(it) } -> VoiceKind.FEMALE
            else -> VoiceKind.OTHER
        }
    }

    /** Лучший голос группы: точное имя > Svetlana/Dmitry > первый в группе > любой русский. */
    fun pick(tts: TextToSpeech?, kind: VoiceKind, exactName: String?): Voice? {
        val ru = russianVoices(tts)
        if (ru.isEmpty()) return null
        if (!exactName.isNullOrBlank()) ru.find { it.name == exactName }?.let { return it }
        val group = ru.filter { classify(it) == kind }
        val preferred = when (kind) {
            VoiceKind.FEMALE -> group.firstOrNull { it.name.contains("svetlana", true) } ?: group.firstOrNull()
            VoiceKind.MALE -> group.firstOrNull { it.name.contains("dmitr", true) } ?: group.firstOrNull()
            else -> group.firstOrNull()
        }
        return preferred ?: ru.firstOrNull()
    }
}
