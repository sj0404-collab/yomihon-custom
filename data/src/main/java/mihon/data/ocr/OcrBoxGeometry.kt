package mihon.data.ocr

import mihon.domain.ocr.model.OcrBoundingBox

/**
 * Перевод координат текстовых боксов из пикселей изображения в нормализованные
 * (0..1) координаты, которыми оперирует [OcrBoundingBox].
 *
 * Вынесено отдельно от движка: это чистая арифметика без Android-зависимостей,
 * поэтому её можно проверить обычным юнит-тестом.
 */
internal object OcrBoxGeometry {

    /**
     * @return нормализованный бокс или null, если он вырожденный либо
     * изображение имеет нулевой размер.
     */
    fun normalize(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): OcrBoundingBox? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (right <= left || bottom <= top) return null

        val l = (left.toFloat() / imageWidth).coerceIn(0f, 1f)
        val t = (top.toFloat() / imageHeight).coerceIn(0f, 1f)
        val r = (right.toFloat() / imageWidth).coerceIn(0f, 1f)
        val b = (bottom.toFloat() / imageHeight).coerceIn(0f, 1f)

        val box = OcrBoundingBox(l, t, r, b)
        return box.takeIf { it.isValid() }
    }

    /**
     * Бокс занимает практически весь лист.
     *
     * Такие регионы бесполезны как цель для тапа (см. OcrRegion.isWholePage):
     * если детектор вернул один бокс на всю страницу, лучше считать, что
     * разметки нет, и не плодить нетапабельные регионы.
     */
    fun coversWholePage(box: OcrBoundingBox): Boolean {
        return box.left <= 0.02f &&
            box.top <= 0.02f &&
            box.right >= 0.98f &&
            box.bottom >= 0.98f
    }
}
