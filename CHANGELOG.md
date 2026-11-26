# Changelog

Newest first. Each entry says what changed and, where the change is not self-evident, why.

## 2.0

### Release

- **The source is public, at github.com/pwn-all/PWN.Health.** About names that address as the
  source the GPL promises. The repository carries the app and the models it ships, listed by their
  hashes in `docs/MODELS.md`. 1.78 was never published on its own: everything its section lists
  reaches people with 2.0. From 2.0 on, each release raises versionCode by one, so 2.0 is 79.

### Backup

- **The automatic copy writes a new file on every run and removes old ones only after it.** A
  second run in one day used to write over the morning's file, so a run that failed partway could
  leave the day without a good copy. The name now carries the installation, the time and a random
  part (`pwn-health-auto-<day>-<installation>-<ms>-<random>.json`); the seven newest days are kept
  as before, one file each, and the old one-a-day names are still recognised and pruned.
- **A database that starts over keeps the lost record's copy of the same day.** The fresh database
  wrote that day's copy under the same name, over the last good copy of the record that was lost.
  Copies made by an earlier installation, or by another phone writing into the same folder, are
  now left alone.
- **A sealed backup is believed only once its last chunk is.** Restoring the profile alone saved it
  as soon as it was read, before the rest of the file and the closing chunk's tag were checked, so
  a file cut off after the profile still changed it. A file that ends on a chunk boundary and lost
  its closing chunk, or carries bytes after it, is now refused and nothing of it is kept.

### Imports

- **A Fitbit day that straddles two of its files is counted whole.** Fitbit's export spreads the
  minute-by-minute steps over several files, and each file was added up on its own, so a day whose
  minutes were in two of them kept only one file's part. The minutes are now joined across files
  per tracker, a minute present twice counts once, and the busiest tracker is the day's figure as
  before.
- **An archive's files are read as they are unpacked.** Every file a service claimed used to be held
  until the whole archive had been walked; now only what was read out of them is. MyFitnessPal's
  weights still take their unit from its exercise file, whichever of the two comes first.

### Health Connect

- **A changed plan, cycle, body or session record is sent even when it hashes as before.** What was
  last written was remembered as a 32-bit hash of the record's text, and a change between two texts
  with the same hash — a plan renamed from "Plan Aa" to "Plan BB" — looked like no change, so
  Health Connect kept the old record. The whole text is compared now.

### Bluetooth devices

- **A Bangle.js 2 running the PWN.Health watch app is a device on the Devices page.** The search
  lists the watch by the name Espruino advertises, and its row says the watch app has to be
  installed from the Espruino App Loader and open. Connecting asks the watch once over the Nordic
  UART for a snapshot and closes the link when it has arrived. Save files the watch's step total
  for that day beside the phone's own count — the day shows the larger of the two, never their sum
  — and its heart rate when it sent one. The link is not authenticated, which is why the figures
  wait for Save.

### Build

- **A GitHub release carries the changelog's section for its version.** It used to list the commits
  since the previous tag. A tag whose version has no section here fails before anything is
  published, and the notes end with a link to this file.
- **Dependabot's Gradle pull requests are merged too.** Their build is started by a dispatch, and
  GitHub raises no event when such a run ends, so `automerge.yml` never heard that it had passed.
  It now also looks once an hour, and when started by hand, at every open Dependabot pull request,
  each on its own: one that cannot be merged no longer holds back the rest.
- **CI builds two tasks at a time.** A public repository's runner has four cores, and three
  variants compiling at once in one Kotlin daemon ran it out of memory; two at a time is how every
  build had passed on two cores.

## 1.78

### Widgets

- **Seven more home-screen widgets.** Last night's sleep, from when to when; the latest weight and
  how it moved in thirty days; today's food against the day's allowance, where a tap logs a meal;
  the week's steps as seven bars; the level, the XP to the next one and the daily streak; the next
  dose of the day with a Taken button that writes it down — the same row the medicine tab and the
  reminder write; and the day of the cycle with its phase and the next expected period, offered
  only to a profile with a cycle. Each is read once from the database and redrawn by the rows it
  shows, and §10 of the privacy policy says what each puts on a home screen outside the app lock.
  The configurable quick-entry widget gains a breathing icon.
- **The dose widget's Taken marks the dose it showed.** A face still standing after midnight used to
  mark the new day's dose of the same time, which left last night's tablet missed and silenced the
  coming evening's reminder. The button now carries the day it was drawn for, and a face more than a
  day old marks nothing and redraws.
- **Widgets show the tap that was just made.** A second glass of water within a minute of the first,
  a cigarette, a route stopped or paused, and the shortcut row just chosen now appear at once; a
  widget already on screen used to keep the figures it read when Android last began drawing it, for
  a minute or for a whole walk. The route widget also takes its recording face as soon as a route
  starts, without waiting for the first fix.
- **A widget's tap leaves an open record behind.** With a record opened from a symptom chat's card,
  a tap on the food, mood or any other widget used to close that record and land in the chat under
  it, with the form the widget asked for hidden behind.
- **Widgets and medicine alarms follow an import at once.** After an exchange with Health Connect or
  a file import the widgets kept their old figures for up to half an hour, and a dose timed from
  waking kept the default hour until the hourly check; both are now brought up to date when the
  import finishes, as are the step widgets when Health Connect brings a larger count for a day.
- **A cup with milk updates the energy and level widgets.** A cappuccino logged from the caffeine
  dialog is a food row, but only the water widget was redrawn and a dose taken before food was not
  worked out again; both now follow the row that was written.
- **The mood widget keeps its four-hour pause across midnight.** It used to bring the faces back at
  00:00 and then silently drop every tap until the pause was over. Its countdown now rounds up, as
  the mood page does.
- **While a walk records, the app redraws only the widgets whose figures moved.** The step widgets
  are redrawn for steps and the walk widget for the route, instead of all fourteen being asked every
  five seconds.
- **The dose widget shows the time before the dose text.** A long dose description no longer pushes
  the time off the end of the line.
- **The cycle widget no longer says "Fertile days".** The estimate is shown in the app beside the
  sentence that it is not a method of contraception, and two short lines on a home screen have no
  room for that; on those days the widget shows when the next period is expected.
- **A file that holds only daily step counts updates the step widgets at once.** An import that
  brought step days and no journal entry redrew nothing, so the step, week, energy and level widgets
  kept their old figures until the launcher's next update.
- **Widgets turn over at midnight, and the mood widget shows its faces again when its pause ends.**
  Both used to wait for the launcher's half-hourly update, so a widget could go on showing
  yesterday's figures as today's; an alarm that does not wake the phone and needs no permission now
  redraws them, and is set again after a reboot.
- **Widgets on the home screen switch to the phone's new language as soon as it is changed.** They
  used to keep the old language for up to half an hour, until the launcher's next update.
- **A cigarette tapped on the widget a moment after a heated stick or another product logged in the
  app is recorded.** The widget kept its own copy of the double-tap guard, which took any tobacco in
  the last three seconds for a repeat; it now uses the app's own guard, where only the same product
  twice is one.
- **The cigarette widget counts the day's cigarettes under its "+ Cigarette".** It showed all the
  day's tobacco in cigarette equivalents, so an average hookah read as fifteen cigarettes and heated
  sticks logged in the app appeared as extra ones.
- **Opening the app from Recents after it was closed no longer reopens the dialog or form a widget
  asked for when it first started the app.** Recents hands the app the request it was first started
  with, so after "Close the app", a crash or a reboot the widget's dialog came up again; a window
  opened from Recents now leaves it aside, while a widget's own tap still opens its dialog.
- **A widget's tap is no longer lost when the phone is turned while an encrypted record is still
  opening.** The screen the widget asked for is now kept through the turn and opened once the record
  has loaded.
- **The dose widget shows a course added, changed or deleted on the Medicine tab at once.** A course
  is not a journal entry, so the widget used to wait up to half an hour for its next redraw.
- **The dose widget says "On waking" for a dose taken on waking, as the rest of the app does.** It
  printed 08:00 or the recorded waking time.
- **The cycle widget no longer says "No cycle recorded" over a record that has only gone quiet.**
  Like the cycle page, it says since when nothing has been logged.
- **Widgets redraw as soon as a profile is saved: at the end of onboarding, after a profile is
  restored from a backup, and when the step goal, units or energy goal change.** Before, the multi
  widget's tiles, the step goal and the units waited up to half an hour for the next redraw.

### Workouts

- **The exercise search finds "İp atlama" for "ip".** Both pickers lowered the names and the query
  without saying in which language, and under those rules the Turkish capital İ is not an i.
- **Personal records for sets and reps.** Each exercise keeps four records read from its own
  journal rows: the heaviest weight, the best estimated one-rep maximum (Epley, from sets of up to
  12 reps, and nothing past that), the most reps in a set without added weight, and the most weight
  lifted in one session. The exercise form shows the best under "last time". The summary after a
  session and the end page of a template or Freestyle session mark the set that broke a record and
  list each record broken with the figure it beat. An exercise's first session breaks nothing, and a
  saved workout opened again is not measured against itself; the session pages read the records
  just before the session is written.
- **A deleted template takes its planned dates with it.** Deleting a template left every date it had
  been planned for in the calendar, and on each of those mornings "Workout today" still named it.
  Its plans are deleted with it now, so Health Connect withdraws them at the next exchange as well,
  and the reminder names only plans whose template is still there, which also quiets the dates a
  template deleted before this version left behind.
- **A shared template's file no longer stays in the cache.** Sharing a template writes it into the
  cache as a file, and nothing ever deleted it. It goes at the next fresh start now, as a shared walk
  or report does.
- **Switching the sound off or on during a template session no longer closes the app.** The
  session's clock kept the tone generator it had started with, which the sound switch releases; the
  next stretch asked it for a tone and the app closed with the session unwritten. Each tone is now
  asked of the generator in use at that moment, so a session switched to silent stays silent and one
  switched back sounds again.
- **An exercise done twice in one session no longer breaks the "Workout complete" page.** Coming
  back to an exercise in Freestyle, or a template with the same exercise on two steps, could close
  the app as the page opened when a record was broken, or put the Record badge on the wrong set. The
  badge now goes to the one set that holds the new record, and each record is listed once — the
  weight lifted being that of one journal row, as the records themselves are kept.
- **Leaving a workout asks first, whichever way you leave.** Tapping another tab of the bottom bar
  in the middle of a workout now asks before discarding it, as Back already did: the tab used to
  open at once, and the session, which is kept only on the add tab until it is saved, went with it.
  A widget or a notification that would take the app off the workout waits for the same answer;
  water, caffeine and the exercise shortcut, which leave it standing, do not ask.
- **A workout under way is no longer lost to the exercise button.** While sets are running or the
  plan has been changed, the button at the top of the workout form leaves out the exercises the
  phone counts: choosing squats there replaced the session with the rep counter and dropped the
  finished sets without a question.
- **The rep counter survives a turn of the phone.** The count, its clock and whether it is running
  are kept when the screen rotates mid-set; the card used to come back at zero with Start showing,
  and the reps never reached the form.
- **A session from a template no longer closes itself when the phone is turned in the middle of
  it.** It closed with nothing written; its page now waits until the template's exercises have been
  read again.
- **A training machine's numbers taken on the Workouts page or in the exercise form keep the
  console's own calories.** The pass that re-estimates workouts after every save used to replace
  them with the MET estimate in the same transaction.
- **The exercises of a template or Freestyle session are timed one after another and end at the
  moment the session was finished.** They used to be stamped together at the finish, so each claimed
  the session's last minutes and Health Connect received them as overlapping sessions, all but the
  first ending in the future.
- **A finished workout session is written in one piece and can no longer be cut short by turning the
  phone or going back while it is being saved.** The page that comes back afterwards shows the
  summary instead of running the session on and logging it a second time.
- **A weight in pounds lifted again is no longer a record over itself.** The records read an
  exercise's history to one decimal of a kilogram, so about a third of the usual weights in pounds —
  45, 135, 185 lb — came out a new heaviest set and a new one-rep estimate, shown as "135 lb · was
  134.9 lb"; they now read the number the row stores. A set answered with no reps is an attempt, not
  the heaviest weight, and wears no badge.
- **The workout summary counts the rest before End, as the saved row does.** A session ended from
  its rest card put that last rest into the row's minutes and energy but not into the summary, and a
  row opened again and ended lost it; the rest is now kept with the sets, listed after the last one,
  and counted the same on the summary, in the journal and on reopening. In a superset it goes into
  the minutes of the exercise it followed.
- **A rest in the exercise form's session counts for no longer than a quarter of an hour, as in
  Freestyle and templates.** A phone left on the rest card and picked up hours later used to write
  those hours into the workout's minutes and energy; the rest clock now stops at 15:00, so what it
  shows is what is saved.
- **A finished set can be corrected.** On the paused session — back from the summary, or a saved
  workout opened again — a tap on a finished set opens its reps and weight (a hold's time), and its
  bin takes the set, or the whole round of a superset, out; End then saves the session as corrected.
  A weight typed as 600 for 60 used to stay in the journal, the volume and the records until the
  whole row was deleted.
- **A superset is offered only on a new workout.** Paired on a saved row, the partner got no row of
  its own and its sets were saved into the first exercise's row, where they read back as that
  exercise's sets and records.
- **A set is now stopped once.** A second touch of Yes or Stop that landed before the screen redrew
  could overwrite a set's time with its superset partner's clock and start the rest again.
- **The rest between sets can be set.** The plan shows the rest under its set with Edit, the coming
  rest of a running set can be changed too, and later sets and added exercises inherit it, so the
  arc on the rest card fills over that length instead of always over a minute. The reorder arrows of
  the plan, which no plan could ever show, are gone.
- **A count the phone lost says so.** Behind a locked screen Android can stop delivering the motion
  sensors to the app; a set whose samples stopped for more than five seconds now shows "Counting was
  interrupted — enter it by hand" and ends on the figures to type, where it used to offer the part
  it had caught as the whole count.
- **A set's weight is stored in kilograms whichever unit it was typed in, and read back in the unit
  the profile shows now.** A workout logged in kilograms and opened after the profile was switched
  to pounds used to show 100 kg as "100 lb" and save 45 kg over it.
- **A workout entered for an earlier day is typed — minutes, sets, reps and weight — instead of run
  as a session.** The session timed it by the taps and saved forty minutes of bench press as twenty
  seconds at next to no energy. A saved workout that holds no sets reopens on the same fields, where
  it used to open as an empty session with no Save.
- **A rest or a set left running counts for at most fifteen minutes.** That holds for a rest in
  Freestyle, for a set of reps in Freestyle that was started and never stopped, and for a set or
  rest that waits for a tap in a template's session (or its planned time, where that is longer); a
  timed Freestyle set such as a walk still counts for as long as it ran. A session left open
  overnight and finished in the morning used to write the night as hours of the exercise, with
  thousands of kilocalories to match.
- **Back in a template's session asks before it leaves once any set has been done.** One swipe from
  the edge of the screen used to throw the whole workout away without a word. A session still
  getting ready, or already complete, closes without the question.
- **Finishing a Freestyle session no longer flashes the exercise catalogue.** While the workout is
  being saved the session's page stays, its buttons off, until the summary appears.
- **Pausing a set of reps, or the rest after it, no longer cuts its time back to the planned
  estimate.** A set paused at 45 seconds and completed 15 seconds after Continue is logged as a
  minute, not 45 seconds.
- **An imported workout file brings at most a hundred exercises, and a template's name is kept on
  one line.** A name with a line break in it used to number every session "#1", so its sessions were
  never folded into one workout in the calendar.
- **A planned workout follows its template.** Renaming or changing a template updates the dates
  still ahead that it is planned for, so the morning reminder, the calendar and Health Connect name
  it as it is now called; days gone by stay as they were planned. A plan can no longer be made for a
  day gone by or twice for the same day, and the confirmation and the plan's line in the calendar
  and the day's list show the date the way the phone's language writes it.
- **In a workout's session a planned rest is set up to fifteen minutes, which is as long as a rest
  counts.** The wheels used to go to 99 minutes while the rest card's clock stops at 15:00, so the
  ring of a longer rest never closed. A template's own rest and a set's duration can still be set up
  to 99:59.
- **A correction to a saved workout is no longer lost without a word.** Back off the page, with a
  set changed, added or under way, now asks first and says how the change is kept: End, then Done on
  the summary.
- **A workout file longer than a template takes says so.** Only the first hundred exercises were
  ever imported, and nothing on the page told a template of three hundred from a whole one; the
  import page now gives how many were taken and how many the file lists.
- **A workout template marks its steps on a silent phone too.** On vibrate, on silent or in Do Not
  Disturb the countdown, the start of the next step and the end are now taps of the motor, as they
  already were in a breathing session.
- **A saved workout taken from a training machine, and a session imported from Health Connect, a
  sensor or a backup, open as a form with a Save.** The minutes, the note and, for a machine, the
  console's energy can be corrected, and an imported session keeps its own name and calories. They
  used to open as an empty session to start, an imported one under the name of an unrelated
  exercise.
- **Deleting a workout template says that its planned days go with it.** The dialog now says that
  the planned days are removed with the template while workouts already recorded stay, and the
  template and its plans are removed in one step, so an app closed half-way leaves no plans behind.
- **Turning the phone while a new workout template is being saved no longer leaves a second copy of
  it when Save is tapped again.** The second Save now updates the template the first one wrote.
- **Floors climbed on the stairs in Freestyle are kept with the workout in the journal.** They used
  to be left out of the row the session wrote.
- **On a phone without the sensor to count an exercise, Freestyle asks for a set's reps starting
  from the last set's instead of from zero.** It used to open the count question at nought, also
  when the count was lost just as the set stopped; a timed set on such a phone is written at once,
  with no question about a count.
- **The screen no longer stays on while picking the first exercise or on the workout's summary.** It
  is kept on only while the session runs.
- **Screen readers hear whether a template's exercise, the walk and run cards and the how-to fold
  are open.** The walk and run cards were announced as selected options rather than as folds, and
  the long-press on an exercise's drawing now says whether it shows the muscles or the drawing.
- **Set numbers and the session's progress percentage are written in each language's own order.**
  "Set N" was put together in English word order in four places and now reads "3セット目" in Japanese or
  "第 3 组" in Chinese, and the player's percentage reads "%50" in Turkish.
- **A shared template file whose picture is damaged is imported without the picture.** It used to be
  refused whole, though the import promised to take such a template without its picture.
- **Adding an exercise to a template no longer shows the plank as already chosen.** Nothing is
  marked on the picker until an exercise is picked.
- **When the phone loses count part-way through a template's set, holding Complete set asks how many
  reps were done.** It used to log the plan's reps as done; a phone that cannot count at all still
  logs the plan.
- **In a superset, the seconds spent answering whether the first half's reps were reached now count
  in the second half when it is a set of reps.** Its clock starts when the first half stopped, as a
  rest's does; they used to fall between the two, and the session came out short. A hold's clock
  still starts at the answer, so the hold is not overstated.
- **Pairing an exercise gives the partner an empty row that keeps only the round's rest.** A hold
  typed for the first exercise used to become a head start on the partner's clock.
- **A volume record on the workout summary is one journal row's, as the records read back from the
  journal are.** A session that came back to an exercise after another used to announce a volume no
  row of it held.
- **A set the phone counts says its count was interrupted, and asks for it, once its samples outrun
  what a set can hold.** A phone delivering faster than the 50 Hz asked for reaches that before
  forty minutes, and the set used to stand on a count that had stopped growing.
- **A workout session keeps the screen on while a set or a rest runs, as Freestyle and a template's
  player do.** The screen used to go dark in the middle of a session; it is let sleep once a rest
  has run past the fifteen minutes a rest counts for.
- **A set's weight and the workout summary's minutes take the decimal mark of the language on
  screen, as the rest of the app's figures do.** They were printed with a point; they now read "61,3
  kg" in German and Russian.
- **A workout plan opened from the chat starts its session on the sets it named.** Three sets of ten
  at sixty opens as three planned sets instead of one empty set.
- **Taking a training machine's numbers while a session is under way now asks before it replaces the
  sets already done.** The machine's result cleared the session and opened the summary, and the
  planned and finished sets went without a question.
- **The exercise button no longer offers a superset's second exercise as its first.** Choosing it
  counted both halves on one row and wrote the second again beside it.
- **The phone's rep counter keeps the screen on while a set is being counted.** With the screen off
  the phone stops sending readings, so a set longer than the screen timeout was cut short.
- **Correcting one half of a superset keeps the line saying what it was paired with.** The corrected
  row lost its pairing, so one row of the day's list named the pair and the other did not.
- **A machine workout's energy typed from the console's screen is kept when workouts are re-costed
  after a weigh-in.** It used to be replaced by the estimate, and a ride whose console reported no
  energy reopened with the estimate shown as the console's figure; it now reopens with the estimate.
- **Correcting your latest workout no longer shows that same workout as "Last time".** The line read
  the newest session of the exercise, which was the one being corrected.
- **A workout's rest timer keeps running through a turn of the phone.** It used to start again at
  nought.
- **Floors climbed in a template session now stay on the workout when you open it and save it
  again.** Saving the workout again from the editor used to drop them.
- **Pausing a template session now pauses the count of the set under way and lets the phone sleep.**
  Steps, jumps or repetitions made while it stood paused used to be counted, and the phone was kept
  awake through the pause.
- **A climb's sets in the workout editor now say what the phone counted — "3 floors · 156 steps" —
  and a rope's say its jumps.** A timed set used to show only its time.
- **Measuring your pulse now stops when the screen locks or you leave the app, as Cancel does.** It
  used to carry on unheard and could put a figure from part of the recording into the picker.
- **Taking the phone out of your pocket to pause a set and putting it back after Continue no longer
  counts as a repetition.**

### Onboarding

- **The questions show the person's own numbers, and the steps goal is theirs to set.** A line of
  eight segments with "3/8" beside it replaces the dots, and stands still while the pages slide
  under it, so it is seen to fill. Each answer, once its page is behind, stays in sight as a small
  tag under the line. The weight page draws WHO's adult BMI bands with a marker that follows the
  ruler, unlit under 18; the date page rolls the age as the wheels turn. A new page asks for the
  daily step goal, starting on the age band's suggestion and showing the distance it means at the
  person's stride: in the ENGAGE trial only a goal people chose themselves got them walking more.
  The sex page no longer starts on "male". The finish reads out the step goal, the resting energy
  and the BMI with where each comes from, counting up once; Start gathers the drifting symbols
  into a ring that tightens to a point and opens into the home screen. The illustration blobs and
  the gradient button are gone. With animations off in the system settings nothing counts or
  drifts.
- **The date of birth starts blank again, and Next waits for it.** The three wheels opened on the
  day twenty-five years back with Next lit, so tapping straight through recorded an adult and opened
  alcohol, tobacco and the intimate journal — the populated age screen 1.47 had removed. Each wheel
  now opens on a blank row, the year's standing beside this year so that a careless flick lands on
  an age that opens nothing; Next lights once day, month and year are all turned to, the age under
  the wheels is not shown before that, and a day or month already chosen is no longer trimmed while
  the year wheel passes this year or the month wheel passes February.
- **Back on the questions can no longer land on the finish.** The arrow in the header counted the
  page down unchecked and could still be tapped while the header faded out over the greeting; a
  second tap there made a page that does not exist, which was drawn as the finish — Start, with no
  question asked, and a profile saved without a sex if a date had been given earlier. Back now stops
  at the greeting, and the finish is drawn for its own page and no other.
- **Onboarding says what Health Connect exchanges before it asks.** Next on the access page opens
  Health Connect's request, and the only words before it were "Health Connect is offered next". The
  page now carries, above its rows, the description the Health Connect card on the Devices page
  shows: what is imported, what is written back, and that access is yours to choose.
- **Usage access opens from onboarding on phones that refuse the app's own row.** Some Android
  builds reject the Usage access screen when it is asked for one app; the profile already fell back
  to the plain list, and onboarding now does too instead of telling you to find the screen by hand.
- **A height or weight you never set is no longer logged as a measurement.** The rulers in
  onboarding open on 170 cm and 70 kg, and tapping straight through used to write those into the
  journal as a measurement taken by hand, from where they reached the charts, the doctor's report
  and Health Connect. They still start the profile; only a ruler you moved is recorded.
- **Reminders and the cycle and tobacco widgets follow the first profile at once.** They used to
  wait until the app was left and opened again: a profile just created in onboarding had no hourly
  reminder and neither widget on the launcher's list.
- **On a phone without Health Connect, onboarding no longer describes the exchange or says it is
  offered next.** Next went straight on there, past a screen the page had promised.
- **The step-goal ruler follows "Use the suggestion" again after Next and an immediate Back.** A
  fling still running when the page was left kept the ruler deaf to outside values.
- **Pressing Back once Start has been tapped on onboarding's last page no longer finishes onboarding
  over the page Back opened.**
- **During onboarding, a permission dialog dismissed without an answer comes up again on the next
  tap instead of sending you to the app's settings.** Settings now open only once the permission has
  been asked for twice and refused.
- **Onboarding saves the profile before the first body measurement, so a profile that fails to save
  no longer leaves a measurement that the next attempt records a second time.**
- **The date-of-birth wheels stand in the order your language writes a date: month first in American
  English, year first in Japanese, Chinese and Korean.** They stood day, month, year for everyone;
  they now follow the order of the platform's own date picker.
- **On a phone set to Indonesian, the first screen opens on "Halo" on every Android version.** A
  runtime may name the language "id" or "in", and the greeting looked for only one of them.
- **TalkBack names the height, weight and daily-steps rulers in onboarding along with their value.**
  It used to read out the bare number.

### Screen time

- **Time in the feed apps, and breaks for the eyes and the head.** A new Screen time card in the
  "+" tab's health section shows today's time in the social and feed apps — TikTok, Instagram,
  YouTube, Reddit, Facebook, X, Threads, Snapchat, Pinterest, VK, Likee, Kwai, Tumblr and 9GAG —
  the longest sitting without a break and the last seven days, read from Android's usage history
  with the same Usage access the sleep estimate asks for. Two reminders can be switched on. One is
  for the eyes: after 20 to 60 minutes of a sitting with no break, it gives the 20-20-20 rule. The
  other is for the head: when the day's total passes 1 to 3 hours, and again at each multiple of
  that, it suggests putting the phone down. Hopping from one feed to another counts as the same
  sitting, and so does an app's own next screen — a profile opened from a feed, a video from its
  list. A check runs every fifteen minutes only while a reminder is on, and nothing is written to
  the journal or leaves the phone.
- **Where else the time goes is counted too.** Three more cards only count, with no reminders:
  Video and streaming (Twitch, Netflix, Prime Video, Disney+, VK Video, Rutube, Kinopoisk, ivi,
  Okko), Messengers (Telegram, WhatsApp, Discord, Messenger) and Games. Games cover some thirty
  titles: Call of Duty and PUBG in every regional and Lite build, Free Fire, Fortnite, Standoff 2,
  Roblox, Minecraft, the Supercell and HoYoverse games, Candy Crush and more. A card shows only the
  apps open a minute or more that day, most time first, and only a kind with any time that week
  gets a card.
- **The eye-break reminder counts a scroll that runs through midnight as one sitting.** A feed
  opened at 23:40 used to start from nothing at 00:00, so the reminder came late, skipped a step or
  named too few minutes.
- **A sitting that began early in the evening is still one sitting after midnight.** The usage log
  was read from three hours before midnight, so the eye reminder repeated once for a sitting begun
  earlier, and a feed left open on one screen across midnight dropped out of the next day's figures;
  the check now reads the log from the start of the previous day, and the privacy policy says so.
- **Changing the eye or brain reminder's step after a reminder no longer silences it for hours or
  repeats it at once.** It kept count in steps of the old length; it now remembers the minutes
  already said, and the next one comes at the next step past them.
- **The Screen time page says in words when today's feed time or the longest sitting has passed its
  step.** It was shown only in orange; and the busiest day of the week or the busiest game or video
  app is no longer painted orange as if it were over a limit.

### Health Connect

- **Resting heart rate and heart rate variability come in from a watch or a ring.** Health
  Connect's resting heart rate and HRV (RMSSD) records are read now, read only: a watch or a ring
  works both out for itself, usually overnight, and the app computes neither, so it asks for no
  write. The resting rate is a vital of its own, graded against the same AHA resting band but kept
  apart from the day's pulse, because a rate measured asleep and a sample taken on the stairs are
  not one series. Imported variability joins the strap's on the existing HRV card. Both cards add a
  line with the person's usual figure, the median of their readings in the 28 days before the
  latest one (at least five), and how far the latest sits from it. Both are in Data sources and
  the doctor's report. Existing installs get the two types from "Allow more types" on the Health
  Connect card. The rationale screen and the privacy policy name both.
- **The privacy policy tabulates the Health Connect request.** Its §6 now carries the rationale
  screen's own table, every row in the same words: which records, and what the app does with each.
  A test holds the two to each other, so a type added to the request without its row in the policy
  fails the build, as one without its row on the screen already did.
- **Lean body mass is no longer exchanged with Health Connect.** The app asked to read and write it,
  but no screen showed it: a scale's fat-free mass went straight out to Health Connect, and another
  app's came back only as a line in the journal. Play's review holds every Health Connect type to a
  feature it can see in the app, so the permission is gone. The scale's reading still keeps the
  figure, and the lean mass records the app published before stay in Health Connect, which can
  delete them.
- **A database set aside or quarantined no longer takes its Health Connect history with it.** What
  the sync remembered about the old database outlived it, so the first exchange afterwards read the
  empty journal as everything deleted and withdrew the app's own records from Health Connect, the
  one store that still held them. That memory is now forgotten with the database, and the next
  exchange brings the last year back as a reinstall does.
- **A reinstall brings back every cycle entry filed for an earlier day.** A flow, a mucus reading,
  an ovulation test and an act logged after the fact all stand on that day's midday, and the restore
  took the first of them for all the rest — which the next exchange then withdrew from Health
  Connect as well.
- **Deleting an entry dated more than a year back now withdraws its record from Health Connect at
  the next exchange, like any other.** The sweep read only the last 365 days, so such a record
  stayed there for good; what an earlier installation published that far back is still left alone.
- **An exchange with Health Connect that is still running when the app is left now stops and runs
  again on return.** Health Connect answers an app in the background with its own records only,
  which could clear the steps a watch had counted and skip other apps' history after an update.
- **After an update the app still learns which records other apps deleted in the meantime, and
  removes the rows it had imported from them.** An update used to discard the app's place in Health
  Connect's change feed without reading it, so a row whose record had been deleted at its source
  stayed for good. A night imported before it was over now follows its session as it grows, and
  gives way to a longer session recorded for the same night; it used to keep the length it was first
  read with.
- **A body value taken out of a measurement is no longer put back at the next exchange with Health
  Connect.** Every record the app had just published came back in Health Connect's change feed and
  was looked up in the journal again, and that echo returned the body fat or the height that had
  been removed. The exchange no longer works through its own export a second time.
- **A reinstall no longer writes over what the previous install published to Health Connect.** A
  record's id used to end in nothing but the number of its journal row, and a fresh install numbers
  its rows from 1 again, so its first entries replaced unrelated older records — anything more than
  a year old, and every route, plan and workout detail, had no other copy. Each record now also
  carries a random tag naming the journal it came from.
- **Entries that share a moment all come back after a reinstall.** Two acts logged against the same
  earlier day, or the last exercises of two template sessions filed for one day, used to be restored
  from Health Connect as one, and the next sync then removed the other from Health Connect as well.
- **A night or a day's heart rate deleted in the app that recorded it now leaves the journal too.**
  Sleep and heart rate were the two imported kinds that never learned of a deletion at their source,
  so the row stayed for good. The days another app's heart rate was found on are kept on the phone,
  where a transfer to a new one does not carry them, so a day deleted while the app was closed goes
  as well.
- **A sync writes to Health Connect only what changed.** Every return to the app used to rewrite the
  record of every journal entry, so the work grew with the history and other apps were told each
  time that all of it had changed. The cycle, body water and resting rate, a workout's figures, the
  day's energy, breathing and plans are held to what their record says rather than to one entry,
  because most of them are built from more than one: a period from its start and its end, a day's
  energy from its steps, workouts and weighings.
- **Workout details from other apps are read when a workout arrives, not at every sync.** With one
  imported workout in the journal, each return to the app used to read a year of distance, energy,
  climb, power and cadence records from every app.
- **A workout from another app no longer counts its energy twice when that app writes a total over
  the workout beside its minute-by-minute figures.** The energy is now Health Connect's own total
  over the workout, of the app that recorded it, which takes each stretch of time once; each workout
  is asked about once, and again only if its app moves it or within two days of its end, instead of
  a year of records being read again whenever one more workout arrives. An energy figure corrected
  by hand is no longer replaced.
- **One record type Health Connect refuses no longer stops the rest of a sync.** The other types are
  still published and deleted entries are still withdrawn; and a sync turned away for Health
  Connect's request quota waits fifteen minutes instead of trying again, and failing again, at every
  return to the app.
- **A record from another app lands on the day it was recorded, wherever the phone is now.** A
  dinner logged at eight in the evening in one time zone used to be filed under the next day once
  the phone had travelled east, and the day's totals were wrong on both.
- **A recorded route is written to Health Connect once, and again only when it changes.** Its
  distance, climb and speeds used to be written at every return to the app, the largest part of what
  a sync still rewrote each time.
- **An import from Health Connect that was cut short is still followed by its recalculation.** If
  Android closes the app in the middle of an import, the next exchange now recalculates activity
  calories and medicine reminders for what had been imported; it used to do so only when that
  exchange itself brought something new or the last one had recorded an error, and a closed app
  records none.
- **A route, a workout's details or a planned workout restored from a backup no longer stands twice
  in Health Connect.** For records the app publishes and never reads back, only what this
  installation had written was withdrawn, so after a restore two records of one fact stood side by
  side; the copy an earlier installation published over exactly the same span of time is now
  withdrawn once the restored one is published, and the privacy policy says so.
- **An entry is published to Health Connect with the time zone it was written in.** The journal now
  keeps each entry's offset from UTC — the phone's at the moment it was filed, or the record's own
  when it came from Health Connect — and backups carry it, so a trip no longer rewrites the hour of
  the whole history at the next full publication. Cycle entries and breathing sessions that came
  from Health Connect keep their record's offset as well, so after a reinstall they go back out on
  the clock they were recorded on. A recorded route keeps the offset of the clock it started on, and
  backups carry it, so its distance, climb and speeds no longer move to another hour when the app
  starts again after a trip. Periods and the day's energy total, which are whole days, are still
  published in the phone's current zone.
- **A plan whose template could not be read is no longer withdrawn from Health Connect as if it had
  been deleted.** The templates are now read before the plans are built, and a failed read stops the
  export of plans for that run.
- **When Health Connect needs an update, its card has a button that opens the store page for it.**
  The card used to say only that an update was needed, and now notices the update when you come
  back.
- **A period another app wrote to Health Connect a day off one already in the journal no longer
  takes that period's end.** It is imported whole or not at all.
- **A period closed today is no longer sent to Health Connect with an end in the future.** It is
  published up to the moment of the sync and completed by the next one.
- **After a reinstall the first exchange with Health Connect removes the earlier install's copies of
  the records it has just restored.** Each one used to stand twice until the next exchange.
- **The Health Connect card's "exported" count includes weights, meals, workouts and readings, and
  counts only what Health Connect accepted.** It used to count heart rate and sleep alone, and the
  records built rather than the ones taken.
- **A day holding more steps than Health Connect accepts, as a hand-edited backup can, is left out
  of the export instead of stopping every exchange.**
- **An edit made after the phone's clock was set back reaches Health Connect.** Health Connect used
  to keep the earlier copy without a word, because the record's version went back with the clock; it
  now only ever goes up.
- **Readings taken by a Bluetooth instrument or by the phone's own sensor, and the night guessed
  from the screen, reach Health Connect as recorded rather than entered by hand.** The guess comes
  back as a guess after a reinstall, so a measured night can still replace it.
- **Fibre, sugar, saturated fat, sodium and cholesterol travel both ways with Health Connect, and a
  workout's or a night's note comes back with it after a reinstall.** A figure out of Health
  Connect's range is left out on its own instead of cancelling the whole meal, and a workout from
  another app brings its note too.
- **A write too large for Health Connect is reported as an error it returned, not as a Health
  Connect that did not respond.**
- **An exchange with Health Connect that gets no answer now gives up after ten minutes, and the card
  says Health Connect did not answer.** Before, it held up every later sync, and the card's Try
  again, until the app was closed.
- **A basal metabolic rate or body water measured by a Bluetooth scale now reaches Health Connect as
  recorded automatically, not as entered by hand.** A basal rate worked out from the formula is
  still published as entered by hand.
- **Each day's total energy written to Health Connect is counted at the weight in force that day, so
  a new weighing no longer rewrites the past month.**
- **When another app deletes records in Health Connect, the sync now finds every row they had been
  imported into with one read of the journal instead of one read per deleted record, so a sync that
  withdraws many records finishes much sooner.**
- **Imported workouts no longer read another app's distance, climb, power or cadence from Health
  Connect: those figures only fed a hidden line in Latin units.** The app now asks only to write
  distance, elevation and power, alongside your own workouts and routes.

### Cycle

- **Spotting can be logged again.** The cycle screen lost its Spotting answer when it became one
  page about today: the day's questions are ladders, spotting is deliberately not a rung of the flow
  one, and it fell off with nothing in its place. A day outside a period now has a Bleeding row with
  a Spotting button, the row it leaves comes off with its ×, and Health Connect gets it as
  intermenstrual bleeding again.
- **The cycle screen answers to the age gate.** Its three buttons for an act — protected,
  unprotected, solo — were drawn for a profile of any age, so under sixteen the sexual-activity log
  the rest of the app removes was one scroll down the cycle page; the buttons, the acts listed under
  them and the heart on the cycle calendar and in its key are now there only from sixteen.
- **A past day on the cycle screen is asked its own questions.** A day inside a period that has
  since ended is asked about bleeding and pain again and shows what was recorded for it, and during
  a period a mid-cycle day is asked about mucus, desire and an ovulation test; the questions used to
  follow today's phase, so a missed period day could not be filled in.
- **The cycle screen no longer opens on the first-run page.** It waits for the journal before
  drawing anything — the page asking when the last period began used to show on every opening, its
  Save ready to file a start — and it reads only the rows it uses rather than the whole journal on
  every write.
- **Cycles from before a long break no longer predict anything.** A gap of 90 days or more between
  two period starts is a break in the record: the next period, "regular" and the fertile window are
  measured only from the cycles after it, where the first start after two years used to bring them
  back from cycles that old.
- **"Your cycle length varies" waits for three cycles.** With one or two cycles on record the line
  under the week said the length varies whatever they were; it now gives the date the next period is
  expected on until there are enough cycles to measure.
- **Under sixteen, the cycle screen no longer asks about desire.** The question is about a person's
  sex life, so it now stands behind the same age gate as the acts, and its line is off the plot as
  well.
- **The line under the week now reaches the readings it was built for.** A mood or sleep pattern is
  printed ahead of "Your cycle is regular", the ovulation estimate holds the line only until its day
  has passed, and "Next expected …" is printed only while its date is still ahead. Before, the mood
  pattern was worked out on every change and never shown.
- **The fertility word carries its caveat on the page.** Under a word drawn from dates the screen
  says it is an estimate and not a method of contraception, and the calendar says so under its key.
  The estimated window is now as wide as the recorded cycles differ, so a record running 25 to 32
  days no longer reads "Low" on days that are fertile in its own shorter or longer cycles.
- **A record gone quiet says so once.** After two cycle lengths without a new start the card reads
  "Nothing logged since …" instead of "No cycle recorded", and the "days late" line is gone.
  Predicted periods are drawn only while the prediction still stands, and no more than three ahead.
- **The cycle plot is read out correctly.** A screen reader now hears the rung that was drawn —
  "Mild" used to be spoken as "No pain", an unanswered day as "Light" and the fertility estimate as
  "Egg white" — and only days actually answered are counted as recorded.
- **Spotting between periods is no longer shown or read out as "Period".** It has its own open dot
  in the calendar and on the week strip, its own entry in the key and its own word for a screen
  reader, and the plot does not shade it as a period.
- **The next question's slider opens in the middle again.** After an answer, the following question
  used to open on the previous answer's position — "No pain" after a light flow, "Positive" after a
  strong desire.
- **A period that was never logged can be added afterwards.** The cycle calendar has "Add a past
  period", which files its start and length, as one write, on days clear of the periods already
  recorded. One missed start used to stretch the average for a year and could not be corrected on
  this screen.
- **Under sixteen, what a restored backup brought of a sex life is hidden outside the cycle screen
  too.** The calendar's day list no longer shows acts, morning check-ins or answers about desire,
  the doctor's sheet prints no "Desire" under the cycle, and the insights page does not set intimacy
  against mood; under eighteen it does not set drinking against mood either. The rows are hidden,
  not deleted, and return on the day the profile turns sixteen.
- **The cycle length the app predicts from is the middle one of the recorded cycles, not their
  mean.** One period start left unlogged makes a cycle of double length, and three cycles of 30 days
  with one such gap came out as 40: every prediction moved by ten days and a "long cycles" note to
  see a doctor could appear. The cycle screen, the profile, the advice and the doctor's report now
  all use the median, and the report and the advice name it as one.
- **The home screen's cycle questions and an open piece of advice stay open when the phone is
  turned, along with the dates entered in them.** They were kept only while the screen stood, so a
  turn closed them and lost what had been picked.
- **A day of a period with no flow answered yet is no longer drawn on the cycle chart as a recorded
  day without bleeding.** Until it is answered the line there is the dashed estimate from earlier
  cycles.
- **An answer on the cycle screen can now be taken back, not only changed.** Reopen it and tap
  Remove this answer: an ovulation test tapped by mistake no longer has to stay on the record.
- **Closing a period left open now proposes an end as long as your periods usually run, instead of
  today.** Dates that run into another period are refused with a word why; the save used to be
  dropped silently.
- **The cycle screen's questions survive turning the phone.** That includes the one asked once after
  a very short period, "That was quick — or a slip?".
- **A long sex-life history opens at once.** The history now draws only the acts in view.
- **A record of cycles under twenty days no longer gets a fertile window drawn over its own
  period.** On such cycles the window opened on or before the day the period began; there is now no
  window and no ovulation marker.
- **The home screen's first-period question files its start and end together.** It files no end when
  the start is refused; that end used to close another period.
- **Day counts on the cycle screen take the right plural in every language.** The period hint, the
  prolonged-bleeding line and the chart's description put a fixed word after the number; they now
  read "1 day", "3 dny".
- **The arrow on each act in the sex-life history points the right way in Arabic.** It was a typed
  character; it is now an icon that turns round for right-to-left languages.
- **Deleting or changing a cycle or sex-life entry that cannot be written now says so instead of
  closing the app.** The form comes back and the page says "Not saved".
- **The cycle screen's note on Health Connect now says exactly what is written there and what is
  not.** Periods, flow, spotting, ovulation tests and mucus go; period pain, desire and masturbation
  stay out. The first question no longer claims its answers stay on the phone, since a period's
  start and end are exported.
- **Deleting a period's start from the calendar or the entry editor now deletes its end too.** A
  leftover end showed as a day of bleeding and blocked any past period logged around it.
- **Taking back a period tapped by mistake keeps the rest of those days.** "A slip — delete it" now
  removes only the period's start, end and flow; the ovulation tests, mucus, spotting, pain and
  desire recorded on those days used to go with it.
- **The cycle's "no period recorded" card stops after a year without a logged start.** It used to
  count a record that was set aside for ever and revive its old cycles.
- **From 45, the "no period recorded" card names the menopause among the reasons a period stops.**
  Younger, it does not, because there the same silence is the reason to see a clinician.
- **The page that asks when a first or forgotten period started keeps the chosen days when the phone
  is rotated.**

### App

- **A write stopped because its page or its worker went is not reported as failed.** A catch around
  a write took the coroutine's own cancellation for the write failing, so a Health Connect exchange
  stopped by Android left its stop on the card as the exchange's error, and a deleted or changed
  act, an import, a dose marked from its notification and the chat's writes ran their "not saved"
  branch over a page already gone. Those catches now let a cancellation through
  (`runCatchingCancellable`) and still take a timeout inside them for a failure.
- **A page left open across a change of time zone takes the new one at once.** The mood, pain,
  work, intimate and lab histories, their charts and the chat kept the zone they were opened in, so
  after a flight their times and the day a record was filed under followed the zone of departure
  until the page was closed, or for the chat until the app was brought back to the front. They now
  read it again when Android announces the change.
- **A course's or a template's picture left waiting by a closed form is deleted at the next start.**
  A shot waits in the cache, shrunk, until its form is saved, and stayed there for good when the app
  was closed with the form open: a photograph of a prescription, in no list and with no button to
  delete it. The start-up sweep that takes the camera's lost shots takes these too, an hour old.
- **A page left open over midnight works with the new date.** The lab form dated a result typed
  after midnight the day before and looked for earlier units from yesterday's window; the doctor's
  sheet ended its period a day short; the routines kept yesterday's weekday as today's; and eight
  more places held the date they opened on. All of them now read the date the way the home screen
  does.
- **The steps notification opens the app as you left it.** Each tap used to put a second copy on
  top, which Back had to get rid of. The icon now does the same after the app was started from a
  widget.
- **A reminder no longer closes what you had open.** Tapping a reminder, a dose card or the steps
  widget no longer closes a camera or a save dialog you had open in the app.
- **The app no longer asks the phone's font provider for an emoji font when it starts.** A library
  Compose brings along did that by itself shortly after the first screen, and on a phone with Google
  Play services the provider it asks is Google's, which keeps a record of the request: the app was
  telling Google when it had been opened, and the privacy policy says it tells nobody anything. The
  request is removed from the build. Nothing changes on screen: from Android 13, the oldest this app
  runs on, the system's own emoji are current.
- **Save closes the form as soon as the entry is written.** The copy to Health Connect is made
  afterwards. While a sync was running the form used to stay up with a dead button until the sync
  ended, and the way back was then taken from whichever page had been opened in the meantime.
- **About the app names SQLCipher and Guava among the third-party components.** SQLCipher (Zetetic
  LLC, BSD-3-Clause) is what the database encryption switch runs on, and neither was in the list; a
  test now holds the list to the build's dependencies.
- **The licence texts are in the app.** About now opens the full licences of SQLCipher and ONNX
  Runtime, the notices of what ONNX Runtime is built with, and the chat reader's notices, which
  shipped in every build and which no screen showed; SQLCipher's row says what the library is used
  for.
- **The bell stays quiet about advice already read.** A nutrition line appearing or leaving at the
  top of the list used to make every advice below it count as unread again, several times a day.
- **The line round the home card no longer redraws itself.** It ran from zero every time the figure
  under it moved — once a minute on the energy card, and with every counted step on a walk — and now
  carries on from where it stands.
- **The Profile tab no longer reads the whole journal to draw its menu.** The rows are read when
  Summary, Health or Sources is opened, and the Health section waits for them instead of briefly
  saying there is nothing to report.
- **The calendar survives a record filed under a broken date.** A row restored from a damaged or
  foreign backup with a day that is not a date used to close the calendar every time its month was
  opened out; it is now passed over.
- **Shared files leave the cache within the hour.** A report, route or workout template you shared
  used to stay in the app's private cache until the app was next started afresh, which can be weeks;
  it is now removed the next time you open the app once it is an hour old, and sharing a new archive
  removes the last one. The archive is also shared under the report's file name rather than a random
  one.
- **A damaged database is shown, and can be saved or deleted.** When SQLite reports the file damaged
  the app still starts a new one, but the profile page now says the damaged file is kept, saves it
  with its log as a zip wherever you choose, and deletes it when told to; a second damaged file is
  numbered beside the first instead of replacing it.
- **Two more ways a damaged database could still be lost are closed.** An encrypted file whose first
  page fails its check no longer lets the original kept from that start's conversion be swept, and a
  damaged file that cannot be moved aside is left where it is — the start fails — rather than
  deleted.
- **The boot receiver is closed to other apps.** The receiver that starts the pedometer again after
  a reboot or an update was exported in the belief that the system's broadcasts needed it; they do
  not, and no other app can now start the app through it.
- **One permission fewer: vibration.** The app declared it for the dose alarm, but Android plays a
  notification channel's vibration itself and nothing in the app drives the vibrator, so the alarm
  vibrates as before and the privacy policy's table no longer lists it.
- **PWN.Health is free software.** The code is licensed under the GNU General Public License,
  version 3 or later (`LICENSE`), and the app is free of charge in both of its forms. The README's
  Licence section and §15 of the privacy policy say so, and the README says what that licence does
  not cover.
- **Turning the phone in the instant after Save no longer brings the form back looking unsaved over
  a row already written.** A second Save there wrote the row twice; the page that comes back now
  closes, or shows the meal's success page. The bin behaves the same way: a double tap no longer
  deletes twice and closes the entry under the editor as well, and a turn during a deletion no
  longer leaves a dead page over the deleted row.
- **A camera shot the app never came back for is removed from the cache.** A progress photograph, a
  meal or a label taken just before Android closed the app is deleted at the next start once it is
  an hour old, instead of lying there full size until the next shot.
- **The Thai dish photographs are credited to their author.** The About row for the food photo
  model's training sets named that set by its Zenodo number alone; its record names Paradorn
  Boonpoor, and CC BY asks for the name.
- **About says what the app's licence is and where its source is.** The page now states that
  PWN.Health is free software under the GNU General Public License, version 3 or later, with no
  warranty, gives the repository's address as text you can select, and the licence itself heads the
  licence texts — a store hands over the APK and nothing beside it.
- **A photograph of a medicine packet or of a workout template that the camera took and no screen
  came back for is cleared from the cache too.** It is deleted at the next start once it is an hour
  old, as a progress photograph, a meal or a label already was.
- **Settings saved just before switching tabs are now applied in full, reminders included.** Leaving
  the tab straight after Save used to cut the save short, so the profile, the reminders or the sleep
  detection could be left half done.
- **The notice shade on the home and blood-work pages slides down from the top again when it
  opens.** It used to fade in where it stood; it also slides back up before it goes.
- **A quick log tapped just before leaving the page — a drink, a cigarette, a bathroom visit — is
  now always written.** It used to be dropped when the page went away mid-write; the answer about
  allergies on the profile is kept the same way.
- **The diagnostics report, shared to a mail app from About, now opens addressed to the developer,
  as the privacy policy says it does.** The mail used to open with no address; other apps ignore the
  field, and the person still sends the report themselves.
- **About now says what GeoNames is used for — the towns on the map.** Its row used to repeat the
  line for the offline map.
- **A full disk no longer crashes the app on every start while it records the day it was opened.**
  The day is tried again thirty seconds later.
- **The resting burn and walking time on the home cards now catch up the moment you return to the
  app, rather than a minute later.**
- **The tick confirming a quick-log tap now stays up for its full three seconds when another
  button's tap is swallowed as a repeat.**
- **If saving an entry fails after you turn the phone, the editor now stays open with your entry and
  says it was not saved.** It used to close as though the entry had been saved.

### Backup

- **A restored plan follows its template.** A backup brought into another installation gave each
  template a new id there and left its plans with the old one, so a plan pointed at whichever template
  the new phone held under that number: Health Connect got that template's exercises, its reminder
  fired for it, and deleting that template would now have taken the plan along. Plans take their
  template's new id now, and one whose template the file did not carry points at none.
- **A lab sheet's name in a backup is held to the whole of itself.** The import checked the name the
  way it reads a reference, which stops at `?` and `#`, and then wrote the photograph under
  everything that followed, so a crafted file could create new files elsewhere in the app's data, a
  rollback journal beside the database among them. A name that is anything but a bare file name is
  now read past.
- **Importing an older backup no longer puts the old version of an exercise back into a workout
  template changed since.** A template already on the phone keeps its own exercises, and only a
  template the import brings comes with the file's.
- **The daily copy no longer rotates out the last copies of a record that was lost.** When the
  database is damaged or its key is gone the app starts an empty one, and seven nights of copying
  that removed every copy made before; those now stay in the folder until you remove them, and the
  copies made since rotate among themselves.
- **One backup import at a time.** While a backup is being imported the page says so and its import
  and export buttons wait; a second import begun beside the first — a double tap on "Decrypt and
  import" was enough — used to sweep the first one's photographs, report them as not restored and
  bring some back twice.
- **A restored profile takes effect at once.** Reminders and medicine alarms, and the tobacco and
  cycle widgets offered on the home screen, used to follow a profile restored from a backup only
  after the app was left and opened again.
- **A phone set up from another by Android's transfer is now its own installation for backups.** The
  two used to sign their files with one name, and importing one phone's backup into the other
  matched unrelated rows by id: entries were dropped as already there, doses moved to another
  course, or the whole import failed.
- **A failed automatic daily copy is now said on the profile's front page, with the reason.** It
  used to be written only under the switch in Local data, so copies could stop for a month, or for
  good on a phone set up by transfer, without a word.
- **A password-protected export is harder to guess open.** It now derives its key with 600,000
  PBKDF2 rounds instead of 200,000 and asks for a password of at least eight characters. Files
  sealed by earlier builds open as before.
- **One very large lab-sheet photograph can no longer end every export.** Its bytes are written a
  slice at a time instead of being held whole several times over. Importing a copy that carries such
  a sheet still reads it whole and can run out of memory.
- **A backup file can no longer plant a number that ends every later export.** A figure that is not
  finite, such as a quoted "Infinity" in a hand-edited file, is read as absent or as zero, and where
  the figure is required the file is refused, as it is when the figure is missing.
- **An import that fails on an incomplete or damaged file now says so in your language.** It used to
  show the program's own English words, such as "truncated backup", inside a translated sentence.
- **The summary after an import counts the days it actually changed.** Importing the same file twice
  now says zero, and the summary also says how many entries stayed out because you had deleted them
  here.
- **Opening the app no longer deletes a backup that is being written.** It could delete the copy in
  progress, and with it that day's file in the daily folder; the export now keeps hold of its
  working file, and the clean-up at start leaves files less than an hour old alone.
- **A backup's entry whose day is not a date is no longer imported.** Such a row sat in the database
  where no page could show or delete it; the import now leaves it out, and its summary says how many
  were left out.
- **The password typed for a protected export is cleared when you leave the Data page.** It used to
  stay filled in for as long as the Profile tab was open, where the eye button could show it to the
  next person holding the phone.
- **An export's or an import's result is now shown even when the phone was turned or the Profile tab
  left while it ran.** The result used to be written into the page that started the job and was lost
  with it; it now waits for whichever Profile page is on screen, and a wrong password found after
  the page has gone is reported as an ordinary failure.
- **A backup the file provider will not open now says so.** The import used to show "Import error:"
  with nothing after it; it now says "The file could not be opened", and a failure with no message
  of its own names its kind.
- **The backup password fields are emptied once the protected backup's file has been chosen.** The
  password used to stay in them for as long as the Data page was open; after a failed export it now
  has to be typed again.
- **A restore no longer brings back an old work session beside its correction.** A session from
  another phone's backup that shares worked time with one already here stays out, as the editor
  refuses it, and the import's summary says how many.
- **Photographs a restore killed half-way had parked are cleared at the next start once they are an
  hour old.** They used to wait, unseen, for a next import; a restore still running is left alone.
- **A progress or walk photograph whose row stayed while its file went missing comes back from a
  backup under its own row.** It used to be skipped as one already here.
- **A backup holding a day that is not a date in its steps, hours, barometer, photographs, chats or
  courses is refused as damaged.** Such rows used to be stored where no page can place them; the
  import is now undone as a whole.
- **Restoring the profile refuses impossible figures.** A weight outside 20–400 kg, a height outside
  50–250 cm or a step goal of nothing marks the file as damaged instead of replacing your profile;
  the limits are the profile editor's own.
- **The export button now reads "Export a backup".** It promised everything, but the app's settings
  (colours, the Work screen's days off, long day and workplace) are not in the file; the privacy
  policy now says so, and that a move to a new phone carries them.
- **An export no longer holds the database while it encodes photographs.** The rows are read in one
  transaction and the pictures written after it, so the step counter and a recording route are not
  kept waiting.
- **A day counted half on the old phone and half on the new one keeps all its steps after a
  restore.** Its total is raised to the sum of its merged hours instead of keeping the larger half.
- **The daily copy no longer loses access to its folder once hundreds of files are attached to
  records.** Android keeps a limited number of such holds for an app and drops the oldest; the copy
  now renews its hold on the folder before every copy, so the system never drops it as the oldest.
- **An export cut off halfway no longer leaves a plain copy of the whole record in the app's
  cache.** The working copy loses its name the moment it is opened, so nothing of it is left behind
  once the app is gone.
- **A password-sealed backup opens with its password however the keyboard put together the accented
  letters in it.** An "é" typed as one character and as an "e" with a separate accent gave different
  keys, so on another phone the file could answer that the password was wrong.
- **A restored backup brings lab-sheet photographs back only under the names the app's camera gives
  them.** A file under any other name or extension, a web page among them, could be put with them,
  and from there handed to a viewer or copied into the doctor's archive.
- **Restoring this phone's own backup no longer fails as a whole when one of its entries' numbers is
  taken here by an unrelated entry.** That entry is restored under a new number.
- **A deleted line of the medical history stays deleted.** Restoring an older backup used to bring
  back a diagnosis, an operation or a pregnancy deleted since; the deletion now leaves a tombstone
  the restore respects, on this phone and on any phone the backup is carried to.
- **A protected backup picked just before the screen rotated or the tab changed no longer leaves its
  copy behind in the app's cache.**

### Labs

- **A lab sheet's photograph goes with the result.** Deleting a lab result, an allergy panel or an
  examination left the photograph the camera took for it in the app's files, and in every move to a
  new phone, for good. It is deleted with the result now, unless another saved result names the same
  shot; one no saved result names, taken off the form or left on one never saved, goes at a fresh
  start once it is an hour old. The copy a PDF is read through, which Android closing the app
  mid-reading left in the cache, goes the same way.
- **A lab result that shares a cell with its unit keeps its last digits.** Where the OCR handed back
  "131 U/L" or "251,000 /µL" as one box, the reader took the final "1" for the "I" of "IU/L" and the
  final "1000" for the multiplier of "×1000/µL", so an ALT of 131 was filed as a normal 13 and a
  platelet count of 251 as 25; a digit that continues a figure is now the figure's.
- **Bilirubin asks which unit the sheet printed.** Its µmol/L and mg/dL fields both took 1 to 35, so
  a sheet's 2.5 mg/dL, raised, was kept as 2.5 µmol/L and read as normal; the field now carries the
  same unit choice as vitamin D and shows the converted figure before saving.
- **A sheet being read survives a turn of the phone.** Reading a photo or a PDF stopped without a
  word when the screen rotated or the theme changed, and the page asked for the file again; the
  reading now carries on while the activity is rebuilt, an allergy panel's too, and the page that
  comes back shows where it has got to.
- **A sheet from years back keeps its date and shows among the results.** The import put any date
  since 1990 into the form while the date picker and the results page stopped ten years back, so
  an old sheet's results were saved under a day the results page never showed; the import, the
  date picker and the results page now all go back forty years.
- **The name of an attached lab file is asked for once.** It used to be asked of the file's own app
  at every redraw, and on a document's form at every letter typed into its note; it is now asked
  once for each file, and off the main thread.
- **A lab sheet no longer fails over a digit of another script in a printed range.** A range that
  came back from the recogniser with such a digit failed the whole file; that one range is now left
  unread.
- **Adding allergens from an allergy panel no longer overwrites the weight and height the profile
  was set up with.** The lab page was handed the profile with the latest measured weight and height
  laid over it, and saving the allergens wrote those back into the row the profile was set up with;
  it is handed the stored row now.
- **Glucose, cholesterol, LDL, HDL, triglycerides, creatinine and uric acid now ask which unit the
  figure was typed in, as bilirubin and vitamin D do.** A glucose of 12 mmol/L copied under the
  imperial setting was kept as 0.67 mmol/L and marked far below. Haemoglobin alone is left without
  the choice, and no field without it accepts a figure in its other unit any more.
- **A lab sheet being read now carries on through a turn of the phone or a theme change instead of
  starting again from its first page.** A locked PDF is asked for its password once, and the pages
  waiting for a date are still there afterwards. An allergy panel being read carries on in the same
  way.
- **Lab results now go back forty years instead of ten.** A sheet out of an archive is dated as
  printed, its year can be picked in the form, and the results page shows it. A 2014 sheet used to
  be saved under today's date, with no way to choose its own.
- **A lab sheet imported after its figures were typed by hand now leaves its file with those
  results, along with the laboratory's name and printed ranges where none were saved.** The page
  said the results were already saved and the file was attached to nothing.
- **A PDF whose first page carries only the heading and the collection date now dates the table on
  the page after it.** The date was dropped, and the results were filed under today.
- **A ferritin of exactly 200 ng/mL (150 for women) and an alkaline phosphatase of exactly 120 U/L
  are inside their bands, as WHO's "above 200" and the printed 30–120 interval say.** They were
  marked raised under ranges printed as ending at 199 and 119.
- **A date written with dashes on a lab sheet is read the way a slashed one is: by the sheet's other
  dates first, then by the phone's region.** An American sheet's 03-04-2025 was read as 3 April, and
  its 01-13-2025 was not read at all. The region puts the month first only where it writes month,
  day, year, and only a number from 13 to 31 in a date counts as evidence of the sheet's own order,
  so a telephone number written with dashes decides nothing.
- **Reading a laboratory's file after its figures were typed in ends on "File added to the saved
  results".** The page said "Results already saved", which read as if nothing had been done.
- **A page of a lab report that holds only the heading and the date is read once instead of four
  times.** Such a page used to be tried in every rotation; a date read with the page upright now
  settles it, so a multi-page PDF is read sooner.
- **Haemoglobin can be typed in g/L or g/dL.** Its field now offers both units, like the other lab
  fields, and shows the figure in the other one as you type; a sheet's 13.9 g/dL used to be refused
  under the metric setting.
- **A photographed lab sheet is kept at a page's reading size.** A shot from the camera is brought
  down to 3,508 pixels on its longer side — an A4 page at 300 dots to the inch — as soon as it is
  taken, instead of staying at the camera's full size, so backups with many sheets stay small enough
  to write and to restore. The smaller copy carries none of the camera's EXIF data, and the privacy
  policy, which said a sheet was kept as the camera wrote it, now says this.
- **The "Add a result" button under each attached file now names that file to a screen reader.**
  Every such button used to sound the same; the visible label is unchanged.
- **HbA1c in mmol/mol.** The HbA1c field now offers the IFCC mmol/mol beside %, the unit a British
  sheet prints alone, and converts it through the IFCC–NGSP equation. A 42 typed into the % field
  used to be refused. A sheet read in mmol/mol now fills that chip with its own figure.
- **Creatinine in mg/dL is printed to two decimals, as American sheets print it.** With one, 0.95
  and 1.04 both read "1".
- **The liver advice names the weight to lose in pounds on an imperial profile.** It said kilograms
  to everyone.
- **A Russian sheet's insulin in мкЕд/мл is read.** Its unit was not recognised and the field stayed
  empty.
- **A count printed with a Cyrillic "х10^9/л" is read.** It used to be left unread.
- **A sheet whose decimals all have three places after a comma (0,012; 15,500) is read with the
  comma as its decimal mark.** Nobody groups the thousands of a nought, so 0,012 is no longer read
  as twelve.
- **An Indian count grouped by lakhs, "2,51,000 /cumm", is read as 251 thousand.** It used to be
  left unread, its "2,51" could switch the whole sheet to a decimal comma, and a Western 1,234,567
  was broken up as well.
- **A visit saved in two goes, the cholesterol first and the HDL with the rest of the sheet later,
  still gets its non-HDL.** The remarks that read two figures off one sheet still see both.
- **Of two panels saved for one day, the one written later is the newest on the cards and charts.**
  It used to be whichever the database returned last.
- **On a lab chart each reading's dot is drawn in its own verdict's colour.** An older reading
  graded against another band (after food, at another age) no longer looks inside or outside the
  band drawn for the newest.
- **The lab form's "Fasting sample" switch now starts off.** A sheet copied or read in without
  touching it used to be graded against the fasting bands, which turn an ordinary result after a
  meal into a finding.
- **On a tablet or an unfolded foldable running Android 16 or later, a lab chart no longer shows a
  sideways button that the system would ignore.**
- **An allergy panel read from a file that also holds the visit's other tests no longer lists a free
  T4, a T3 or complement C3/C4 as an allergen when the sheet prints its reference range beside it.**
  Such a row was filed as a class 3 allergen and reached the panel's card and the doctor's report as
  positive.
- **An ALT of exactly 33 U/L for a man, or 25 for a woman, is now shown as normal, as the American
  College of Gastroenterology's 2017 guideline counts it, and its range reads "up to 33" (or 25)
  instead of "up to 32".** An Lp(a) of exactly 125 nmol/L is now marked raised rather than
  high-risk, because the ESC's high-risk level is above 125.

### Doctor reports and attachments

- **A file's read permission follows the saved entries, not the ×.** The permission to read an
  attached file was given up only by the × beside it, and at once, even when the form was then left
  unsaved and the entry kept the file, which it could no longer open after a reboot; a form left
  unsaved, a file picked over by another, a deleted entry and a lab result, which has no ×, never
  gave it up at all. The × only changes the form now. A permission no saved entry names any more goes
  at a later fresh start, never within an hour of the pick, or at once when a lab result is deleted,
  and that also lets go of the ones earlier versions left behind.
- **An attachment a restored backup names by a file path or a web address is no longer opened.** A
  tap on it closed the app or sent the browser to a page, and the archive for the doctor copied the
  file the path named, the app's own database included, into the ZIP. Only a file the person picked
  and a lab sheet's own photograph are attachments now.
- **A report saved while the screen turned is no longer an empty file.** Turning the phone, a theme
  change or Android reclaiming the app while the system's Save dialog was open left the document
  that dialog had created empty, with no message. The document now waits for the sheet to be built
  again and is written then, PDF or ZIP.
- **The report page offers nothing to save until the record has been read.** For a moment after the
  page opened the sheet was built on empty lists: "Nothing recorded in this period" flashed above
  the buttons, and a PDF saved in that moment said so under every heading. Save and Share now wait
  for the journal, the steps, the medicines, the visits and the medical history.
- **A report that could not be saved or shared says so until it is dismissed.** The error used to
  appear under the green tick for three seconds, like "Report saved"; it is now the error card the
  backup uses and stays until tapped. Save and Share are also off while a PDF or ZIP is being put
  together, so a second tap no longer starts another export over the first.
- **"Around each blood test" no longer counts a day as kept by hand when all it holds is a workout
  brought from Health Connect, a planned workout or an exercise left undone.** Those days read as
  days without a drink and thinned the alcohol before a draw, while the changes section of the same
  sheet already left them out.
- **The doctor's sheet no longer prints alcohol as grams "a day" in the food line before a GGT or
  urate result.** That figure was averaged over the days a drink was logged on, so a glass every
  fourth day read as a glass every day. The alcohol line under it, counted over every day the diary
  was kept, is the one figure for those weeks.
- **A blood test copied in under a date from before the diary began no longer decides where the
  doctor's sheet starts comparing.** One old result used to leave the first stretch of a 24-month
  sheet empty and every figure "not compared".
- **A report closed too early no longer leaves an empty file behind.** A document the system picker
  created used to stay empty when the report page was closed before the sheet was ready to be
  written into it; the file is now removed instead.
- **The sexual-activity question on a doctor's report no longer promises symptoms.** The question
  that adds sexual activity to a report, and the line under its row, said symptoms came with it;
  that section carries the counts and the protection, and the symptoms are a row of their own with
  their own question.
- **The report preview says sexual activity is included whenever its heading is on the sheet.** It
  now reads "Sexual activity: included at your request" over a period with nothing recorded too; it
  used to say "not included" above a sheet that printed the heading.
- **A very long entry on the doctor's sheet continues on the following pages.** An entry such as an
  attachment described in several thousand characters used to run over the page number and off the
  paper.
- **The doctor's sheet prints steps in Latin digits and writes the date year first in Chinese,
  Japanese and Korean.** The steps a day now use the same digits as every other figure, on a phone
  set to Arabic too, and in those three languages the international date reads 2026年9月5日 instead of
  "5 9月 2026".
- **The alcohol line of the doctor's sheet says how big its standard drink is.** It now names the
  size it counts in, 10 g of ethanol, since a "standard drink" is 14 g to a reader in the United
  States and 8 g in Britain.
- **The "Source" line on the doctor's sheet names where food and sexual-activity entries came
  from.** Under Food and under Sexual activity it now names Health Connect or an imported file where
  the entries came from one, instead of always saying "Entered by hand".
- **The cycle section of the doctor's sheet counts the gaps it leaves out.** Gaps between periods
  that fall outside 15–60 days are now counted and said to be left out of the average, instead of
  being dropped in silence; and the median length of bleeding over an even number of periods is the
  mean of the two middle ones, not the longer.
- **The doctor's sheet puts a word beside the average mood only after a week of answers.** A word
  such as "Persistently low" is printed once mood was answered on at least seven days; with fewer
  the sheet prints the average and the count alone.
- **The report page follows the app's age gate.** Under sixteen it offers no sexual-activity,
  symptom or morning check-in rows, and under eighteen no alcohol and tobacco row, so a doctor's
  preset no longer prints that heading for a child; the preview before sending no longer says
  "Sexual activity: not included" to someone who is not offered those rows.
- **A report no longer opens with pregnancy status, contraception and the menstrual cycle already
  ticked.** "My own choice" leaves them off too: they go in when you tick them or pick a doctor they
  were written for.
- **The examination date of an attachment is picked from a calendar.** It used to be typed as
  YYYY-MM-DD, and typed in Arabic or Hindi digits the date was refused and the description could not
  be saved. A button beside the date removes it, since the date is optional.
- **The doctor's sheet no longer calls a stretch before a blood draw "not recorded" two sections
  after describing it.** The comparison of two results now counts 60% of the window in whole days,
  rounded down, as the blood-test context does: 16 days of 28 and 25 of 42 are enough, where it
  asked for one more.
- **A share of days that moved by exactly twenty points is marked "changed" on the doctor's sheet.**
  With unequal numbers of answered days such a share — wound up, head or neck pain, morning
  erections — used to fall just short of its own threshold.
- **A 24-month sheet over a diary of fourteen to sixteen months no longer says its two stretches
  fall in different seasons.** They cover largely the same days of the year, so the sheet now says
  nothing about seasons there.
- **The "Generated by … on …" line of a doctor's report writes its date the way the report's country
  does.** Every other date on the sheet already did; this one kept the international form.
- **The examination date of an attached file can no longer be set in the future.** Days ahead are
  greyed in its calendar, as they are in the medical history's, and a future date that came with a
  restored backup is not accepted on OK.
- **A report page returned to from under another screen no longer shows an export error.** The
  document it had been waiting to write was removed when the page was left, yet the page that came
  back still tried to write into it; the wait now goes with the document, and the report is saved
  again from its button. A turn of the phone under the Save dialog still finishes the file as
  before.
- **"Select all" on a doctor's report no longer speaks of a sensitive section to a profile that is
  offered none.** Under sixteen the list has no such rows, yet the line under the button still read
  "Everything except the sensitive section"; for such a list it now reads "Every section on this
  list".
- **The doctor's sheet no longer marks food energy, sugar or the saturated-fat share as changed when
  they moved off nought by a trifle.** A fifth of nothing is nothing, so any change was marked, down
  to "0 g a day → 0.0 g a day"; each now needs 100 kcal, 4 g or a point of energy as well as a
  fifth.
- **The steps beside a blood test are rounded as the changes section rounds them.** One window no
  longer prints a step apart in the two places of the doctor's sheet.
- **The doctor's sheet names the courses and conditions begun or ended inside the two compared
  stretches as well as between them, under "From the start of the first stretch".** The row read
  only the gap between the stretches, so a course begun in the first week of the last one, which
  could have moved its figures, went unnamed.
- **A sexual row the sheet does not print, or a planned workout, no longer sets the start of the
  stretches the doctor's sheet compares.** Such a row could put the date the sexual diary began on
  the sheet, and a plan is set a day ahead; only rows the sheet reads now start the stretches.
- **The doctor's report labels the bedtime and waking-time rows as the standard deviation they
  are.** "Bedtimes apart by" read as the gap between the earliest and the latest night.
- **An attachment restored from someone else's backup can no longer open the app's own cache.** A
  tap on a reference into the app's provider now opens only its own pictures, where it could hand a
  viewer the backup being written.
- **The doctor's sheet no longer prints the caveat about the app's reference bands under a vitals
  section that prints no band.** An armpit temperature or a child's blood pressure has no band, yet
  the line about bands stood under every vitals section.
- **The dates under the report's period choice are written in the chosen country's order.** They
  were written in the international order while the preview and the PDF used the country's, so
  Germany now reads 05.03.2026 – 05.09.2026 there too.
- **A second tap on Continue in the report's Share preview no longer opens a Save dialog as well.**
  A tap before the sheet closed fell through to saving the PDF.
- **The report's section picker keeps ticks not yet applied through a turn of the phone.** A
  sensitive section's open question stays open too; both used to be lost.
- **With nothing ticked, the report page now says so even when the allergy question is answered or
  an allergy panel is on file.** Those are on every sheet, yet they counted as something chosen and
  the line disappeared.
- **Lists on the doctor's sheet and in the doctor picker are joined with the language's own comma.**
  They used a Latin comma in every language; Japanese and Chinese now read 、 and Arabic ،.
- **A report the system cannot open for writing now fails with "The file could not be opened".** The
  error used to be empty.
- **Attachments with long names in Cyrillic or other non-Latin scripts now unpack from the report's
  ZIP.** Names were cut at 160 characters, which in Cyrillic is more than a file name may hold, and
  the cut could take the extension; they are now cut by bytes and keep their file extension.
- **The attachments list in the report's ZIP is marked as UTF-8 for spreadsheets, and a cell
  starting with =, +, - or @ is written as text.** A spreadsheet opened Cyrillic names in the
  system's code page, and such a cell could be run as a formula.
- **The Visits row of the report picker, and the privacy policy, now say that the doctor's name, the
  place and your note go on the sheet.** The row said only "Appointments marked as attended".

### Imports

- **An import Android cut off leaves no copy behind.** The exported file is copied into the cache for
  the import and deleted when it ends; an import Android closed the app in the middle of left its
  copy there until the cache was cleared. The next fresh start removes it now, unless another import
  is running.
- **A night or a period day already in the journal is not written again by the file import.** The
  import recognised only what another file had filed, so a night Health Connect had brought or one
  typed in by hand got a second row and the day's sleep read as the sum of the two; a waking day
  that holds a measured night is now left alone, a night guessed from screen use gives way to the
  imported one, and a day keeps one period start and one end. Weighings, meals and sessions that
  also arrived through Health Connect are still imported a second time.
- **An archive the import could not walk to its end is no longer reported as read whole.** One that
  holds more than 20,000 entries, or more than 512 MB of files that are read, was walked up to that
  limit and the part that was read was reported as all of it; the report now counts it with what was
  skipped.
- **A run, a ride and a row imported from a file are filed as what they are.** Strava's and Fitbit's
  "Run", Strava's "Ride" and the "Rowing" of Apple, Garmin and Strava were filed as strength
  training or as a barbell row, so a run was costed at a lifting MET and counted on top of the day's
  steps; the session's own type is now asked before its title, so a run named "Lunch break" is a run
  too.
- **Two of the same thing on one day are both imported.** A diary that keeps no clock — Cronometer
  without timestamps, Fitbit's food and water logs, MyFitnessPal's exercises — files the day at
  midday, so a second coffee at breakfast, or every glass of water after the first, shared a name
  with the first and was reported as "already here". Each is a row of its own now, and opening the
  same export again adds only the ones that were lost.
- **The resting heart rate in an export file lands on the resting card.** Apple Health, Garmin,
  Fitbit and Oura state a resting rate for the day; it was imported as an ordinary pulse and mixed
  into that series, and is now the vital of its own with its four-week median. Days imported before
  this keep the row they have.
- **Fitbit's resting pulse and nightly SpO2 sit on their own day west of Greenwich.** Both are one
  figure a day stamped with the day at 00:00; read as a UTC instant, that was the evening before in
  the Americas. They are filed at the day's midday now, and a history already imported is recognised
  rather than added a second time.
- **A Samsung Health watch's all-day pulse is one figure a day.** The export holds a ten-minute
  window for every stretch the watch measured, and each became a journal row — up to 144 a day; a
  day's windows are now their average, filed at midday as Google's daily table is, and a reading
  taken by hand stays a row of its own.
- **An exported file no longer doubles what Health Connect already brought from the same service.**
  A weighing, meal, session, drink or reading is left out on any day that service's own app has
  already published one, so importing a Fitbit or MyFitnessPal export over a synced year adds only
  the days that were missing.
- **The note under the resting heart rate names export files too.** It now says the figure can come
  from an export file as well as from Health Connect.
- **A file import now says what it could not read.** An archive too large to walk to its end is
  reported as only partly read, and rows whose date could not be read are counted instead of the
  file being reported as holding nothing.
- **Large exports are lighter on memory.** A Nike Run Club archive keeps only each run's summary
  while it is read, a loose Apple export.xml is no longer read whole before its own reader sees it,
  an archive entry larger than an eighth of the memory the app may use is skipped instead of being
  read whole, and an import that still runs out of memory says so in plain words.
- **Apple Health's oxygen saturation is imported as one figure a day.** The day's average replaces a
  row for every sample a watch took.
- **The same export opened in another time zone is no longer imported twice.** Opening the same
  Withings, Cronometer, Samsung Health or wger export after moving to another time zone used to
  import its weighings and readings a second time.
- **One damaged cell in an exported file no longer costs the whole import.** A figure that is not a
  number is read as blank, a date from 1970 or the far future leaves out its own row, and an endless
  name is cut short.
- **Garmin activities written in milliseconds and centimetres are read as such for the whole file.**
  A short run no longer comes out a hundred times too long beside correctly read ones.
- **An Apple Health export taken in the middle of a period no longer leaves a false end on that
  day.** The period stays open until a later export shows where it really ended.
- **A history imported from a service's export file is no longer doubled when Health Connect brings
  the same days afterwards.** The check used to work only when the file came second; a record from
  that service's app is now left out on a day its file has already filed, up to the file's last
  entry of that kind, so the evening of the day the export was made still arrives. A workout from
  Hevy or Strong is kept once, as the sets its file states, whichever came first, and a Health
  Connect session you corrected by hand is left alone.
- **A ZIP whose file names were written in an old Windows code page now imports.** It used to fail
  with nothing but "MALFORMED".
- **A wger weight file with no header row, saved by a Windows spreadsheet, is now read.** The
  byte-order mark at its front made the first date unreadable and the whole file was refused.
- **Hevy and Strong loads and distances written with a decimal comma (82,5) are imported.** A
  European spreadsheet re-saves them that way, and they used to arrive as sets with no weight.
- **A file import on a full phone reports that it failed.** It used to end the app with the card
  still spinning.
- **Picking a file from a slow cloud provider no longer holds up the screen.** The file's name is
  now fetched in the background, after the copy, rather than at the moment of the pick.
- **Workouts imported from a file are given their energy in the same step that writes them.** An app
  closed mid-import used to leave sessions at 0 kcal that a second import would not mend.
- **A password-protected or half-downloaded archive is reported in the app's language as damaged,
  cut off or locked.** The message used to be the library's own, in English.
- **Fitbit and Samsung Health meals are labelled Breakfast, Lunch and the rest in the app's
  language.** They were always labelled in English.
- **A Garmin activity or a Fitbit food whose name or brand is empty in the export is no longer
  titled "null".** Android reads such an empty field as the word "null".
- **A Cronometer heart-rate-variability reading is no longer imported as a pulse.** Its figure in
  milliseconds fell inside the pulse range and was filed as one; it is now left out, since the file
  does not say which measure of variability it is.
- **Water and caffeine logged with Apple Health food entries are counted once rather than twice.**
  The import reads every record inside a food entry as well, and the same record met a second time
  is no longer added to the day again.
- **A damaged or hostile Apple Health or TCX file can no longer run the app out of memory with a
  value that never ends.** A name, a value or a text is now cut at 64 thousand characters, far
  beyond any real one, and an element keeps at most 64 attributes.
- **A loose Apple Health export.xml that was cut off on its way to the phone is imported with a note
  that only part of it was read.** It used to be imported as a history that simply ends early.
- **CSV files saved as UTF-16, a spreadsheet's "Unicode text", are read.** Their rows used to
  disappear without a word.
- **The import report no longer names a second service that contributed nothing.** A Garmin archive
  used to be reported as "Garmin Connect + Fitbit"; only the readers that found a record are named
  now.
- **A Withings export whose body temperatures are written in Fahrenheit has them converted to
  Celsius.** Its other.csv names no unit, and every such reading used to be dropped as a temperature
  nobody has.
- **Tapping "Choose a file" twice in quick succession no longer opens two file pickers or runs two
  imports side by side.** Both pickers answered into one import, and a second import running beside
  the first could bring the button back and delete the copy of the file the first was still reading.
- **On the import page, the count beside each kind of imported record is written in the language's
  own digits.** It was printed in Latin digits beside a summary in the language's own.
- **The file-import card no longer lists wger among the training logs it reads.** It names wger's
  weigh-ins, which are all wger exports.
- **Importing a health export archive from any service other than Apple Health now unpacks it once
  instead of twice, which roughly halves the time a large Garmin, Fitbit or Google Takeout import
  spends reading.**

### Database encryption

- **SQLCipher stays on its 4.x line until a bump brings the migration with it.** How a file is
  encrypted is the defaults of the release that wrote it, and nothing pins them: a major release
  that changed them would read a healthy record as no database, and the quarantine would set it
  aside as damaged. A test now stops a bump past 4 that does not bring `cipher_compatibility` or
  `cipher_migrate` and a device test with it.
- **A Keystore that is slow to answer no longer costs an encrypted record its place.** A refusal was
  counted once for every part of the app that asked — the screen's two asks at launch, each boot
  receiver, each restart of the step service — so the three refused starts allowed before the file
  is set aside as locked could be spent in one launch or one boot. A refusal is now counted once per
  process and at most once a minute, so three of them are three separate starts across at least two
  minutes of silence.
- **The button under the encryption switch says what it does.** It read "Restart now" and only
  closed the app, which looked like a crash; it now reads "Close the app", and the change is applied
  the next time the app is opened.
- **The encryption switch says that the daily copies stay unencrypted.** With both database
  encryption and the automatic daily copy switched on, a line under the switch now says that the
  daily copies stay unencrypted in the folder you chose.
- **A record the keystore refused can be opened again.** After three starts at which the phone's
  keystore did not answer, the encrypted database was set aside under the name of a file no key
  opens: the profile called it another phone's and offered to delete it, and nothing tried it again
  once the keystore answered. It is now kept as this phone's own, its card offers to open it at the
  next start with the database begun in the meantime kept in its place, and the card for a file no
  key opens no longer claims it must be another phone's.
- **The encryption switch says what it protects against.** Its text promised protection whenever the
  storage is read without unlocking the phone and placed the key in secure hardware; it now says
  what the build does: a copy of the storage taken off the phone cannot be read, software with root
  on the phone itself is not stopped, and the key stays in this phone's keystore.
- **The first screen says when an earlier record is set aside.** When the phone's keystore would not
  answer and the app began an empty database, it opened on onboarding as if new, and the card
  offering to open the earlier record stood only in the profile, past the whole of onboarding. The
  welcome screen now shows that card with "Open it", and the reason when the last attempt to open it
  failed; it does not offer to delete the record there.
- **"Close the app" under the database encryption switch, and "Open it" on a set-aside database,
  stay greyed out while a backup export or import, a file import or a Health Connect exchange is
  still writing.** Both end the app, and used to end it in the middle of that work, leaving a backup
  cut short without a word.
- **A failed change to database encryption is explained in the app's language.** The line under the
  switch used to print the raw English error, often with a file path in it; it now says whether the
  keystore did not answer, no key opens a database set aside, the phone's storage is full, or the
  database files could not be read, copied or moved.
- **A database set aside because no key on the phone opens it, or because it was damaged, is named
  on the first page of onboarding.** Only one set aside by a silent keystore was, so onboarding went
  on as if there were no earlier record.
- **A ciphered database is no longer decrypted at a start whose encryption switch could not be
  read.** It is opened as it is and the switch shows On; only turning the switch off removes the
  cipher.

### Chat

- **The symptom chat no longer stops on a phrase split by punctuation.** A message such as "I'm on
  my period, pain is bad", «ломит, всё тело болит» or «выпил нурофен, плюс болит горло» — or two
  messages in a row whose last and first words happened to make up a phrase the chat knows — closed
  the app; a phrase is now read only where it stands within one clause, so the last of these names
  ibuprofen, never Nurofen Plus.
- **An undone dose stays undone after the app restarts.** A dose noted on its own in the chat and
  taken back with Undo was written to the journal again the next time the chat was saved after
  Android had closed the app in the background, because the fingerprint of its answers came out
  different in every run; it is now made from the answers' names, so only Save again or a changed
  answer brings the dose back, and a course and its marks are no longer rewritten once after every
  restart.
- **Yesterday's late dose, taken after midnight, stays yesterday's.** Marking it in the chat with a
  time past twelve — confirmed with today's date, or chosen on the clock — marked tonight's dose
  taken instead, or wrote the time a day early. It is now yesterday's dose at the time it was taken,
  and a time that has not come yet is asked again.
- **The chat's model leaves memory when the app leaves the screen.** With the chat open, pressing
  Home or turning the screen off kept the reader's session — about 100 MB outside the Java heap —
  loaded for as long as the process went on counting steps. It closes five seconds after the app
  stops now, as it does after the chat is left, and loads again when the app is back; a quick switch
  to another app and back still reuses it.
- **Words about the chest keep their questions after a friendly message.** A greeting, "how are
  you?" or "drank some water" written while the chat was asking whether the chest pain is there now
  left the rest of the chest's questions as a chip that could be skipped; the chat now goes on to
  them as soon as that question is answered.
- **A record card in a chat that runs past midnight counts "today" from the real day.** A chat begun
  before midnight kept its first day for its cards, so "today I drank 300 ml" written after midnight
  was offered and saved under yesterday; the card, its Add and the form it opens now count from the
  current day, while a chat for an earlier day and a chat read back still count from their own.
- **A chat that is over shows the files attached to it.** A file given to a chat was listed only
  while the chat was live: once it ended, or the next day, the chat read back without it, though it
  still kept the file and the right to read it, and the only thing left to do was delete the whole
  chat. A past chat now lists its files under what was said, each with the type, date and
  description given to it, and a tap opens one; adding, describing and removing stay with the live
  chat.
- **The chat history opens at the cost of the rows on screen.** The page drew a card for every chat
  ever had, and read each chat's whole text to name it, before its first frame; it is now a lazy
  list that draws and reads only the rows in view.
- **A fraction of a tablet is offered as it was written.** «1/2 таблетки» in a message came back at
  the dose question as the highlighted chip «2 таблетки» — four times the dose in what looked like
  the person's own words — and «1 000 мг» as «000 мг». A fraction or a range is now offered with
  both its figures ("1/2 tablet", «1-2 таблетки») or not at all, and the tail of a number written
  with a space is never offered.
- **More of the ways people say it bring the safety question.** "I want to take my own life",
  "ending my life", "hurting myself", «хочу себя убить», «наложить на себя руки», "I can't breath"
  typed without its "e", «рвота с кровью», and a count of tablets written as a word past twelve
  ("took twenty paracetamol") were read as nothing; each now asks its question first, as the forms
  already listed did.
- **The safety words of fourteen languages await a native speaker.** The words that bring the
  safety questions in Arabic, Chinese, Czech, French, German, Hindi, Indonesian, Italian, Japanese,
  Korean, Polish, Portuguese, Spanish and Turkish were written by the developer and have not yet
  been checked by a native speaker of each or by a safety consultant, so the chat makes no promise
  about these languages; English and Russian are the ones it answers for. Until that review the
  lists are kept level: the same plain sentence of each sign — someone unconscious or not
  breathing, a stroke, blood vomited, too many tablets — brings its question in every one of them,
  and no language is left with a handful of words where its neighbour has dozens (Korean had 18 of
  suicide where Spanish had 49). The app is open source: a speaker's correction is a change to
  `SafetyWords.kt` and to the sentences of its tests.
- **The chat reader's notices now cover the journal tower added after them.**
- **A night told to the chat by its length alone opens with those hours on the dial.** "Slept six
  hours" now opens the sleep form with the hours counted back from the waking time and Save lit; it
  used to open on 23:00–07:00 beside a box reading 360, with Save dark and nothing to say why. The
  length in the box now follows the hands when they are moved.
- **A message that begins with a space or a new line now gets its record cards.** Pasted or dictated
  words often begin that way, and every card of such a message used to be dropped without a word;
  the chat now reads the same trimmed words it keeps.
- **A record card now reads its day from more than four exact words.** "Yesterday evening", «вчера
  вечером», «позавчера», "gestern", "ayer", 昨天 and their like in every language of the app file
  under that day rather than under today.
- **A chat reader loads only with the calibration its manifest names.** The loader now holds the
  calibration file to the manifest's SHA-256, as it already held the tokenizer and the schema;
  thresholds made for another model used to load and would have raised the safety questions at the
  wrong certainties.
- **A chat reader that fails to load or to run says why in logcat.** The reason and the exception
  are written under the tag `ChatModel`, so a failure only a phone has, such as an operator missing
  from its ONNX Runtime build, can be read from a bug report; the chat still falls back to its
  rules.
- **The chat reads the time of a dose more carefully.** "Today" alone no longer takes "Just now"
  away or asks when a course's dose taken on time was taken; «в 2 раза больше» and "as 2 doses" are
  no longer read as two o'clock; «в 11 дня» is 11:00 rather than 23:00; and «сегодня в 8» or "took
  citalopram at 8" ask morning or evening instead of assuming one.
- **A medicine chat left open past midnight keeps its days.** A course started "from tomorrow
  instead" no longer moves a day on when the question about food is answered after midnight, and a
  course's dose chosen before midnight is marked on that evening, not on the coming one, whose
  reminder it used to silence.
- **"I didn't take it" is no longer noted as a dose taken.** After words such as «не выпила нурофен»
  or "forgot my tablets", the question "When did you take it?" offers "I missed a dose", which notes
  nothing and says what to do about a missed dose; and one of your own medicines is no longer marked
  taken by the single tap on its name.
- **The chat is as you left it after a look at its history.** Words typed and not yet sent, changes
  made on a record's card before Add and the place the list stood at come back with the chat, and a
  short look no longer starts a new sitting, so the chest is not asked about again within a quarter
  of an hour. The history stands where it was scrolled to when a chat opened from it is closed.
- **An answer tapped twice is taken once.** A second tap that landed before the chat had moved on —
  "Turn on" for a new course's reminders is the one that takes a moment — could write the same
  answer twice, or into the topic the chat started next.
- **A file attached while Android had put the app away no longer disturbs the chat.** The file comes
  back before the chat has read its records, and was written against an empty picture of them, which
  could close a topic early; it now waits for the chat to be ready, as does the draft kept when the
  page is left before then.
- **The chat says when something was not saved.** A write that failed, and an Undo or a Save again
  that did not go through, used to pass in silence, so a dose or a mood could be missing with
  nothing to show for it; a card now says "Not saved" or "Not undone" and stays until it is tapped
  away or the next write succeeds.
- **"He is not breathing" is asked about at once.** The chat now asks whether someone is not
  responding, and then whether it is very hard to breathe, when a message says a person is not
  breathing or has stopped, in all sixteen languages; a blocked nose ("нос не дышит") asks nothing,
  though the words must stand together: "упал, разбил нос, не дышит" asks both. A count of tablets
  in the Russian genitive ("около двадцати таблеток") is read as the count it is.
- **The safety questions are read more evenly across languages.** Plain sentences that were missed
  in one language while their neighbour's were read — "숨이 막혀요", "意識がありません", "He vomitado sangre",
  "Mio padre vomita sangue" — now bring their question, and an app that does not respond or a gym
  without air conditioning no longer does. The words in the fourteen languages besides English and
  Russian are still the developer's own and have not been reviewed by native speakers or a safety
  consultant; corrections are welcome.
- **A can of cola with its time is still a can of cola.** "Выпила всю банку колы в 20:00" or "I had
  a whole bottle of water at 8:30 pm" no longer asks whether more of a medicine was taken than the
  packet says; the hour or the amount beside the drink used to switch that check off.
- **A rower's stroke rate is not a stroke.** "My stroke rate was 24", "breast stroke", "the app is
  not responding", "the screen is unresponsive" and "бот не реагирует" no longer bring the question
  about a stroke or about someone unconscious, and "saddle sore" is no longer offered as low mood.
- **"Three days" is no longer three in the afternoon.** In Russian «пью нурофен 3 дня» offered 15:00
  as the time of the dose; «дня» after two, three or four now names a time only behind «в», «около»
  or «к», and a count of days no longer decides which half of the day the hour after it is.
- **A dose's time keeps its day across midnight.** A time said with no date — "at 8" — and confirmed
  before midnight was dated the minute the chat was finished after it; and for a course's dose
  picked in the evening, 00:03 chosen on the clock just after midnight was put a day back. The date
  is now fixed when the time is answered.
- **A dose you did not take is no longer offered as "Took: …".** Words such as «не выпила нурофен»
  put a chip in the queue that said the medicine was taken; it now says "Missed: Nurofen", and so
  does your bubble when you tap it.
- **A record that could not be written the first time now says so.** When building a new record
  failed, the chat showed nothing at all — no card and no "Not saved"; the failure is now said, and
  the next answer tries again.
- **"Not saved" no longer repeats after one failed write.** While an answer of a saved record was
  being changed, a single failure kept the warning coming back with every later message, though the
  record stood as saved.
- **A reading that comes back after you leave the chat is filed with the chat's real surroundings.**
  Leaving the page before it had finished loading could settle a late reading against an empty
  picture of your courses and records, and take away records the chat still meant to keep; the
  reading now waits until those have been read.
- **A record's card says when your last change to it was not saved.** It went on showing the old
  answers as saved, and only a flash that a tap dismissed said anything had failed. The chips under
  "Turn on" also wait while the reminders are being turned on, a past chat keeps its place when you
  look at the history and come back, and the chat's question is worked out again when you return
  after a quarter of an hour.
- **A blocked nose is not someone who has stopped breathing.** «Нос заложен, не дышит» and "my nose
  is not breathing" asked two emergency questions, and "I'm not breathing well" asked whether
  someone was unconscious; they now ask nothing, or only about breathing, while «ребёнок с
  заложенным носом, не дышит» still asks both. The question about someone unconscious also names not
  breathing, since it is asked for those words too.
- **More ways of saying "too many tablets" are read in Russian.** «Выпила около двадцати белых
  таблеток», «около двадцати парацетамола» and «выпила таблеток двадцать» now bring the question
  about taking more than the packet says. A rower's "500 strokes" no longer asks about a stroke, a
  spinning class is not offered as dizziness, and Japanese «反応しません» is read as someone not
  responding.
- **A chat about something else, titled by words with no spaces such as Chinese or Japanese, now
  gets a forty-character title like any other, and an emoji at the limit is no longer cut in half.**
  Such a title used to run one character past the limit before its "…".
- **A chat card now reads "1,500 ml" or "2.000 g" as thousands.** It used to take the separator for
  a decimal point and offer 2 ml of water for a litre and a half; kilograms and litres are still
  read as decimals.
- **Workout cards from the chat now take sets and repetitions written in Arabic, Devanagari and
  other digits, as they already did with amounts.** "٣ مجموعات" or "१२ बार" used to leave the field
  empty.
- **A symptom chat reopened after a quarter of an hour no longer starts the chest-pain questions
  when a card is ticked or a photo added.** Chest words in an earlier topic whose chest had already
  been asked about used to bring them back; they are now asked only when the topic holding the words
  is closed.
- **The chat now offers period and cycle cards only to a profile that has the cycle page, as the
  cycle widget already did.** Every profile used to be offered them, though only one with the cycle
  page has anywhere for them to go.
- **A dose said to be taken yesterday is now asked about beside every dose of the same medicine in
  the 24 hours round it.** The chat read only its own day and the day before, so a tablet taken the
  evening before yesterday was missed and "more than the packet says?" could go unasked.
- **A dose taken just before midnight and noted just after can be put on the evening before.** Given
  a time that has not come yet again after "That time has not come yet today", a one-off dose stands
  at that time last night instead of the minute it was noted.
- **Past midnight, the last dose of a course that ended yesterday can be marked from the chat while
  it is still inside its grace, as its reminder offers it.** The chat used to treat the course as
  gone and note the tablet as a dose on its own.
- **A course's times are proposed as they were said.** "Take it at 9 pm" or «в 9 вечера» proposes
  21:00, where only a time with minutes used to be read and 09:00 was offered instead; a bare "at 9"
  offers both.
- **In Arabic, Japanese and Chinese the chat's lists — a pain's areas in its title, what a mood was
  about, a course's times — are separated by the language's own comma.** The English comma was
  written into the code.
- **In a chat left open past midnight, a course's start chosen as "Today" is no longer shown as
  "Tomorrow", and the same pain counts how long it has gone on to the new date.** Both used to count
  from the day the chat began.
- **A record card added from the chat with an amount that is not a number is saved with no amount,
  instead of leaving the whole chat unreadable.** Such an amount was written into the saved chat in
  a form that cannot be read back, which lost the whole chat.
- **A record filed again says so.** A record taken back with Undo, or one its page refused, that is
  filed once an answer is changed now shows its "Added" flash, in a chat carried on later too; it
  used to come back to the journal without a word.
- **The chat follows a new time zone.** A chat left open across a flight now files what it records
  under the day where the phone is when the person comes back to it, the zone its medicine questions
  already reckoned in.
- **Turning the phone as a chat is deleted no longer brings it back.** The page used to return to
  the deleted chat, where nothing said was kept; it now moves on as it does unturned.
- **A chat that cannot be opened says so and can be deleted.** Its page used to show the top bar
  alone, with no Delete.
- **The chat's delete button now dims while a deletion is under way, as the pain page's does.** Its
  picture used to stay bright though the button took no tap.
- **An emoji pasted at the chat field's limit is no longer cut in half.** The field used to keep
  half of it at its end.
- **A past chat is sealed even when left at once.** Opened and left in the same moment, it used to
  keep what could put its records' earlier states back until it was opened again.
- **A record the symptom chat was asked to open by its moment no longer opens over another page when
  the chat is left before the record is found.** The search went on after the chat was gone and then
  opened the record wherever you had moved to.
- **A pain written as "6/100" no longer rests the pain ruler at 6 out of 10.** Only two digits after
  the 10 used to stop the reading; "6/10." and "6/10, …" are still read.
- **"Палец правой руки", "перелом правой руки" and similar phrases now propose the right side only,
  and a finger or toe takes the side said with its hand or foot.** Words such as «руки» and «ноги»
  can also mean one arm or leg, so they mean both sides only when no side is named; «болят руки»
  still opens the map of both arms.
- **"3 раза в неделю", "twice a week" and other words of how often no longer mark "about a week ago"
  (or a month, a year) as when a pain started.** Words of frequency are now read as such and mark
  nothing.
- **"My knee pain is back" no longer proposes a back pain as well, and neither do "позвонишь" or
  "позвонят".** "Back pain" itself is still read as the back.
- **The symptom chat no longer offers an amount such as "2.50 IU", "0.25 of a tablet" or "2.30
  pills" as a time of day to confirm.** Every unit it reads a dose by now marks the number before it
  as an amount.
- **In Arabic, Japanese and Chinese the symptom chat now joins its lists — the areas of a pain, the
  answers read back to you, the areas a question names and what a screen reader says about a sent
  painting — with that language's own comma instead of an English one.**
- **Deleting a symptom chat, closing a finished one and adding a record from its card now say "Not
  deleted" or "Not saved" when the database fails, and the chat stays as it was.** A database error
  there used to close the app, and after a failed deletion the chat silently stopped saving for the
  rest of the session.
- **A symptom chat carried on past midnight now sees the symptoms and pains logged after midnight,
  its own among them, when it asks whether something is new or the same as before.**
- **Adding water, a drink or a meal from a symptom chat's record card now saves the row and the
  card's mark together.** A failure in between used to leave a saved row whose card still offered
  Add, and a second tap saved it twice.
- **A dose of an earlier day whose time was not remembered now says so in its journal row: its chat
  card shows the date alone, while a dose given 12:00 shows 12:00.** The two used to be stored the
  same way, and a scheduled dose with no time said read "marked at 12:00".

### Bluetooth devices

- **The note under the Bluetooth section names the Keiser M Series.** It listed every protocol the
  scan speaks but the Keiser bikes' broadcast.
- **A malformed Bluetooth advertisement no longer closes the app.** While a search for devices was
  running, a packet Android itself could not parse — from any broadcaster in range, paired or not —
  reached the scan with no service list at all, and reading it ended the process along with whatever
  was unsaved. Such a packet now advertises nothing and is passed over.
- **A glucose record is saved once, whichever way the meter's clock was judged.** A meter running a
  few minutes fast had its newest reading dated on arrival by a sync made right after the test and
  by its own clock at the next one, so the same record went into the journal, the lab chart and
  Health Connect twice. A record is now recognised under either form.
- **A control-solution test is no longer saved as blood glucose.** A meter's check with reference
  liquid was saved with the rest of the transfer, graded as severe hyperglycaemia and published to
  Health Connect; it is now left out of the save and is never the figure shown on the card.
- **A second tap on Save or on Finish no longer writes the reading or the workout twice.** The card
  was cleared only after the row was written and Health Connect had been told, which can wait for a
  whole exchange; it is now cleared inside the tap, the confirmation appears as soon as the row is
  in, and a machine's last packets after Finish no longer bring the workout card back.
- **Pairing a cuff or a scale with a passkey is given ninety seconds.** When Android started the
  pairing by itself, the connection's thirty-second limit kept running under the system dialog and
  hung up in the middle of it; a pairing in progress now extends the limit, and a failure says that
  pairing did not finish.
- **A glucose transfer made only of control solution tests says why there is nothing to save.** It
  used to show a grey Save button with no explanation.
- **Stored readings sent together are all kept.** A blood pressure cuff, thermometer or scale that
  sends several stored readings at once no longer loses all but the last: the card says how many
  earlier readings came with it, and Save writes them all under their own dates, skipping any the
  journal already holds.
- **A machine or sensor that goes on sending for a moment after Disconnect or Take result no longer
  puts the card back on screen.** A reading that arrives after the tap is now dropped.
- **A search that fails to start while a device is connected no longer takes Disconnect off the
  page.** The failure used to be shown as the connection's own, which left the connection stuck.
- **After a glucose transfer is saved the page says how many records came.** It used to go back to
  “waiting for data”; it now also says when some of the meter's records could not be read.
- **A run recorded from a foot pod is saved with the distance of that run.** The pod's lifetime
  odometer used to be shown, saved and sent to Health Connect as the workout's distance; the
  distance is now counted from the first reading of the connection.
- **A Mi Body Composition Scale's impedance is read only once the scale says it has measured one.**
  A weighing saved early no longer carries a body-fat figure worked out from unfinished bytes.
- **A reading a device dated to another day now shows that date and time on its card before it is
  saved.** The weight card and the clinical card print it; a reading dated as it arrives is always
  today's and gets no such line. The date is shown, not corrected: Save still files the reading
  under it.
- **The Devices page says when a search could not start beside an open connection.** The button went
  back to "Search" and the page reported that nothing was found, about a search that had never
  begun; the line under the search button now says the search failed, and the connection is left as
  it is.
- **A glucose transfer says how many of its records will not be saved.** When a transfer mixes
  readings with control solution tests, or with results the meter itself marked invalid, a line
  under the count of received records gives the number Save leaves out; the count used to include
  them, and the journal got fewer without a word.
- **Earlier readings from a device's memory can be saved when the last one cannot.** When the last
  reading a blood-pressure cuff, thermometer or scale hands over cannot be saved, Save stays
  available and the card says how many earlier ones it writes; they used to be lost with it.
  Readings the device keeps for another person are now counted on the card instead of being dropped
  without a word.
- **A training machine that reports no time of its own is timed on a clock that does not move when
  the phone's time is set.** Changing the time during a ride no longer changes its length.
- **Disconnecting a training machine, or taking its result, puts its card away with one press.** The
  link's own report of the drop used to bring the card back with a second Disconnect to press.
- **A training machine whose link fails says why in a sentence, as the Devices page does.** It used
  to show a raw token such as "Bluetooth error: link:133".
- **Bluetooth cards print power, cadence and impedance in the units of the language showing, and a
  foot-pod workout's note gives its distance in the person's own unit.** The units were written in
  Latin letters and the distance always in km.
- **Reopening the device list during a Keiser ride no longer offers a Stop that froze the ride, or a
  new search that ended it fifteen seconds later.** While a broadcasting machine or scale is being
  followed, the list keeps filling and offers neither button.
- **Once a Mi Scale is chosen under a weight, a second one in range no longer writes its weighing
  into the form.** Only the chosen scale is listened to, as with a Keiser bike.
- **A glucose record the meter sent without a value is no longer saved as an empty Glucose row.**
  The line under the count includes it among the records left out, and the large figure on the card
  is never one the meter itself rejected.
- **A glucose meter with a large memory is no longer refused at every sync.** A sync always asks for
  every record, and one transfer may now hold 4,096 of them where 1,024 was the limit.
- **A glucose record the meter flags as a sensor, strip, temperature or device fault is no longer
  saved as a reading, and one flagged with a time fault is dated when it arrives rather than by the
  meter's clock.** The glucose standard says each of these flags makes the result unreliable; a low
  battery still lets the reading through.
- **A device whose pairing with the phone has gone stale is now said to need forgetting in Android's
  Bluetooth settings.** The page used to ask the person to accept a pairing request that never
  comes.
- **A COLMI ring that sends a message of its own in the middle of a sync no longer fails it.** A
  well-formed message the sync was not waiting for is set aside; a damaged one still ends the sync.
- **Turning Bluetooth off now ends the connection on the devices page and in the workout form, with
  a line saying Bluetooth is off.** A device used to be left shown as connected.
- **Saving a glucose transfer that holds only readings already in the journal says so.** It used to
  claim it had saved them.
- **A Bluetooth link left open when the phone was turned behind the app lock is now closed if that
  window never shows the device page again.** It used to stay connected until the app's process
  ended.
- **Tapping Save twice under a heart-rate strap now saves the pulse once, and a second tap under a
  glucose meter's records no longer replaces the "Saved" confirmation with "already saved".** The
  pulse card cannot be cleared while the strap keeps updating it, so a second tap within three
  seconds is passed over; the glucose records now leave the page inside the tap.
- **The device picker no longer checks the Bluetooth permission and radio every two seconds while
  the app is in the background.** It still checks while the app is in view, so a permission granted
  in Settings beside it in split screen is seen.
- **Searching for or connecting to a Bluetooth device without the nearby-devices permission now says
  the permission is needed instead of risking a crash.** The check now sits in the Bluetooth code
  itself, where it used to rest on every screen asking first.

### Medicine

- **"Later" brings the dose back when its quarter of an hour is up.** A dose put off was still held
  against what had been said before the button was pressed, so it stayed silent until that
  announcement's hour ran out, and one put off in the last hour of its grace never came back at all.
  It is now announced afresh at the moment it was put off until, ringing when the alarm is on.
- **The silent card after a ringing reminder now goes up every time.** The check for it was
  scheduled from the dose's own time while the wait was measured from the moment the ring was
  actually posted, so a ring set off by a logged meal, or by an alarm that had to start the app
  first, could leave the shade empty for up to an hour, and the hourly ring came round after one
  hour or after two. Both are now scheduled from when the dose was last spoken about; the card goes
  up two seconds after the ring ends, and the ring returns an hour after the card.
- **The medical history takes dates from decades ago.** Its form borrowed the medicine courses' date
  picker, whose years begin two before this one, and a Material 3 picker accepts no date outside its
  years, tapped or typed — a diagnosis from 2015, an old operation or a childhood vaccination could
  only be saved without a date. The form now has its own picker that reaches back to 1900.
- **The silent dose card survives the alarm page.** When the lock-screen alarm page ran out of its
  minute it cancelled the dose's notification by its tag, and on a phone that had slept through part
  of that minute this took away the silent card that follows the ring. The page now only closes;
  Stop sound, Open app and Back still take the notification down.
- **Muting the Medicine alarms channel no longer silences every dose.** With the alarm switched on
  in the app and its channel muted in Android's settings, no dose was announced at all. It now
  arrives as the ordinary Dose reminder card, as it does with the alarm switched off.
- **A medicine added from the form asks for what its reminder needs.** With reminders switched off,
  the form now asks whether to turn them on, in the chat's own words; where notifications were never
  allowed, it shows Android's prompt before the exact-alarm and lock-screen requests. The form used
  to ask for those two alone, so a medicine added there with reminders off or notifications not
  allowed was never announced, while the chat asked for all four.
- **A failed dose check no longer closes the app in the background or loses the next alarm.** The
  dose alarm and the notification's "I took it" and "Later" used to end the whole process, the step
  counter with it, when the database could not be opened, and a check that failed or ran out of time
  booked no further alarm. Errors are now contained, and the check is made once more five minutes
  later.
- **A pregnancy status or contraception method saved without a date is the current answer from the
  day it was written.** It used to sort under every dated answer, so "not pregnant" saved today left
  "pregnant since January" as the current one on the medicine tab, the history page and the doctor's
  sheet.
- **The Medicine alarms switch says when its channel is muted in Android settings.** The doses then
  arrive as ordinary notifications, and a button opens that channel's page; the line is read again
  on every return to the page, and full-screen alarm access is no longer asked for while the channel
  is muted.
- **The Medicine tab says, above the day's doses, when nothing will announce them.** Reminders
  switched off in the app, or notifications blocked on the phone: each comes with the one button
  that mends it.
- **A course taken on waking says, where that is chosen, what times it.** The night's sleep record
  does, and 08:00 on a morning without one, since the estimate from phone use is worked out only
  when the app is opened.
- **Doses that fall due together with the same instruction are one reminder and one alarm.** There
  used to be several identical ones; the one that remains says that several doses share it and that
  "I took it" marks them all, still without naming a medicine.
- **A dose marked twice in quick succession is written once.** Whether from the reminder, the alarm
  page, the widget or the Medicine tab, a double tap used to leave two rows for one dose, and an
  Undo that left it marked.
- **A pregnancy status or a contraceptive that has an end date in the past is no longer tagged
  "Current".** It has ended: the history page drops the tag and the medicine tab's card no longer
  gives it as the answer, and until a new answer is written down the card counts the records
  instead.
- **The "?" on an appointment's Place page names the Dubai Health Authority's register among its
  sources again.** Two thirds of the Emirates' places come from it (Dubai Pulse, December 2023
  edition) and stood under "Sources" with no source at all.
- **A date picked in the medical history can be removed again by a button under its field.** An
  "until" set by mistake used to mean deleting the record. The pickers no longer offer a day after
  today, nor an end before the start.
- **Opening the Place page straight after the booking form no longer reads the list of clinics
  twice.** The read the form began is kept and waited for, where it used to be thrown away and
  started again beside itself.
- **A new course no longer asks to allow notifications while its alarm rings.** The chat read only
  the quiet medicine channel, so with that card muted and the alarm ringing it asked for a
  permission the reminders did not need; it now goes by the same answer as the Medicine page and the
  alarm itself.
- **Correcting the note of an old undated pregnancy or contraception answer no longer makes it the
  current one.** An answer without a date stood from the moment it was last saved, so any later save
  of it put it over an answer given since; it now stands from the moment the answer itself was
  given, and backups carry that moment.
- **The location settings button under a Place page's list no longer closes the app on a phone that
  has no such settings screen.** There it now does nothing, as the same button in the location
  prompt already did.
- **On a Place page, a letter typed and rubbed out after tapping the location button no longer
  brings the nearest places back over the matches for the text.** Any change to the field after the
  tap now ends the list of nearest places.
- **The visit history lists doctors in the order of the app's language.** They were sorted by
  character code, which put "Ёлкин" before "Борис" and "Борис" before "анна".
- **The medical history card counts its records in the digits of the app's language.** The count was
  printed in Latin digits whatever the language.
- **The booking form no longer reads the list of clinics just to show the location button.** The
  button is always there and the Place page reads the list itself; a list that failed to load is
  read again next time, and the list is let go when the app leaves the screen instead of being held
  until the app closes.
- **The appointment reminder gives the visit's date in the phone's language and with its weekday.**
  It printed it as 2026-11-23; and a dose reminder sent in the same hour no longer holds the visit's
  reminder back to the next, which for a reminder an hour ahead came after the visit had begun.
- **A visit edited or deleted at the moment its reminder went out keeps the edit, or stays
  deleted.** The reminder wrote back the whole visit as it had read it before notifying, undoing the
  edit or bringing the deleted visit back.
- **A meal or a dose saved while the medicine check after it failed is no longer reported as not
  saved.** Trying again used to file it twice; the doses are checked again by the next alarm, the
  hourly check or the next write.
- **A course whose end date cannot be read, as after an edited backup, now gets its dose alarms.**
  Its doses were listed on the Medicine tab while no alarm was booked for them.
- **On the days the clocks change, a dose reminder's "Later" is measured against the same window the
  dose is due in.** In spring it was accepted for a dose let go before it came back, and in autumn
  it was refused an hour early.
- **A dose reminder no longer offers "Later" in the dose's last quarter of an hour, when it could
  not be kept, and its buttons no longer do nothing.** "Later" pressed too late stops a ring and
  leaves a quiet card, and "I took it" on a dose already marked takes the reminder away.
- **Tapping a dose reminder, or Open app on the alarm's page, opens the Medicine tab instead of the
  home page.**
- **Switching the medicine alarm or the reminders off and on no longer leaves a due dose without a
  notification for the rest of the hour.** Turning the alarm off stops a ring and keeps the card,
  and turning reminders back on announces a due dose again.
- **After a change of time zone, the Medicine tab places the day's doses and visits in the new zone
  within a minute.** It used to do so only once the tab was reopened.
- **Screen readers announce the title of the full-screen medicine alarm as a heading.**
- **Saving a course whose photo takes a moment to move no longer closes a different course opened in
  the meantime, which used to lose that course's edits.**
- **Correcting an appointment while its reminder goes out no longer brings that reminder back a
  second time.**

### Food

- **The food catalogue finds an English name typed in another script.** "Маффин" finds the muffins,
  "начос" the nachos and "мисо" miso, rows that carry no Russian: a query not in Latin letters is
  also spelt in them by the phone's own transliteration (BGN/PCGN for Russian, which writes х as kh,
  ж as zh and й as y the way English borrows the names), and what that spelling finds is listed
  after every row the query matched as typed, only while the page has room. A guessed spelling has
  to match the start of a name or alias, or of one of their words when the word typed has four
  letters or more, and forgives one slip only in a word of six, so "мед" does not fill the page with
  MCT oil. Japanese and Korean spellings of English words mostly do not come back close enough
  ("ビッグマック" reads "biggumakku"), so the catalogue's aliases still carry the common ones.
- **Some 140 classic foods are written as they are eaten and in the helping they are served in, most
  of them near the top of their group.** The ribeye stood as a raw USDA row with ounce and pound
  portions deep in the list; it is now a grilled steak of 240 g beside strip, filet mignon, T-bone,
  porterhouse, skirt and flank. Several rows held a different food than their name: the hot dog was
  a frankfurter with no bun, KFC's Original Recipe was skinless wing meat, Вишня carried
  sweet-cherry figures, the octopus was battered and fried, plain naan was whole-wheat. Cooked cuts
  such as tuna, sea bass and most of the steaks now carry cooked figures with the cook's salt in
  them, and others, cheesecake and green beans among them, the USDA food closest to what is served.
  Mussels, chili con carne with beans and boiled beetroot are new. Across them — окорочок,
  McChicken, lasagne, biryani, baguette, brie, smetana, dried fruit and nuts, tiramisu, coffees,
  wines, beer and spirits — the rows gain a Russian name where they lacked one, a first portion that
  is a helping (a steak, a slice, a 50 ml рюмка rather than "1 cubic inch" or "1 fl oz") and the
  names they go by in the app's other languages. Figures come from the freshest USDA dataset that
  has the food in that form: Foundation Foods of October 2026, then FNDDS 2021-2023, then SR Legacy;
  the vitamins follow the new figures.
- **An almond milk names its almonds.** The line that says what a catalogue food usually contains
  missed tree nuts on almond milk, almond butter and Brazil nuts, sesame on a dish with za'atar and
  sulphites on dried apricots: the phrase that keeps almond milk from being called dairy was hiding
  the almond as well, and a two-word name was not recognised in the plural.
- **A cheeseburger is a milk food and a swordfish is a fish.** The allergen line reads whole words,
  so a name with the allergen inside a longer word — cheesecake, milkshake, buttermilk, soybeans,
  swordfish, flatbread — or a ramen, an udon or lupini beans named nothing; some 120 more catalogue
  foods now carry the line, and a test walks the catalogue for the next such word.
- **A goal to lose or gain weight is acted on from eighteen.** Under it the day's budget stays at
  maintenance and the weekly energy advice says nothing, as the daily food advice already did; the
  goal starts with the year that opens at eighteen.
- **A word's last letter is no longer read as a zero on a packet.** The label reader took the "o"
  that ends "Białko" for the zero of "012" and filed 12 g of protein as 0.12 g, read "Valor
  energético 250 kcal" as a quarter of a kilocalorie, and stopped seeing "pro 100 g" as the basis of
  a German table, so its figures were logged for the 30 g portion printed beside it. An "o" is a
  lost zero only after a digit or where no letter stands before it.
- **The first food page no longer stalls as it opens.** The food catalogue is loaded in the
  background as the app opens, where a megabyte of it used to be parsed on the first food page; and
  the quick-entry widget's setup screen opens an encrypted record off the main thread.
- **The label page shows the sugar, fibre and sodium it read, each in a field that can be
  corrected.** It used to keep them unseen. A figure printed in milligrams is no longer taken for
  grams ("Salt 20 mg" was kept as 8,000 mg of sodium), and "Sodium 1,200mg" reads as twelve hundred.
- **Turning the phone or switching the theme no longer closes the dish sheet or the label crop.**
  The figures corrected on the label page, the chosen basis and the name stay; the photograph is
  read again behind them.
- **A product kept from its label under a name the catalogue also has is its own favourite now.**
  "Kefir" kept from a label no longer replaces the catalogue's row or is replaced by it, the label
  page no longer calls it kept when it is not, and it opens with its own figures rather than the
  catalogue's portion chips.
- **Turning the phone over a meal photo no longer recognises it a second time or loses what was
  corrected on the sheet.** The dish chosen, the portion, the weights typed and a product added come
  back as they were. A product label is likewise read once, so a field cleared by hand is no longer
  filled in again by a second reading.
- **The caffeine dialog keeps the drink being sized and the milligrams being typed when the phone is
  turned.** They were lost on rotation, while the amount of water was already kept.
- **The meal form says what a dish recognised from a photo, a dish put together or a catalogue food
  usually contains.** The allergens a person reported are named first, as the catalogue search
  already did, and that search was the only place the line appeared.
- **Water, unsweetened tea, diet colas and other foods with no energy can be logged as food.** The
  Add button used to stay dark for them.
- **Saving a meal again without changes keeps its figures exactly.** They reopened with one decimal
  place, so a second Save turned 12.34 g into 12.3.
- **Reading a packet's label frees the decoded photograph as soon as its pixels are copied, and a
  turned crop no longer keeps the unturned photograph in memory beside it.** On a high-resolution
  camera that meant two pictures of some 50 MB at once.
- **The dish sheet answers sooner after the photo model has run.** Matching its labels to catalogue
  names no longer folds all 4,995 names again for every word of every label.
- **A US nutrition panel's footnote, "2,000 calories a day", is no longer read as the kilocalorie
  half of an energy pair.** A 10-calorie drink was offered as 2 calories.
- **Two-column nutrition tables headed in German, Czech, Slovak, French, Spanish, Portuguese or
  Polish have their per-100 g column chosen as English and Russian ones already did, and salt is
  read on Ukrainian, Latvian, Lithuanian and Hungarian packets.**
- **Closing the label crop screen while it is cutting no longer leaves the cut photograph in the
  app's cache until a later start.**
- **The food catalogue no longer flashes "nothing found" while the first search runs.** It did so
  whether the page came back with a query already typed or the editor's catalogue dialog opened on
  the meal's title; until the first answer the list stays empty.
- **A saved dish whose stored weights read as NaN, infinity or a negative number drops those parts
  instead of adding them into the dish's calories and nutrients.** A damaged database or backup can
  carry such weights; the part is now treated like a row no longer in the catalogue.
- **Once a dish is saved, its row now reads "In your dishes: <name>" instead of a tick beside the
  bare name.** A screen reader announced only the name; it now hears that the dish was kept.

### Routes

- **A route records again after a stretch the phone could not see.** Left recording through a metro
  ride, a route measured every later fix from the station left behind, refused each as too fast to
  have been walked, and stood still for ten minutes after 2.5 km while the notification said it was
  recording; once fifteen fixes in a row are refused, the last accepted one is a minute old and the
  new ones lie further off than a walk covers, the line now starts a new stretch at the current fix,
  as after a pause, with nothing drawn or counted across the ride.
- **A ride the phone did not see is no longer counted as walking.** When the first fix after a metro
  or bus stretch was a walker's, all the distance since the last fix went to "Walking", its splits
  and its calories; a leg covered faster than a walk (7 km/h) or a run (25 km/h) is now counted as
  transport.
- **Stopping a route a restart had left open no longer closes the app.** On a phone with no step
  sensor the service stopped itself before it had declared itself to Android, and from the widget
  without the physical activity permission it had nothing to declare at all; the service now
  declares itself first, and the widget closes such a route itself.
- **A slow walk is no longer recorded as standing still.** A steep climb, or a walk at a small
  child's pace, stays under 2 km/h and within a couple of metres a fix, which is also what a stop
  looks like to GPS: after fifteen seconds the route called it one and dropped its metres, its climb
  and its energy. While the pedometer counts steps and the accelerometer hears the stride, or has no
  window to offer, the stretch stays a walk. Routes already recorded keep their labels.
- **A route saved to a file is the route, even when the phone was turned under the file picker.**
  The picker's answer could come back to a screen drawn afresh, before the route's fixes had been
  read again, and a file with no point in it was written and reported as exported. The file is now
  written from the database when the picker answers, and a route with nothing to write reports a
  failure.
- **Saved routes lists every route.** The list showed only the latest twenty, so an older walk could
  no longer be opened, shared or exported, though it was still on the phone and in every backup.
- **Pausing or resuming a route that a restart had left open no longer closes the app.** On Android
  14, when neither the location nor the physical activity permission is held, either tap ended it;
  the track screen now sends both as plain starts, as it already sent Finish.
- **A route finished while a backup was being written can no longer be picked up again a moment
  later.** The step service could read the route before its end was written, which left the GPS on
  and Start doing nothing.
- **A paused route asks for a position twice a minute instead of every two seconds.** A pause left
  standing no longer costs the battery what the walk did; a photograph taken in the pause is still
  placed, and recording returns to its usual pace on resume.
- **A recorded walk can be deleted.** The walk page has a bin beside the share button: after a
  confirmation the route goes with every point and photograph, files included, and a backup written
  before that no longer brings it back on this phone. A route was the one record that could only be
  removed together with all of the app's data.
- **The tilted map no longer stops following at a touch.** A tap that slid a pixel, or the finger
  left on the glass after a pinch, used to count as a drag and take the map off the walker; a drag
  now starts only past the system's touch slop, so a double tap zooms as it should. Two fingers
  drawn slowly up or down tip the camera, where only a quick stroke did.
- **The route pages write dates and numbers the phone's way.** Saved routes show their start in the
  language's own order of day, month and year and in the phone's 12- or 24-hour clock; counts and
  the energy figure use the language's digits; and the latest GPS correction is said in words rather
  than as an internal name such as UNCONFIRMED_JUMP.
- **Start says when location access is missing.** After the permission had been refused, Start did
  nothing visible; it now shows why the recording did not begin, and the Signal and route page has a
  button that opens the app's settings.
- **The map's return button is called "Back to the route".** It used to promise "Show the whole
  route", which a ride wider than the farthest zoom — about 48 km across a phone — could not keep.
- **Long recordings no longer stutter at every fix.** What the track screen and its map work out
  from a route's points — where the line runs, its heights and colours, the energy and the stops —
  is now computed off the thread that draws; several hours into a walk it used to hold the map up
  every couple of seconds.
- **A route stopped from the widget while the app was opening no longer leaves the recorder
  running.** The widget closed the route itself while the service still held it, which left the GPS
  on and a Start button that did nothing; the service now checks at every command whether its route
  was closed elsewhere, and stops as Stop does.
- **A route you deleted stays deleted on a second phone too.** Its deletion marker now travels in
  backups and is accepted from another installation's file, so an older backup imported there
  afterwards does not bring the route back; the same holds for a deleted entry that had come from an
  export file.
- **A route stopped in the moment after it was started no longer comes back as recording.** Its
  distance stayed in the notification and the next Start was ignored until the app noticed the route
  had been closed.
- **The map behind a walk across the antimeridian, on Taveuni or in Chukotka, draws the land and
  towns beyond it instead of sea.** The map was read only on its own side of 180°.
- **A GeoJSON export of a route that crosses the antimeridian is cut in two there, as RFC 7946
  asks.** Map tools no longer draw it the long way round the world.
- **A walk's calories charge a long gentle climb, and give back a long gentle descent.** A slope
  that no fifty metres of it was steep enough to show used to be costed as level ground; the rise is
  now carried on until the instrument can tell it.
- **A walk's kilometre splits add up to its total distance.** The total was measured on the Earth's
  ellipsoid and the splits and chart on a sphere, which left a stray tail split of a few dozen
  metres under a round total; both are now measured the same way.
- **With a walk's map open full screen, the map on the page behind it no longer reads the map,
  relief and ground a second time.** The hidden map decoded every view of the same camera again.
- **The signal page of a walk already finished says "finished" under Current speed and Movement.**
  It showed its last fix's speed and movement as current.
- **The buttons on the signal page that open location settings no longer close the app on a device
  whose Settings lacks that page.** A tap there now does nothing.
- **Saving a route to a file that fails part way no longer leaves an empty or cut-off file behind.**
  The file is deleted when the save fails; a file that already held something before the save began
  is left as it was.
- **After its first fix, a recorded route dates every point by the phone's boot clock, which never
  steps back, so a clock that jumps back mid-walk no longer scrambles the line, the splits or the
  GPX export.**

### Advice

- **A nap no longer counts as a short night.** The sleep advice and the "usual night" on the profile
  averaged sleep row by row, so an hour on the sofa was a second night of sixty minutes and could
  raise "short sleep" over a fortnight that averaged eight hours; both now count a day once, as the
  sleep summary does.
- **A bathroom log that was put down is not read as constipation.** The quarter's line to a
  clinician counted the rows in 91 days and took the weeks with nothing written as weeks without a
  stool; it now needs a record with no empty fortnight in it.
- **A fortnight whose mood averages exactly the middle of the scale no longer gets the advice
  written for a low one.** The advice kept a threshold of its own, one step apart from the mood
  card's, which calls such a fortnight balanced; it now follows the card's word, and like the card
  says nothing from the marks of a single day.

### App lock

- **The app lock holds when the window is rebuilt under it.** With the lock on, a dialog left open —
  a medicine's delete confirmation, a picker — came back on top of the lock screen when the phone
  was turned or the theme switched before unlocking, with its buttons working. A window built while
  the app is locked now composes none of the app until it has been let in once; a result a file
  picker hands back meanwhile is kept and delivered after the unlock.
- **The lock stays on top of what opens under it.** A window that had already been let in keeps
  running under the lock, and a sheet or dialog it opened there — from a reminder's tap, or a picker
  handing back its file — stood above the lock screen with its buttons working; the lock's own
  window is now added again whenever its prompt comes or goes and whenever another of the app's
  windows covers it.
- **The app lock asks for a strong biometric or the PIN.** A face unlock the phone itself rates as
  the weaker class no longer opens the app; on such a phone the prompt offers the fingerprint or the
  device PIN.
- **The app lock's description no longer promises face unlock.** It read "fingerprint, face or PIN",
  while the lock takes a fingerprint, another biometric the phone itself rates as strong, or the
  device PIN; the text now says so in all sixteen languages.
- **With the app lock on, a second window of the app opened from another app no longer leaves the
  first one unable to unlock.** The second window's prompt cancelled the first one's without a word,
  after which the first window's Unlock did nothing; both windows now go by one prompt.
- **Turning the app lock off, or the database encryption off while the lock is on, now asks for the
  fingerprint or PIN first, as turning the lock on always did.** Either switch used to go off with a
  single tap.

### Insights

- **The drinking and intimacy insights compare the days they name.** A yes-or-no record was cut into
  thirds like a step count, so three heavier evenings became "40 days after a heavier drinking day",
  thirty-seven of them sober; the card now sets the days with it against the days without, and says
  nothing until there are fifteen of each.
- **An insight card prints the mood ratings that were given.** The two levels in "you rated your
  mood … out of 5" were averages of what is left once the usual rating is taken out — figures near
  nought, sometimes negative; they are now the plain averages of the ratings on those days, with the
  difference and its range measured as before.
- **"Sleep and the day after" is measured against the right day.** A night is filed under the
  morning it ended on, and the insight paired it with the mood of the day after that one — a day
  late; it now pairs a night with the day it ended on.
- **A pattern card names a direction only when the reverse order was actually measured and found
  quiet.** A reverse with too few days to measure used to count as cleared.
- **"Patterns in your days" can now show a pattern that is clearly there.** The correction for
  having compared five things was applied to a figure it was never written for, and in practice let
  a card through only at about six standard errors; it now works at the one-in-ten rate it names.
- **The weather card's comparison is counted among the comparisons the app says it made.** The link
  between air pressure and mood was judged alone, outside the correction for having compared several
  things; it is now the sixth in the count the profile prints, and it and the other five are held to
  the correction for six, so a borderline pattern may no longer be shown.
- **The "Your usual" card no longer counts today's unfinished steps, water and fat.** They pulled
  each figure down every morning; the card now takes the thirty finished days before today.

### Measurements

- **A weighing under 20 kg no longer becomes the weight the app counts with.** A connected scale's
  reading is kept from 2 kg up, so a child weighed on it and saved turned into the profile's weight
  for every formula until the row was deleted. The row is still kept; the weight in effect is the
  newest one between 20 and 400 kg, the range the editor, the importers and Health Connect already
  held a weighing to, now written in one place.
- **The Tape tab saves only what was measured.** Save stays dark until one of the three rulers is
  moved; it used to be lit over rulers that read "Not measured" and wrote a measurement with no
  value in it.
- **A saved measurement is corrected as what it is.** The tab strip is no longer drawn when a saved
  reading is opened: tapping Sleep on an old weighing dated it today with the weight still on it,
  and tapping Pulse added a 72 nobody had measured.
- **A saved waist, hip or chest measurement reopens on its rulers and can be corrected.** It used to
  open as a card saying it was logged with a single tap and could only be deleted.
- **The weight and height the app counts with now come from measurements only.** A row whose kind
  was changed in the editor kept its weight or height and could set them, though the charts did not
  show it.
- **The hip and chest rulers start at 40 cm, the lowest figure those readings are kept at.** A hip
  saved below that used to reopen as not measured; the rulers now also reach 250 cm, so a reading
  imported above 200 cm shows on them.
- **A progress photograph whose record cannot be written no longer crashes the app and leaves its
  copy behind unlisted, and deleting one removes the picture before its record.** An app closed
  between the two now leaves an empty tile that can be deleted again, not an invisible picture of
  the body.

### Steps

- **Steps are counted again right after the app updates.** An update stops the step service and
  Android does not bring it back, so the pedometer stood still until the app was next opened and the
  steps of all that time arrived as one lump on one day, or were lost to a reboot; the service now
  starts as soon as the update is installed, as it does after a reboot.
- **Step counting starts when the permission is granted in Android's settings.** After two refusals
  the home card sends you to the app's settings page; coming back with the permission granted used
  to leave the card asking and the steps uncounted until the tab was left and opened again.
- **The ongoing notification no longer shows yesterday's steps as today's.** It did so when the app
  was opened after midnight before the first step of the day, and showed 0 after Android had
  restarted the step service; the day's total is now read again at every start.
- **"No steps for two hours" is no longer sent every hour when the phone is not the one counting.**
  It stays silent when the step service is not running or the permission is gone, when a band or
  Health Connect has given the day more steps than the phone, and when the phone's last step is more
  than a day old.
- **The step service stops when a route is finished on a phone that may not count steps.** On
  Android 13 without the physical activity permission it used to stay under a permanent "0 steps
  today" notification with the motion sensors running.
- **A profile under eighteen is no longer asked for the losing mode's extra 2,000 steps, and the
  profile screen no longer offers it a weight goal.** Under eighteen the goal already moved neither
  the food budget nor the advice, so the choice changed nothing but the walking.
- **The steps notification starts the new day at midnight.** It went on showing yesterday's count as
  today's until the first step or the next start, and a batch of steps delivered just after midnight
  put yesterday's total back up; it is now redrawn at midnight — on a sleeping phone, when it next
  wakes — and a late batch answers with today's total.
- **The step service stops instead of crashing when Android refuses to bring it back into the
  foreground from the background.** Such a refusal used to close the app; steps are counted again
  once the app is opened.
- **Floors are no longer credited for the change in air pressure over a night the phone slept
  through, nor, on a barometer that reports once a second, for a sudden step in pressure.** The
  first reading after a gap passed as a climb, and smoothing counted in readings rather than in
  seconds let a slow barometer turn one jump into two floors.
- **A sensor trace left recording stops by itself after three hours.** It ran on for as long as the
  app stayed in memory, writing 25 rows a second after its wake lock had gone.
- **The sensor page's repetition counter reads at most 64 MB of a picked file.** A large file picked
  by mistake was read whole into the memory the step service shares, and a file it cannot load is
  now reported as a failed read rather than as a save error.
- **Opening an encrypted record and leaving the app before it appeared no longer crashes the app
  when it tries to start step counting.** Android refuses to start the step service from the
  background; counting now starts when the app is next in view.
- **A sensor trace that stops itself at its three-hour limit now records the step counters it ended
  with, as one stopped by hand does.**

### Work

- **A work session's start and end can be set to the minute.** Under the dial on a session's page
  there are now two clock wheels, as on the sleep form: the dial snaps to five minutes, a screen
  reader could move it only a quarter of an hour at a time, and a keyboard could not move it at all.
- **A Resume after a pause of eight hours or longer starts a new session and keeps the paused one as
  it stood.** A Stop forgotten in the evening no longer joins two working days into one row; the tap
  answers "Started" rather than "Resumed", and the "?" beside the timer explains the rule. A
  session's page now says when a session is longer than a day and saving would keep only the times
  it shows.
- **A work session whose stored stretch is longer than two days is no longer counted.** Such a row,
  from a damaged or hand-edited backup, used to hold the Work screen and its widget while every day
  of it was walked. It stays in the lists, where it can be opened and corrected or deleted, and is
  left out of the totals.
- **After the clock is set back, the workplace check carries on ten minutes later.** It used to wait
  for the clock to catch up with its last look.
- **A workplace check that takes no position reads the sessions and the phone's usage log once, not
  twice.** The second reading served only to set the time of the next look, which now uses what the
  first one found.
- **A work session whose recorded times cannot be right opens for editing on its own day.** It used
  to open on today, and saved as it stood the session moved to today.
- **Undo on an automatic stop no longer loses the time worked after it when the app is killed in the
  middle of the Undo.** The next Stop took the old row for a copy of its own and was dropped; it now
  rewrites the old row.
- **Start by location keeps to the clock on the mornings the clocks change.** Its checks used to
  begin an hour late in spring and an hour early in autumn.
- **On the Work page, a location or notification prompt dismissed without an answer is shown again
  on the next tap, instead of sending you to the app's system settings, as onboarding already
  does.**

### Sleep

- **Returning to the app no longer rewrites the week's estimated nights when nothing about them has
  changed.** They used to be marked as edited, re-sent to Health Connect and followed by a
  recalculation of the medicine reminders each time; and two readings started at once can no longer
  write the same night twice.
- **The sleep form says why Save is dark when the length and the clock disagree.** A night told to
  the chat with a length that contradicts its two times, or a length typed over the one the hands
  show, left Save dark with nothing to say why; a line under the field now gives the length the
  clock shows and says to type it or move a hand until the two agree.

### Vitals

- **The pulse read from the phone on the chest no longer comes out at half or a third of a fast,
  steady heartbeat.** With the breath held, 108 could read as 54.
- **The blood pressure card says that its band is a clinic's.** Behind its question mark it now says
  that the band is the guideline's for a measurement at a clinic, and that for readings taken at
  home the same guideline counts an average of 135/85 or higher as raised.
- **A vitals card names the device that wrote the reading it shows.** When another record carried
  the very same time, it could name that record's device instead.
- **The blood-pressure range on the vitals card and the doctor's sheet starts at 91, the first
  systolic it reads as inside the band.** It printed 90, which it colours as low.

### Documentation

- **What the food photo model learned from is said where a reader looks.** The README's Licence
  section now states that `photo_v3.onnx` is trained on public food photo datasets under their own
  terms, and that it keeps its own licence rather than the app's GPL.
- **`docs/MODELS.md` lists the models the app ships.** Each model file is listed with its size and
  its SHA-256.
- **The OCR models are in that table too, and a test holds every model to its row.** The three
  PaddleOCR networks and their two dictionaries were missing, and only the chat reader was held to
  its hash: a photo or OCR network swapped in the assets was another network with nothing to say
  so. `DocsMapTest` now hashes every model in the assets against the table.
- **A map of the documents, and a security model.** `docs/README.md` says which of the repository's
  documents are held by tests and which describe the app as of a date; `docs/SECURITY_MODEL.md`
  says what the app lock, the screenshot block, database encryption and a sealed backup stop, and
  what they do not.
- **README says that the food catalogue carries English and Russian names only.** Phones in the
  other fourteen languages list foods in English; the Languages section said only that the whole
  catalogue is translated.

### Journal

- **A symptom, a lab row, a cup, a drink and a cigarette are named in the language showing.** Each
  was given its words on the day it was logged, so the list read "Тошнота" or "Эспрессо" in English.
  A symptom is now named by its topic, a lab row by what it holds — results, an allergy panel, an
  ultrasound or MRI file — and a black cup by its drink, and a drink's pour and a tobacco product
  are kept as names and figures and said when the row is shown. The doctor's archive lists an
  examination's file under its kind in the sheet's language. A symptom told in the person's own
  words and a cup with milk, which the food editor can rename, keep their words.
- **The day's list names its rows in the language on screen.** A glass of water, a period's start, a
  cigarette, a mood, a work day or an exercise from the catalogue was given its name as text when it
  was logged, so after a change of language, or a backup restored on a phone set to another one, the
  list read "Вода" beside "Water". The name is now looked up when the row is shown, in the calendar,
  on the card of a record that cannot be corrected and in the question before a delete. A meal, a
  medicine, an imported session and a measurement are still shown as they were stored.
- **Save on an entry being corrected stays dark until the entry has loaded.** After the phone was
  turned, a tap in that moment could save the correction as a new entry dated today and leave the
  original as it was.
- **The card of a record that cannot be corrected no longer says it was logged with a single tap.**
  A blood pressure reading, a lab panel or a breathing practice opens on the same card as a one-tap
  entry, and for those the line was untrue; it now says only that there is nothing to correct on
  this record here, and to delete it if it should not be there.
- **Opening a record that was deleted in the meantime returns to where it was opened from.** It used
  to stay on an empty form headed as a correction.
- **A meal over 10,000 kcal or a workout over 24 hours can no longer be saved.** Such figures were
  typing slips that went into the day's energy balance as they were.
- **A save or deletion in the entry editor that the database refuses, on a full disk for instance,
  now says "Not saved" and leaves Save ready to try again.** It used to close the app.
- **A row saved on the stroke of midnight is no longer stamped a day early.** The date and the time
  were read from the clock separately, so a save at midnight could take the old date with the time
  00:00.
- **Decimal fields for a workout's time and load, a night's length, a water amount and a dish's
  grams show the language's decimal separator, like the other food fields, instead of a dot.**

### Home

- **The home screen shows the month-long activity note when a quiet month is on record.** It read
  one day too few and could only ever show the weekly one, while the Health section and the reminder
  said "month" about the same steps.

### Levels

- **The level badge no longer drops to LVL 0 and runs back up each time the Home tab is opened.**
  The year is counted once all of its records have been read, and until then the badge keeps the
  level it last showed.
- **Tapping Share twice on a medal opens one share sheet.** A second tap within three seconds of the
  first is passed over; it used to open a second share sheet over the first while two writes of the
  same medal picture ran side by side.

### Mood

- **The chat's mood pause looks past a mood stamped far ahead.** The chat asked for the newest mood
  with no upper bound, so a mood a fast clock or a restore had stamped a day ahead was the answer:
  the pause was taken as over, and a second mood went in inside the pause of the one three hours
  before it. It now asks the four hours either side of the moment, as the mood page and the widget
  do.
- **The weather card on the mood page is measured like every other pattern.** It used to call a link
  from ten days and a correlation of 0.3, which two unrelated series reach about one month in three,
  and printed the coefficient beside it. It now waits for ninety days that carry both a mood and a
  barometer reading, compares the mood on the days of highest pressure with the days of lowest, and
  says so only when the range around the difference leaves zero out.
- **A mood stamped in the future no longer shuts the scale for a day.** An entry written while the
  phone's clock ran ahead, or restored from a phone whose clock did, kept the next entry out until
  the clock caught up with it and four hours more, and the Save button did nothing without saying
  why. Such an entry now counts only inside the four-hour window around its own moment, and a save
  the app has to refuse puts the countdown up.
- **A mood or a breathing session that cannot be written says so.** A failed write — a full disk, a
  key the Keystore would not give — closed the app from the Save button, or as the breathing page
  was being left; it now puts up the same "Not saved" card the quick-log tiles use.
- **The week's mood is named for what was logged.** A week of nothing but "Neutral" was called
  "Below average" in the warning colour and offered breathing because "recent entries have been
  low"; it is the middle of the scale and now reads "Balanced". "Persistently low" is plain "Low",
  since three entries over two days say nothing about persistence; a tense week is offered breathing
  in words about tension; and the wellbeing figure is rounded rather than cut, so 28.9% no longer
  prints as 28%.
- **A mood entry stamped far in the future no longer hides the real ones before it.** As the newest
  on file it was the only entry the pause looked at, and lying outside the window it let a second
  mood in at once; the mood page, the widget and the save itself now look only at the four hours
  either side of the moment, so the four-hour pause between moods is kept.

### Habits

- **A second tobacco product logged right after the first is kept.** A heated stick tapped within
  three seconds of a cigarette, or a small bowl right after a large one, was taken for a bounced tap
  and dropped without a word while its button stood open. Only the same product twice inside the
  window is a repeat now, as with drinks.
- **A drink logged at a size or strength of its own opens as it is, with its delete button.** It
  used to open under drink choices with Save dark, and a tap on one reset it to the standard pour.
- **The alcohol week line no longer reads "10 standard drinks, reporting line 10" in the plain
  colour at 95–99 g.** Under the line the count stops at 9, and the 10 comes with the warning
  colour.
- **The smoking week line prints its grams to the tenth, so one heated stick reads 0.3 g instead of
  0 g.**

### Breathing

- **Ending a breathing practice early no longer reads as completing it.** The button under the bud
  said "Cancel the practice", saved the session anyway and announced "Practice complete", so a tap
  five seconds in became a finished session of no cycles in the statistics and in Health Connect.
  The button now says "End the practice"; a session with at least one whole cycle is saved as
  "Practice saved", a shorter one is not recorded, and "Practice complete" is kept for a session
  that ran its length.
- **Breathing cues are felt when they cannot be heard.** With the phone on vibrate, on silent or in
  Do Not Disturb the tones played at nothing, and a practice done with the eyes shut had no phases
  and no end although Sound cues was on. Each cue is now a short vibration there, and the end a
  longer one.
- **The breathing animation costs less to draw.** The bud redraws on every frame without rebuilding
  the page around it or allocating some thirty outlines a frame, which matters over a session that
  keeps the screen on for up to fifteen minutes.

### Pain

- **A stroke still under a finger when the pain figure is turned to its other view is dropped.** It
  used to stay on the map as a mark no Undo reached, and the next touch on the other view was saved
  joined to it — the right side painted as the left.
- **The pain map keeps a stroke exactly as the entry stores it.** A dab on the edge of an area could
  mark it on the page and no longer mark it once saved, and that area was then lit whole when the
  entry was opened again.
- **"No pain today" is offered only once the day has been read.** The button used to show for a
  moment on a day that already had an answer, and a tap in that moment filed a second one.
- **The pain page says when there is more painting than one entry keeps.** Past 40 strokes the
  drawing was dropped at the save without a word, the strokes of an entry opened again along with
  it; the line now appears while an Undo still mends it, as it already did in the chat.
- **The urgent-care line on the pain page is read out by a screen reader when it appears.** It comes
  up above the questions while the focus is in the list of areas or on the ruler, and was heard only
  by someone who swiped back up to it.
- **The line on the pain page that says what is still to be answered before Save is read out by a
  screen reader.** It is announced when it appears and when its question changes, as the urgent-care
  line on the same page already was.
- **A follow-up opened again names the year of its first report.** The card that says which pain an
  entry continues now prints the year when the first report was in another year, as the history
  already did.
- **Clear on the pain map takes only the side in view.** The painting on the other side of the body,
  which nobody could see go, used to be cleared with it; Undo still brings back what Clear took.
- **Undo on the pain map takes back all a stroke did.** A stroke over an area struck off the list
  marks it again; undoing that stroke now strikes the area off again instead of leaving it marked.
- **A pain logged as begun "Now" and already gone ends at the report.** Its end used to be counted
  on from the report, a moment in the future; it now began as long before the report as it lasted.
- **An oversized pain painting from a file is read as its areas.** A restored or imported pain row
  with more painting than the app ever stores is shown by its areas alone, instead of being
  hit-tested and drawn on every screen that reads it.
- **A pain entry that cannot be written says so.** Saving or deleting one when the write fails (a
  full disk, a key the Keystore will not give) now shows "Not saved" or "Not deleted" and leaves the
  page open to try again, where it used to close the app.

### Interface

- **A list is parted by the comma of the language showing, wherever it is read.** The chat and the
  doctor's report wrote "、" in Japanese and Chinese and "،" in Arabic, but some thirty other lists —
  the pain areas and words, a medicine's times, the ticks of an act, a calendar day and a chart read
  out by a screen reader, a reminder of the day's workouts — joined their parts with ", " in every
  language. One string, `list_comma`, now parts them all; what is written to a file or to the
  database keeps its plain comma. The ticks of a sexual act are named from the entry in the language
  showing, where the day list printed the words of the day they were logged.
- **The figures that kept the point take the decimal mark of the language too.** Water in fluid
  ounces on its card, its widget and the advice, the loads in a template's exercise lines, a lab
  result with its range, its chart and the figures the advice quotes from it, and a drink's
  strength in the day list read "16.9" on a Russian or German phone beside a weight of "72,4". A lab
  figure keeps every digit it was printed with: a TSH of 0.035 reads "0,035".
- **A date is written in the order its language writes one, on every screen.** Twenty-one places
  still put the day first for everyone — the home screen's cycle card, the work history, vitals, the
  lab pages, a medicine's course, the import cards, a medal's picture — so Japanese read "17 9月" and
  American English "17 Sep", and the same day was written two ways on two pages. They now ask the
  platform for the order, as the calendar and the cycle page already did.
- **Every time is on the 24-hour clock, whatever the phone is set to.** The wheels that take a time
  turn through 0 to 23 and sixty-odd places print "20:00", but the pain history, a walk's photos, a
  route's start and a Bluetooth reading followed the phone and printed "8:00 PM" beside them. The
  rule is written down at `clockText` and in README, and a test keeps a single screen from choosing
  for itself.
- **A figure on screen takes the decimal mark of the language it is shown in.** A weight of "72.4
  kg" stood over a BMI of "23,5" on a Russian or German phone, because some figures were printed by
  a template and some by a formatter fixed to a point. Some fifty figures on the home cards, the day
  list, the profile, the habit, mood and food pages now agree with the templates; what is stored,
  exported or printed on the doctor's sheet keeps its point.
- **The hours under a chart are in the same digits as the times beside them.** In Arabic the hourly
  step chart, the busiest hour and the longest still stretch wrote the hour in the language's digits
  and the rest of the same line in Latin ones: "٠٨:00–08:37".
- **A custom theme with a dark page under light cards keeps its text visible.** No colour reaches
  full contrast on both, and the text used to be drawn pure black — invisible on the page — whatever
  colour was picked for it. It is now drawn in the tone of the picked colour that reads best on
  both, about 4:1 on each.
- **In Arabic the height ruler reads from the right like the rest of its page.** It drew its labels
  and arrow on the right whatever the language; its labels now stand to the left of the ticks and
  its arrow comes in from the left edge.
- **TalkBack now announces the calendar's days, entries and workouts and the profile's menu rows as
  buttons.** Nothing used to say that a tap on them does something.
- **The Done button under the allergen list grows with a large font size instead of cutting its
  label.**
- **The theme editor opens on the palette the app is currently drawn in, so an edit made at night
  changes the dark theme you are looking at.** It used to open on the light palette, and the edit
  changed only the preview.
- **Warning, bleeding, success and link colours stay readable on a custom theme whose background is
  of middling brightness.** On a page of #808080 the warning colour stood at 2.18:1; they are now
  brought to 4.5:1 against the theme's grounds, and the built-in palettes keep their tones.
- **Vital readings and their normal ranges on the cards, the alcohol and temperature lines of a
  symptom entry, and the weight widget now use the decimal mark of the phone's language, as the rest
  of the app does.**
- **TalkBack now announces the meal flow's doors and rows, pain entries, a template's exercise rows,
  progress pictures, a chart's cells and the data notes link as buttons.** Nothing used to say that
  a tap on them does something.

### Build

- **Dependabot raises the dependencies, and CI merges its pull requests once they pass.** The
  Gradle wrapper, plugins and libraries and the workflows' actions monthly, each a few days after
  its release. On a Gradle branch, CI writes the checksums of
  the new versions into `gradle/verification-metadata.xml` and takes that file back only if it adds
  to the old one as Gradle reads it; `automerge.yml` merges only Dependabot's pull request, at the
  commit the workflow for its kind of manifest passed on. A newer release no longer fails an
  untouched build: the three Lint checks that asked the network are off, and the
  `dependencyUpdates` report and its plugin are gone. README no longer repeats the versions it once
  had to be tested against.
- **The release key is named only in the job that builds a version tag.** GitHub hands a runner
  every secret its job names, in a skipped step too, and code running there can read it from
  memory; a branch's build runs every plugin and test of the pushed commit, and Dependabot's run
  versions nobody has read.
- **No APK kept for a branch's build.** They were unsigned, which no phone installs, and at 350 MB
  for each push they filled the repository's artifact storage until every upload, GitHub's
  dependency submission's too, was refused. A version tag still hands its signed APKs to
  the release.
- **Gradle 9.9.2.** Android Lint fails the build on the newer release, published on 19 November,
  which also closes four advisories: SFTP repositories trusted any host key, a transitive snapshot
  dependency skipped dependency verification, TestKit used a predictable temporary directory, and
  the daemon registry was readable by other local users for a moment when it was created. The
  wrapper jar is unchanged: Gradle publishes the same SHA-256 for 9.9.2's.
- **AndroidX Lifecycle 2.11.0, with its version written down.** `lifecycle-runtime-compose`, which
  the screens call for `collectAsStateWithLifecycle`, was declared without a version, which the
  Compose BOM does not give, and took the 2.10.0 another library asked for; Lint's version checks
  never saw it, and the day no library asked, the build would have found none. It now names 2.11.0,
  the newest stable release, which the rest of Lifecycle follows. `DependencyDeclarationsTest`
  holds every library to a version or a BOM.
- **The Gradle wrapper's jar and Windows script are 9.9.1's.** The version bumps had changed only
  `gradle-wrapper.properties`, and the jar that downloads Gradle was still the one of 9.7; it now
  matches the SHA-256 Gradle publishes for 9.9.1's wrapper.
- **The corpus harnesses are left out of a test run without their corpus.** `LabSheetDumpHarness`,
  `LabSheetCorpusHarness` and `LabelCorpusHarness` are tools run by hand against files outside the
  repository; with no `LAB_CORPUS_DIR` or `LABEL_CORPUS_DIR` they stood as three of the five
  skipped tests, where a test that skips for a reason of its own goes unseen. The changelog's
  order is read version number by version number, so a 2.0 sorts above 1.78, and the strength
  screen shots start each from an empty database.
- **detekt allows a function four returns.** At its default of two the rule was suppressed 75
  times, on parsers of a Bluetooth packet, a lab sheet's cell or a typed dose that return from
  each check they fail, and 14 of those suppressions sat on functions it no longer flagged. 50 are
  gone; the 25 functions with five returns or more keep theirs.
- **CI installs the build tools the build reads, and Dependabot proposes new releases of its
  actions.** The workflow installed build tools 37.0.0 while the build, naming none, read the
  Android Gradle plugin's 36.0.0; `buildToolsVersion` now names 37.0.0 and `CiWorkflowTest` holds
  the two together. Each action is pinned to a commit, which stays where it is when the action
  fixes something, so Dependabot now offers the new ones once a month in one pull request; Gradle's
  dependencies stay with Lint and the `dependencyUpdates` report, since Dependabot would raise a
  version without its checksum and the build would refuse it.
- **ktlintCheck is up to date on a tree it has passed, and a debug build waits on no report.**
  ktlint declared nothing it read or wrote, so every `check` read every source again; it now names
  the sources, the three build scripts and `.editorconfig`, and leaves a checkstyle report, as
  detekt does, and it is pointed at those three scripts rather than a `**/*.kts` that walked the
  whole checkout. The `dependencyUpdates` report runs with `check` and a release `assemble*`, no
  longer with the debug build Android Studio's Run assembles, and leaves out the Kotlin plugin's
  ABI-validation tools, which it pins to 2.4.0 and listed as an upgrade nobody here can take.
- **The release's keep check reads R8's mapping line by line and stops a build it cannot follow.**
  The check that refuses a release whose ONNX Runtime types, widgets or widget callbacks R8 renamed
  held the whole mapping, some 130 MB, in the Gradle daemon's heap for each release variant; it now
  reads it line by line. It runs after R8's task, which it finds by AGP's name for it, and a name
  AGP changed would have left it running after nothing; the build now stops and says so.
- **The unit tests run in Auckland's time zone and in Turkish.** The CI runner is UTC in the C
  locale, where an offset of nought and an "i" that upper-cases to "I" let a test pass on an
  assumption a phone does not share; the suite passed whole when the Test tasks were
  given `Pacific/Auckland` and `tr`, and `TestEnvironmentTest` keeps them so. A test class that
  freezes the medicine chat's clock must set it going again (`TestSeamsTest`), and no data class
  holds an array, whose equality is its identity (`DataClassArraysTest`).
- **CI builds a commit once, names its image and stops a run that hangs.** A pull request from a
  branch of this repository was built twice, by the push and as the pull request; it is now built
  by the push, and a fork's pull request as before. Both jobs run on `ubuntu-24.04` rather than an
  image GitHub moves, the build stops at an hour rather than six, and the checkout keeps no token
  in `.git/config` for the build scripts and tests after it. `AssetsBudgetTest` holds the assets
  under 185 MB, against Play's 200 MB for the compressed download of the base module.
- **The patterns that read messages, sheets and files say [0-9] and an explicit space.** Android
  runs a pattern with ICU and the unit tests with java.util.regex, and the two read `\d` and `\s`
  apart: to ICU a digit of any script and every space Unicode names, a no-break one among them; to
  the JVM ASCII's ten and six. A clock, a dose or a pain score was therefore read on the phone in
  ways no test saw. Each of the hundred-odd patterns now writes `[0-9]` and `SPACE`, a dose's time
  and amount and a pain score fold the digits of other scripts to ASCII before they are read, so
  «في ٨ مساءً» is still eight in the evening, and IcuRegexTest fails on the short forms.
- **Kotlin 2.4.21.** The compiler, the standard library and the Compose compiler plugin, whose
  version sets the other two. JetBrains' compatibility table has 2.4.10 tested with Gradle up to 9.5
  and the Android Gradle plugin up to 9.1, and 2.4.21 with the plugin's 9.3 line this build is on;
  no source needed a change for it.
- **KSP 2.3.14.** Three patch releases on from 2.3.11, the processor Room's compiler runs in. The
  schemas Room exports under `app/schemas` came out byte for byte as they were.
- **`check` runs the unit tests once, not once for each flavour.** The two flavours share every
  source file and differ by one line of manifest, so the second run repeated the first and took four
  of the gate's ten minutes. The tests now belong to sideload, which is also the default variant
  and the one Android Lint reads; `testPlayDebugUnitTest` is gone, and a test fails on the day the
  flavours differ in anything but that manifest. A unit-test run that passes thirty minutes is
  stopped.
- **The promise of no network permission is checked on the manifests that ship.** The test read the
  merged manifest of the debug build it ran in, and the two release manifests were read by nothing.
  It is now handed all four, and so is the test of what the app exports to other apps.
- **detekt runs from its command-line jar.** The Gradle plugin of detekt 1.23.8, the newest stable
  one, calls a method Gradle 10 removes, and the build would have stopped on the day of that
  upgrade. `./gradlew detekt` is the same task with the same rules and the same `detekt.yml`.
- **Every `!!` in the app's code is counted by a test.** `detekt.yml` carries a rule against them
  that needs resolved types and so never ran; the comment there now says so, and a new `!!` fails
  `NotNullAssertionTest` until it is handled or counted.
- **README's toolchain table is held to the build files.** It still named AGP 9.3.1 three days
  after the build had moved to 9.3.3.
- **A sideload APK no longer carries a list of its libraries that only Google can read.** The
  Android Gradle plugin writes one into the signing block of every signed APK by default, encrypted
  for Google Play: 6,932 bytes in a build signed with a test key, and none with the setting off.
  The bundle made for the Play Console keeps its copy.
- **CI holds the signing key for a version tag only.** Every push to a branch and every pull
  request used to restore the release key beside the build scripts, the Gradle plugins and the
  tests it then ran, and to keep four signed release APKs for the ninety days GitHub keeps an
  artifact by default. A branch now builds unsigned.
- **A release is not failed by a library published after its commit.** Android Lint fails `check`
  when a dependency, Gradle or the Android Gradle plugin has a newer release, which keeps a branch
  current. A build made with `-PrequireReleaseSigning`, as CI makes a version tag, reports the same
  as notes.
- **A release page gets the two sideload APKs and R8's mapping for them.** It used to get the
  `play` APKs as well, which differ by one manifest line and are not how that flavour is
  distributed, and no mapping, without which a stack trace from a release cannot be read. CI keeps
  a run's APKs for a week.
- **The ONNX Runtime in the app is still 1.30.0, and tests now hold what that rests on.** Release
  1.30.1 repaired two faults in memory, and the AAR cannot be rebuilt without the machine its
  script was written for. Neither fault can be reached with the five models shipped, which have no
  tensor narrower than a byte and no `Loop` or `Scan`; a test fails on a model that has. Another
  fails when the AAR, the desktop runtime of the JVM tests, `build.sh` and its README stop naming
  one release, and the README says what the rebuild to 1.32.0 takes.
- **Gradle checks what it downloads.** `gradle/verification-metadata.xml` holds a SHA-256 for
  every library, plugin and tool the build fetches, and a build stops on a file that differs or is
  not listed; until now a download was taken on the strength of HTTPS alone. A version bump now
  includes that file, and README gives the command that writes it.
- **The three oldest kinds of journal row have names.** `KIND_EAT`, `KIND_ACTIVITY` and
  `KIND_MEASUREMENT` stand beside `JournalEntry` where 138 comparisons and assignments in 44 files
  spelled the text out, and a test fails on a new one that does.
- **Every notification id is in `NotificationIds.kt`.** The numbers were literals in six files, kept
  apart by two comments that listed the others and had both missed one; a test now reads the file
  and fails on a number given out twice.
- **`ops.config` is checked against the models the app ships.** A test fails when a bundled ONNX
  model uses an operator `ops.config` does not list at the model's own opset, or when the README of
  `tools/onnxruntime-android` does not name the model; the README now names all five.
  `tools/onnxruntime-android/make_ops_config.py` writes the header of `ops.config` from the models
  it is given, and the README says why the committed `ops.config` keeps the header of 2026-10-06.
- **`tools/medicine-shots.sh` works with schema 46 and the per-architecture APK again.** It stops
  when the database refuses a seed, where it used to go on and photograph onboarding, and a test now
  holds every seed in `tools/*.sh` to the schema.
- **The ONNX Runtime build script checks what it downloads.** The JDK archive, cmake, ninja and the
  ONNX Runtime commit are held to recorded values, its README records the SHA-256 of the committed
  AAR, and the map builders hold every file they download to `tools/world-inputs.sha256`.
- **`check` runs the database migrations.** A database of every exported schema version, 25 to
  47, is opened through the app's own builder on the SQLite Robolectric carries, so a migration
  that differs from the entity it serves fails the build and not the first phone to update; a
  version-25 record's rows are read back at the end. Until now the migrations were read as text by
  one test and run only by a device test nobody was asked to run.
- **Twenty-five tests that needed a phone run in `check`.** A chat's act in one Room transaction,
  a duplicate tap, and a backup exported and imported again with a course's and a walk's
  photographs needed Room and files, not a device; they moved from `app/src/androidTest` to the
  unit tests, on Robolectric.
- **`check` compiles the device tests.** Lint, detekt and ktlint read `app/src/androidTest` and
  none resolves a name, so a rename in the app could leave those tests unable to build with every
  gate green. Running them still takes a phone: `./gradlew connectedSideloadDebugAndroidTest`,
  which the release checklist now asks for.
- **The database encryption has a test that runs it.** `DatabaseEncryptionDeviceTest` encrypts and
  decrypts a database with rows in it on a device and reads back every table, row count, the
  version and the id sequences, makes a key and has the Keystore unwrap it, and hands the
  conversion and Room a file under another key. Two tests said this was "exercised on the
  emulator" when no such test existed. It needs SQLCipher's library and a Keystore, so it has been
  compiled and not yet run.
- **Onboarding's Next and three hand-drawn controls are tested as composed screens.** No date of
  birth, no Next; no sex chosen, no Next; a ruler, the sleep dial and a wheel hand a screen reader
  their value and actions that set it; a reply folding away in the chat takes no touch. They run
  in `check`, where the only tests that composed a screen took pictures and asserted nothing.
- **What "I took it" and "Later" on a dose's notification do is tested.** A device test of the
  ringing reminder had thrown on every run since those two buttons were added in September,
  unnoticed because nothing ran it; it is repaired, and the two buttons are held in the unit tests.
- **Renaming a stored constant fails a test.** Seventy-nine enums are written into rows, backups
  and preferences by name and read back with a fall to a default, so a renamed constant read
  everything already written as nothing, silently. `app/src/test/resources/stored-enum-names.txt`
  lists them and says what a rename takes.
- **Four tests guard what they were written to guard.** The test that the chat's rules lose no
  step to the model passed over a source file that had been renamed, without a word, until under
  a quarter of its messages were left; the test that a widget's tap does not wait for the app
  lock read one of the five files widgets are in; a test of today's date could fail at midnight;
  and the tests of real timers left the machine 200 ms to stall in before they failed for nothing.
- **`tools/build-top-foods.py` now writes the eighteen columns the catalogue parser requires.** It
  wrote seventeen, so a fresh seed produced a catalogue the food hub fails to open.
- **AndroidX Lifecycle 2.11.1.** Android Lint fails the build on the newer release, published on
  19 November.
- **The build's own tools no longer run on Guava and Bouncy Castle releases with published
  advisories.**
  - AGP and its Lint brought Guava 33.4.0-jre and Bouncy Castle 1.79; the test platform brought
    Guava 32.0.1-jre and Room's KSP processor 33.2.1-jre.
  - GitHub flags those Guava releases for unbounded allocation on deserialisation, and Bouncy
    Castle 1.79 for a timing channel and an LDAP injection.
  - None of them is in the APK, but they run on the build machine and on CI.
  - `settings.gradle.kts` now raises every classpath, the build script's included, to the first
    fixed releases: Guava 33.7.2 and Bouncy Castle 1.86. The app's own Guava 33.8.0-android still
    wins wherever the two meet.
- **Compose BOM 2026.11.01, compiled against Android 17's first minor release.** Android Lint fails
  the build on the newer release, published on 19 November. It brings Compose 1.13 and Material3
  1.5, and Compose 1.13 compiles against no platform older than 37.1, so compileSdk is 37.1, and CI
  installs that platform and the store art's gesture injectors compile against it; targetSdk stays
  37, so the newer platform changes nothing on a phone. The sliders are handed a state rather than
  a value, kept the way the old overload kept it inside, so to a finger and a screen reader each
  still shows what its screen holds and files the same choice at the same moment; the dropdown
  menus come from the new top-level function; and the two bottom sheets name the positions they
  may rest at rather than the ones they skip. Material3 1.5's slider itself behaves differently:
  its bar thumb narrows as soon as a finger touches it or a keyboard focuses it, not only in a
  drag. On a keyboard, Up and Down move focus off the portion and theme colour sliders instead of
  moving them, and Page Up raises them and Page Down lowers them, the other way round from before;
  the mood dial keeps its own keys; and the cycle questions' slider, which took no keys, now steps
  a rung per Left, Right, Home, End or Page key and files it as a tap does.
- **The fields figures and names are typed into run on Compose's state-based text engine.** Compose
  1.13 deprecates the value-based fields they were: a set's and a drink's figure boxes, the number
  fields of the forms, the onboarding, the drink widget and the chat's cards, an exercise timer's
  hours, minutes and seconds, a food's name and detail, the portion weight, the nutrient figures and
  lines, a dish's grams per product, the food search, a dose's nutrient lines and the passwords a
  backup or a locked lab PDF asks for. Each screen still holds the text itself and still hears every
  keystroke the moment it is typed, so a refused character still goes, a comma still turns into the
  stored dot drawn as the locale's separator, a button or a loaded record still replaces the text,
  and the cursor keeps its place as before. What can be noticed: a refused character may show for a
  frame before it goes; undo on a hardware keyboard stops at the last point the screen rewrote the
  text; the long-press menu and the selection handles are the new engine's; and the passwords, still
  never shown a character at a time, can no longer be copied or cut while they are shown, a screen
  reader still treats them as password fields then, and showing or hiding one while typing restarts
  the keyboard, which can drop back to its letters or reset its shift.

### Privacy policy

- **The policy names what a kept lab shot carries and what the app writes to Android's log.** A
  shot no larger than a printed page is kept as the camera app wrote it, and §3 now says that can
  include the phone's make and model, the moment and the place, and that it goes so into a backup
  and a report's archive. §9 says a failure is written to Android's system log, which other apps
  cannot read and which can name a file — a progress photograph's name is the moment it was taken —
  but holds no entry, measurement or medicine.
- **The privacy policy describes 1.76, not 1.66.** Ten releases had passed it since 12 October,
  and it was also wrong where 1.66 had already moved on. It now
  names the workout templates, their photographs — in every backup, the daily copy and a transfer —
  the shared template file the app never deletes from its cache, the plans and the "Workout today"
  reminder that names them; the compass that turns the map on the track and walk pages; the sets
  Freestyle and a template's workout count with the step counter, the barometer, the accelerometer
  or the gyroscope, and the wake lock that keeps them counting; the twelve health exports the import
  reads through a copy in the cache; the allergy panel, the examinations kept as a file, and 36 lab
  markers where it said 23; the lab sheets' photographs that ride in backups since schema 11, kept
  as the camera wrote them; the chat's cards, the note a meal saved from one carries, and the
  symptom row a safety answer gets. The doctor's report is a PDF or a ZIP of the attached files,
  never a JSON; its person and allergy sections — the newest allergy panel's positives with their
  IgE among them — are on every report; and its face calls diagnoses, allergies and vaccinations
  the patient's own statements, where the policy said it carried none. `ACCESS_NETWORK_STATE` left
  the manifest and the table no longer lists it, and `WAKE_LOCK` is the app's own now. No widget
  shows calories or logs a toilet visit, while the water target gives the weight away and says so.
  A picked attachment's read grant is taken at the pick and given up only by its ×, which a saved
  lab result has none of; a deletion marker names an imported record's source, time and food or
  exercise; and a backup this installation wrote no longer brings back an entry deleted since.
  `docs/` and the app's copy are one file again.
- **The policy says when each of these goes.** §2, §3, §8.1 and §11 said a shared template's file,
  an interrupted import's copy and a lab photograph were never removed and that only the × gave a
  file's permission up; they say what the app does now, and the version line says 1.78.
- **The privacy policy no longer calls the two builds identical.** §15 said they were the same app,
  with the same code, permissions and features; it now says they are the same code and that the
  directly installed APK alone declares location all the time and offers Start by location, as §4
  already did.
- **The privacy policy describes the two Screen time reminders.** §10 gives them their own channel,
  Screen time breaks — four channels where it counted three — and says that one names the app a
  sitting was mostly in and its minutes, that both are off by default, read nothing from the journal
  and show on the lock screen as any notification does.
- **The privacy policy lists one more way a file leaves the app.** §8.1, and a sentence in §3, now
  name an attached file or a laboratory camera photograph opened in another app by a tap, one at a
  time.
- **The privacy policy's effective date is 19 November 2026.** The published copy and the one inside
  the app both carry it.
- **The privacy policy names the channel of the workplace check's notification.** §10 said that
  Android may show a notice while the app checks whether you are at work, but not that it sits on a
  channel of its own, *Workplace checks*, at the lowest importance, nor that it names neither the
  place nor the time.
- **The privacy policy says what database encryption protects against.** §12 named a rooted phone,
  which was more than the mechanism gives: the database key is wrapped by a key in the phone's
  keystore that is not bound to the screen lock, so it guards a copy of the storage read away from
  the phone, not software running with root on the phone itself.
- **The privacy policy says that the saved workplace is not covered by the database-encryption
  switch.** It is kept in the app's settings, outside the database, and the policy had said so only
  in general terms.
- **The privacy policy now says what the app keeps to recognise a Bluetooth device.** It keeps a
  fingerprint of the device's address, not the address; for a smart ring that fingerprint is the
  same on every phone and can be worked back to the ring's address by anyone holding a copy of your
  data, while for other devices it is mixed with a secret of the installation that no backup
  carries.
- **The privacy policy says that the app lock lets the app open while the phone has no screen lock
  of its own.** Without one the lock could never be passed, so the app opens without asking until a
  screen lock is set again.
- **The app now lets other apps reach one folder of its cache — the camera's shots and the files you
  share — instead of the whole cache.** The backup being written and a history being imported now
  lie where no link the app hands out can reach them.
