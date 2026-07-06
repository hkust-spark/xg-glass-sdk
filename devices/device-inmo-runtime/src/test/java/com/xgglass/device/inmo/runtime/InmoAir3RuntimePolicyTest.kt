package com.xgglass.device.inmo.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class InmoAir3RuntimePolicyTest {
    @Test
    fun enterMapsToSingleTap() {
        assertEquals(
            InmoAir3RuntimePolicy.HostKeyAction.Tap(1),
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_ENTER),
        )
    }

    @Test
    fun dpadUpIsUnhandledForFutureSwipeApi() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_DPAD_UP),
        )
    }

    @Test
    fun dpadDownIsUnhandledForFutureSwipeApi() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_DPAD_DOWN),
        )
    }

    @Test
    fun dpadLeftIsUnhandledForFutureSwipeApi() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_DPAD_LEFT),
        )
    }

    @Test
    fun dpadRightIsUnhandledForFutureSwipeApi() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_DPAD_RIGHT),
        )
    }

    @Test
    fun backIsUnhandledBecauseLauncherMayConsumeDoubleTap() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_BACK),
        )
    }

    @Test
    fun longPressKeysAreUnhandledForFutureInputApi() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_ONE_FINGER_LONG_PRESS),
        )
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(InmoAir3RuntimePolicy.KEYCODE_TWO_FINGER_LONG_PRESS),
        )
    }

    @Test
    fun unknownKeyIsUnhandled() {
        assertSame(
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled,
            InmoAir3RuntimePolicy.hostKeyAction(999),
        )
    }

    @Test
    fun nullRotationStaysNull() {
        assertNull(InmoAir3RuntimePolicy.normalizeRotationDegrees(null))
    }

    @Test
    fun cameraRotationIsNormalized() {
        assertEquals(0, InmoAir3RuntimePolicy.normalizeRotationDegrees(0))
        assertEquals(90, InmoAir3RuntimePolicy.normalizeRotationDegrees(90))
        assertEquals(180, InmoAir3RuntimePolicy.normalizeRotationDegrees(180))
        assertEquals(270, InmoAir3RuntimePolicy.normalizeRotationDegrees(270))
        assertEquals(90, InmoAir3RuntimePolicy.normalizeRotationDegrees(450))
        assertEquals(270, InmoAir3RuntimePolicy.normalizeRotationDegrees(-90))
    }
}
