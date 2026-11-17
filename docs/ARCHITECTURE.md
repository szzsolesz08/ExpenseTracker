# KiadásKövető – Architecture

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `core` | Kotlin/JVM | Exact money (`Money`, integer fillér), quantities and units, amount parsing (Hungarian formats), price math, Hungarian text normalization, fuzzy matching, hashing. No Android dependencies. |
| `receipt-parser` | Kotlin/JVM | The receipt interpretation engine: 10-stage pipeline, configurable store profiles (`parser_config.json`), product dictionary (`product_dictionary.json`), name interpreter, categorizer, duplicate detector, product matcher. Deterministic, no I/O. |
| `database` | Android library | Room schema (11 entities), DAOs, analytics queries, backup DAO, migration registry. Schemas exported to `database/schemas/`. |
| `ocr` | Android library | Image preprocessing (pure-Kotlin `GrayImage` algorithms + Android bitmap ops), receipt edge detection, deskew, PDF rendering (`PdfRenderer`), Tesseract 5 engine with bundled models, multi-photo page merging, ZXing barcode decoding. |
| `local-ai` | Android library | Optional local LLM integration surface: GGUF header inspection, manual model import, device compatibility, `LocalLanguageModel` interface. No inference runtime is bundled in this build. |
| `app` | Android application | Jetpack Compose UI (Material 3), navigation, view models, repositories, import pipeline, backup/export, notifications, WorkManager jobs, biometric lock. |

Dependency direction: `app → (receipt-parser, database, ocr, local-ai) → core`. The parser does not know about the
database; the database does not know about the parser. The app layer maps between them.

## Key decisions

- **Manual DI** (`AppContainer`). The object graph is small; Hilt/Koin would add build complexity without real value.
- **Tesseract instead of ML Kit.** ML Kit is closed source, reports usage metrics to Google and merges network
  permissions. Tesseract 5 (Apache-2.0) runs fully offline with bundled Hungarian models:
  `hun` (tessdata_fast, default, "Gyors") and `hun_best` (tessdata_best, "Pontos"), plus `eng` (fast).
- **Exact money.** All amounts are `Long` fillér; SQL `SUM` is exact; `BigDecimal` with explicit rounding is used only
  for unit-price and percentage calculations. Floating point never touches money.
- **Wall-clock time encoding.** Purchase times are the printed local time stored as epoch millis *as if UTC*, so SQL
  `strftime(..., 'unixepoch')` groups by the printed date regardless of device time zone or DST.
- **Never invent data.** Missing date → the receipt is saved as a draft with `dateKnown = false` and cannot be confirmed
  until the user sets a date. Missing total → the line sum is used and the review screen says so. Unplaceable OCR lines
  are stored in `unparsedText` and shown for review. Unit prices are only stored when printed.
- **Originals are preserved first.** The import pipeline moves originals into private storage before any processing;
  every failure path (OCR error, cancellation, out of memory) still saves a `FAILED` receipt pointing at the originals.
- **Product identity is conservative.** A receipt line links automatically to a product only through an alias the user
  confirmed (by saving a receipt) for the same store or explicitly for all stores. Similar names are suggestions only.
  Products can be merged and aliases split.
- **Learning.** Changing an item's category writes a `category_mappings` row (store-scoped). The categorizer consults,
  in order: confirmed mapping → user rules → near-identical confirmed descriptions (OCR variants) → dictionary
  (keywords, abbreviations, brands, compound/fuzzy matching) → "Nem besorolt".
- **Charts are custom Canvas code** (no chart library), following a validated single-hue palette; category identity is
  carried by icon + name, never by colour alone (17 categories cannot be colour-coded accessibly).

## Receipt parsing pipeline (`ReceiptParser`)

1. **Raw text** – OCR lines with confidence.
2. **Structure** – `LinePreparer` cleans OCR junk (leading punctuation, `%` read as `X`, VAT codes read as `c0o`/`Ceo`),
   extracts trailing amounts, VAT columns and quantity lines; `LineClassifier` labels header / items / totals / payment /
   VAT / footer with a phase state machine.
3. **Store** – `StoreDetector` matches profile identifiers (OCR-tolerant: `5PAR`, `L1DL`), extracts company, address,
   tax number.
4. **Date** – `DateExtractor` (y-m-d, d-m-y, month names, OCR digit repair, plausibility check).
5. **Product lines**, 6. **Quantities & prices**, 7. **Discounts** – `ItemAssembler` attaches quantity lines before/after
   items by arithmetic agreement, handles name-only and price-only lines, item-level vs receipt-level discounts,
   deposits (DRS `Visszaváltási díj`) and deposit returns.
8. **Total check** – printed total, payable total, cash rounding; mismatches are reported with the difference.
9. **Categories** – `ProductCategorizer` (see Learning above).
10. **Confidence** – per item (OCR, structure, category, name certainty) and per receipt; items below thresholds or
    with blocking issues are flagged `needsReview`.

Store profiles (SPAR, INTERSPAR, Lidl, ALDI, Tesco, PENNY, Auchan, CBA, COOP, dm, Rossmann, Müller, METRO) and all
keyword lists live in `receipt-parser/src/main/resources/hu/kiadaskoveto/parser/parser_config.json`.

## Import pipeline (`ReceiptImportPipeline`)

The 8 user-visible steps: Dokumentum importálva → Kép előfeldolgozása → Szövegfelismerés → Szöveg értelmezése →
Termékek kinyerése → Kategóriák hozzárendelése → Ellenőrzés és jóváhagyás → Mentés. Preprocessing: EXIF orientation,
user rotation, automatic or manual 4-corner perspective crop, grayscale, contrast stretch, 3×3 blur, Bradley adaptive
threshold, despeckle, 90° orientation detection, ±8° deskew, size normalization, blur/lighting/contrast warnings,
perceptual hash for duplicate detection. OCR is retried rotated 180° when confidence is very low.

## Database

Schema version 1 (`database/schemas/hu.kiadaskoveto.database.KiadasDatabase/1.json`). No destructive migration fallback
is configured: a missing migration fails loudly instead of deleting user data. Add migrations to `Migrations.ALL` with a
`MigrationTestHelper` test. Consistency check (Beállítások → Adatbázis ellenőrzése): `PRAGMA integrity_check`,
`foreign_key_check`, stale line sums (auto-repaired), missing and orphaned files.

## Optional local LLM

`local-ai` contains the full integration surface (model import, GGUF validation, RAM/ABI compatibility, a
`LocalLanguageModel` interface wired into the import pipeline for low-confidence categories only — never for amounts).
A llama.cpp JNI runtime is **not** bundled: building it requires the Android NDK and native compilation, and the brief
asks not to trade reliability for experimental AI. To add it, implement `LocalLanguageModel` over llama.cpp and set
`LocalModelManager.runtimeBundled = true`.
