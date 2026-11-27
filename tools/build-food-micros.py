#!/usr/bin/env python3
"""Write app/src/main/resources/top_foods_micros.csv: vitamins and minerals per 100 g for the
catalogue rows USDA FoodData Central can vouch for — and, for the test that checks it,
app/src/test/resources/top_foods_micros_fdc.csv.

A file of its own beside top_foods.csv rather than twenty more columns in it. The catalogue is
edited by hand and walked on every keystroke of a search; the vitamins are read by the vitamin
table and the report and by nothing else. A catalogue row that is missing here is a row nobody can
say anything about, which is the honest default.

How a catalogue row finds its USDA food
---------------------------------------
Most of the catalogue's tail was written out of FDC by tools/build-top-foods.py, so its four
numbers per 100 g — energy, protein, fat, carbohydrate — are FDC's own, printed by that script's
gram(). The same four numbers printed the same way find the food again. Where several FDC foods
share them, the next four the catalogue carries (fibre, sugar, sodium, cholesterol) narrow the
field, then the words of the row's name, then FNDDS over SR Legacy, because FNDDS reports every
nutrient for every food and SR Legacy leaves a third of them without vitamins D, E and K.

A fingerprint five or more FDC foods share — pure fats, spirits, drinks with nothing in them — says
almost nothing about which food it is, so there the row's first word has to appear in the FDC
description or the row stays unmatched. Without that rule "Shortening" takes "Vegetable oil, NFS",
with three times a shortening's vitamin E and twice its vitamin K, and red palm oil takes USDA's
"Oil, palm", which reports no vitamin A at all.

Two candidates that are equally plain and differ by a tenth of a day's reference in anything leave
the row unmatched too (see best()): FDC keeps enriched and unenriched, fortified and unfortified
versions of one food under the same four numbers.

The hand-written head of the catalogue matches nothing this way: its figures were averaged by hand.
Its most logged single foods are mapped in top_foods_micros_manual.csv instead, one line each,
quoting the FDC description the person who wrote the line checked it against. This script refuses a
line whose quoted description is not FDC's, so a mistyped id cannot slip through. A line may also
overrule a match the numbers made — white rice matches its enriched American self exactly — and may
name columns to leave empty, where the FDC food carries American fortification the row's food need
not. A mapped row is written as `proxy` — the same food, not the same numbers — unless the chosen
food's numbers happen to be the row's. Dishes are never mapped: a borscht is not any one FDC food.

What is written
---------------
id;fdc_id;match; then one column per nutrient, named as the app's Nutrient enum names it, plus
RETINOL — preformed vitamin A, which the upper level is about.

Beside it, in the unit tests' resources, id;fdc_kcal;fdc_protein;fdc_fat;fdc_carbs: the FDC food's
own four numbers, printed by gram(), so a test can see that an `exact` row still agrees with the
catalogue after somebody edits the catalogue by hand. They live there rather than in the app's file
because the app never reads them, and every install would carry them.

Values keep three significant figures. gram()'s one decimal would print 0.04 µg of B12 as "0" and
0.07 mg of thiamin as "0.1". A value FDC does not report is left EMPTY — never 0 — because SR Legacy
leaves vitamins D, E and K out for about a third of its foods, and an empty cell summed as zero is a
deficiency nobody has.

A row with a fat rail (fat_steps) is one product sold at several fat contents. Its vitamins A, D, E
and K — and retinol — are left empty: they travel with the fat, and whether a skimmed version was
fortified back up is a thing the label knows and USDA's one food does not.

Iodine is not written: neither SR Legacy nor FNDDS reports it for any food.

USDA FoodData Central is a work of the U.S. government and carries no copyright.

Usage:
    curl -o sr.zip https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
    curl -o fndds.zip https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_survey_food_csv_2024-10-31.zip
    unzip -q sr.zip -d sr && unzip -q fndds.zip -d fndds
    tools/build-food-micros.py sr/*/ fndds/*/
"""
import csv
import importlib.util
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parent / "app/src/main/resources"
CATALOG = RESOURCES / "top_foods.csv"
MANUAL = HERE.parent / "app/src/test/resources/top_foods_micros_manual.csv"
OUT = RESOURCES / "top_foods_micros.csv"
OUT_FDC = HERE.parent / "app/src/test/resources/top_foods_micros_fdc.csv"

def _top_foods_script():
    """build-top-foods.py by path: its name is not an importable module name."""
    spec = importlib.util.spec_from_file_location("build_top_foods", HERE / "build-top-foods.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

TOP = _top_foods_script()
gram = TOP.gram

KCAL, PROTEIN, FAT, CARBS = TOP.KCAL, TOP.PROTEIN, TOP.FAT, TOP.CARBS
FINGERPRINT = (KCAL, PROTEIN, FAT, CARBS)
# The catalogue's next four columns, in its own order: fibre, sugar, sodium, cholesterol.
NARROWING = (TOP.FIBER, TOP.SUGAR, TOP.SODIUM, TOP.CHOLESTEROL)

# (column, FDC nutrient id, the unit FDC must report it in, the adult reference it is read against).
# The column is the app's Nutrient enum name where there is one. The unit is checked against
# nutrient.csv, so a renumbering cannot slip a milligram figure into a microgram column. The
# reference is a man's of 19-30 as Micronutrients.kt writes it (NASEM RDA, or AI for vitamin K and
# potassium; retinol and folic acid borrow vitamin A's and folate's); it is used here only to tell a
# difference worth refusing a match over from noise.
MICROS = (
    ("VITAMIN_A", "1106", "UG", 900),   # Vitamin A, RAE
    ("RETINOL", "1105", "UG", 900),     # preformed: what the upper level counts
    ("VITAMIN_C", "1162", "MG", 90),
    ("VITAMIN_D", "1114", "UG", 15),    # D2 + D3
    ("VITAMIN_E", "1109", "MG", 15),    # alpha-tocopherol
    ("VITAMIN_K", "1185", "UG", 120),   # phylloquinone
    ("THIAMIN", "1165", "MG", 1.2),
    ("RIBOFLAVIN", "1166", "MG", 1.3),
    ("NIACIN", "1167", "MG", 16),       # preformed only; FDC has no niacin equivalents
    ("VITAMIN_B6", "1175", "MG", 1.3),
    ("VITAMIN_B12", "1178", "UG", 2.4),
    ("FOLATE", "1190", "UG", 400),      # dietary folate equivalents
    ("FOLIC_ACID", "1186", "UG", 400),  # synthetic, what the folate upper level counts
    ("CALCIUM", "1087", "MG", 1000),
    ("IRON", "1089", "MG", 8),
    ("MAGNESIUM", "1090", "MG", 400),
    ("PHOSPHORUS", "1091", "MG", 700),
    ("POTASSIUM", "1092", "MG", 3400),
    ("ZINC", "1095", "MG", 11),
    ("SELENIUM", "1103", "UG", 55),
)
FAT_SOLUBLE = {"VITAMIN_A", "RETINOL", "VITAMIN_D", "VITAMIN_E", "VITAMIN_K"}

WANTED = set(FINGERPRINT) | set(NARROWING) | {nutrient for _, nutrient, _, _ in MICROS}

# A fingerprint this many FDC foods share needs the row's first word in the description as well.
CROWDED = 5

# Two equally plain candidates that differ by more than this share of the reference per 100 g are
# two different foods as far as the vitamins go, and the row is left without either.
MATERIAL = 0.10

# Words that say how a food was handled rather than which food it is.
STOP = set(
    "with and or in of the a an no not nfs ns from made to without added type all raw cooked "
    "prepared canned frozen plain regular average".split()
)

CATALOG_COLUMNS = 18
ID, NAME_EN, DETAIL_EN, ALIASES, FAT_STEPS = 0, 3, 5, 15, 16
NUMBERS = slice(6, 10)
NARROWING_NUMBERS = slice(10, 14)

def rows(path):
    with open(path, newline="", encoding="utf-8", errors="replace") as handle:
        yield from csv.DictReader(handle)

def legacy_numbers(folder):
    """FNDDS files nutrients under the old SR numbers; nutrient.csv says which id each one is."""
    units, legacy = {}, {}
    for row in rows(folder / "nutrient.csv"):
        units[row["id"]] = row["unit_name"]
        if row["id"] in WANTED and row["nutrient_nbr"]:
            legacy[row["nutrient_nbr"]] = row["id"]
    for column, nutrient, unit, _ in MICROS:
        if units.get(nutrient) != unit:
            sys.exit(f"{folder}: nutrient {nutrient} ({column}) is in {units.get(nutrient)}, expected {unit}")
    return legacy

def load(folder, source):
    """{fdc_id: {"source", "description", "n": {nutrient id: amount per 100 g}}}."""
    legacy = legacy_numbers(folder)
    foods = {
        row["fdc_id"]: {"source": source, "description": row["description"], "n": {}}
        for row in rows(folder / "food.csv")
    }
    for row in rows(folder / "food_nutrient.csv"):
        nutrient = legacy.get(row["nutrient_id"], row["nutrient_id"])
        food = foods.get(row["fdc_id"])
        if food is not None and nutrient in WANTED and row["amount"] != "":
            food["n"][nutrient] = float(row["amount"])
    return foods

def tokens(text):
    return {word for word in re.findall(r"[a-z]+", text.lower()) if len(word) > 2 and word not in STOP}

def row_words(cell):
    """
    The row's English words: name, detail, the aliases written in Latin letters, and the id — which
    for the tail is the FDC description it was cut from, and still says "enriched" where the name
    no longer does.
    """
    aliases = [alias for alias in cell[ALIASES].split(",") if alias.isascii()]
    return tokens(" ".join([cell[NAME_EN], cell[DETAIL_EN], cell[ID].replace("_", " ")] + aliases))

def first_word(cell):
    words = [word for word in re.findall(r"[a-z]+", cell[NAME_EN].lower()) if len(word) > 2 and word not in STOP]
    return words[0] if words else None

def spelled(description):
    """A description's words, and each two neighbours run together: FDC writes "sour dough"."""
    words = re.findall(r"[a-z]+", description.lower())
    return set(words) | {left + right for left, right in zip(words, words[1:])}

def printed(cells):
    """Catalogue cells as gram() would have printed them, so "0.40" and "0.4" are the same number."""
    return tuple(gram(float(cell)) if cell else "" for cell in cells)

def fingerprint(food, nutrients):
    return tuple(gram(food["n"].get(nutrient)) for nutrient in nutrients)

def sig3(value):
    """Three significant figures, in plain decimals: 1270, 0.0412, 27."""
    if value == 0:
        return "0"
    decimals = 2 - math.floor(math.log10(abs(value)))
    rounded = round(value, decimals)
    if decimals <= 0:
        return str(int(rounded))
    return f"{rounded:.{decimals}f}".rstrip("0").rstrip(".")

def differs(one, other):
    """Whether two FDC foods disagree on a nutrient both report, by enough to matter to somebody's day."""
    for _, nutrient, _, reference in MICROS:
        a, b = one["n"].get(nutrient), other["n"].get(nutrient)
        if a is not None and b is not None and abs(a - b) > MATERIAL * reference:
            return True
    return False

def best(cell, candidates, foods):
    """
    Among foods sharing the row's four numbers, the one that is most plainly this row — or None
    where two are equally plain and would write different vitamins. Apple juice is the case that
    decides it: SR Legacy files it with and without added ascorbic acid under the same four
    numbers, and the lower id is the fortified one, 38.5 mg of vitamin C per 100 g against 0.9.
    """
    narrowed = [fdc for fdc in candidates
                if fingerprint(foods[fdc], NARROWING) == printed(cell[NARROWING_NUMBERS])]
    pool = narrowed or candidates
    if len(candidates) >= CROWDED:
        word = first_word(cell)
        pool = [fdc for fdc in pool if word and word in spelled(foods[fdc]["description"])]
        if not pool:
            return None
    words = row_words(cell)

    def score(fdc):
        described = tokens(foods[fdc]["description"])
        overlap = len(words & described) / max(1, len(words | described))
        # FNDDS first on a tie: it reports every nutrient for every food.
        return (-overlap, foods[fdc]["source"] != "FNDDS")

    top = min(score(fdc) for fdc in pool)
    tied = [fdc for fdc in pool if score(fdc) == top]
    # The lower id among equals, only so that two runs over the same files write the same file.
    winner = min(tied, key=int)
    if any(differs(foods[winner], foods[fdc]) for fdc in tied):
        return None
    return winner

def manual_file(foods, catalogue):
    """
    {catalogue id: (FDC id, columns to leave empty)} from the hand-checked file.

    `left_empty` names the columns a person knew the FDC food to carry for an American reason the
    row's food need not share: milk fortified with vitamins A and D, flour enriched with thiamin,
    riboflavin, niacin, folic acid and iron. Unknown is the honest figure for those, not America's.
    """
    columns = {column for column, _, _, _ in MICROS}
    out = {}
    with open(MANUAL, newline="", encoding="utf-8") as handle:
        for line in csv.DictReader(handle, delimiter=";"):
            row_id, fdc, quoted = line["id"], line["fdc_id"], line["fdc_description"]
            left_empty = {column for column in line["left_empty"].split("|") if column}
            if row_id not in catalogue:
                sys.exit(f"{MANUAL.name}: {row_id} is not a catalogue id")
            food = foods.get(fdc)
            if food is None:
                sys.exit(f"{MANUAL.name}: {row_id} names FDC {fdc}, which neither folder carries")
            if food["description"] != quoted:
                sys.exit(f"{MANUAL.name}: {row_id} quotes “{quoted}”, but FDC {fdc} is “{food['description']}”")
            if left_empty - columns:
                sys.exit(f"{MANUAL.name}: {row_id} leaves out {sorted(left_empty - columns)}, which is no column")
            if row_id in out:
                sys.exit(f"{MANUAL.name}: {row_id} is mapped twice")
            out[row_id] = (fdc, left_empty)
    return out

def main(folders):
    if len(folders) != 2:
        sys.exit(__doc__)
    foods = {}
    for folder in map(Path, folders):
        source = "FNDDS" if (folder / "survey_fndds_food.csv").exists() else "SR"
        foods.update(load(folder, source))

    by_print = defaultdict(list)
    for fdc, food in foods.items():
        if all(nutrient in food["n"] for nutrient in FINGERPRINT):
            by_print[fingerprint(food, FINGERPRINT)].append(fdc)

    lines = CATALOG.read_text(encoding="utf-8").splitlines()
    catalogue = {}
    for line in lines[1:]:
        cell = line.split(";")
        if len(cell) != CATALOG_COLUMNS:
            sys.exit(f"{CATALOG.name}: expected {CATALOG_COLUMNS} columns in `{line}`")
        catalogue[cell[ID]] = cell

    # {catalogue id: (FDC id, "exact" or "proxy", columns to leave empty)}
    matched = {}
    for row_id, cell in catalogue.items():
        candidates = sorted(by_print.get(printed(cell[NUMBERS]), []), key=int)
        fdc = best(cell, candidates, foods) if candidates else None
        if fdc is not None:
            matched[row_id] = (fdc, "exact", set())

    # A person's reading wins over the numbers'. "Tea" shares its four numbers with instant iced tea,
    # which is not what a cup of it is, and white rice with its enriched American self. The row is
    # still `exact` where the chosen food's own numbers are the row's.
    for row_id, (fdc, left_empty) in manual_file(foods, catalogue).items():
        if matched.get(row_id, (None,))[0] == fdc and not left_empty:
            sys.exit(f"{MANUAL.name}: {row_id} matches FDC {fdc} by its numbers already; drop the line")
        same = fingerprint(foods[fdc], FINGERPRINT) == printed(catalogue[row_id][NUMBERS])
        matched[row_id] = (fdc, "exact" if same else "proxy", left_empty)

    header = ["id", "fdc_id", "match"] + [column for column, _, _, _ in MICROS]
    out = [";".join(header)]
    out_fdc = [";".join(["id", "fdc_kcal", "fdc_protein", "fdc_fat", "fdc_carbs"])]
    written = empty = 0
    for row_id, cell in catalogue.items():
        if row_id not in matched:
            continue
        fdc, how, left_empty = matched[row_id]
        amounts = foods[fdc]["n"]
        if cell[FAT_STEPS]:
            left_empty = left_empty | FAT_SOLUBLE
        values = [
            "" if nutrient not in amounts or column in left_empty else sig3(amounts[nutrient])
            for column, nutrient, _, _ in MICROS
        ]
        if not any(values):
            empty += 1
            continue
        out.append(";".join([row_id, fdc, how, *values]))
        out_fdc.append(";".join([row_id, *fingerprint(foods[fdc], FINGERPRINT)]))
        written += 1
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    OUT_FDC.write_text("\n".join(out_fdc) + "\n", encoding="utf-8")
    exact = sum(1 for _, how, _ in matched.values() if how == "exact")
    print(f"{len(catalogue)} catalogue rows: {exact} exact, {len(matched) - exact} proxy; "
          f"{written} written, {empty} matched with no vitamin or mineral reported")

if __name__ == "__main__":
    main(sys.argv[1:])
