# Offline Bluetooth integrations

## Hard project boundary

Only an exchange that the app carries out locally with a device over Bluetooth/BLE counts as an
integration. A manufacturer account, REST API, cloud, WebView, vendor companion app and the
`INTERNET` permission are not allowed.

Health Connect remains a separate on-device IPC bridge. It is useful for exchange between apps that
are already installed, but Fitbit, Samsung Health, Garmin Connect, Withings, Polar Flow, Zepp, Oura,
Whoop or Mi Fitness writing to Health Connect is not a direct BLE integration with their devices,
so these brands are not listed as Bluetooth integrations.

Statuses:

- **runtime ready** — scan/connect/subscribe/parse/save are implemented;
- **parser/asset only** — the format is worked out, but the Android transport is not wired in.

`runtime ready` does not mean compatibility with every device that advertises the same UUID. The
code has no status catalogue: a status is recorded only in this file. Every integration in the next
table is `runtime ready`.

## What is implemented in the Android runtime

| Integration | Basis | Received locally | Notes |
|---|---|---|---|
| Bluetooth SIG Weight Scale Service (WSS) | official adopted SIG profile | weight, height, BMI, user, timestamp | GATT indication; bonding when the device requires it |
| Bluetooth SIG Body Composition Service (BCS) | official adopted SIG profile | fat, muscle, water, impedance, weight/height | multipart indication and an increased MTU |
| Bluetooth SIG Heart Rate Service (HRS) | official adopted SIG profile | HR, RR, contact, energy | live notification |
| Bluetooth SIG Running Speed and Cadence (RSCS) | official adopted SIG profile | speed, cadence, stride, distance | local workout recording |
| Bluetooth SIG Cycling Speed/Cadence (CSCS) | official adopted SIG profile | wheel/crank revolutions, cadence | rollover-aware accumulator; distance is not calculated — the wheel circumference is not guessed |
| Bluetooth SIG Cycling Power Service (CPS) | official adopted SIG profile | power, wheel/crank data, cadence | merged with CSCS on the same connection |
| Bluetooth SIG Fitness Machine Service (FTMS) | official adopted SIG profile | treadmill/rower/bike live data | read-only passive collection, no control of the machine |
| Bluetooth SIG Blood Pressure Service (BPS) | official adopted SIG profile | systolic/diastolic/MAP, pulse, timestamp | strict IEEE-11073 parser |
| Bluetooth SIG Pulse Oximeter Service (PLXS) | official adopted SIG profile | spot/continuous SpO₂ and pulse | spot-check and continuous measurement characteristics |
| Bluetooth SIG Health Thermometer Service (HTS) | official adopted SIG profile | temperature, type, timestamp | indication |
| Bluetooth SIG Glucose Service (GLS) | official adopted SIG profile | stored glucose records + context | mandatory bond, RACP Report All, 30 s timeout, batched idempotent save |
| Concept2 PM5 | published Concept2 BLE specification | rower/SkiErg/BikeErg status, distance, pace, power, cadence, HR | read-only notifications; exact machine-type enum |
| Keiser M Series (M3i/M3iX/M5) | published Keiser M Series broadcast specification | cadence, power, kcal, time, distance, gear, HR from the console | **broadcast-only**: the console is TX-only, there is no GATT connection; manufacturer data 0x0102 is read from the advertisement |
| Xiaomi Mi Body Composition Scale 2 | independently documented community protocol | stable weight advertisement, impedance | exact adapter (`MIBFS`/`MIBCS` names), fixed 13 bytes |
| Xiaomi Mi Smart Scale v1 | independently documented community protocol | stable weight advertisement | exact adapter (`MISCA`/`MI SCALE` names); the generic WSS parser refuses packets with Xiaomi's extra flag bits |
| COLMI R02 / R06 / R10 | clean-room from an independently published MIT wire format | battery and today's 15-minute step history | exact local-name + proprietary service + valid handshake; no binding/key |
| Bangle.js 2 (PWN.Health watch app) | the app's own v1 protocol over Espruino's Nordic UART Service; watch script in `assets/integrations/banglejs2/` | the day's step total, heart rate (only when the watch is at least 80 % confident), battery | exact `Bangle.js xxxx` name + NUS + a valid v1 frame; one `PWN_HEALTH 1` request, a 5 s answer timeout, then disconnect; no bonding, the link is not authenticated; the watch app is installed through the Espruino App Loader and has to be open |

A running pod, a fitness machine, a Concept2 PM5 or a cycling sensor that also offers the Heart
Rate Service has its heart rate collected on the same connection. For the standard profiles the
available Device Information, Battery and Glucose Feature are read as well. The device's identity
(manufacturer, model, firmware, battery) is shown on the connection card, but is not stored as a
separate entity.

### Keiser: two format traps

- **Review packets.** Byte 2 is the only difference between a live ride and its replay. After a
  workout the console keeps broadcasting the finished ride byte for byte, so the decoder accepts
  only `dataType == 0` and `128..227`, and discards every other value, the review values `255` and
  `1..99` included. Without this check the app would record a workout that nobody is doing.
- **Two-byte offset.** Keiser's documentation numbers the fields from the company id, which Android
  has already cut off (`ScanRecord.extractBytes(..., currentPos + 2, ...)`). Carrying the indices
  over from the published parsers as they are reads the power from the calorie bytes and gives
  plausible nonsense. All offsets in `BluetoothKeiserM3i.kt` are against the array that
  `getManufacturerSpecificData(0x0102)` returns.

The version bytes are the only BCD in the packet (`0x30` = 6.30, not 6.48); this is recognised there
alone. The distance-unit flag is the high bit of the word, not part of the value: Keiser's own C#
and TypeScript examples do not mask it; here it is masked. `neverForLocation` does not affect the
packet — the platform's denylist contains only Eddystone and iBeacon, and Keiser is neither.

### Storage and de-duplication

- Medical data is stored as structured versioned JSON, not only as a text note.
- GLS saves all the records of a successful RACP procedure atomically and does not insert the same
  `source + payload` again.
- COLMI keeps the daily total separately for each pseudonymous source ID; the raw MAC does not reach
  the database or the backup. But the source ID is an unsalted 64 bits of SHA-256 of "model:MAC",
  the same on every installation (it is the key of `direct_ble_step_days`, which has to be found
  again after a backup is restored on another phone), so the ring's address can be recovered from
  the ID by brute force: it is a pseudonym, not anonymisation. The IDs of the other devices
  (`BLE_…`) are salted with an installation secret.
- The day's total takes the maximum of the phone's steps, Health Connect and the direct BLE sources.
  This is a conservative guard against counting the same wear twice, not an attempt to add up two
  parallel trackers.
- Direct BLE totals are not exported back to Health Connect as phone steps — this rules out a
  feedback loop.
- The JSON backup carries the direct-source days with their provenance and the structured
  measurement payloads.

## Official channels without the Garmin SDK

The channels built are the ones with a published local wire contract that need no vendor SDK: the
adopted Bluetooth SIG profiles in the table above, Concept2 PM5 and the Keiser M Series broadcast.

The app does not use the Garmin Health SDK: it is a vendor SDK, not an open consumer BLE
specification. Nor is any of the following passed off as a direct API:

- Garmin Connect, Polar Flow, Samsung Health, Fitbit, Withings, Zepp, Oura, Whoop, Mi Fitness;
- Huawei Health/HMS and Wear OS companion routes;
- a vendor SDK, if it requires partner registration, a proprietary binary or network activation;
- "brand support" only because a particular model of the brand speaks standard HRS/FTMS.

A standard profile is not tied to a brand: for example, a compatible Polar chest strap may work
through HRS, but that does not mean support for the history of Polar watches.

## Community/Gadgetbridge tactics

Gadgetbridge shows that a class of devices can work without the cloud, but its AGPL-3.0 code is not
manufacturer documentation. No Gadgetbridge code is copied or translated into the implementation.

Every family adapter follows the same rules:

- it is written from an official specification or an independent description of the wire format
  under a compatible licence;
- it is gated on an exact model/name/service/characteristic; a similar UUID or a brand name is not
  enough, and the gate is not widened without documents or captures that show the format;
- its parser is transport-independent and unit-tested on own or permitted fixtures;
- on Android, GATT operations are serialised, and the CCCD result, bonding, timeouts and
  disconnects are checked;
- it reads data only: firmware update, account binding and destructive commands are out of scope.

### Implemented community adapter: COLMI

Only the exact names `R02_XXXX`, `R06_XXXX`, `R10_XXXX` are supported; after connecting, the service
`6e40fff0-b5a3-f393-e0a9-e50e24dcca9e` is checked again. The runtime performs a battery handshake,
requests the current day's history, checks the 16-byte checksum/order of the parts and saves the
steps with provenance. A history whose every record is more than a day away from today is refused
as an unset ring clock. R03, R09, arbitrary QRing rebrands and similar services are not admitted
automatically.

Live HR/SpO₂ parsers exist in the protocol core (`ColmiRingProtocol.kt`), but the runtime does not
start them: continuous start/continue/stop would need a lifecycle of its own. "Blood pressure",
"glucose" and stress/fatigue values from COLMI are not implemented — they cannot be passed off as
medical measurements without a confirmed sensor path.

### Bangle.js 2

The phone asks the app's own watch program once for a versioned snapshot over the Nordic UART
Service and shows it on the Devices page; Save files the watch's step total for that day beside the
phone's own count — the day shows the larger of the two, never their sum — and the heart rate when
the watch sent one. The figures wait for Save because the link is not authenticated. The watch is
identified by a salted source ID. The script is installed through the Espruino App Loader
(`assets/integrations/banglejs2/README.md`).

### Not built: candidates

| Candidate | Now | What is needed before implementation |
|---|---|---|
| Pebble/Rebble | not started | own watch app + a documented phone transport; this is not a generic history endpoint |
| CMF/Huawei/Amazfit/Garmin watches | rejected as "just Gadgetbridge" | a permitted specification or clean-room captures; the AGPL implementation must not be carried over |
| R03/R09/QRing rebrands | rejected | exact captures and a separate model/firmware adapter |

## Protocol sources

- Bluetooth SIG adopted specifications and assigned numbers:
  <https://www.bluetooth.com/specifications/specs/> and
  <https://www.bluetooth.com/specifications/assigned-numbers/>.
- Glucose Profile 1.0.1:
  <https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/GLP_v1.0.1/out/en/index-en.html>.
- Concept2 software development and PM5 Bluetooth Smart Interface Definition:
  <https://www.concept2.com/support/software-development>.
- Espruino Bangle.js API, BLE UART and security:
  <https://www.espruino.com/ReferenceBANGLEJS>, <https://www.espruino.com/BLE%20UART>,
  <https://www.espruino.com/BLE%20Security>.
- Independent MIT reference for COLMI R02/R06/R10:
  <https://github.com/tahnok/colmi_r02_client>.
- Gadgetbridge as confirmation of offline feasibility, not as copied source:
  <https://codeberg.org/Freeyourgadget/Gadgetbridge>.
