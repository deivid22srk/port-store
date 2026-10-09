package com.deivid22srk.portstore.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Lime = Color(0xFFDFFF00)
val LimeDim = Color(0xFFB9C700)
val BgDark = Color(0xFF0F0F0F)
val SurfaceDark = Color(0xFF141414)
val SurfaceContainerDark = Color(0xFF1B1B1B)
val SurfaceContainerHighDark = Color(0xFF232323)
val OutlineDark = Color(0xFF3C3C3C)
val TextPrimary = Color(0xFFEDEDED)
val TextSecondary = Color(0xFFA8A8A8)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = Color(0xFF171800),
    primaryContainer = Color(0xFF3A4100),
    onPrimaryContainer = Lime,
    secondary = LimeDim,
    onSecondary = Color(0xFF171800),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainerLowest = Color(0xFF101010),
    outline = OutlineDark,
    outlineVariant = Color(0xFF2C2C2C),
    error = Color(0xFFFF5449),
    onError = Color(0xFF2B0000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5F6D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4FB4F),
    onPrimaryContainer = Color(0xFF1C2000),
    background = Color(0xFFFCFCF5),
    onBackground = Color(0xFF1B1C17),
    surface = Color(0xFFFDFDF7),
    onSurface = Color(0xFF1B1C17),
    surfaceVariant = Color(0xFFE6E7D6),
    onSurfaceVariant = Color(0xFF47493E),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun PortStoreTheme(
    themeMode: Int,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        com.deivid22srk.portstore.settings.AppSettings.THEME_LIGHT -> false
        com.deivid22srk.portstore.settings.AppSettings.THEME_SYSTEM -> isSystemInDarkTheme()
        else -> true // escuro por padrão (identidade Hail Games)
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
