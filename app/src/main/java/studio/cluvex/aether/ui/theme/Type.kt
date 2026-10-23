package studio.cluvex.aether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import studio.cluvex.aether.R

/**
 * The app's type scale.
 *
 * ## Why there are two of these
 *
 * Material's default family is Roboto, which has no Persian coverage at all, so
 * every Persian glyph fell through to whatever Arabic-script fallback the device
 * happened to ship - Noto Naskh on a Pixel, something else on a Xiaomi, something
 * else again on a Samsung. Persian rendered in a naskh face with Latin-tuned
 * metrics next to Roboto numerals, differently on every phone.
 *
 * [Vazirmatn] is now bundled and used for the WHOLE UI whenever the app is
 * rendering Persian: every screen, every settings row, every dialog, every
 * button, every snackbar. It is applied by overriding all fifteen Material type
 * roles rather than by touching call sites, because Material 3 wraps its content
 * in `ProvideTextStyle(typography.bodyLarge)` and each of its components reads its
 * own role from the same [Typography] - so replacing the scale is the only change
 * that genuinely reaches everything, including composables written later.
 *
 * English keeps the stock scale: Vazirmatn's Latin is perfectly good, but there is
 * no reason to repaint a UI that was designed against Roboto's metrics.
 *
 * ## The bundled file is Bold only
 *
 * Only `Vazirmatn-Bold.ttf` ships with the app, so every weight is mapped to it
 * EXPLICITLY. That is deliberate: a family that declares only 700 would make
 * Compose apply synthetic emboldening on top of an already-bold face for any
 * heavier role, which is the smeared, too-heavy look of faked bold. Declaring the
 * file at each weight guarantees an exact match and therefore no synthesis at all.
 * Drop `Vazirmatn-Regular.ttf` and `Vazirmatn-Medium.ttf` into `res/font` and
 * point the lighter entries at them to get a real weight ramp.
 */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_bold, FontWeight.Thin),
    Font(R.font.vazirmatn_bold, FontWeight.ExtraLight),
    Font(R.font.vazirmatn_bold, FontWeight.Light),
    Font(R.font.vazirmatn_bold, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_bold, FontWeight.ExtraBold),
    Font(R.font.vazirmatn_bold, FontWeight.Black),
)

/** Material 3 default type scale; displayLarge/headlineMedium/etc. are all defined. */
val AetherTypography = Typography()

/** The same scale, re-cut in Vazirmatn. Built once, not per recomposition. */
private val AetherTypographyPersian = AetherTypography.withFontFamily(Vazirmatn)

/**
 * The type scale for the language the app is currently rendering in.
 *
 * @param persian true when the UI is in Persian (see
 *   `LanguagePrefs.isPersian`), which is the only thing that decides the face.
 */
fun aetherTypography(persian: Boolean): Typography =
    if (persian) AetherTypographyPersian else AetherTypography

/**
 * Re-cuts every Material type role in [family].
 *
 * All fifteen roles are listed on purpose. Anything left out would silently keep
 * Roboto and reintroduce the mixed-typeface look this exists to remove, and there
 * is no API that maps over the roles for us.
 */
private fun Typography.withFontFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
