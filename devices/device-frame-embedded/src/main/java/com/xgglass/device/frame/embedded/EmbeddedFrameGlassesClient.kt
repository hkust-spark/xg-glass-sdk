package com.xgglass.device.frame.embedded

import android.content.Context
import com.xgglass.core.GlassesClient
import com.xgglass.device.frame.flutter.FrameGlassesClient

/**
 * One-line constructor for Frame support.
 *
 * App developers create this client directly without manually embedding a Flutter module.
 * Today this works in-repo because `xgglass` auto-includes the Flutter module
 * (`third_party/frame/frame_module`) as :flutter.
 * For real distribution, ship the prebuilt Flutter AAR alongside this library.
 */
class EmbeddedFrameGlassesClient(context: Context) : GlassesClient by FrameGlassesClient(
    bridge = EmbeddedFrameFlutterBridge(context.applicationContext)
)
