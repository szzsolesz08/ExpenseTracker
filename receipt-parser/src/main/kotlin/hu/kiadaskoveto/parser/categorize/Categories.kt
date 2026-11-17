package hu.kiadaskoveto.parser.categorize

/** Stable keys of the built-in categories. User-created categories get `user-…` keys. */
object CategoryKeys {
    const val PRODUCE = "produce"
    const val MEAT = "meat"
    const val DAIRY = "dairy"
    const val BAKERY = "bakery"
    const val FOOD_OTHER = "food_other"
    const val DRINKS = "drinks"
    const val SWEETS = "sweets"
    const val FROZEN = "frozen"
    const val HOUSEHOLD = "household"
    const val CLEANING = "cleaning"
    const val COSMETICS = "cosmetics"
    const val BABY = "baby"
    const val PET = "pet"
    const val ALCOHOL = "alcohol"
    const val TOBACCO = "tobacco"
    const val OTHER = "other"
    const val UNCATEGORIZED = "uncategorized"
}

data class DefaultCategory(val key: String, val name: String, val sortOrder: Int)

object DefaultCategories {
    val ALL: List<DefaultCategory> = listOf(
        DefaultCategory(CategoryKeys.PRODUCE, "Zöldség és gyümölcs", 1),
        DefaultCategory(CategoryKeys.MEAT, "Hús és húskészítmények", 2),
        DefaultCategory(CategoryKeys.DAIRY, "Tejtermékek", 3),
        DefaultCategory(CategoryKeys.BAKERY, "Pékáru", 4),
        DefaultCategory(CategoryKeys.FOOD_OTHER, "Élelmiszer – egyéb", 5),
        DefaultCategory(CategoryKeys.DRINKS, "Italok", 6),
        DefaultCategory(CategoryKeys.SWEETS, "Édesség és nassolás", 7),
        DefaultCategory(CategoryKeys.FROZEN, "Fagyasztott termékek", 8),
        DefaultCategory(CategoryKeys.HOUSEHOLD, "Háztartási termékek", 9),
        DefaultCategory(CategoryKeys.CLEANING, "Tisztítószerek", 10),
        DefaultCategory(CategoryKeys.COSMETICS, "Kozmetikumok és higiénia", 11),
        DefaultCategory(CategoryKeys.BABY, "Babaápolás", 12),
        DefaultCategory(CategoryKeys.PET, "Állateledel", 13),
        DefaultCategory(CategoryKeys.ALCOHOL, "Alkohol", 14),
        DefaultCategory(CategoryKeys.TOBACCO, "Dohánytermékek", 15),
        DefaultCategory(CategoryKeys.OTHER, "Egyéb", 16),
        DefaultCategory(CategoryKeys.UNCATEGORIZED, "Nem besorolt", 17),
    )
}
