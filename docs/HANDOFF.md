# FrostKeys — session handoff

Written at the end of a long session so the next one can pick up without
re-deriving anything. FrostKeys is a fork of HeliBoard (itself a fork of
OpenBoard / AOSP LatinIME), package `com.orion.frostkeys`, app module `app/`.

---

## 1. Repository state

| Branch | Contains | Status |
|---|---|---|
| `main` @ `7543f61` | Haptics picker, frosted-glass save fix, flick keys, liquid glass, spellchecker/suggestion/font settings | Pushed, user-tested |
| `claude/key-animations-and-spacebar-trackpad` @ `3c9b279` | Keypress animations, strip crossfade, popup blur, spacebar trackpad, plus three rounds of bug fixes | Pushed, **partially tested — see §4** |

The working branch is 4 commits ahead of `main` and has **not** been merged.
Merge it only once the open items in §4 are resolved.

```
3c9b279 Stop the strip flashing per keystroke, fix settings search, back up learned words
7b88797 Fix press brightness, strip flashing and settings search, extend animations
92736c9 Fix crash inflating the suggestion strip
a9424f3 Add keypress animations, strip crossfade, popup blur and spacebar trackpad
```

Older merged branches (`claude/vibration-system-haptics-kcq9vb`,
`claude/frosted-glass-save-and-flick-keys`, `claude/liquid-glass-and-settings-fixes`)
are already in `main` and can be ignored.

Workflow the user expects: **new work on a new branch**, merge to `main` only
when asked. Never push to `main` without being told.

---

## 2. Build environment

There is **no Android SDK in the container by default**. It has to be installed
before anything can be compiled. This takes ~10 minutes the first time and is
worth doing immediately — several bugs shipped in this session were compile-clean
but broken at runtime, and a build is the only automated check available.

```bash
SP=/tmp/claude-<...>/scratchpad          # use the session scratchpad path
curl -sSL -o $SP/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p $SP/android-sdk/cmdline-tools
unzip -q -o $SP/cmdline-tools.zip -d $SP/android-sdk/cmdline-tools
mv $SP/android-sdk/cmdline-tools/cmdline-tools $SP/android-sdk/cmdline-tools/latest

export ANDROID_HOME=$SP/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Then, for every build:

```bash
export ANDROID_HOME=$SP/android-sdk ANDROID_SDK_ROOT=$SP/android-sdk
export GRADLE_USER_HOME=$SP/gradle-home
./gradlew --no-daemon :app:assembleDebug
```

Facts: compileSdk 36, minSdk 23, targetSdk 36, Kotlin 2.3.20, AGP 8.13.2,
Gradle 8.14.4, Java 21 in the container (CI uses 17). A full `assembleDebug`
takes ~2 min warm, ~6 min cold. `:app:compileDebugKotlin` alone is faster for
iterating. There are **no unit tests** and CI runs no lint gate.

The APK lands at `app/build/outputs/apk/debug/FrostKeys_<ver>-debug.apk`.

---

## 3. What was built, and where each setting lives

The user has repeatedly asked to be told **where in settings** new things are,
because the surface has grown a lot. Keep doing that.

### Settings → Appearance
Keypress animation block (all added this session, sits below "Keyboard corner roundness"):
Key shrink on press · Label shrink on press · Spacebar shrink on press ·
Spacebar label shrink on press · Press brightness · Press brightness direction ·
Press / Release animation length · Animation easing ·
Fade suggestion strip changes · Fade length · **Wait before showing toolbar** ·
**Adjust liquid glass** (opens its own dialog)

### Settings → Preferences (Input)
Flick keys to enter symbols · Flick rebound · Rebound shape · Rebound strength ·
Rebound length · Spacebar cursor trackpad · Fade keys while moving ·
Vertical sensitivity · Stick to lines · Hold spacebar for trackpad ·
Delete swipe selects words · Keypress vibration (the haptic picker) ·
Keypress sound volume (speaker) · **Keypress sound volume (headphones)**

### Settings → Text correction
Hide more suggestions marker (only shown when 5-word chips are off) ·
Show spellchecker suggestions (now actually wired up)

### Settings → Advanced
Backup and restore (pre-existing) · **Back up learned words** (new)

### Feature notes

**Haptics picker** (`event/KeyboardHaptic.kt`, `event/KeyboardHaptics`) — replaced a
millisecond slider with ~49 system haptics: `HapticFeedbackConstants`, predefined
`VibrationEffect`s, and composition primitives at three strengths. Default is
"Crisp tap" = `PRIMITIVE_CLICK` @0.35 with fallbacks. Entries the device can't
play are filtered out. `SCROLL_TICK`/`SCROLL_ITEM_FOCUS`/`SCROLL_LIMIT` are
documented as API 34 but are **not in the public SDK** — do not re-add them.

**Liquid glass** (`latin/LiquidGlass.kt`, `latin/LiquidGlassOverlay.kt`) — static
Apple-style glass edge drawn in `RoundedKeyboardFrameView.dispatchDraw`, after
`super` so it sits above the keys. Five layers: tint → edge lensing → contact
shadow → specular rim → face sheen. The rim uses a `SweepGradient` whose per-angle
alpha is `cos(θ − lightθ)^exponent`, approximating the surface normal by the
direction from the keyboard centre — that is what makes the shine travel along the
flat top edge instead of only lighting corners. Own `pref_liquid_*` keys, own
dialog, light/dark profiles, off by default. Values cached in `LiquidGlass`;
`invalidateCache()` on any pref change.

**Special key colour adjustments** (`KeyboardTheme.getThemeColors`, THEME_FROSTED_GLASS branch) —
`adjustSpecialColor` runs hue shift, then a blend towards a picked tint colour, then a lightness
move, over the colour the Material You palette already produced. Every knob is a no-op at 0, so
the keys stay fully dynamic until one is touched, and the key's alpha survives all three steps.
Lives in the **Adjust frosted glass** dialog under a "Special keys" heading, per light/dark
profile, and drives `ENTER_KEY_BACKGROUND` too since that has always shared the colour. Note a
hue shift alone does nothing to a near-grey base - that is what the tint colour is for.

**Keypress animations** (`keyboard/internal/KeyPressAnimator.java`) — a per-key
0..1 value **sampled during drawing**, not driven by an `Animator`. That is
deliberate: it lands on whatever rate the view is drawn at, so 120 Hz works with
no configuration. `KeyboardView.onDrawKeyboard` re-adds still-animating keys to
`mInvalidatedKeys` and calls `postInvalidateOnAnimation()`.

**Spacebar trackpad** (`PointerTracker.onSpacebarTrackpadMove`) — both axes at
once, no axis lock. Requires horizontal spacebar swipe set to **Move cursor**;
it hooks that path rather than adding a competing gesture. Vertical step is
`sPointerStep * (8 − 7·sensitivity)`, default sensitivity 25 → 6.25×. "Stick to
lines" multiplies by 1.8 after each line change. The **delete swipe is
deliberately untouched** — the user asked for that explicitly.

**Learned-words backup** (`settings/preferences/BackupRestorePreference.kt`) —
note the pre-existing full Backup **already** includes `UserHistoryDictionary*`
and `dicts/**/user.dict`. The new entry is a narrower one (those files + the
device `UserDictionary.Words` as JSON, no settings) so a reinstall can restore
personalization without dragging settings along.

---

## 4. Open / uncertain — start here

Listed in the order the user is most likely to hit them.

0. **Fixed after this document was written** (branch `claude/keyboard-ui-bugs-4hmcyx`):
   items 2 and 4 below, plus pinned toolbar keys drawing on top of the suggestions.
   - *Pinned keys over the suggestions*: `populatePinnedKeys()` set `pinnedKeys.isVisible`
     itself while `applyContainerVisibility` skipped its work whenever the target state
     equalled the applied one, so the container stayed up over the words. Visibility of the
     three stacked containers now goes through `applyContainerVisibility` only, and its
     early-out checks the views rather than trusting `appliedContainers`.
   - *Settings search pulling the keyboard down*: the real cause was `SearchScreen` swapping
     its whole content out for the results list on the first typed character. The search
     field lives inside that content, so it was detached and lost focus. There is no swap any
     more: `SearchState.searchResults` hands the results to the screen, which draws them
     below its own (unmoved) search field - see `SearchFieldWithResults()`.
   - *Popup panel white on dark*: `keyboard_popup_panel_background_*` is a `layer-list`
     whose fill is `#ffffff`, and it is only ever dark because `Colors.setBackground` puts a
     MODULATE colour filter on it. `applyPanelOpacity()` called `mutate()` on it, and
     `LayerDrawable.mutate()` re-creates every child from its constant state - which does not
     carry a colour filter - so the first popup after the input view was built drew the raw
     white shape. The next `setKeyboard()` put the filter back, which is why the second long
     press looked right. The whole panel-opacity / backdrop-blur mechanism is gone (it existed
     only to make the panel translucent, which is what needed the mutate), along with its three
     settings, and `showPopupKeysPanelInternal` now re-applies the colour from the live
     `Colors` on every open. **Do not call `mutate()` on that background again.**

1. **Compose lazy-list prefetch crash in settings search.** Reported twice.
   `LayoutNode.onChildRemoved` NPE from `LazyLayoutPrefetchState`. Mitigations
   applied: filtered list is `remember`ed per query; the settings search now
   passes `itemKey = { it.key }`; the search field was moved out of the
   `LazyColumn` into a fixed header. **Not confirmed fixed.** This is a known
   upstream Compose bug — if it recurs, the next step is disabling the prefetcher
   for that list or replacing the `LazyColumn` with a plain scrolling `Column`
   (which is what `SearchSettingsScreen` already uses and which has never crashed).
   File: `settings/SearchScreen.kt` ~line 380.

2. ~~**Settings search pulling the keyboard down.**~~ See item 0 - the lazy item was
   never the cause.

3. **Suggestion strip flashing.** Root cause was *not* the crossfade: the
   suggestions go briefly empty between keystrokes, and an empty set hands the
   strip to the toolbar and takes it straight back. `applyContainerVisibility`
   now debounces only the *losing* direction by `mStripSettleDelay` (180 ms).
   Untested. If it still flashes, raise the delay or look for another caller
   forcing container visibility directly — every write is supposed to go through
   `applyContainerVisibility`.

4. ~~**Popup panel light-on-dark.**~~ See item 0 - it was the `mutate()`, not the alpha.

5. ~~**Popup backdrop blur quality.**~~ Removed. The user only uses the dark theme and
   asked for the light background to be cut out entirely, so the symbol popup is a plain
   opaque themed panel again and `pref_popup_keys_blur*` / `pref_popup_keys_panel_opacity`
   are gone.

6. **Never verified on a device by me:** the liquid glass rim on real hardware
   (only verified by porting the shader maths to Python and rendering PNGs), the
   flick rebound shapes, press brightness after the paint fix, delete-swipe word
   snapping in both directions, and the learned-words backup round trip.

---

## 5. Bugs shipped this session, and the lesson from each

Worth reading — three of the four were compile-clean and broke at runtime.

- **`SuggestionStripView` inflate crash.** A `HashMap` property was declared at
  the bottom of the class but read from `updateKeys()`, which the constructor
  calls. Kotlin initialises properties in source order, so it was still null and
  the whole input view failed to inflate. **Lesson: in a Kotlin `View`, declare
  any property the constructor path touches above the `init` block.**

- **Press brightness did nothing.** It was applied as a `ColorFilter` on the key's
  background `Drawable`, but every theme style except Holo paints keys with
  `mBackgroundPaint` + `drawRoundRect` and never draws that drawable.
  **Lesson: `KeyboardView.onDrawKeyBackground` has two completely separate paint
  paths — check both.**

- **"Hide prediction placeholders" broke chained suggestion picking.** Filtering
  predictions out of the strip removed the next-word suggestions people rely on.
  The setting was deleted. **Lesson: the user's stated symptom ("a placeholder
  flashes") was a timing problem, not a content problem — fix the timing.**

- **Crossfade misdiagnosis.** Two rounds were spent making the fade prettier
  (sequential instead of overlapping) when the actual bug was that the swap
  should not happen at all. **Lesson: when an animation looks wrong, first ask
  whether the state change driving it is real.**

---

## 6. Working with this user

- They test on a real device (Redmi, Android 16, dark theme, frosted glass) and
  send crash reports as `.txt` uploads. Those are the primary feedback channel —
  read them carefully, the stack trace usually names the exact line.
- They want to be told **where each new setting lives**.
- They want features **toggleable and customisable**, and they mean it — when in
  doubt, add the slider.
- They notice when a report is vague or a fix is speculative. Say plainly what
  was verified and what was not.
- Everything shipped so far was compiled but never run. Be explicit about that
  distinction rather than implying a change is confirmed working.

---

## 7. Codebase orientation

Files touched most often, with what lives in them:

| File | Role |
|---|---|
| `latin/settings/Settings.java` | pref key constants |
| `latin/settings/Defaults.kt` | default values (Kotlin object; `const val` = compile-time) |
| `latin/settings/SettingsValues.java` | cached snapshot read on the drawing hot path |
| `settings/screens/*.kt` | Compose settings screens; each has an item list + a `createXSettings()` factory |
| `keyboard/KeyboardView.java` | key drawing, press animation, flick label, label fade |
| `keyboard/MainKeyboardView.java` | `DrawingProxy` impl, key previews, spacebar extras |
| `keyboard/PointerTracker.java` | all touch handling: press, long press, flick, swipes, trackpad |
| `keyboard/internal/DrawingProxy.java` | interface between PointerTracker and the view |
| `latin/suggestions/SuggestionStripView.kt` | the strip, toolbar keys, container swapping |
| `latin/RoundedKeyboardFrameView.kt` | outer frame, corner clipping, dust + liquid glass overlays |

Adding a setting means touching four files (`Settings.java`, `Defaults.kt`,
`SettingsValues.java`, the screen) plus `res/values/strings.xml`. Only add
strings to `values/strings.xml` — the other locales come from Weblate. If you
remove a string, remove it from all `values-*/strings.xml` too or aapt warns on
every build.

`Settings.dontReloadOnChanged` lists prefs that must not trigger a full keyboard
reload; the `pref_frosted_*` and `pref_liquid_*` families are handled by their
own listeners instead.
