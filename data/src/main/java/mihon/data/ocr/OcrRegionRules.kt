package mihon.data.ocr

import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion

/**
 * Правила перевода настроек области и точной подстройки в профиль пресета.
 *
 * Вынесены из `OcrRepositoryImpl`, чтобы у правила был ровно один хозяин:
 * движок, экран настроек и инструменты AI-агента обязаны видеть область и
 * переопределения одинаково. Любая копия этой логики рано или поздно
 * разошлась бы с движком, и агент начал бы докладывать пользователю не то,
 * что реально применяется к странице.
 *
 * Все функции чистые (кроме `profileOf`, читающей настройки), поэтому правила
 * покрыты юнит-тестами без Android.
 */
object OcrRegionRules {

    /** Ключи области, которые понимает `pref_ocr_preset_region`. */
    val REGION_KEYS = listOf("full", "top", "bottom")

    /** Ключ ключа области → значение настройки. Незнакомый ключ = null. */
    fun regionOf(key: String?): ScanRegion? = when (key) {
        "full" -> ScanRegion.FULL_PAGE
        "top" -> ScanRegion.TOP_HALF
        "bottom" -> ScanRegion.BOTTOM_HALF
        else -> null
    }

    /**
     * Фактическая область сканирования.
     *
     * `pref_ocr_preset_region` задаётся пресетом типа контента, а legacy
     * `pref_scan_region` остаётся быстрым переопределением для значений,
     * которые пресет не покрывает (пустая строка, старая установка).
     */
    fun effectiveRegion(presetKey: String?, legacy: ScanRegion): ScanRegion =
        regionOf(presetKey) ?: legacy

    /**
     * Переопределения пресета из строк настройки.
     *
     * Пустое или нечисловое поле означает «как в пресете»: значение,
     * сохранённое старой версией или введённое с опечаткой, не должно ломать
     * распознавание.
     */
    fun overridesOf(
        detectorThreshold: String,
        minComponentArea: String,
        maxTextBoxes: String,
        wordGapFactor: String,
        minAcceptConfidence: String,
        shortTextMinConfidence: String,
        minCoverage: String,
        rescueMaxLines: String,
    ): OcrTuningOverrides = OcrTuningOverrides(
        detectorThreshold = detectorThreshold.toFloatOrNull(),
        minComponentArea = minComponentArea.toIntOrNull(),
        maxTextBoxes = maxTextBoxes.toIntOrNull(),
        wordGapFactor = wordGapFactor.toFloatOrNull(),
        minAcceptConfidence = minAcceptConfidence.toFloatOrNull(),
        shortTextMinConfidence = shortTextMinConfidence.toFloatOrNull(),
        minCoverage = minCoverage.toFloatOrNull(),
        rescueMaxLines = rescueMaxLines.toIntOrNull(),
    )

    /** Профиль распознавания, который движок применит к следующей странице. */
    fun profileOf(prefs: OcrPreferences): OcrRegionProfile = OcrRegionProfile(
        contentType = OcrContentType.fromId(prefs.contentType().get()),
        scanRegion = effectiveRegion(prefs.presetScanRegion().get(), prefs.scanRegion().get()),
        overrides = overridesOf(
            detectorThreshold = prefs.detectorThresholdOverride().get(),
            minComponentArea = prefs.minComponentAreaOverride().get(),
            maxTextBoxes = prefs.maxTextBoxesOverride().get(),
            wordGapFactor = prefs.wordGapFactorOverride().get(),
            minAcceptConfidence = prefs.minAcceptConfidenceOverride().get(),
            shortTextMinConfidence = prefs.shortTextConfidenceOverride().get(),
            minCoverage = prefs.minCoverageOverride().get(),
            rescueMaxLines = prefs.rescueMaxLinesOverride().get(),
        ),
    )

    /** Человекочитаемое имя области — для отчётов и подсказок. */
    fun regionTitle(region: ScanRegion): String = when (region) {
        ScanRegion.FULL_PAGE -> "вся страница"
        ScanRegion.TOP_HALF -> "верхняя половина"
        ScanRegion.BOTTOM_HALF -> "нижняя половина"
    }

    /** Человекочитаемое имя порядка чтения. */
    fun orderTitle(order: String): String = when (order) {
        "rtl" -> "справа налево"
        "ltr" -> "слева направо"
        "vertical" -> "сверху вниз"
        else -> order
    }
}
