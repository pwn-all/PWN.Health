# Comparisons on the doctor's sheet

## What the app records

Everything the app keeps about the person and their surroundings. Each entry says how the figure
gets in and what is kept. **Sheet** names the doctor's-sheet section (`ReportPart`) that prints it;
**Pairs** names the `ChangeDomain` and the comparisons below that read it. A signal with no pair is
a candidate for "Adding a comparison". A signal the sheet does not print cannot be paired at all
(see "What is read").

How a figure gets in:

- **Phone sensors**: the step counter, accelerometer, gyroscope, barometer and ambient-temperature
  sensor. No measurement uses the camera or the microphone.
- **By hand**: the entry editor, the one-tap screens (water, caffeine, alcohol, tobacco, bathroom,
  mood, cycle, intimacy, the morning check-in), the pain map, the symptom chat, the work timer and
  the home-screen widgets.
- **Camera**: a packet's nutrition label (read on the phone) or a plate (dish recogniser), used to
  fill the food form and then discarded; progress photos, which are kept.
- **GPS**: routes, only between Start and Stop on the Track screen or its widget. No location is
  read otherwise, except one fix per scheduled check while Work → Start by location is switched on
  (sideload build only); the fix is compared with the saved point and not kept.
- **Bluetooth devices**: the standard profiles for weight scales, body-composition scales, blood
  pressure cuffs, heart-rate straps, pulse oximeters, thermometers, glucose meters, fitness machines
  (treadmill, rower, indoor bike), running foot pods and cycling power and cadence sensors; also
  Xiaomi scales, the Concept2 PM5, the Keiser M3i and the COLMI ring (steps only).
- **Health Connect**: 28 read permissions and the history permission (the lists are
  `HEALTH_RECORD_TYPES` and `HEALTH_READ_ONLY_TYPES` in `HealthSync.kt`), under which 26 record
  types are read: the two cadences ask for a read permission and are only written. The app's own
  rows are written back.
- **Export files**: Apple Health, Fitbit, Garmin, Strava, Nike Run Club, MyFitnessPal, Cronometer,
  Flo, Oura, Withings, Samsung Health and Google Health; set logs from Hevy, Strong, FitNotes and
  Lyfta, and weigh-ins from wger.
- **Usage access** (optional): phone-interaction events, read only to estimate a night and never
  kept; and the time spent in a fixed list of feed, video, messenger and game apps,
  shown on the Screen time page and not kept either.

### Movement and location

- **Steps.** The phone's step counter, in a background service that checks each batch against the
  accelerometer, so a shaken phone or a car ride does not count; Health Connect; the COLMI ring;
  export files. Kept per day for each source. The day's figure is the largest of them, never a sum,
  so the sheet cannot say which device counted. The phone's own steps are also kept per hour. Sheet:
  `ACTIVITY` (mean a day, days at nought, chart), `LAB_CONTEXT`. Pairs: `STEPS` → `weight_steps`,
  `steps_mood`, `steps_pressure`, `steps_blood`.
- **Walking distance, time and pace.** Worked out from steps × stride (0.414 × height) and the
  routes recorded. Estimates, shown in the app only.
- **Climb.** The barometer, while the step service runs: metres climbed a day. Kept, shown nowhere.
- **Routes.** GPS between Start and Stop: raw and corrected coordinates, accuracy, distance, the
  split into walking, running and vehicle, speed, stops, and elevation gain and loss from GPS and
  the barometer. Exported as GPX, GeoJSON and CSV; only the distance, climb and speed, not the path,
  go to Health Connect. Not on the sheet. Routes from other apps are not imported.
- **Motion state.** The accelerometer tells still, walking, running, vehicle and shaking apart. Live
  only: it decides whether steps count.

### Environment

- **Atmospheric pressure.** The barometer, one reading every 10 minutes while the step service runs:
  station pressure in hPa, a daily mean, and a reading at each route point. Shown live on the Track
  screen during a route, and as its correlation with mood on the Mood screen's weather card. Not on
  the sheet.
- **Air temperature.** The ambient-temperature sensor, which few phones have, only during a route.
  Track screen only.

### Body

- **Weight.** By hand (20–400 kg), Bluetooth scales, Health Connect, export files. The newest
  weigh-in replaces the profile weight everywhere, the energy figures and BMI included. Sheet:
  `WEIGHT`, `PERSON`, `LAB_CONTEXT`. Pairs: `WEIGHT` → `weight_steps`, `food_weight`,
  `weight_pressure`, `weight_blood`.
- **Height.** By hand, scales, Health Connect; the newest replaces the profile height. Sheet:
  `PERSON`.
- **BMI.** Worked out from the newest weight and height, with the WHO adult bands. Sheet: `PERSON`,
  when `WEIGHT` is ticked.
- **Waist, hip and chest.** By hand, in cm. Sheet: `GIRTH`.
- **Body fat.** Body-composition scales (the scale's own figure, or one worked out from a Xiaomi
  scale's impedance), Health Connect, export files. It cannot be typed. Sheet: `BODY_COMPOSITION`.
- **Muscle mass, body water, basal metabolism.** Body-composition scales, Health Connect (water and
  basal rate), Garmin, Withings and Samsung files. Sheet: `BODY_COMPOSITION`.
- **Progress photos.** Camera or gallery, re-encoded without their location. Not on the sheet;
  shared as JPEGs from the gallery.

### Heart and circulation

- **Blood pressure.** A Bluetooth cuff, Health Connect, Apple Health, Cronometer and Withings files.
  There is no form to type one. Systolic and diastolic in mmHg; the cuff's pulse goes to the pulse.
  Sheet: `PRESSURE`. Pairs: `PRESSURE` → `weight_pressure`, `sodium_pressure`, `alcohol_pressure`,
  `steps_pressure`.
- **Pulse.** By hand, the phone laid on the chest (the accelerometer; a rough estimate), a
  heart-rate strap, Health Connect (one sample a day), readings from export files (a resting rate
  from Apple Health, Fitbit and Garmin; spot or daily readings from Samsung, Google, Withings and
  Cronometer). A workout's pulse is kept on its session and left out of this figure. Sheet: `PULSE`.
  Pairs: `PULSE` → `tobacco_pulse`.
- **Heart-rate variability (RMSSD).** From a chest strap's beat-to-beat intervals, when the reading
  is saved on the Devices screen. Sheet: `PULSE`.
- **Blood oxygen (SpO₂).** A pulse oximeter, Health Connect, export files. Sheet: `OXYGEN`.
- **Body temperature.** A Bluetooth thermometer, with where it was taken; Health Connect; export
  files. Sheet: `TEMPERATURE`. There is no basal temperature for the cycle.
- **Blood glucose outside the laboratory.** A Bluetooth glucose meter (every record it holds) and
  Health Connect, in mmol/L, with fasting where the source says so. Sheet: `LABS`, as a separate
  "other glucose readings" row, never as a laboratory result. Never paired (see "Never printed").

### Sleep

- **Nights.** By hand (bedtime and waking on a dial), Health Connect (the longest session a night),
  export files, or an optional estimate from the longest overnight gap in phone use, which measures
  a phone left alone rather than sleep. Start and end only: no stages and no quality rating. Sheet:
  `SLEEP` (nights, mean, spread of bedtimes, shortfall, chart). Pairs: `SLEEP` → `alcohol_sleep`,
  `sleep_mood`, `sleep_wound_up`, `sleep_food`, `caffeine_sleep`, `work_sleep`, `sleep_morning`.

### Food and drink

- **Meals.** By hand (per 100 g or per portion); the offline catalogue of about 5,000 foods, mostly
  from USDA, and the dish builder; a packet label read by the camera; a plate photo; favourites and
  recent meals; Health Connect; MyFitnessPal, Cronometer, Fitbit and Samsung files. Kept per meal:
  energy, protein, fat, carbohydrate and portion grams, and where known fibre, sugar, saturated fat,
  sodium, cholesterol and caffeine, with the name and the time. Sheet: `NUTRITION` (an average day
  over days with at least two meals, chart), `MICRONUTRIENTS`, `LAB_CONTEXT`. Pairs: `FOOD` →
  `food_weight`, `sleep_food`; `SODIUM` → `sodium_pressure`; `SUGAR` → `sugar_blood`;
  `SATURATED_FAT` → `saturated_fat_cholesterol`.
- **Meals a day and meal times.** Read off the entries; two meals make a "full day". A meal carries
  no breakfast, lunch or snack tag, so snacks cannot be counted apart.
- **Vitamins and minerals from food.** Worked out from the catalogue foods a meal was built from ×
  USDA figures, for 18 nutrients. A typed or label-read meal carries none. Sheet: `MICRONUTRIENTS`,
  `LAB_CONTEXT`.
- **Allergens a catalogue food usually contains.** Matched by keyword against the 14 EU allergens.
  App only.
- **Water.** One-tap glasses of 100, 330, 400 or 500 ml, the water widget, Health Connect, Apple
  Health and Fitbit files, against 30 ml per kg (1,600–2,500 ml). Water in food is not counted.
  Sheet: `NUTRITION`. Pairs: `WATER` is described and never paired (see "Never printed").
- **Caffeine.** A drink and a cup size (tea, matcha, coffee, espresso, cappuccino) or milligrams
  typed; caffeine stated on a meal; Health Connect; Apple Health and Cronometer files. mg a day
  against 400. Sheet: `NUTRITION`. Pairs: `CAFFEINE` → `caffeine_sleep`.
- **Energy budget and targets.** Worked out from the profile (Mifflin–St Jeor) and the goal. App
  only.

### Habits

- **Alcohol.** One tap per drink, in grams of ethanol: beer 20, wine 14, spirits 16, a shot 12.6
  at the default size and strength, both of which can be changed; printed as 10 g standard drinks.
  From age 18. Sheet: `SOCIAL`, `LAB_CONTEXT`. Pairs: `ALCOHOL` → `alcohol_mood`,
  `alcohol_wound_up`, `alcohol_sleep`, `alcohol_partnered`, `alcohol_pressure`, `work_alcohol`,
  `alcohol_liver_enzymes`, `alcohol_lipids`, `alcohol_urate`.
- **Tobacco and nicotine.** One tap per cigarette (0.8 g), heated stick, hookah bowl or snus pouch,
  kept in grams and printed as cigarette equivalents. From age 18. Sheet: `SOCIAL`. Pairs: `TOBACCO`
  → `tobacco_pulse`.

### Mood

- **Mood.** One of five faces, 1 to 5, from the Mood screen, its widget or a chat (with an optional
  "what it was about" from a short list, kept in the row and not on the sheet). A single question, not
  a questionnaire. Sheet: `MOOD`. Pairs: `MOOD` → `alcohol_mood`, `sleep_mood`, `steps_mood`,
  `work_mood`.
- **Wound up ↔ settled.** An optional second question, 1 to 5; a day with a 1 or a 2 counts as wound
  up. Sheet: `MOOD`, from seven answered days. Pairs: `WOUND_UP` → `wound_up_partnered`,
  `alcohol_wound_up`, `sleep_wound_up`, `work_wound_up`, `wound_up_pain`, and the third figure of
  `weight_steps`.
- **Breathing practice.** A paced-breathing timer: minutes and breath cycles, no sensor. Health
  Connect's breathing sessions too. App only.

### Bathroom

- **Stools.** One tap per visit: normal, straining or loose (not the Bristol scale), with optional
  ticks: burning, tingling, urgency, pain, not finished, blood. Sheet: `BOWEL`.
- **Urination.** One tap per visit, the same ticks; no volume or colour. Sheet: `BOWEL`.

### Pain

- **Pain map entries.** By hand on the Pain screen, or in a chat, where the areas come from the same
  body map, from places named in the person's words and tapped, or from the list: the areas painted on
  a body map, front and back, each on the person's own side (Nordic questionnaire areas, the head
  split, the muscle bellies between the joints); up to two words for how it feels; a score "on average
  today" from 1, mild, to 10, unbearable (there is no 0: a day without pain is "No pain today"); when
  it started and when it was first noticed; whether it is gone and how long it lasted; a painkiller
  tick; and "No pain today" as a row of its own, so a day can be answered without pain. An area marked
  again within 30 days is asked whether it is the same pain: a follow-up keeps the moment of the first
  report in place of a start of its own and carries its words and first-noticed answer over; it counts
  on its own day like any other pain row. The row's day is the day of the report, never the onset.
  Never sent to Health Connect. Statistics pool the areas into six groups (`PainGroup`): a day is
  answered when it was kept by hand or holds a pain row, and a day with nothing on it is unknown.
  Sheet: `PAIN` (days with pain of the days answered, per group with the median and highest score, the
  words, episodes, episodes that fit the timing of soreness after a logged workout counted apart when
  `ACTIVITY` is ticked, painkiller days, and a line when an area was recorded over more than three
  months; charts). Pairs: `HEAD_NECK_PAIN` → `wound_up_pain`. A pain told in a chat is the same row,
  written by the same rules; its painted areas are on the doctor's drawing, areas tapped from a list
  are not drawn (the page's own list path).

### Symptoms

- **Symptom entries.** In a chat, reached from the home screen's chat circle (for an earlier day,
  from the calendar's add sheet as Chat): one of the chat's symptom topics
  (vomiting, nausea, dizziness, fainting, palpitations, shortness of breath, heartburn, diarrhoea,
  fever, tiredness, chest pain, worry or anxiety, low mood, stress, trouble sleeping, or something
  else), a few short answers of its own (after a meal, what brought it on, how many times, what the
  person was doing, what it felt like, what came with it, since when), mild, moderate or severe; a
  note written at the chat's end belongs to the chat, not to the entry. Beside it the entry keeps a
  snapshot of what the journal held when it was opened, never worked out again: the last meal and how
  long before, the day's food against its target, steps, the last workout and when it ended or that
  there was none, water, standard drinks, caffeine, the night's sleep, the last blood pressure, pulse
  and temperature of the 24 hours before, and the day's stools. An entry filed for an earlier day
  keeps that day's totals only. The row's day is the day of the report; a back-dated one carries that
  day's midday, a millisecond past it from a chat. Tiredness is recorded here only as an entry on a
  day it was logged, never as a daily figure. The chat's own words and turns are not part of the
  entry: they are kept in a separate table, which marks no day kept and reaches no reader of the
  journal. Never sent to Health Connect. Not on the sheet: no `ReportPart` prints it,
  so no pair can read it. A symptom entry is typed in by hand, so it makes its day kept, a back-dated
  one included, as a back-dated meal or pain row does: somebody was writing the diary for that day. A
  day whose only entry by hand is a symptom entry therefore counts as answered for pain, and as
  pain-free unless a pain row is on it; that is accepted. Unlike the
  work timer's row, it is a form filled in answer by answer, not a Start and a Stop.

### Work

- **Work sessions.** The work timer, on the Work screen or its widget: Start, Pause, Resume, Stop,
  kept as the segments between them; net minutes are split at midnight. A session typed in afterwards
  is marked, and one the timer stopped itself at 16 hours is marked and left out of every figure
  until its end is set. The usual days off and where a long day starts are the person's settings.
  Never sent to Health Connect. A timer row does not make a day kept (see "What is read"). Sheet:
  `WORK` (days with work, the counted weeks' median and range, weeks of 48 and 55 hours or more,
  weeks with work on all seven days, the longest day, long days, work after 20:00, usual days off
  with work, the longest run, rests under 11 hours, stretches of 6 hours without a pressed pause,
  typed and capped sessions; charts). Pairs: `WORK` → `work_mood`, `work_wound_up`, `work_sleep`,
  `work_alcohol`.

### Cycle and sexual health

- **Period start and end.** By hand on female profiles, Health Connect, Apple Health, Flo and Fitbit
  files. Sheet: `CYCLE` (last period, cycles, length, period length, chart). Never paired (see
  "Never printed").
- **Flow, ovulation tests, cervical mucus, period pain, desire.** One answer a day on the cycle
  screen; spotting arrives only from Health Connect. Sheet: `CYCLE` (days per answer).
- **Next period and fertile window.** A calendar estimate, not contraception. App only.
- **Partnered sex.** One tap per act, protected or not, from age 16; Health Connect. Sheet:
  `SEXUAL_HEALTH`, which is never preset. Pairs: `PARTNERED` → `wound_up_partnered`,
  `alcohol_partnered`, only when the section goes with its dates.
- **Masturbation.** One tap per act. Sheet: `SEXUAL_HEALTH` (a count). Pairs: `SOLO` →
  `solo_partnered`, only when the section goes with its dates.
- **Morning check-in.** On male profiles from 16, on the Intimate screen only: firm, partly, none or
  "didn't notice", the waking time (from the sleep record or typed, with which) and, optionally, what
  woke him. One a day; "didn't notice" and an unanswered morning are unknown, never none. Never sent to
  Health Connect, never on the home screen, a widget or a notification. Sheet: `MORNING_ERECTIONS` —
  a man's, sensitive, never preset and included only after its own question — with the mornings
  answered, firm or partly, firm, none and "didn't notice" as counts, the usual waking and its spread
  (never the moment of answering, and from one source), and a weekly chart of the share (weeks of at
  least 3 answers). No single morning is listed or dated; the dates that go with them are the chart's
  weeks and the changes section's two stretches, and the question, the caveat and the foot of the
  sheet say so. Pairs: `MORNING` → `sleep_morning`.
- **Symptoms of an act.** Pain, little desire, discomfort; bleeding afterwards and dryness on female
  profiles; erection difficulty, pain on ejaculation and blood in semen on male ones. Sheet:
  `SEXUAL_SYMPTOMS` ("reported", no counts).
- **Pregnancy status and contraception.** Medical history. Sheet: `PREGNANCY`, `CONTRACEPTION`.

### Workouts

- **Sessions.** By hand (54 catalogue exercises, with set and rest timers), routines, fitness
  machines over Bluetooth, running and cycling sensors, Health Connect, export files: the exercise,
  minutes and energy (the machine's own, or from MET). Sheet: `ACTIVITY` (sessions, minutes, chart),
  `LAB_CONTEXT` (the 14 days before an ALT or AST draw). No pairs (see "Not yet").
- **Sets, reps, load, rest, effort (RIR/RPE), supersets.** All of them from a live session; sets,
  reps and load from Hevy, Strong, FitNotes and Lyfta files, with effort from Hevy and Strong. App
  only.
- **Reps counted by the phone.** The accelerometer (jump rope, jumping jacks, stairs, burpees) or
  the gyroscope (squats, lunges, push-ups). The best jump height is live only.
- **Power, cadence, speed, distance, incline, resistance, strokes.** From machines and sensors.
  Kept, not shown after saving; power, cadence and distance go to Health Connect, the rest do not.
- **Planned workouts.** A routine put on a date, or a plan from Health Connect. App only.

### Clinical records

- **Blood and urine tests.** Typed from the laboratory's sheet, or read on the phone from a photo or
  PDF of it and checked before saving: 27 blood markers, plus 3 the app works out, and 6 urine
  markers.
  - Blood sugar: glucose, HbA1c, insulin (HOMA-IR worked out).
  - Lipids: total cholesterol, LDL, HDL, triglycerides, Lp(a) (non-HDL worked out).
  - Kidneys: creatinine, urea, uric acid (eGFR, CKD-EPI 2021, worked out).
  - Liver: ALT, AST, ALP, GGT, bilirubin.
  - Blood count and iron: platelets, haemoglobin, ferritin, transferrin saturation.
  - Inflammation: CRP.
  - Thyroid: TSH, free T4.
  - Vitamins and minerals: vitamin D, B12, folate, magnesium.
  - Urine: albumin-to-creatinine ratio, albumin, creatinine, protein, specific gravity, pH.

  An allergy panel (the IgE class of each allergen tested) is a laboratory record of its own; the
  newest is printed in `ALLERGIES`. Each sheet also keeps the draw day, fasting, the specimen, the
  analyser, the laboratory's own range and the attached scan. Sheet: `LABS`, `LAB_CONTEXT`. The
  blood-result comparisons set GGT, ALT, AST, triglycerides, HDL, uric acid, LDL and non-HDL beside
  the diary; HbA1c is not among them (see "Never printed").
- **Medicines and supplements.** Courses (name, dose as written, times or on waking, before, with or
  after food, dates, and what one dose holds, over 23 nutrients) and each dose marked taken, on the
  Medicine tab or from the alarm. Sheet: `MEDICATIONS`, `MICRONUTRIENTS`, `LAB_CONTEXT`, and, when
  the medicines are ticked, a course's start on its marker's chart in `LABS`. No pairs; courses
  started or ended between the two stretches are named. Doses marked on the Medicine tab, from the
  alarm or in a chat; a dose recorded in a chat without a course is a journal row of its own: it
  makes its day kept, counts in no adherence and is not on the sheet, and one taken for pain with no
  pain marked leaves that day unanswered for pain.
- **Diagnoses, operations, vaccinations.** Free text with dates. Sheet: `PROBLEMS`, `PROCEDURES`,
  `IMMUNISATIONS`; a diagnosis begun from the start of the first stretch on is named in `CHANGES`.
- **Doctor visits.** Specialty, doctor, place, date, attended or not. Sheet: `VISITS` (attended
  ones).
- **Food allergies.** The 14 EU allergens, "none known" or "not tested". Food only. Sheet:
  `ALLERGIES`, with the newest allergy panel.
- **Attached documents.** Files the person picks (lab sheets, scans, prescriptions), kept as links,
  and photos taken in the app, kept in its private storage; attached to a visit, a lab record or a
  medicine course. Not printed on the sheet; the "With files" archive carries a copy of those on the
  records of the ticked sections in the period.

### Profile

Sex (male or female), date of birth (the age is worked out), height and weight at onboarding, units,
step goal, energy goal (lose, keep, gain), and the switches for the sleep estimate and reminders.
Sheet: `PERSON`. The app also counts the days it was opened, for its levels; that is not a health
figure.

### Kept, never shown once saved

The daily barometric climb; the COLMI ring's distance and calorie counter; a cuff's flags (irregular
pulse, movement, loose cuff) and mean arterial pressure; an oximeter's fast and slow SpO₂ and
perfusion index; a body-composition scale's muscle percentage, lean mass and impedance; a glucose
meter's context beyond its fasting code (carbohydrate, exercise, medication, HbA1c); a machine's
incline, resistance, stroke rate and stride. Some of them appear on the Devices screen's reading
card before saving. Each is a question before it is a figure: why keep it?

### Looks tracked, but is not

- A Nutri-Score, NOVA group, glycaemic index or any other food score.
- Snacks as opposed to meals.
- The Bristol stool scale, stool colour, urine volume or colour.
- Sleep stages or sleep quality.
- Stress, a daily tiredness level or depression (see "What the app does not record"). Tiredness
  logged as a symptom is an entry on that day, not a level (see "Symptoms").
- A continuous heart rate or heart-rate zones.
- A pulse or anything else measured with the camera.
- The COLMI ring's sleep, heart rate and SpO₂ (it syncs steps only), and anything from a Bangle.js,
  for which only a parser exists.
- Location outside a recorded route: the fix a Work → Start by location check takes is compared
  with the saved point and forgotten. The phone-use events behind the sleep estimate and the
  screen-time figures are read and dropped.

## About this file

The last section of the doctor's sheet, "Changes over the same months" (`ReportPart.CHANGES`,
`ReportChanges.kt`), sets figures the person recorded side by side and says which of them changed in
the same months. This file is the project's standing record of every such comparison. A pair of
measurements reaches a sheet only after it has been worked through here: why the two can move
together, what else moves them, what the app does not record about them, and in which cases the
app must say nothing at all.

`ComparisonCatalogTest` keeps this file and the code in step. Every `ComparisonPair` and
`LabComparison` key has a `## <key>` section below, after "Pairs" or "Blood results between two
draws". A section for a key the code does not have fails the build, and so does a key with no
section.

## Purpose

A doctor reads trends: steps over a year, sleep over a season, a blood result two years apart. The
person may also want the sheet to say what changed together: "started drinking, and mood dropped",
"drinking more, and the blood results changed", "sleeping less, and feeling worse". The app can set
those changes side by side. It cannot say that one caused the other, and it must not look as if it
does. One person's diary is not an experiment. Two things that moved in the same months may share a
third cause, may have moved in the other order, or may have moved together by chance.

So every comparison is:

- **descriptive**: two figures, earlier → later, with the days behind each;
- **symmetric**: printed whichever way either figure moved, never chosen by direction;
- **from a fixed catalogue**: the sheet says how many pairs it looked at and how many it shows;
- **backed by published research about other people**: one footnote per pair, and the footnote says
  so.

## Rules

### What is read

- A figure is read only from a section the sheet prints. Partnered sexual activity and masturbation
  are read only when the sexual-health section is printed **and** sent with its dates
  (`SexualDetail.DETAILED`): a count per stretch discloses more than the period's total that the
  summary option agreed to. The morning check-ins are read only when their own section is printed,
  which only a man's sheet can carry, and only after its own question; the sexual section's dates
  neither bring them nor are needed for them.
- The stretches never reach outside the period. The one reach past its start is the window before
  a blood draw, and only beside a marker whose newer result falls inside the period (see "The two
  blood results").
- A day counts as kept only if something was written on it by hand. Rows from a watch, Health
  Connect, the phone's own estimates, planned workouts and copied laboratory sheets do not make a
  day kept. Sexual rows do not make a day kept either, except for the partnered count itself.
  Otherwise a count of days beside the alcohol would carry the days of sex onto a sheet that left
  that section off. Nor does the work timer's own row: a Start and a Stop write nothing else down, so
  every timed day would read as a day without a drink, and more work would thin the alcohol rate out
  beside the work figure for no other reason.
- A missing day is never a zero. A day with nothing written on it is left out of every rate. A
  nutrient column left blank is "not entered", not nought. Every figure is printed with the count of
  days, nights or readings behind it, and readings with the number of days they were taken on.
- The mark reads a missing day both ways. Alcohol, smoking and caffeine print their rate over the
  days kept, and are marked "changed" only where the change also holds over every day of the
  stretch, each blank day read as a day without any. Read one way alone, the same 140 g is 70 g a
  week over 14 days kept and 35 g a week over 28, and a diary kept more fully would be marked as
  drinking less.
- Where the rows behind a figure came from different places in the two stretches, such as a pulse
  typed in the first and received through Health Connect in the last, the figure's row says so:
  "source: Entered by hand → Received through Health Connect". A blood-results row carries the same
  note for its two windows. The note never changes the "changed" mark. Steps have no source per day
  (see "What the app does not record"), so their row never carries it.

### Two stretches

| Period | Compared | Why |
|---|---|---|
| 3 and 6 months | First third against last third | The middle third blurs the change and is left out |
| 12 months | First third against last third, plus a line saying the stretches fall in different seasons | Clemes 2011 (J Phys Act Health 8:36, n = 96) measured 1,737 fewer steps a day in winter in normal-weight adults |
| 24 months | Last 122 days against the same days a year earlier | Takes the season out |

The stretches start at the first day the diary holds anything, not at the first day of the period.
A copied laboratory sheet, a planned workout and a sexual row that no printed figure reads do not
start them: the stretches' dates are printed.
A 24-month sheet over a diary of fourteen to sixteen months therefore compares thirds that lie most
of a year apart, on largely the same days of the year. Where the last third, set a year back, shares
half its days or more with the first, the sheet prints no line about seasons: the stretches are
neither in different seasons nor the same days a year apart.

### Figures, gates and thresholds

Every threshold below is **the app's own**, and the row explaining "changed" says so on the sheet.
None is a clinical cut-off. Where a published figure motivated a floor, it is cited in the KDoc
beside the constant in `ReportChanges.kt`.

| Figure | Section | Counted over | Gate per stretch | "Changed" |
|---|---|---|---|---|
| Weight, median | Weight | Weigh-ins | ≥ 3 weigh-ins spread ≥ 14 days | ≥ 3% and ≥ 2 kg |
| Blood pressure, mean | Blood pressure | Cuff readings, deduplicated | ≥ 6 readings on ≥ 3 days | ≥ 5 mmHg systolic or ≥ 4 mmHg diastolic (rounded means) |
| Pulse, mean | Heart rate | Pulse readings (the vitals series leaves out workout pulses) | ≥ 6 readings on ≥ 3 days | ≥ 5 bpm (rounded means) |
| Steps a day | Activity | Days a counter was carried (> 0) | ≥ 21 days and ≥ 60% of the stretch | ≥ 20% and ≥ 1,000 a day |
| Sleep a night | Sleep | Nights (a day's sessions summed) | ≥ 14 nights | ≥ 30 min and ≥ 8% |
| Wound up or restless | Mood | Days the second dial was answered | ≥ 14 answered days | ≥ 20 points and the Newcombe 95% interval leaves out 0 |
| Average mood | Mood | Daily mood means | ≥ 14 answered days | ≥ 0.5 of a point |
| Partnered sexual activity | Sexual health, detailed | Days kept by hand, sexual rows included | ≥ 50% of days kept and ≥ 10 acts over both | Rate ratio ≥ 1.5 either way and the exact test leaves out 1 |
| Alcohol, g a week | Social history | Days kept by hand | ≥ 50% of days kept | ≥ 50% and ≥ 2 standard drinks (20 g) a week, and also over every day of the stretch |
| Smoking, cigarette equivalents a week | Social history | Days kept by hand | ≥ 50% of days kept | ≥ 50% and ≥ 7 a week, and also over every day of the stretch |
| Food energy a day | Food | Days with ≥ 2 entries | ≥ 14 days and ≥ 25% of the stretch | ≥ 20% and ≥ 100 kcal |
| Sugar, g a day | Food | Full days on which every entry carries sugar | ≥ 14 days | ≥ 20% and ≥ 4 g |
| Saturated fat, share of energy | Food | Full days on which every entry carries it | ≥ 14 days | ≥ 20% of itself and ≥ 1 point of energy |
| Sodium, mg a day | Food | Full days on which every entry carries sodium | ≥ 14 days | ≥ 20% and ≥ 500 mg |
| Caffeine, mg a day | Food | Days kept by hand, or with caffeine logged | ≥ 50% of the stretch | ≥ 50% and ≥ 100 mg, and also over every day of the stretch |
| Water, ml a day | Food | Days with a glass | ≥ 14 days | Never marked, never paired (see "Never printed") |
| Work, net hours a week | Work | Counted weeks: seven days at a time back from the stretch's last day, each with ≥ 5 days kept by hand or with a session | ≥ 4 counted weeks | ≥ 20% and ≥ 5 h a week |
| Head or neck pain | Pain | Days answered: kept by hand, or with a pain row, "no pain today" included | ≥ 14 answered days | ≥ 20 points and the Newcombe 95% interval leaves out 0 |
| Morning erection noted, firm or partly | Morning check-ins | Mornings answered firm, partly or none | ≥ 14 answered mornings | ≥ 20 points and the Newcombe 95% interval leaves out 0 |
| Masturbation | Sexual health, detailed | Days kept by hand, sexual rows included | ≥ 50% of days kept and ≥ 10 acts over both | Rate ratio ≥ 1.5 either way and the exact test leaves out 1 |

A figure that does not pass its gate in either stretch is listed as "Not compared", with the counts
that stopped it.

The work figure's weeks are counted back from each stretch's last day, seven days at a time, as the
sheet's other weekly charts count back from the period's end — not Monday to Sunday, as the work
section itself counts them (`workWeekHoursBack` against `workSummary`). A month-long third then holds
four whole weeks whatever day it starts on, where Monday-to-Sunday weeks hold three when it starts in
the middle of a week. A week is counted by the work section's own rule (`WORK_COUNTED_WEEK_DAYS`), so
a week nobody timed is not a week off, and a session the timer stopped at its cap is in no week. The pain and morning
figures take the wound-up figure's gate and threshold as they are; their counts are over what was
answered, never over every day of the stretch.

The floor of three days for blood pressure and pulse is the app's own. It follows the ESH position
paper on home monitoring (Parati 2021, J Hypertens 39:1742, Box 15: seven days of readings, not
fewer than three), so that six readings taken in one evening are not a stretch's mean. The figure is
never called a home average in the ESH sense, because the app asks for no morning and evening
schedule.

### The two blood results

The blood-test section prints the newest panel results of each marker. Where its two newest results
of a marker differ as printed, the exposure is read in the window before each draw. The window is
`LAB_DIET_WINDOWS` in `LabFoodWindow.kt` (LDL and non-HDL 42 days, triglycerides 28, GGT 56, urate
14), or 56 days for a marker with none (ALT, AST, HDL). Each window must pass the exposure's own
gate. Food figures must also cover 60% of the window, in whole days rounded down as the blood-test
screen rounds it (16 of 28, 25 of 42), counted over the full days on which every entry carries the
nutrient. The blood-test screen counts every full day logged, so the two can still differ on a
window in which some entries carry no figure for the nutrient. A row is printed
where both windows pass, the exposure changed past its threshold, and the two results differ. A
window that fails is printed as a refusal: "Before the 12 Mar 2024 result: not recorded". There is
no verdict, no percentage and no word on which way is good. Whether a change in a laboratory result
means anything is exactly the interpretation that turns software into a medical device.

Two pairs of draws are left out without a row or a refusal:

- **Both results before the period.** The newer of the two must be drawn inside the period, the
  rule the blood-test context keeps (`LabSheet.compared`). Otherwise a quarter's sheet would print
  the drinking, weight or steps of two years ago beside two old results, although the person chose
  to share only that quarter. Where the newer result is inside the period, the window before the
  older one may reach back past the period's start: that result is on the sheet, and so is what
  came before it.
- **Windows that overlap.** Two GGT results 20 days apart share 36 of their 56 days, so the
  "change" would be read largely off the same days twice. The draws must be at least a window apart.

### Wording

- The arrow means earlier → later, never "led to". A pair reads: "Alcohol 11 g a week → 25 g a week;
  in the same months, average mood 3.4 of 5 → 2.7 of 5." Its note says "happened in the same
  months".
- "Shown: 5" counts pairs, not changes: one change in the alcohol can fill five pairs. Where the
  pairs shown share a figure, the count row says "Some pairs share a figure, so one change can
  appear in more than one pair". The blood-results count says the same where two of its rows read
  the same diary figure, such as GGT and ALT beside one change in the alcohol.
- The caveat under the section: the diary cannot show which change came first, or whether either
  affected the other.
- The printed text never contains: because, due to, caused, led to, resulted in, improved,
  worsened, better, worse, helped, harmed, effect, impact, linked to, associated with, risk,
  predicts, likely, suggests, indicates, significant, abnormal, deficiency, depression or depressed
  (the app says "lower mood"), stress (the app says "wound up or restless"), libido, dehydrated,
  fast food, junk, unhealthy, consider, check, retest, get tested, reduce, increase, stop, ask
  about. This section also never says anxiety or anxious. A clinical symptom term printed under
  the person's own wound-up figure reads as a hint at a condition, which is the reason "depression"
  and "stress" are banned too. The footnotes say "tense and on edge" or "worry" instead.
  `ReportChangesTest` scans every string the section can emit, footnotes included.
- No disease name that the app has inferred. A condition is repeated only as the person wrote it,
  in the "from the start of the first stretch" row, and only where the problem list is printed.
- Footnotes begin "Background (published research, not about this person):" and end with the
  citation: author, year, journal, and the number of people or studies. Each cited figure is as the
  paper's abstract or the publisher's own summary gives it.

### Regulatory framing

- US: the FDA's *General Wellness: Policy for Low Risk Devices* (January 2026) allows a product to
  display values, trends and longitudinal summaries in relation to sleep, activity and similar
  wellness domains. That holds only without disease references, diagnostic thresholds, alerts
  calling for clinical action, or treatment guidance. Displaying laboratory results is outside the
  device definition (FD&C Act §520(o)(1)(D)) unless the function interprets or analyses them.
- EU: MDCG 2019-11 rev.1 (June 2025) names trend analysis and risk assessment in a self-testing app
  as medical device software. The blood rows therefore describe the diary by date and set two
  printed results beside it. They never judge the change.
- The sheet's stated purpose stays "share my own diary with a clinician in summary form". It is not
  "monitor a condition" or "detect deterioration".

### What the app does not record

- **Tiredness, fatigue, energy level as a daily figure.** The second mood dial asks calm ↔ wound
  up. A symptom entry for tiredness exists only on a day it was logged, so a day without one says
  nothing about tiredness. A pair about "sleeping less → feeling worse" uses the average mood and the
  wound-up share and says only those.
- **Stress.** Not asked. The dial's answers are "wound up" and "restless".
- **Sexual desire as a figure.** "Low desire" is a tick on a logged act, offered to every profile.
  It exists only on days with an act, so weeks without one say nothing about desire. It is never
  turned into a figure or a pair.
- **Salt added at the table, and drinks logged as food.** Sodium is what the food entries carry.
  A drink logged from the food catalogue carries no ethanol.
- **Which device counted the steps.** The report is handed each day's largest count, so a change of
  phone or watch between the stretches cannot be seen, and the steps row never carries the source
  note the other figures do.
- **When in the day caffeine was drunk, relative to bedtime.** The pair counts milligrams a day.

## Pairs

One section per `ComparisonPair`, in the catalogue's order.

## weight_steps

- **Exposure / outcome**: weight (median of weigh-ins) × steps a day. If it changed too, the share
  of days answered wound up or restless is named in the same sentence.
- **Window**: the two stretches.
- **Sentence**: "Weight 80 kg → 84 kg; in the same months, steps 9,000 a day → 6,000 a day[, and
  wound up or restless on 2 of 20 days answered → 12 of 20 days answered]."
- **Why they can move together**: each can move the other. Hankinson 2010 (JAMA, CARDIA, n =
  3,554): over 20 years, young adults who kept high activity gained 2.6 kg (men) and 6.1 kg (women)
  less. Richmond 2014 (PLoS Med, ALSPAC, n = 4,296): a Mendelian randomisation placed higher BMI
  before lower activity in children, without ruling out the other direction.
- **Confounders**: season (the 12-month sheet says so), a new job or commute, injury or illness, a
  change of phone or of carrying habit (Duncan 2018 found phones in everyday use differing from a
  worn accelerometer by 21.5%, put down to the phone not being carried), holidays for weight.
- **Not recorded**: why steps changed, and whether a weigh-in was on the same scale.

## wound_up_partnered

- **Exposure / outcome**: share of days answered wound up or restless × partnered sexual activity
  per 4 weeks. **Sensitive**: only with the sexual-health section printed with its dates.
- **Window**: the two stretches.
- **Sentence**: "Wound up or restless on 2 of 20 days answered → 12 of 20 days answered; in the same
  months, partnered sexual activity 11 per 4 weeks → 2 per 4 weeks."
- **Why they can move together**: Mües 2025 (Psychoneuroendocrinology, n = 63, 14 days, six ratings
  a day): higher momentary stress went with lower desire and arousal at the same moment, and sexual
  activity went with lower cortisol afterwards. The association held at the moment, not over months.
- **Confounders**: the relationship itself, a partner away, illness, a new medicine (the "between
  the two stretches" row lists courses started or ended), pregnancy and the months after a birth.
- **Not recorded**: desire as a figure (see above), the partner's situation, stress as such.

## food_weight

- **Exposure / outcome**: food energy a day (days with 2 entries or more) × weight. The sentence
  calls it "logged food energy", as sleep_food does, so the figure has one name on the sheet.
- **Window**: the two stretches.
- **Sentence**: "Logged food energy 1,800 kcal a day → 2,300 kcal a day; in the same months, weight
  80 kg → 84 kg."
- **Beside the energy**: the food figure's own row prints the entries a counted day held in each
  stretch next to the days counted: "4 → 2.5 entries a day". It is printed on that row only, not in
  the pair or under "Not compared".
- **Why they can move together**: energy balance. Hall 2011 (Lancet 378:826) modelled adult
  metabolism and found the body weight response to a lasting change of intake slow, with half-times
  of about a year. A few months therefore show only part of it.
- **Confounders**: the diary itself. Tiring of the diary lowers the recorded energy without any
  change in eating (the KDoc on `foodFigure` cites Foster 2019 on under-reporting); the entries a
  day printed beside the energy show it. Activity, illness, and medicines that move weight or
  appetite.
- **Not recorded**: meals not logged, portion accuracy.

## alcohol_mood

- **Exposure / outcome**: alcohol, g a week over days kept by hand × average mood.
- **Window**: the two stretches.
- **Sentence**: "Alcohol 11 g a week → 25 g a week; in the same months, average mood 3.4 of 5 → 2.7
  of 5."
- **Why they can move together**: Gawn 2025 (Appl Psychol Health Well Being; two daily-diary
  studies, n = 1,114 and 212 students): nights of medium and high drinking were followed by lower
  positive mood and well-being the next day, and nights of light drinking were not. Poorer sleep
  accounted for part of it in the first study. The other direction also exists: mood moves drinking.
- **Confounders**: a life event behind both, the social season (holidays), sleep, a new medicine.
- **Not recorded**: depression is not measured and never named. The app records a mood from 1 to 5.

## alcohol_wound_up

- **Exposure / outcome**: alcohol × share of days answered wound up or restless.
- **Window**: the two stretches.
- **Sentence**: "Alcohol 11 g a week → 25 g a week; in the same months, wound up or restless on 2 of
  20 days answered → 9 of 20 days answered."
- **Why they can move together**: people commonly expect to drink more when tense. Dora 2023
  (Psychol Bull; individual data from 69 diary studies, n = 12,394) found they did not drink more
  on days of higher negative affect, and did drink more on days of higher positive affect. Months
  were not studied. Next-day tension after drinking is plausible, but no published figure for it is
  cited, so the footnote says only what was measured.
- **Confounders**: work, a life event, sleep, caffeine.
- **Not recorded**: anxiety, stress.

## alcohol_sleep

- **Exposure / outcome**: alcohol × sleep a night.
- **Window**: the two stretches.
- **Sentence**: "Alcohol 11 g a week → 25 g a week; in the same months, sleep 7:30 a night → 6:40 a
  night."
- **Why they can move together**: Ebrahim 2013 (Alcohol Clin Exp Res 37:539, a review of the
  laboratory studies in healthy volunteers): alcohol shortens the time to fall asleep, consolidates
  the first half of the night and disrupts the second half. The published findings are about the
  shape of sleep more than its length.
- **Confounders**: late nights out (the evening that holds the drink also holds the late bedtime),
  shift work, a new baby, the phone's own estimate of sleep (it cannot tell sleep from lying still).
- **Not recorded**: sleep stages or quality. The app records intervals only.

## alcohol_partnered

- **Exposure / outcome**: alcohol × partnered sexual activity. **Sensitive**: only with the
  sexual-health section printed with its dates.
- **Window**: the two stretches.
- **Sentence**: "Alcohol 11 g a week → 25 g a week; in the same months, partnered sexual activity 6
  per 4 weeks → 2 per 4 weeks."
- **Why they can move together, and why the footnote says the research is mixed**: Wang 2018 (Int
  J Impot Res; 24 studies, 154,295 people): light to moderate drinking went with lower odds of
  erectile dysfunction, and heavy drinking with no difference. Patrick 2015 (J Res Adolesc; 731
  students, 56,372 days): on days with more drinks, sex was more common, not less. The direction
  differs by dose and by time scale.
- **The footnote depends on the profile**: Wang 2018 is about erections, so it says nothing about
  a woman's count. On a male profile the footnote carries both studies
  (`report_changes_background_alcohol_partnered_male`). On any other profile it carries only the
  Patrick 2015 diary clause and "The research is mixed" (`report_changes_background_alcohol_partnered`).
- **Confounders**: the relationship, a partner away, going out (drinks and sex on the same
  evenings), medicines.
- **Not recorded**: erectile function as a figure (it is a tick on an act, on male profiles), and
  alcohol-use disorder, which the app never names.

## sleep_mood

- **Exposure / outcome**: sleep a night × average mood.
- **Window**: the two stretches.
- **Sentence**: "Sleep 7:30 a night → 6:10 a night; in the same months, average mood 3.9 of 5 → 3.1
  of 5."
- **Why they can move together**: Triantafillou 2019 (JMIR Ment Health; 208 adults, 6 weeks of
  daily ratings): sleep quality's association with next-day mood was much larger than the reverse.
  The sample is not the general population. It was recruited in four roughly equal groups:
  depressed and anxious, depressed only, anxious only, and controls. The footnote says "about three
  in four of them recruited for low mood, worry or both" and names no diagnosis.
  Palmer 2024 (Psychol Bull; 154 experiments, n = 5,717): experimental sleep loss lowered positive
  affect. Low mood also disturbs sleep, so both directions exist.
- **Confounders**: a new baby, shift work, illness or pain, a new medicine, season and daylight.
- **Not recorded**: tiredness. This pair is the app's answer to "sleeping less → feeling worse",
  and it says mood, because mood is what was asked.

## sleep_wound_up

- **Exposure / outcome**: sleep a night × share of days answered wound up or restless.
- **Window**: the two stretches.
- **Sentence**: "Sleep 7:30 a night → 6:10 a night; in the same months, wound up or restless on 2 of
  20 days answered → 9 of 20 days answered."
- **Why they can move together**: Palmer 2024 (Psychol Bull; 154 experiments, n = 5,717): every
  form of sleep loss raised anxiety symptoms (SMD 0.57–0.63) and lowered positive affect. Being
  wound up also delays sleep. The footnote renders the anxiety symptoms as "feeling more tense and
  on edge" so that no clinical symptom term sits under the person's figure (see "Wording").
- **Confounders**: caffeine (its own pair), work, a life event, illness.
- **Not recorded**: anxiety as such. The dial asks about calm and restlessness, and the pair says
  only that.

## sleep_food

- **Exposure / outcome**: sleep a night × food energy a day (days with 2 entries or more), called
  "logged food energy" in the sentence, as in food_weight.
- **Window**: the two stretches.
- **Sentence**: "Sleep 7:20 a night → 6:10 a night; in the same months, logged food energy 1,800
  kcal a day → 2,250 kcal a day."
- **Why they can move together**: Tasali 2022 (JAMA Intern Med 182:365; a randomised trial, n = 80,
  aged 21–40, BMI 25.0–29.9, usually sleeping under 6.5 hours): two weeks of counselling to sleep
  longer added about 1.2 hours a night (95% CI 1.0 to 1.4), measured on the wrist at home, and went
  with energy intake 270 kcal a day lower than in the controls (−393 to −147). Intake was worked out
  from energy spent (doubly labelled water) and the change in body stores, not from food records,
  and no diet was set. Al Khatib 2017 (Eur J Clin Nutr 71:614; 17 studies reviewed, 11 with 172
  people meta-analysed): shortened sleep, with food eaten freely, went with 385 kcal a day more (252
  to 517) and no clear change in energy spent. Between them the two cover both directions of a
  change in sleep. Neither gives kcal per hour of sleep, and the sheet never works one out.
- **Where the research and the diary differ**: Tasali measured intake because food records miss
  food. The app's figure is the energy logged, so a change can come from how fully the days were
  logged, or from portion estimates that shift with a routine that also moves sleep; the food row
  prints the entries a counted day held in each stretch. The trial lasted two weeks, only lengthened
  sleep, and was in short sleepers aged 21–40 carrying extra weight, against a control group; the
  app compares one person's months, either way. Al Khatib pools short studies of shortened sleep,
  which matches the direction "less sleep" but not months or a home diary.
- **Confounders**: the diary itself (tiring of it lowers the recorded energy without any change in
  eating; the `foodFigure` KDoc cites Foster 2019), days or meals not logged, portion estimates,
  shift work, a new baby, alcohol (its own pair with sleep), illness, medicines that move appetite
  or sleep, a deliberate change of diet, the phone's own estimate of sleep.
- **Not recorded**: appetite or hunger, which the sheet never names; meals not logged; when food was
  eaten relative to sleep. The pair never goes on to weight: food_weight is a row of its own.

## caffeine_sleep

- **Exposure / outcome**: caffeine, mg a day × sleep a night.
- **Window**: the two stretches.
- **Sentence**: "Caffeine 150 mg a day → 400 mg a day; in the same months, sleep 7:30 a night →
  6:40 a night."
- **Why they can move together**: Drake 2013 (J Clin Sleep Med, n = 12 healthy sleepers): 400 mg
  taken 6 hours before bed was followed by more than an hour less objectively measured sleep than
  placebo. Short sleep also sends people to coffee the next day.
- **Confounders**: the timing of the cups (not compared), work hours, a new baby.
- **Not recorded**: when the caffeine was drunk relative to bedtime, and caffeine in food entries
  that do not state it.

## steps_mood

- **Exposure / outcome**: steps a day × average mood.
- **Window**: the two stretches.
- **Sentence**: "Steps 9,000 a day → 5,500 a day; in the same months, average mood 3.9 of 5 → 3.2 of
  5."
- **Why they can move together**: Rehder 2026 (Nat Hum Behav; individual data from 67 datasets, n =
  8,223, accelerometer and phone ratings): affective well-being went with both earlier and later
  activity in everyday life. Within a person, activity went with more energy and positive affect and
  with less calm. The associations were small (r of about 0.04–0.05 within persons).
- **Confounders**: season and daylight, illness or injury, a new job, a change of phone.
- **Not recorded**: movement that is not steps (swimming, or cycling on a watch that writes no
  steps).

## weight_pressure

- **Exposure / outcome**: weight × blood pressure, mean of the cuff's readings.
- **Window**: the two stretches.
- **Sentence**: "Weight 80 kg → 84 kg; in the same months, blood pressure 124/80 mmHg → 133/85
  mmHg."
- **Why they can move together**: Neter 2003 (Hypertension; 25 trials, n = 4,874): about 1.05 mmHg
  systolic and 0.92 mmHg diastolic lower for each kilogram lost.
- **Confounders**: a blood-pressure medicine started or ended (listed in the "between" row), sodium,
  alcohol, the time of day and the arm the readings were taken on, a different cuff.
- **Not recorded**: posture, rest before the reading, cuff size.

## sodium_pressure

- **Exposure / outcome**: sodium, mg a day × blood pressure.
- **Window**: the two stretches.
- **Sentence**: "Sodium 2,300 mg a day → 3,100 mg a day; in the same months, blood pressure 124/80
  mmHg → 133/85 mmHg."
- **Why they can move together**: He 2013 (BMJ, a Cochrane review; 34 trials, n = 3,230): 75 mmol a
  day less urinary sodium (4.4 g of salt, about 1.7 g of sodium) for at least four weeks went with
  blood pressure 4.18/2.06 mmHg lower.
- **Confounders**: salt at the table and in food without a label (not in the diary), weight,
  alcohol, medicines.
- **Not recorded**: urinary sodium, salt added at the table.

## alcohol_pressure

- **Exposure / outcome**: alcohol × blood pressure.
- **Window**: the two stretches.
- **Sentence**: "Alcohol 40 g a week → 180 g a week; in the same months, blood pressure 124/80 mmHg
  → 133/85 mmHg."
- **Why they can move together**: Roerecke 2017 (Lancet Public Health; 36 trials, n = 2,865): above
  two drinks a day, about halving intake went with lower blood pressure, most of all at six or more
  (5.50/3.97 mmHg lower). At two or fewer a day, no significant difference was found, and the
  footnote says so.
- **Confounders**: weight, sodium, readings taken the morning after, medicines.
- **Not recorded**: drinks logged as food.

## steps_pressure

- **Exposure / outcome**: steps a day × blood pressure, mean of the cuff's readings.
- **Window**: the two stretches. The pressure's six readings must fall on three days in each (see
  "Figures, gates and thresholds").
- **Sentence**: "Steps 5,200 a day → 8,100 a day; in the same months, blood pressure 132/84 mmHg →
  125/80 mmHg."
- **Why they can move together**: Lee 2021 (Cochrane Database Syst Rev CD008823; 73 trials of a
  walking programme against none, n = 5,763, aged 16 and over): systolic pressure 4.11 mmHg
  lower (95% CI 3.01 to 5.22 lower; 73 trials, n = 5,060; moderate certainty) and diastolic 1.79
  mmHg lower (1.07 to 2.51 lower; 69 trials, n = 4,711; low certainty). The programmes lasted 15
  weeks and 153 minutes of walking a week on average, mostly at moderate intensity. The footnote
  rounds these to 4 and 2 mmHg and calls the diastolic figure less certain.
- **Where the research and the diary differ**: the trials set minutes of walking against a control
  group and mostly measured pressure in the clinic, by protocol. The app's figure is the day's
  largest step count from any device, walking about the day included, so more steps do not show
  that brisk walking was added, and the footnote says the trials counted minutes, not steps. The
  app's own threshold for pressure (5 mmHg systolic or 4 diastolic) is larger than the trials'
  average difference, so the footnote is never an expected size, and no mmHg per 1,000 steps is ever
  worked out. Bravata 2007 (JAMA 298:2296; 26 studies, n = 2,767) did count steps: in its randomised
  trials pedometer users walked about 2,491 steps a day more than controls. Its systolic fall of 3.8
  mmHg was measured before and after within the pedometer groups, across trials and observational
  studies together, so it is not the footnote's source.
- **Confounders**: a blood-pressure medicine started or ended (the "between" row), weight (its own
  pair), sodium, alcohol, season (the 12-month sheet says so), a change of phone or watch or of
  carrying habit (Duncan 2018), the time of day, the arm and the cuff.
- **Not recorded**: how brisk the walking was, which device counted the steps, posture and rest
  before a reading.

## tobacco_pulse

- **Exposure / outcome**: smoking, cigarette equivalents a week × pulse, mean of readings.
- **Window**: the two stretches.
- **Sentence**: "Smoking 20 cigarette equivalents a week → 70 cigarette equivalents a week; in the
  same months, pulse 64 bpm → 71 bpm."
- **Why they can move together**: Linneberg 2015 (Circ Cardiovasc Genet; 23 studies, n = 141,317):
  among current smokers, each further cigarette a day went with a resting heart rate 0.21 bpm
  higher, and the Mendelian randomisation put it at about 7 bpm at 20 a day.
- **Confounders**: caffeine, fitness, illness, a new medicine (beta-blockers, thyroid), a different
  device.
- **Not recorded**: the nicotine in pouches and heated sticks (counted as grams of tobacco, not
  nicotine), and whether a reading was taken at rest.

## work_mood

- **Exposure / outcome**: work, net hours a week over the counted weeks × average mood.
- **Window**: the two stretches; the work figure needs four counted weeks in each.
- **Sentence**: "Work 38 h a week → 52 h a week; in the same months, average mood 3.7 of 5 → 3.1 of
  5."
- **Why they can move together**: Virtanen 2018 (Scand J Work Environ Health 44:239; 28 cohorts,
  189,729 workers in 35 countries): working 55 hours a week or more, against 35 to 40, went with new
  depressive symptoms 1.14 times as often; 1.50 in Asian cohorts, 1.11 in European ones, and no
  difference in North America or Australia. Rugulies 2021 (Environ Int 155:106629; the WHO/ILO
  review, 22 cohorts, 109,906 workers) put the same comparison at 1.08 (0.94 to 1.24) and judged the
  evidence inadequate. The footnote says "low-mood symptoms", names no condition, and ends "The
  research is mixed". Low mood also shortens working weeks, through sick days and lost drive, so both
  directions exist.
- **Where the research and the diary differ**: the cohorts set bands of hours against each other over
  years, in groups; the diary sets two stretches of one person's tracked hours, most often below 55,
  against a 1 to 5 tap that is no symptom scale. Fisher 2018 (PNAS 115:E6106): variation within one
  person was two to four times larger than across the group, so a group's figure is not this diary's.
  The tracker records time between Start and Stop, not load (Ilies 2007, in the work section's
  footnote), and people asked about their usual week name 5 to 12% more hours than their time diaries
  show (Robinson 2011), so the diary's hours run below the survey figures the research used.
- **Confounders**: illness (fewer hours and lower mood together), holidays and season, a new job, sleep
  (its own pair), alcohol, a life event, the timer used on fewer days (weeks not counted).
- **Not recorded**: how heavy the work felt, the commute, work nobody timed, the kind of work.
  Depression is never measured or named.

## work_wound_up

- **Exposure / outcome**: work, net hours a week × share of days answered wound up or restless.
- **Window**: the two stretches.
- **Sentence**: "Work 38 h a week → 52 h a week; in the same months, wound up or restless on 3 of 20
  days answered → 11 of 20 days answered."
- **Why they can move together**: Sonnentag 2008 (J Appl Psychol 93:674; 166 employees over one work
  week): on evenings of low detachment from work, people reported more fatigue and more negative
  activation the next morning; the footnote says "more tired and more tense". Wendsche 2016 (Front
  Psychol 7:2072; 91 samples, 38,124 employees): work in free time went with less detachment, and
  detachment with less exhaustion and fewer sleep complaints.
- **Where the research and the diary differ**: both measured detachment rather than hours, and days
  or cross-sections rather than months; the diary has hours only. A busy stretch can also be one the
  dial was answered on less often, and the days answered are printed beside the share.
- **Confounders**: sleep, caffeine, alcohol, a life event, illness.
- **Not recorded**: detachment, workload, stress or anxiety (never named; the dial asks wound up and
  restless).

## work_sleep

- **Exposure / outcome**: work, net hours a week × sleep a night.
- **Window**: the two stretches.
- **Sentence**: "Work 38 h a week → 52 h a week; in the same months, sleep 7:20 a night → 6:30 a
  night."
- **Why they can move together**: Virtanen 2009 (Sleep 32:737; British civil servants, n = 937 to
  1,594): more than 55 hours a week, against 35 to 40, went with newly shortened sleep (odds ratio
  1.98) and difficulty falling asleep (3.68) about five years later. Ikeda 2025 (Occup Environ Med
  82:476; 98 daytime workers, 10 days each): nights after workdays of more than 13 hours were shorter
  on a wrist actigraph than nights after days of 9 hours or less.
- **Where the research and the diary differ**: Virtanen compares bands years apart and Ikeda days
  within people; the sheet compares two stretches' weekly means, and the night is whatever the diary
  holds — typed, from Health Connect or the phone's own estimate, which measures a phone left alone.
- **Confounders**: the commute, shift work, a new baby, caffeine (its own pair), alcohol, illness.
- **Not recorded**: shift timing as a class (the segments are kept, not classed), sleep quality.

## work_alcohol

- **Exposure / outcome**: work, net hours a week × alcohol, g a week over days kept by hand.
- **Window**: the two stretches.
- **Sentence**: "Work 38 h a week → 52 h a week; in the same months, alcohol 40 g a week → 110 g a
  week."
- **Why they can move together**: Virtanen 2015 (BMJ 350:g7772; 20 studies following 100,602
  workers): at 55 hours a week or more, against 35 to 40, drinking above the studies' weekly limits
  began 1.12 times as often, 0.7 percentage points more over a 6.2% base. Wang 2010 (J Appl Psychol
  95:377; 57 workers, five weeks of daily reports): on days work spilled more into family life,
  people drank more. The footnote says "drinking above the studies' weekly limits" and never names
  risky drinking or a disorder.
- **The days the rate is counted over**: a day with nothing on it but the work timer's row is not a
  day kept (see "What is read"). Counted as one, every timed day would be a day without a drink, and
  the pair would set more hours beside a rate that fell only because of them.
- **Confounders**: drinking tied to work (events, travel), season and holidays, a new job, illness,
  a diary kept less often in busy weeks (the mark asks the change to hold over every day as well).
- **Not recorded**: drinks logged as food. The in-app card compares long work days with other work
  days evening by evening; the sheet does not.

## wound_up_pain

- **Exposure / outcome**: share of days answered wound up or restless × share of days answered with
  pain in the head or the neck and shoulders (`PainGroup.HEAD` and `PainGroup.NECK_SHOULDERS`: the
  head's areas and the jaw; the neck, shoulders and upper back).
- **Window**: the two stretches; each figure needs 14 answered days in each.
- **Sentence**: "Wound up or restless on 3 of 20 days answered → 11 of 20 days answered; in the same
  months, head or neck pain on 2 of 25 days answered → 9 of 24 days answered."
- **Why they can move together**: Wöber 2007 (Cephalalgia 27:304; PAMINA, 327 people, a three-month
  diary): neck-muscle tension and mental tension on the preceding days raised the chance of headache
  by less than a third, and menstruation mattered more. Lundberg 1994 (Int J Behav Med 1:354): mental
  laboratory tasks raised the activity of the trapezius muscle with no physical work being done. The
  footnote says "less than a third more often" and "mentally demanding tasks"; it never says stress.
  A day of pain can also make the evening's answer on the dial, so both directions exist.
- **Where the research and the diary differ**: a diary of days and a laboratory's minutes, against
  months here. The dial is not a stress scale, and an area is where the person painted, not a
  headache type — the sheet never names one.
- **Confounders**: sleep (its own pairs), cycle days (Wöber's strongest factor; the sheet never names
  a phase), work days (the work pairs), caffeine, illness, a new medicine, a diary answered more on
  bad days (the days answered are printed).
- **Not recorded**: stress, the kind of headache. Screen time is read from Usage access for a fixed
  list of apps and shown on its own page; it is not kept, not on the sheet and paired with nothing.
  Painkiller days stay in the pain section, as a count.

## sleep_morning

- **Exposure / outcome**: sleep a night × share of the mornings answered with a morning erection
  noted, firm or partly. **Sensitive and a man's**: the morning figure is read only when the morning
  check-ins' own section is printed, which only a male profile's sheet can carry, and only after its
  own question.
- **Window**: the two stretches; 14 nights and 14 answered mornings in each.
- **Sentence**: "Sleep 7:25 a night → 5:50 a night; in the same months, a morning erection noted on
  20 of 26 mornings answered → 7 of 24 mornings answered."
- **Why they can move together**: erections during sleep come with REM sleep (Hirshkowitz 2005, in
  the morning section's own footnote), and REM is most plentiful near the end of a night that ends at
  the usual waking time (Dijk & Czeisler 1995, J Neurosci 15:3526; 8 men) — a shortened night cuts
  its end. The footnote carries the hormone studies with their limits: Leproult 2011 (JAMA 305:2173;
  10 young men): a week with 5 hours in bed a night went with daytime testosterone 10 to 15% lower.
  Su 2021 (Sleep Med 88:267; 18 studies, 252 men): short partial sleep loss showed no clear change;
  24 hours or more without sleep went with a fall. "The research is mixed and is about hormones, not
  morning erections."
- **Where the research and the diary differ**: no study of months of sleep and morning erections
  is cited. The morning answer depends on the stage of sleep a man wakes from, so one morning says
  little, and a share over two stretches is all the sheet gives.
- **Confounders**: antidepressants, which reduce REM most early in a course (Wilson & Argyropoulos
  2005, Drugs 65:927) — a course started or ended is on the "between" row; alcohol (evening drinking
  and the next morning is in-app only, see "Not yet"); illness; waking by an alarm or being woken
  (recorded on the check-in, not compared on the sheet); snoring or pauses in breathing (not
  recorded); the phone's own sleep estimate.
- **Not recorded**: sleep stages, hormones. Never "lowered testosterone", never "improved".

## solo_partnered

- **Exposure / outcome**: masturbation per 4 weeks × partnered sexual activity per 4 weeks, both over
  the days kept by hand with the acts included, each with the partnered figure's own gate (half the
  stretch's days kept, 10 acts over both) and rule (a rate ratio of 1.5 either way and the exact test
  leaving out 1). **Sensitive**: only with the sexual-health section printed with its dates.
- **Window**: the two stretches.
- **Sentence**: "Masturbation 3 per 4 weeks → 10 per 4 weeks; in the same months, partnered sexual
  activity 8 per 4 weeks → 2 per 4 weeks."
- **Why they can move together, and why the footnote depends on the profile**: the published
  direction differs by sex. Regnerus 2017 (Arch Sex Behav 46:2111; 7,648 US men): men who
  masturbated more tended to have somewhat less partnered sex, a compensatory tendency, and how
  content they were with their sex life went with masturbation more closely than how often they had
  sex. Herbenick 2023 (Arch Sex Behav 52:1317; 1,784 US men): masturbation went with wanting more
  partnered sex, not with how much they had. In women the same surveys (8,090 and 1,958) found a
  complementary tendency: more masturbation went with more partnered sex, not less. A male profile's
  sheet carries the men's findings (`report_changes_background_solo_partnered_male`), any other the
  women's.
- **Where the research and the diary differ**: surveys between people at one time, against one
  person's months. The in-app card "Solo and partnered" is the app's answer to "does masturbating
  more mean trouble with partnered sex?": the two counts side by side for the last four weeks and the
  eight before, with no verdict.
- **Confounders**: the relationship itself, a partner away, wanting more or less sex than the
  partner (not recorded), illness, a new medicine, pregnancy or a birth, the wound-up share (its own
  pair with partnered activity).
- **Not recorded**: satisfaction, desire as a figure, pornography (never named). Symptom ticks stay
  "reported", never a rate.

## Blood results between two draws

One section per `LabComparison`, in the catalogue's order.

## alcohol_liver_enzymes

- **Exposure / markers**: alcohol, g a week over days kept by hand × GGT, ALT, AST.
- **Window**: GGT 56 days (`LAB_DIET_WINDOWS`); ALT and AST 56 days (the default).
- **Row**: "Alcohol in the 56 days before each result: 5 g a week → 30 g a week; GGT 22 U/L → 41 U/L
  (12 Mar 2024 → 20 Aug 2026)".
- **Why they can move together**: Alatalo 2008 (Am J Clin Nutr, n = 2,164): moderate drinkers had
  higher ALT and GGT than people who did not drink, and AST was no different; the difference grew
  with BMI. Orrego 1985 (Alcohol Clin Exp Res; 32 patients with alcoholic liver disease who stayed
  abstinent for 8 weeks): the GGT half-life was 26 days, so a result carries weeks of drinking. It
  is the half-life `LabFoodWindow` weights GGT's window by.
- **Confounders**: weight (see weight_blood), medicines (statins, anticonvulsants), a different
  laboratory, and exercise before the draw for ALT and AST. Pettersson 2008 (Br J Clin Pharmacol
  65:253; 15 healthy men who did not usually lift weights, 14 analysed): about an hour of
  weightlifting (73 minutes on average) was followed by AST and ALT still higher than before on day
  7, and 10 to 12 days after it most of the men still had ALT above the laboratory's upper limit;
  GGT did not rise. The ACG guideline (Kwo 2017, Am J Gastroenterol 112:18) lists strenuous exercise
  among the causes of raised AST and ALT outside the liver; the app records no CK. "Around each
  blood test" (`LAB_CONTEXT`) therefore lists the workouts logged in the 14 days before an ALT or
  AST draw, and none beside GGT. It names the session and the day only: the app cannot tell a hard
  or unaccustomed session from a routine one.
- **Not recorded**: drinks logged as food, one heavy day hidden in a weekly mean.

## alcohol_lipids

- **Exposure / markers**: alcohol, g a week over days kept by hand × triglycerides, HDL.
- **Window**: triglycerides 28 days (`LAB_DIET_WINDOWS`); HDL 56 days (the default, not the lipids'
  42).
- **Row**: "Alcohol in the 56 days before each result: 5 g a week → 30 g a week; HDL 1.2 mmol/L →
  1.5 mmol/L (12 Mar 2024 → 20 Aug 2026)".
- **Why they can move together, and why the footnote also prints a null**: Brien 2011 (BMJ
  342:d636; 63 interventional studies in adults without known heart disease, 44 meta-analysed;
  periods of drinking against periods without, fasting samples): HDL was 0.094 mmol/L higher while
  drinking (95% CI 0.064 to 0.123; 33 studies, n = 796), more at larger amounts: 0.072 at
  12.5–29.9 g a day, 0.103 at 30–60 g and 0.141 above 60 g, about 88–209, 210–420 and over 420 g a
  week. Triglycerides did not differ on average (0.016 mmol/L, −0.018 to 0.051; 31 studies, n =
  752), except above 60 g a day, where two studies found them 0.274 mmol/L higher (0.043 to 0.505).
  The studies lasted a week to three months, mostly two to six weeks, which fits the windows. The
  triglycerides stay because doctors ask about them, and the footnote prints the null with its
  exception.
- **Where the research and the diary differ**: the smallest amount tested was about 88 g a week,
  and the app marks a change from 20 g a week and half again, so many printed changes (30 → 60 g a
  week, say) lie below anything the trials tested; the footnote gives grams so the doctor can
  compare. The trials set drinking against none; the diary sets two amounts side by side. A rise of
  0.07–0.14 mmol/L is within the test's own within-person variation, which the blood-test context
  section prints as its own footnote: from HDL's 5.7%, about 19% up or 16% down, roughly 0.2–0.3
  mmol/L at 1.2–1.6 mmol/L. So is the change in saturated_fat_cholesterol. Brien calls the changes
  favourable; the sheet does not, and never calls HDL "good".
- **Confounders**: exercise and weight loss, which also raise HDL (steps_blood reads HDL too), lipid
  medicines, a sample taken after food (the blood-test section prints the fasting state), sugar and
  weight for the triglycerides, a different laboratory.
- **Not recorded**: drinks logged as food, one heavy day hidden in a weekly mean.

## alcohol_urate

- **Exposure / markers**: alcohol × uric acid.
- **Window**: 14 days.
- **Why they can move together**: Choi 2004 (Arthritis Rheum; NHANES III, n = 14,809): serum urate
  was higher by 0.46 mg/dL for each daily serving of beer and 0.29 mg/dL for liquor, and not higher
  with wine.
- **Confounders**: the kind of drink (not separated in the weekly figure), diet, diuretics, weight,
  kidney function.

## steps_blood

- **Exposure / markers**: steps a day × triglycerides, HDL (not HbA1c: see "Never printed").
- **Window**: triglycerides 28 days, HDL 56.
- **Why the footnote also prints a null**: Kodama 2007 (Arch Intern Med 167:999; 25 trials): aerobic
  training went with HDL 2.53 mg/dL (0.065 mmol/L) higher, from about 120 minutes or 900 kcal a
  week, and more with longer sessions. That is structured training, and steps a day are mostly
  walking. In trials of walking alone the lipids did not differ: Murtagh 2015 (Prev Med 72:34; 32
  trials of walking in inactive adults) found HDL 0.01 mmol/L higher (−0.04 to 0.07; 15 trials, n =
  725), no clear difference, and Kelley 2004 (Prev Med 38:651; 25 trials, n = 1,176) found HDL and
  triglycerides no different, with LDL 5.5 mg/dL lower. The footnote gives both, printing the null
  as alcohol_lipids does, because doctors ask. Nothing here ties steps to triglycerides except that
  null.
- **Confounders**: weight, diet, alcohol (HDL; see alcohol_lipids), season, medicines.
- **Not recorded**: how brisk the walking was, and movement that is not steps.

## weight_blood

- **Exposure / markers**: weight × LDL, triglycerides, ALT (not HbA1c: see "Never printed").
- **Window**: LDL 42 days, triglycerides 28, ALT 56.
- **Why they can move together**: Dattilo 1992 (Am J Clin Nutr 56:320; 70 studies): weight loss by
  dieting went with lower total and LDL cholesterol and triglycerides. Alatalo 2008 (n = 2,164):
  liver enzymes were higher at a higher BMI.
- **Confounders**: what the weight change came from (diet, illness, medicine), alcohol for ALT.

## sugar_blood

- **Exposure / markers**: sugar, g a day × triglycerides (not HbA1c: see "Never printed").
- **Window**: 28 days.
- **Why they can move together**: Te Morenga 2014 (Am J Clin Nutr 100:65; 39 trials, 37 with
  lipids): higher sugar intakes went with triglycerides 0.11 mmol/L higher (95% CI 0.07 to 0.15).
- **Confounders**: total energy and weight, a sample taken after food, and the sugar column being
  filled in on only some days (the gate counts only full days on which every entry carries it).

## saturated_fat_cholesterol

- **Exposure / markers**: saturated fat, share of energy × LDL, non-HDL cholesterol.
- **Window**: 42 days.
- **Why they can move together**: Mensink 2016 (WHO; 84 studies): each 1% of energy moved from
  saturated to polyunsaturated fat went with LDL 0.055 mmol/L lower. A shift of five percentage
  points is therefore about 0.3 mmol/L, within the test's own within-person variation, which the
  blood-test context section prints as its own footnote.
- **Confounders**: statins or other lipid medicines (listed with the medicines), weight, a different
  laboratory, the season.

## Never printed

These comparisons are never made. They are listed so that none is added by accident.

### Water and kidney results

Water stays a row in the food section and never joins a pair. What is logged is not all that is
drunk: food and unlogged drinks carry the rest. Some patients are told to drink less. Clark 2018
(JAMA 319:1870, CKD WIT, n = 631) found a year of coaching to drink more left the decline in
filtration rate no different. A kidney condition exists in the app only as free text, and matching
it to water would be the app naming and interpreting a disease.

### "Fast food" and blood results

A food entry has no catalogue id, so "fast food" cannot be counted, and the word is on the
forbidden list. Nutrients are compared only as columns (sugar, saturated fat, sodium), over the days
that carry them.

### Supplements as the reason for a result

The blood-test context section lists the courses running before each draw, with the doses marked,
and the published average response for a labelled supplement. This section adds no pair between a
course and a result: most courses carry no structured composition, and a verdict on a supplement in
one person's two results is not something the diary can support.

### Alcohol and sexual function as a diagnosis

The alcohol_partnered pair sets two counts side by side and says the research is mixed. It never
names erectile dysfunction or an alcohol-use disorder about the person.

### Tiredness, stress, desire

Not recorded as figures (see "What the app does not record"), so never compared.

### The insights engine's correlations

`Insights` removes a 28-day moving median before it correlates anything, which takes out the
months-long drift this section is about, and its hypotheses are tuned for the phone screen. None of
its output reaches the sheet.

### HbA1c beside a diary figure

Nathan 2008 (Diabetes Care 31:1473, ADAG, n = 507) shows what HbA1c averages: the glucose of about
the three months before it (R² = 0.84). That explains the marker's 90-day window, not a link with
steps, sugar grams or weight, and set as the footnote under such a comparison it would read as
though it did. In adults without diabetes the trials show no clear HbA1c change for what the diary
records:

- **Steps.** Cavero-Redondo 2018 (Sports Med 48:1151; 15 studies): against control groups,
  endurance exercise (2 studies) and activity counselling (3 studies) left HbA1c not clearly
  different. The one clear subgroup was supervised low-intensity resistance training, which steps do
  not measure, and the overall result stopped being clear once one study was left out. The effects
  are standardised, not in HbA1c units.
- **Sugar.** Choo 2018 (BMJ 363:k4644; 155 trial comparisons, n = 5,086): sugars added as extra
  energy left HbA1c no different (6 comparisons); sugars in place of starch at equal energy went
  with HbA1c 0.22 percentage points lower, mostly in people with diabetes or a higher HbA1c; low
  certainty throughout. The diary's total sugar cannot tell added sugar from sugar in place of
  starch. The abstract's "−25.9 mmol/mol" is a conversion error and is never copied.
- **Weight.** No published figure for weight and HbA1c in people without a diagnosis is cited.

Clear effects exist in diabetes (Umpierre 2011, JAMA 305:1790: structured exercise, 0.67 percentage
points lower in type 2 diabetes), and naming that group under a person's own result would read as a
hint at a diagnosis. "Around each blood test" (`LAB_CONTEXT`) still prints the steps, the sugar and
carbohydrate over HbA1c's 90-day window, and the weigh-in nearest each HbA1c draw, with no footnote.
`ComparisonCatalogTest` fails if a `LabComparison` lists HbA1c.

### Glucose meter readings

A meter's readings are printed as their own "other glucose readings" row in `LABS` and are never
set beside a diary figure. The person chooses when to test (after a meal, when unwell, when
something felt off), so a month's mean says as much about when they tested as about their glucose.
The only context the report reads with a reading is its fasting code. And someone who tests at
home usually does so because a condition is being followed: a pair beside the readings would turn
the sheet from sharing a diary into monitoring a condition (see "Regulatory framing").

### The cycle's phase and mood, sleep or weight

No `ChangeDomain` reads `CYCLE`. The stretches are months long, so each holds several cycles and
every phase, and a month's figure averages the phase out. The phase itself is a calendar estimate
(the app keeps no basal temperature), and a row about "the days before a period" would name a
premenstrual condition the app does not diagnose. `ComparisonCatalogTest` fails if a figure reads
`CYCLE`.

### Weight and sleep

Both figures are paired with others, never with each other. A doctor would read breathing during
sleep into weight set beside sleep, and the app records neither breathing nor snoring during sleep;
the pair would hint at a condition the diary cannot show. `ComparisonCatalogTest` fails if a pair
names both.

### Workouts beside kidney markers

"Around each blood test" lists workouts before an ALT or AST draw only, never beside creatinine,
eGFR or urea. Hodgson 2017 (BMJ Open Sport Exerc Med 3:e000093; 17 studies, n = 568 marathon and
ultramarathon runners): creatinine was about 29 µmol/L higher just after the race and, where it was
measured again, back to its earlier level within 48 hours. KDIGO 2024 (Kidney Int 105(4S):S117)
names a cooked meat meal within 12 hours (about 20 µmol/L, highest 2–4 hours after) and muscle
mass among the things that move creatinine without a change in filtration. A row of the workouts of
the days before a draw would suggest a link the studies do not show at that time scale, and no
source is cited for exercise and urea.

## Not yet

Not built: candidates that are not in the catalogue, each with what blocks it and its source where
one is cited. Where none is, the paragraph says so.

**Sleep and blood pressure.** Covassin 2021 (Hypertension 78:859; 20 healthy young adults, nine
nights of 4 against 9 hours in bed on a ward, food held fixed): 24-hour systolic pressure was 3.1
mmHg higher on day 12, but the rise was in 24-hour and sleep-time pressure; pressure while awake,
which is what a home cuff reads, did not differ. Hu 2020 (West J Nurs Res 42:567; 6 trials that
shortened healthy adults' sleep) found no difference (systolic +1.0 mmHg, −2.3 to 4.2). What
positive findings there are come from lengthening sleep in small, mixed studies (Mostafa 2026, Eur
Heart J Open; 7 studies, 4 of them randomised). No source compares months of sleep with months of
cuff readings, and the sheet must never mention pressure at night or breathing during sleep.

**Steps and pulse.** Lee 2021: walking programmes went with a heart rate 2.76 bpm lower (−4.57 to
−0.95; 26 trials, n = 1,747), low certainty. The app's pulse is the mean of whatever readings were
taken, not a resting pulse, and its own threshold of 5 bpm is larger than the trial figure. Blocked
until a reading can be told to be at rest.

**Aerobic minutes and waist.** Jayedi 2024 (JAMA Netw Open 7:e2452185; 116 trials, n = 6,880): in
adults carrying extra weight, each further 30 minutes a week of supervised aerobic exercise for
eight weeks or more went with a waist 0.56 cm smaller (62 trials, high certainty). Blocked by the
workouts: a session's minutes do not confirm its intensity, sessions from a routine or an import
can be typed strength by default, and plans must be left out. It also needs its own waist gate (at
least two measurements two weeks apart in each stretch) and threshold, since under about 1 cm is
tape error.

**Workout minutes and blood pressure.** Cornelissen 2013 (J Am Heart Assoc 2:e004473; 93 trials, n
= 5,223): endurance training went with pressure 3.5/2.5 mmHg lower, but in groups whose pressure was
normal systolic did not differ (−0.75 mmHg, −2.2 to +0.69) and diastolic was about 1 mmHg
lower. That is below the app's own threshold outside raised pressure, and it needs the same filter
for completed aerobic sessions; steps_pressure covers the ground with data the app already gates.

**Fibre and stools.** van der Schoot 2022 (Am J Clin Nutr 116:953; 16 trials, n = 1,251): in adults
with long-standing constipation, fibre supplements went with more frequent stools (SMD 0.72),
mainly psyllium above 10 g a day for four weeks or more, and with less straining in the four trials
that scored it. The trials tested supplements, not fibre in food; the app's fibre is often blank;
and a day without a tap is not a day without a stool, which breaks the rule that a missing day is
never a zero.

**Fibre and HbA1c.** Reynolds 2019 (Lancet 393:434): in six trials of people without a long-term
illness, more fibre in food left HbA1c not clearly different (SMD −0.35, −0.73 to 0.03; low
certainty). A null from six small trials, and HbA1c is beside no diary figure (see "Never
printed"). `LabFoodWindow` already shows fibre before a draw as context.

**RMSSD and sleep or feeling wound up.** Zhang 2025 (Front Neurol 16:1556784; 11 trials, n = 549):
after a night or more without sleep, RMSSD was slightly lower (SMD −0.24, −0.47 to −0.00). That is
acute deprivation, not months, and there is no pooled figure for tension. The app's RMSSD is an
occasional chest-strap reading with no posture, time of day, breathing or length recorded, and
those move it more than the effect does.

**Potassium and blood pressure.** No source is cited. The app's potassium is worked out only for
meals built from catalogue foods, so a typed or label-read meal carries none; a figure would need a
gate like sugar's, and it must never be set beside potassium in the blood.

**Stopping smoking and weight.** No source is cited. A study would follow people who stopped; the
diary shows fewer cigarette equivalents logged, and a gap in the log confirms neither stopping nor
its date, nor says whether nicotine was replaced.

**Caffeine and blood pressure.** No source is cited. The app does not record when a cup was drunk
relative to a reading, and a day's milligrams set against months of cuff readings lose the order of
the two.

**Alcohol and pulse.** No source is cited. The app's pulse is not a resting pulse (see steps and
pulse), and a source's people and amounts would have to be read against what a diary holds.

**Steps and sleep.** No source is cited. The app records how long a night was, not how well it
was slept, and the phone's estimate of a night measures a phone left alone.

**Steps and pain.** In-app only. The Pain screen's "Movement before pain started" card sets the
mean steps of the three days before each pain start against the answered days of the same calendar
months (a case-crossover: Maclure 1991, Am J Epidemiol 133:144; the month as the referent, Dixon 2019),
from 5 starts and 15 control days, with both ends of the person's own range, since too little and too
much movement have each gone with back pain in other people (Heneweer 2009, Pain 143:21). A months-level
pair on the sheet is confounded twice over: a pain lowers walking on the days it hurts, and a stretch
of new training raises both the steps and soreness after training; the soreness rule removes only the
episodes whose timing fits it. Same-day steps are never read beside pain.

**Waking spread and mornings.** In-app only, as the Intimate screen's "Waking time and
mornings" card: mornings more than 70 minutes from the median of the previous 14 wakings against
those within 20, from 15 mornings a group. The one rationale is physiological — REM is most plentiful
near a night that ends at the usual waking time (Dijk & Czeisler 1995; 8 men) — and a 2022 review says
the effect of circadian misalignment still needs study (Liu & Reddy 2022, Rev Endocr Metab Disord
23:1323). There is no direct human evidence, and a sheet pair would need one source of waking times
per comparison, where the sleep section's spread mixes the sleep record with typed times.

**Evening alcohol and mornings.** In-app only by design, as the Intimate screen's "Evening alcohol and
mornings" card (20 g or more against none, on days kept by hand, from 15 mornings a group). The sheet
already has alcohol_partnered, and per-morning sexual detail beside the drinks would add disclosure.
Alcohol delays the first REM period at every dose and cuts total REM at moderate and high doses
(Ebrahim 2013); no study of evening drinking and next-morning erections is cited.

**Air pressure and head pain.** Not a figure the sheet prints: the barometer's pressure is on the
Mood screen's weather card only (see "Environment"), and a signal the sheet does not print cannot be
paired. In a smartphone study of 2,658 people, only a minority showed weather-pain links, and the
direction differed between people (Dixon 2019, NPJ Digit Med 2:105; Yimer 2022, Pain Rep 7:e963). The
app's own weather card rule (10 days, |r| ≥ 0.3) must not be reused for pain: a correlation that size
arises by chance in about four 10-day samples in ten. Blood pressure and head pain: no source is
cited, and a pair would read as a warning about pressure, which the sheet never gives.

**The device behind each day's steps.** Not a pair but what several would need. The report is
handed each day's largest count, whoever wrote it, so the steps row cannot say that a change of
phone or watch fell between the stretches, as the other rows do (see "What the app does not
record"). It needs the source kept with each day's figure.

## Adding a comparison

1. **Catalogue entry.** Add a `ComparisonPair` (or `LabComparison`) with a new lowercase `key`, the
   exposure and outcome `ChangeDomain` (or the markers), and its three strings. A new figure also
   needs a `ChangeDomain` with the section it is read from, an entry in `FIGURES`, a gate, a
   threshold labelled as the app's own, and its KDoc.
2. **This file.** Add a `## <key>` section: exposure, outcome, window, gates and thresholds, the
   sentence, why the two can move together with sources you have checked, confounders, and what
   the app does not record. A pair that must never be printed goes under "Never printed" instead,
   and one that waits on data or a source under "Not yet".
3. **Strings.** The sentence (`report_changes_pair_<key>`) and the background
   (`report_changes_background_<key>`) in `values/ui.xml`, then in all 16 locales. The citation
   (`report_changes_source_<key>`) is `translatable="false"`: author, year, journal, n. Run the
   forbidden-word list over the English first. A finding about men alone (erections, for example)
   goes in `report_changes_background_<key>_male` and `report_changes_source_<key>_male`. It is
   printed on a male profile's sheet only, and the plain background must stand without it.
4. **Tests.** `ComparisonCatalogTest` fails until the doc section exists. In `ReportChangesTest`,
   add a fixture that moves the new figure past its threshold; the every-pair test then prints the
   sentence, and the below-threshold and forbidden-word tests cover it. A sensitive figure needs its
   gating test.
5. **Changelog.** A `###` subsection under the current version, as for every change.
