package com.pinpin.app.overlay

import android.content.Context
import android.content.res.Configuration
import com.pinpin.app.R

/**
 * Centralized theme tokens for the pin overlay UI.
 *
 * The overlay (toolbar pill, close button, passthrough exit button,
 * drawing overlay toolbar) reads its fill / stroke / icon colors
 * from this object. The colors are **theme-aware** — they follow
 * the device's light / dark mode setting so the floating UI looks
 * like a natural extension of the system at all times.
 *
 * Usage:
 * ```
 * val theme = PinTheme.of(context)
 * drawable.setColor(theme.surfaceFill)
 * imageView.imageTintList = ColorStateList.valueOf(theme.iconColor)
 * ```
 *
 * The system theme can change at runtime (user toggles dark mode
 * in Settings, or a manufacturer-specific auto-switch fires). The
 * [PinOverlayService] detects this via [onConfigurationChanged]
 * and calls [PinWindowView.applyTheme] on every active view, which
 * re-reads these values and re-applies them to the toolbar /
 * buttons / close button / annotation toolbar.
 */
data class PinTheme(
    /** Fill color for the toolbar pill, close button, and other surfaces. */
    val surfaceFill: Int,
    /** Stroke (border) color for the toolbar pill and similar surfaces. */
    val surfaceStroke: Int,
    /** Icon and text color used on top of the surface. */
    val iconColor: Int,
) {
    /** Whether the resolved theme corresponds to the dark mode. */
    val isDark: Boolean get() = iconColor == LIGHT_ICON

    companion object {
        // Reference icon colors used for isDark detection. Must
        // stay in sync with colors.xml.
        private val LIGHT_ICON = 0xFFFFFFFF.toInt()

        /**
         * Resolve the current [PinTheme] from the given [context].
         * Reads `Configuration.UI_MODE_NIGHT_MASK` to decide
         * between light and dark.
         */
        fun of(context: Context): PinTheme {
            val nightMode = (context.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK)
            val isDark = nightMode == Configuration.UI_MODE_NIGHT_YES
            return if (isDark) {
                PinTheme(
                    surfaceFill = context.getColor(R.color.pin_surface_fill_dark),
                    surfaceStroke = context.getColor(R.color.pin_surface_stroke_dark),
                    iconColor = context.getColor(R.color.pin_icon_color_dark),
                )
            } else {
                PinTheme(
                    surfaceFill = context.getColor(R.color.pin_surface_fill_light),
                    surfaceStroke = context.getColor(R.color.pin_surface_stroke_light),
                    iconColor = context.getColor(R.color.pin_icon_color_light),
                )
            }
        }
    }
}
