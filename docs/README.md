# The repository's documents

What each document is, and how far to trust it. A document is one of two things:

- **Held by tests** — a JVM test reads the file and fails when it and the app part.
- **Current** — describes the app as built. Nothing but its next reader holds it there: change it
  in the commit that changes the code.

Paths are from the repository root.

## Root

| Document | Status | What it is |
|---|---|---|
| `README.md` | Current | The front page: what the app is, how to install and build it, and the licences |
| `README.ru.md` | Current | The front page in Russian |
| `README.zh-CN.md` | Current | The front page in Simplified Chinese |
| `CHANGELOG.md` | Held by tests (`ChangelogVersionTest`) | What changed in each version, and why |
| `LICENSE` | Held by tests (`DocsMapTest`) | The text of the GNU General Public License, version 3. The README's Licence section says what it covers and what it does not |

## docs/

| Document | Status | What it is |
|---|---|---|
| `DESIGN.md` | Current | How the app is designed and built: the status table at the top is the index, and what is not built is marked "Not built" |
| `BUILDING.md` | Current | Building from a clean clone, the dependency checksums, release builds, the signing key and how a tag becomes a release |
| `privacy-policy.html` | Held by tests (`PrivacyPolicyTest`, `PrivacyPolicyBlocksTest`, `PrivacyPolicyHealthTableTest`) | The published privacy policy. `app/src/main/assets/privacy-policy.html` is the same file byte for byte, and an edit goes into both. The website serves its own copy, at the address of the `canonical` link; that copy is not in the repository, so no test here can hold it |
| `REPORT_COMPARISONS.md` | Held by tests (`ComparisonCatalogTest`) | The comparisons on the doctor's sheet |
| `SECURITY_MODEL.md` | Current | What the app lock, the screenshot block, database encryption and a sealed backup stop, and what they do not |
| `LAB_IMPORT.md` | Current | Reading lab results off photographs and PDFs |
| `integrations.md` | Current | The Bluetooth integrations |
| `STEP_DETECTION.md` | Current | How steps and motion are detected; ideas not built are in a section of their own |
| `levels.md` | Current | The rules of levels and XP |
| `nutrition-advice.md` | Current | The rules of the daily nutrition feedback |
| `SCREENSHOT_TESTING.md` | Current | The rule for checking value-bearing screens with real screenshots |
| `MODELS.md` | Held by tests (`DocsMapTest`) | The networks that ship in the assets, by their bytes and SHA-256 |
| `play-release-checklist.md` | Held by tests (`PlayReleaseChecklistTest`, `DeviceTestsTest`) | What Google Play asks for that the build cannot prove; one pass per release |
| `medical-export/EXPORT_MODEL.md`, `COUNTRY_PROFILES.md` | Current | The doctor's report: its pipeline and the country presets |
| `medical-export/SPECIALTY_PROFILES.md`, `PRIVACY.md` | Held by tests (`SpecialtyProfilesDocTest`) | The doctor's report: the specialty presets, and what it can carry |

## Beside the code

| Document | Status | What it is |
|---|---|---|
| `app/src/main/assets/chat/NOTICES.md` | Held by tests (`DocsMapTest`) | The licence notices of the chat reader, shipped in the app |
| `tools/onnxruntime-android/README.md` | Held by tests (`OnnxRuntimeBuildTest`, `OnnxOpsConfigTest`) | How the ONNX Runtime AAR in `app/libs` is built |
| `app/src/main/assets/integrations/banglejs2/README.md` | Current | The Bangle.js 2 watch app and the protocol the phone reads it by |

## Keeping this page true

A document added to the root, to `docs/` or to one of its folders gets a row here in the same
commit. Before moving a document, grep the tests, the build scripts and the other documents for
its path.
