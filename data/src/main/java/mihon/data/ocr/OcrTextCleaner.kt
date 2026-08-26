package mihon.data.ocr

/**
 * Постобработка текста после CTC-декодирования и онлайн-движков.
 *
 * Три независимые операции:
 * 1. [fixLookalikesPerWord] — латинско-кириллические омоглифы правятся только
 *    внутри слов, где уже есть кириллица: «cлишком» → «слишком», но чистая
 *    латынь («SOS», «Wi-Fi») остаётся нетронутой и TTS не читает её по буквам;
 * 2. [joinLineHyphens] — переносы слов соединяются («пере-\nносится» →
 *    «переносится»): онлайн-модели отдают текст построчно и раньше разрыв
 *    оставался в карточке;
 * 3. [looksLikeDictionaryRamp] — фильтр мусора «словарной лесенкой»
 *    («0123456789», «ABCDEFGHIJKLM»): такие строки возникают, когда CTC-поток
 *    дрейфует по словарю, и показывать их пользователю нельзя.
 */
object OcrTextCleaner {

    private val HYPHEN_LINE_BREAK = Regex("([\\p{L}])-[ \\t]*\\n[ \\t]*([\\p{L}])")

    fun joinLineHyphens(text: String): String {
        if (!text.contains('-')) return text
        return HYPHEN_LINE_BREAK.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2]
        }
    }

    fun fixLookalikesPerWord(text: String): String {
        if (text.isEmpty()) return text
        return text.split(' ').joinToString(" ") { word ->
            if (word.any { it.code in CYRILLIC_RANGE }) {
                CyrillicTranslitFixer.fixLookalikes(word)
            } else {
                word
            }
        }
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
