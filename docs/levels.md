# Annual levels

The home header shows the user's level (0–99) and progress towards the next level. Tapping it
opens this year's XP breakdown. Every health record saved in the current year counts; opening
days count from the first opening the app recorded.

| Activity | XP |
|---|---:|
| Open the app, days 1–6 of a consecutive streak | 1 per day |
| Open the app, days 7–29 | 2 per day |
| Open the app, day 30 onwards | 3 per day |
| Every complete 1,000 steps within one local day | 1 |
| Each saved food entry | 1 |
| A day containing at least one recorded workout | 2 |
| A doctor visit marked completed | 2 |
| Each earned annual step medal | 50 |

Opening multiple times on one date counts once. A missed date restarts the opening streak;
bonuses apply from the threshold day without retroactively changing earlier days. A month is
30 consecutive dates. The opening streak survives New Year; **XP and levels reset on January 1**.
The date is the device's local date, and an app kept in the foreground across midnight records
the new date as well. A background service does not count as an opening.

Steps use the same reconciled daily totals as the dashboard (phone, Health Connect and direct
Bluetooth are not added together). Step remainders do not carry between days. Multiple exercises,
sets or sessions on one date still earn only 2 workout XP. Pending doctor appointments earn no XP;
the completed appointment earns 2 XP in total, on its local completion date.

| Transition | XP cost |
|---|---:|
| 0 → 1 | 50 |
| 1 → 2 | 100 |
| 2 → 3 | 200 |
| 3 → 4 | 350 |
| 4 → 5 | 600 |
| 5 → 6 | 1,000 |
| 6 → 7 | 1,300 |
| 7 → 8 | 1,600 |
| 8 → 9 | 2,000 |
| 9 → 10 | 3,000 |
| Each subsequent transition, up to level 99 | 1,000 |

These are costs for individual transitions, not cumulative thresholds. Level 10 needs 10,200
annual XP; level 99 needs 99,200. Level 99 has no further progress target.

XP is derived from the saved records rather than incremented on every screen refresh. Editing
or deleting a record recalculates its contribution, including medals. The import identity
and merge rules prevent repeatedly importing the same records from granting extra XP.

The opening dates are the `app_open_days` table, keyed by ISO date. Backups carry them under
`appOpenDays`; restore merges dates without replacing newer local dates or duplicating them, and a
backup without the field is still read. The level itself is recalculated from data.

The home carousel's neighbouring cards show their value and goal, six hourly bars ending at
that metric's latest activity, and one goal percentage. Their content is sized to the visible
strip. The calories denominator combines the profile's daily resting estimate and active-burn
goal, so the displayed total and goal use the same basis.
