package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Постобработка OCR-текста: склейка переносов (онлайн-модели отдают текст
 * построчно), пословная правка омоглифов (чтобы TTS не читал латынь по
 * буквам внутри русских слов) и фильтр «словарной лесенки» — признака
 * дрейфа CTC-декодера.
 */
class OcrTextCleanerTest {

    @Test
    fun `line hyphen is joined into one word`() {
        OcrTextCleaner.joinLineHyphens("пере-\nносится") shouldBe "переносится"
    }

    @Test
    fun `hyphen with spaces around the break is joined`() {
        OcrTextCleaner.joinLineHyphens("чело- \n век") shouldBe "человек"
    }

    @Test
    fun `local caption normalization joins device-reported false line hyphens before whitespace collapse`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption("МНЕ ХО-\nРОШО ЗНАКОМО ЭТО ИМЯ.") shouldBe
            "МНЕ ХОРОШО ЗНАКОМО ЭТО ИМЯ."
        OcrTextCleaner.normalizeLocalCyrillicCaption("НЕУПРАВ-\nЛЯЕМЫЙ... БЕС-\nПОЛЕЗНЫЙ") shouldBe
            "НЕУПРАВЛЯЕМЫЙ... БЕСПОЛЕЗНЫЙ"
    }

    @Test
    fun `inline hyphen stays`() {
        OcrTextCleaner.joinLineHyphens("из-за дома") shouldBe "из-за дома"
    }

    @Test
    fun `mixed word gets lookalikes fixed`() {
        OcrTextCleaner.fixLookalikesPerWord("cлишком") shouldBe "слишком"
    }

    @Test
    fun `pure latin word is kept so tts reads it as a word`() {
        OcrTextCleaner.fixLookalikesPerWord("SOS Wi-Fi") shouldBe "SOS Wi-Fi"
    }

    @Test
    fun `non-whitelisted pure latin is preserved instead of fabricated into russian`() {
        OcrTextCleaner.fixLookalikesPerWord("PEILENIE") shouldBe "PEILENIE"
    }

    @Test
    fun `mixed word gets the extended confusion map`() {
        OcrTextCleaner.fixLookalikesPerWord("NОЖНО") shouldBe "НОЖНО"
        OcrTextCleaner.fixLookalikesPerWord("ВАН5АСКЕРВWАВ") shouldBe "ВАНБАСКЕРВВАВ"
    }

    @Test
    fun `dictionary ramp is detected as garbage`() {
        OcrTextCleaner.looksLikeDictionaryRamp("0123456789:?LABCDEFGHIJKLM") shouldBe true
    }

    @Test
    fun `normal russian text is not a ramp`() {
        OcrTextCleaner.looksLikeDictionaryRamp("Идиот! Бежит...") shouldBe false
    }

    @Test
    fun `mixed visual lookalikes become acceptable cyrillic`() {
        val repaired = OcrTextCleaner.fixLookalikesPerWord("УMНO,")
        repaired shouldBe "УМНО,"
        OcrTextCleaner.isAcceptableCyrillicOcrText(repaired) shouldBe true
    }

    @Test
    fun `punctuation and latin-shaped garbage are rejected for russian local ocr`() {
        OcrTextCleaner.isAcceptableCyrillicOcrText("?!") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("Tele'axect.E") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("SOS") shouldBe true
    }

    @Test
    fun `restores caption spaces and ё without replacing unknown words`() {
        OcrTextCleaner.restoreKnownCaptionWords("ОНБЫЛ ЛОЖНООБВИНЕН В СГОВОРЕ СДЕМОНОМ") shouldBe
            "ОН БЫЛ ЛОЖНО ОБВИНЁН В СГОВОРЕ С ДЕМОНОМ"
        OcrTextCleaner.restoreKnownCaptionWords("«ОХОТНИЧИЙПЕС»ДОМАБАСКЕРВИЛЕЙ.") shouldBe
            "«ОХОТНИЧИЙ ПЁС» ДОМА БАСКЕРВИЛЕЙ."
        OcrTextCleaner.restoreKnownCaptionWords("ВИКИРВАНБАСКЕРВИЛЬ.") shouldBe
            "ВИКИР ВАН БАСКЕРВИЛЬ."
    }

    @Test
    fun `restores all known words in device-reported no-result white caption`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption(
            "ПОСЛОВАМ «ОХОТНИЧЬЕГОПСА», КОТОРЫЙПОСВЯТИЛ СЕБЯОТЦУИСЕМЬЕ,",
        ) shouldBe "ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА», КОТОРЫЙ ПОСВЯТИЛ СЕБЯ ОТЦУ И СЕМЬЕ,"
    }

    @Test
    fun `unknown cyrillic run is never split or rewritten`() {
        OcrTextCleaner.restoreKnownCaptionWords("НЕИЗВЕСТНОЕСЛОВО") shouldBe "НЕИЗВЕСТНОЕСЛОВО"
    }

    @Test
    fun `short valid russian utterances are accepted`() {
        OcrTextCleaner.isAcceptableCyrillicOcrText("а") shouldBe true
        OcrTextCleaner.isAcceptableCyrillicOcrText("а-а-а") shouldBe true
        OcrTextCleaner.isAcceptableCyrillicOcrText("а!") shouldBe true
        OcrTextCleaner.isAcceptableCyrillicOcrText("а...") shouldBe true
    }

    @Test
    fun `mixed latin lookalike garbage is never accepted as russian`() {
        OcrTextCleaner.isAcceptableCyrillicOcrText("разiiiнение") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("мама-naма") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("сахар-samaар") shouldBe false
    }

    @Test
    fun `uncertain cyrillic text is preserved rather than rewritten`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption("сахар-самаар") shouldBe "сахар-самаар"
        OcrTextCleaner.normalizeLocalCyrillicCaption("цвет-свек") shouldBe "цвет-свек"
    }
}
