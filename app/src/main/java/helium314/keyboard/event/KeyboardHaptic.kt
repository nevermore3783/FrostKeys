// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.event

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * One entry of the keyboard vibration picker.
 *
 * Everything offered here is a haptic that the framework or the vibrator hardware already knows how
 * to render, so the actuator plays a waveform someone tuned instead of the raw "run the motor for n
 * milliseconds" buzz that a duration slider produces.
 */
sealed class KeyboardHaptic(
    @JvmField val id: String,
    @StringRes protected val nameRes: Int,
    @StringRes @JvmField val categoryRes: Int,
    private val minSdk: Int
) {
    open fun getName(context: Context): String = context.getString(nameRes)

    /** whether this haptic can produce anything on the current device */
    open fun isSupported(vibrator: Vibrator?): Boolean =
        Build.VERSION.SDK_INT >= minSdk && vibrator != null && vibrator.hasVibrator()

    /** plays the haptic, returns whether anything was played */
    abstract fun play(view: View?, vibrator: Vibrator?, event: HapticEvent): Boolean
}

/** Whatever Android plays for the event in question, i.e. what the keyboard did before. */
object SystemDefaultHaptic : KeyboardHaptic(
    "system_default", R.string.settings_system_default, R.string.haptic_category_recommended, 0
) {
    override fun play(view: View?, vibrator: Vibrator?, event: HapticEvent) =
        performConstant(event.feedbackConstant, view, vibrator)
}

/**
 * Reconstruction of the short, crisp tap Gboard plays on its default setting.
 *
 * Gboard asks the framework for a keyboard tap, and on hardware with a tuned haptic HAL (Pixel and
 * similar) that ends up as a single click primitive played at a fraction of full strength. Many
 * OEMs instead map the keyboard tap constant onto a plain fixed-length buzz, which is what makes
 * the same "system default" setting feel like a motor spinning up on those devices. So rather than
 * going through the constant, ask for the click primitive directly and only fall back to the
 * framework mapping where primitives do not exist.
 */
object CrispTapHaptic : KeyboardHaptic(
    "crisp_tap", R.string.haptic_crisp_tap, R.string.haptic_category_recommended, 0
) {
    private const val PRESS_SCALE = 0.35f
    private const val LONG_PRESS_SCALE = 0.7f

    override fun play(view: View?, vibrator: Vibrator?, event: HapticEvent): Boolean {
        val longPress = event == HapticEvent.KEY_LONG_PRESS
        val scale = if (longPress) LONG_PRESS_SCALE else PRESS_SCALE
        if (playPrimitive(vibrator, VibrationEffect.Composition.PRIMITIVE_CLICK, scale)) return true
        val effect = if (longPress) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null
                && playEffect(vibrator, VibrationEffect.createPredefined(effect)))
            return true
        return performConstant(event.feedbackConstant, view, vibrator)
    }
}

/** One of the haptics the framework defines for a UI event, see [HapticFeedbackConstants]. */
class SystemConstantHaptic(
    id: String,
    @StringRes nameRes: Int,
    minSdk: Int,
    private val constant: Int
) : KeyboardHaptic(id, nameRes, R.string.haptic_category_system, minSdk) {
    override fun play(view: View?, vibrator: Vibrator?, event: HapticEvent) =
        performConstant(constant, view, vibrator)
}

/**
 * One of the device-tuned effects of [VibrationEffect]. The framework provides a fallback for
 * these, so they always play something.
 */
class PredefinedEffectHaptic(
    id: String,
    @StringRes nameRes: Int,
    private val effectId: Int
) : KeyboardHaptic(id, nameRes, R.string.haptic_category_effect, Build.VERSION_CODES.Q) {
    override fun play(view: View?, vibrator: Vibrator?, event: HapticEvent): Boolean {
        if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return performConstant(event.feedbackConstant, view, vibrator)
        return playEffect(vibrator, VibrationEffect.createPredefined(effectId))
    }
}

/**
 * A single [VibrationEffect.Composition] primitive at a fixed strength. These are the building
 * blocks the haptic HAL exposes, and the only way to get a tap that is shorter and lighter than
 * anything the framework constants offer.
 */
class PrimitiveHaptic(
    id: String,
    @StringRes nameRes: Int,
    @StringRes private val strengthRes: Int,
    minSdk: Int,
    private val primitive: Int,
    private val scale: Float
) : KeyboardHaptic(id, nameRes, R.string.haptic_category_primitive, minSdk) {

    override fun getName(context: Context): String = context.getString(
        R.string.haptic_name_with_strength, context.getString(nameRes), context.getString(strengthRes)
    )

    override fun isSupported(vibrator: Vibrator?) =
        super.isSupported(vibrator) && isPrimitiveSupported(vibrator, primitive)

    override fun play(view: View?, vibrator: Vibrator?, event: HapticEvent) =
        // the primitive can be missing when the setting comes from a backup of another device
        playPrimitive(vibrator, primitive, scale) || performConstant(event.feedbackConstant, view, vibrator)
}

object KeyboardHaptics {
    /** what the keyboard plays for events the user cannot pick a haptic for */
    @JvmField
    val SYSTEM_DEFAULT: KeyboardHaptic = SystemDefaultHaptic

    /** everything the picker knows about, in the order it is shown */
    @JvmField
    val ALL: List<KeyboardHaptic> = buildList {
        add(CrispTapHaptic)
        add(SystemDefaultHaptic)

        add(SystemConstantHaptic("keyboard_tap", R.string.haptic_keyboard_tap, 0, HapticFeedbackConstants.KEYBOARD_TAP))
        add(SystemConstantHaptic("keyboard_release", R.string.haptic_keyboard_release, 27, HapticFeedbackConstants.KEYBOARD_RELEASE))
        add(SystemConstantHaptic("virtual_key", R.string.haptic_virtual_key, 0, HapticFeedbackConstants.VIRTUAL_KEY))
        add(SystemConstantHaptic("virtual_key_release", R.string.haptic_virtual_key_release, 27, HapticFeedbackConstants.VIRTUAL_KEY_RELEASE))
        add(SystemConstantHaptic("clock_tick", R.string.haptic_clock_tick, 0, HapticFeedbackConstants.CLOCK_TICK))
        add(SystemConstantHaptic("context_click", R.string.haptic_context_click, 0, HapticFeedbackConstants.CONTEXT_CLICK))
        add(SystemConstantHaptic("text_handle_move", R.string.haptic_text_handle_move, 27, HapticFeedbackConstants.TEXT_HANDLE_MOVE))
        add(SystemConstantHaptic("long_press", R.string.haptic_long_press, 0, HapticFeedbackConstants.LONG_PRESS))
        add(SystemConstantHaptic("gesture_start", R.string.haptic_gesture_start, 30, HapticFeedbackConstants.GESTURE_START))
        add(SystemConstantHaptic("gesture_end", R.string.haptic_gesture_end, 30, HapticFeedbackConstants.GESTURE_END))
        add(SystemConstantHaptic("confirm", R.string.haptic_confirm, 30, HapticFeedbackConstants.CONFIRM))
        add(SystemConstantHaptic("reject", R.string.haptic_reject, 30, HapticFeedbackConstants.REJECT))
        add(SystemConstantHaptic("toggle_on", R.string.haptic_toggle_on, 34, HapticFeedbackConstants.TOGGLE_ON))
        add(SystemConstantHaptic("toggle_off", R.string.haptic_toggle_off, 34, HapticFeedbackConstants.TOGGLE_OFF))
        add(SystemConstantHaptic("drag_start", R.string.haptic_drag_start, 34, HapticFeedbackConstants.DRAG_START))
        add(SystemConstantHaptic("gesture_threshold_activate", R.string.haptic_gesture_threshold_activate, 34, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE))
        add(SystemConstantHaptic("gesture_threshold_deactivate", R.string.haptic_gesture_threshold_deactivate, 34, HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE))
        add(SystemConstantHaptic("segment_tick", R.string.haptic_segment_tick, 34, HapticFeedbackConstants.SEGMENT_TICK))
        add(SystemConstantHaptic("segment_frequent_tick", R.string.haptic_segment_frequent_tick, 34, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK))

        add(PredefinedEffectHaptic("effect_tick", R.string.haptic_effect_tick, VibrationEffect.EFFECT_TICK))
        add(PredefinedEffectHaptic("effect_click", R.string.haptic_effect_click, VibrationEffect.EFFECT_CLICK))
        add(PredefinedEffectHaptic("effect_heavy_click", R.string.haptic_effect_heavy_click, VibrationEffect.EFFECT_HEAVY_CLICK))
        add(PredefinedEffectHaptic("effect_double_click", R.string.haptic_effect_double_click, VibrationEffect.EFFECT_DOUBLE_CLICK))

        addPrimitives("click", R.string.haptic_primitive_click, 30, VibrationEffect.Composition.PRIMITIVE_CLICK)
        addPrimitives("tick", R.string.haptic_primitive_tick, 30, VibrationEffect.Composition.PRIMITIVE_TICK)
        addPrimitives("low_tick", R.string.haptic_primitive_low_tick, 31, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
        addPrimitives("thud", R.string.haptic_primitive_thud, 31, VibrationEffect.Composition.PRIMITIVE_THUD)
        addPrimitives("spin", R.string.haptic_primitive_spin, 31, VibrationEffect.Composition.PRIMITIVE_SPIN)
        addPrimitives("quick_rise", R.string.haptic_primitive_quick_rise, 30, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
        addPrimitives("slow_rise", R.string.haptic_primitive_slow_rise, 30, VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
        addPrimitives("quick_fall", R.string.haptic_primitive_quick_fall, 30, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
    }

    private fun MutableList<KeyboardHaptic>.addPrimitives(
        name: String, @StringRes nameRes: Int, minSdk: Int, primitive: Int
    ) {
        add(PrimitiveHaptic("primitive_${name}_light", nameRes, R.string.haptic_strength_light, minSdk, primitive, 0.2f))
        add(PrimitiveHaptic("primitive_${name}_medium", nameRes, R.string.haptic_strength_medium, minSdk, primitive, 0.5f))
        add(PrimitiveHaptic("primitive_${name}_strong", nameRes, R.string.haptic_strength_strong, minSdk, primitive, 1f))
    }

    @JvmStatic
    fun byId(id: String?): KeyboardHaptic = ALL.firstOrNull { it.id == id } ?: CrispTapHaptic

    @JvmStatic
    fun fromPrefs(prefs: SharedPreferences): KeyboardHaptic =
        byId(prefs.getString(Settings.PREF_KEYPRESS_HAPTIC, Defaults.PREF_KEYPRESS_HAPTIC))

    /** the entries that make sense to offer on this device */
    fun supported(vibrator: Vibrator?): List<KeyboardHaptic> = ALL.filter { it.isSupported(vibrator) }
}

private const val LEGACY_FALLBACK_DURATION = 20L

private val touchAudioAttributes by lazy {
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

private val touchVibrationAttributes by lazy {
    VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
}

/**
 * Asks the view hierarchy for [constant]. If that does not produce anything, e.g. because the view
 * is not attached or because the system ignores [HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING]
 * (it does from Android 13 on), play the effect the framework would have picked ourselves.
 */
@Suppress("DEPRECATION") // FLAG_IGNORE_GLOBAL_SETTING, still honored on older versions
private fun performConstant(constant: Int, view: View?, vibrator: Vibrator?): Boolean {
    if (view != null
            && view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING))
        return true
    if (vibrator == null) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return playLegacy(vibrator)
    return playEffect(vibrator, VibrationEffect.createPredefined(frameworkEffectFor(constant)))
}

/** the predefined effect AOSP maps [constant] to, used when we have to play it ourselves */
private fun frameworkEffectFor(constant: Int): Int = when (constant) {
    HapticFeedbackConstants.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
    HapticFeedbackConstants.REJECT -> VibrationEffect.EFFECT_DOUBLE_CLICK
    HapticFeedbackConstants.KEYBOARD_TAP,
    HapticFeedbackConstants.VIRTUAL_KEY,
    HapticFeedbackConstants.CONFIRM,
    HapticFeedbackConstants.TOGGLE_ON,
    HapticFeedbackConstants.DRAG_START,
    HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE -> VibrationEffect.EFFECT_CLICK
    else -> VibrationEffect.EFFECT_TICK
}

private fun playPrimitive(vibrator: Vibrator?, primitive: Int, scale: Float): Boolean {
    if (vibrator == null || !isPrimitiveSupported(vibrator, primitive)) return false
    return try {
        playEffect(vibrator, VibrationEffect.startComposition().addPrimitive(primitive, scale).compose())
    } catch (e: Exception) {
        false
    }
}

private fun isPrimitiveSupported(vibrator: Vibrator?, primitive: Int): Boolean {
    if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return try {
        vibrator.arePrimitivesSupported(primitive).firstOrNull() == true
    } catch (e: Exception) {
        false
    }
}

@Suppress("DEPRECATION") // vibrate(effect, audioAttributes), the replacement needs Android 13
private fun playEffect(vibrator: Vibrator, effect: VibrationEffect): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        vibrator.vibrate(effect, touchVibrationAttributes)
    else
        vibrator.vibrate(effect, touchAudioAttributes)
    true
} catch (e: Exception) {
    false
}

/** last resort for devices without any predefined effects (below Android 10) */
@Suppress("DEPRECATION")
private fun playLegacy(vibrator: Vibrator): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        vibrator.vibrate(
            VibrationEffect.createOneShot(LEGACY_FALLBACK_DURATION, VibrationEffect.DEFAULT_AMPLITUDE),
            touchAudioAttributes
        )
    else
        vibrator.vibrate(LEGACY_FALLBACK_DURATION)
    true
} catch (e: Exception) {
    false
}
