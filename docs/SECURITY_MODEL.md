# Security model

Written for: anyone asking what each protection stops, and what it does not.

The words a person reads are §8, §9, §10 and §12 of the privacy policy (`privacy-policy.html`,
which `PrivacyPolicyTest` holds to the copy in the app); this page is the same model in the code's
terms, and the two say the same thing.

## What is guarded

One person's health record on one phone: the Room database `steps.db`, the photographs kept as
files beside it (`progress/`, `medicines/`, `walk-photos/`, `routines/`, `lab-captures/`), and the
settings in SharedPreferences.

## The outer boundary: no network

Neither flavour declares `INTERNET`, and `ACCESS_NETWORK_STATE` is removed from the merged manifest.
`NoNetworkPermissionTest` reads each built variant's merged manifest, so a dependency cannot bring
the permission in unnoticed. There is no server, no account and no remote party to defend against.
What remains is someone holding the phone, a copy of its storage, a file that has left the phone, a
file brought into it, and other software on it.

Of the app's own components three are exported: the launcher activity, the Health Connect rationale
page, and its permission-usage alias, which asks its caller for `START_VIEW_PERMISSION_USAGE`. The
libraries add a few of their own to the merged manifest, each behind a system permission except the
Health Connect client's service. `ManifestSurfaceTest` holds the whole list, and the declared
permissions, to what the code uses.

## The protections

The first three are switches, off until the person turns them on; the fourth is a choice made at
each export.

| Protection | What it stops | What it does not stop | Code |
|---|---|---|---|
| **App lock** — a strong biometric or the device credential (`BiometricPrompt`), asked for when the app is opened; a new process starts locked, and the app locks again whenever it goes out of sight | Someone who has the unlocked phone in hand opening the app's screens | Home-screen widgets: the launcher draws them, and their taps write without the lock. The medicine alarm panel, which shows over the lock screen and whose "I took it" marks the dose. Notifications. A phone with no screen lock at all: the prompt passes straight through, since otherwise the app would be shut for good. Anyone who can read the app's files | `AppLock.kt` |
| **Block screenshots** — `FLAG_SECURE` on the app's window; a switch of its own, and always on while the lock is on | Screenshots and the recent-apps thumbnail of the app's own window | A widget in a screenshot of the home screen. The alarm panel, which is kept out of the recents list instead | `AppLock.kt` (`secureWindow`) |
| **Database encryption** — SQLCipher over `steps.db`; the key is 32 random bytes made on the phone and stored wrapped by an AES-GCM key in the Android Keystore | Reading the database from a copy of the storage taken away from the phone | Software running as root on the phone: the Keystore key is bound to no screen lock and no unlock, because the step service and the widgets open the database with nobody there. No StrongBox is asked for, so "hardware-backed" is not claimed. The photographs and the settings, which are not encrypted. A device-to-device transfer: the key stays behind, and the new phone sets the database aside unopened (`SetAside.LOCKED`) | `DatabaseKey.kt`, `DatabaseEncryption.kt` |
| **Sealed backup** — a manual export under a password: PBKDF2-HMAC-SHA256, 600,000 rounds, a 16-byte salt, then AES-256-GCM in 1 MiB chunks, each bound to its place in the file | Reading an exported file without the password; a chunk dropped, repeated, swapped or cut off passing as a shorter history | A weak password: the file is made to leave the phone, so guessing offline is the attack, and the rounds and the password's length (eight characters at least) are all that prices it. A forgotten password: it is stored nowhere | `BackupCrypto.kt` |

## Copies the app makes

- **The automatic daily copy** (`AutoBackup.kt`) is plain JSON with the photographs, the seven
  newest kept, in a folder the person chose. It cannot be sealed: no password is stored to seal it
  with. A folder that a cloud app watches is uploaded with the whole record in it every day, as
  §8.2 of the policy warns.
- **Android's backup** (`res/xml/data_extraction_rules.xml`): every domain is excluded from cloud
  backup; a device-to-device transfer copies the database, the settings and the photograph folders,
  and nothing else. The installation's own name, which signs its backups (`installId`), is kept in
  `no_backup` and does not travel; the cache, where photographs wait to be read, never does.
- **Health Connect**, once connected, receives what §6 of the policy lists; after that the record
  is also the platform's to guard.
- **Android's system log** gets warnings only: a constant message and the error Android gave, which
  can name a file (a progress photograph's file is named after its moment), or one of four messages
  that add a Bluetooth protocol token, error code or count, or the chat reader's fixed reason
  word. Never an entry, a measurement or a medicine's name; `LogCallsTest` reads every logging call
  to hold that. Other apps cannot read the log.

## Files brought in

A backup or an export picked for import is treated as something somebody else may have written: a
restored photograph's file name, for one, is derived from its moment and never read from the file
(`Backup.kt`), since `../databases/steps.db` is a name too.

## Not in the model

A compromised or rooted phone, a malicious launcher or keyboard, and whoever the person hands an
exported file or a doctor's report to. The app does not try to hide that it is installed, or that a
dose or an appointment is due: a reminder names neither the medicine nor the doctor, but it does
appear.
