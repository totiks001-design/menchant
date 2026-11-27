package com.menchant.ai.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.menchant.ai.data.Api
import com.menchant.ai.data.ApiException
import com.menchant.ai.data.ModelId
import com.menchant.ai.data.Msg
import com.menchant.ai.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    var messages by mutableStateOf<List<Msg>>(emptyList())
        private set
    var model by mutableStateOf(ModelId.GEMINI)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    init {
        viewModelScope.launch {
            messages = store.history.first()
            model = store.model.first()
        }
    }

    fun selectModel(m: ModelId) {
        if (busy) return
        model = m
        viewModelScope.launch { store.saveModel(m) }
    }

    fun newChat() {
        viewModelScope.launch {
            job?.cancelAndJoin()
            messages = emptyList()
            notice = null
            busy = false
            store.saveHistory(messages)
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun send(text: String) {
        val clean = text.trim()
        if (busy || clean.isEmpty()) return

        val base = messages + Msg("user", clean)
        val request = base.takeLast(30).dropWhile { it.role != "user" }
        messages = base + Msg("assistant", "")
        busy = true
        notice = null

        job = viewModelScope.launch {
            var acc = ""
            try {
                val used = Api.sendWithFallback(
                    preferred = model,
                    messages = request,
                    onSwitch = { from, to ->
                        notice = "${from.title}: лимит исчерпан (429), переключаюсь на ${to.title}…"
                    }
                ) { token ->
                    acc += token
                    messages = messages.dropLast(1) + Msg("assistant", acc)
                }
                if (used != model) {
                    model = used
                    store.saveModel(used)
                    notice = "Лимит исчерпан (429) — ответила модель ${used.title}."
                } else {
                    notice = null
                }
                if (acc.isBlank()) notice = "Модель вернула пустой ответ. Попробуйте переформулировать."
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                notice = e.userMessage
            } catch (e: Exception) {
                notice = "Непредвиденная ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                val l = messages.lastOrNull()
                if (l != null && l.role == "assistant" && l.content.isBlank()) {
                    messages = messages.dropLast(1)
                }
                withContext(NonCancellable) { store.saveHistory(messages) }
            }
        }
    }
}
