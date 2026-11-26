# Daily nutrition feedback

The home notice carousel and the food detail screen show feedback for the current local date.
The ENERGY topic switch hides this feedback together with weekly energy advice.
Nutrition feedback stays inside the app and is not sent as a lock-screen notification.

The food card, diary feedback and remaining budget use the same target: the profile's daily food
budget plus net recorded activity, with walking/workout overlap reconciled by the movement
calculation. The advice refreshes when food, activity, profile or local time changes.
It describes **recorded** food, not a claim that an empty diary proves the person has not eaten.

## Intake thresholds

| Condition | Feedback |
|---|---|
| No food entries, from 12:00 | Check missing entries; make time for a balanced meal if none was eaten |
| Below 25% of target, from 14:00 | Very little recorded intake; wording depends on the goal |
| Below 60% of target, from 18:00 | Low recorded intake, with goal-specific wording |
| Gain mode, below 85%, from 18:00 | Gradually close the remaining gap with a balanced meal or snack |
| Lose mode, above 100% | Target exceeded; review portions and hunger when planning the next meal |
| Gain mode, at least 130% | Substantial excess above a target that already includes a surplus |
| Tracking mode, at least 120% | Neutral notice to check portions and duplicate records |

The percentage and time thresholds are product heuristics, not clinical thresholds or a schedule
a user must follow. Morning intake is not assessed as a completed day. A single day does not
establish a usual eating pattern. Weight-loss and weight-gain coaching is withheld for profiles
under 18; those profiles receive neutral tracking wording, and their daily budget stays at
sedentary maintenance whatever goal the profile holds. The goal applies from the 1st of January
on or after the eighteenth birthday.

## Food composition

At most one composition message accompanies an intake message, in this priority order:

1. Known saturated-fat calories exceed 10% of the daily energy target.
2. Fat supplies more than 40% of recorded food energy, and fat calories already reach at least
   25% of the daily target. This prevents a small fatty snack from describing the entire day.
3. In gain mode, energy reaches at least 75% of the target but protein is below half of the app's
   protein guide. This requires usable macros for every caloric entry, accounting for 75–125%
   of each entry's stated energy, so absent protein data is not interpreted as low protein intake.

Composition feedback requires at least 25% of daily target energy to be recorded. Invalid energy
suppresses daily feedback; invalid or inconsistent macros suppress composition feedback. Missing
saturated-fat data is not treated as zero consumption. Food categories are not guessed from names,
and total sugar is not mislabelled as added/free sugar.

## Wording and sources

The messages encourage regular balanced meals and gradual changes, without directing users to
stop eating, fast, skip meals or compensate for an overage. Fat messages distinguish fat quantity
from fat type and do not label all fats as harmful.

- [NHS: tips to help you lose weight](https://www.nhs.uk/live-well/healthy-weight/managing-your-weight/tips-to-help-you-lose-weight/)
- [NHS: healthy ways to gain weight](https://www.nhs.uk/live-well/healthy-weight/managing-your-weight/healthy-ways-to-gain-weight/)
- [NHS: facts about fat](https://www.nhs.uk/live-well/eat-well/food-types/different-fats-nutrition/)
- [WHO: healthy diet](https://www.who.int/news-room/fact-sheets/detail/healthy-diet)

The saturated-fat guide follows WHO; the other alert cutoffs above are deliberately documented
as app behaviour rather than attributed to the cited organisations.
