package mihon.domain.ocr.model

/**
 * Represents the available OCR and AI vision models.
 */
enum class OcrModel {
    /**
     * Legacy and slower model, supports GPU/CPU.
     */
    LEGACY,

    /**
     * Faster model designed for ARM CPU.
     */
    FAST,

    /**
     * Online Google Lens OCR model.
     */
    GLENS,

    /**
     * Self-hosted OwOCR model.
     */
    OWOCR,

    /**
     * OpenRouter online AI model.
     */
    OPENROUTER,

    /**
     * Google AI / Gemini Vision model.
     */
    GOOGLE,

    /**
     * Zen Free AI model (Free zero-config, works without API key).
     */
    ZEN_FREE,
}
