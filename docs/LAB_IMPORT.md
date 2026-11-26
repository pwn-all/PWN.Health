# Lab result import

"Lab tests → Add a result" is four pages: the type of test, how the results come in, the entry and
a page that says they are saved. For blood and urine the second page offers manual entry, "Choose
a file" and "Take a photo". "Choose a file" opens a picker for several photos and PDFs at once;
"Take a photo" opens the phone's camera. Going back a step and taking another photo adds it to the
same form: each shot gets its own permanent file. A sheet handed over from the chat opens the flow
on its third page and is read the same way.

After recognition the ordinary editable form is filled in automatically: markers, units, laboratory
ranges and the presumed date. All the selected sources and photos are attached to the form
automatically. The user checks the form and only then taps "Save results" — until then there is no
entry in the log. Saving is unavailable while recognition is running.

Fields filled in earlier are not overwritten. Pages and files of the same date are merged, and a
page without a date joins the batch's only date; conflicting values of one marker are left empty,
and a value that agrees across copies keeps a laboratory range only if every copy prints the same
one. If several dates are recognised, the user is asked to choose the date to fill the form with.
The other groups are available through "Show recognised pages": a group of another date can only
fill the form once the current results are saved or cleared. The form shows the fields of one
sample at a time; figures read for the other sample are kept, and after the first panel is saved
the form turns to them.

A password-protected PDF asks for its password in the form. The platform renderer takes a password
from Android 15, and on Android 13 and 14 with the PDF module at SDK extension 13; on other phones
the file is reported as unreadable.

Selected documents are kept as URIs with read permission. Camera photos are kept in the private
directory `files/lab-captures/`, not in the cache: new photos and clearing the cache do not damage
saved attachments. A photo larger than 3508 pixels on its long side (an A4 page at 300 dpi) is
scaled down to that size before it is read, and the smaller file is the one kept. This directory
is included in a direct transfer to another phone; cloud backup stays disabled. The manual JSON
export also carries the photos from this directory that saved results refer to; restore puts them
back under the same names. Documents selected from other providers are carried as references, not
as contents.

## Processing

1. `LabDocumentReader` recognises a PDF by its signature and decodes photos through `ImageDecoder`,
   taking EXIF into account. A PDF is copied into a bounded temporary file, so that providers that
   return a stream instead of a seekable file work too.
2. `PdfRenderer` rasterises the pages one at a time with the long side at 2048 pixels; a photo is
   decoded with its long side at most 2048 pixels. The OCR detector works at 1600 pixels. PDF: at
   most 20 pages and 32 MiB; exceeding a limit aborts the whole import. The bitmap and the temporary
   PDF are released on an error or a cancellation too.
3. The app's shipped PaddleOCR ONNX models are used: the PP-OCRv6 small detector and two
   recognisers, PP-OCRv6 small, which reads Latin, Chinese and Japanese with one model, and the East
   Slavic PP-OCRv5. Both recognisers read each page. If the upright page gives no results and no
   date, it is turned 90°, 270° and 180° in turn until one gives results. One reading of a page is
   chosen (most results, then most markers named but unread, then a date); conflicting OCR passes
   are not merged. A page recognised as a veterinary report ends the search at once.
4. `LabSheet` rebuilds the rows and the adjacency of cells from their coordinates. The
   `LabSheetNames` dictionary maps names and abbreviations to the app's markers; it also knows
   unsupported neighbouring markers, so as not to give their numbers to supported ones. Urine
   sections, marker ratios, cards, wrapped lines and result columns with dates are handled
   separately.
5. Normalisation brings commas, thousands separators, full-width digits, and the notation of powers
   and units to a common form. The value and the reference range are converted together into the
   app's storage units. Supported conversions include mg/dL ↔ mmol/L, g/dL ↔ g/L, cell counts,
   vitamins and HbA1c IFCC → NGSP.
6. Permitted units and value bounds are checked; mismatching repeats of one marker are excluded. A
   result without a unit is accepted only when the range determines the unit unambiguously enough.
   Known markers that could not be parsed are shown as a count. A comparison such as `<5` and a
   negative figure such as `-5` are refused, never read as the number `5`.

An allergy panel's file goes through the same reader (`LabDocumentReader.readPanel`), but each page
is read as a table of allergen rows rather than as markers.

No network services, API keys, separate models or the INTERNET permission are used. HTML is not
supported. PDFs with a text layer are read through rasterisation as well: there is no separate
extraction of PDF text. The renderer's API is described in the
[Android documentation](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer).

## Limitations

- What is supported is the numeric markers the app can already store, not arbitrary
  investigations, diagnoses, conclusions or qualitative answers.
- This is not support for any language. The
  [PP-OCRv5 family has additional language models](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md),
  but having a translation of a name in the dictionary does not replace an OCR model for the script
  concerned. The dictionary holds Korean, Arabic and Hindi names, but neither shipped recogniser
  reads Hangul, Arabic or Devanagari script. The interface text is translated into all 16 of the
  app's languages.
- The date is a guess: ambiguous dates with slashes use the order of the phone's region; the date
  of birth and future dates are excluded. A date that was not recognised has to be checked in the
  form. On one page with several people or visits, reliable separation of patients cannot be
  counted on.
- Strong perspective, blur, cut-off units and small dot-matrix print can leave fields empty. There
  is no trained classifier of all document types and no calibrated probability of being correct.
  The veterinary signs are a heuristic.
- The source file stays with its provider; deleting the file or revoking the permission makes the
  attachment unavailable, as with the app's other attachments.

## Checks

`LabSheetTest` uses synthetic OCR cells without personal data: normalisation, unit and range
conversions, languages, dates, ambiguities, veterinary signs, urine sections, duplicates and the
transfer into the form.
`LabDocumentReaderTest` on Android creates a PDF and a PNG, then reads them with the real ONNX
models; it checks page splitting, the result, the date, the page limit and the deletion of the
temporary PDF, and that a locked PDF asks for its password until the right one opens it.
Separately, it checks that camera photos create distinct files in permanent storage and are
accessible through FileProvider.
`LabImportTest` checks the merging of several files, attachments without duplicates, the rejection
of conflicting values, the separation of different dates and when a page stops being turned.
`LabCaptureBackupTest` checks how camera photos travel in the JSON export and come back on restore.

```sh
./gradlew :app:testSideloadDebugUnitTest --tests 'com.pwnall.health.Lab*Test'
./gradlew :app:connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pwnall.health.LabDocumentReaderTest
./gradlew :app:detekt :app:ktlintCheck :app:lintPlayDebug
```

## Not built

- Extraction of a PDF's text layer; HTML documents.
- Recognisers for further scripts (Hangul, Arabic, Devanagari).
- A trained table detector or document-type classifier, and a calibrated probability that a filled
  value is correct.
