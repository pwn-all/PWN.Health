# The export model

Written for: the engineer changing the export next.

## The pipeline

```
App journal / Health Connect imports / medicines / appointments / medical history
        │
        ▼
Raw Room rows  (JournalEntry, DaySteps, Medication, Appointment, MedicalHistoryRow)
        │
        ▼
Readers the screens already use  (vitalHistory, labHistory, sleepSummary,
        │                         cycleSummary, moodSummary, medAdherence, habitsOver)
        ▼
Canonical clinical vocabulary  (ClinicalModel.kt: UcumUnit, Provenance,
        │                       SectionPresence, Completeness)
        ▼
Normalisation + selection  (DoctorReport.kt: doctorReport(sources, request, …))
        │      ▲                ▲                    ▲
        │   CountryProfile  DoctorSpecialty    the person's ticks
        ▼
Clinical report model  (DoctorReport: sections, presence, provenance, notes)
        │
        ▼
       PDF  (DoctorReportPdf.kt), alone or in a ZIP with the originals (DoctorReportArchive.kt)
```

The PDF reads the `DoctorReport` and computes no figure of its own: it lays out what the model
holds, and its charts are placed by `ReportChartGeometry.kt`.

## Files

| File | What lives in it |
| --- | --- |
| `ClinicalModel.kt` | the vocabulary: UCUM units, provenance, section presence, completeness, sensitivity. No Android resources, no Compose, no country. |
| `CountryProfile.kt` | five profiles and a registry with an international fallback. |
| `DoctorSpecialty.kt` | nine doctors and "My own choice", each a default selection and nothing more. |
| `DoctorReport.kt` | the section builders and `doctorReport(sources, request, today, …)`. |
| `DoctorReportPatterns.kt` | the pain, work and morning check-in sections. |
| `ReportLabContext.kt`, `ReportMicronutrients.kt`, `ReportChanges.kt` | the three computed sections: around each lab test, vitamins and minerals, changes over the same months. |
| `ReportChartGeometry.kt` | where a chart's days, figures, ticks and labels fall on the page, without Android graphics. |
| `DoctorReportPdf.kt` | the A4 sheet, and the cache sweep at a fresh start. |
| `DoctorReportArchive.kt` | the "With files" ZIP: the PDF, the attachment index and the originals. |
| `DoctorReportScreen.kt` | the page, its pickers, the three consent dialogs, the preview. |

## The rules

### 1. No codes

The report leaves the phone as a PDF, alone or in a ZIP with the attached originals, and nothing
machine-readable goes with it. The sheet carries no LOINC, ICD or SNOMED code: a marker is named in
words, with its sample and its unit.

### 2. The original survives

A country profile changes presentation only: a figure is stored as recorded and converted only for
the sheet. A lab record stores its unit as UCUM 2.2 spells it, case-sensitively.

### 3. Three kinds of nothing

`SectionPresence` is `PRESENT`, `NO_KNOWN`, `NO_DATA`, `NOT_INCLUDED`. `PROBLEMS` and `ALLERGIES`
are printed on every sheet whatever they hold.

`NO_KNOWN` is emitted in exactly one place: the allergy section, when the person answered "none that
I know of". That is a claim about what the person says, which is why the row above it says so
(`report_allergy_reported`) and why the three other answers are three other presences — ticked
allergens are `PRESENT`, "never been tested" is `NO_DATA` with a caveat saying that is not a
statement that there are none, and an unanswered question is `NO_DATA` with nothing added. Where an
allergy panel is on file, its newest one is printed under the answer whatever its date, and the
section is `PRESENT`: the person's answer stays a row of its own beside the test. Nothing else in
the app may emit `NO_KNOWN`: it has no way to establish the absence of a condition nobody asked
about.

A section the person did not tick is left off the sheet, sensitive or not, and one line at the foot
(`report_note_chosen`) says the sheet carries only the sections the person chose. A sheet listing
what its owner withheld is a list of what they are hiding. The two core sections are the exception:
they print whatever the ticks say, and `PROBLEMS`, being a tick as well, is the one section that
prints `NOT_INCLUDED` — rather than its rows — when it is not ticked.

An empty section that stands outside the report's window — problems, allergies, procedures,
immunisations, pregnancy, contraception — says "nothing written down" (`section_nothing_written`,
`ReportSection.emptyLabelRes`) instead of "no data for this period". The second sentence is true of
a measurement and false of an appendix removed in 1998.

### 4. Nothing is diagnosed

The app applies published population bands to figures and prints a word for which side of the band a
value fell on. That is arithmetic, and the sheet says so: every banded section carries
`report_caveat_band`, naming the band as applied by the app and not as a laboratory's range for that
sample. No ICD or SNOMED code is ever attached to a measurement, and no section states that
ovulation, pregnancy, a deficiency or a disease occurred.

### 5. Summaries, not dumps

A section prints its shape — latest, range, count, change — and its completeness. Individual dated
rows go to the appendix, which only the weight section and the detailed sexual-health answer fill.

## Deduplication

The Health Connect import (`HealthSync.kt`) collapses only steps, through the platform's own
aggregate, and sleep and heart rate, which it chooses among a window's records. Every other record
another app wrote is stored as a row of its own (`place`), so a pressure taken once by a cuff can
stand in the journal twice: once from the vendor app, once from Health Connect.

The export collapses readings that agree **to the second** on every value
(`DoctorReport.kt`, `deduplicated`). Two readings a minute apart are two readings — measuring twice
is a real thing a person does — so nothing merges across time. The journal itself keeps both rows.

## Tests

`ClinicalExportTest` covers, with synthetic fixtures only: an empty record; the core sections on
every sheet; weight alone; a month of pressures summarised; metric and imperial by country, and the
international profile deferring to the person's units; the timezone line; a DST transition; one
measurement arriving from two apps; two genuine readings close together; an applied band
attributed; a marker the app declines to grade; a medicine with partial data; an incomplete cycle;
gynaecology with the sensitive section off and on; the detailed answer; that no preset can ever
tick a sensitive section, and that the symptoms and the tally travel apart; a seventeen-year-old;
the country registry and its fallback; UCUM spellings, and a UCUM unit on every marker; non-Latin
text with no patient name; free text on a journal entry never reaching the sheet; the problem list
printed, withheld, and empty; the newest contraception method; pregnancy asked only of a woman;
every offered section presentable in the picker.

## Not built

- Cross-source deduplication at import, in `place` (`HealthSync.kt`); the export compensates as
  above.
- Device metadata for what a machine measured in a session (distance, power, cadence): it goes to
  Health Connect marked as entered by hand, because the session's row does not keep which device
  measured it. A journal row's own types already say how they were taken (`publishedMetadata` in
  `HealthSync.kt`).
