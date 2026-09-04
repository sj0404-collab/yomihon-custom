package mihon.data.ocr

import mihon.domain.ocr.service.OcrPreferences

/**
 * Авто-пресет типа контента без ручного «применить» (запрос пользователя).
 *
 * Правило сознательно узкое и безопасное:
 *  - срабатывает ОДИН раз на главу, на первом скане;
 *  - только пока пользователь не выбрал пресет явно (contentType == balanced);
 *  - определяет только манхву/вебтун по геометрии страницы: вертикальная
 *    полоса (height/width >= 1.8). Пейджинг-манга и комиксы по геометрии
 *    неразличимы, поэтому их авто-пресет не трогает — там решает пользователь;
 *  - область сканирования не трогает вообще: её выделяет пользователь.
 *
 * Выключается настройкой «Авто-пресет» (по умолчанию включён).
 */
object ContentAutoPreset {

    private const val WEBTOON_RATIO = 1.8f

    private val appliedChapters = mutableSetOf<Long>()

    @Synchronized
    fun maybeApply(chapterId: Long, pageWidth: Int, pageHeight: Int, prefs: OcrPreferences) {
        if (prefs.autoPreset().get() != "on") return
        if (chapterId in appliedChapters) return
        appliedChapters.add(chapterId)
        // Явный выбор пользователя священен: авто-пресет его не перебивает.
        if (prefs.contentType().get() != "balanced") return
        if (pageWidth <= 0 || pageHeight <= 0) return
        val ratio = pageHeight.toFloat() / pageWidth.toFloat()
        if (ratio >= WEBTOON_RATIO) {
            prefs.contentType().set(OcrContentType.MANHWA.id)
        }
    }

    /** Для тестов и смены главы в памяти: сброс отметок применения. */
    @Synchronized
    fun resetForTests() = appliedChapters.clear()
}
