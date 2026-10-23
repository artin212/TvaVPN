package studio.cluvex.aether.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import java.util.Locale

/**
 * The three states of the in-app language switch.
 *
 * [SYSTEM] is not "English": it means the app keeps following the phone, which
 * is what every build before this one did unconditionally.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    PERSIAN("fa"),
    ;

    companion object {
        fun fromTag(raw: String?): AppLanguage =
            entries.firstOrNull { it.tag == raw?.trim().orEmpty() } ?: SYSTEM
    }
}

/**
 * In-app language selection (English / Persian / follow the phone).
 *
 * ## Why this is SharedPreferences and not the DataStore every other setting uses
 *
 * The language has to be known inside `attachBaseContext`, which runs BEFORE
 * `onCreate` and cannot suspend. Every other setting is read asynchronously from
 * DataStore, which is fine for a switch inside a screen but impossible for the
 * value that decides which `strings.xml` that screen is rendered from: an async
 * read would paint the whole first frame in the old language and then visibly
 * re-layout. SharedPreferences answers synchronously from an already-loaded map,
 * so the very first frame is already correct.
 *
 * ## How the override is applied
 *
 * [wrap] returns a context whose [Configuration] carries the chosen locale AND
 * the matching layout direction, so Compose picks up both `stringResource` and
 * RTL/LTR mirroring with no extra work at the call sites. Every component that
 * renders user-visible text wraps its base context: the activities, the VPN
 * service (its notification), the Quick Settings tile and the Application.
 *
 * On Android 13+ the same choice is ALSO handed to the platform's per-app
 * language API, so the app shows the right language in
 * *System settings -> Apps -> Aether -> Language* and the system stops undoing
 * the override on a configuration change. The stored value remains the single
 * source of truth on every API level, which is what keeps the behaviour
 * identical from Android 8 to Android 15.
 *
 * ## ROOT CAUSE of "Persian is not right-to-left when the PHONE is in English"
 *
 * [wrap] is necessary and not sufficient. It produces a [Configuration] that
 * says `fa` and `SCREENLAYOUT_LAYOUTDIR_RTL`, which is why the *text* came out
 * Persian in every build - but the layout direction Compose actually lays out
 * with does not come from that Configuration. `AndroidComposeView` overrides
 * `onRtlPropertiesChanged`, so the direction is whatever the VIEW hierarchy
 * resolves, and the decor view at the top of that hierarchy resolves
 * `LAYOUT_DIRECTION_LOCALE` against `Locale.getDefault()`.
 *
 * `Locale.getDefault()` is not ours to keep. The framework rewrites it from the
 * *activity's* configuration - `LocaleList.setDefault(config.getLocales())` -
 * on bind, on resume and on every configuration change, all of which happen
 * AFTER `attachBaseContext` has run. On a Persian phone the framework's own
 * locale is already Persian, so the value it writes back happens to be the one
 * we wanted and Persian mirrored correctly. On an English phone it writes `en`
 * back over our `fa`, the decor view resolves LTR, `onRtlPropertiesChanged`
 * hands that to Compose, and the whole app lays out left-to-right while reading
 * in Persian. Exactly the reported bug, and exactly why it only showed up in
 * that one combination.
 *
 * The fix is to stop inferring the direction at all: [isRtl] derives it from the
 * user's stored CHOICE, [layoutDirection] hands that to the window so the view
 * system agrees, and `AetherTheme` pins `LocalLayoutDirection` so Compose is
 * never at the mercy of the resolution above. Same answer on every phone, in
 * every system language, on every API level.
 */
object LanguagePrefs {

    private const val FILE = "aether_locale"
    private const val KEY = "app_language"

    fun read(context: Context): AppLanguage =
        AppLanguage.fromTag(
            runCatching {
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "")
            }.getOrNull(),
        )

    /**
     * Persists the choice. Written with `commit()` on purpose: the caller
     * recreates the activity immediately afterwards, and an `apply()` still
     * sitting in the background queue would let the new activity read the OLD
     * value inside `attachBaseContext` and come back up in the previous
     * language.
     */
    fun write(context: Context, language: AppLanguage) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, language.tag)
                .commit()
        }
        applyToPlatform(context, language)
    }

    /**
     * The locale the app is actually rendering in.
     *
     * For [AppLanguage.SYSTEM] that is the phone's first locale; otherwise it is
     * the chosen one, whatever the phone says. This is the single answer every
     * language-dependent decision in the app is derived from, so text, layout
     * direction and typeface can never disagree with each other again.
     */
    fun effectiveLocale(context: Context): Locale {
        val language = read(context)
        if (language != AppLanguage.SYSTEM) return Locale.forLanguageTag(language.tag)
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty()) Locale.getDefault() else locales[0]
    }

    /**
     * True when the UI must be mirrored.
     *
     * Derived from [effectiveLocale] and NOT from the configuration, the view
     * tree or `Locale.getDefault()` - see the class KDoc for why every one of
     * those lies in at least one language/phone combination.
     */
    fun isRtl(context: Context): Boolean =
        TextUtils.getLayoutDirectionFromLocale(effectiveLocale(context)) ==
            View.LAYOUT_DIRECTION_RTL

    /** [View.LAYOUT_DIRECTION_RTL] or [View.LAYOUT_DIRECTION_LTR] for [isRtl]. */
    fun layoutDirection(context: Context): Int =
        if (isRtl(context)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    /** True when the app is rendering Persian, which is what selects the Vazirmatn face. */
    fun isPersian(context: Context): Boolean = effectiveLocale(context).language == "fa"

    /**
     * Forces the window's own layout direction to match the chosen language.
     *
     * The decor view sits above every Compose view in the hierarchy, so pinning it
     * here means `onRtlPropertiesChanged` delivers the RIGHT value instead of the
     * one resolved from a `Locale.getDefault()` the framework keeps overwriting.
     * It also mirrors everything Compose does not own: dialog windows, popup
     * anchoring, the text selection handles and the overflow of any platform view.
     *
     * Call after `super.onCreate` and again from `onConfigurationChanged`.
     */
    fun applyLayoutDirection(activity: Activity) {
        val direction = layoutDirection(activity)
        // Keep java.text, String.format and our own log timestamps in step: the
        // framework may have reset the default locale since attachBaseContext.
        Locale.setDefault(effectiveLocale(activity))
        runCatching {
            activity.window?.decorView?.let { decor ->
                if (decor.layoutDirection != direction) decor.layoutDirection = direction
            }
        }
    }

    /**
     * Walks out of any [ContextWrapper] to the hosting [Activity].
     *
     * `LocalContext.current` is not guaranteed to BE the activity - and this whole
     * file exists because the app wraps its contexts - so a bare
     * `context as? Activity` can silently return null and turn "apply the new
     * language" into a no-op.
     */
    fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** Returns [base] with the selected locale applied, or [base] unchanged for SYSTEM. */
    fun wrap(base: Context): Context {
        val language = read(base)
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        // Locale.setDefault matters for everything that formats text WITHOUT a
        // Context: java.text, platform messages, and our own log timestamps.
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        // Set explicitly instead of relying on a side effect of setLocales: the
        // layout direction is what mirrors every row of the settings screens.
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun applyToPlatform(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales =
                if (language == AppLanguage.SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(language.tag)
                }
        }
    }
}
