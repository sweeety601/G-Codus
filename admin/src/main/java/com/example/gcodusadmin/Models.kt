package com.example.gcodusadmin

data class GameMeta(
    val key: String,
    val idPrefix: String,
    val name: String,
    val seedPath: String,
    val bannerPrefix: String
)

data class AdminCharacter(
    val id: String,
    var name: String,
    var element: String,
    var rarity: Int
)

data class BannerRow(
    var phase: String,
    var startDate: String,
    var endDate: String,
    var characters: MutableList<String>,
    var fourStars: MutableList<String>
)

object GameCatalog {
    val games = listOf(
        GameMeta("wuwa","1","Wuthering Waves","library/seed/01_Wuthering_Waves.xlsx","01_Wuthering_Waves"),
        GameMeta("genshin","2","Genshin Impact","library/seed/02_Genshin_Impact.xlsx","02_Genshin_Impact"),
        GameMeta("starrail","3","Honkai: Star Rail","library/seed/03_Honkai_Star_Rail.xlsx","03_Honkai_Star_Rail"),
        GameMeta("endfield","4","Arknights: Endfield","library/seed/04_Arknights_Endfield.xlsx","04_Arknights_Endfield"),
        GameMeta("zzz","5","Zenless Zone Zero","library/seed/05_Zenless_Zone_Zero.xlsx","05_Zenless_Zone_Zero")
    )
}