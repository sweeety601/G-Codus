# Character tables

The five Excel files in this folder are the human-editable source of truth:

- `01_Wuthering_Waves.xlsx` — IDs `1.x`
- `02_Genshin_Impact.xlsx` — IDs `2.x`
- `03_Honkai_Star_Rail.xlsx` — IDs `3.x`
- `04_Arknights_Endfield.xlsx` — IDs `4.x`
- `05_Zenless_Zone_Zero.xlsx` — IDs `5.x`

Columns, in this exact order:

1. `ID`
2. `Имя персонажа`
3. `Стихия`
4. `Редкость`

There is no portrait column. A character with ID `2.17` uses exactly `images/2.17.webp`.

IDs are permanent. Existing IDs are never renumbered or reused. For a new row, the repository sync workflow assigns the next free ID for that game's prefix if the ID cell is empty. If an ID is explicitly entered, it is validated and preserved.

The workflow validates duplicates, validates the game prefix, checks that `images/<ID>.webp` exists, and generates `data/generated/characters.json`. The Android app reads that generated data online, so adding a valid row and its portrait does not require an APK update.

`The Storyteller` and `Sunbringer` are permanently excluded from generated tracking data.