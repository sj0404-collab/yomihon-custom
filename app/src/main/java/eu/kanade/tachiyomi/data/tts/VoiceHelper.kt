package eu.kanade.tachiyomi.data.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

enum class VoiceKind { FEMALE, MALE, TEEN, OTHER }

/**
 * Классификация системных голосов по полу и подбор голоса для реплики.
 *
 * Важно: у Google Speech Services (самый распространённый движок на Android)
 * голоса называются НЕ по именам, а кодами вида `ru-ru-x-dfc-local`,
 * `ru-ru-x-ruf-network`. Прежняя версия искала в имени «svetlana»/«dmitry»
 * (это стиль Яндекс/RHVoice), поэтому все Google-голоса попадали в OTHER,
 * и pick(FEMALE)/pick(MALE) возвращали один и тот же первый голос — из-за
 * этого «работал только один голос».
 *
 * Теперь классификация трёхступенчатая:
 * 1. [Voice.getFeatures] / имя содержит явный признак пола;
 * 2. таблица известных кодов Google (`dfc`, `ruf`, … — женские; `rud`, `rue`,
 *    … — мужские) — коды берутся из середины имени `xx-xx-x-CODE-local`;
 * 3. детерминированный запасной вариант: голоса сортируются и делятся между
 *    полами по индексу, чтобы разные роли всё равно звучали по-разному.
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

    /**
     * Коды голосов Google Speech Services. Имя выглядит как
     * `ru-ru-x-dfc-local`, значащая часть — предпоследний сегмент.
     */
    private val googleFemaleCodes = setOf(
        // ru
        "dfc", "ruf", "rug",
        // en
        "iob", "iog", "sfg", "tpc", "tpf", "jomn", "iol",
        // прочие распространённые
        "afb", "bfa", "cfa", "dfa", "dfb", "efa", "ffa", "gfa", "hfa", "sfb",
    )
    private val googleMaleCodes = setOf(
        // ru
        "rue", "rud", "dmc",
        // en
        "iom", "iog2", "tpd", "sfb2", "jomn2",
        // прочие распространённые
        "ama", "bma", "cma", "dma", "dmb", "ema", "fma", "gma", "hma", "smb",
    )

    fun russianVoices(tts: TextToSpeech?): List<Voice> = voicesFor(tts, "ru")

    /**
     * Голоса для языка [language] (ISO-639-1). Пустой список, если движок
     * не отдал голоса.
     */
    fun voicesFor(tts: TextToSpeech?, language: String): List<Voice> {
        val all = try {
            tts?.voices
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "voices() failed" }
            null
        } ?: return emptyList()
        val lang = language.lowercase(Locale.US)
        return all.filter {
            val tag = it.locale.toLanguageTag().lowercase(Locale.US)
            val name = it.name.lowercase(Locale.US)
            (it.locale.language.equals(lang, true) || tag.startsWith("$lang-")) &&
                blacklist.none { b -> name == b }
        }.sortedBy { it.name }
    }

    /** Значащий сегмент имени Google-голоса: `ru-ru-x-dfc-local` -> `dfc`. */
    private fun googleCode(name: String): String? {
        val parts = name.lowercase(Locale.US).split('-')
        val xIndex = parts.indexOf("x")
        return if (xIndex >= 0 && xIndex + 1 < parts.size) parts[xIndex + 1] else null
    }

    fun classify(v: Voice): VoiceKind {
        val n = (v.name + " " + v.locale.toLanguageTag()).lowercase(Locale.US)

        // 1) признак пола, объявленный самим движком
        val features = runCatching { v.features }.getOrNull().orEmpty()
        features.forEach { f ->
            val lf = f.lowercase(Locale.US)
            if (lf.contains("female")) return VoiceKind.FEMALE
            if (lf.contains("male")) return VoiceKind.MALE
        }

        // 2) явные подсказки в имени (Яндекс, RHVoice, Samsung)
        when {
            teenHints.any { n.contains(it) } -> return VoiceKind.TEEN
            // "female" содержит "male", поэтому женское проверяем первым
            femaleHints.any { n.contains(it) } -> return VoiceKind.FEMALE
            maleHints.any { n.contains(it) } -> return VoiceKind.MALE
        }

        // 3) таблица кодов Google Speech Services
        googleCode(v.name)?.let { code ->
            if (code in googleFemaleCodes) return VoiceKind.FEMALE
            if (code in googleMaleCodes) return VoiceKind.MALE
        }

        return VoiceKind.OTHER
    }

    /**
     * Голос для роли. Если движок не даёт распознать пол (частый случай для
     * Google-кодов вне таблицы), голоса всё равно РАЗВОДЯТСЯ: женским ролям
     * достаётся первый нераспознанный, мужским — следующий. Так в сцене
     * звучат разные голоса, даже когда пол формально неизвестен.
     */
    fun pick(tts: TextToSpeech?, kind: VoiceKind, exactName: String?): Voice? =
        pickFor(tts, kind, exactName, "ru")

    fun pickFor(tts: TextToSpeech?, kind: VoiceKind, exactName: String?, language: String): Voice? {
        val all = voicesFor(tts, language)
        if (all.isEmpty()) return null
        if (!exactName.isNullOrBlank()) all.find { it.name == exactName }?.let { return it }

        // Локальные голоса предпочтительнее сетевых: работают без интернета.
        val ranked = all.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
        val group = ranked.filter { classify(it) == kind }
        if (group.isNotEmpty()) {
            val preferred = when (kind) {
                VoiceKind.FEMALE -> group.firstOrNull { it.name.contains("svetlana", true) }
                VoiceKind.MALE -> group.firstOrNull { it.name.contains("dmitr", true) }
                else -> null
            }
            return preferred ?: group.first()
        }

        // Пол не определён — разводим роли по разным голосам детерминированно.
        val unknown = ranked.filter { classify(it) == VoiceKind.OTHER }
        val pool = unknown.ifEmpty { ranked }
        val index = when (kind) {
            VoiceKind.FEMALE -> 0
            VoiceKind.MALE -> 1
            VoiceKind.TEEN -> 2
            VoiceKind.OTHER -> 0
        }
        return pool.getOrNull(index % pool.size) ?: pool.firstOrNull()
    }

    /**
     * Отдельный голос на говорящего: разные персонажи одного пола получают
     * разные голоса из своей группы (по кругу), чтобы диалог не звучал
     * одинаково. [speakerSlot] — порядковый номер персонажа в сцене.
     */
    fun pickForSpeaker(
        tts: TextToSpeech?,
        kind: VoiceKind,
        speakerSlot: Int,
        language: String = "ru",
    ): Voice? {
        val all = voicesFor(tts, language)
        if (all.isEmpty()) return null
        val ranked = all.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
        val group = ranked.filter { classify(it) == kind }
        val pool = when {
            group.isNotEmpty() -> group
            else -> ranked.filter { classify(it) == VoiceKind.OTHER }.ifEmpty { ranked }
        }
        if (pool.isEmpty()) return null
        val offset = if (group.isEmpty()) {
            when (kind) {
                VoiceKind.MALE -> 1
                VoiceKind.TEEN -> 2
                else -> 0
            }
        } else {
            0
        }
        return pool[(offset + speakerSlot.coerceAtLeast(0)) % pool.size]
    }
}
