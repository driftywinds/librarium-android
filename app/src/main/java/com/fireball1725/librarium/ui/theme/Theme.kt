package com.fireball1725.librarium.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireball1725.librarium.R

/**
 * Port of `Librarium/Redesigned/Theme.swift` (160 lines), which is itself a
 * mirror of the CSS custom properties in `plans/ios-redesign/mockup.html`.
 *
 * That file is the single source of truth on iOS and the same rule applies here:
 * never hard-code a hex in a composable, take it from [LibrariumTheme.colors].
 *
 * The app is **dark-only**. Every iOS screen paints `appBackground` with
 * `ignoresSafeArea`, and there is no light palette in `Theme.swift` and no
 * `@Environment(\.colorScheme)` branch anywhere in the app. So this is a fixed
 * dark palette rather than a light/dark pair.
 */
@Immutable
data class LibrariumColors(
    val appBackground: Color = Color(0xFF0C0D12),
    val appBackgroundElevated: Color = Color(0xFF16181F),
    val appCard: Color = Color(0xFF1B1D25),
    /** What the floating tab bar sits on: elevated, at 72 % alpha. */
    val appBackgroundTrans: Color = Color(0x16181F).copy(alpha = 0.72f),

    /** Primary text. */
    val appText: Color = Color(0xFFF1EDE2),
    /** Body / supporting text. */
    val appText2: Color = Color(0xFFB9B3A4),
    /** Meta / timestamps. Three tiers, deliberately no fourth shade. */
    val appText3: Color = Color(0xFF6E6A5F),

    /** Hairlines. */
    val appLine: Color = Color.White.copy(alpha = 0.07f),
    /** Emphasised dividers. */
    val appLineStrong: Color = Color.White.copy(alpha = 0.13f),

    val accent: Color = Color(0xFF8089FF),
    val accentStrong: Color = Color(0xFFAAB1FF),
    val accentSoft: Color = Color(0x8089FF).copy(alpha = 0.18f),
    /** Far end of every accent gradient: `linear-gradient(160deg, accent, accentDeep)`. */
    val accentDeep: Color = Color(0xFF5A64E8),

    /** read / success. */
    val good: Color = Color(0xFF7BD6A8),
    /** overdue / caution. */
    val warn: Color = Color(0xFFFFB866),
    /** destructive. */
    val bad: Color = Color(0xFFFF8A8A),
    /** rating + admin badges. */
    val gold: Color = Color(0xFFF3C971),
    /** Books out on loan. A deeper amber than [warn]; two close ambers is a known smell. */
    val lent: Color = Color(0xFFF59E0B),
    /** Text on [lent] — amber is too light to carry white. */
    val lentInk: Color = Color(0xFF1A1306),
)

/**
 * `Theme.swift:130-137`. The generous end (`lg` 18 / `xl` 24) is part of the
 * "cozy" identity, together with hairline dividers instead of borders.
 */
@Immutable
data class LibrariumSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 18.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/** `Theme.swift:141-146`. */
@Immutable
data class LibrariumRadius(
    val chip: Dp = 999.dp,
    val pill: Dp = 14.dp,
    val card: Dp = 18.dp,
    val sheet: Dp = 24.dp,
)

/**
 * The two bundled serif faces, declared with several weights against the same
 * variable TTF so Compose applies the `wght` axis.
 *
 * Cinzel ships on iOS (`Cinzel-VariableFont_wght.ttf`) but is deliberately
 * *not* used for text there: `Theme.Fonts.label` uses the system face
 * uppercased and tracked instead, because "Cinzel reads too display-face for
 * utility labels". We reproduce that decision rather than the file.
 */
object LibrariumFonts {
    val display: FontFamily = FontFamily(
        Font(R.font.cormorant_garamond, weight = FontWeight.Normal),
        Font(R.font.cormorant_garamond, weight = FontWeight.Medium),
        Font(R.font.cormorant_garamond, weight = FontWeight.SemiBold),
        Font(R.font.cormorant_garamond, weight = FontWeight.Bold),
    )

    val bodySerif: FontFamily = FontFamily(
        Font(R.font.crimson_pro, weight = FontWeight.Normal),
        Font(R.font.crimson_pro, weight = FontWeight.Medium),
    )

    val bodySerifItalic: FontFamily = FontFamily(
        Font(R.font.crimson_pro_italic, weight = FontWeight.Normal),
        Font(R.font.crimson_pro_italic, weight = FontWeight.Medium),
    )

    /** SF Pro on iOS; the platform face is the equivalent here. */
    val ui: FontFamily = FontFamily.Default
    val mono: FontFamily = FontFamily.Monospace
}

/** `Theme.swift:118-125` — the named type roles. */
@Immutable
data class LibrariumType(
    val pageTitle: TextUnit = 36.sp,
    val heroTitle: TextUnit = 22.sp,
    val cardTitle: TextUnit = 18.sp,
    val rowPrimary: TextUnit = 14.sp,
    val rowMeta: TextUnit = 12.sp,
    val inlineAction: TextUnit = 11.sp,
    val sectionLabel: TextUnit = 11.sp,
    val bodyPara: TextUnit = 15.sp,
)

@Immutable
data class LibrariumTheme(
    val colors: LibrariumColors = LibrariumColors(),
    val spacing: LibrariumSpacing = LibrariumSpacing(),
    val radius: LibrariumRadius = LibrariumRadius(),
    val type: LibrariumType = LibrariumType(),
)

val LocalLibrariumTheme = staticCompositionLocalOf { LibrariumTheme() }
