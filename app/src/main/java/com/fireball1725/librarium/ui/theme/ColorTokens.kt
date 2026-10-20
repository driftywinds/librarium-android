package com.fireball1725.librarium.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color

/**
 * Port of `Librarium/Views/Components/ColorTokens.swift` (54 lines).
 *
 * That file exists so user-chosen tag colours stay legible. It computes WCAG
 * relative luminance with gamma correction and returns white below a 0.55
 * luminance threshold and the system label colour above it. The point is to port
 * the *function*, not a hard-coded white/black switch — a tag colour is picked by
 * a person and can be any hex.
 */
object ColorTokens {

    /** Below this luminance a tint is dark enough that white text reads on it. */
    private const val WHITE_BELOW_LUMINANCE = 0.55

    /** Parse a `#RRGGBB` / `#RRGGBBAA` / `#RGB` string. Falls back to a neutral grey. */
    fun parseHex(hex: String): Color {
        val cleaned = hex.trim().removePrefix("#")
        val expanded = when (cleaned.length) {
            // #RGB -> #RRGGBB
            3 -> cleaned.map { "$it$it" }.joinToString("")
            else -> cleaned
        }
        return try {
            when (expanded.length) {
                6 -> Color(AndroidColor.parseColor("#$expanded") or 0xFF000000.toInt())
                8 -> {
                    // The web and iOS both write CSS order: RRGGBBAA.
                    val rgb = expanded.substring(0, 6)
                    val alpha = expanded.substring(6, 8)
                    Color(AndroidColor.parseColor("#$alpha$rgb"))
                }
                else -> FallbackGrey
            }
        } catch (_: IllegalArgumentException) {
            FallbackGrey
        }
    }

    private val FallbackGrey = Color(0xFF6B7280)

    /**
     * WCAG relative luminance: sRGB channels linearised, then weighted 0.2126 /
     * 0.7152 / 0.0722.
     */
    fun relativeLuminance(color: Color): Double {
        fun linearise(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearise(color.red) +
            0.7152 * linearise(color.green) +
            0.0722 * linearise(color.blue)
    }

    fun isDark(hex: String): Boolean = relativeLuminance(parseHex(hex)) < WHITE_BELOW_LUMINANCE

    /** Ink for text/icon glyphs drawn on top of [tintHex]. */
    fun chipForeground(tintHex: String): Color =
        if (isDark(tintHex)) Color.White else Color(0xFF1B1D25)

    /** Wash behind a chip, at [opacity] (iOS default 0.18). */
    fun chipBackground(tintHex: String, opacity: Float = 0.18f): Color =
        parseHex(tintHex).copy(alpha = opacity)
}
