/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.accessibility.PopupKeysKeyboardAccessibilityDelegate;
import helium314.keyboard.keyboard.emoji.EmojiViewCallback;
import helium314.keyboard.keyboard.internal.KeyDrawParams;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.common.CoordinateUtils;

/**
 * A view that renders a virtual {@link PopupKeysKeyboard}. It handles rendering of keys and
 * detecting key presses and touch movements.
 */
public class PopupKeysKeyboardView extends KeyboardView implements PopupKeysPanel {
    private final int[] mCoordinates = CoordinateUtils.newInstance();

    private final Drawable mDivider;
    protected final KeyDetector mKeyDetector;
    private Controller mController = EMPTY_CONTROLLER;
    protected KeyboardActionListener mListener;
    protected EmojiViewCallback mEmojiViewCallback;
    private int mOriginX;
    private int mOriginY;
    private Key mCurrentKey;

    private int mActivePointerId;

    protected PopupKeysKeyboardAccessibilityDelegate mAccessibilityDelegate;

    public PopupKeysKeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.popupKeysKeyboardViewStyle);
    }

    public PopupKeysKeyboardView(final Context context, final AttributeSet attrs,
                                 final int defStyle) {
        super(context, attrs, defStyle);
        final TypedArray popupKeysKeyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.PopupKeysKeyboardView, defStyle, R.style.PopupKeysKeyboardView);
        mDivider = popupKeysKeyboardViewAttr.getDrawable(R.styleable.PopupKeysKeyboardView_divider);
        popupKeysKeyboardViewAttr.recycle();
        mKeyDetector = new PopupKeysDetector(getResources().getDimension(
                R.dimen.config_popup_keys_keyboard_slide_allowance));
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
            final int height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(width, height);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onDrawKeyTopVisuals(@NonNull final Key key, @NonNull final Canvas canvas,
            @NonNull final Paint paint, @NonNull final KeyDrawParams params) {
        if (!key.isSpacer() || !(key instanceof PopupKeysKeyboard.PopupKeyDivider)
                || mDivider == null) {
            super.onDrawKeyTopVisuals(key, canvas, paint, params);
            return;
        }
        final int keyWidth = key.getDrawWidth();
        final int keyHeight = key.getHeight();
        final int iconWidth = Math.min(mDivider.getIntrinsicWidth(), keyWidth);
        final int iconHeight = mDivider.getIntrinsicHeight();
        final int iconX = (keyWidth - iconWidth) / 2; // Align horizontally center
        final int iconY = (keyHeight - iconHeight) / 2; // Align vertically center
        drawIcon(canvas, mDivider, iconX, iconY, iconWidth, iconHeight);
    }

    /** what is behind the panel, blurred, so the panel reads as glass rather than as a tinted box */
    @Nullable
    private android.graphics.Bitmap mBackdrop;
    private final android.graphics.Paint mBackdropPaint =
            new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.RectF mBackdropRect = new android.graphics.RectF();
    private final android.graphics.Path mBackdropClip = new android.graphics.Path();
    /** the background instance we own a private copy of, so we never dim a shared drawable */
    @Nullable
    private android.graphics.drawable.Drawable mMutatedBackground;

    /**
     * Thins out the panel's own fill so the blur the keyboard window already puts behind itself
     * shows through. Without this the panel stays an opaque slab and the captured backdrop below
     * it is invisible, which reads as a flat colour with a faint edge rather than as glass.
     */
    private void applyPanelOpacity() {
        final SettingsValues sv = Settings.getValues();
        android.graphics.drawable.Drawable background = getBackground();
        if (background == null || sv == null) {
            return;
        }
        if (background != mMutatedBackground) {
            // the theme hands the same drawable state to several views, so take a private copy
            // before changing its alpha
            background = background.mutate();
            setBackground(background);
            mMutatedBackground = background;
        }
        // without a captured backdrop there is nothing behind the panel except the app under the
        // keyboard window, so thinning the fill would show that through - which on a light app
        // turns the panel white instead of leaving it the colour the theme picked
        final int alpha = sv.mPopupKeysBlur && mBackdrop != null
                ? Math.round(sv.mPopupKeysPanelOpacity / 100f * 255f) : 255;
        if (background.getAlpha() != alpha) {
            // guarded, since setting it unconditionally invalidates the drawable every frame
            background.setAlpha(alpha);
        }
    }

    /** waits for the panel to be laid out before grabbing what is behind it */
    private final android.view.ViewTreeObserver.OnPreDrawListener mBackdropCaptureListener =
            new android.view.ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (getWidth() <= 0 || getHeight() <= 0) {
                return true; // not laid out yet, try again on the next frame
            }
            removeBackdropCapture();
            captureBackdrop();
            return true;
        }
    };
    private boolean mBackdropCaptureScheduled;

    private void scheduleBackdropCapture() {
        final SettingsValues sv = Settings.getValues();
        if (sv == null || !sv.mPopupKeysBlur || sv.mPopupKeysBlurRadius <= 0) {
            return;
        }
        if (mBackdropCaptureScheduled) {
            return;
        }
        mBackdropCaptureScheduled = true;
        getViewTreeObserver().addOnPreDrawListener(mBackdropCaptureListener);
    }

    private void removeBackdropCapture() {
        if (!mBackdropCaptureScheduled) {
            return;
        }
        mBackdropCaptureScheduled = false;
        final android.view.ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(mBackdropCaptureListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeBackdropCapture();
        super.onDetachedFromWindow();
    }

    @Override
    public void draw(@NonNull final android.graphics.Canvas canvas) {
        // re-checked here rather than only when the panel opens, because the theme can hand the
        // view a freshly coloured background after that point, and thinning the previous one
        // would leave the new one at full strength in whatever colour it defaulted to
        applyPanelOpacity();
        drawBackdrop(canvas);
        super.draw(canvas);
    }

    private void drawBackdrop(@NonNull final android.graphics.Canvas canvas) {
        final android.graphics.Bitmap backdrop = mBackdrop;
        if (backdrop == null || backdrop.isRecycled()) {
            return;
        }
        mBackdropRect.set(0f, 0f, getWidth(), getHeight());
        final float radius = backgroundCornerRadius();
        final int saveCount = canvas.save();
        if (radius > 0f) {
            mBackdropClip.reset();
            mBackdropClip.addRoundRect(mBackdropRect, radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(mBackdropClip);
        }
        canvas.drawBitmap(backdrop, null, mBackdropRect, mBackdropPaint);
        canvas.restoreToCount(saveCount);
    }

    /** the radius the panel's own background is drawn with, so the backdrop does not show corners */
    private float backgroundCornerRadius() {
        final android.graphics.drawable.Drawable background = getBackground();
        if (background == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return 0f;
        }
        try {
            final android.graphics.Outline outline = new android.graphics.Outline();
            background.setBounds(0, 0, getWidth(), getHeight());
            background.getOutline(outline);
            final float radius = outline.getRadius();
            return radius > 0f ? radius : 0f;
        } catch (Exception e) {
            return 0f;
        }
    }

    /**
     * Grabs the keyboard underneath and blurs it. Done once when the panel appears rather than
     * every frame, since neither the panel nor what is under it moves while it is open.
     */
    private void captureBackdrop() {
        mBackdrop = null;
        final SettingsValues sv = Settings.getValues();
        if (sv == null || !sv.mPopupKeysBlur || sv.mPopupKeysBlurRadius <= 0) {
            return;
        }
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        final MainKeyboardView source = KeyboardSwitcher.getInstance().getMainKeyboardView();
        if (source == null || source.getWidth() <= 0) {
            return;
        }
        try {
            final int[] here = new int[2];
            final int[] there = new int[2];
            getLocationInWindow(here);
            source.getLocationInWindow(there);
            // captured small on purpose, the downscale is where most of the blur comes from
            final int sample = 6;
            final android.graphics.Bitmap shot = android.graphics.Bitmap.createBitmap(
                    Math.max(1, width / sample), Math.max(1, height / sample),
                    android.graphics.Bitmap.Config.ARGB_8888);
            final android.graphics.Canvas shotCanvas = new android.graphics.Canvas(shot);
            shotCanvas.scale(1f / sample, 1f / sample);
            shotCanvas.translate(there[0] - here[0], there[1] - here[1]);
            source.draw(shotCanvas);
            source.releaseSoftwareBuffer();
            mBackdrop = blur(shot, sv.mPopupKeysBlurRadius);
        } catch (Exception e) {
            mBackdrop = null;
        }
    }

    /** Repeated halving with bilinear filtering, which is a cheap and good enough box blur. */
    private static android.graphics.Bitmap blur(final android.graphics.Bitmap source, final int radius) {
        final int passes = Math.max(1, Math.min(4, radius / 20));
        android.graphics.Bitmap current = source;
        final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < passes; i++) {
            final int w = Math.max(1, current.getWidth() / 2);
            final int h = Math.max(1, current.getHeight() / 2);
            if (w <= 1 && h <= 1) break;
            final android.graphics.Bitmap smaller =
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(smaller)
                    .drawBitmap(current, null, new android.graphics.Rect(0, 0, w, h), paint);
            current = smaller;
        }
        return current;
    }

    @Override
    public void setKeyboard(@NonNull final Keyboard keyboard) {
        mBackdrop = null;
        super.setKeyboard(keyboard);
        mKeyDetector.setKeyboard(
                keyboard, -getPaddingLeft(), -getPaddingTop() + getVerticalCorrection());
        if (AccessibilityUtils.Companion.getInstance().isAccessibilityEnabled()) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = new PopupKeysKeyboardAccessibilityDelegate(
                        this, mKeyDetector);
                mAccessibilityDelegate.setOpenAnnounce(R.string.spoken_open_popup_keys_keyboard);
                mAccessibilityDelegate.setCloseAnnounce(R.string.spoken_close_popup_keys_keyboard);
            }
            mAccessibilityDelegate.setKeyboard(keyboard);
        } else {
            mAccessibilityDelegate = null;
        }
        final Key shortcutKey = keyboard.getKey(KeyCode.VOICE_INPUT);
        if (shortcutKey != null) {
            shortcutKey.setEnabled(RichInputMethodManager.getInstance().isShortcutImeReady());
            invalidateKey(shortcutKey);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void showPopupKeysPanel(final View parentView, final Controller controller,
            final int pointX, final int pointY, final KeyboardActionListener listener) {
        mListener = listener;
        mEmojiViewCallback = null;
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void showPopupKeysPanel(final View parentView, final Controller controller,
            final int pointX, final int pointY, final EmojiViewCallback emojiViewCallback) {
        mListener = null;
        mEmojiViewCallback = emojiViewCallback;
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY);
    }

    @SuppressLint("RtlHardcoded") // a key on the left is on the left, independent of layout direction
    private void showPopupKeysPanelInternal(final View parentView, final Controller controller,
            final int pointX, final int pointY) {
        mController = controller;
        mBackdrop = null;
        applyPanelOpacity();
        // grabbed once the panel has been laid out, since only then is it known what it covers.
        // A pre-draw listener rather than post(), because the first time the panel is shown it is
        // not attached yet, and a posted runnable then runs before the panel has a size at all -
        // which used to leave the first popup of a keyboard session without any backdrop.
        scheduleBackdropCapture();
        final View container = getContainerView();
        // The coordinates of panel's left-top corner in parentView's coordinate system.
        // We need to consider background drawable paddings.
        final int x = pointX - getDefaultCoordX() - container.getPaddingLeft() - getPaddingLeft();
        final int y = pointY - container.getMeasuredHeight() + container.getPaddingBottom()
                + getPaddingBottom();

        parentView.getLocationInWindow(mCoordinates);
        final int containerY = y + CoordinateUtils.y(mCoordinates);
        container.setY(containerY);

        // This is needed for cases where there's also a text popup above this keyboard
        final int panelMaxX = parentView.getMeasuredWidth() - getMeasuredWidth();
        var panelFinalX = Math.max(0, Math.min(panelMaxX, x));
        var center = panelFinalX + getMeasuredWidth() / 2;
        var layoutGravity = center < pointX - getKeyboard().mMostCommonKeyWidth / 2?
                        Gravity.RIGHT : center > pointX + getKeyboard().mMostCommonKeyWidth / 2? Gravity.LEFT : Gravity.CENTER_HORIZONTAL;

        int containerAdjustedX = x;
        if (getMeasuredWidth() < container.getMeasuredWidth()) {
            containerAdjustedX = layoutGravity == Gravity.LEFT? panelFinalX : layoutGravity == Gravity.RIGHT
                ? panelFinalX + getMeasuredWidth() - container.getMeasuredWidth()
                : panelFinalX + (getMeasuredWidth() - container.getMeasuredWidth()) / 2;
        }

        // Ensure the horizontal position of the panel does not extend past the parentView edges.
        int containerMaxX = parentView.getMeasuredWidth() - container.getMeasuredWidth();
        int containerFinalX = Math.max(0, Math.min(containerMaxX, containerAdjustedX));
        int containerX = containerFinalX + CoordinateUtils.x(mCoordinates);
        container.setX(containerX);
        setTranslationX(panelFinalX - containerFinalX);
        controller.setLayoutGravity(layoutGravity);

        mOriginX = panelFinalX;
        mOriginY = y + container.getPaddingTop() + (int) getY();
        controller.onShowPopupKeysPanel(this);
        final PopupKeysKeyboardAccessibilityDelegate accessibilityDelegate = mAccessibilityDelegate;
        if (accessibilityDelegate != null
                && AccessibilityUtils.Companion.getInstance().isAccessibilityEnabled()) {
            accessibilityDelegate.onShowPopupKeysKeyboard();
        }
    }

    /**
     * Returns the default x coordinate for showing this panel.
     */
    protected int getDefaultCoordX() {
        return ((PopupKeysKeyboard)getKeyboard()).getDefaultCoordX();
    }

    @Override
    public void onDownEvent(final int x, final int y, final int pointerId, final long eventTime) {
        mActivePointerId = pointerId;
        mCurrentKey = detectKey(x, y);
    }

    @Override
    public void onMoveEvent(final int x, final int y, final int pointerId, final long eventTime) {
        if (mActivePointerId != pointerId) {
            return;
        }
        final boolean hasOldKey = (mCurrentKey != null);
        mCurrentKey = detectKey(x, y);
        if (hasOldKey && mCurrentKey == null) {
            // A popup keys keyboard is canceled when detecting no key.
            mController.onCancelPopupKeysPanel();
        }
    }

    @Override
    public void onUpEvent(final int x, final int y, final int pointerId, final long eventTime) {
        if (mActivePointerId != pointerId) {
            return;
        }
        // Calling {@link #detectKey(int,int,int)} here is harmless because the last move event and
        // the following up event share the same coordinates.
        mCurrentKey = detectKey(x, y);
        if (mCurrentKey != null) {
            updateReleaseKeyGraphics(mCurrentKey);
            onKeyInput(mCurrentKey, x, y);
            mCurrentKey = null;
        }
    }

    /**
     * Performs the specific action for this panel when the user presses a key on the panel.
     */
    protected void onKeyInput(final Key key, final int x, final int y) {
        if (mListener != null) {
            final int code = key.getCode();
            if (code == KeyCode.MULTIPLE_CODE_POINTS) {
                mListener.onTextInput(mCurrentKey.getOutputText());
            } else if (code != KeyCode.NOT_SPECIFIED) {
                if (getKeyboard().hasProximityCharsCorrection(code)) {
                    mListener.onCodeInput(code, x, y, false /* isKeyRepeat */);
                } else {
                    mListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE,
                            false /* isKeyRepeat */);
                }
            }
        } else if (mEmojiViewCallback != null) {
            mEmojiViewCallback.onReleaseKey(key);
        }
    }

    private Key detectKey(int x, int y) {
        final Key oldKey = mCurrentKey;
        final Key newKey = mKeyDetector.detectHitKey(x, y);
        if (newKey == oldKey) {
            return newKey;
        }
        // A new key is detected.
        if (oldKey != null) {
            updateReleaseKeyGraphics(oldKey);
            invalidateKey(oldKey);
        }
        if (newKey != null) {
            updatePressKeyGraphics(newKey);
            invalidateKey(newKey);
        }
        return newKey;
    }

    private void updateReleaseKeyGraphics(final Key key) {
        key.onReleased();
        invalidateKey(key);
    }

    private void updatePressKeyGraphics(final Key key) {
        key.onPressed();
        invalidateKey(key);
    }

    @Override
    public void dismissPopupKeysPanel() {
        if (!isShowingInParent()) {
            return;
        }
        final PopupKeysKeyboardAccessibilityDelegate accessibilityDelegate = mAccessibilityDelegate;
        if (accessibilityDelegate != null
                && AccessibilityUtils.Companion.getInstance().isAccessibilityEnabled()) {
            accessibilityDelegate.onDismissPopupKeysKeyboard();
        }
        mController.onDismissPopupKeysPanel();
    }

    @Override
    public int translateX(final int x) {
        return x - mOriginX;
    }

    @Override
    public int translateY(final int y) {
        return y - mOriginY;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(final MotionEvent me) {
        final int action = me.getActionMasked();
        final long eventTime = me.getEventTime();
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index);
        final int y = (int)me.getY(index);
        final int pointerId = me.getPointerId(index);
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
            onDownEvent(x, y, pointerId, eventTime);
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
            onUpEvent(x, y, pointerId, eventTime);
            break;
        case MotionEvent.ACTION_MOVE:
            onMoveEvent(x, y, pointerId, eventTime);
            break;
        }
        return true;
    }

    @Override
    public void invalidateKey(final Key key) {
        super.invalidateKey(key);
        invalidate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onHoverEvent(final MotionEvent event) {
        final PopupKeysKeyboardAccessibilityDelegate accessibilityDelegate = mAccessibilityDelegate;
        if (accessibilityDelegate != null
                && AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled()) {
            return accessibilityDelegate.onHoverEvent(event);
        }
        return super.onHoverEvent(event);
    }
}
