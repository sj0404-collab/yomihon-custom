# OCR release report — pending candidate

## Quality status

This candidate is **not device-validated yet**. Its purpose is to remove confirmed false word breaks and recover a known PP-OCRv5 whole-line result without applying broad dictionary spell correction.

## Positive qualities expected from this change

The local Cyrillic postprocessing now joins a hyphen followed by an actual line break before it converts line breaks into spaces. This targets typeset wraps such as `ХО-\nРОШО`, `НЕУПРАВ-\nЛЯЕМЫЙ`, and `БЕС-\nПОЛЕЗНЫЙ` while preserving ordinary inline hyphens such as `из-за`.

The recognizer also compares the visual word-split result with the original full-line crop. A full-line candidate is selected only when the existing conservative caption list can already recover every involved word boundary; unknown fused Cyrillic runs remain unchanged rather than being guessed.

## Negative qualities and known limitations

The candidate does not claim to correct an arbitrary missing letter, infer untranslated Korean sound effects, or perform free-form Russian spell correction. Decorative outlined text and words outside the proven caption vocabulary can still fail or remain incorrectly spaced. Device testing remains the acceptance criterion.

## Regression evidence expected in the remote workflow

The focused OCR test suite now covers false line-break hyphens and the visible white caption: `ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА», КОТОРЫЙ ПОСВЯТИЛ СЕБЯ ОТЦУ И СЕМЬЕ,`.
