package com.xgglass.device.inmo.runtime

/** Pure runtime decisions for the INMO Air3 adapter. */
object InmoAir3RuntimePolicy {
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_BACK = 4
    const val KEYCODE_ONE_FINGER_LONG_PRESS = 289
    const val KEYCODE_TWO_FINGER_LONG_PRESS = 290

    sealed class HostKeyAction {
        data class Tap(val count: Int) : HostKeyAction()
        data object LongPress : HostKeyAction()
        object Unhandled : HostKeyAction()
    }

    fun hostKeyAction(keyCode: Int): HostKeyAction =
        when (keyCode) {
            KEYCODE_ENTER -> HostKeyAction.Tap(1)
            KEYCODE_ONE_FINGER_LONG_PRESS,
            KEYCODE_TWO_FINGER_LONG_PRESS -> HostKeyAction.LongPress
            else -> HostKeyAction.Unhandled
        }

    fun normalizeRotationDegrees(raw: Int?): Int? {
        if (raw == null) return null
        val normalized = raw % 360
        return if (normalized >= 0) normalized else normalized + 360
    }
}
