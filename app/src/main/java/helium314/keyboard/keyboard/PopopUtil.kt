package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.PopupKeySpec
import helium314.keyboard.latin.common.Constants

fun findPopupHintLabel(popupKeys: Array<PopupKeySpec>?, oldHintLabel: String?): String? {
    if (popupKeys == null || oldHintLabel == null) return null
    if (popupKeys.any { it.mLabel == oldHintLabel }) return oldHintLabel
    return popupKeys.firstOrNull { it.mLabel != null }?.mLabel
}

/**
 * The popup key a downward flick on [key] should enter, or null when the key has none worth
 * flicking to. This is the same symbol the key shows as its hint, so what you get is what you see.
 */
fun findFlickPopupKey(key: Key): PopupKeySpec? {
    // functional keys carry negative codes, and their popups are things like the language switch
    if (key.code <= 0 || key.code == Constants.CODE_SPACE || key.isModifier || key.isRepeatable) return null
    val popupKeys = key.popupKeys ?: return null
    val enterable = popupKeys.filter {
        it.mLabel != null && (it.mCode > 0 || it.mOutputText != null)
    }
    if (enterable.isEmpty()) return null
    return enterable.firstOrNull { it.mLabel == key.hintLabel } ?: enterable.first()
}
