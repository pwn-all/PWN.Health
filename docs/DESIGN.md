# PWN.Health — design and implementation

> How the app is built, and why. It targets **API 37 (Android 17)** and runs on Android 13 (API 33)
> and later. The status table below is the index. Building, signing and releasing are in
> [BUILDING.md](BUILDING.md); the licence is in the [README](../README.md#licence).

## TL;DR
- AOSP platform APIs only: `Sensor.TYPE_STEP_COUNTER` with reboot-safe delta accumulation for steps, `LocationManager` with the platform `FUSED_PROVIDER` (API 31+) for routes, Room for storage, and `androidx.health.connect` for Health Connect — none of which needs Google Play Services. What bears on an F-Droid submission — a prebuilt ONNX Runtime and model weights trained on data under its own terms — is in §13.
- Double-counting is a first-class problem: step-detector events are never summed; deltas come from the cumulative counter with reboot detection; one listener, owned by the foreground service, credits the day; Health Connect gets one `StepsRecord` per day with a stable `clientRecordId`, and totals are read with `aggregate()` and a `DataOrigin` filter, so neither another source's data nor the app's own is counted twice.
- Health Connect is optional (Apache-2.0, part of AOSP since Android 14): the app asks `getSdkStatus()` and works fully without it, because de-Googled ROMs may not have the provider.

## Key Findings
1. **Steps come from the counter, not the detector**: `TYPE_STEP_COUNTER` (cumulative since boot) plus delta computation is the standard anti-double-count approach; `TYPE_STEP_DETECTOR` fires per-step events and is trivially double-counted by multiple listeners.
2. **A GMS-free fused provider exists**: `LocationManager.FUSED_PROVIDER` ("fused") is a platform constant added in API 31 (Android 12), available without Play Services. The Android Open Source Project ("Get coarse location") states verbatim: "In API 31, the FUSED_PROVIDER, previously accessible only through the GPS API, is now available as a location provider to LM." `GPS_PROVIDER` is the fallback where it is absent.
3. **Health Connect avoids double-counting on read only through `aggregate()`** with priority lists and `DataOrigin` filters; raw `readRecords()` sums overlapping sources. It also returns nothing at all for a writer absent from that priority list — see §3.4.
4. **Overlapping same-origin StepsRecords are illegal**: the official docs say records "shouldn't have overlapping time"; overlaps are "a common cause of write failures." The app writes one record per local day and upserts it through `clientRecordId`/`clientRecordVersion` (§3.2).
5. **Android 15/16 FGS**: `location` and `health` foreground service types are NOT subject to the 6-hour `dataSync`/`mediaProcessing` timeout. Android 15's `BOOT_COMPLETED` restriction is a named list — `dataSync`, `camera`, `mediaPlayback`, `mediaProjection`, `phoneCall`, `microphone` — and `health` is not on it, so the pedometer is started at boot (§2.2). Android 16 tightens JobScheduler quotas and enforces edge-to-edge and predictive back (§10).
6. **No `INTERNET` permission**: the manifest declares none, and `NoNetworkPermissionTest` holds the merged manifest to that (§7.1). Trail Sense and Gadgetbridge ship the same way.

## Implementation status

The app, area by area: the state of each part, the files that hold it, the tests that hold it where
the row names them, and the section that explains it.

| Area | State | Where | Section |
|---|---|---|---|
| Reboot-safe, rate-limited step bouts | **Shipped**; JVM tests in `StepAccumulatorTest` | `StepAccumulator.kt` | §2 |
| Motion gate — anti-false-count regimes | **Shipped**; JVM tests in `MotionGateTest` | `MotionGate.kt` | §14 |
| Live sensor / gate debug view, with a trace recorder | **Shipped**; seven taps on the version line in About the app | `SensorScreen.kt` | §15 |
| Foreground service, `health` + `location` | **Shipped** | `StepService.kt` | §6, §8 |
| Room persistence (days, accumulator, tracks, points) | **Shipped** | `Data.kt` | §5 |
| Dashboard — greeting, a pager of the day's cards (steps, food, energy burned), notices behind a bell, chat button | **Shipped** | `HomeScreen.kt`, `HomeCards.kt`, `HomeHeader.kt` | §16 |
| Onboarding — profile, allergies, optional permissions and animated start | **Shipped** | `OnboardingScreen.kt` | §21 |
| Food / activity / health event journal (weight, height, heart rate, sleep) | **Shipped** | `EntryEditorScreen.kt` | — |
| Offline food recognition from a photo — one ONNX classifier (`photo_v3.onnx`) answering both the dish and the plain foods on it → templates → editable products; nutrition-label OCR off a packet (PP-OCR on ONNX Runtime, rotation ladder) → meal form on the label's own basis | **Shipped**; JVM tests in `FoodRecognitionTest`, `MealPhotoTest`, `NutritionLabelTest`, `OnnxOcrTest` | `FoodRecognizer.kt`, `MealPhoto.kt`, `LabelReader.kt`, `NutritionLabel.kt` | §19 |
| Decimal weight wheels + 24-hour sleep start/end wheels; every time on every screen is written on the 24-hour clock, whatever the phone is set to (`clockText`, held by `ClockStyleTest`) | **Shipped** | `EntryEditorScreen.kt`, `WheelPicker.kt` | — |
| Optional local sleep estimate from Android Usage Access | **Shipped** (off by default) | `SleepDetection.kt`, `ProfileScreen.kt` | — |
| Screen time — today's and the week's time in a fixed list of feed, video, messenger and game apps from the same Usage Access, every other app left nameless as the log is read; eye and head reminders checked every fifteen minutes while switched on | **Shipped**; JVM tests in `ScreenTimeTest` | `ScreenTime.kt`, `ScreenTimeScreen.kt` | — |
| BMI, settings, update log, JSON export / import; a manual export can be sealed under a password (PBKDF2, AES-256-GCM) | **Shipped** | `ProfileScreen.kt`, `Backup.kt`, `BackupCrypto.kt` | `docs/SECURITY_MODEL.md` |
| Monthly calendar + step / calorie charts | **Shipped** | `CalendarScreen.kt` | — |
| GPS route history + correlation filter + full telemetry | **Shipped** (drawn over the offline vector map) | `TrackScreen.kt`, `RouteMotion.kt`, `RouteCorrelation.kt` | §4, §17 |
| Health Connect two-way sync (body, heart rate, sleep, workouts, water, meals, blood pressure, SpO2, temperature, glucose, body fat, the cycle, sexual activity, mindfulness, planned sessions) + guarded steps + reinstall restore | **Shipped** (optional) | `HealthSync.kt` | §3 |
| Training history imported from Hevy, Strong, FitNotes and Lyfta exports (+ wger body weight), one reader over four Strong variants; health exports of twelve more services — Apple Health, Fitbit / Google Takeout, Samsung Health, Garmin, Withings, Oura, Strava, Nike Run Club, Flo, MyFitnessPal, Cronometer and the Google Fit daily table — read from a cache copy of the picked file: the archive is walked as a stream, and an entry that has to be read whole is refused past 96 MB | **Shipped**, fixture tests per reader | `WorkoutImport.kt`, `HealthFileImport.kt`, `HealthArchive.kt`, `HealthExportReaders.kt`, `WearableExportReaders.kt`, `AppleHealthImport.kt` | — |
| Direct BLE SIG profiles + Concept2 PM5 + Xiaomi/COLMI + Bangle.js 2 (PWN.Health watch app) runtime | **Implemented** | `DirectBluetooth.kt`, `BangleJsSyncSession.kt` | `docs/integrations.md` |
| Sixteen languages, whole catalogue + Android per-app language settings | **Shipped** | `res/values*/*.xml` | Languages |
| GPX route export | **Shipped** | `Gpx.kt` | §9 |
| GeoJSON / CSV route export | **Shipped** | `RouteExport.kt` | §9 |
| Walk page — a random name from the start of each recording, photographs taken during it (kept with position, compass bearing, tilt and roll) marked on its map, smooth elevation / speed charts, share as GPX, GeoJSON or CSV; the photographs travel in backups and device transfer | **Shipped** | `WalkScreen.kt`, `WalkPhotos.kt`, `WalkChart.kt`, `Walks.kt`, `RouteFormat.kt`, `TrackScreen.kt`, `Backup.kt` | — |
| Reminders (goal reached, long inactivity, evening log) | **Shipped** | `Reminders.kt` | — |
| Medicine tab — courses with clock or on-waking doses, food-relative reminders, two-week adherence chart, appointments, age/sex routine check-ups | **Shipped**; JVM tests in `MedicineTest`, `MedicineAlarmsTest`, `MedicineSchedulesEdgeCases*Test`, `MedicinePhotoTest` | `Medicine.kt`, `MedicineScreen.kt` | — |
| Workout templates + 56-exercise catalogue (54 offered in the pickers) with worked-muscle diagram and wordless how-to panels | **Shipped** | `RoutinesScreen.kt`, `MuscleMap.kt` | — |
| Live set-by-set session: plan, run, rest, summary | **Shipped** | `WorkoutSession.kt` | — |
| Supersets — a paired exercise run with no rest between the halves, saved as two linked rows | **Shipped**; JVM tests in `SupersetPlanTest` | `WorkoutSession.kt`, `EntryEditorScreen.kt` | — |
| RIR / RPE per set, asked on the rest card, taught in place | **Shipped** | `WorkoutSession.kt` | — |
| Home-screen widgets (Glance) — steps, water, mood, quick add, one to six configurable shortcuts, route start and stop, one-tap cigarette, work timer | **Shipped** | `Widgets.kt`, `MultiWidget.kt`, `WorkWidget.kt` | — |
| Seven more widgets — last night's sleep, latest weight and its 30-day change, today's energy against the allowance, the week's steps as bars, level with XP and streak, the next dose with a Taken button, the cycle day and phase | **Shipped**; JVM tests in `FigureWidgetsTest` | `FigureWidgets.kt`, `DoseWidgets.kt` | — |
| Water, mood, breathing, cycle, achievements | **Shipped** | `Caffeine.kt`, `Mood.kt`, `Breathing.kt`, `Cycle.kt`, `Achievements.kt` | — |
| Annual levels and XP, daily opening streak, backup of opening history | **Shipped** | `Levels.kt`, `LevelsUi.kt`; [rules](levels.md) | — |
| Alcohol, tobacco, bathroom and intimate logging, each behind its own door | **Shipped** | `Habits.kt`, `BathroomScreen.kt`, `IntimateScreen.kt`, `Sexual.kt` | §20 |
| Evidence-based advice (sleep, activity, mood, cycle, energy goal, hydration, bowel, tobacco, sexual) | **Shipped** | `HealthAdvice.kt`, `BodyAdvice.kt` | §20 |
| Daily nutrition feedback by goal, intake share, time of day and recorded macros | **Shipped** | `NutritionAdvice.kt`; [rules](nutrition-advice.md) | — |
| Barometric altitude, elevation gain | **Shipped** | `StepService.kt`, `RouteMotion.kt` | §4.2 |
| Floors climbed all day, gait-gated against lifts and weather drift | **Shipped**; JVM tests in `BarometerTest` | `Barometer.kt`, `MotionGate.kt` | §4.2 |
| Air pressure against self-reported mood, over four months, through the same engine and gates as the other measured links | **Shipped**; JVM tests in `PressureMoodTest` | `Mood.kt`, `MoodScreen.kt`, `Insights.kt` | — |
| Jump height from accelerometer flight time | **Shipped**; JVM tests in `RepCountingTest` | `RepCounting.kt` | — |
| Offline map under the route, in dots and tilted — rivers, lakes, relief, forests, towns; turns with the compass; no tiles and no network | **Shipped** | `WorldMap.kt`, `MapDots.kt`, `Map3D.kt`, `assets/world.{map,relief,forest,towns}` (29.97 MB: Natural Earth 1:10m, ETOPO 2022, ESA WorldCover 2021, GeoNames) | §17 |
| Douglas–Peucker simplification (on export) | **Shipped** | `RouteSimplify.kt` | §4.3 |
| Lab results — panels typed in or read off photographs and PDFs, units, reference ranges, charts | **Shipped** | `Labs.kt`, `LabsScreen.kt`, `LabImport.kt`, `LabDocumentReader.kt`, `LabSeriesChart.kt` | `docs/LAB_IMPORT.md` |
| Report for a doctor — rendered to PDF, shared alone or in an archive with the original files | **Shipped** | `DoctorReport*.kt`, `Report*.kt` | `docs/medical-export/`, `docs/REPORT_COMPARISONS.md` |
| Health chat — rules, plus an ONNX reader that runs on the device | **Shipped** | `SymptomChat*.kt`, `Chat*.kt`, `assets/chat/` | `docs/MODELS.md` |
| Pain log — painted on a body map, with history and statistics | **Shipped** | `Pain*.kt` | — |
| Medical history | **Shipped** | `MedicalHistory.kt` | — |
| Database encryption at rest (SQLCipher) and an app lock on the device credential | **Shipped** (both optional) | `DatabaseEncryption.kt`, `AppLock.kt` | `docs/SECURITY_MODEL.md` |
| Daily plain backup into a folder of the person's choosing, the last seven kept | **Shipped** (optional) | `AutoBackup.kt` | — |
| Large-screen adaptive layout | **Not built**: one pane at every width | — | §10 |
| Driving the emulator's virtual sensors | How-to | — | §18 |

Version **2.0** (versionCode 79). The chat reader that ships is named by
`app/src/main/assets/chat/reader-manifest.json`, every network in the assets by
[docs/MODELS.md](MODELS.md), and the photo model by §19. This repository carries the app and
the models it ships; the code that trains them is not part of it.

```
compileSdk 37.1 (Android 17), targetSdkVersion=37, minSdk=33
ABIs: arm64-v8a and armeabi-v7a. The sideload build is one APK per ABI;
             the play bundle is split per device by Google Play
release: R8 + resource shrinking; no dependency block in the APK's signing block
foregroundServiceType=0x108  (health|location)
permissions: ACTIVITY_RECOGNITION, ACCESS_FINE/COARSE_LOCATION,
             BLUETOOTH_SCAN, BLUETOOTH_CONNECT, USE_BIOMETRIC,
             FOREGROUND_SERVICE{,_HEALTH,_LOCATION}, POST_NOTIFICATIONS,
             SCHEDULE_EXACT_ALARM, USE_FULL_SCREEN_INTENT, WAKE_LOCK,
             RECEIVE_BOOT_COMPLETED, PACKAGE_USAGE_STATS (Usage Access),
             54 android.permission.health.* — 33 record types, of which 26 are
             read and write, five (TOTAL_CALORIES_BURNED, SPEED, DISTANCE,
             ELEVATION_GAINED, POWER) write only and two (RESTING_HEART_RATE,
             HEART_RATE_VARIABILITY) read only,
             plus READ_HEALTH_DATA_HISTORY. Fewer strings than types because
             some types share one permission — the menstruation records among
             them. HEALTH_PERMISSIONS in HealthSync.kt is the list;
             HealthPermissionManifestTest holds the manifest to it in both
             directions, because an undeclared permission can never be granted
             and a declared one nothing asks for is what Play rejects
             — and no android.permission.INTERNET; the sideload build adds
             ACCESS_BACKGROUND_LOCATION (§7.1)
the largest assets: chat/reader.onnx 66.4 MB, the four OCR models and their
             dictionaries in assets/ocr 39.0 MB, photo_v3.onnx 28.3 MB, the world
             map's four files 29.97 MB. The OCR models are compressed in the
             APK; the rest are stored whole, so they can be mapped or seeked.
             AssetsBudgetTest holds the assets under 185 MB, WorldReliefTest the
             map under 30 MB
native code per ABI: libonnxruntime.so 14.1 MB on arm64 (9.0 MB on 32-bit),
             libsqlcipher.so about 2.0 MB (1.0 MB)
JVM unit tests: app/src/test, run by testSideloadDebugUnitTest. The count
             is not kept here; the test report under app/build/reports/tests
             has it
instrumentation tests besides those, in app/src/androidTest: what only a
             device has — the migrations step by step on Android's own SQLite
             (MigrationTest), the database encryption with SQLCipher and the
             Keystore, the shipped models, a reminder through the system — run
             on an attached device through connectedSideloadDebugAndroidTest.
             check compiles them and does not run them; the release checklist
             asks for the run. What needs no device runs in the unit tests on
             Robolectric: every exported schema version opened through the
             migrations (MigrationRunTest), Room's transactions, a composed
             screen's semantics
```

### Languages

Sixteen: English (default), Arabic, Chinese, Czech, French, German, Hindi, Indonesian, Italian,
Japanese, Korean, Polish, Portuguese, Russian, Spanish, Turkish. The whole catalogue is
translated — every string and plural set in `values/` but the strings marked
`translatable="false"` — not a UI subset, because the strings a person most needs to
understand are the health ones. AGP generates the locale config from the `values-*` folders, so
Android 13+ offers this app its own language independently of the phone's, and RTL is on for
Arabic. What a translation lacks falls back to English per string. The food catalogue is not part
of it: a product has an English name and, for about half of them, a Russian one (§19), so a phone
in any of the other fourteen languages lists its foods in English.

What cannot be checked by eye is checked by Android Lint, which the build runs with every check on
and warnings as errors: identical key sets, format placeholders preserved through reordering
(`%1$s` may move but may not become `%1$d`), and the plural categories CLDR gives each language —
six in Arabic including the dual, four in Czech and Polish, one in Japanese, Korean, Chinese and
Indonesian. Two resource files, `redesign.xml` and `workout_flow.xml`, opt out of the missing-key
check. `StringNamesTest` and `SymptomChatStringsTest` add what Lint does not see.

## Details

### 1. Architecture and module structure

One `:app` module and one package, `com.pwnall.health`: Jetpack Compose with Material 3 for the
screens, Room with KSP for storage, Kotlin coroutines and Flow, WorkManager for deferrable work and
Glance for the widgets. There are no ViewModels, no DataStore, no navigation library and no
dependency-injection framework; §1.1 says what stands in their place and why.

#### 1.1 One module, and what keeps the core testable

Over 300 Kotlin files under `main`, and more under `test`; `find app/src -name '*.kt' | wc -l`
counts them. A module boundary with no second consumer to name is a build-time cost with no
payoff, so the app is not split:

```
app/src/main/java/com/pwnall/health/
  StepAccumulator.kt   pure: cumulative counter -> non-double-counted delta      (§2)
  MotionGate.kt        pure: accelerometer window -> motion regime + gate verdict (§14)
                       plus SensorFeed, the in-memory live view the dev screen reads
  Data.kt              Room entities, DAO, database, and the transactional writeSteps()
  StepService.kt       the only writer of the day's steps; accelerometer, pedometer, location
  MainActivity.kt      the activity, screen switching and the bottom bar
  SensorScreen.kt      live sensor + gate debug view                             (§15)
  TrackScreen.kt       route recording and the map under it                      (§17)
  OnboardingScreen.kt  first-run profile and permission flow                     (§21)
  WheelPicker.kt       snapping vertical wheel used by onboarding and settings
  HomeScreen.kt        the greeting, the pager of the day's cards, the notices   (§16)
  EntryEditorScreen.kt food macros and MET-based activity records
  CalendarScreen.kt    monthly history and charts
  MedicineScreen.kt    doses due today, adherence chart, appointments and check-ups
  Medicine.kt          dose schedules, food-relative nudges, screening intervals
  HealthSync.kt        bidirectional Health Connect sync; DevicesScreen.kt is the page that reports on it
  DirectBluetooth.kt   BLE scan, GATT and the Devices-page section for every direct device kind
  SleepDetection.kt    optional local night-gap estimate from Android Usage Access
  RouteMotion.kt       GPS speed + periodic gait movement classifier
  RouteCorrelation.kt  prediction/accuracy filter with one-fix confirmation
  IntegrationCatalog.kt Weight-scale adapters: the SIG WSS decoder and two Xiaomi formats
  FitnessMachineIntegration.kt Bluetooth SIG FTMS bike/treadmill/rower decoder
  ProfileScreen.kt     BMI, settings and backup controls
  Backup.kt            local JSON export/import
  HealthMath.kt        BMI, Mifflin–St Jeor, macro and MET calculations
  … and the rest, in the same directory
app/src/test/java/com/pwnall/health/
  StepAccumulatorTest.kt
  MotionGateTest.kt
  HealthMathTest.kt
  IntegrationCatalogTest.kt
  FitnessMachineIntegrationTest.kt
  SleepDetectionTest.kt
  RouteMotionTest.kt
  RouteCorrelationTest.kt
  … and the rest of the test files
```

What a pure `:domain` module would buy — the double-count logic and the motion classification
tested on the JVM with no Android on the classpath — holds already: `StepAccumulator.kt` and
`MotionGate.kt` import nothing from `android.*` or `androidx.*`, and call nothing declared in a file
that does; `DocsMapTest` holds both to that. That is all that is promised: the other files in the
package call one another freely, so a real module would first have to bring the helpers those two
call across with it.

No DI framework either. The database is reached through `StepsDb.get(context)`, a `@Volatile`
singleton that also remembers the `Application` its after-write hooks redraw the widgets and re-arm
the medicine alarms with; the other process-wide pieces — the Health Connect exchange, the app
lock, the alarms — are top-level objects holding their own state.

### 2. Step counting without double-counting

#### 2.1 Counter vs detector
- `Sensor.TYPE_STEP_COUNTER`: returns a **monotonically increasing total number of steps since the last reboot** while the sensor is active. Steps in a window are `current - baseline`. This is inherently idempotent — re-reading the same counter value yields the same delta, so it cannot double-count within a boot session.
- `Sensor.TYPE_STEP_DETECTOR`: emits one event per detected step. If two components register listeners, each step is delivered to both → immediate double count. It is only useful for latency-sensitive "step happened now" UI.

**The phone's own steps come from `TYPE_STEP_COUNTER` alone; the app does not listen to the detector.**

#### 2.2 Reboot detection and the delta accumulator
The counter restarts at zero on reboot. Each `SensorEvent` carries `timestamp` in **nanoseconds since boot**, and the wall-clock time of the boot, `System.currentTimeMillis() - SystemClock.elapsedRealtime()`, is the boot id: roughly constant within a boot, different after a restart.

`StepAccumulator.accumulate` takes the persisted `StepState` and a reading — the raw counter, the boot id, the event time and the gate's verdict (§14) — and returns the delta to credit and the next state. How it differs from the textbook accumulator, and why:

1. **State carries no totals.** There is no baseline and no running total: the accumulator
   returns a *delta* and the caller adds it to the current day's row, so the day bucket already is
   the window and there is nothing to baseline against. What is persisted, beside the boot id and
   the last counter value, is the evidence that has to survive a restart: the last event time, the
   pending bout and the rate-limiter window (`StepState`).
2. **A reboot sets a new baseline and credits nothing.** The first reading after a boot change is
   adopted as the baseline (`REBOOT_BASELINE`): the since-boot value cannot be checked against any
   gait evidence, and crediting it whole would put a large unverified burst on the current day
   (§14.5). Steps taken between the restart and that first reading are therefore not counted.
   That is what `BootReceiver` is for. It starts the pedometer at boot, and after an update of the
   app (`MY_PACKAGE_REPLACED`, which ends the process without `START_STICKY` bringing the service
   back), so the baseline is taken then and not whenever the app is next opened, and the steps
   after it are credited as they happen, to the day they were taken on. Without it a phone
   restarted at seven and opened in the evening would have nothing for the day up to that evening.
   Starting there is also what the restriction permits: `health` is not one of the types Android 15
   refuses to start from `BOOT_COMPLETED` (§6). A recording is deliberately *not* resumed there —
   see §6 for why.
3. **A counter that goes backwards *without* a boot change credits nothing.** Treating any decrease
   as a reboot and then crediting `rawCounter` would inject the whole counter value on a sensor
   glitch. A reboot is detected by the boot-id jump; a bare decrease is a glitch, and a glitch is
   worth zero steps.
4. **Boot ids are compared with a tolerance** (60 s) rather than quantised. Rounding to a bucket
   makes drift across a bucket edge look like a reboot; a tolerance comparison does not.
5. **State is written on every sample only while the screen is on.** With the screen off, each
   sample is still decided as it arrives, but the state and the steps it credited are written
   together once a minute, a few seconds after the screen comes on, and when the service stops. A
   process killed in between loses the decisions, not the steps: the older state on disk makes the
   next reading's delta carry them, to be judged afresh. A transaction per sample would be some
   1 800 an hour of walking, for a count nobody can see.

#### 2.3 One listener, one owner
Two `SensorEventListener`s on `TYPE_STEP_COUNTER` in the same process each receive the same cumulative value, so a design in which each listener adds its own delta double-counts. `StepService` is the only writer of the day's steps: it registers the counter once and funnels every event through the process's one `StepLedger` (held by `Pedometer` in `StepService.kt`), under a lock, so the writes land in the order the decisions were made. The screens observe the database.

A workout set (`SetTracker` in `ExerciseTracking.kt`) listens to the same counter for its own
count, from its own first reading, and writes nothing to the day.

#### 2.4 Foreground service restart / re-registration
Because the accumulator keys off the **persisted last counter value**, re-registration after a service restart re-reads the counter and computes `current - lastCumulative` — which is ~0 if nothing happened in between. The baseline is never re-read from scratch, so restarts don't re-add steps. The state and the steps it credited are written in one transaction (`writeSteps` in `Data.kt`), so a torn write cannot cause a recount.

#### 2.5 Never sum pedometer AND GPS-derived steps
Deriving steps from GPS distance (distance ÷ stride) in parallel with the pedometer and summing them is a guaranteed double count. The pedometer is the source of truth for steps; GPS is used only for distance and the route.

#### 2.6 Where the hardware counter is not enough

`TYPE_STEP_COUNTER` solves *double*-counting. It does not solve *false* counting, and the two are
different problems. The hardware pedometer is a filter tuned to be generous — OEMs would rather
over-report than have a user complain their walk did not register — so it will happily emit steps
when the phone is:

- riding in a car, a bus, a train or the metro (engine, rail joints, road texture);
- sitting on a passenger seat or in a cupholder while the vehicle moves;
- being shaken, deliberately or in a bag;
- being handled while the user stands still — scrolling, gesturing, fishing it out of a pocket.

That is what §14 exists for.

### 3. Health Connect: read, write, and dedup

#### 3.1 Availability and graceful degradation
```kotlin
when (HealthConnectClient.getSdkStatus(context)) {
    HealthConnectClient.SDK_UNAVAILABLE -> { /* the Health Connect card says so; nothing else changes */ }
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> { /* offer the update */ }
    HealthConnectClient.SDK_AVAILABLE -> { /* proceed */ }
}
```
On Android 14+ Health Connect is a system module, present by default. On Android 13 it is an installable app from Google Play, and one too old for this client is a tap away from working: the app offers the update rather than declaring the provider missing (`syncUnavailableText`). **On de-Googled ROMs (GrapheneOS/CalyxOS/LineageOS) the provider may be entirely absent** — `SDK_UNAVAILABLE` is a normal state, and the app is fully functional, local-only, without it. The manifest's `<queries>` names `com.google.android.apps.healthdata` so `getSdkStatus` can resolve the Android 13 app, and the two permission-rationale actions so the data sources screen can name the app behind an imported row.

#### 3.2 Write-side idempotency with clientRecordId
Every record the app writes carries a stable `clientRecordId` it owns and a `clientRecordVersion`. Health Connect treats `insertRecords` as an **upsert**: if a record with that `clientRecordId` already exists and the incoming `clientRecordVersion` is higher, it overwrites; otherwise it inserts new. Retries and re-syncs therefore update rather than duplicate.

```kotlin
// syncStepDays in HealthSync.kt: one record per local day
StepsRecord(
    startTime = start, startZoneOffset = zone.rules.getOffset(start),
    endTime = end,     endZoneOffset = zone.rules.getOffset(end),   // the exchange's moment, for today
    count = steps,
    metadata = Metadata.autoRecorded(
        device = device,                                // TYPE_PHONE, with make and model
        clientRecordId = STEP_CLIENT_ID_PREFIX + day,   // "steps-local-day:2026-07-29"
        clientRecordVersion = version,                  // publishVersion: never goes back
    ),
)
```

A record built from a journal row is named by its family's prefix, the journal's own tag and the
Room row id (`publishedClientId`, §3.4a). The version is the clock, or one past the last version
written when the clock has since been set back (`recordVersion`): Health Connect answers an insert
of a lower version as a success and keeps the old copy, so an edit published after the clock went
back would vanish without a word.

#### 3.3 Non-overlapping windows — mandatory
Official docs: `StepsRecord` "should not have overlapping time," and overlapping records are "a common cause of write failures." The app's steps are one record per local day, which never overlap one another, and nothing else it writes is a `StepsRecord`: a workout is an `ExerciseSessionRecord` — a container, start, end and type — with its cadence beside it (`StepsCadenceRecord`), not a second count over the same hours. Aggregation counts steps from `StepsRecord`s, so a session over the same interval does not double-count them.

Every write and delete is made per record type (`insertIfAllowed`, `deleteIfAllowed`): a type switched off in Health Connect is skipped and the rest of the export still goes. Records are built through `healthRecords`, which drops a row Health Connect would refuse — a day past the million steps a record may hold — rather than letting it end the exchange.

#### 3.4 Read-side dedup and the feedback loop
- **For totals, `aggregate()`, not `readRecords()`.** The docs are explicit: "For cumulative types like StepsRecord, use `aggregate()` instead of `readRecords()` to avoid double counting from multiple sources." Aggregation honors the user's **app priority list** (Settings → Data sources and priority), keeps only the highest-priority source where sources overlap, and counts only the sources that are on that list at all.

  A writer that holds `WRITE_STEPS` but is not on the list has its records returned in full by `readRecords()`, and an aggregate filtered to its `DataOrigin` returns nothing — for this app as for any other. Health Connect's own screen says it: a source off the list "will stop it from contributing to totals, but it will still have write permissions". Nothing in an app can change that list, so `syncStepDays` in `HealthSync.kt` detects the state (an origin whose per-origin aggregate answered and answered nothing, while its records are in hand) and the Health Connect card names the app and the screen.
- **Feedback loop**: an app that both writes and reads steps re-counts its own data on an unfiltered read. `syncStepDays` reads raw records only to discover the external origins — never its own package — and asks the aggregate for each of them; it never adds raw counts, because several apps can publish the same wearable's steps. A day another source counted higher than the phone is imported, so a watch worn all day wins over a phone left on a desk; a day nobody else reported is published; and a day another source has taken over withdraws the record this app had published for it — never the running day, which is still being counted. When consuming `getChanges`, "only take changes that didn't come from the calling app to make sure you're not re-importing data."

#### 3.4a Read before you write, or a reinstall deletes the history

`HealthSync.kt` — `HealthImport`, `restoreOwnStepDays`.

An app that keeps Health Connect tidy withdraws its own records once the local event behind them is
gone. That housekeeping is correct until the local database is empty for a reason other than
deletion: after a reinstall nothing claims anything, so an export-first sync would delete the
entire history the app had published — the one copy that survived the uninstall.

Two rules follow, and both are cheap:

- **Import first, export last, in every sync pass.** The stale sweep then runs against a journal
  that has already been refilled.
- **Read your own `dataOrigin` too, and adopt what you find.** The usual advice — filter out your
  own package on read (§3.4) — is about not re-counting live data, not about ignoring your own
  history. A row is adopted only when nothing local claims it: no journal entry with the id its
  `clientRecordId` encodes at the same time (`publishedCopyOf`), and none already sitting on that
  instant. An adopted row drops its Health Connect provenance and becomes a local event again, so
  the next export republishes it under a fresh client id and the old copy is swept.

Steps need the same treatment through their own table rather than the journal, and the running day
is the exception: the accumulator owns today, so restoring today's total from a previous install
would count the morning twice.

The rest of the exchange is built round the same danger:

- **It runs itself.** The first launch with a saved profile pulls up to 365 days of history back
  (onboarding has just granted the permissions), and later launches and returns to the foreground
  repeat the exchange no oftener than every five minutes (`AUTO_SYNC_INTERVAL`); one that has not
  ended in ten minutes fails as a Health Connect that did not answer. There is no sync screen: the
  Health Connect card on the Devices page says how the last exchange ended and, after a failure,
  offers "Try again", which does not wait the five minutes out.
- **The quick mirror waits for the restore.** The export that fires after every local edit is held
  back until the first full exchange has run, so onboarding's own first write cannot sweep a nearly
  empty journal against the previous install's records before the restore has read them.
- **An id alone does not name a row.** Room numbers a fresh journal from 1 again, so a previous
  install's first rows share numbers with the new install's: the restore matches on the time as
  well as the id (`publishedCopyOf`), and every client id carries the journal's own tag between the
  prefix and the row id (`journalGeneration`, `publishedClientId`), so the new install never
  publishes over ids the previous install's records hold.
- **One refused type does not abort the pass.** Every read and delete is guarded per type, so a
  partial grant syncs everything that was allowed.

#### 3.5 Incremental sync with change tokens
Changes made by other apps (OEM and wearable data written by Samsung Health, Mi Fitness, Zepp, Fitbit, Gadgetbridge) are read with `getChangesToken` and `getChanges` (`HealthChanges.kt`):
- One token per record type, not one for all of them, so a revoked permission refuses only its own type.
- `getChanges` is followed until it has no more pages, in order — a record upserted and then deleted in the same stretch ends deleted. The Health Connect record `id` is kept on the imported row, because a deletion change carries only the id.
- **Change tokens are only valid for 30 days** (per the Android Developers "Synchronize data" guide and the Health Connect codelab). An expired token — reported by the expiry flag or as an `IllegalArgumentException` — reads the window whole again and keeps a fresh token.
- The tokens are kept in the `health_sync` SharedPreferences, and are retired when the app's version code changes, so that a changed mapping re-reads the year once; the retired token is first asked for the records deleted since, which a whole read cannot see, and goes when the fresh one is kept (`retireChangeTokensIfAppChanged`).

#### 3.6 Permissions

The manifest declares the 54 permissions of `HEALTH_PERMISSIONS` (§7.1): read and write for 26
record types, write only for five whose figures the app derives itself from sources it already
imports (`HEALTH_WRITE_ONLY_TYPES`), read only for resting heart rate and heart-rate variability,
which a watch or ring works out and the app does not, and `READ_HEALTH_DATA_HISTORY` — without
which Health Connect serves another app's records from no earlier than 30 days before this app's
first grant. There is **no historical limit on reading your own app's data**. The request asks only
for the permissions the phone's Health Connect defines (`healthPermissionsKnownTo`): an older
provider asked for a type it has never heard of turns down the whole request.
`READ_HEALTH_DATA_IN_BACKGROUND` is not declared, because the exchange is started only from the
open app (§3.4a).

The **Play Console health-permissions declaration form** is a Google Play distribution requirement; it does not apply to F-Droid or to the sideload APK. The play build's declaration is in `docs/play-release-checklist.md`.

#### 3.7 What an edit and a delete actually do

Corrections are the part of a two-way sync that quietly goes wrong, because nothing complains: the
app shows the new value, Health Connect keeps the old one, and neither side knows they disagree.

**Editing** works because the `clientRecordId` is derived from the journal's tag and the Room
primary key, not from a clock. Saving an existing entry reuses its id, so the export re-inserts the same
`clientRecordId` with a higher `clientRecordVersion` and Health Connect replaces the record instead
of gaining a second one. The save then mirrors immediately for the twelve record families
`wellnessRecords` builds — weight, height, heart rate, sleep, workouts, water, food, blood pressure,
oxygen saturation, temperature, glucose and body fat. The cycle, body, activity and session records
(`exportCycleRecords`, `exportBodyRecords`, `exportActivityRecords`, `exportSessionRecords`) are
assembled from more than one row and wait for the next exchange, which runs at every launch and
return to the foreground, no oftener than every five minutes.

**Deleting** removes the row and leaves a tombstone: `deleteJournalEvent` writes the row's record
key and the time of deletion to `journal_tombstones` (`JournalTombstone`), and the import skips any
record whose key is tombstoned — otherwise a record deleted here while Health Connect still held it
would come back at the next sync. The removal itself reaches Health Connect through the entry's
absence from the next exchange's export, not at the edit: `removeStalePwnFitRecords` reads back
this app's own records for a type, page by page through the whole history, and deletes any whose
`clientRecordId` is no longer wanted — though older than the 365 days a restore reads back, only
what this install published itself, because the journal knows nothing of the rest. A deletion
that quietly did nothing is indistinguishable from one that worked, so the sweep has to see
everything before it can decide.

Three cases resolve in Health Connect's favour rather than the app's, all by design: an entry
imported from another app keeps `measurementSource = "HEALTH_CONNECT"` and is never exported, so
editing it locally changes only this app's copy; deleting such an entry removes nothing remotely,
because it was never this app's record; and the sweep needs read permission for a type to reconcile it,
so a type granted write-only is written but never tidied.

**An editor may not delete a column it does not render.** The entry form covers meals, workouts and
the hand-typed measurements. It has no field for `measurementPayloadJson`, `bodyFatPercent`,
`moodScore` or `breathCycles`, and saving carries those four columns across from the row it
replaces: a form that dropped them would null a blood pressure's or a glucose reading's payload,
the export would then produce no record for it, and the stale sweep would delete the record that
reading had written. A row whose meaning lives in a payload the form cannot show does not open a
form at all (§16.2).

### 4. GMS-free location & track recording

Provider selection, quality filtering, GPS altitude, pressure-calibrated relative altitude,
elevation gain and loss, stops, speed accuracy, movement segmentation and optional
ambient-temperature telemetry are in `StepService.kt`, `RouteCorrelation.kt`, `RouteMotion.kt` and
`RouteRecording.kt`. Douglas–Peucker simplification on export is `RouteSimplify.kt`, and the offline
vector map under the route is §17; there is no tile layer, and §17.3 says why.

#### 4.1 Provider selection
`LocationManagerCompat.requestLocationUpdates` with a `LocationRequestCompat`, on:
1. `LocationManager.FUSED_PROVIDER` ("fused") where the phone has it — platform-level, API 31+, combines sensors without Play Services. Confirmed by AOSP ("Get coarse location"): "In API 31, the FUSED_PROVIDER, previously accessible only through the GPS API, is now available as a location provider to LM."
2. `GPS_PROVIDER` otherwise.

```kotlin
// StepService.kt
LocationRequestCompat.Builder(interval)          // 2 s while recording, 30 s while paused
    .setMinUpdateIntervalMillis(interval / 2)     // fixes other apps cause, up to twice as often
    .setMinUpdateDistanceMeters(0f)               // filter in software, where we can see why
    .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
    .build()
```

A 2 s interval with `minDistance = 0` captures switchbacks, and all noise filtering happens in
software where it can be inspected (§17.2). A paused route keeps its fixes coming at the pause's
slower pace and stores none of them: they are what places a photograph taken during the pause.

#### 4.2 Altitude: WGS84 → MSL and elevation gain
Raw GNSS altitude from `Location.getAltitude()` is **WGS84 ellipsoidal height**, off from mean sea level by a large, location-dependent geoid undulation, and noisy. Per NOAA/geodesy sources the undulation ranges from roughly −106 m (south of India) to about +85 m (near Iceland); Esri (ArcUser, Summer 2003) notes "The maximum range of the Geoid99 undulations with respect to the WGS84 ellipsoid is 192 meters." So:
- **Sea-level altitude where the fix carries one.** From Android 14 the fused provider attaches a mean-sea-level altitude beside the raw one (`Location.hasMslAltitude()`). The recorder takes it where it is there and the raw ellipsoidal height otherwise, records which on every point (`altitudeMsl`), and the GPX names the datum it carries, so a reader never has to guess.
- **Barometer (`Sensor.TYPE_PRESSURE`)** gives far better *relative* elevation changes for cumulative gain (OpenTracks and Trail Sense both use the barometer for altitude). Its altitude is calibrated to the GPS altitude, whichever datum that is.
- **Cumulative gain without noise inflation**: hysteresis against a smoothed altitude. The climb is measured from an exponential mean of the fixes, a fifth of the way at each (`smoothedElevationM`), because against a raw series every wander that clears the step books the step. A change counts as climb or descent only past 1.5 m with a barometer and, without one, past the fix's stated vertical accuracy and never under **3 m** (`elevationStepThresholdM`) — and never while the person stands still (`climbCounts`).

#### 4.3 Track quality
- **Accuracy and age**: a fix with no accuracy, or one worse than 25 m, is rejected, and so is one older than 30 s — a provider's cached last fix (§17.2).
- **Speed sanity**: the correlation filter (§17.2) rejects an implied speed over 70 m/s, and above 12 m/s accepts a jump only with the receiver's Doppler speed behind it.
- **Stationary jitter**: accurate stationary fixes are kept to show stops but add no distance. The distance anchor moves only once a fix clears `max(3 m, accuracy × 0.5)`: the wander of a receiver standing still would otherwise add up to distance.
- **No Kalman filter**: the correlation filter predicts each fix from the last two confirmed ones and holds a contradictory jump for one more fix instead (§17.2).
- **Distance** is equirectangular between confirmed fixes (`equirectangularMetres` in `Geo.kt`, on a 6 371 km sphere) — over the metres between two fixes its error against the great circle is millimetres — and is never computed on raw points.
- **Simplification only on the way out**: raw points stay in the database; **Douglas–Peucker** thins the GPX and GeoJSON exports, each stretch between pauses on its own. Distance and gain are computed before that, never on the simplified line.

### 5. Persistence schema (Room + KSP)

The schema is `StepsDb` in `Data.kt`: 24 tables at version 48, every version exported to
`app/schemas`. `ALL_MIGRATIONS` holds the 46 hand-written migrations; `RoomSchemaTest` reads the
exported schemas, `MigrationRunTest` opens every exported version through the migrations on
Robolectric, and the instrumented `MigrationTest` runs them on a device's own SQLite. The
destructive fallback is `fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)`: version 1
only.

- **Steps**: `days` (one total a day), `hourly_steps` (§16), `external_step_days` and
  `external_step_origin_days` (what Health Connect's sources counted, in total and per app),
  `direct_ble_step_days`, `legacy_step_days` (days whose source cannot be proven safe to re-export)
  and `barometer_days`. The accumulator's `StepState` is the single row of `state`, written in the
  same transaction as the steps it credited (§2.4).
- **The journal**: `journal`, one row per meal, workout, measurement, dose, drink and every other
  logged event, each with a `kind`, and `journal_tombstones` (§3.7).
- **Routes**: `tracks`, `trackpoints` and `track_photos`. A track is open while `endedAt` is null
  (§17.2). A point keeps the raw and the corrected coordinates, one GPS altitude with an
  `altitudeMsl` flag beside it, pressure and barometric altitude, accuracy, speed and its accuracy,
  the movement class and the filter's verdict and reason (§17.2); points are indexed by
  `(trackId, timestamp)`.
- **The rest**: `profile`, `medications`, `appointments`, `routines`, `routine_exercises`,
  `favorite_foods`, `saved_dishes`, `progress_photos`, `medical_history`, `chats` and
  `app_open_days`.

Raw accelerometer samples are never stored — only the derived step deltas and the location fixes.

Android *cloud* backup gets nothing (`dataExtractionRules`): it would upload a health record to Google, which an app with no `INTERNET` permission exists not to do. Device-to-device transfer is a separate section of the same file and is kept: it copies straight from the old phone to the new one during setup, never touches a server, and is the only thing standing between a broken phone and a health record that exists in one place. The rules exclude every domain from `<cloud-backup>` and include the database, the preferences and five private file folders (`progress/`, `medicines/`, `walk-photos/`, `routines/`, `lab-captures/`) in `<device-transfer>`.

### 6. Background execution

`StepService` is a started foreground service, never bound (`onBind` returns null): the screens
observe the database. It is `START_STICKY`, and what a restart needs is in Room — the accumulator's
state and the open track — so a killed process resumes without losing or repeating steps or fixes.
WorkManager runs the deferrable work: the daily backup (`AutoBackup.kt`), the reminders
(`Reminders.kt`), the screen-time check (`ScreenTime.kt`) and the work timer's forgotten-stop check
(`WorkOverrun.kt`). The Health Connect exchange is started from the open app (§3.4a), not from
WorkManager, so `READ_HEALTH_DATA_IN_BACKGROUND` is not declared.

- **Types**: the service declares `health|location` and claims `location` only while a route is recording (§17.1).
- **No timeout**: Android 15/16 `location` and `health` FGS types are **NOT** subject to the 6-hour timeout. The Android Developers "Foreground service timeouts" doc states the system permits `dataSync` and `mediaProcessing` FGS "to run for a total of 6 hours in a 24-hour period, after which the system calls the running service's `Service.onTimeout(int, int)` method," and that "Currently, this restriction only applies to dataSync and mediaProcessing foreground service type foreground services" (exceeding it throws `ForegroundServiceStartNotAllowedException` with "Time limit already exhausted for foreground service type dataSync"). A walk can therefore run for hours under `location`/`health`, with no `onTimeout` handling. `shortService` is capped (~3 min) and is not used.
- **Boot**: Android 15's restriction on starting a foreground service from `BOOT_COMPLETED` names the types it applies to — `dataSync`, `camera`, `mediaPlayback`, `mediaProjection`, `phoneCall` and `microphone`. Neither `health` nor `location` is on that list, and `BootReceiver` starts the `health` service there so the counter's new baseline is taken at boot, and the steps after it are credited as they happen instead of being lost up to the next time somebody opens the app (§2.2). What a boot-started service does *not* get is while-in-use permission: location, camera and
  microphone deliver nothing to a service started from the background. That rule has a named
  exemption list, though, and it matters: a service started **by a widget tap or a notification tap**
  *is* granted while-in-use, because the user acted on a UI element of this app and the launcher that
  sent the `PendingIntent` is itself a visible app. The track widget's Start goes through an
  invisible activity, `TrackStartActivity`, so the system sees a component of this app starting —
  the plainest form of that grant. `ACCESS_BACKGROUND_LOCATION` is only what would upgrade the grant
  to all-the-time access, which is why the track widget can start a recording without it and
  `BootReceiver` still cannot — a boot broadcast is on neither list. So a recording is not resumed
  at boot: there is no location to record with, and the phone was off for the part of the route
  that is missing anyway. Its track stays open, and the next start of the service from the app picks
  it up, beginning a new stretch of line.
- **A restart from the background**: a `START_STICKY` restart has no while-in-use standing for `location` either, and Android 14 answers that type with a `SecurityException`. `startForegroundDegrading` falls back to `health` alone — the service is then a pedometer, and a route waits for the app to be opened — and declares nothing when no type is allowed.

**OEM background-killing**: Samsung, Xiaomi (MIUI/HyperOS), Huawei (EMUI), OnePlus/Oppo (ColorOS), and Vivo aggressively kill background processes; MIUI historically blocked background work by default and Samsung (pre-One UI 6) restricted wake locks in foreground services. Samsung's own Application Management page (dated 07/2024 by dontkillmyapp.com) states: "To strengthen the Android platform, our collaboration with Google has resulted in a unified policy that we expect will create a more consistent and reliable user experience for Galaxy users. Since One UI 6.0, foreground services of apps targeting Android 14 will be guaranteed to work as intended so long as they are developed according to Android's new foreground service API policy." The app's answer is a proper FGS with an ongoing notification, and state persisted so that a kill costs at most the last minute's decisions, never the steps (§2.2).

### 7. AndroidManifest.xml

The manifest is `app/src/main/AndroidManifest.xml`, and it is not copied here: a copy would drift
from it. The sideload flavor adds one permission in `app/src/sideload/AndroidManifest.xml`.
`ManifestSurfaceTest` holds every declared permission to a use of it in the code and lists what is
exported.

#### 7.1 What the manifest declares

In groups, the main manifest asks for:

- activity recognition, and fine and coarse location;
- Bluetooth scan, flagged `neverForLocation`, and Bluetooth connect;
- foreground service with the `health` and `location` types — `StepService` declares both,
  `WorkPlaceCheckService` `location` alone;
- notifications, exact alarms (the medicine alarm, and the Work screen's place check) and
  full-screen intent (the medicine alarm);
- `USE_BIOMETRIC` for the app lock, and `WAKE_LOCK` for a sensor trace or a workout set counted
  with the screen off;
- boot completed, for `BootReceiver` (§2.2);
- `PACKAGE_USAGE_STATS`, the Usage Access app-op behind the sleep estimate and screen time;
- 54 `android.permission.health.*` permissions: `HEALTH_PERMISSIONS` in `HealthSync.kt` is the
  list, and `HealthPermissionManifestTest` holds the manifest to it in both directions.

There is no `INTERNET` permission, and `NoNetworkPermissionTest` holds the merged manifest to that.
`ACCESS_NETWORK_STATE`, which WorkManager contributes, is removed in the merge (no worker here has a network
constraint), and so is
androidx.emoji2's startup initializer, which would ask the phone's downloadable-fonts provider —
Google's, on a phone with Play services — for an emoji font shortly after the first screen. The
`<queries>` block names Health Connect (§3.1). Cloud backup and device transfer follow
`data_extraction_rules.xml` (§5).

Not declared, on purpose:

- **`READ_HEALTH_DATA_IN_BACKGROUND`** — the exchange is started only from the open app (§3.4a);
  and **`READ_TOTAL_CALORIES_BURNED`** and **`READ_DISTANCE`**, because those types are write-only
  here (`HEALTH_WRITE_ONLY_TYPES`).
- **`ACCESS_BACKGROUND_LOCATION`** in the main manifest — route recording does not need it: an FGS
  with the `location` type keeps receiving fixes with the screen off (§17.1). Only the sideload
  build declares it, in `src/sideload/AndroidManifest.xml`, for the Work screen's *Start by
  location*: that check's service is started by an exact alarm with the app closed, and gets no
  position without it (`WorkPlaceCheck.kt`). The play build leaves it out, and hides the feature,
  until Google Play has approved a Location permissions declaration
  (`docs/play-release-checklist.md`).
- **`HIGH_SAMPLING_RATE_SENSORS`** — the accelerometer runs at 25 Hz, nowhere near the 200 Hz
  threshold that permission gates.
- **`BODY_SENSORS`** — Health Connect's granular permissions stand in its place (§10).
- **`CAMERA`** — a photograph is taken by the system camera app into a `FileProvider` URI (§19).
- **`VIBRATE`** — the dose alarm's vibration is its notification channel's.

The `location` foreground type is declared in the manifest but only *claimed* at
`startForeground()` while a track is recording; step-only operation runs as `health` alone.

**Permission request order (Android 11+ two-step)**: activity recognition and foreground location (`ACCESS_FINE_LOCATION`/`COARSE`) are asked for first. Background location, where the sideload build asks for it, is asked for **separately**, only when *Start by location* is switched on, after precise location and behind a card that says what it is for. Per the Android 11 privacy docs, "If you request a foreground location permission and the background location permission at the same time, the system ignores the request and doesn't grant your app either permission." On Android 11+ the "Allow all the time" option is not in the dialog — the person is sent to Settings. `POST_NOTIFICATIONS` (Android 13+) is asked for in onboarding (§21). A type's permission must be granted **before** `startForeground()` claims that type, or the service throws (§6).

### 8. The foreground service, as it starts

`StepService.onStartCommand` does the same few things for every command:

1. It acts on the intent's action — start, stop, pause or resume a route (`ACTION_START_TRACK`,
   `ACTION_STOP_TRACK`, `ACTION_PAUSE_TRACK`, `ACTION_RESUME_TRACK`), the boot receiver's
   `ACTION_BOOT`, which resumes nothing (§6), or none at all, which is a plain start or a
   `START_STICKY` restart and picks an open track back up from the database.
2. It calls `startForeground` with the types the moment allows (`startForegroundDegrading`, §6):
   `health`, and `location` while a route is recording.
3. It stops itself when there is neither a step counter it may listen to nor a route to record
   (`serviceHasWork`) — a phone with no pedometer still records a route.
4. Otherwise it registers the step counter (batched at 2 s), the accelerometer (25 Hz, unbatched,
   §14.4), the barometer and, while recording, the ambient-temperature sensor, and returns
   `START_STICKY`. Re-registering a listener that is already registered is harmless: the
   accumulator turns a repeated counter value into a delta of 0.

`location` and `health` are exempt from the 6 h `dataSync` timeout, so no `onTimeout()` handling is
needed for a long walk.

### 9. Export formats

Raw points are stored; a route is exported on demand through the **Storage Access Framework** (`ActivityResultContracts.CreateDocument`) or shared from the walk page, so the app needs no storage permission.

- **GPX 1.1** (primary): namespace `http://www.topografix.com/GPX/1/1`, `<trk><trkseg><trkpt lat lon><ele/><time/></trkpt>`; `<time>` is ISO-8601 UTC, the elevation datum is named in a comment. Speed, bearing and heart rate are not written: no `<extensions>` block. Douglas–Peucker thins the points, each stretch between pauses on its own (`Gpx.kt`).
- **GeoJSON**: one `Feature` carrying the route's start, end, distance, climb and descent, simplified the same way. Its geometry is a `LineString`, or a `MultiLineString` with a line for each stretch between pauses (a `Point` or `MultiPoint`, or a null geometry, when no stretch has two fixes). Positions are `[lon, lat]` or `[lon, lat, elevation]`, with no per-point properties and no timestamps; those are in the GPX and the CSV.
- **CSV**: every fix, rejected ones included, with its timestamp, raw and corrected coordinates, GPS and barometric altitude, accuracy, speed, movement kind, the filter's verdict and reason, and the mark of a stretch's first fix after a pause, for power users. It is the one export that decides nothing: no simplification and no filtering.

None of the three route formats is read back by this app; the app's own JSON backup (`Backup.kt`) is the only round trip. There is no TCX or FIT export.

### 10. Android 15 / 16 / 17 platform checklist

| Requirement | State |
|---|---|
| FGS types declared and matching permissions held before `startForeground` | **done** — types recomputed per start (§6, §17.1) |
| `location`/`health` exempt from the 6 h timeout, no `onTimeout` needed | **done** |
| No FGS of a type Android 15 bars from `BOOT_COMPLETED` | **done** — the boot receiver starts `health`, which is not one of them (§2.2, §6) |
| Edge-to-edge insets | **done** — `enableEdgeToEdge()` + `Scaffold` |
| Predictive back, no `onBackPressed()` override | **done** — Compose `BackHandler` |
| 16 KB page size for native libs | **done** — none of the `.so` files are first-party: ONNX Runtime's two, SQLCipher's and androidx's `libandroidx.graphics.path.so` ship in the APK uncompressed, with every `PT_LOAD` segment aligned to 0x4000. `OnnxRuntimeBuildTest` holds ONNX Runtime's to that, and the release checklist runs `zipalign -c -P 16` on the APK. |
| Large-screen adaptive layout at sw600dp+ | **not built** — one pane at every width |
| Granular `android.permission.health.*` instead of `BODY_SENSORS` | **done** — 54 of them are declared, covering 33 record types (26 read and write, five write only, two read only) plus `READ_HEALTH_DATA_HISTORY` (§3.6); `BODY_SENSORS` is not |

- **FGS types** are mandatory since API 34: `location|health` is declared and the matching runtime permissions are held before `startForeground`.
- **Android 15**: `dataSync`/`mediaProcessing` FGS limited to 6 h/24 h with `onTimeout`; `location`/`health` exempt. `BOOT_COMPLETED` cannot launch `dataSync`, `camera`, `mediaPlayback`, `mediaProjection`, `phoneCall` or `microphone` services — `health` and `location` are not on that list, though a service started from the background gets no while-in-use camera, microphone or location.
- **Android 16 (API 36)**: edge-to-edge enforced (opt-out attribute deprecated); predictive back mandatory (`onBackPressed()` no longer fires — `OnBackInvokedCallback`/`BackHandler`); stricter JobScheduler quotas even with an FGS running; large-screen adaptive layout expected at sw600dp+; 16 KB page-size support required for native libs; granular `android.permission.health` permissions replace `BODY_SENSORS`/`BODY_SENSORS_BACKGROUND` for apps targeting 36, with `READ_HEALTH_DATA_IN_BACKGROUND` in place of `BODY_SENSORS_BACKGROUND`.

### 11. Testing the double-count logic

The pure Kotlin suite in `app/src/test` covers deterministic step, motion, route, backup and
Bluetooth protocol logic without a device. No test count is kept here, in this section or elsewhere
in the document: the report `check` writes under `app/build/reports/tests` has it. Coverage
includes:

- accumulator cases (`StepAccumulatorTest`): cold start/reboot baselines, duplicates, counter reset,
  three-step bout confirmation, pause reset, missing gait evidence, non-monotonic time, valid
  batching, and the nine-steps-per-second rejection;
- motion cases (`MotionGateTest`): slow/normal gait, running, hand/pocket/flat postures,
  insufficient data, vibration, shaking, irregular handling, and irregular pocket jostling;
- route-correlation cases (`RouteCorrelationTest`): normal walking, a 10 m jump followed by return,
  confirmed fast continuation, impossible speed, final unconfirmed jump, and non-monotonic time;
- movement segmentation, health, integration, sleep and strict Bluetooth decoder cases, including
  clipped/RFU packets, multipart records, counter rollover and protocol state machines.

Robolectric is on the test classpath and runs, in every `check`, the migrations from each exported
schema version (`MigrationRunTest`), a chat's act and a duplicate tap over Room (`ChatActTest`,
`CaffeineCupLogTest`), a dose's notification (`MedicineReminderActionsTest`) and the semantics of
composed controls (`ControlSemanticsTest`, `OnboardingNextTest`); the screenshot tests
(`OnboardingShotsTest`, `StrengthShotsTest`) run with `-Pscreens`. The instrumented tests in
`app/src/androidTest` need a device: `MigrationTest` runs the Room migrations on Android's own
SQLite, and `ShippedModelsTest` and `LabDocumentReaderTest` open the shipped models through the ONNX
Runtime the APK carries.

Not built: instrumented tests of the service's lifecycle, and Health Connect tests over the androidx
`FakeHealthConnectClient` that would assert one record for one `clientRecordId` written twice and
an aggregate that leaves the app's own writes out.

### 12. Other open-source apps and their licences

No code is copied or closely adapted from another app. Gadgetbridge is AGPLv3, and its protocol
implementations are not used; the COLMI adapter is written from an independently published,
MIT-licensed description of the wire format, with its own Kotlin and tests
([integrations.md](integrations.md)).

### 13. F-Droid inclusion & anti-features

The code is GPL-3.0-or-later, with `LICENSE` at the root ([Licence](../README.md#licence)), and
there is no DI framework or other non-free dependency. What bears on a submission:

- **Dependencies.** AndroidX, Room, Compose, WorkManager, Glance, SQLCipher and `androidx.health.connect:connect-client` are FOSS and do **not** trigger `NonFreeDep`, which applies to apps needing a **non-libre app to run** (e.g. Google Maps/Play Services).
- **Health Connect is acceptable on F-Droid.** It is Apache-2.0 and part of AOSP since Android 14; Gadgetbridge ships Health Connect integration. It is optional and the app works fully when it is absent, so it does not make the app depend on a non-free component (on Android 13 the provider is a proprietary app from Google Play).
- **No INTERNET permission** avoids `NonFreeNet`/`Tracking` concerns entirely.
- **ONNX Runtime is a prebuilt AAR** committed in `app/libs`, built by `tools/onnxruntime-android/build.sh` without telemetry, not a dependency built from source in the app's build.
- **The models are shipped weights.** The food photo model was trained on public datasets under their own terms, which a submission has to weigh against F-Droid's `NonFreeAssets` anti-feature; [Licence](../README.md#licence) says what the code's licence does not cover.
- **The APK carries no dependency block** in its signing block (`dependenciesInfo.includeInApk = false`); F-Droid's scanner refuses an APK that does.
- **Reproducible builds**: not built. Nothing compares two builds of one tag.

### 14. Motion gating — refusing steps that aren't steps

`MotionGate.kt`, with its JVM tests in `MotionGateTest.kt`, plus the sequence tests in
`StepAccumulatorTest.kt`. A detailed comparison with Android, published open algorithms, Apple
Watch calibration, and open-source outdoor trackers is in `docs/STEP_DETECTION.md`.

The gate is a second opinion sitting behind the hardware pedometer. Every step delta the
accumulator produces is checked against what the accelerometer was doing at the time, and a delta
that does not look like walking is **discarded, not deferred**.

#### 14.1 Why gating cannot be bolted onto the accumulator

The accumulator must advance `lastCumulative` on every reading whether or not the steps are
credited. If a rejected delta left the state untouched, the next accepted reading would compute its
delta against a stale baseline and hand back the rejected steps with interest. So the verdict
travels with the reading, and the ledger that decides it advances the state whatever it says:

```kotlin
class StepLedger(private var state: StepState?, private var lastStepAtMs: Long) {
    fun add(reading: StepReading, now: Long, zone: ZoneId): StepUpdate {
        val update = StepAccumulator.accumulate(state, reading.rawCounter, ..., reading.gaitEvidence, ...)
        state = update.state                                 // ALWAYS advances
        ...                                                  // credits by (day, hour)
    }
    fun take(): StepWrite?                                   // the state and its credits, together
}

suspend fun StepsDb.writeSteps(write: StepWrite): Long = withTransaction {
    dao.saveState(...)
    write.credits.forEach { (key, steps) -> dao.bump(key.first, steps); ... }
    ...
}
```

State advance and conditional credit are written in one transaction, so a crash between them is not
a recount. The service writes at once while the screen is on and within a minute while it is off
(point 5 in §2.2).

#### 14.2 The two axes: what the device is doing, and where it is

Two independent questions, because the answer to the second changes how much you should trust the
first.

**Motion** — from the accelerometer magnitude over a 6 s analysis window (`Motion`), seven regimes:

| Regime | Signature | Steps |
|---|---|---|
| `STILL` | std < 0.35 m/s² | rejected |
| `WALKING` | smoothed std ≥ 0.8 and raw std ≤ 10 m/s², dominant rhythm (or twice it) in 0.8–3.8 Hz, periodicity ≥0.40 | **counted** |
| `RUNNING` | the same periodic gait, with the rhythm itself ≥ 2.35 Hz over a window of at least 5 s | **counted** |
| `VEHICLE` | lying flat with the screen off and no stride in the signal, or a stride-like rhythm under 1.5 m/s² there | rejected |
| `NON_GAIT` | any other movement that fails the gait test: no stride in it at all | rejected |
| `SHAKING` | std > 12 m/s² | rejected |
| `UNKNOWN` | not enough window yet to classify | **counted** — see below |

The gait floor is 0.8 m/s², measured off real recordings rather than assumed, and the periodicity
floor is 0.40 on a normalised autocorrelation. The cadence is not inferred by counting mean
crossings: the autocorrelation is searched for its own peak across the plausible stride range,
because a crossing count reads a real pocket walk as an implausible period and throws its steps
away.

**Posture** — from the direction of the mean acceleration vector, which is gravity, expressed as the
angle off the device's +Z axis (0° = screen up, 90° = upright, 180° = screen down) combined with
`PowerManager.isInteractive`:

| Posture | Test | The situation |
|---|---|---|
| `FLAT` | tilt < 30° or > 150° | on a table, a car seat, a cupholder, a lap |
| `IN_HAND` | not flat, screen on | being read — navigating, scrolling |
| `POCKET` | not flat, screen off, tilt > 60° | trouser pocket, bag, jacket |
| `UNKNOWN` | anything else | |

#### 14.3 The regimes in practice, and how each is caught

- **"In hand vs in pocket"** — these have genuinely different signatures, and the discriminator that
  matters is not the tilt but the *screen*. A phone held and looked at is `IN_HAND`; the same tilt
  with the screen off is `POCKET`. Both are allowed to produce steps: walking while navigating is
  still walking. Posture is used to *tighten* the vehicle test, not to veto.
- **"Screen locked but horizontal and steps counting → car or train"** — this is the case the plain
  cadence test misses, because road and rail vibration can land inside the 0.8–3.8 Hz gait band.
  The extra rule: `FLAT` **and** screen off **and** std < 1.5 m/s² ⇒ `VEHICLE`, regardless of how
  gait-like the cadence looks. Vibration transmitted through a car seat is low-amplitude; a real
  stride in a jacket pocket that happens to lie flat is not. There is a test for each direction —
  `low amplitude buzz on a flat surface reads as a vehicle` and
  `an energetic walk with the phone flat still counts`.
- **"Person just sitting somewhere (train/metro) with some vibration"** — the same rule, plus
  `STILL` catching the stationary case outright.
- **"Shaking a phone"** — the `SHAKING` band. Note the honest limit in 14.6.
- **"Exploring navigation — display on and some shaking"** — `IN_HAND` with a real cadence passes
  (you are walking), `IN_HAND` without one does not (you are standing still waving the phone about).

#### 14.4 Cadence and periodicity

The magnitude signal first receives a three-sample moving average. Nothing is inferred from mean
crossings (§14.2): `dominantRhythm` evaluates the normalised autocorrelation at every lag
between 4 Hz and 0.5 Hz — a period has to fit the window at least twice — and takes the shortest
lag that comes within 90 % of the strongest correlation, because a signal that repeats every N
samples repeats every 2N just as faithfully. That lag is the rhythm, and its correlation is the
periodicity:

```kotlin
val (cadenceHz, periodicity) = dominantRhythm(smoothed, sampleRateHz, config)
```

A rhythm is gait when it, or twice it, lies in 0.8–3.8 Hz (`GateConfig.rhythmIsGait`): a phone in a
pocket feels the stride, one repeat for every two steps. The periodicity floor rejects isolated
bumps that happen to land on a plausible tempo. The same six-second window distinguishes walking
from running and is reused as evidence when the route exceeds the product's 7 km/h walking limit.

Sampling: the accelerometer runs at **25 Hz** (40 000 µs) — above the Nyquist limit for a 3 Hz
running cadence with margin — and is deliberately **not batched**
(`StepService.registerAccelerometer`): the gate classifies a step against the window that covers
it, and an accelerometer buffered while the pedometer flushes separately leaves the gate with no
window at the moment it is asked. The step counter and the barometer are batched at 2 s.

#### 14.5 Sequence confirmation, and what an unclassifiable window means

```kotlin
val stepsAllowed: Boolean
    get() = motion == Motion.WALKING || motion == Motion.RUNNING || motion == Motion.UNKNOWN
```

`UNKNOWN` **does** credit a counter change, on purpose: `TYPE_STEP_COUNTER` is already filtered by
the platform's own gait model, so an unclassifiable accelerometer window is not grounds for throwing
its steps away — and on a batching sensor hub the window normally has not arrived yet, so requiring
positive gait would discard the normal case (`MotionReading.stepsAllowed`). The gate vetoes only a
positive finding of something that is not walking.

A third outcome, `MotionReading.stepsDoubtful`, is movement with a walker's energy and a stride's
tempo but too little regularity — a stair climb with the phone going from the hand into a pocket.
`StepAccumulator` credits such steps only inside a walk the gate has just confirmed.

A new bout keeps its first two candidate steps pending and credits them only when a third periodic
gait step confirms the sequence. **A gap longer than 30 s starts a new bout**
(`StepAccumulator.BOUT_GAP_NANOS`), sized for how the hardware reports: a hub flushes
`TYPE_STEP_COUNTER` every few tens of seconds, and with a gap of a few seconds every flush would
begin a fresh bout, every bout would stay pending, and almost nothing would be credited. What keeps
isolated pocket and hand movement out is the `MIN_BOUT_STEPS` rule, not the gap.

The cumulative counter increase is also checked against its sensor timestamps. The hard ceiling is
5 steps/s plus one boundary-event allowance for batching; nine steps in one second are rejected as
a whole. After reboot the first cumulative value becomes a baseline instead of injecting
unverifiable since-boot history into the current day.

#### 14.6 What this does not fix

- **A long, deliberate shake at walking cadence can still resemble gait.** It must also make
  the hardware step counter produce a physiologically timed, three-step sequence, so incidental
  handling and pocket jostling are rejected. Perfect separation still needs a labelled
  multi-position accelerometer/gyroscope dataset and device validation.
- **`GateConfig` starts from published gait figures, tuned against a handful of recordings** —
  pocket walks, a bus ride, two stair climbs; the KDoc of the gait floor, the periodicity floor and
  the doubt threshold says which. Its own KDoc calls the values starting points, which is why every
  threshold is a named field on a data class: retune per device family rather than editing the
  branching.
- **The accelerometer runs continuously and unbatched**, which is the real cost of this feature. At
  25 Hz it is not free, and there is no switch to turn it off.
- **The step counter's two-second batching is a lower bound on gate latency.** A step is judged
  when its batch arrives, up to 2 s after it was taken, against the accelerometer window that ends
  at the step's own timestamp (`SensorFeed.decisionAt`).

### 15. Live sensor view (developer)

`SensorScreen.kt` renders from `AppScreen.SENSORS`. It has two doors: seven taps on the version
line under Settings → About the app, in any build, and a long press on the home stop of the bottom
bar, in a debuggable build only. Nothing announces the first. It exists because a step count that
came out wrong cannot be argued about without the trace this screen records, and the person holding
the wrong count is never the person holding a debug build.

Motion classification that you cannot watch in real time is untunable — you are reduced to walking
around and checking whether the number went up. The sensor screen shows, live:

- **Acceleration magnitude** over the last ~6 s as a single line trace with a hairline mean rule.
  One series, so no legend; the title names it.
- **The gate verdict** as a banner — `Gate open — steps are being counted` or
  `Gate blocked — steps are being discarded` — in `primary` or `error`. The colour is redundant
  with the words, never the only encoding.
- **The inputs to the decision**: motion regime, posture, energy (std), cadence in Hz, tilt in
  degrees, screen state, and the raw x/y/z of the newest sample.
- **The consequences**: the raw hardware counter value, and steps credited vs steps rejected since
  the service started.

Below them, a **trace recorder** writes the sensors and the gate's decisions to a CSV that can be
shared, and a **rep analyser** runs a recorded trace through every accelerometer-based repetition
profile and reports what each would have counted.

Put the phone in a pocket and walk, then sit in a car, and the regime and banner flip in front of
you. That is the tuning loop for §14's thresholds.

Implementation notes:
- `SensorFeed` (in `MotionGate.kt`) keeps the last 12 s of samples in memory, capped at 1 024, plus
  a `StateFlow`; one decision reads at most the 6 s that end at its event. Deliberately not
  persisted: it is a live view, not a record, and writing 25 rows/s to Room to power a debug screen
  would be absurd.
- It re-classifies and emits every 400 ms (~2.5×/s) rather than on every sample, so the graph
  is smooth without recomposing at 25 Hz.
- The screen is reachable **even when the device has no step counter** — it reads the accelerometer,
  not the pedometer — which is what makes it usable on an emulator.

### 16. Dashboard design

`HomeScreen.kt`, `HomeCards.kt`, `HomeHeader.kt`.

The screen is a greeting, a pager of cards and very little else:

- **The greeting** (`HomeGreeting`) — the hour's words, with the annual level and a bell on the
  right. While a permission is missing, a card under it asks for it.
- **The day's cards** (`HomeMetricCarousel`) — steps, food and energy burned, one to a page, each
  with a bar for every hour of the day, standing in the middle of what the greeting leaves above
  the bar. A tap opens the metric's own page.
- **Notices** — advice, a missed dose, a cycle question — wait in a shade behind the bell rather
  than on the page, and a floating button opens the chat.

Quick add is a home-screen widget, `QuickAddWidget` in `Widgets.kt`, not a row on this screen.

The steps card draws the day's hours from `hourly_steps` (day, hour, steps), which `writeSteps`
fills in the same transaction that credits the day total, and `Backup.kt` carries out and back.
Only the phone's own counter feeds it — Health Connect and BLE peers report daily figures — so the
day total can legitimately exceed the sum of its hours. The total stays the figure of record, and the chart says
so where the hours cannot add up to the day (`hoursOn` in the DAO, `HomeCards.kt`).

Details that matter: the caffeine sheet is logged in drinks but printed in milligrams against the
day's limit, because a tea and a double espresso are not the same cup; the water target is floored
(`waterTargetMl`), so a profile carrying a zero weight still has a target; and a day with no record
keeps its place in the bar window as a zero instead of closing the gap and making two tracked days
look adjacent. Dark mode is a second hand-written scheme, `PwnDarkColors` — its own tones rather
than an inverted light one. No Material You dynamic colour anywhere: `PwnFitTheme` starts from the
two hand-written schemes and lays over them the colours a person has chosen in the theme settings
(`ThemePalette.kt`); the word `dynamic` does not appear in `PwnDesign.kt`.

Navigation is a screen enum and a `BackHandler` in `MainActivity.kt`, which is also how the app
satisfies Android 16's predictive-back requirement (§10) without a navigation library.

### 16.1 Recording a measurement

`EntryEditorScreen.kt`.

The add sheet has one door for measurements, because the editor behind it already asks which
measurement this is — offering the same choice twice would only make the sheet longer. Inside, a
five-way tab strip (weight, height, heart rate, sleep, girth): mutually exclusive options read as a
segmented control, and the icon lands faster than the word under it.

Under the value sit the last three readings **of that kind** and a note about how to take it well.
A weight means little on its own and a great deal next to the two before it, and the tip is where
the app admits that when you weigh yourself moves the number more than the week does.

Heart rate is measured by the accelerometer, not the camera: a phone resting on the chest rides the
ballistocardiogram, `Ballistocardiogram` in `Heartbeat.kt` pulls the period out of it by
autocorrelation. No camera permission, and it works in the dark.

The value itself is a wheel, not a drag-ruler. A ruler looks better in a mockup; a wheel hits 68.5
without a fight, and on a 40–140 bpm slider a pixel is worth several beats.

### 16.2 Reopening a row that was logged in one tap

Every row in the journal is tappable, and the editor has three forms behind that tap: a meal, a
workout, and the hand-typed measurements. There are twenty-three kinds of row, one `KIND_*`
constant each, and a form per kind would be the wrong answer: a one-tap row is not typed, so it has
no form to reopen — what it has is the choice it was logged with. Falling through to the
measurement form would be worse than useless — a glass of water would open a weight wheel loaded
with the profile's weight, and a breathing practice, which carries a duration, would light Save up
over a form that rewrites the row as a weigh-in.

So water, alcohol, tobacco, stool, mood and the morning check are re-offered as the same chips
their own screens use, with the option already selected (`simpleOptions` in `SimpleOption.kt`),
and saving rebuilds the row through the very factory that wrote it (`rebuiltSimpleEntry`), keeping
only the id and the timestamp. A correction reads identically to a fresh log, because it is one.

Kinds with nothing to choose — a visit to the toilet, a finished practice, a planned session, a lab
panel — get a read-only card that says so, above a Delete button. That is honest rather than lazy:
there is nothing in a urination row to correct except whether it happened, and a panel of a dozen
markers belongs to the screen that entered it. The same card catches any kind a restored backup
introduces that this build has never heard of. It also takes caffeine, which is two choices at once
(`simpleOptions` says why), and a measurement the form cannot hold, such as a blood pressure, which
a Save there would replace with the profile's weight (`measurementFormShows`).

### 17. Track recording, and the offline map it draws on

`StepService.kt` (recording), `TrackScreen.kt` (UI), `WorldMap.kt` (map).

#### 17.1 Recording

Start, stop, pause and resume are `Intent` actions on the existing foreground service
(`ACTION_START_TRACK`, `ACTION_STOP_TRACK`, `ACTION_PAUSE_TRACK`, `ACTION_RESUME_TRACK`) — no second
service, no binding. Provider selection is §4.1 exactly: platform
`FUSED_PROVIDER` when present, `GPS_PROVIDER` otherwise, via `LocationManagerCompat` with a 2 s
interval and `minUpdateDistanceMeters = 0` so all filtering happens in software where it can be
inspected.

The service's foreground type is recomputed when a track starts: `health` alone while only counting
steps, `health|location` while recording. Declaring `location` permanently would demand the location
permission from users who never record a track.

**No `ACCESS_BACKGROUND_LOCATION` for recording.** A foreground service with the `location` type
keeps receiving fixes with the screen off, so for a route the extra permission buys nothing and
costs a scary dialog and an F-Droid eyebrow. The one thing that does need it is the Work screen's
*Start by location*, whose check is started by an alarm with the app closed; it is asked for only
there, and declared only by the sideload build (see §7).

#### 17.2 Point filtering and movement segmentation

Per §4.3, with raw fixes kept regardless, the first gate rejects stale or inaccurate measurements:

```kotlin
if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_M) return RouteFixReason.LOW_ACCURACY // 25 m
if (ageMs > MAX_FIX_AGE_MS) return RouteFixReason.STALE_FIX // 30 s: a cached last-known fix
```

The staleness check matters: on the first request a provider hands over its last known location,
which can be minutes old and kilometres away, and without the check the line would open with a spur
from wherever the phone last got a fix to where the walk actually starts.

The second gate is `RouteCorrelationFilter`, covered by the JVM scenarios in `RouteCorrelationTest`
and `RouteGeometryEdgeCasesExtraTest`. It predicts the next position from the last two confirmed
fixes and compares innovation with combined GPS accuracy, GNSS/Doppler speed, speed accuracy, and
the inertial movement class. A contradictory jump is held for one more fix. Continued displacement
confirms genuine fast travel; a return to the predicted line marks the middle fix `RETURN_SPIKE`.
An unconfirmed last jump and speeds over 70 m/s are rejected. Raw coordinates and the reason remain
stored, while the plot and distance use only confirmed coordinates. When fifteen fixes in a row
have been refused, the last confirmed one is a minute old and the new ones lie further from it than
a walk covers in that time — a metro ride — the filter gives that anchor up and starts again at the
current fix (`REANCHORED`), which begins a new stretch of line.

Accurate stationary fixes are retained to show stops, but never add distance. A separate
distance anchor accumulates slow movement until it clears `max(3 m, accuracy × 0.5)`. Movement is
classified as walking, running, vehicle, stopped, or unknown. A three-speed median suppresses a
single GNSS spike. Above 7 km/h, the segment is never walking: a six-second periodic cadence of at
least 2.35 Hz proves running; otherwise sustained speed is vehicle movement. Stops require 15
seconds below the stop threshold inside the accuracy radius.

Each point also stores raw and corrected coordinates, the correlation verdict, speed and vertical
accuracy, GPS altitude, pressure, GPS-calibrated
barometric altitude, ambient temperature when available, cadence, motion energy, and environment
spike flags. Track totals keep walking, running, and vehicle distance separate. Completed routes
are selectable in the Saved routes list, and complete tracks/raw points are part of the local JSON
backup.

`TrackEntity.endedAt IS NULL` is the recording marker, so a service restart (`START_STICKY`) picks
up an interrupted track from the database rather than from memory.

#### 17.3 The map

The route is drawn in **normalised Web Mercator** over a world map that ships in the APK. Segment
colors show walking, running, vehicle, stop, and unknown states; the legend repeats every meaning in
text. Start, end, stop points, and environmental jumps are marked. Pinch and drag inside the panel,
drag two fingers up or down to tilt it, or open it full screen — the panel and the dialog share one
`MapCamera` and one `MapTilt`, so the zoom and the angle carry over. Until the first gesture the view
follows the route, refitting as fixes arrive; after one, the button in the corner gives the route
back.

**The ground is a lattice of dots, tilted, drawn on the GPU.** `GroundShading` in `MapDots.kt` is one
AGSL `RuntimeShader` over the whole panel. For each pixel it casts the ray from the eye, marches it
in eight steps between the highest and the lowest ground in view and refines the hit by a secant,
so a hill hides what lies behind it. Where the ray lands it takes the nearest dot of a hexagonal
lattice fixed to the ground — a pan moves the dots with the land under them — and colours it by what
the ground is there: forest green, water blue, a town grey-blue, ice and salt flats pale, and plain
land in a tint that runs with height. Woods and glaciers take the relief's light and shade against
the plain's own tint, or a forested mountain would be one flat green. The lattice coarsens with distance continuously: every
coarser lattice holds every dot of the finer one, and the dots it lacks shrink away over the first
third of an octave, so there are no rings where the spacing jumps and no fat dots on the horizon. A
dot is a disc on the ground — an ellipse on screen — smoothed by the pixel's own footprint. The CPU
sorts the ground into a small texture once per region and lattice level (`classifyGround`, at most
1536 texels a side) and draws only the route, the rivers' rows of dots, the photographs and the
phone's position over it; a pan, a pinch or a turn of the phone changes a few uniforms. `minSdk` is
33, so `RuntimeShader` is always there; should a renderer refuse the shader, the flat map with filled
pens draws instead.

**The camera tilts and turns with the phone.** `TiltCamera` in `Map3D.kt` looks at the middle from
twice the panel's longer side away, `MapTilt.pitch` above the horizon: 38° to begin with, 20° to 80°
with two fingers — never straight down, because the relief is what the tilt is for. Turning the
phone turns the map like a compass: `FollowCompass` reads `TYPE_ROTATION_VECTOR` (the geomagnetic
vector where there is none), lays the screen's top and the back camera's line of sight level and adds
them, so whichever is nearer level weighs the more, corrects that bearing by the magnetic declination
at the route and smooths it — while the
camera follows. A zoom about the middle — the buttons, a double tap, a pinch — keeps the camera
following at the zoom asked for (`MapCamera.zoomed`); a one-finger drag stops it (`manual`), and
then the map holds still and only `MapTilt.facing` moves, which an arrow out of the position disc
points along (`screenBearing` projects a short step of ground that way); the corner button gives the
turning map back. The relief's light turns with the map, or a ridge lit from below would read as a
valley: `render` takes the side it comes from, and the screen keeps it at the upper left, moving it
in `RELIEF_LIGHT_STEP` steps of 30° so a phone swaying in the hand does not shade the relief again
at every degree. A twist of two fingers turns the map whenever the compass is not turning it: once a
drag has stilled it, and always on a phone without a compass. The relief
is exaggerated until the highest span in view stands about an eighth of the view's width, eightfold
at most, and the route stands on it at its own height — the barometer's where the phone has one, the
receiver's otherwise, median-filtered — with its shadow on the ground and a stem down to it every so
often.

Four assets hold the world, **29.97 MB** of the 30 MB the map may take, all stored uncompressed in
the APK so their readers can seek:

- `assets/world.map`, **4.6 MB**, from Natural Earth 1:10m — public domain, no attribution required.
  Six layers, each with its own pen, in the order the file writes them: land, lakes, rivers,
  glaciers, salt flats and the streams Natural Earth adds for Europe and North America. Land, lakes,
  glaciers and salt are **filled**; rivers and streams are stroked as rows of dots. No roads, no
  country or region borders, no labels and no place names: at the zooms a walk is looked at they
  were lines nobody reads among the dots.
- `assets/world.relief`, **17.5 MB**, the ground's height from NOAA's ETOPO 2022 (public domain) at
  one minute of arc, about 1.8 km, with three coarser levels — see `tools/world-relief.py`.
- `assets/world.forest`, **7.1 MB**: where trees cover at least half of a cell, counted on ESA
  WorldCover 2021's ten-metre map (CC BY 4.0), in five levels down to half a minute of arc, about
  900 m in the middle latitudes — a wood a walk goes through. On the phone each texel weighs the
  four cells round it (`forestShare`), so a wood's edge runs between their middles instead of in
  steps along their sides. `tools/world-forest.py` reads
  WorldCover's own overviews by HTTP range requests rather than its ten-metre tiles. The About page
  credits WorldCover and ETOPO.
- `assets/world.towns`, **786 kB**, built by `tools/world-towns.py`: 224,745 places from GeoNames
  (CC BY 4.0, credited on the About page) — every one of five hundred people or more, and every
  seat of an administration — each measured on what ESA WorldCover shows built up round it, from
  its 75 m overview. A town is written as the middle of its built-up ground, the radius of a circle
  as large, and, for 55,134 towns of 600 m or more that run at least 1.54 to one, the stretch and
  turn of an ellipse of the same area. A blot several places share is divided among them as a
  Voronoi diagram weighted by the radius their populations suggest (0.0014 × people^0.85 km²,
  fitted to 1,906 towns and villages of Moravia and Lower Austria), and the measured area is held
  to between a third of that and four times it. The places with no built-up ground near
  them keep GeoNames' point and that area, as a circle. A town on this map is a blot of dots and
  needs no outline.

Greenland, Antarctica and everything south of 60° S are left out of all four, islets within 15 km of
them included: nobody walks there with this app, and their ice would be a megabyte and a half of the map
and its relief. A tile-based map is the usual answer and cannot be the answer here, because this app
has no `INTERNET` permission and will not grow one for scenery.

The palette comes in two, light by day and dark after dark, and the route's own segment colours
belong to the same object, because the daylight set is dark ink on paper and would vanish on night
land; they lift together with the map. `MapStyle` in `TrackScreen.kt` holds both. The route is drawn
over a casing of the land's own colour, a little wider than the line: the line owes its 3:1 to
whatever it is drawn on, and over forest and rock that is the casing. **Pens carry a zoom ceiling**
(`MapPen.upTo`, in degrees per pixel), so rivers appear at about the zoom where a region fills the
panel and streams only closer in.

Filling is why the encoder cuts the filled layers to a 5° grid rather than into fixed-length
strips: a fill needs closed rings, and one undivided Eurasia would be eighty thousand points
redrawn every frame of a walk down a street. Clipping happens in degrees, before quantisation, so a
cell edge lands on the grid from both sides and neighbours cannot leave a seam. The reader fills
even-odd, so a lake cut out of a country stays cut out.

**What this map does not have is streets**, or roads at all. Natural Earth's road layer is 9 752
major-highway and about 21 000 other road features for the entire planet; it is a world atlas, not
a street map. The only global alternative is OpenStreetMap-derived — GRIP4 is the packaged form —
and its classes 1–3 are about 5.2 million separate segments worldwide, so simplification bottoms
out at two points each and the layer cannot go below **~28 MB before any base map**. It would fit
a 35 MB budget only after stitching segments into continuous ways, and it comes under ODbL:
attribution in the app, and the obligation to offer the derived database. Natural Earth carries
neither. Leaving streets out is a product decision, not a technical one.

`tools/world-map.py` builds the asset — run it only when the map should change; the result is
committed. It simplifies with Douglas–Peucker into **five levels of detail** (0.6° down to 0.0002°,
about 22 m, which is finer than Natural Earth's own accuracy), quantises each to its own grid, and
writes zigzag varint deltas: 1.2 M points in 3.6 MB at the finest level, most coordinates one byte. Long lines
are cut into 128-point pieces so a bounding box can be small enough to cull against. Each level's
body is written whole with an offset in the header, so the reader can take one and skip the rest.

**The camera is fenced to the route.** It zooms out to `MAP_FARTHEST_ZOOM` — 168 times the track
screen's closest view, about 48 km across a phone in central Europe — and no further, and
`MapCamera.limit` is the route's own bounding box grown by half that widest view's longer side, so
zoomed all the way out the route can still be anywhere under the middle, with the land round it
in view. This is a record of where somebody went, not an atlas to look up
Peru in, and the cap is also what bounds everything below: the tilted ground is never drawn for a
country, so it never lags. Before the first fix there is no route, and the view is the world.
Tilted, the ground is read and drawn a view's length past the box (`tiltLimit`): looking low across
it the camera sees almost a screen's height behind its middle, and from the box's edge that would
be blank paper.

`WorldMap.kt` reads the header once per process — a few dozen bytes — and then reads **one level,
for one region**, straight off the asset stream with no copy of the body in between. The level is
the one whose error hides under a pixel; the region is the view grown to at least
`MAP_READ_METRES` (400 km) and clipped to the camera's limit. Lines are read into a scratch buffer
and copied out only if their box touches the region, so the rest of the planet costs the time to
step over its bytes and nothing else.

Both numbers earn their size. Reading any region costs one pass over the level, so a region the
size of the screen would mean a fresh pass every kilometre of panning; at 400 km the camera runs
out of room to roam before it runs out of map, and in practice a walk reads each level once. A new
read starts only when the zoom crosses a level boundary, on a background thread, with the level in
hand still drawing — so a boundary shows as detail arriving rather than as the map blinking out.

Nothing is retained after the screen closes: the decoded level lives in the composable's state and dies
with it, and the process-wide part is the header. The map fails soft: if the asset is missing the
route still draws.

### 18. Running it on an emulator

The emulator is a limited but genuinely useful rig for this app. It has **no `TYPE_STEP_COUNTER`**,
so the dashboard shows its no-sensor state — but it does have an accelerometer and a GPS provider,
which is enough to exercise §14's gate, §15's sensor view and §17's track recording end to end.
The APKs carry ARM code only (`arm64-v8a` and `armeabi-v7a`), so the image has to be an arm64 one.

#### 18.1 Setup

```sh
sdkmanager "platforms;android-37.1" "build-tools;37.0.0" "emulator" \
           "system-images;android-37.0;google_apis_ps16k;arm64-v8a"
avdmanager create avd -n steps37 \
  -k "system-images;android-37.0;google_apis_ps16k;arm64-v8a" -d pixel_7
emulator -avd steps37 -no-window -no-audio -no-boot-anim -gpu host
```

The `ps16k` image runs with 16 KB pages, which is what §10's alignment is for. Use `-gpu host`:
with the software renderer, `swiftshader_indirect`, the emulator can hang.

#### 18.2 Driving the virtual sensors

`adb emu <cmd>` opens a fresh console connection per call, which is far too slow to fake a cadence.
Hold one socket open instead:

```python
token = open(os.path.expanduser("~/.emulator_console_auth_token")).read().strip()
s = socket.create_connection(("127.0.0.1", 5554)); s.recv(65536)
s.sendall(f"auth {token}\n".encode()); s.recv(65536)

# a 2 Hz stride, gravity on Y so posture reads upright
while walking:
    y = 9.81 + 3.0 * math.sin(2 * math.pi * 2.0 * t)
    s.sendall(f"sensor set acceleration 0:{y:.3f}:0\n".encode())
    time.sleep(0.05)

# a route
s.sendall(f"geo fix {lon:.7f} {lat:.7f} 150\n".encode())
```

Injection rates that work: **20 accelerometer updates/s** (enough for a believable 2 Hz stride at
the app's 25 Hz sampling), and **one `geo fix` every ~2.5 s**. Faster `geo fix` injection is wasted —
the provider honours the app's 2 s interval and drops the rest — and points spaced too far apart
imply more than 12 m/s, which the correlation filter accepts only with a Doppler speed behind
it (§17.2). A sparse arc comes out looking like a scribble for that reason alone; test
with a shape whose corners survive decimation, like an L.

Inspect what actually landed:

```sh
adb shell dumpsys location | grep pwnall          # is the registration alive, or dropped?
# Quoted whole: adb hands the device shell one line, and bare parentheses stop it. Needs a debug
# build (run-as) and the database not encrypted.
adb shell "run-as com.pwnall.health \
  sqlite3 /data/data/com.pwnall.health/databases/steps.db \
  'select count(*), sum(accepted) from trackpoints;'"
```

### 19. Recognising food from a photograph, offline

`Add meal → Meal` runs one ONNX model, `assets/photo_v3.onnx`, on the device, with no network
and no Google Play Services. The dish camera comes from the system camera app via
`ActivityResultContracts.TakePicture` and a `FileProvider` cache URI, the gallery from the photo
picker — neither needs a permission, and the Product button takes the same two doors. What it adds
is one screen in between (`LabelCrop.kt`): the photograph, with the nutrition table marked out by a
finger dragging a red box over it, and that box is what the reader receives. A frame fixed in the
middle of a viewfinder would not do: real packets print the table around a can, up one edge, or in
a corner, and a centred rectangle cuts most of those in half. Drag nothing and the whole
photograph goes to the reader.

```text
photo → ImageDecoder (EXIF-corrected, 224²)
      → photo_v3.onnx (PE-Core-S16, int8 weights, 28.3 MB, ONNX Runtime)
         ├─ dish_probs: 2 024 classes (background + 2 023 dishes)
         └─ product_probs: 459 foods, independent probabilities
      → dish_templates.csv · product_labels.csv · catalogue word match
      → top 5 dishes and top 5 products, defaulting to the more confident answer
      → editable products and grams → one journal entry
```

**One network for dishes and plain foods.** The two heads share one image encoder and run in a
single pass (`PhotoModel.kt`). There is one self-contained food model file, with preprocessing and
calibration in its graph; no TensorFlow/LiteRT runtime, external weights or text table is packaged.
Its input is uint8 RGB in NHWC order at 224². The model is stored uncompressed and mapped from the
APK.

On 231 held-out photographs the opening answer is right on 131, and the right answer is among the
two rows of chips on 176.

**Weight is never claimed to be measured.** It is not observable from one RGB frame without a scale
reference; Nutrition5k reports ~17% mass error even with depth. Templates carry typical portions, a
small/medium/large chip scales them by 0.7/1.0/1.4, grams land on multiples of five, and every row
is editable, removable and extendable from the same catalogue the manual form uses.

**Mapping 2 023 dish classes onto the catalogue is where the bugs live.** The catalogue's
own search is deliberately forgiving — a person typing "chiken" wants chicken — and that generosity
is poison for an automatic path: `McDonald's` normalises to `mcdonald s`, and that stray `s` would
make Big Mac a prefix match for 205 of the 2 023 labels, while the multi-word alias `beet soup`
would make borscht the answer for all 45 of the model's soups. So the automatic path takes
whole-phrase equality, then whole words against product *names* only, and templates match on word
boundaries (`steamed` contains `tea`, `eggplant` contains `egg`, `pierogi` contains `pie`). About a
quarter of the vocabulary reaches a product; the rest — Chaudin, Ghoriba, Funge — resolves to
nothing on purpose.

**The wait is legible.** Reading a packet turns it four ways in three scripts, and a bare spinner
spends those seconds saying nothing. The sheet shows the steps as they happen — which turn it is
looking from, how many fragments of text came back, what parsed out of them, and the moment it gives
up on the frame and goes closer (`LabelStep`). Where the figure disagrees with the macros by
Atwater's arithmetic it is shown with a "≈" and a line saying so, and where the packet declares a
serving the basis is offered as a choice rather than announced: per serving, or per 100 g. The
basis is the one thing a wrong guess quietly triples.

**A dish is built, not looked up.** "Chicken fillet with buckwheat" as a catalogue row is a guess
about the two things its name cannot carry: how many grams of each, and how it was cooked. So the
meal form has a third door beside the two cameras — `Build a dish` — which assembles the dish from
rows the catalogue does know exactly: boiled fillet, fried egg, tomato. Weights are typed in grams,
and only food that is genuinely counted gets buttons beside the field — an egg, a burger, a slice of
pizza, with the unit named beside them ("1 шт. · 50 г", Russian for "1 piece · 50 g"). Buckwheat
does not: its "portion" is a bare 150 g typed into the catalogue, and USDA's most common portion
label, on three thousand rows, is literally "Quantity not specified", followed by "oz", "cup" and
"1 cubic inch". A button stepping by any of those is a worse offer than the keyboard. Roughly two
rows in three qualify. The rows, their arithmetic and the picker are the photograph path's; the
only thing missing is the model that guesses which rows to start with.

**The catalogue is 4 999 rows, 2 438 of them with a Russian name** (`top_foods.csv`, 1.09 MB). The
head of it is hand-written: Russian names, aliases and portions chosen by hand; the rest come from
USDA FoodData Central — SR Legacy, whose Fast Foods group carries McDonald's, KFC, Burger King,
Wendy's, Domino's, Pizza Hut, Taco Bell and Subway by name, plus the FNDDS survey foods, which is
where the world's dishes live. Public domain, seeded by `tools/build-top-foods.py`, which leaves
aliases, the fat rail and `saturated` empty (the first two are written by hand, the third by a
later USDA match) and refuses to top a curated file back up; the file is edited by hand. One row
per name — USDA files four "Mustard greens, frozen, cooked" that differ only in whether salt was
added — and a quota per category, because without one SR Legacy's cuts of meat take a third of the
catalogue and crowd out the food people log. The seed takes none of USDA's spices, baby food and
cooking oils; the oils in the catalogue were added by hand. The long tail carries English names
only — the 2 561 rows without a Russian name are past what anyone translates by hand — and a blank
side falls back to the one that exists.

The sheet shows confidence and editable rows. Czech and Slovak dishes are a known weak spot of the
model, and class-to-catalogue links can still give the wrong row.

**One keep rule holds the whole reader up.** ONNX Runtime's native layer finds its Java types by
name through JNI, and nothing in Kotlin refers to `ai.onnxruntime.TensorInfo`, so R8 would shrink
it away — and the first inference would then abort the process from native code, with no exception
to catch and no crash dialog, in a release build only. `proguard-rules.pro` keeps the package, and
a Gradle task (`verifyPlayReleaseKeeps`, `verifySideloadReleaseKeeps`) reads R8's own mapping after
every release build and fails it if the class was renamed or removed, because that is the only
place the mistake is visible.

**Licensing is a selection criterion for the code and the checkpoints.** PE-Core is Apache-2.0,
and ONNX Runtime, which runs it, is MIT; the shipped PE file is a changed work (re-exported, int8
weights) and says so in its own metadata, as the licence asks. Not used, for their terms: Apple's
MobileCLIP, whose weights are for non-commercial research; ML Kit, whose image labeller arrives
with `play-services-base` and Google's telemetry transport; Ultralytics YOLO (v5/v8/v11), because
AGPL-3.0 covers its weights and any fine-tune of them, whatever a Hugging Face upload tags itself
as; and SegFormer's `nvidia/mit-b0` base, which carries a non-commercial licence. All of that is
about code and base checkpoints. The training data is a separate question: `photo_v3.onnx` was
trained on public datasets under their own terms — [Licence](../README.md#licence) says what the
code's licence does not cover.

**Tests:** `FoodRecognitionTest` replays real model output — twelve photographs, the top classes of
the model's two answers with their probabilities, checked in as `recognition_samples.csv` — a
replay rather than a live run, so the test does not depend on the runtime's native library.
`PhotoModelTest` exercises the model file itself on the desktop runtime, and the instrumented
`ShippedModelsTest` runs it on a device, through the reduced Android runtime the APK carries.
`MealPhotoTest` covers the hand-written tables and the arithmetic that reaches the meal form.

**The other thing a meal photograph can be: a packet.** The capture card offers two buttons —
Meal for the classifier, Product for the label reader — so a packet never waits for dish chips
it will not get and a plate never runs seconds of OCR it does not need. The Product path is
PaddleOCR through ONNX Runtime (MIT) — the PP-OCRv6 small text detector and recognizer (Latin,
Chinese and Japanese in one model) plus PP-OCRv5's East-Slavic recognizer, with their CTC
dictionaries, 39 MB in `assets/ocr`. The Paddle family, measured against Tesseract on a corpus of
128 photographed European labels, reads 81 of them against Tesseract's 32, and 47 of 57 packets off
Gulf shelves against 30, keeping Czech diacritics, Cyrillic and Japanese alike where Tesseract
returns "297M/ekgll" for a printed "267kJ/64kcal". No OpenCV: a convex hull and rotating calipers
(`OcrGeometry.kt`) give each detected blob its own oriented rectangle, and the crop is sampled along
that line's axes — an upright box around a tilted row swallows the background above it and the row
below. A rectangle tilted past 25° is not a tilted line but a blob whose long side points
elsewhere, and keeps its upright box; the ladder's quarter turns answer real sideways print. Every
pixel operation lives in a plain-Kotlin `Raster` (`OnnxOcr.kt`), so the exact pipeline the phone
runs is what the JVM tests run against real photographs (`OnnxOcrTest`).

The v6 small detector and recognizer read more than v5's on 120 packets with their hand-typed
truth: cut out as the finger cuts them, 38 packets read whole against 27 and
328 of 480 figures against 290, and a quarter to a third faster, because one recognizer reads Latin
and Japanese both. v6's detector alone, its tiny tier and its tiny recognizer each read fewer than
v5 on the same packets. v6 drops the handakuten of たんぱく質 ("protein") outright, so たんはく is
among the protein keys; with that, and a kJ/kcal pair the packet printed outranking the macros'
arithmetic, the same packets read 42 whole and 333 figures. No v6 recognizer has a Cyrillic
letter, so Russian and Ukrainian print is read by v5's East-Slavic model. The pipeline has no
textline-orientation classifier and detects at 960 pixels: on the same corpus the classifier (a
6.5 MB model) read no more labels whole, and 1280 pixels read no more for twice the detector's
work.

Four rules read the panel by where its ink is. A row that names a nutrient and prints no figure
adopts the one printed above it — the layout every US panel uses for its calories. Where a header
names two columns, only the per-100 g one is kept, because a parser reading left to right takes the
serving column while believing it read the other. Where nothing paired at all, the largest type on
the panel is taken as the calories, which is how a person reads one across a shop aisle. And where
the whole frame yielded nothing usable, the panel is cut out of it and read again on its own, with
its contrast stretched: detection normalises to 960 pixels whatever it is handed, so a table filling
a third of a photograph arrives at three times the resolution it had. The crop only happens where
the frame failed.

Fields are not voted on across passes: the passes are not independent — the same recognition feeds
every assembly — so agreement would count repetitions of one mistake.

A keyword-line parser (`NutritionLabel.kt`, pure Kotlin, JVM-tested) strips spaces first —
Japanese OCR shreds 炭水化物 ("carbohydrate") into characters — then lifts the printed figures:
kcal (a lone kJ converts at 4.184, a bare "1130/270" pair splits by that same ratio),
белки/жиры/углеводы (protein/fat/carbohydrate), plus sugar, fibre and sodium when stated (salt × 400
when it is not; a printed натрий, sodium, always wins and they are never summed). Sub-rows — "в том
числе сахара" ("of which sugars"), "насыщенные" ("saturated") — cannot answer for their parent
macro, the "100" of a "на 100 г" ("per 100 g") header is not a value, the %-of-daily-norm column is
ignored, and figures physics forbids per 100 g (белки 250 г, 2 700 ккал: protein 250 g, 2 700 kcal) are dropped as misread
OCR. The basis is the label's own: per 100 g as EAEU/EU packets print, or the portion a Japanese
"(190g当たり)" ("per 190 g") marker names, whose grams land in the serving field. The reading fills
the meal form and the person types the two things print cannot know, the product's name and the
grams eaten. Not ML Kit, whose text recognizer has no Cyrillic model and arrives with Play
Services' network transport; not a `.tflite`, because no Cyrillic OCR exists in that form. The cost
of the choice made is ONNX Runtime's native library on each of the two ABIs that ship — 14.1 MB on
arm64, 9.0 MB on 32-bit, built here without telemetry and with only the operators the bundled
models use (`tools/onnxruntime-android`; Microsoft's own AAR's is 33.0 and 23.3 MB, and from 1.29
it compiles in a telemetry client) — plus 39 MB of models, paid because Tesseract, which costs half
that, reads half as many labels. The parser's dictionaries speak a whole European shelf — Czech,
Slovak, Hungarian, Polish, German, Romanian, the Baltic three, Bulgarian, Croatian, Slovene and
Ukrainian row names in the diacritic-stripped forms OCR actually returns ("Bilkoviny", "Sul",
"Weglowodany": Czech protein, salt, Polish carbohydrate) — and the energy figure is resolved once
from every candidate the text offered, the kJ/kcal pair ratio arbitrating when OCR mangles a unit.
`NutritionLabelTest` holds fixtures of layouts real packets print: the Russian table, the inline EU
line, the two-column portion table, kJ-only imports, the salt-vs-sodium pair, the Czech table, the
praline box whose kcal lost its unit, and Japanese cartons exactly as OCR read them — half the
vocabulary drowned, the kcal survived. They are joined by the US panel, whose "Serving size 2
tablespoon (32g)" makes its figures that serving's and not 100 g's — filing peanut butter's 195 kcal
per 32 g as per 100 g is a threefold lie in the diary — except where the table prints its own
per-100 g column, which is then the column the figures came from.

The reader also survives harder photographs. A label written as one running line — "エネルギー
123kcal/たんぱく質 5.8g/…" ("energy … protein …") on a drinking-yoghurt carton — wraps wherever the
packet runs out of width, so a line ending in exactly one unanswered nutrient hands it to the next
line, which pays only if it leads with a number; a line opening with words is a new row, not a
continuation. OCR's digit impostors are cured only where a digit stands beside them — a
convenience-store sticker's "21ЗК", with a Cyrillic З, reads 213 again — and a bottled tea's
"Okcal" is a lost zero, believed, because zero calories is a reading and not an absence. The
serving marker speaks plain-kana あたり and bracketed "1本(110ml)当たり" ("per bottle (110 ml)")
besides 当たり ("per"), and たんぱく matches its OCR shadows たんばく and たんぽく.

The ladder (`LabelReader.kt`): each of four quarter turns is detected once and read in up to three scripts, and each read is offered to the parser
three ways — box by box, reassembled into printed lines, and as a table. The best attempt is kept
rather than the first that found anything — energy plus two macros still ends the climb early — and
a serving marker or a per-100 g basis is believed from any pass, since every pass photographed the
same label.

### 20. Logging the things people do not talk about

Alcohol, tobacco, the toilet and sex are each a fact about a body that a diary is worse without
and that a person will not enter twice. Four rules follow from that.

**Each subject gets its own door.** Alcohol and tobacco each have a screen of their own
(`AlcoholScreen` and `SmokingScreen`, both in `HabitsScreen.kt`), and so do the bathroom and the
intimate log; all four are separate entries on the add sheet. This is not tidiness — a list that
puts a stool entry directly above a sex entry is a list nobody opens in company, and the age gate
on `IntimateScreen` only means something if there is a screen it can refuse to open.

**Every tap answers, and then stops accepting taps.** `QuickLogState` shows a tick in the middle of
the screen and shuts the one button that was pressed — keyed by what it logs — for three seconds
(`DUPLICATE_TAP_WINDOW_MS`); the other tiles stay live. A line of text at the bottom of a scroll is
not an answer, and a button that has not visibly changed invites a second press. A duplicated
cigarette or a duplicated visit is invisible afterwards — the row looks exactly like a real one —
so the guard has to be at the press, not in a later reconcile. The database enforces the same
window inside the transaction that writes the row (`StepsDb.logWater`, `logUrination`, `logAlcohol`,
`logSmoking`), because a widget tap — water, a cigarette — and a screen tap are separate coroutines.

**Health Connect gets what Health Connect has a type for.** A partnered act is a
`SexualActivityRecord`, which carries protection and nothing else. There is no record type for
tobacco, for a stool, for a visit, or for masturbation; those stay in Room, and the screens say so
rather than implying a sync that is not happening.

**The advice these produce never reaches a notification.** `AdviceTopic` carries an `inAppOnly`
flag and only `ACTIVITY` is false, so steps and inactivity can buzz a lock screen while
hydration, bowel, tobacco and sexual advice can only be read inside the open app. The rules
themselves are `BodyAdvice.kt`, and each cites what it is derived from — Rome IV for constipation
and chronic loose stools, ICS/IUGA for voiding frequency against intake, WHO for tobacco, Rider
et al. (*Eur Urol* 2016) for ejaculation frequency, WHO/EFSA's 25 g for fibre. Where the
literature has no finding, none is invented: there is no women's counterpart to the prostate
association, so the female rules are driven by symptoms the person ticked. Frequency of
masturbation produces a neutral ICD-11 6C72 note and not a scolding, because the diagnostic
criterion is loss of control and distress, not a number.

### 21. Onboarding

`OnboardingScreen.kt` coordinates ten pages:

1. **Greeting** — “Hello” cycles through the sixteen supported languages. Tap to continue; the
   privacy-policy link is independently accessible.
2. **What we ask you for** — what the profile questions are for, said before they are asked.
3. **Date of birth** — day, month and year wheels, standing in the order the phone's language
   writes a date and bounded to valid dates. They open blank, and Next waits until all three are
   turned to; the age gate the date feeds is `AgeGate.kt` (see §20).
4. **Sex** — two cards, neither chosen beforehand; Next waits for one.
5. **Height** and 6. **Weight** — vertical and horizontal rulers. A strong throw coasts until
   touched or the scale ends; a small throw settles. Velocity controls speed. Mechanical ticks
   respect silent/vibrate mode and Android’s sound-effects setting. Units start from the phone's
   setting, and both pages carry a metric/imperial switch. A ruler left on the 170 cm or 70 kg it
   opens on starts the profile and is not logged as a measurement.
7. **Allergies** — preserves the distinction between known allergies, none known and never tested.
8. **Daily step goal** — starts on the age band's suggestion and shows the distance it means at the
   person's stride.
9. **Optional access** — activity recognition, notifications, nearby Bluetooth devices, route
   location, exact reminders, full-screen alarms (Android 14+) and usage access for sleep detection.
   Each row opens its Android request or settings page and refreshes its status on return. Declining
   access never blocks Continue. Motion/pressure sensors need no additional runtime grant. Health
   Connect is asked for next, behind the age gate, and the page says above its rows what the
   exchange imports and writes back before Next opens the request; its values prevent duplicate
   initial measurements, rather than replacing the answers already entered.
10. **Shall we begin** — the finish reads back the step goal, the resting energy and the BMI. Start
    gathers the drifting symbols into a ring that tightens to a point and opens, in the theme's
    surface colour, into the home screen; the profile is saved on the way, before the body
    measurement the answers came to (`finishOnboarding`), so a failed save leaves nothing to repeat.

`OnboardingChrome.kt` keeps Back and a progress line of one segment per question still above the
pages while they change, shows each answer given so far as a small tag under that line, and anchors
the page's one action at the bottom. The headings carry no illustrations.
`OnboardingReady.kt`, `OnboardingAccess.kt` and `RulerMotion.kt` isolate the final animation,
permission handling and ruler motion. The default icon and primary color is **#7094EE**, with blue
shades across light and dark themes; user-selected theme overrides remain available.

The energy goal is not asked here: it defaults to MAINTAIN and is set in the profile, where every
other thing about the body already is.

Validation: `check` for Play and Sideload, whose JVM tests include ruler motion, bounds, velocity
fallback and touch cancellation; the count is not kept here (§11). With `-Pscreens` the screenshot
tests on Robolectric (§11) write these pages into `ScreensTest/onboarding/`; the home cards' sweep
on an emulator (`tools/screens-test.sh`, `docs/SCREENSHOT_TESTING.md`) writes its captures into
`ScreensTest/` itself. Both are git-ignored.

## Caveats
- **Exact overlap exception unnamed**: Google documents that overlapping same-origin `StepsRecord`s "shouldn't have overlapping time" and are "a common cause of write failures," and the androidx client throws `IllegalArgumentException` for validation and surfaces `HealthConnectException` for service errors — but no single doc names the exact exception thrown *specifically* for an overlap. The app writes nothing that overlaps (§3.3).
- **OEM behavior varies**: step-sensor batching/latency and background-kill aggressiveness differ across Samsung/Xiaomi/Huawei (dontkillmyapp.com keeps the list). Samsung's commitment to honour foreground services on One UI 6+ is a stated policy.
- **Health Connect availability on de-Googled ROMs is not guaranteed**; some users install the provider manually, others cannot. The app never depends on it.
- **Platform APIs evolve**: Android 16's granular health permissions, JobScheduler quota specifics and Play Console health-declaration rules change; the Play-specific ones bear on the play build only.
- **Sea-level altitude needs Android 14** (§4.2): where a fix carries none — always on Android 13 — a route's altitude is the raw WGS84 ellipsoidal height, and the export says so.

### Caveats specific to the shipped app

- **The motion gate's thresholds come from a handful of recordings** — pocket walks, a bus ride,
  two stair climbs (§14.6) — on few phones. `GateConfig`'s own KDoc calls them starting points, and
  every threshold is a named field so it can be retuned per device family with §15's sensor screen.
  The gate is the one part of the app that can be *silently* wrong.
- **The motion gate's tests are mostly synthetic.** `MotionGateTest` drives it with generated
  waveforms, a few of them shaped on figures from real recordings, and that is not the same thing
  as a pocket. The synthetic signals are also cleaner than reality: `|a|` rectifies the
  negative half of a sinusoid, so a 40 m/s² drive reads as ~14.5 m/s² of standard deviation, and
  real gait is not a sinusoid at all.
- **A deliberate shake at walking cadence and walking amplitude still counts as walking**, and no
  accelerometer-only method fixes that (§14.6).
- **The gate's rejections are not kept.** Credited and rejected counts live in memory and die with
  the process; a trace recorded on §15's screen is the only record of a rejection. Not built: a
  rejected-steps column on `days`.
- **The step counter's two-second batching bounds gate latency.** A step is judged when its batch
  arrives, up to 2 s after it was taken (§14.6).
- **Continuous accelerometer sampling is the price of the gate.** Unbatched 25 Hz is not free.
- **Steps taken between a reboot and the service's first counter reading are not credited.** The
  first value after boot becomes a baseline (`REBOOT_BASELINE`), so they are lost rather than
  misfiled; `BootReceiver` keeps that window short (§2.2).
- **The map is scenery, not navigation** — land, water, relief, forests and towns, but no streets,
  no labels and no scale bar (§17.3).
- **The emulator cannot test the pedometer path**: its system images expose no `TYPE_STEP_COUNTER`,
  so everything from hardware counter to database row is covered by unit tests only. It *can* test
  the gate, the sensor view and track recording — see §18.
