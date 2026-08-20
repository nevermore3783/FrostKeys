// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.utils.prefs
import java.lang.ref.WeakReference

/**
 * The static "liquid glass" edge treatment drawn on top of the keyboard frame.
 *
 * Apple's Liquid Glass is a stack of optical layers rather than a single effect: the material
 * bends what is behind it near its edges (lensing), catches a specular highlight whose brightness
 * follows the angle between the surface normal and a fixed light, is seated by a shadow, and
 * carries an adaptive tint. This reproduces those layers statically — nothing here reacts to
 * device motion — and every layer is optional, so the effect can be dialled from "barely there"
 * to "obvious glass slab".
 *
 * All of this is stored under its own preference keys and is off by default, so it neither reads
 * nor writes anything the existing frosted glass settings use.
 */
object LiquidGlass {
    const val PREF_ENABLED = "pref_liquid_glass_enabled"
    /** every key of this effect starts with this, which is how live redraws are triggered */
    const val PREF_PREFIX = "pref_liquid_"

    const val BLEND_NORMAL = 0
    const val BLEND_SCREEN = 1
    const val BLEND_PLUS = 2

    /** one adjustable value of the effect, all of them are ints so they store and slide the same */
    enum class Param(
        val key: String,
        val default: Int,
        val darkDefault: Int,
        val min: Int,
        val max: Int,
        val isColor: Boolean = false
    ) {
        RIM_INTENSITY("rim_intensity", 58, 78, 0, 100),
        RIM_THICKNESS("rim_thickness", 14, 14, 2, 80),
        RIM_SOFTNESS("rim_softness", 35, 45, 0, 100),
        LIGHT_ANGLE("light_angle", 0, 0, -180, 180),
        RIM_SPREAD("rim_spread", 75, 75, 0, 100),
        RIM_COLOR("rim_color", Color.WHITE, Color.WHITE, 0, 0, isColor = true),
        COUNTER_RIM("counter_rim", 20, 26, 0, 100),
        SIDE_FALLOFF("side_falloff", 45, 45, 0, 100),
        LENS_INTENSITY("lens_intensity", 30, 38, 0, 100),
        LENS_DEPTH("lens_depth", 30, 30, 0, 100),
        EDGE_SHADOW("edge_shadow", 38, 28, 0, 100),
        SHEEN_INTENSITY("sheen_intensity", 0, 0, 0, 100),
        SHEEN_HEIGHT("sheen_height", 40, 40, 5, 100),
        SHEEN_ANGLE("sheen_angle", 18, 18, -90, 90),
        TINT_COLOR("tint_color", Color.WHITE, Color.WHITE, 0, 0, isColor = true),
        TINT_OPACITY("tint_opacity", 0, 0, 0, 100),
        BLEND_MODE("blend_mode", BLEND_SCREEN, BLEND_SCREEN, 0, 2);

        fun prefKey(night: Boolean) = PREF_PREFIX + key + if (night) "_night" else ""

        fun defaultValue(night: Boolean) = if (night) darkDefault else default
    }

    /** a full set of values, one for the light theme and one for the dark theme */
    data class Profile(val values: Map<Param, Int>) {
        operator fun get(param: Param): Int = values[param] ?: param.default

        fun fraction(param: Param): Float = get(param) / 100f

        fun with(param: Param, value: Int) = Profile(values + (param to value))
    }

    /** set while the adjust dialog is open, so the keyboard shows unsaved values */
    @JvmStatic
    var livePreview: Profile? = null
        set(value) {
            field = value
            invalidateCache()
        }

    private val listeners = mutableListOf<WeakReference<Runnable>>()

    // The keyboard asks for these on every frame, so they are not re-read from the preferences
    // until something actually changes them.
    private var cachedProfile: Profile? = null
    private var cachedNight = false
    private var cachedEnabled: Boolean? = null

    fun invalidateCache() {
        cachedProfile = null
        cachedEnabled = null
    }

    fun defaultProfile(night: Boolean) =
        Profile(Param.entries.associateWith { it.defaultValue(night) })

    fun isEnabled(context: Context): Boolean {
        if (livePreview != null) return true
        return cachedEnabled ?: context.prefs().getBoolean(PREF_ENABLED, false).also { cachedEnabled = it }
    }

    fun readProfile(prefs: SharedPreferences, night: Boolean): Profile =
        Profile(Param.entries.associateWith { prefs.getInt(it.prefKey(night), it.defaultValue(night)) })

    fun writeProfile(prefs: SharedPreferences, night: Boolean, profile: Profile) {
        prefs.edit(commit = true) {
            Param.entries.forEach { putInt(it.prefKey(night), profile[it]) }
        }
        invalidateCache()
    }

    /** the values the keyboard should draw with right now */
    fun activeProfile(context: Context): Profile {
        livePreview?.let { return it }
        val night = KeyboardTheme.isDarkThemeActive(context)
        cachedProfile?.let { if (night == cachedNight) return it }
        return readProfile(context.prefs(), night).also {
            cachedProfile = it
            cachedNight = night
        }
    }

    /** Views that should repaint when the effect changes. Held weakly, so they can just register. */
    fun addListener(listener: Runnable) {
        synchronized(listeners) {
            listeners.removeAll { it.get() == null }
            listeners.add(WeakReference(listener))
        }
    }

    fun removeListener(listener: Runnable) {
        synchronized(listeners) {
            listeners.removeAll { it.get() == null || it.get() === listener }
        }
    }

    fun notifyChanged() {
        invalidateCache()
        val current = synchronized(listeners) { listeners.mapNotNull { it.get() } }
        current.forEach { it.run() }
    }
}
