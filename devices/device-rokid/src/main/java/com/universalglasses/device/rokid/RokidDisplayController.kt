package com.universalglasses.device.rokid

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil

/**
 * Rokid "Custom View" text renderer.
 *
 * Mirrors the proven approach in the sample app:
 * - call openCustomView() once
 * - update text via updateCustomView() to reduce flicker
 * - throttle streaming updates to avoid over-refresh
 */
internal class RokidDisplayController(
    private val minUpdateIntervalMs: Long = 350L,
) {
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isCustomViewOpened: Boolean = false
    private var lastUpdateAt: Long = 0L
    private var pendingText: String? = null
    private var pendingRunnable: Runnable? = null

    var lastText: String = ""
        private set

    fun showText(text: String, force: Boolean) {
        pendingText = text

        val now = System.currentTimeMillis()
        val withinWindow = now - lastUpdateAt < minUpdateIntervalMs
        if (!force && withinWindow) {
            pendingRunnable?.let { mainHandler.removeCallbacks(it) }
            val delay = minUpdateIntervalMs - (now - lastUpdateAt)
            pendingRunnable = Runnable {
                val latest = pendingText ?: return@Runnable
                pendingRunnable = null
                sendTextNow(latest)
            }
            mainHandler.postDelayed(pendingRunnable!!, delay)
            return
        }

        sendTextNow(text)
    }

    fun close() {
        try {
            CxrApi.getInstance().closeCustomView()
        } finally {
            isCustomViewOpened = false
        }
    }

    private fun sendTextNow(text: String) {
        lastUpdateAt = System.currentTimeMillis()
        lastText = text

        if (!isCustomViewOpened) {
            val layoutJson = createLayoutJson(text)
            val status = CxrApi.getInstance().openCustomView(layoutJson)
            val ok = status == ValueUtil.CxrStatus.REQUEST_SUCCEED || status == ValueUtil.CxrStatus.REQUEST_WAITING
            isCustomViewOpened = ok
            return
        }

        val updateJson = createUpdateJson(text)
        val status = CxrApi.getInstance().updateCustomView(updateJson)
        val ok = status == ValueUtil.CxrStatus.REQUEST_SUCCEED || status == ValueUtil.CxrStatus.REQUEST_WAITING
        if (!ok) {
            // fallback: reopen once
            isCustomViewOpened = false
            val layoutJson = createLayoutJson(text)
            val openStatus = CxrApi.getInstance().openCustomView(layoutJson)
            val openOk = openStatus == ValueUtil.CxrStatus.REQUEST_SUCCEED || openStatus == ValueUtil.CxrStatus.REQUEST_WAITING
            isCustomViewOpened = openOk
        }
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
