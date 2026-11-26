# Country profiles

Written for: the engineer adding the next country.

A country profile decides **presentation**: the units a figure is printed in and how a date is
written.

It decides nothing about compliance. Choosing "Germany" does not make this app GDPR-, EHDS- or
gematik-compliant; choosing "United States" says nothing about HIPAA. The picker carries that
sentence on screen (`report_country_disclaimer`) and the app must never say otherwise anywhere.

The domain model stays international. A weight is stored in kilograms whatever a profile prints.

## What ships

| Profile | Units (weight / height / temp / glucose) | Date |
| --- | --- | --- |
| **International** (fallback) | as the app is set: metric, or the imperial set below | `d MMM yyyy` |
| **United States** | lb / ft-in / °F / mg/dL | `MMM d, yyyy` |
| **Germany** | kg / cm / °C / mmol/L | `dd.MM.yyyy` |
| **United Kingdom** | kg / cm / °C / mmol/L | `d MMMM yyyy` |
| **Australia** | kg / cm / °C / mmol/L | `d MMMM yyyy` |

A profile carries one switch, `imperial`, and all four units follow it (`ReportRequest.imperial`):
one switch is what the sheet reads. The international profile's switch is null: it has no answer
of its own and prints the units the app itself is set to. Its date is written in
the order above except in Chinese, Japanese and Korean, which write the year first and get
`y年M月d日` / `y년 M월 d일` (`CountryProfile.datePatternIn`); a national profile keeps its
country's order in every language.

## A decision worth keeping

**Germany's glucose unit is not national.** BfArM's own guidance records that mg/dL is customary in
the former West German states and mmol/L in the former East. The profile keeps the app's stored unit
(mmol/L) rather than pretending there is a national answer.

## Adding a country

1. Confirm, from an official source, the customary units and the customary date format.
2. Add an `object` implementing `CountryProfile` in `CountryProfile.kt`, with the sources in its
   KDoc and the date they were read.
3. Add it to `CountryProfileRegistry.all` and add a `country_*` string in all sixteen locales.
4. Add the profile's expected date to `ClinicalExportTest.everyShippedProfileIsInternallyConsistent`,
   which walks the registry, pins the date each profile prints, and checks that the height and
   weight on each profile's sheet are printed on the scale its `imperial` switch declares.

Do not write `if (country == "DE")` anywhere outside `CountryProfile.kt`.
