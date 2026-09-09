package com.xgglass.device.meta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MockDeviceKit smoke test for the Meta DAT 0.9 migration.
 *
 * Validates the DAT 0.9 session lifecycle (initialize -> pair -> createSession -> start -> STARTED)
 * end-to-end on a real Android runtime using a simulated device, without physical Meta glasses.
 *
 * NOTE: MockDeviceKit 0.9 only simulates camera-class devices (RAYBAN_META, *_OPTICS, OAKLEY_*,
 * META_GLASSES) and has no Meta Ray-Ban Display model or MockDisplayKit. The new display() output
 * path is therefore compile-verified only and still needs real Meta Ray-Ban Display hardware to
 * validate at runtime.
 */
@RunWith(AndroidJUnit4::class)
class MetaWearablesMockDeviceKitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mockDeviceKit = MockDeviceKit.getInstance(context)

    @After
    fun tearDown() {
        mockDeviceKit.disable()
    }

    @Test
    fun mockDeviceStartsSession() = runBlocking {
        Wearables.initialize(context).fold(
            onSuccess = {},
            onFailure = { error, cause ->
                throw AssertionError("DAT initialize failed: ${error.description}", cause)
            },
        )
        mockDeviceKit.enable()
        val device = mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        device.powerOn()
        device.unfold()
        device.don()

        val session = Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { it },
            onFailure = { error, cause ->
                throw AssertionError("createSession failed: ${error.description}", cause)
            },
        )
        try {
            session.start()
            val sessionState = withTimeout(5_000) {
                session.state.first { state ->
                    state == DeviceSessionState.STARTED || state == DeviceSessionState.STOPPED
                }
            }
            assertEquals(DeviceSessionState.STARTED, sessionState)
            // A closed Camera must release the capability so repeated photo requests can attach.
            repeat(2) {
                session.addCamera(StreamConfiguration()).getOrThrow().use { camera ->
                    org.junit.Assert.assertNotNull(camera.stream)
                }
            }
        } finally {
            session.stop()
        }
        Unit
    }
}
