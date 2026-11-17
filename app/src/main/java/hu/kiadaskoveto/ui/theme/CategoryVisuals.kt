package hu.kiadaskoveto.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChildFriendly
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Egg
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KebabDining
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.RiceBowl
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material.icons.outlined.SmokingRooms
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** Icon and identity tint per category. Identity is carried by icon + name; the tint only supports it. */
object CategoryVisuals {

    data class Visual(val key: String, val icon: ImageVector, val color: Long)

    val iconOptions: List<Pair<String, ImageVector>> = listOf(
        "produce" to Icons.Outlined.Eco,
        "meat" to Icons.Outlined.KebabDining,
        "fish" to Icons.Outlined.SetMeal,
        "dairy" to Icons.Outlined.Egg,
        "bakery" to Icons.Outlined.BakeryDining,
        "food_other" to Icons.Outlined.RiceBowl,
        "drinks" to Icons.Outlined.LocalDrink,
        "coffee" to Icons.Outlined.LocalCafe,
        "sweets" to Icons.Outlined.Cake,
        "frozen" to Icons.Outlined.AcUnit,
        "household" to Icons.Outlined.Home,
        "cleaning" to Icons.Outlined.CleaningServices,
        "cosmetics" to Icons.Outlined.Spa,
        "pharmacy" to Icons.Outlined.LocalPharmacy,
        "baby" to Icons.Outlined.ChildFriendly,
        "pet" to Icons.Outlined.Pets,
        "alcohol" to Icons.Outlined.WineBar,
        "tobacco" to Icons.Outlined.SmokingRooms,
        "flowers" to Icons.Outlined.LocalFlorist,
        "clothes" to Icons.Outlined.Checkroom,
        "games" to Icons.Outlined.SportsEsports,
        "tools" to Icons.Outlined.Build,
        "grocery" to Icons.Outlined.LocalGroceryStore,
        "other" to Icons.Outlined.Category,
        "uncategorized" to Icons.AutoMirrored.Outlined.HelpOutline,
    )

    private val iconMap = iconOptions.toMap()

    fun icon(iconKey: String?): ImageVector = iconMap[iconKey] ?: Icons.Outlined.Category

    /** Default tints (mid-chroma, readable as icon colour on both themes' containers). */
    val defaultColors: Map<String, Long> = mapOf(
        "produce" to 0xFF3E8E41, "meat" to 0xFFB5523B, "dairy" to 0xFF4A7FB5, "bakery" to 0xFFB07A2A,
        "food_other" to 0xFF8A6D3B, "drinks" to 0xFF2E86A8, "sweets" to 0xFFC0567E, "frozen" to 0xFF4F8FC0,
        "household" to 0xFF6D6A8F, "cleaning" to 0xFF3E9E9A, "cosmetics" to 0xFF9C5FB5, "baby" to 0xFFD08A4E,
        "pet" to 0xFF8B6B4E, "alcohol" to 0xFF8E3B5B, "tobacco" to 0xFF6E6E6E, "other" to 0xFF5F7A70,
        "uncategorized" to 0xFF8A938D,
    )

    /** Palette offered when the user creates or recolours a category. */
    val pickerColors: List<Long> = listOf(
        0xFF3E8E41, 0xFF0E7A5A, 0xFF3E9E9A, 0xFF2E86A8, 0xFF4A7FB5, 0xFF4F5FC0, 0xFF6D6A8F, 0xFF9C5FB5,
        0xFFC0567E, 0xFF8E3B5B, 0xFFB5523B, 0xFFD08A4E, 0xFFB07A2A, 0xFF8A6D3B, 0xFF8B6B4E, 0xFF6E6E6E,
    )

    fun color(argb: Long): Color = Color(argb.toInt())
}
