package com.menchant.ai.data

import org.json.JSONArray
import org.json.JSONObject

data class Msg(val role: String, val content: String) {
    companion object {
        fun toJson(list: List<Msg>): String {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
            return arr.toString()
        }

        fun fromJson(s: String?): List<Msg> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(s)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Msg(o.getString("role"), o.getString("content"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

enum class ModelId(val id: String, val title: String, val provider: String) {
    GEMINI("gemini", "Gemini 2.5 Flash", "Google AI Studio"),
    GROQ("groq", "Llama 3.3 70B", "GroqCloud"),
    MISTRAL("mistral", "Mistral Small", "Mistral AI"),
    OPENROUTER("openrouter", "OpenRouter Free", "OpenRouter");

    companion object {
        fun fromId(id: String?): ModelId = values().firstOrNull { it.id == id } ?: GEMINI
    }
}
