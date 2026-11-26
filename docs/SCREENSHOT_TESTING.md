# Screenshot testing rule

Every change to the home-screen cards (and any other value-bearing UI) is verified with real
screenshots before it ships, swept across both screen sizes and value magnitudes:

- **Devices**: the smallest and the largest current Pixel phone screens —
  **Pixel 9a** (1080×2424 @ 420 dpi, 6.3") through **Pixel 10 Pro XL** (1344×2992 @ 480 dpi, 6.8"),
  one arm64 AVD of each panel (the sweep installs the arm64 APK; `pixel9a` in the example below is
  such an AVD's name).
- **Values**: kilocalories from X to XXXX (**1 … 9 999** — every digit count) and steps from
  X to XXXXX (**1 … 99 999**). Numbers must never clip, wrap, collide with neighbours, or push a
  sibling out of its column at either extreme on either panel.
- **Locale**: Russian (`ru-RU`) — its strings are the longest in the app, so truncation shows
  there first.
- **Cards**: all three of them — the sweep shoots the steps card the carousel opens on, taps that
  card open (it is the one the seed gives a full day of hours, so the opened page's chart and the
  pill naming its fullest hour are drawn against real values), comes back, then swipes to food and
  to calories. Two of the three print kilocalories and the opened page reprints the same figures
  larger, so a number that overruns its column has three more chances to show it.
- **Output**: PNGs land in `ScreensTest/` named
  `<avd>-kcal<K>-steps<S>[-idle][-burn|-food|-open].png` and are reviewed by eye before the
  change is committed.

## How to run

```bash
./gradlew assembleSideloadDebug
tools/screens-test.sh pixel9a
```

The script boots each AVD cold (`-wipe-data`), installs the debug APK, grants the runtime
permissions so no system dialog covers the cards, switches the app to `ru-RU`, then for each
value pair force-stops the app, seeds its Room database directly (profile, `days`,
`hourly_steps`, an `ACTIVITY` journal row sized so the calories card shows the target kcal, and
`state.lastStepAtMs` for the idle-nudge case), relaunches, and captures `screencap` into
`ScreensTest/`. Before each capture it waits for the app's own window to hold focus and for its
loading page to go, and stops rather than photograph anything else. It needs `sqlite3` and
`python3` on the host and finds the SDK through `ANDROID_SDK_ROOT` (by default
`~/Library/Android/sdk`).

Seeding the database rather than driving the sweep from an instrumentation test is deliberate: a
screenshot of the real app exercises the real HomeScreen wiring (standby clock, hour bars,
dialogs), not a re-composed fixture. The device tests in `app/src/androidTest` (`MigrationTest`
among them) are not part of the sweep.
