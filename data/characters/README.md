# Character tables

Upload the five Excel source tables here:

- `01_Wuthering_Waves.xlsx`
- `02_Genshin_Impact.xlsx`
- `03_Honkai_Star_Rail.xlsx`
- `04_Arknights_Endfield.xlsx`
- `05_Zenless_Zone_Zero.xlsx`

Columns: `ID`, `Имя персонажа`, `Стихия`, `Редкость`.

The repository workflow assigns missing IDs as `1.N` through `5.N`, preserves existing IDs, validates duplicates, and generates `data/characters.json` for the app.