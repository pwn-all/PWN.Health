#!/usr/bin/env python3
"""Rebuild app/src/main/resources/top_foods.csv from USDA FoodData Central.

It seeds a catalogue from scratch: it refuses to run against a catalogue larger than
CURATED_CEILING, because the shipped file is curated by hand and topping it up to LIMIT would undo
that curation. build-food-micros.py reuses its readers.

The hand-written head of the catalogue — the rows carrying Russian names, aliases and portions
somebody chose on purpose — is kept exactly as it is and merged on top. Everything after it is the
long tail: SR Legacy (branded fast food included: McDonald's, KFC, Burger King, Wendy's, Domino's,
Pizza Hut, Taco Bell, Subway) plus the FNDDS survey foods, which is where the world's dishes live.

Every row it writes has the eighteen columns the app's parser requires (CATALOG_COLUMNS in
TopFoodCatalog.kt), the last three empty: aliases and the fat rail are hand-written things,
and the saturated fat was matched from USDA later, on the four numbers a row already holds,
by a pass this script does not repeat.

USDA FoodData Central is a work of the U.S. government and carries no copyright.

Usage:
    curl -o sr.zip https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
    curl -o fndds.zip https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_survey_food_csv_2024-10-31.zip
    unzip -q sr.zip -d sr && unzip -q fndds.zip -d fndds
    tools/build-top-foods.py sr/*/ fndds/*/
"""
import csv
import re
import sys
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/resources/top_foods.csv"
LIMIT = 10_000

# Above this many existing rows the catalogue is hand-made and this script must not top it up.
#
# Left to itself the script writes all ten thousand rows, most of them the same foods over and over:
# "Beef, ground", "Beef, Ground, raw" and "Beef, ground, cooked" are one mince, and USDA files
# hundreds of trims of one cut. The catalogue is cut back by hand — names rewritten out of database
# syntax, duplicates merged, and frying fat moved out of product rows into an oil ingredient the user
# weighs. None of it is reproducible by rule: the commonest reason a row goes is "duplicate of
# another row", which needs judgement, not a regex. Refilling the free space to LIMIT would pour
# every one of those rows back in beside the survivors.
CURATED_CEILING = 2_000

# The nutrients the catalogue carries, by FoodData Central nutrient id.
KCAL, PROTEIN, FAT, CARBS, FIBER, SUGAR, SODIUM, CHOLESTEROL = (
    "1008", "1003", "1004", "1005", "1079", "2000", "1093", "1253")
WANTED = {KCAL, PROTEIN, FAT, CARBS, FIBER, SUGAR, SODIUM, CHOLESTEROL}

# FNDDS still files its nutrients under the old SR numbers; the two sets mean the same things.
LEGACY_NUTRIENT = {
    "208": KCAL, "203": PROTEIN, "204": FAT, "205": CARBS,
    "291": FIBER, "269": SUGAR, "307": SODIUM, "601": CHOLESTEROL,
}

SR_CATEGORY = {
    "1": "DAIRY", "2": "OTHER", "3": "OTHER", "4": "OTHER", "5": "MEAT", "6": "DISH",
    "7": "MEAT", "8": "GRAIN", "9": "FRUIT", "10": "MEAT", "11": "VEGETABLE", "12": "NUTS",
    "13": "MEAT", "14": "DRINK", "15": "FISH", "16": "VEGETABLE", "17": "MEAT", "18": "GRAIN",
    "19": "SWEET", "20": "GRAIN", "21": "FAST_FOOD", "22": "DISH", "23": "SNACK", "24": "OTHER",
    "25": "FAST_FOOD",
}

# FNDDS files its foods under WWEIA categories, whose names say plainly enough what they are.
WWEIA_WORDS = [
    ("FAST_FOOD", ("burger", "pizza", "sandwich", "french fries", "taco", "burrito", "nachos")),
    ("DRINK", ("milk,", "juice", "soft drink", "tea", "coffee", "water", "beer", "wine", "liquor",
               "smoothie", "drink")),
    ("SWEET", ("cake", "cookie", "candy", "pastr", "doughnut", "pie", "ice cream", "pudding",
               "sugar", "syrup", "jam", "sweet")),
    ("SNACK", ("chips", "crackers", "popcorn", "pretzel", "snack", "bar")),
    ("MEAT", ("beef", "pork", "chicken", "turkey", "lamb", "sausage", "bacon", "ham", "meat",
              "frankfurter", "cold cut", "poultry")),
    ("FISH", ("fish", "shrimp", "seafood", "tuna", "salmon")),
    ("DAIRY", ("cheese", "yogurt", "milk", "cream", "egg")),
    ("GRAIN", ("bread", "cereal", "rice", "pasta", "grain", "roll", "bagel", "tortilla",
               "biscuit", "pancake", "oatmeal", "noodle")),
    ("VEGETABLE", ("vegetable", "potato", "salad", "bean", "lettuce", "tomato", "carrot", "corn",
                   "soy", "legume")),
    ("FRUIT", ("fruit", "apple", "banana", "berr", "citrus", "melon", "grape", "peach", "pear")),
    ("NUTS", ("nut", "seed", "peanut")),
    ("DISH", ("soup", "stew", "casserole", "dish", "entree", "rice mixed", "mexican", "asian",
              "pasta mixed")),
]

# What a description leads with when the interesting part comes after it.
BUCKETS = ("fast foods", "restaurant foods", "beverages", "snacks", "sweets", "babyfood",
           "cereals ready-to-eat", "usda commodity", "school lunch")

BRANDS = ("mcdonald", "kfc", "kentucky fried", "burger king", "wendy", "domino", "pizza hut",
          "taco bell", "subway", "starbucks", "dunkin", "popeyes", "chick-fil-a", "arby",
          "little caesar", "papa john", "sonic", "jack in the box", "five guys", "denny",
          "applebee", "olive garden", "t.g.i. friday", "chili")

CATEGORY_WEIGHT = {
    "FAST_FOOD": 0, "DISH": 1, "MEAT": 2, "FISH": 2, "DAIRY": 2, "GRAIN": 2, "VEGETABLE": 3,
    "FRUIT": 3, "SWEET": 3, "SNACK": 3, "DRINK": 3, "NUTS": 4, "OTHER": 6,
}

# How many rows a category may take of the ten thousand. Without this, SR Legacy's cuts of meat
# take a third of the catalogue — "Beef, chuck, arm pot roast, separable lean only" nine hundred
# times over — and crowd out the food people actually log. OTHER is zero on purpose: it holds
# spices, baby food and cooking oils, and a filter chip named "other" helps nobody find lunch.
CATEGORY_QUOTA = {
    "FAST_FOOD": 1600, "DISH": 1500, "MEAT": 1200, "GRAIN": 1100, "VEGETABLE": 900,
    "DAIRY": 800, "SWEET": 700, "FISH": 600, "DRINK": 600, "FRUIT": 500, "SNACK": 400,
    "NUTS": 250, "OTHER": 0,
}


def rows(path):
    with open(path, newline="", encoding="utf-8", errors="replace") as handle:
        yield from csv.DictReader(handle)


def nutrients(folder, keep):
    """{fdc_id: {nutrient_id: amount}} for the ids asked for, streamed — the file is large."""
    out = {}
    for row in rows(folder / "food_nutrient.csv"):
        fdc = row["fdc_id"]
        nutrient = LEGACY_NUTRIENT.get(row["nutrient_id"], row["nutrient_id"])
        if fdc in keep and nutrient in WANTED and row["amount"]:
            out.setdefault(fdc, {})[nutrient] = float(row["amount"])
    return out


def portions(folder, keep):
    """Up to three printed portions per food, smallest first, as (grams, label)."""
    out = {}
    for row in rows(folder / "food_portion.csv"):
        fdc = row["fdc_id"]
        if fdc not in keep:
            continue
        grams = float(row["gram_weight"] or 0)
        if not 1 <= grams <= 2000:
            continue
        label = (row.get("portion_description") or row.get("modifier") or "").strip()
        # The separators the catalogue format spends: a label carrying one would split a row.
        label = re.sub(r"[;|=]", " ", re.sub(r"\s+", " ", label)).strip()[:28]
        out.setdefault(fdc, []).append((grams, label))
    return {fdc: sorted(set(items))[:3] for fdc, items in out.items()}


def wweia_categories(folder):
    """{wweia code: FoodCategory} from the category's own description."""
    out = {}
    for row in rows(folder / "wweia_food_category.csv"):
        text = row["wweia_food_category_description"].lower()
        out[row["wweia_food_category"]] = next(
            (group for group, words in WWEIA_WORDS if any(word in text for word in words)),
            "OTHER",
        )
    return out


def clean(description, parts_kept=3):
    """USDA writes for a database; this writes for a person picking lunch."""
    text = description.replace(";", ",").strip()
    head, _, tail = text.partition(",")
    if head.lower().strip() in BUCKETS and tail.strip():
        text = tail.strip()
    parts = [part.strip() for part in text.split(",") if part.strip()]
    text = ", ".join(parts[:parts_kept])
    words = []
    for word in text.split(" "):
        letters = [c for c in word if c.isalpha()]
        upper = len(letters) > 3 and all(c.isupper() for c in letters)
        words.append(word.capitalize() if upper else word)
    text = " ".join(words)
    return (text[:1].upper() + text[1:])[:70]


def slug(text, taken):
    base = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")[:40] or "food"
    name, n = base, 2
    while name in taken:
        name, n = f"{base}_{n}", n + 1
    taken.add(name)
    return name


def gram(value):
    if value is None:
        return ""
    return f"{value:.1f}".rstrip("0").rstrip(".")


def collect(folder, taken):
    foods = {}
    groups = {}
    if (folder / "wweia_food_category.csv").exists():
        groups = wweia_categories(folder)
    for row in rows(folder / "food.csv"):
        fdc = row["fdc_id"]
        if groups:
            # FNDDS files a food straight under its WWEIA category number.
            group = groups.get(row["food_category_id"], "OTHER")
        else:
            group = SR_CATEGORY.get(row["food_category_id"], "OTHER")
        foods[fdc] = (row["description"], group)

    facts = nutrients(folder, set(foods))
    sizes = portions(folder, set(foods))
    out = []
    for fdc, (description, group) in foods.items():
        fact = facts.get(fdc, {})
        if not all(key in fact for key in (KCAL, PROTEIN, FAT, CARBS)):
            continue
        if fact[KCAL] < 0 or fact[KCAL] > 900:
            continue
        name = clean(description)
        brand = next((brand for brand in BRANDS if brand in description.lower()), None)
        if brand:
            group = "FAST_FOOD"
        served = sizes.get(fdc) or [(100.0, "")]
        out.append({
            "id": slug(name, taken),
            "group": group,
            "description": description,
            "name_en": name,
            "kcal": fact[KCAL],
            "protein": fact[PROTEIN],
            "fat": fact[FAT],
            "carbs": fact[CARBS],
            "fiber": fact.get(FIBER),
            "sugar": fact.get(SUGAR),
            "sodium": fact.get(SODIUM),
            "cholesterol": fact.get(CHOLESTEROL),
            "portions": served,
            "brand": brand,
            "score": CATEGORY_WEIGHT.get(group, 6) * 100 + min(len(name), 99) - (50 if brand else 0),
        })
    return out


def main(folders):
    kept = CATALOG.read_text(encoding="utf-8").splitlines()
    header, curated = kept[0], [line for line in kept[1:] if line.strip()]
    if len(curated) > CURATED_CEILING:
        sys.exit(
            f"{CATALOG.name} already holds {len(curated)} curated rows, so this would undo them.\n"
            "Pull the foods you actually want out of the USDA dump by hand, or raise "
            "CURATED_CEILING once you have a way to keep the cleanup."
        )
    taken = {line.split(";")[0] for line in curated}
    curated_names = {line.split(";")[3].lower() for line in curated}

    tail = []
    for folder in folders:
        tail += collect(Path(folder), taken)
    tail = [food for food in tail if food["name_en"].lower() not in curated_names]
    tail.sort(key=lambda food: (food["score"], food["name_en"]))

    # One row per name, and no category eating the catalogue. USDA files four rows of "Mustard
    # greens, frozen, cooked" that differ only in whether salt was added; a picker showing the same
    # words four times is worse than showing three other vegetables. A collision keeps the fuller
    # description first, and only a name that is still taken after that is dropped.
    # Two passes: the quota first, so no category eats the catalogue, then whatever is left over
    # fills the remaining slots in score order rather than leaving them empty.
    seen, quota, kept = set(curated_names), {}, []
    room = LIMIT - len(curated)
    for capped in (True, False):
        for food in tail:
            if len(kept) >= room:
                break
            if food.get("kept"):
                continue
            cap = CATEGORY_QUOTA.get(food["group"], 250)
            if quota.get(food["group"], 0) >= cap and (capped or cap == 0):
                continue
            name = food["name_en"]
            if name.lower() in seen:
                name = clean(food["description"], parts_kept=5)
            if name.lower() in seen:
                continue
            seen.add(name.lower())
            quota[food["group"]] = quota.get(food["group"], 0) + 1
            food["name_en"], food["kept"] = name, True
            kept.append(food)
    tail = kept

    lines = [header] + curated
    for food in tail:
        portion_field = "|".join(
            f"{gram(grams)}={label}={label}" if label else gram(grams)
            for grams, label in food["portions"]
        )
        lines.append(";".join([
            food["id"], food["group"], "", food["name_en"], "", "",
            gram(food["kcal"]), gram(food["protein"]), gram(food["fat"]), gram(food["carbs"]),
            gram(food["fiber"]), gram(food["sugar"]), gram(food["sodium"]),
            gram(food["cholesterol"]), portion_field, "", "", "",  # aliases, fat_steps, saturated
        ]))
    CATALOG.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(curated)} curated + {len(tail)} from USDA = {len(lines) - 1} rows")


if __name__ == "__main__":
    main(sys.argv[1:])
