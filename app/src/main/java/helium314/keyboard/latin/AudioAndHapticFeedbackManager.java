/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Vibrator;
import android.view.View;

import androidx.annotation.Nullable;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.event.KeyboardHaptic;
import helium314.keyboard.event.KeyboardHaptics;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.SettingsValues;

/**
 * This class gathers audio feedback and haptic feedback functions.
 * <p>
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private boolean mDoNotDisturb;
    /** whether audio is currently going to headphones rather than to the speaker */
    private boolean mHeadsetConnected;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        if (sInstance.mAudioManager != null) {
            return;
        }
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        startWatchingHeadsets();
    }

    /**
     * Keeps track of whether anything worn on the head is plugged in or paired, so the keypress
     * sound can be quieter on the speaker than it is in headphones. The callback fires once with
     * everything that is already connected when it is registered, and then on every change, so
     * nothing has to be queried on the press itself.
     */
    private void startWatchingHeadsets() {
        if (mAudioManager == null) {
            return;
        }
        mAudioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(final AudioDeviceInfo[] addedDevices) {
                updateHeadsetConnected();
            }

            @Override
            public void onAudioDevicesRemoved(final AudioDeviceInfo[] removedDevices) {
                updateHeadsetConnected();
            }
        }, null); // null handler means the main thread, which is where the keyboard reads this
        updateHeadsetConnected();
    }

    private void updateHeadsetConnected() {
        if (mAudioManager == null) {
            return;
        }
        boolean connected = false;
        for (final AudioDeviceInfo device : mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isHeadset(device)) {
                connected = true;
                break;
            }
        }
        mHeadsetConnected = connected;
    }

    // the newer device types are compile time constants, so naming them costs nothing on a device
    // that has never heard of them - they simply never match
    @SuppressLint("InlinedApi")
    private static boolean isHeadset(final AudioDeviceInfo device) {
        return switch (device.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET,
                 AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                 AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                 AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                 AudioDeviceInfo.TYPE_USB_HEADSET,
                 AudioDeviceInfo.TYPE_USB_DEVICE,
                 AudioDeviceInfo.TYPE_HEARING_AID,
                 AudioDeviceInfo.TYPE_BLE_HEADSET,
                 AudioDeviceInfo.TYPE_BLE_BROADCAST -> true;
            default -> false;
        };
    }

    /** true while the keypress sound would come out of headphones rather than the speaker */
    public boolean isHeadsetConnected() {
        return mHeadsetConnected;
    }

    public void performHapticAndAudioFeedback(
        final int code,
        final View viewToPerformHapticFeedbackOn,
        final HapticEvent hapticEvent
    ) {
        performHapticFeedback(viewToPerformHapticFeedbackOn, hapticEvent);
        performAudioFeedback(code, hapticEvent);
    }

    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    @Nullable
    public Vibrator getVibrator() {
        return mVibrator;
    }

    /** plays {@code haptic} once, so the settings can preview what was picked */
    public void previewHaptic(final KeyboardHaptic haptic, final View view) {
        haptic.play(view, mVibrator, HapticEvent.KEY_PRESS);
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null || mDoNotDisturb) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code, final HapticEvent hapticEvent) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        if (mAudioManager == null) {
            return;
        }
        if (!mSoundOn) {
            return;
        }
        if (hapticEvent != HapticEvent.KEY_PRESS) {
            return;
        }
        final int sound = switch (code) {
            case KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE;
            case Constants.CODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN;
            case Constants.CODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR;
            default -> AudioManager.FX_KEYPRESS_STANDARD;
        };
        final float volume = mHeadsetConnected
                ? mSettingsValues.mKeypressSoundVolumeHeadset
                : mSettingsValues.mKeypressSoundVolume;
        mAudioManager.playSoundEffect(sound, volume);
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn, final HapticEvent hapticEvent) {
        if (mSettingsValues == null || !mSettingsValues.mVibrateOn
                || (mDoNotDisturb && !mSettingsValues.mVibrateInDndMode)) {
            return;
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            // Avoid surprises with the handling of HapticFeedbackConstants.NO_HAPTICS
            return;
        }
        final KeyboardHaptic haptic = hapticEvent.usesSelectedHaptic
                ? mSettingsValues.mKeypressHaptic
                // gesture and repeat keep whatever the system plays for the matching event
                : KeyboardHaptics.SYSTEM_DEFAULT;
        haptic.play(viewToPerformHapticFeedbackOn, mVibrator, hapticEvent);
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged(boolean doNotDisturb) {
        mDoNotDisturb = doNotDisturb;
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
