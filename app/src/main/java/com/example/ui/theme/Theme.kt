package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class BabyThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    AMOLED("AMOLED Black"),
    SYSTEM("System")
}

private val BabyDarkColorScheme = darkColorScheme(
    primary = BabyOrangeDark,
    onPrimary = OnBabyOrangeDark,
    primaryContainer = BabyOrangeContainerDark,
    onPrimaryContainer = OnBabyOrangeContainerDark,
    secondary = BabyTealDark,
    onSecondary = OnBabyTealDark,
    secondaryContainer = BabyTealContainerDark,
    onSecondaryContainer = OnBabyTealContainerDark,
    tertiary = BabySuccessDark,
    onTertiary = OnBabySuccessDark,
    tertiaryContainer = BabySuccessContainerDark,
    onTertiaryContainer = OnBabySuccessContainerDark,
    error = BabyErrorDark,
    onError = OnBabyErrorDark,
    errorContainer = BabyErrorContainerDark,
    onErrorContainer = OnBabyErrorContainerDark,
    background = NightBg,
    onBackground = TextPrimaryNight,
    surface = NightSurface,
    onSurface = TextPrimaryNight,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = TextSecondaryNight,
    surfaceContainerLowest = NightBg,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurfaceVariant,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = ColorTokenHighestDark,
    outline = NightOutline,
    outlineVariant = NightOutline.copy(alpha = 0.6f)
)

private val ColorTokenHighestDark
    get() = androidx.compose.ui.graphics.Color(0xFF2B3755)

private val BabyAmoledColorScheme = BabyDarkColorScheme.copy(
    background = AmoledBg,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceVariant,
    surfaceContainerLowest = AmoledBg,
    surfaceContainerLow = AmoledSurface,
    surfaceContainer = AmoledSurfaceVariant,
    surfaceContainerHigh = AmoledSurfaceHigh,
    outline = AmoledOutline,
    outlineVariant = AmoledOutline.copy(alpha = 0.65f)
)

private val BabyLightColorScheme = lightColorScheme(
    primary = BabyOrangeLight,
    onPrimary = OnBabyOrangeLight,
    primaryContainer = BabyOrangeContainerLight,
    onPrimaryContainer = OnBabyOrangeContainerLight,
    secondary = BabyTealLight,
    onSecondary = OnBabyTealLight,
    secondaryContainer = BabyTealContainerLight,
    onSecondaryContainer = OnBabyTealContainerLight,
    tertiary = BabySuccessLight,
    onTertiary = OnBabySuccessLight,
    tertiaryContainer = BabySuccessContainerLight,
    onTertiaryContainer = OnBabySuccessContainerLight,
    error = BabyErrorLight,
    onError = OnBabyErrorLight,
    errorContainer = BabyErrorContainerLight,
    onErrorContainer = OnBabyErrorContainerLight,
    background = DaylightBg,
    onBackground = TextPrimaryDaylight,
    surface = DaylightSurface,
    onSurface = TextPrimaryDaylight,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = TextSecondaryDaylight,
    surfaceContainerLowest = DaylightSurface,
    surfaceContainerLow = DaylightBg,
    surfaceContainer = DaylightSurfaceVariant,
    surfaceContainerHigh = DaylightSurfaceHigh,
    outline = DaylightOutline,
    outlineVariant = DaylightOutline.copy(alpha = 0.65f)
)

val BabyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),   // Chips
    medium = RoundedCornerShape(20.dp),  // Bubbles & inputs
    large = RoundedCornerShape(24.dp),   // Large cards
    extraLarge = RoundedCornerShape(28.dp) // Bottom sheets
)

val LocalBabyColors = staticCompositionLocalOf { DarkBabyExtendedColors }

object BabyThemeTokens {
    val colors: BabyExtendedColors
        @Composable get() = LocalBabyColors.current
    val spacing: BabySpacing
        @Composable get() = LocalBabySpacing.current
}

@Composable
fun BabyTheme(
    themeMode: BabyThemeMode = BabyThemeMode.DARK,
    dynamicColor: Boolean = false,
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = darkTheme ?: when (themeMode) {
        BabyThemeMode.DARK, BabyThemeMode.AMOLED -> true
        BabyThemeMode.LIGHT -> false
        BabyThemeMode.SYSTEM -> systemDark
    }
    val useAmoled = themeMode == BabyThemeMode.AMOLED

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val base = if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (useAmoled && effectiveDark) base.copy(background = AmoledBg, surface = AmoledSurface) else base
        }
        useAmoled && effectiveDark -> BabyAmoledColorScheme
        effectiveDark -> BabyDarkColorScheme
        else -> BabyLightColorScheme
    }

    val extendedColors = when {
        useAmoled && effectiveDark -> AmoledBabyExtendedColors
        effectiveDark -> DarkBabyExtendedColors
        else -> LightBabyExtendedColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !effectiveDark
                    isAppearanceLightNavigationBars = !effectiveDark
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalBabyColors provides extendedColors,
        LocalBabySpacing provides BabySpacing()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BabyTypography,
            shapes = BabyShapes,
            content = content
        )
    }
}
