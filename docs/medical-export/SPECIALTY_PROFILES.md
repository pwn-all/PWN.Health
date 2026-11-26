# Specialty profiles

Written for: whoever is asked to change what a preset ticks.

A preset is a **default selection**. It is not a clinical guideline, it does not state what a doctor
needs, and it replaces no medical guidance. Every tick it makes is shown to the person in the picker
and is theirs to undo before anything leaves the phone.

## What ships

| Preset | Sex | Default window | Ticked by default | Suggested next |
| --- | --- | --- | --- | --- |
| General practitioner | anyone | 6 months | problem list, operations, vaccinations, medicines, blood tests, weight, blood pressure, pulse, activity, sleep, alcohol/tobacco | pregnancy, contraception, body composition, food, mood, SpO₂, temperature, bowel/bladder, visits, pain, work hours |
| Cardiologist | anyone | 3 months | problem list, medicines, blood pressure, pulse, SpO₂, weight, activity, blood tests | sleep, alcohol/tobacco, body composition, food |
| Endocrinologist | anyone | 12 months | problem list, medicines, blood tests, weight, body composition, blood pressure, food, activity | cycle, sleep, mood, alcohol/tobacco |
| Gynaecologist | female | 6 months | problem list, pregnancy, contraception, medicines, cycle, blood tests, weight, blood pressure | **symptoms during sex**, **sexual activity**, mood, body composition, visits |
| Dietitian | anyone | 3 months | problem list, food, weight, body composition, activity, blood tests | sleep, bowel/bladder, medicines, alcohol/tobacco |
| Sports doctor | anyone | 3 months | problem list, operations, activity, pulse, weight, body composition, sleep, food | blood pressure, SpO₂, blood tests, medicines, pain |
| Sleep specialist | anyone | 3 months | problem list, sleep, pulse, SpO₂, activity, medicines | mood, alcohol/tobacco, weight, food, work hours |
| Urologist | male | 6 months | problem list, medicines, blood tests, bowel/bladder, weight, blood pressure | **symptoms during sex**, **sexual activity**, **morning check-ins**, activity, visits |
| Psychologist or psychotherapist | anyone | 3 months | problem list, medicines, mood, sleep, work hours, alcohol/tobacco, activity | pain, visits |
| My own choice | anyone | 6 months | every routine section: everything except the reproductive and the sensitive ones | nothing |

The table is `DoctorSpecialty.kt` read out; that file is the source. Three sections are the app's
own arithmetic over others and follow what they read: a preset that ticks the blood tests also
ticks "Around each lab test", one that ticks the food also ticks "Vitamins and minerals", and
every preset ticks "Changes over the same months". A section a profile is not offered — pregnancy
and the cycle on a man's, the morning check-ins on a woman's — is neither ticked nor suggested
there.

Sex filtering follows the rule the routine check-ups already keep (`Checkup`/`checkupsFor`): a man is
not offered a gynaecologist, a woman is not offered a urologist. Age is deliberately **not** a
filter here — which waiting room somebody is sitting in is not a screening interval, and a
seventeen-year-old sees a gynaecologist.

## The rule about the sensitive sections

`preselectedParts()` filters out anything whose sensitivity is `SENSITIVE`, whatever a preset names.
Two specialties list such sections under *suggested*, which only means the picker puts them forward;
each is ticked by answering its own question and in no other way.

`ClinicalExportTest.noPresetCanEverTickTheSensitiveSection` and
`noPresetTicksEitherSensitiveSection` walk the presets for both sexes and fail if any of them
ever ticks one. Change the presets freely; those tests are the invariant.

## Changing a preset

Presets are a product decision, not a clinical one; PRIVACY.md lists what in them is worth a
clinician's eye. When changing one:

- keep `recommended` to what that specialty routinely asks for — a preset that ticks everything is
  the same as no preset;
- put anything plausible-but-situational in `optional`;
- never put a sensitive section in `recommended`;
- set `defaultMonths` to the window the specialty reasons over: three months for a pressure diary,
  twelve for an endocrine picture — and to one the period control offers (`REPORT_MONTHS`: 3, 6,
  12 or 24);
- change the table above in the same commit: `SpecialtyProfilesDocTest` holds it to the enum.
