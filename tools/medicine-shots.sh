#!/bin/bash
# Screenshots of the Medicine tab: the empty summary, the populated summary, and the pages it opens.
#
# The home sweep in tools/screens-test.sh seeds numbers and shoots one screen. This one seeds a
# medicine cabinet — courses, marked doses across the chart's fortnight, an appointment, a past visit
# that winds a screening interval — and then leaves the emulator running, because every page here is
# reached by touching a row rather than by a widget route, and the taps are driven from outside.
#
#   tools/medicine-shots.sh boot <avd>   boot cold, install, grant, ru-RU, profile only (empty tab)
#   tools/medicine-shots.sh cabinet      seed the courses and appointments into the running emulator
#   tools/medicine-shots.sh shot <name>  screencap into ScreensTest/<name>.png
#
# Requires: the AVD exists, a sideload debug APK is built, sqlite3 on the host.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
EMU="$SDK/emulator/emulator"
# The emulator is booted on a fixed console port and every command below names that serial. seed()
# force-stops the app and overwrites its database, so a plugged-in phone must never be the device
# adb happens to pick.
PORT=5580
ADB=("$SDK/platform-tools/adb" -s "emulator-$PORT")
# One APK per architecture, and the AVDs these shots are taken on are arm64, as the home sweep's
# are (tools/screens-test.sh).
APK="app/build/outputs/apk/sideload/debug/app-sideload-arm64-v8a-debug.apk"
PKG=com.pwnall.health
OUT=ScreensTest
mkdir -p "$OUT"

# `cabinet` and `shot` run against an emulator a previous `boot` left up, so they have to check it
# is actually there rather than let adb fall through to an attached phone.
require_emulator() {
  [ "$("${ADB[@]}" get-state 2>/dev/null | tr -d '\r')" = device ] || {
    echo "no emulator on port $PORT — run: tools/medicine-shots.sh boot <avd>" >&2
    exit 1
  }
}

# The database round trip the home sweep uses: pull, edit on the host, push back checkpointed.
seed() { # $1=sql
  local tmp
  tmp=$(mktemp -d)
  "${ADB[@]}" shell am force-stop $PKG
  "${ADB[@]}" exec-out run-as $PKG cat databases/steps.db > "$tmp/steps.db"
  "${ADB[@]}" exec-out "run-as $PKG sh -c 'cat databases/steps.db-wal 2>/dev/null'" > "$tmp/steps.db-wal" || true
  "${ADB[@]}" exec-out "run-as $PKG sh -c 'cat databases/steps.db-shm 2>/dev/null'" > "$tmp/steps.db-shm" || true
  [ -s "$tmp/steps.db-wal" ] || rm -f "$tmp/steps.db-wal" "$tmp/steps.db-shm"
  # -bail: without it sqlite3 reads on past a statement the schema refuses, and a seed that put no
  # profile in is the onboarding page photographed under every name asked for.
  printf '%s\nPRAGMA wal_checkpoint(TRUNCATE);\nPRAGMA journal_mode=DELETE;\n' "$1" | sqlite3 -bail "$tmp/steps.db" ||
    { echo "seed failed — schema drift?" >&2; exit 1; }
  "${ADB[@]}" push "$tmp/steps.db" /data/local/tmp/steps.db >/dev/null
  "${ADB[@]}" shell "run-as $PKG sh -c 'cp /data/local/tmp/steps.db databases/steps.db && rm -f databases/steps.db-wal databases/steps.db-shm'"
  "${ADB[@]}" shell rm /data/local/tmp/steps.db
  rm -rf "$tmp"
  "${ADB[@]}" shell am start --display 0 -n $PKG/com.pwnall.health.MainActivity >/dev/null
  sleep 4
}

case "${1:?usage: boot <avd> | cabinet | shot <name>}" in
boot)
  AVD=${2:?usage: tools/medicine-shots.sh boot <avd>}
  echo "=== $AVD ==="
  "$EMU" -avd "$AVD" -port "$PORT" -no-snapshot -no-audio -no-boot-anim -wipe-data >/dev/null 2>&1 &
  "${ADB[@]}" wait-for-device
  until [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
  sleep 5
  # The seeded timestamps are computed on the host, so the two clocks have to agree about the hour.
  "${ADB[@]}" shell su root setprop persist.sys.timezone "$(readlink /etc/localtime | sed 's|.*zoneinfo/||')" \
    2>/dev/null || true
  "${ADB[@]}" install -r "$APK" >/dev/null
  for p in ACTIVITY_RECOGNITION ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    "${ADB[@]}" shell pm grant $PKG android.permission.$p 2>/dev/null || true
  done
  # Russian strings are the longest — clipping shows there first.
  "${ADB[@]}" shell cmd locale set-app-locales $PKG --user 0 --locales ru-RU 2>/dev/null || true
  # First launch creates the schema; onboarding is on screen and is never shot.
  "${ADB[@]}" shell am start --display 0 -n $PKG/com.pwnall.health.MainActivity >/dev/null
  sleep 6
  TODAY=$("${ADB[@]}" shell date +%F | tr -d '\r')
  seed "INSERT OR REPLACE INTO profile(id,sex,weightKg,heightCm,birthDate,unitSystem,activityLevel,
      dailyStepGoal,automaticSleepDetection,energyGoal,remindersEnabled,allergies)
    VALUES(0,'FEMALE',64.0,168,'1984-06-15','METRIC','MODERATE',8000,0,'MAINTAIN',1,'');
  DELETE FROM days; DELETE FROM hourly_steps; DELETE FROM journal;
  DELETE FROM medications; DELETE FROM appointments;
  INSERT INTO days(day,steps) VALUES('$TODAY',6240);"
  echo "ready: profile seeded, medicine cabinet empty"
  ;;

cabinet)
  require_emulator
  TODAY=$("${ADB[@]}" shell date +%F | tr -d '\r')
  NOW=$(python3 -c "import time; print(int(time.time()*1000))")
  SQL_FILE=$(mktemp)
  # Not SQL=$(python3 <<PY ...): inside a command substitution bash keeps parsing the heredoc for
  # quotes and parentheses, and the Python below is full of both. A file keeps it out of reach.
  python3 - "$TODAY" "$NOW" > "$SQL_FILE" <<'PY'
import sys
from datetime import date, datetime, timedelta

today = date.fromisoformat(sys.argv[1])
now = int(sys.argv[2])
NAMES = {1: "Метформин", 2: "Магний B6", 3: "Омега-3"}
DOSES = {1: "1 таблетка", 2: "2 капсулы", 3: "1 капсула"}


def at(day, minute):
    return int(datetime.combine(day, datetime.min.time()).timestamp() * 1000) + minute * 60_000


def dose_row(course, slot, day, minute):
    return (
        "INSERT INTO journal(day,occurredAt,kind,title,subtitle,calories,proteinG,fatG,carbsG,"
        "servingG,enteredPer100g,measurementPayloadJson,note) VALUES("
        f"'{day}',{at(day, minute)},'MED','{NAMES[course]}','{DOSES[course]}',0,0,0,0,0,0,"
        f'\'{{"medicationId":{course},"slot":"{slot}"}}\',\'\');'
    )


rows = [
    "DELETE FROM medications; DELETE FROM appointments;",
    "DELETE FROM journal WHERE kind IN ('MED','EAT');",
    # Three courses: two on the clock, one on waking, one of them ending this autumn.
    # No attachment, no contents and no photograph on any of them: three empty strings.
    f"""INSERT INTO medications(id,name,dose,schedule,times,foodRelation,startDay,endDay,note,attachments,contents,createdAt,photo)
        VALUES(1,'Метформин','1 таблетка','CLOCK','480,1200','AFTER','{today - timedelta(days=40)}',
               '{today + timedelta(days=22)}','Пить регулярно, запивая водой.','','',{now},'');""",
    f"""INSERT INTO medications(id,name,dose,schedule,times,foodRelation,startDay,endDay,note,attachments,contents,createdAt,photo)
        VALUES(2,'Магний B6','2 капсулы','WAKE','','ANY','{today - timedelta(days=12)}',NULL,'',
               '','',{now + 1},'');""",
    f"""INSERT INTO medications(id,name,dose,schedule,times,foodRelation,startDay,endDay,note,attachments,contents,createdAt,photo)
        VALUES(3,'Омега-3','1 капсула','CLOCK','720','WITH','{today - timedelta(days=30)}',
               '{today + timedelta(days=120)}','','','',{now + 2},'');""",
]

# Two weeks of marked doses, thinning out so the chart shows full, partial and empty days.
plan = {1: ["480", "1200"], 2: ["WAKE"], 3: ["720"]}
history = {13: "all", 12: "all", 11: "some", 10: "all", 9: "all", 8: "none", 7: "some",
           6: "all", 5: "all", 4: "all", 3: "some", 2: "all", 1: "all"}
for back, how in history.items():
    day = today - timedelta(days=back)
    for course, slots in plan.items():
        for index, slot in enumerate(slots):
            if how == "none" or (how == "some" and index == 0 and course != 3):
                continue
            minute = 8 * 60 if slot == "WAKE" else int(slot)
            rows.append(dose_row(course, slot, day, minute + 12))

# Today: the morning metformin is marked, the rest is open and one of them already late.
rows.append(dose_row(1, "480", today, 8 * 60 + 12))
# A meal an hour ago: the cue an after-food dose waits for.
rows.append(
    "INSERT INTO journal(day,occurredAt,kind,title,subtitle,calories,proteinG,fatG,carbsG,servingG,"
    f"enteredPer100g,note) VALUES('{today}',{now - 3600 * 1000},'EAT','Овсяная каша','с яблоком',"
    "320,9,7,54,250,0,'');"
)
# The diary: one visit ahead, one that has passed unmarked, and a gynaecologist six months back
# whose marked visit is what winds that screening interval.
visits = [
    (1, "GP", "Иванов А. П.", "Клиника Здоровья", at(today + timedelta(days=4), 10 * 60 + 30),
     "Взять результаты анализов", "NULL"),
    (2, "DENTIST", "", "Стоматология на Ленина", at(today - timedelta(days=3), 18 * 60), "", "NULL"),
    (3, "GYNECOLOGIST", "", "", at(today - timedelta(days=182), 11 * 60), "",
     str(at(today - timedelta(days=182), 11 * 60))),
]
for row_id, specialty, doctor, place, when, note, done in visits:
    rows.append(
        "INSERT INTO appointments(id,specialty,doctor,place,atMs,note,remindDaysBefore,doneAt,remindedAt,attachments)"
        f" VALUES({row_id},'{specialty}','{doctor}','{place}',{when},'{note}',1,{done},NULL,'');"
    )
print("\n".join(rows))
PY
  seed "$(cat "$SQL_FILE")"
  rm -f "$SQL_FILE"
  echo "ready: three courses, a fortnight of doses, three appointments"
  ;;

shot)
  NAME=${2:?usage: tools/medicine-shots.sh shot <name>}
  require_emulator
  "${ADB[@]}" exec-out screencap -p > "$OUT/$NAME.png"
  echo "shot: $OUT/$NAME.png"
  ;;

*)
  echo "usage: boot <avd> | cabinet | shot <name>" >&2
  exit 1
  ;;
esac
