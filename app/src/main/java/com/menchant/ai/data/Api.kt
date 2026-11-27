package com.menchant.ai.data

import com.menchant.ai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ApiException(val kind: Kind, val userMessage: String) : Exception(userMessage) {
    enum class Kind { NO_NETWORK, RATE_LIMIT, UNAVAILABLE, TIMEOUT, HTTP, UNKNOWN }
}

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Отправляет сообщения выбранной модели через Worker и стримит токены в onToken
     * (onToken вызывается в главном потоке). Бросает ApiException с русским текстом.
     */
    suspend fun send(model: ModelId, messages: List<Msg>, onToken: (String) -> Unit): Unit =
        coroutineScope {
            val arr = JSONArray()
            messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
            val payload = JSONObject().put("model", model.id).put("messages", arr).toString()

            val builder = Request.Builder()
                .url(BuildConfig.PROXY_URL)
                .header("Accept", "text/event-stream")
                .post(payload.toRequestBody(JSON_TYPE))
            if (BuildConfig.APP_TOKEN.isNotEmpty()) {
                builder.header("X-Menchant-Token", BuildConfig.APP_TOKEN)
            }
            val call = client.newCall(builder.build())

            // При отмене корутины мгновенно обрываем сетевой вызов.
            val watcher = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { readStream(call, onToken) }
            } finally {
                watcher.cancel()
            }
        }

    /**
     * При 429 (лимит) или 503 (ключ не настроен) пробует следующие модели.
     * Возвращает модель, которая реально ответила.
     */
    suspend fun sendWithFallback(
        preferred: ModelId,
        messages: List<Msg>,
        onSwitch: (from: ModelId, to: ModelId) -> Unit = { _, _ -> },
        onToken: (String) -> Unit
    ): ModelId {
        val order = listOf(preferred) + ModelId.values().filter { it != preferred }
        var previous = preferred
        var last: ApiException? = null
        for (m in order) {
            if (m != preferred) onSwitch(previous, m)
            try {
                send(m, messages, onToken)
                return m
            } catch (e: ApiException) {
                if (e.kind != ApiException.Kind.RATE_LIMIT && e.kind != ApiException.Kind.UNAVAILABLE) throw e
                last = e
                previous = m
            }
        }
        throw ApiException(
            ApiException.Kind.RATE_LIMIT,
            "Все модели сейчас недоступны или исчерпали бесплатный лимит (429). Подождите минуту и повторите."
        ).also { if (last == null) throw it }
    }

    private suspend fun readStream(call: Call, onToken: (String) -> Unit) {
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string())
                val source = resp.body?.source()
                    ?: throw ApiException(ApiException.Kind.UNKNOWN, "Сервер вернул пустой ответ.")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val obj = try {
                        JSONObject(data)
                    } catch (e: JSONException) {
                        continue
                    }
                    if (obj.has("error")) {
                        throw ApiException(ApiException.Kind.UNKNOWN, "Ошибка модели: ${obj.optString("error")}")
                    }
                    val t = obj.optString("t")
                    if (t.isNotEmpty()) {
                        withContext(Dispatchers.Main.immediate) { onToken(t) }
                    }
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ApiException(ApiException.Kind.NO_NETWORK, "Нет подключения к сети. Проверьте интернет и повторите.")
        } catch (e: ConnectException) {
            throw ApiException(ApiException.Kind.NO_NETWORK, "Не удалось подключиться к серверу. Проверьте интернет.")
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiException.Kind.TIMEOUT, "Время ожидания ответа истекло. Попробуйте ещё раз.")
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // отмена пользователем — не ошибка
            throw ApiException(ApiException.Kind.UNKNOWN, "Ошибка соединения: ${e.message ?: "неизвестно"}")
        }
    }

    private fun httpError(code: Int, body: String?): ApiException {
        val detail = try {
            JSONObject(body ?: "").optString("error")
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            429 -> ApiException(ApiException.Kind.RATE_LIMIT, "Превышен лимит запросов (429).")
            503 -> ApiException(ApiException.Kind.UNAVAILABLE, "Модель недоступна на сервере (503).")
            else -> ApiException(
                ApiException.Kind.HTTP,
                "Ошибка сервера ($code)" + if (detail.isNotBlank()) ": $detail" else "."
            )
        }
    }
}
