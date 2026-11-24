package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Base Brand Tokens (Orange Primary + Teal Secondary)
val BabyOrangeDark = Color(0xFFFF8A5B)
val OnBabyOrangeDark = Color(0xFF2B0D02)
val BabyOrangeContainerDark = Color(0xFF4A2215)
val OnBabyOrangeContainerDark = Color(0xFFFFD8C8)

val BabyTealDark = Color(0xFF2DD4BF)
val OnBabyTealDark = Color(0xFF032622)
val BabyTealContainerDark = Color(0xFF0D3D38)
val OnBabyTealContainerDark = Color(0xFFA7F3EB)

val BabySuccessDark = Color(0xFF34D399)
val OnBabySuccessDark = Color(0xFF032619)
val BabySuccessContainerDark = Color(0xFF0F3B2B)
val OnBabySuccessContainerDark = Color(0xFFA7F3D0)

val BabyErrorDark = Color(0xFFF87171)
val OnBabyErrorDark = Color(0xFF33080C)
val BabyErrorContainerDark = Color(0xFF52181E)
val OnBabyErrorContainerDark = Color(0xFFFFD6DA)

val BabyWarningDark = Color(0xFFFBBF24)
val OnBabyWarningDark = Color(0xFF2E1E02)
val BabyWarningContainerDark = Color(0xFF3D2A07)
val OnBabyWarningContainerDark = Color(0xFFFDE68A)

// Midnight Dark Surfaces
val NightBg = Color(0xFF0B0E17)
val NightSurface = Color(0xFF121726)
val NightSurfaceVariant = Color(0xFF1A2237)
val NightSurfaceHigh = Color(0xFF222C45)
val NightOutline = Color(0xFF313D5C)
val TextPrimaryNight = Color(0xFFF5F7FC)
val TextSecondaryNight = Color(0xFFB3BDD4)

// AMOLED Pure Black Surfaces
val AmoledBg = Color(0xFF000000)
val AmoledSurface = Color(0xFF06080E)
val AmoledSurfaceVariant = Color(0xFF101521)
val AmoledSurfaceHigh = Color(0xFF171E2E)
val AmoledOutline = Color(0xFF2B3550)

// Daylight Light Palette (WCAG AA compliant)
val DaylightBg = Color(0xFFF6F7FB)
val DaylightSurface = Color(0xFFFFFFFF)
val DaylightSurfaceVariant = Color(0xFFECEFF7)
val DaylightSurfaceHigh = Color(0xFFE2E7F2)
val DaylightOutline = Color(0xFFC5CEE0)
val TextPrimaryDaylight = Color(0xFF0F172A)
val TextSecondaryDaylight = Color(0xFF475569)

val BabyOrangeLight = Color(0xFFC2410C)
val OnBabyOrangeLight = Color(0xFFFFFFFF)
val BabyOrangeContainerLight = Color(0xFFFFE6DA)
val OnBabyOrangeContainerLight = Color(0xFF431404)

val BabyTealLight = Color(0xFF0F766E)
val OnBabyTealLight = Color(0xFFFFFFFF)
val BabyTealContainerLight = Color(0xFFCCFBF1)
val OnBabyTealContainerLight = Color(0xFF042F2E)

val BabySuccessLight = Color(0xFF047857)
val OnBabySuccessLight = Color(0xFFFFFFFF)
val BabySuccessContainerLight = Color(0xFFD1FAE5)
val OnBabySuccessContainerLight = Color(0xFF064E3B)

val BabyErrorLight = Color(0xFFB91C1C)
val OnBabyErrorLight = Color(0xFFFFFFFF)
val BabyErrorContainerLight = Color(0xFFFEE2E2)
val OnBabyErrorContainerLight = Color(0xFF7F1D1D)

val BabyWarningLight = Color(0xFFB45309)
val OnBabyWarningLight = Color(0xFFFFFFFF)
val BabyWarningContainerLight = Color(0xFFFEF3C7)
val OnBabyWarningContainerLight = Color(0xFF78350F)

@Immutable
data class BabyExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val userBubbleGradientStart: Color,
    val userBubbleGradientEnd: Color,
    val onUserBubble: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val orbError: Color,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val dangerZoneBg: Color,
    val dangerZoneBorder: Color
)

val DarkBabyExtendedColors = BabyExtendedColors(
    success = BabySuccessDark,
    onSuccess = OnBabySuccessDark,
    successContainer = BabySuccessContainerDark,
    onSuccessContainer = OnBabySuccessContainerDark,
    warning = BabyWarningDark,
    onWarning = OnBabyWarningDark,
    warningContainer = BabyWarningContainerDark,
    onWarningContainer = OnBabyWarningContainerDark,
    userBubbleGradientStart = Color(0xFFFF7849),
    userBubbleGradientEnd = Color(0xFFE05324),
    onUserBubble = Color(0xFFFFFFFF),
    orbPrimary = BabyOrangeDark,
    orbSecondary = BabyTealDark,
    orbError = BabyErrorDark,
    shimmerBase = Color(0xFF1A2237),
    shimmerHighlight = Color(0xFF293554),
    dangerZoneBg = Color(0xFF2D1116),
    dangerZoneBorder = Color(0xFF7F2933)
)

val AmoledBabyExtendedColors = DarkBabyExtendedColors.copy(
    shimmerBase = Color(0xFF101521),
    shimmerHighlight = Color(0xFF1E273D),
    dangerZoneBg = Color(0xFF220A0E)
)

val LightBabyExtendedColors = BabyExtendedColors(
    success = BabySuccessLight,
    onSuccess = OnBabySuccessLight,
    successContainer = BabySuccessContainerLight,
    onSuccessContainer = OnBabySuccessContainerLight,
    warning = BabyWarningLight,
    onWarning = OnBabyWarningLight,
    warningContainer = BabyWarningContainerLight,
    onWarningContainer = OnBabyWarningContainerLight,
    userBubbleGradientStart = Color(0xFFD94E1F),
    userBubbleGradientEnd = Color(0xFFB8390F),
    onUserBubble = Color(0xFFFFFFFF),
    orbPrimary = BabyOrangeLight,
    orbSecondary = BabyTealLight,
    orbError = BabyErrorLight,
    shimmerBase = Color(0xFFE6EAF3),
    shimmerHighlight = Color(0xFFF6F8FC),
    dangerZoneBg = Color(0xFFFFF1F2),
    dangerZoneBorder = Color(0xFFFCA5A5)
)
