package com.xgglass.device.rokid

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import com.xgglass.core.GlassesError

internal interface RokidDisplayTransport {
    fun open(layout: String): ValueUtil.CxrStatus
    fun update(update: String): ValueUtil.CxrStatus
    fun close()
}

internal interface RokidDisplayScheduler {
    fun nowMillis(): Long
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

private class AndroidDisplayScheduler : RokidDisplayScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun nowMillis(): Long = SystemClock.uptimeMillis()
    override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
}

private class CxrDisplayTransport : RokidDisplayTransport {
    override fun open(layout: String) = CxrApi.getInstance().openCustomView(layout)
    override fun update(update: String) = CxrApi.getInstance().updateCustomView(update)
    override fun close() { CxrApi.getInstance().closeCustomView() }
}

/** Coalesces display updates while preserving every APPEND accepted by the caller. */
internal class RokidDisplayController(
    private val minUpdateIntervalMs: Long = 350L,
    private val transport: RokidDisplayTransport = CxrDisplayTransport(),
    private val scheduler: RokidDisplayScheduler = AndroidDisplayScheduler(),
    private val onAsyncFailure: (Exception) -> Unit = {},
    private val isConnected: () -> Boolean = { true },
) {
    private val gson = Gson()
    private var isCustomViewOpened = false
    private var lastUpdateAt: Long? = null
    private var pendingRunnable: Runnable? = null
    private var generation = 0L

    // The desired document, including queued updates; not just the last transmitted text.
    var lastText: String = ""
        private set

    @Synchronized
    fun showText(text: String, force: Boolean, append: Boolean = false) {
        val previousText = lastText
        lastText = if (append) lastText + text else text
        val now = scheduler.nowMillis()
        val elapsed = lastUpdateAt?.let { now - it }
        if (!force && elapsed != null && elapsed < minUpdateIntervalMs) {
            cancelPending()
            val queuedGeneration = generation
            pendingRunnable = Runnable {
                synchronized(this) {
                    if (generation != queuedGeneration) return@Runnable
                    pendingRunnable = null
                    try {
                        sendTextNow(lastText)
                    } catch (e: Exception) {
                        onAsyncFailure(e)
                    }
                }
            }
            scheduler.postDelayed(pendingRunnable!!, minUpdateIntervalMs - elapsed)
        } else {
            try {
                sendTextNow(lastText)
                cancelPending()
            } catch (e: Exception) {
                lastText = previousText
                throw e
            }
        }
    }

    private fun cancelPending() {
        generation++
        pendingRunnable?.let(scheduler::cancel)
        pendingRunnable = null
    }

    @Synchronized
    fun close() {
        cancelPending()
        lastText = ""
        lastUpdateAt = null
        try {
            transport.close()
        } finally {
            isCustomViewOpened = false
        }
    }

    private fun requireAccepted(status: ValueUtil.CxrStatus, operation: String) {
        when (status) {
            // 1.2.2's request path returns only SUCCEED/FAILED. Keep the
            // adapter's existing WAITING acceptance for vendor compatibility.
            ValueUtil.CxrStatus.REQUEST_SUCCEED, ValueUtil.CxrStatus.REQUEST_WAITING -> Unit
            else -> throw GlassesError.Transport("Rokid $operation failed: $status")
        }
    }

    private fun sendTextNow(text: String) {
        if (!isConnected()) throw GlassesError.NotConnected
        if (!isCustomViewOpened) {
            requireAccepted(transport.open(createLayoutJson(text)), "openCustomView")
            isCustomViewOpened = true
        } else {
            val status = transport.update(createUpdateJson(text))
            if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                // The view may have been closed on the glasses. Reopen once.
                isCustomViewOpened = false
                requireAccepted(transport.open(createLayoutJson(text)), "openCustomView")
                isCustomViewOpened = true
            } else {
                requireAccepted(status, "updateCustomView")
            }
        }
        lastUpdateAt = scheduler.nowMillis()
    }

    private fun createLayoutJson(content: String): String {
        val root = JsonObject().apply {
            addProperty("type", "LinearLayout")
            add("props", JsonObject().apply {
                addProperty("layout_width", "match_parent")
                addProperty("layout_height", "match_parent")
                addProperty("orientation", "vertical")
                addProperty("gravity", "center_vertical")
                addProperty("backgroundColor", "#FF000000")
                addProperty("paddingStart", "0dp")
                addProperty("paddingEnd", "0dp")
            })
            add("children", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "TextView")
                    add("props", JsonObject().apply {
                        addProperty("id", "tv_content")
                        addProperty("layout_width", "match_parent")
                        addProperty("layout_height", "wrap_content")
                        addProperty("text", content)
                        addProperty("textSize", "12sp")
                        addProperty("textColor", "#FFFFFFFF")
                        addProperty("gravity", "start")
                        addProperty("paddingTop", "8dp")
                        addProperty("paddingBottom", "8dp")
                    })
                })
            })
        }
        return gson.toJson(root)
    }

    private fun createUpdateJson(content: String): String {
        val updateArray = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("action", "update")
                addProperty("id", "tv_content")
                add("props", JsonObject().apply {
                    addProperty("text", content)
                })
            })
        }
        return gson.toJson(updateArray)
    }
}
