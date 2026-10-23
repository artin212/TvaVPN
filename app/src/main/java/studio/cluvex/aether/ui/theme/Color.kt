package studio.cluvex.aether.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// The Aether dark-navy ("سورمه‌ای") palette.
//
// ONE curated ramp, used by every surface in the app. It replaces the old
// mixture of a four-step navy ramp for the fallback theme, a separate set of
// "brand tokens" for the connection card, and Material You's wallpaper colours
// for everything else - which is why the same app could show a navy home screen
// and a lilac settings sheet on the same phone.
//
// The ramp is built the way the big Android apps build a dark theme: an almost
// black base, then four elevation steps that each add a measured amount of
// lightness (never transparency, which double-darkens when two cards overlap),
// and a single hue (222 deg) held constant across all of them so nothing looks
// like a different blue.
// ---------------------------------------------------------------------------

/** The page behind everything. */
val Navy950 = Color(0xFF070B14)
/** Sheets, drawers, the settings surface. */
val Navy900 = Color(0xFF0B111D)
/** Elevation 1: a settings group / card. */
val Navy850 = Color(0xFF101827)
/** Elevation 2: a row inside a card, an input field. */
val Navy800 = Color(0xFF152034)
/** Elevation 3: a pressed row, the selected segment's track. */
val Navy750 = Color(0xFF1B2842)
/** Hairlines and dividers. */
val Navy700 = Color(0xFF223154)
/** Disabled strokes. */
val Navy600 = Color(0xFF2B3C63)

// ---- Accents -------------------------------------------------------------

/** Primary action / selected state. */
val AetherBlue = Color(0xFF5B93FF)
/** Container tint behind a primary icon. */
val AetherBlueDim = Color(0xFF1B2C52)
/** The connected accent, shared with the card's travelling edge. */
val AetherMint = Color(0xFF3EDBB0)
val AetherMintDim = Color(0xFF10322C)
/** Second light of the animated card edge, cooler than the mint. */
val AetherGlowCyan = Color(0xFF35D0E8)
val AetherCyan = Color(0xFF32E0C4)
val AetherError = Color(0xFFFF5C7A)
val AetherErrorDim = Color(0xFF3A1522)
/** Warning / "fair" quality. */
val AetherAmber = Color(0xFFFFB74D)

// ---- AI (1.2.9) -----------------------------------------------------------
//
// The assistant gets its OWN accent rather than reusing the primary blue, and
// that is a functional decision rather than decoration: an AI icon sits directly
// beside a switch, a chevron and a value label on the same settings row, all of
// which are already primary blue. In one colour the row reads as four controls of
// equal weight and the user cannot tell at a glance which mark opens an
// explanation and which one changes their tunnel. The violet is one hue step off
// the ramp - same darkness, same saturation family - so it belongs to the palette
// while never being mistaken for an action.

/** Everything the assistant owns: its icon, its bubbles, its accents. */
val AetherViolet = Color(0xFF9B8CFF)

/** Container tint behind an AI icon, matched to the ramp's elevation steps. */
val AetherVioletDim = Color(0xFF241E4A)

// ---- Text ----------------------------------------------------------------

val OnDark = Color(0xFFE8EEF9)
val OnDarkMuted = Color(0xFF93A1BC)
val OnDarkDim = Color(0xFF64718C)

// ---- Connection card ------------------------------------------------------
//
// The card keeps its own two-stop glass gradient because it is the one surface
// in the app that sits on top of the backdrop rather than in the elevation ramp.
// The stops are now derived from the ramp above so the card belongs to the same
// palette as the settings screens.

val CardSurfaceTop = Color(0xF0142039)
val CardSurfaceBottom = Color(0xF6090F1B)

/** Every sub-container inside the card (IP pill, speed strip, protocol strip). */
val CardSubSurface = Navy800

val CardTextPrimary = OnDark
val CardTextMuted = OnDarkMuted
val CardTextDim = OnDarkDim
