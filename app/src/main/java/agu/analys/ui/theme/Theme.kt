package agu.analys.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun NadiAgentfeedTheme(
    isDarkTheme: Boolean = true,
    themeStyle: ThemeStyle = if (isDarkTheme) ThemeStyle.DARK_NAVY else ThemeStyle.LIGHT_CLEAN,
    accentPreset: AccentColorPreset = AccentColorPreset.BLUE,
    candleStyle: CandleColorStyle = CandleColorStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val effectiveStyle = if (!isDarkTheme && themeStyle != ThemeStyle.LIGHT_CLEAN) {
        ThemeStyle.LIGHT_CLEAN
    } else {
        themeStyle
    }

    val appColors = createCustomAppColors(
        themeStyle = effectiveStyle,
        accent = accentPreset,
        candleStyle = candleStyle
    )

    val isDark = effectiveStyle != ThemeStyle.LIGHT_CLEAN

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = appColors.blue,
            onPrimary = Color.White,
            primaryContainer = appColors.surfaceVariant,
            onPrimaryContainer = appColors.textPrimary,
            secondary = appColors.blueSoft,
            onSecondary = Color.White,
            tertiary = appColors.green,
            onTertiary = Color.White,
            background = appColors.background,
            onBackground = appColors.textPrimary,
            surface = appColors.surface,
            onSurface = appColors.textPrimary,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.textSecondary,
            outline = appColors.border,
            error = appColors.red,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = appColors.blue,
            onPrimary = Color.White,
            primaryContainer = appColors.surfaceVariant,
            onPrimaryContainer = appColors.textPrimary,
            secondary = appColors.blueSoft,
            onSecondary = Color.White,
            tertiary = appColors.green,
            onTertiary = Color.White,
            background = appColors.background,
            onBackground = appColors.textPrimary,
            surface = appColors.surface,
            onSurface = appColors.textPrimary,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.textSecondary,
            outline = appColors.border,
            error = appColors.red,
            onError = Color.White
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                window.statusBarColor = appColors.background.toArgb()
                window.navigationBarColor = appColors.surface.toArgb()
            }
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
