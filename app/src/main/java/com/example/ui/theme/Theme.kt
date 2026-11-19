package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BotDarkColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = ObsidianBg,
    primaryContainer = AmberGoldMuted,
    onPrimaryContainer = WarmCream,
    secondary = SkyPredict,
    onSecondary = ObsidianBg,
    secondaryContainer = SkySurface,
    onSecondaryContainer = WarmCream,
    tertiary = EmeraldSafe,
    onTertiary = ObsidianBg,
    tertiaryContainer = EmeraldSurface,
    onTertiaryContainer = WarmCream,
    error = CrimsonRisk,
    onError = WarmCream,
    errorContainer = CrimsonSurface,
    onErrorContainer = WarmCream,
    background = ObsidianBg,
    onBackground = WarmCream,
    surface = SlateSurface,
    onSurface = WarmCream,
    surfaceVariant = SlateCard,
    onSurfaceVariant = MutedSlateText,
    outline = SlateBorder,
)

private val BotShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun TileBotTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BotDarkColorScheme,
        typography = AppTypography,
        shapes = BotShapes,
        content = content,
    )
}
