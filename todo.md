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
