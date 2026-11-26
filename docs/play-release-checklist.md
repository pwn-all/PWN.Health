# Play release checklist

Everything Google Play asks for that this repository can neither fill in nor prove. The build, the
tests and the lint gate cover the APK; the forms in Play Console are filled by hand and drift
silently — a new `uses-permission` in the manifest, a new Health Connect type, a changed listing
sentence — and the review that catches the drift arrives weeks later as a rejection. So: one pass
through this file per release.

The rule that makes the file worth keeping: **a new permission or Health Connect type in
[AndroidManifest.xml](../app/src/main/AndroidManifest.xml), a new store asset, or a changed
sentence in the store listing (kept outside this repository) or
[docs/privacy-policy.html](privacy-policy.html) is not done until the matching Console form is
updated and the line below says so.**

## 1. Before anything is uploaded

- [ ] `CHANGELOG.md` opens with `## <versionName>` and `versionCode` is one above the last
      release's (2.0 is 79) — `ChangelogVersionTest` enforces it, a bump opens a new section rather
      than renaming the old one.
- [ ] `./gradlew check` is green in a **fresh** worktree of the exact commit (a reused tree reports
      stale lint successes).
- [ ] `./gradlew connectedSideloadDebugAndroidTest` is green on an attached arm64 phone or emulator
      (the debug build carries no x86 libraries), on the exact commit. `check` and CI compile
      `app/src/androidTest` and run none of it: this is the only run of the migrations on a real
      Android's SQLite, of the database encryption with SQLCipher and the Keystore
      (`DatabaseEncryptionDeviceTest`), of the shipped models on the phone's runtime and of a
      reminder through the system. The run uninstalls the app when it ends, so not on a phone
      whose record matters.
- [ ] The signed release is built with the keystore outside the checkout (`~/keys/`) and both
      artefacts land in `releases/`: the `.aab` for Play, the `.apk` for sideload.
- [ ] The release APK is **installed on a device and the product-label reader opened**: R8 can
      strip the ONNX runtime classes, and nothing but a device shows it. The build has a task
      that guards the keep rule; the device is the second witness.
- [ ] Native libraries are 16 KB page-aligned (`zipalign -c -P 16 -v 4 <apk>`).
- [ ] The bundle's page in Play Console gives the base module's compressed download under Play's
      200 MB. Nearly all of it is the assets, about 174 MB, which barely compress.
      `AssetsBudgetTest` holds the assets under 185 MB; a model or map that needs more needs Play
      Asset Delivery first.
- [ ] The privacy policy at `https://pwn-all.com/app/health/privacy` is byte-for-byte
      `docs/privacy-policy.html`, and its effective date is not older than the last change to the
      data the app handles.
- [ ] §12 of the policy and the line under the database-encryption switch (`db_encrypt_body`)
      promise no more than the key's `KeyGenParameterSpec` in `DatabaseKey.kt` gives; no test can
      hold the words to it, so the three are re-read together whenever that spec changes.

## 2. App content — the declarations

Each is a form under *Policy → App content* in Play Console. The answer the repository supports
is written beside it; if the app changed, the answer may have too.

### Health apps declaration

- The app is a health and fitness app that uses Health Connect. Declare it as such.
- Every Health Connect **read** type must be justified by a feature the reviewer can see on screen;
  a blanket "synchronisation / migration from another app" is not accepted. The rationale screen (`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`) lists
  each type beside the feature that shows it — use the same wording in the form.
- `READ_HEALTH_DATA_HISTORY` (records older than thirty days) needs its own sentence: the import
  from another app reaches back a year for weight, sleep, workouts, meals and steps.
- Onboarding asks for the same request the devices screen asks for — every declared type, less
  `READ_SEXUAL_ACTIVITY` and `WRITE_SEXUAL_ACTIVITY` for anyone under sixteen, whose app does not
  render that screen at all (`AgeGate.kt`). There is no shorter first-run list to describe: the
  per-type justifications above are the answer, and they are the rationale screen's own sentences.

### Data safety

- No `INTERNET` permission: the app transmits nothing by itself. WorkManager's
  `ACCESS_NETWORK_STATE` is removed in the manifest (`tools:node="remove"`), and
  `NoNetworkPermissionTest` fails if it, `INTERNET`, `ACCESS_WIFI_STATE` or `CHANGE_NETWORK_STATE`
  is in the merged manifest. `WAKE_LOCK` is the app's own — held while a set is counted or a sensor
  trace records — and is not a network permission.
- Data leaves the phone only by the person's own action: a backup file, a share, a Health Connect
  write to an app they authorised, a device transfer, a diagnostics report they send. The form's
  "collected" and "shared" answers are *no* for every category; the free-text explains the
  user-initiated paths in the words of §8 of the policy.
- The exchange with Health Connect hands records to another app on the phone, which Play counts as
  *sharing* unless the person starts it or agrees to it after a prominent disclosure. The "no"
  rests on that consent: the request is Android's own permission screen, at the end of the
  first-run questions or from the Health Connect card when asked, where each type is approved on
  its own (§6 of the policy), and nothing moves until one is. Say so in the free text. This is the
  app's reading of Play's definition, not one Google has confirmed.
- No account, so no account-deletion URL is required. No advertising ID is used or requested.

### Target audience and content

**The app is for every age.** The date of birth asked on the
onboarding's neutral age screen (nothing prefilled, no age limit mentioned; once a date is set the
page shows the age it gives) removes — not locks — the
alcohol and tobacco logging and the tobacco widget below 18, and sexual-activity logging below 16
(`AgeGate.kt`; §14 of the policy; the listing's own sentence). The food catalogue's alcoholic drinks
(beer, wine, spirits) stay searchable at every age.

- Declare the age groups the app is meant for. Including anyone under 13 puts the app under the
  Families policy: no ads (none), no personal data collected from children (none leaves the phone),
  content gated behind a neutral age screen (it is), and the content rating below must agree.
- If the age groups change, §14 of the policy and the listing's own sentence must change first.

### Content rating (IARC questionnaire)

Answer what the app *does*, gated or not: it lets an adult log alcohol and tobacco use and sexual
activity, without depicting any of it; it holds health data. Mention the age gate in the free text.
A rating that contradicts the target audience is a rejection.

### Permission declarations

| Permission | Why the app has it | What the form wants |
|---|---|---|
| `FOREGROUND_SERVICE_HEALTH` + `_LOCATION` (`foregroundServiceType="health|location"`) | all-day step counting; GPS route recording while walking | a short video of the route recording running with the screen off |
| `ACCESS_FINE_LOCATION` (precise) | route recording, splits, altitude | "core feature: recording a walk/run"; no background location is requested by the play build |
| `ACCESS_BACKGROUND_LOCATION` | **not in the play build.** Declared only in `src/sideload/AndroidManifest.xml`, for Work → Settings → Start by location, which the play build therefore does not show | nothing while it stays out. To bring the feature to Play: file the Location permissions declaration first — the in-app disclosure (the "Location all the time" card shown before the request) and a video of turning it on, the card, Android's "Allow all the time" screen and a notice of an automatic start — and move the line into the main manifest only once it is approved; until then any play `.aab` that declares it is held, whoever turns the feature on |
| `FOREGROUND_SERVICE_LOCATION` (WorkPlaceCheckService) | in the play build, only the route recording's use (the check's service never starts without the permission above) | the route recording video |
| `USE_FULL_SCREEN_INTENT` | medicine doses ring like an alarm | "alarm / medication reminder" |
| `SCHEDULE_EXACT_ALARM` | doses at a clock time; the workplace checks | same; doses degrade when the permission is refused, the workplace checks do not run without it |
| `PACKAGE_USAGE_STATS` (Usage access) | the sleep estimate from screen use; Screen time, which reads the foreground time of a fixed list of apps (`TrackedApp`) and drops every other app's name as the log is read | "when the screen was used and how long the listed apps were open, never what was on the screen" — the sentence is in the listing; §5 of the policy names the list |
| `ACTIVITY_RECOGNITION` | the pedometer | runtime permission, explained on the steps page |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | scales, cuffs, pods, machines | `neverForLocation` is set on the scan permission |
| `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC` | reminders, restart after boot, app lock | no declaration form |
| `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `WAKE_LOCK` | asked together with precise location, and alone by an appointment's Place field; the foreground services above; keeping the processor awake while a set is counted or a sensor trace records | no declaration form |

No photo or video permission is requested — the photo picker and the camera intent are used
instead — so the Photo and Video Permissions declaration stays "does not apply".

### The rest

- Ads: none. News app: no. Government app: no. Financial features: none. COVID-19: no.
- App access: no login. The reviewer's instructions are the onboarding — tap the word, Next past
  "What we ask you for", pick a date of birth (a date over 18 shows every feature), sex, height,
  weight, Skip on the allergy question, Next on the step goal and on the permissions page, "Don't
  allow" on the Health Connect sheet, Start.

## 3. Store listing assets

The store art and the listing text are not part of this repository.

| Asset | What it is | State |
|---|---|---|
| Icon 512×512 | PNG | present |
| Screenshots, phone | eight per language (1080×1920, 9:16), for all 16 app languages | present |
| Feature graphic 1024×500 | one per language | present |
| Listing text | en-US | the "NOT A MEDICAL DEVICE" block uses the policy's words |
| Privacy policy URL | `https://pwn-all.com/app/health/privacy` | must equal `docs/privacy-policy.html` |
