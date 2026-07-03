package com.xgglass.core

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent

internal actual fun nowMillis(): Long = ((CFAbsoluteTimeGetCurrent() + 978_307_200.0) * 1000).toLong()
