# Local OCR repair

- [x] Trace the local OCR engine, language configuration, image preprocessing, and result post-processing.
- [x] Reproduce the supplied Russian comic-panel failures: merged words, Latin/Cyrillic substitutions, UI-text capture, and false gibberish.
- [ ] Add a safe Russian-oriented comic-text preprocessing and recognition path without weakening other configured languages.
- [ ] Add regression coverage for representative Russian text-panel crops and reject low-confidence garbage.
- [ ] Build, test, and manually validate OCR results on the supplied screenshots.
- [ ] Configure and trigger the Android APK build only through the repository's GitHub Actions runner; do not assemble APK locally.
- [ ] Commit and push the verified local OCR fix directly to `sj0404-collab/yomihon-custom` on `main`.
- [ ] Download the verified GitHub Actions release artifact and upload the APK to GoFile with the user's explicit authorization.

## Caption OCR correction after device validation

- [ ] Reproduce and fix dropped initials and vowel substitutions: `ОН БЫЛ ЛОЖНО ОБВИНЁН В СГОВОРЕ С ДЕМОНОМ` must not become `н был лжн вбинен в сговоре с демоном`.
- [ ] Reproduce and fix word-boundary and Cyrillic substitutions: `«ОХОТНИЧИЙ ПЁС» ДОМА БАСКЕРВИЛЕЙ.` must not become `ххтничий лес и дма фаскервилаей`.
- [ ] Preserve explicit spaces in `ВИКИР ВАН БАСКЕРВИЛЬ.` instead of fusing the name.
- [ ] Build the corrected APK exclusively through GitHub Actions and validate it against these supplied panels before a new external upload.

## Device validation after build 9d19650

- [ ] Reproduce and correct dropped or absent caption results, including the white caption `ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА», КОТОРЫЙ ПОСВЯТИЛ СЕБЯ ОТЦУ И СЕМЬЕ,` that currently returns no result.
- [ ] Correct erroneous hyphenation that splits intact words across OCR lines, for example `МНЕ ХО-РОШО`, `НЕУПРАВ-ЛЯЕМЫЙ`, `БЕС-ПОЛЕЗНЫЙ`, and `БЕР-ДИУМА`.
- [ ] Correct missed letters and word-boundary errors in clean captions, including `МНЕ ХОРОШО ЗНАКОМО ЭТО ИМЯ.`, `«ОХОТНИЧИЙ ПЁС» ДОМА БАСКЕРВИЛЕЙ.`, and `И СХОЖУ ТАМ С УМА.`
- [ ] Improve coverage for outlined Cyrillic dialogue and narration that currently returns no OCR result.
- [ ] Add targeted regression tests for the reported hyphen, missing-result, and word-boundary cases without fabricating text from non-Russian artwork.

## Release documentation

- [x] Create a versioned Markdown release report for every future GitHub Actions APK candidate, documenting source commit, build link, improvements, known limitations, test evidence, and unresolved device-validation examples.
- [x] Validate candidate `9a8e4f8` remotely in GitHub Actions run `33007959697`: focused Cyrillic OCR tests, complete unit tests, signed arm64 APK assembly, and quality-report artifact all succeeded.
- [x] Upload candidate `9a8e4f8` from GitHub Actions run `33007959697` to GoFile for device testing: `https://gofile.io/d/s7HstrXp`.
- [ ] Install and device-test candidate `9a8e4f8` against the reported false line-wrap hyphens and missing-result captions before treating the fix as accepted.

## Standalone local OCR overlay APK

- [ ] Inspect the requested Overlay Translator repository and confirm a compatible Android source baseline.
- [ ] Create a separate Android overlay APK instead of modifying the reader UI: the user explicitly launches it over another app.
- [ ] Request Android screen-capture and draw-over-other-apps permissions only after an explicit user action, and explain their purpose in-app.
- [ ] Let the user place and resize one capture frame over the actual page content; crop to that frame before local OCR so status bars, overlay controls, and content outside the frame are excluded.
- [ ] Run local Russian/Cyrillic OCR on the selected screen crop, render editable text in the overlay, and add optional Russian text-to-speech controls.
- [ ] Add a Markdown quality report for every overlay APK candidate and build the APK only through GitHub Actions.

## New quality gate: Russian text fidelity

- [ ] Enforce UTF-8 end-to-end and add a regression that rejects mojibake and non-Cyrillic lookalike output when the source text is Russian.
- [ ] Prevent hallucinated pseudo-words such as `разiiiнение`, `сахар-самаар`, and `мама-нама`; retain the raw OCR or return a clearly low-confidence/no-result state instead of inventing a correction.
- [ ] Preserve whole detected sentences and large speech bubbles instead of returning a partial word result or `Нет результатов` when usable text exists.
- [ ] Preserve short valid utterances such as `а`, `а-а-а`, `а!`, and `а...`; do not reject them solely because they are short.
- [ ] Keep hyphens only for real orthographic hyphens or visual line-wraps that can be safely joined; never introduce a hyphen between recognized Cyrillic words.
- [ ] Decide explicitly whether preprocessing plugins are safe; any plugin must be local, deterministic, UTF-8 aware, and disabled if it lowers OCR confidence.
- [ ] Add a release report with positive and negative device examples before uploading the next APK candidate.

## Final one-build gate

- [ ] Cancel the in-progress intermediate GitHub Actions run before any further APK build.
- [ ] Complete all requested OCR logic, safety filters, full-bubble rescue, short-utterance handling, hyphen handling, and plugin decision before triggering a release workflow.
- [ ] Finish the complete regression suite and inspect its results before the final build; no APK is to be built from an unverified commit.
- [ ] Trigger exactly one final GitHub Actions APK build after all tests and the release Markdown report are complete.
- [ ] Upload only that final APK to GoFile and clearly report its single final commit, run, SHA-256, positive results, and remaining limitations.

## Unified Yomihon APK: OCR and floating voice controls

- [ ] Port only the working local OCR changes from the overlay branch into `yomihon-custom` without copying its standalone APK shell or cloud paths.
- [x] Add Yomihon-native floating `Голос` and `Выбрать голос` controls outside the OCR result card, with the existing copy and close actions preserved.
- [x] Connect the voice picker to installed Russian system TTS voices and persist the selected voice locally through the existing `TtsSettingsDialog` and `OcrPreferences.voiceName()` path.
- [ ] Keep UTF-8/Cyrillic fidelity, full-bubble rescue, short utterances, safe line-wrap joining, and no pseudo-word hallucination as one shared quality gate.
- [ ] Run the complete regression suite before triggering exactly one signed release APK build in GitHub Actions. Local sandbox compilation is blocked because Android SDK is unavailable; GitHub runner remains the authoritative build/test environment.
- [ ] Upload only the verified Yomihon release APK and its Markdown quality report to GoFile.
