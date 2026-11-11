package agu.analys.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeStyle(val displayName: String, val description: String) {
    DARK_NAVY("Dark Navy (Default)", "TradingView Navy Slate"),
    OLED_BLACK("AMOLED Pitch Black", "Hitam pekat OLED hemat baterai"),
    LIGHT_CLEAN("Light Clean", "Tampilan terang Slate-50 minimalis"),
    CYBERPUNK("Cyberpunk Matrix", "Nuansa gelap hijau neon berenergi"),
    ROYAL_PURPLE("Royal Purple", "Nuansa ungu midnight modern"),
    SUNSET_AMBER("Sunset Gold", "Nuansa gelap emas hangat"),
    EMERALD_OCEAN("Oceanic Teal", "Nuansa hijau toska laut dalam")
}

enum class AccentColorPreset(val displayName: String, val primary: Color, val soft: Color) {
    BLUE("Electric Blue", Color(0xFF3B82F6), Color(0xFF60A5FA)),
    EMERALD("Emerald Green", Color(0xFF10B981), Color(0xFF34D399)),
    CYAN("Cyber Cyan", Color(0xFF06B6D4), Color(0xFF67E8F9)),
    PURPLE("Neon Purple", Color(0xFFA855F7), Color(0xFFC084FC)),
    AMBER("Sunset Gold", Color(0xFFF59E0B), Color(0xFFFBBF24)),
    ROSE("Crimson Rose", Color(0xFFF43F5E), Color(0xFFFB7185))
}

enum class CandleColorStyle(
    val displayName: String,
    val bullish: Color,
    val bullishLight: Color,
    val bearish: Color,
    val bearishLight: Color
) {
    CLASSIC(
        "Klasik (Hijau / Merah)",
        Color(0xFF10B981), Color(0xFF34D399),
        Color(0xFFEF4444), Color(0xFFF87171)
    ),
    TEAL_ROSE(
        "Modern (Teal / Rose)",
        Color(0xFF14B8A6), Color(0xFF2DD4BF),
        Color(0xFFF43F5E), Color(0xFFFB7185)
    ),
    CYAN_MAGENTA(
        "Cyber (Cyan / Magenta)",
        Color(0xFF00E5FF), Color(0xFF80F0FF),
        Color(0xFFFF007F), Color(0xFFFF66B2)
    ),
    MONOCHROME(
        "Monokrom (Putih / Slate)",
        Color(0xFFFFFFFF), Color(0xFFE2E8F0),
        Color(0xFF64748B), Color(0xFF94A3B8)
    )
}

enum class AnimationSpeed(val displayName: String, val durationMs: Int, val description: String) {
    SMOOTH("Halus & Dinamis (300 ms)", 300, "Transisi visual halus & responsif"),
    FAST("Cepat & Snappy (150 ms)", 150, "Perpindahan halaman instan"),
    REDUCED("Minimal / Hemat Daya (0 ms)", 0, "Tanpa efek transisi, hemat baterai")
}

data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val cardBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val green: Color,
    val greenLight: Color,
    val red: Color,
    val redLight: Color,
    val blue: Color,
    val blueSoft: Color,
    val amber: Color,
    val orange: Color
)

fun createCustomAppColors(
    themeStyle: ThemeStyle = ThemeStyle.DARK_NAVY,
    accent: AccentColorPreset = AccentColorPreset.BLUE,
    candleStyle: CandleColorStyle = CandleColorStyle.CLASSIC
): AppColors {
    val green = candleStyle.bullish
    val greenLight = candleStyle.bullishLight
    val red = candleStyle.bearish
    val redLight = candleStyle.bearishLight
    val blue = accent.primary
    val blueSoft = accent.soft

    return when (themeStyle) {
        ThemeStyle.DARK_NAVY -> AppColors(
            background = Color(0xFF0B1220),
            surface = Color(0xFF111A2E),
            surfaceVariant = Color(0xFF172239),
            cardBackground = Color(0xFF111A2E),
            textPrimary = Color(0xFFF1F5F9),
            textSecondary = Color(0xFF94A3B8),
            textMuted = Color(0xFF64748B),
            border = Color(0xFF1E2D4A),
            green = green,
            greenLight = greenLight,
            red = red,
            redLight = redLight,
            blue = blue,
            blueSoft = blueSoft,
            amber = Color(0xFFF59E0B),
            orange = Color(0xFFFB923C)
        )
        ThemeStyle.OLED_BLACK -> AppColors(
            background = Color(0xFF000000),
            surface = Color(0xFF0A0A0A),
            surfaceVariant = Color(0xFF141414),
            cardBackground = Color(0xFF0D0D0D),
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFA3A3A3),
            textMuted = Color(0xFF737373),
            border = Color(0xFF262626),
            green = green,
            greenLight = greenLight,
            red = red,
            redLight = redLight,
            blue = blue,
            blueSoft = blueSoft,
            amber = Color(0xFFF59E0B),
            orange = Color(0xFFFB923C)
        )
        ThemeStyle.LIGHT_CLEAN -> AppColors(
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F5F9),
            cardBackground = Color(0xFFFFFFFF),
            textPrimary = Color(0xFF0F172A),      // Slate 900 - font gelap pekat sangat terbaca
            textSecondary = Color(0xFF1E293B),    // Slate 800 - font sekunder kontras tinggi
            textMuted = Color(0xFF475569),        // Slate 600 - font label gelap terbaca jelas
            border = Color(0xFF64748B),           // Slate 500 - frame / bingkai kartu tegas dan gelap
            green = Color(0xFF15803D),            // Dark Green 700 - ikon/indikator hijau gelap kontras
            greenLight = Color(0xFF16A34A),
            red = Color(0xFFB91C1C),              // Dark Red 700 - ikon/indikator merah gelap kontras
            redLight = Color(0xFFDC2626),
            blue = Color(0xFF1D4ED8),             // Deep Blue 700 - aksen biru gelap kontras
            blueSoft = Color(0xFF2563EB),
            amber = Color(0xFFB45309),            // Dark Amber 700 - indikator amber gelap
            orange = Color(0xFFC2410C)            // Dark Orange 700
        )
        ThemeStyle.CYBERPUNK -> AppColors(
            background = Color(0xFF040A07),
            surface = Color(0xFF0A140F),
            surfaceVariant = Color(0xFF102018),
            cardBackground = Color(0xFF0B1711),
            textPrimary = Color(0xFFE8F5E9),
            textSecondary = Color(0xFF81C784),
            textMuted = Color(0xFF4CAF50),
            border = Color(0xFF1B382B),
            green = green,
            greenLight = greenLight,
            red = red,
            redLight = redLight,
            blue = blue,
            blueSoft = blueSoft,
            amber = Color(0xFFFFD54F),
            orange = Color(0xFFFFB74D)
        )
        ThemeStyle.ROYAL_PURPLE -> AppColors(
            background = Color(0xFF0B071A),
            surface = Color(0xFF130D2B),
            surfaceVariant = Color(0xFF1C143F),
            cardBackground = Color(0xFF140E2E),
            textPrimary = Color(0xFFF3E8FF),
            textSecondary = Color(0xFFC084FC),
            textMuted = Color(0xFF9333EA),
            border = Color(0xFF2E1F5E),
            green = green,
            greenLight = greenLight,
            red = red,
            redLight = redLight,
            blue = blue,
            blueSoft = blueSoft,
            amber = Color(0xFFFBBF24),
            orange = Color(0xFFFB923C)
        )
        ThemeStyle.SUNSET_AMBER -> AppColors(
            background = Color(0xFF120B05),
            surface = Color(0xFF1E130A),
            surfaceVariant = Color(0xFF2E1C10),
            cardBackground = Color(0xFF1F140B),
            textPrimary = Color(0xFFFEF3C7),
            textSecondary = Color(0xFFFCD34D),
            textMuted = Color(0xFFF59E0B),
            border = Color(0xFF452B18),
            green = green,
            greenLight = greenLight,
            red = red,
            redLight = redLight,
            blue = blue,
            blueSoft = blueSoft,
            amber = Color(0xFFF59E0B),
            orange = Color(0xFFFB923C)
        )
        ThemeStyle.EMERALD_OCEAN -> AppColors(
            background = Color(0xFF040E14),
            surface = Color(0xFF091A24),
            surfaceVariant = Color(0xFF0E2838),
            cardBackground = Color(0xFF0A1D28),
            textPrimary = Color(0xFFE0F2FE),
            textSecondary = Color(0xFF7DD3FC),
            textMuted = Color(0xFF0284C7),
            border = Color(0xFF163E57),
            green = green,
            greenLight = greenLight,
            red = red,
            redLight = redLight,
            blue = blue,
            blueSoft = blueSoft,
            amber = Color(0xFFF59E0B),
            orange = Color(0xFFFB923C)
        )
    }
}

val DarkAppColors = createCustomAppColors(ThemeStyle.DARK_NAVY)
val LightAppColors = createCustomAppColors(ThemeStyle.LIGHT_CLEAN)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

val TvBackground: Color @Composable get() = LocalAppColors.current.background
val TvSurface: Color @Composable get() = LocalAppColors.current.surface
val TvSurfaceVariant: Color @Composable get() = LocalAppColors.current.surfaceVariant
val TvCardBackground: Color @Composable get() = LocalAppColors.current.cardBackground

val TvTextPrimary: Color @Composable get() = LocalAppColors.current.textPrimary
val TvTextSecondary: Color @Composable get() = LocalAppColors.current.textSecondary
val TvTextMuted: Color @Composable get() = LocalAppColors.current.textMuted
val TvBorder: Color @Composable get() = LocalAppColors.current.border

val TvGreen: Color @Composable get() = LocalAppColors.current.green
val TvGreenLight: Color @Composable get() = LocalAppColors.current.greenLight
val TvRed: Color @Composable get() = LocalAppColors.current.red
val TvRedLight: Color @Composable get() = LocalAppColors.current.redLight
val TvBlue: Color @Composable get() = LocalAppColors.current.blue
val TvBlueSoft: Color @Composable get() = LocalAppColors.current.blueSoft
val TvAmber: Color @Composable get() = LocalAppColors.current.amber
val TvOrange: Color @Composable get() = LocalAppColors.current.orange
val TvCyan: Color
    @Composable get() = if (LocalAppColors.current.textPrimary == Color(0xFF0F172A) || LocalAppColors.current.background == Color(0xFFF8FAFC)) {
        Color(0xFF0369A1) // Sky 700 (Deep Cyan / Oceanic Blue) - gelap & terbaca jelas di Light Mode
    } else {
        Color(0xFF00E5FF) // Electric Cyan di Dark Mode
    }
