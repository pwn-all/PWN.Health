#!/bin/bash
# Screenshot sweep of the home cards across phone screens and value magnitudes.
#
# The testing rule (see docs/SCREENSHOT_TESTING.md): every home-screen change is swept on the
# smallest and largest current Pixel screens — Pixel 9a (1080x2424 @420dpi) through
# Pixel 10 Pro XL (1344x2992 @480dpi) — with kcal values from X to XXXX (1..9999) and step
# values from X to XXXXX (1..99999), in the Russian locale (its strings are the longest).
# Screenshots land in ScreensTest/ as <avd>-kcal<K>-steps<S>[-idle][-burn|-food|-open].png:
# the steps card, then the two the carousel is swiped to, then the page a tap opens.
#
# Usage: tools/screens-test.sh <avd-name> [more-avd-names...]
# Requires: the AVD exists, a sideload debug APK is built, sqlite3 on the host.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
EMU="$SDK/emulator/emulator"
# The emulator is booted on a fixed console port and every command below names that serial. Seeding
# force-stops the app and overwrites its database, so a plugged-in phone must never be the device
# adb happens to pick.
PORT=5580
ADB=("$SDK/platform-tools/adb" -s "emulator-$PORT")
# The sideload flavor: emulators have no Play Store, and the home cards are flavor-identical.
# One APK per architecture, so the arm64 one is named rather than assumed — every AVD this sweep
# runs on is arm64, and an x86 image has no build to install.
APK="app/build/outputs/apk/sideload/debug/app-sideload-arm64-v8a-debug.apk"
PKG=com.pwnall.health
OUT=ScreensTest
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"

# K:S:idle — kcal 1..4 digits, steps 1..5 digits, one idle-nudge case.
CASES=(
  "7:8:0"
  "68:74:0"
  "532:7245:0"
  "4620:25000:0"
  "9999:99999:0"
  "532:532:1"
)

# Net kcal of S steps for the seeded 80 kg / 175 cm profile: 0.5 * 80 * S * 0.724 / 1000.
walk_kcal() { python3 -c "print($1 * 0.02896)"; }

hourly_sql() { # $1=day $2=steps -> INSERT rows shaped like a real day (peak at 14:00)
  python3 - "$1" "$2" <<'EOF'
import sys
day, steps = sys.argv[1], int(sys.argv[2])
weights = {8:1,9:2,10:3,11:4,12:5,13:7,14:9,15:10,16:9,17:7,18:5,19:4,20:3,21:2}
total = sum(weights.values())
rows, given = [], 0
for hour, w in weights.items():
    n = steps * w // total
    given += n
    rows.append((hour, n))
# Remainder goes to the 14:00 peak, not the last hour: with single-digit days the leftover
# outweighs every share and a lone full-height bar at 21:00 reads as a rendering bug.
peak = next(i for i, (h, _) in enumerate(rows) if h == 14)
rows[peak] = (14, rows[peak][1] + steps - given)
print(";".join(f"INSERT INTO hourly_steps(day,hour,steps) VALUES('{day}',{h},{n})" for h, n in rows if n > 0) + ";")
EOF
}

seed_and_shoot() { # $1=avd $2=kcal $3=steps $4=idle
  local avd=$1 kcal=$2 steps=$3 idle=$4
  local day now_ms walk activity_kcal
  day=$("${ADB[@]}" shell date +%F | tr -d '\r')
  now_ms=$(python3 -c "import time; print(int(time.time()*1000))")
  walk=$(walk_kcal "$steps")
  activity_kcal=$(python3 -c "print(max(0.0, $kcal - $walk))")

  "${ADB[@]}" shell am force-stop $PKG
  # Pull the DB trio, edit locally, push back a single checkpointed file.
  rm -f "$TMP"/steps.db*
  "${ADB[@]}" exec-out run-as $PKG cat databases/steps.db > "$TMP/steps.db"
  "${ADB[@]}" exec-out "run-as $PKG sh -c 'cat databases/steps.db-wal 2>/dev/null'" > "$TMP/steps.db-wal" || true
  "${ADB[@]}" exec-out "run-as $PKG sh -c 'cat databases/steps.db-shm 2>/dev/null'" > "$TMP/steps.db-shm" || true
  [ -s "$TMP/steps.db-wal" ] || rm -f "$TMP/steps.db-wal" "$TMP/steps.db-shm"

  # A schema drift has to stop the sweep here: sqlite3 prints "no column named ..." on stderr, and
  # unchecked the sweep would go on to photograph the onboarding page six times.
  sqlite3 "$TMP/steps.db" <<SQL || { echo "seed failed — schema drift?" >&2; exit 1; }
INSERT OR REPLACE INTO profile(id,sex,weightKg,heightCm,birthDate,unitSystem,activityLevel,dailyStepGoal,automaticSleepDetection,energyGoal,remindersEnabled,allergies)
  VALUES(0,'MALE',80.0,175,'1996-06-15','METRIC','MODERATE',8000,0,'MAINTAIN',0,'');
DELETE FROM days; DELETE FROM hourly_steps; DELETE FROM journal;
INSERT INTO days(day,steps) VALUES('$day',$steps);
$(hourly_sql "$day" "$steps")
INSERT INTO journal(day,occurredAt,kind,title,subtitle,calories,proteinG,fatG,carbsG,servingG,enteredPer100g,activityCode,note)
  VALUES('$day',$now_ms,'ACTIVITY','Push-ups','',$activity_kcal,0,0,0,0,0,'PUSHUPS','');
INSERT OR REPLACE INTO state(id,bootId,lastCumulative,lastEventNanos,pendingSteps,boutStartedNanos,boutLastNanos,boutConfirmed,rateWindowStartedNanos,rateWindowStartCounter,pendingDay,pendingDaySteps,lastStepAtMs)
  VALUES(0,0,0,0,0,0,0,0,-1,-1,'',0,$([ "$idle" = 1 ] && echo "$now_ms - 3*3600*1000" || echo "$now_ms"));
PRAGMA wal_checkpoint(TRUNCATE);
PRAGMA journal_mode=DELETE;
SQL

  "${ADB[@]}" push "$TMP/steps.db" /data/local/tmp/steps.db >/dev/null
  "${ADB[@]}" shell "run-as $PKG sh -c 'cp /data/local/tmp/steps.db databases/steps.db && rm -f databases/steps.db-wal databases/steps.db-shm'"
  "${ADB[@]}" shell rm /data/local/tmp/steps.db

  "${ADB[@]}" shell am start --display 0 -n $PKG/com.pwnall.health.MainActivity >/dev/null
  sleep 5
  # Not "$( [ ] && echo )": a false test inside a command substitution fails the assignment
  # under set -e and kills the whole run silently.
  local suffix=""
  if [ "$idle" = 1 ]; then suffix="-idle"; fi
  shoot "$avd-kcal$kcal-steps$steps$suffix"

  # The other two cards, and the page one of them opens. All three carry the swept magnitudes —
  # kcal on two of them — so a number that overruns its column does it here as readily as on the
  # steps card, and the opened page prints the same figures again at a larger size.
  local w h
  read -r w h <<< "$("${ADB[@]}" shell wm size | tr -d '\r' | sed 's/.*: //; s/x/ /')"
  local mid=$((w / 2)) card=$((h * 42 / 100))
  # The steps card is opened, not one of the two the carousel is swiped to: it is the one the
  # seed gives a full day of hours, so the opened page's chart, its rulings and the pill naming
  # the fullest hour are all drawn against real values rather than against an empty day.
  await_app
  "${ADB[@]}" shell input tap "$mid" "$card"
  sleep 2
  shoot "$avd-kcal$kcal-steps$steps$suffix-open"
  "${ADB[@]}" shell input keyevent 4
  sleep 2
  swipe_card "$w" "$card"
  shoot "$avd-kcal$kcal-steps$steps$suffix-food"
  swipe_card "$w" "$card"
  shoot "$avd-kcal$kcal-steps$steps$suffix-burn"
}

# Every screencap is of this app or the run stops. A fixed sleep after `am start` is a guess, and
# on the first cold case it is the wrong one: the tap lands on the splash screen, BACK then leaves
# the app, and the four PNGs that follow are of the launcher. A sweep cannot tell you what it
# photographed unless it checks.
shoot() { # $1=basename without extension
  local name="$OUT/$1.png"
  await_app
  "${ADB[@]}" exec-out screencap -p > "$name"
  echo "shot: $name"
}

# Waits for the app's own window to hold focus — not for a number of seconds.
await_app() {
  local waited=0
  until "${ADB[@]}" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*$PKG/"; do
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge 30 ]; then
      echo "the app never took focus — refusing to photograph whatever is on screen instead" >&2
      exit 1
    fi
  done
  # The splash holds focus before the first frame of content is drawn, and the app's own
  # "Loading…" page holds it after that, so focus alone would photograph that page for the steps card.
  local loading=0
  while "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q "Загрузка"; do
    sleep 1
    loading=$((loading + 1))
    if [ "$loading" -ge 30 ]; then
      echo "the app never finished loading" >&2
      exit 1
    fi
  done
  sleep 2
}

# One page of the carousel. A page is about 45% of the width, so the drag covers 30%: past the
# half-page the pager snaps forward from, short of a whole page — a 56% drag plus its fling
# carries two pages, and the file named for the food card would hold the calories one. Short of the
# screen edges too, so the gesture belongs to the pager and not to the system's back swipe.
swipe_card() { # $1=width $2=y
  "${ADB[@]}" shell input swipe $(($1 * 65 / 100)) "$2" $(($1 * 35 / 100)) "$2" 300
  sleep 2
}

for AVD in "$@"; do
  echo "=== $AVD ==="
  "$EMU" -avd "$AVD" -port "$PORT" -read-only -no-window -no-snapshot -no-audio -no-boot-anim -wipe-data >"$TMP/emulator.log" 2>&1 &
  EMU_PID=$!
  for attempt in {1..60}; do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
      cat "$TMP/emulator.log" >&2
      exit 1
    fi
    if "${ADB[@]}" get-state 2>/dev/null | grep -q device; then break; fi
    sleep 2
  done
  if ! "${ADB[@]}" get-state 2>/dev/null | grep -q device; then
    cat "$TMP/emulator.log" >&2
    kill "$EMU_PID" 2>/dev/null || true
    exit 1
  fi
  until [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
  sleep 5

  "${ADB[@]}" install -r "$APK" >/dev/null
  for p in ACTIVITY_RECOGNITION ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    "${ADB[@]}" shell pm grant $PKG android.permission.$p 2>/dev/null || true
  done
  # Russian strings are the longest — clipping shows there first.
  "${ADB[@]}" shell cmd locale set-app-locales $PKG --user 0 --locales ru-RU 2>/dev/null || true

  # First launch lets Room create the schema (onboarding shows; we never screenshot it).
  "${ADB[@]}" shell am start --display 0 -n $PKG/com.pwnall.health.MainActivity >/dev/null
  sleep 6

  for case in "${CASES[@]}"; do
    IFS=: read -r K S I <<< "$case"
    seed_and_shoot "$AVD" "$K" "$S" "$I"
  done

  "${ADB[@]}" emu kill || kill "$EMU_PID" 2>/dev/null || true
  wait "$EMU_PID" 2>/dev/null || true
  sleep 3
done
echo "done: $(ls "$OUT" | wc -l | tr -d ' ') screenshots in $OUT/"
