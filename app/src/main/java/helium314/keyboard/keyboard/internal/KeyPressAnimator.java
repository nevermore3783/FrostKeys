// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal;

import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.util.SparseArray;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps a 0..1 "how pressed is this key" value per key, so a press can shrink or brighten the key
 * over time instead of switching straight to the pressed look.
 * <p>
 * The value is sampled while drawing rather than driven by an {@link android.animation.Animator},
 * which means it lands on whatever refresh rate the view is being drawn at and stays smooth on
 * 120 Hz panels without doing extra work on 60 Hz ones.
 */
public final class KeyPressAnimator {

    /** easing options offered in the settings */
    public static final int EASING_LINEAR = 0;
    public static final int EASING_SMOOTH = 1;
    public static final int EASING_OVERSHOOT = 2;

    public static final int HIGHLIGHT_AUTO = 0;
    public static final int HIGHLIGHT_LIGHTEN = 1;
    public static final int HIGHLIGHT_DARKEN = 2;

    private static final Interpolator LINEAR = input -> input;
    private static final Interpolator SMOOTH = new DecelerateInterpolator(1.6f);
    private static final Interpolator OVERSHOOT = new OvershootInterpolator(1.8f);
    private static final Interpolator SETTLE = new AccelerateDecelerateInterpolator();

    /** one key on its way to or from the pressed look */
    private static final class State {
        float from;
        float target;
        long startTime;
        int duration;
        float current;
    }

    private final Map<Key, State> mStates = new HashMap<>();
    // color filters are rebuilt constantly otherwise, and there are only 256 useful ones
    private final SparseArray<ColorFilter> mLightenFilters = new SparseArray<>();
    private final SparseArray<ColorFilter> mDarkenFilters = new SparseArray<>();
    private boolean mHasRunningAnimation;

    public void onPressed(@NonNull final Key key) {
        start(key, 1f, Settings.getValues().mKeyPressAnimDuration);
    }

    public void onReleased(@NonNull final Key key) {
        start(key, 0f, Settings.getValues().mKeyReleaseAnimDuration);
    }

    /** Drops a key straight back to its resting look, without the release animation. */
    public void reset(@NonNull final Key key) {
        mStates.remove(key);
    }

    public void clear() {
        mStates.clear();
        mHasRunningAnimation = false;
    }

    private void start(final Key key, final float target, final int duration) {
        if (!isEnabled()) {
            mStates.remove(key);
            return;
        }
        State state = mStates.get(key);
        if (state == null) {
            state = new State();
            mStates.put(key, state);
        }
        state.from = state.current;
        state.target = target;
        state.duration = Math.max(0, duration);
        state.startTime = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        if (state.duration == 0) {
            state.current = target;
        }
    }

    /** whether any of the effects driven by this value is switched on */
    public boolean isEnabled() {
        final SettingsValues sv = Settings.getValues();
        return sv != null && (sv.mKeyPressScale != 100 || sv.mKeyPressLabelScale != 100
                || sv.mSpacePressScale != 100 || sv.mSpacePressLabelScale != 100
                || sv.mKeyPressHighlight > 0);
    }

    /**
     * How pressed {@code key} is right now, between 0 and 1. Also records whether anything is
     * still moving, see {@link #hasRunningAnimation()}.
     */
    public float progressOf(@Nullable final Key key) {
        if (key == null) return 0f;
        final State state = mStates.get(key);
        if (state == null) return 0f;
        if (state.duration <= 0) {
            state.current = state.target;
        } else {
            final long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            final float elapsed = (now - state.startTime) / (float) state.duration;
            if (elapsed >= 1f) {
                state.current = state.target;
            } else {
                final float eased = interpolator(state.target).getInterpolation(Math.max(0f, elapsed));
                state.current = state.from + (state.target - state.from) * eased;
                mHasRunningAnimation = true;
            }
        }
        if (state.current == 0f && state.target == 0f) {
            mStates.remove(key);
            return 0f;
        }
        return state.current;
    }

    private static Interpolator interpolator(final float target) {
        // an overshoot on the way back is the part that reads as springy, on the way in it would
        // push the key past its normal size before shrinking
        final int easing = Settings.getValues().mKeyPressEasing;
        if (easing == EASING_OVERSHOOT) return target == 0f ? OVERSHOOT : SETTLE;
        if (easing == EASING_LINEAR) return LINEAR;
        return SMOOTH;
    }

    /** True while a key still has to be redrawn to finish its animation. */
    public boolean hasRunningAnimation() {
        return mHasRunningAnimation;
    }

    public void clearRunningAnimationFlag() {
        mHasRunningAnimation = false;
    }

    /** The keys that still have to be redrawn for their animation to finish. */
    public void collectAnimatingKeys(@NonNull final java.util.Collection<Key> out) {
        for (final Map.Entry<Key, State> entry : mStates.entrySet()) {
            final State state = entry.getValue();
            if (state.current != state.target) {
                out.add(entry.getKey());
            }
        }
    }

    /**
     * How much the key itself shrinks, 1 when the key keeps its size. The spacebar has its own
     * value, since shrinking it by the same share as a letter key moves its edges much further.
     */
    public static float keyScale(final float progress, final boolean isSpaceBar) {
        final SettingsValues sv = Settings.getValues();
        final int scale = isSpaceBar ? sv.mSpacePressScale : sv.mKeyPressScale;
        if (scale == 100 || progress <= 0f) return 1f;
        return 1f + (scale / 100f - 1f) * progress;
    }

    /** How much the label, icon and hint shrink, 1 when they keep their size. */
    public static float labelScale(final float progress, final boolean isSpaceBar) {
        final SettingsValues sv = Settings.getValues();
        final int scale = isSpaceBar ? sv.mSpacePressLabelScale : sv.mKeyPressLabelScale;
        if (scale == 100 || progress <= 0f) return 1f;
        return 1f + (scale / 100f - 1f) * progress;
    }

    /**
     * The filter that lifts (or drops) the brightness of the key background while it is held.
     * Applied to the background drawable itself, so it follows the key's shape and shrinks along
     * with it instead of painting over the space the key used to take up.
     */
    @Nullable
    public ColorFilter highlightFilter(final float progress, final boolean isNightTheme) {
        final SettingsValues sv = Settings.getValues();
        if (sv.mKeyPressHighlight <= 0 || progress <= 0f) return null;
        final boolean lighten = switch (sv.mKeyPressHighlightMode) {
            case HIGHLIGHT_LIGHTEN -> true;
            case HIGHLIGHT_DARKEN -> false;
            default -> isNightTheme;
        };
        final float amount = sv.mKeyPressHighlight / 100f * Math.min(1f, progress);
        if (lighten) {
            final int add = Math.round(amount * 150f);
            if (add <= 0) return null;
            ColorFilter filter = mLightenFilters.get(add);
            if (filter == null) {
                filter = new LightingColorFilter(0xFFFFFFFF, (add << 16) | (add << 8) | add);
                mLightenFilters.put(add, filter);
            }
            return filter;
        }
        final int mul = Math.round((1f - amount * 0.6f) * 255f);
        ColorFilter filter = mDarkenFilters.get(mul);
        if (filter == null) {
            final int m = (mul << 16) | (mul << 8) | mul;
            filter = new LightingColorFilter(0xFF000000 | m, 0);
            mDarkenFilters.put(mul, filter);
        }
        return filter;
    }
}
