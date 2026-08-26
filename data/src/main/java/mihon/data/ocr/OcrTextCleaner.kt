package mihon.data.ocr

/**
 * Постобработка текста после CTC-декодирования и онлайн-движков.
 *
 * 1. [fixLookalikesPerWord] — пословная правка: в словах С кириллицей
 *    латинские омоглифы и похожие цифры заменяются на кириллицу (N→Н, I→И,
 *    5→Б …), чтобы текст читался и TTS не диктовал буквы; слова из чистой
 *    латыни транслитерируются в кириллицу (PEILENIE → ПЕИЛЕНИЕ), если это не
 *    известный латинский токен из белого списка (SOS, BMW, Wi-Fi …);
 * 2. [joinLineHyphens] — переносы слов соединяются («пере-\nносится» →
 *    «переносится»);
 * 3. [looksLikeDictionaryRamp] — фильтр мусора «словарной лесенкой».
 */
object OcrTextCleaner {

    private val HYPHEN_LINE_BREAK = Regex("([\\p{L}])-[ \\t]*\\n[ \\t]*([\\p{L}])")

    private val LATIN_WHITELIST = setOf(
        "sos", "bmw", "wi-fi", "ok", "tv", "dvd", "3d", "hp", "pc", "usb", "sim", "sd",
    )

    /**
     * Расширенная таблица омоглифов и визуальных замен для слов, в которых
     * уже есть кириллица. Собрана по реальным промахам модели на манге:
     * N→Н, I→И, L→Л, D→Д, G→Г, W/V→В, Z→З, J→Й, S→С, 5→Б, 0→О, 4→Ч…
     */
    private val EXT_LOOKALIKE_MAP = mapOf(
        'A' to 'А', 'a' to 'а',
        'B' to 'В',
        'C' to 'С', 'c' to 'с',
        'E' to 'Е', 'e' to 'е',
        'H' to 'Н',
        'K' to 'К', 'k' to 'к',
        'M' to 'М', 'm' to 'м',
        'O' to 'О', 'o' to 'о',
        'P' to 'Р', 'p' to 'р',
        'T' to 'Т', 't' to 'т',
        'X' to 'Х', 'x' to 'х',
        'y' to 'у',
        '3' to 'З', '6' to 'б',
        'I' to 'И', 'L' to 'Л', 'N' to 'Н', 'D' to 'Д', 'G' to 'Г',
        'W' to 'В', 'V' to 'В', 'Z' to 'З', 'J' to 'Й', 'S' to 'С',
        's' to 'с',
        '5' to 'Б', '0' to 'О', '4' to 'Ч',
    )

    fun joinLineHyphens(text: String): String {
        if (!text.contains('-')) return text
        return HYPHEN_LINE_BREAK.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2]
        }
    }

    fun fixLookalikesPerWord(text: String): String {
        if (text.isEmpty()) return text
        return text.split(' ').joinToString(" ") { word ->
            val hasCyrillic = word.any { it.code in CYRILLIC_RANGE }
            val hasLatin = word.any { it.isLetter() && it.code < 0x80 }
            when {
                hasCyrillic -> mapChars(word, EXT_LOOKALIKE_MAP)
                hasLatin -> {
                    val bare = word.trimEnd('.', '!', ',', '?', '…').lowercase()
                    if (bare in LATIN_WHITELIST) word else CyrillicTranslitFixer.translitToCyrillic(word)
                }
                else -> word
            }
        }
    }

    private fun mapChars(word: String, map: Map<Char, Char>): String {
        val sb = StringBuilder(word.length)
        for (char in word) sb.append(map[char] ?: char)
        return sb.toString()
    }

    /**
     * Правда, если в тексте есть «лесенка» из 6+ символов подряд по порядку
     * кодов (цифры/латиница/кириллица) — верный признак того, что декодер
     * дрейфовал по словарю, а не читал надпись.
     */
    fun looksLikeDictionaryRamp(text: String): Boolean {
        var run = 1
        var previous = -2
        for (char in text) {
            val code = char.code
            run = if (code == previous + 1) run + 1 else 1
            if (run >= 6) return true
            previous = code
        }
        return false
    }

    private val CYRILLIC_RANGE = 0x0400..0x052F
}
