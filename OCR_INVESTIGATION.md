# Local OCR investigation

## Supplied visual regressions

The supplied screenshots establish four reproducible failure classes: words are fused without spaces, Cyrillic and Latin lookalikes are mixed, large unrelated UI text or page artwork is recognized, and some results are dictionary-index garbage such as ordered digits and letters.

The first two ordered tiles of `Screenshot_20260826-200005.png` show an ordinary comic speech balloon containing only `?!` immediately above the bottom-sheet OCR interface. This establishes a control case: a punctuation-only balloon must not yield invented lexical text, and the bottom sheet itself must never be part of the recognition source.

Tiles three and four preserve the same capture in reading order. They show a Korean sound-effect graphic, a comic speech bubble with the short text `А-А?`, and the OCR sheet overlapping the viewer. These regions are deliberately unsuitable for a Russian word recognizer and must be excluded by a minimum lexical-character threshold rather than converted into fabricated Russian words.

Tiles five and six continue the same scene and contain no additional Russian lexical text: the visible content is artwork, a Korean sound-effect, a partially hidden empty balloon, and the OCR UI. Together these tiles confirm that the local path needs a conservative acceptance policy: return no text for non-Cyrillic artwork/punctuation instead of passing low-confidence CTC fragments to transliteration.

The final tile contains a valid two-line Russian balloon: `ШУМНО,` followed by `В ТЕЛЕ ТЯЖЕСТЬ.`. It is a representative regression target for line ordering, Cyrillic preservation, and word-spacing. The adjacent overlap with tile six contains only the OCR sheet, so no comic text is lost at the tile boundary.

## Confirmed implementation facts

The local engine uses the pinned Cyrillic PP-OCRv3 recognizer as primary and PP-OCRv5 as a verifier. The downloaded models expose `[1, 40, 165]` and `[1, 40, 852]` outputs respectively; their dictionaries contain 163 and 850 entries. The v3 output is therefore one class larger than a direct blank-plus-dictionary mapping, and the decoder needs an explicit contract check rather than relying on a generic index assumption.

The model sources describe these as **text-line recognizers** and recommend a full OCR pipeline with detection and line-orientation handling. The current Android path needs to keep line segmentation strict, score both available recognizers fairly, reject malformed outputs, and avoid applying Russian transliteration to arbitrary Latin-only garbage.
