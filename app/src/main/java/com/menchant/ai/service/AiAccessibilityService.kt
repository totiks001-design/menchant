package com.menchant.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AiAccessibilityService? = null
            private set

        private const val MAX_NODES = 150
        private const val MAX_CHARS = 6000
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События не обрабатываем: дерево читаем по запросу агента.
    }

    override fun onInterrupt() {}

    // ---------- действия ----------

    suspend fun clickAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, 60)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(path, 400)
    }

    fun typeText(s: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        if (node.isPassword) return false // пароли не заполняем
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + s)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun openApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // ---------- снимок экрана ----------

    /** Текстовый снимок дерева AccessibilityNodeInfo: «текущий экран» для модели. */
    fun snapshot(): String {
        val root = rootInActiveWindow ?: return "(экран недоступен)"
        val sb = StringBuilder("Пакет: ${root.packageName}\n")
        var count = 0

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= MAX_NODES || sb.length > MAX_CHARS) return
            if (n.isVisibleToUser) {
                val text = if (n.isPassword) "***" else n.text?.toString()?.take(80)
                val desc = n.contentDescription?.toString()?.take(80)
                if (!text.isNullOrBlank() || !desc.isNullOrBlank() || n.isClickable || n.isEditable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    sb.append('[').append(n.className?.toString()?.substringAfterLast('.') ?: "?").append(']')
                    if (!text.isNullOrBlank()) sb.append(" \"").append(text.replace('\n', ' ')).append('"')
                    if (!desc.isNullOrBlank()) sb.append(" desc=\"").append(desc.replace('\n', ' ')).append('"')
                    sb.append(" центр=(").append(r.centerX()).append(',').append(r.centerY()).append(')')
                    if (n.isClickable) sb.append(" кликабельно")
                    if (n.isEditable) sb.append(" поле_ввода")
                    sb.append('\n')
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }

        walk(root)
        if (count == 0) sb.append("(нет видимых элементов)\n")
        return sb.toString()
    }

    // ---------- внутреннее ----------

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            findEditable(n.getChild(i))?.let { return it }
        }
        return null
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val started = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!started && cont.isActive) cont.resume(false)
        }
}