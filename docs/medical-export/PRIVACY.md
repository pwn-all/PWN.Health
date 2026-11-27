# Privacy of the doctor export

Written for: the reviewer asking what can leak, and the clinician asked to look at this.

## What the export can carry

Everything ticked in the picker, plus two blocks that are not ticks: the person (the period, the
doctor and the country profile it was made for, the time zone, age, sex and height, and the weight
and BMI only when the weight is ticked) and the allergies — the profile's answer and, where one is
on file, the newest allergy panel. Everything else is built from `ReportRequest.parts`; there is no
other door into it. A section that is not ticked is left off the sheet. Until a doctor is chosen the
page opens with the routine sections ticked and no others: the reproductive ones (pregnancy status,
contraception, the cycle) are ticked by the doctors they were written for or by the person, and "My
own choice" ticks the same routine set. The preview lists what is ticked before anything leaves.

With "With files" switched on the export is a ZIP: the same PDF, the original files attached to the
attended visits, lab records and medicine courses in the ticked sections, each copied whole, and an
index (`attachments.tsv`) linking each file to its record.

### The written-down history

Diagnoses, operations, vaccinations, pregnancy status and contraception are `ROUTINE` for the first
three and `REPRODUCTIVE` for the last two — not `SENSITIVE`, because the person who needs to know a
pregnancy status is the one about to prescribe something, and a section nobody could send would be
a section nobody could use. What protects them is the ordinary machinery: a preset ticks only what
that specialty asks for, and none of them is on a sheet its owner did not tick.

The problem list is the one core section that is also a tick. It is printed on every sheet, with its
contents when ticked and "not included by the patient" when not — so a diagnosis can be kept off a
particular desk without the heading disappearing and reading as "no problems".

## The sensitive class

`DataSensitivity.SENSITIVE` is not a stronger word for "personal". It is the class of data whose
disclosure can put somebody at risk, and in this app it is three sections: sexual activity, the
symptoms noted while logging it, and — on a man's profile only — the morning check-ins.

The first two are separate sections so that the symptoms can travel without the tally. Pain during
sex, bleeding after it, dryness, difficulty with an erection — the questions a clinician opens with
— print on their own, apart from the count of acts, protected and unprotected, so a woman can tell
her gynaecologist that intercourse hurts without also handing over how often she had it. The
symptoms section says which ones were reported, and nothing else. No count, because a count of
symptoms is a count of acts wearing a clinical word; no dates; nothing about protection.

The symptoms section stays in the sensitive class even so. The ticks live on the entries for sex and
nowhere else, so a symptom on a sheet does say that sex happened — it just says nothing more. Both
sections therefore keep all five rules below, and each asks its own question, because they put
different things on the page.

The morning check-ins are a third record, not a part of either: answers a man gave on waking,
counted over the period and by week, never morning by morning. They keep the same five rules and
ask a question of their own (`MorningConsentDialog`); ticking sexual activity brings none of them,
and the preview names them on a line of their own when they are included.

Five rules hold it:

1. **No preset ticks any of them.** Enforced by `preselectedParts()` and by a test that walks every
   preset for both sexes. A preset may *suggest* one — the gynaecologist's and the urologist's put
   the symptoms and sexual activity forward, and the urologist's the morning check-ins too — and
   suggesting is not ticking.
2. **"Select all" does not reach it.** The sweep is defined as everything *except* the sensitive
   group, and the row under it says so.
3. **It is included by answering its own question.** `SensitiveConsentDialog` states what would be
   sent, what would not, and offers two depths separately: counts by protection, or the same with
   individual dates. The symptoms are a separate row with a question of their own
   (`SymptomConsentDialog`). Cancelling leaves it unticked.
4. **The preview says which way it fell.** Before anything is saved or shared, the sheet says either
   "Sexual activity: included at your request" or "Sexual activity: not included". The negative is
   printed deliberately: somebody handing a report to a physiotherapist should be able to see at a
   glance that nothing about their sex life is on it.
5. **A report that withholds it says nothing about withholding it.** A section that is not ticked is
   left off the sheet, sensitive or not. The one heading that stays is the problem list, which
   prints "Not included by the patient", and one line at the foot says the sheet carries only the
   sections the person chose. A document that listed what its owner declined would be a list of
   what they are hiding.

Nothing about a partner is carried, because nothing about a partner is recorded. Protection travels
exactly as it was logged — protected or unprotected. An act another app recorded without saying
which is not imported and is not counted, and the caveat under the section says so.

A seventeen-year-old is treated the same way: same gate, same dialog, no silent inclusion and no
silent exclusion, and the test says so. Below sixteen the three sensitive rows are not offered at
all, and below eighteen neither is alcohol and tobacco: the picker follows the app's own age gate
(`AgeGate.kt`, `reportPartsFor(profile, today)`), which draws no door it keeps closed.

## Files

| File | Where it is written | Who can read it | When it goes |
| --- | --- | --- | --- |
| Report PDF (Save) | the document the person picked, through the system picker | whoever can read that folder | never — it is the person's file |
| Report PDF (Share) | `cacheDir/reports/`, handed out as a `content://` URI with a one-shot read grant | the app the person chose | when the next report is shared; an hour old, the next time the app comes forward (`sweepStaleShares`); at the latest at the next fresh start (`sweepExportLeftovers`) |
| Report ZIP ("With files", Save or Share) | as the PDF: the document the person picked, or `cacheDir/reports/` | as the PDF | as the PDF |
| Backup spool (not the report: a backup of the whole record) | `cacheDir/backup*.out` during a backup export | nobody — app-private | its name is removed before a byte is written, so a killed export leaves an empty file at most, which the next fresh start sweeps once it is an hour old |

The share copy cannot be deleted the moment it is sent: the receiving app's read grant outlives this
process. So it goes when the next report is shared, PDF or ZIP; otherwise the next time the app
comes forward once the file is an hour old — the app it was handed to has read it by then — and at
the latest at the next fresh start. The hour matters more than the fresh start: the app's window is
the root of its task and the step service keeps the process, so a fresh start can be weeks away. A
shared ZIP holds a full copy of every attached original until it goes.

## What the app does not do

- **No network.** The app holds no `INTERNET` permission, and a test asserts the merged manifest
  keeps it that way. Nothing is uploaded, ever.
- **No analytics, no crash reporting.** There is no such SDK in the dependency list.
- **No health data in logs.** Every logging call (`grep -rnE '\bLog\.[a-z]+\(' app/src/main/java`)
  is a warning with a constant message and a throwable, except four. Three in the Bluetooth code
  append the protocol token of a failed connection, the error code of a search that could not start,
  and how many glucose records the meter sent that could not be read: a count, never a record. The
  chat's on-device reader logs one of a few fixed reason codes with its throwable, never the
  message it read. None is in the doctor export's path. No value, medicine name, lab result, cycle
  row or sexual-activity row is passed to any of them; `LogCallsTest` reads every call and fails on
  any other message that is not a constant.
- **No patient identity on the sheet.** There is nowhere in the app to type a name. The originals in
  a "With files" archive are another matter: each is copied whole, with whatever is written on it.

## Screen protection

The export screen is inside the app's main activity, so it is covered by the app lock and by
`FLAG_SECURE`, which the lock turns on with it and screenshot blocking turns on alone (both off by
default, in Settings).

## What a clinician should look at

1. The preset table in [SPECIALTY_PROFILES.md](SPECIALTY_PROFILES.md) — is each default the set that
   specialty actually asks for?
2. The reproductive section: is LMP, cycle length with spread, bleeding length, flow, spotting,
   ovulation tests and mucus the right shape, and is anything missing that changes management?
3. The sexual-health summary: are counts by protection, with the symptoms in a section of their own
   that says only which were reported, the clinically useful form, or do dates matter more often
   than assumed?
4. The caveat lines under each section — do they say enough, and do any of them read as a finding?
5. The app's population bands: they are printed with the body that published them, but a clinician
   should say whether printing a verdict word beside a value is helpful or misleading on a sheet
   handed across a desk.
