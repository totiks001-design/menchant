// Menchant proxy: APK -> этот Worker -> API моделей.
// Ключи хранятся только в secrets Worker'а.
// Клиенту всегда отдаётся единый SSE-формат:
//   data: {"t":"фрагмент текста"}\n\n ... data: [DONE]\n\n

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-Menchant-Token",
  "Access-Control-Max-Age": "86400",
};

const OPENAI_COMPAT = {
  groq: {
    url: "https://api.groq.com/openai/v1/chat/completions",
    model: "llama-3.3-70b-versatile",
    keyName: "GROQ_KEY",
  },
  mistral: {
    url: "https://api.mistral.ai/v1/chat/completions",
    model: "mistral-small-latest",
    keyName: "MISTRAL_KEY",
  },
  openrouter: {
    url: "https://openrouter.ai/api/v1/chat/completions",
    model: "meta-llama/llama-3.3-70b-instruct:free",
    keyName: "OPENROUTER_KEY",
  },
};

const GEMINI_URL =
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse";

const MAX_CHARS = 100000;
const ROLES = new Set(["system", "user", "assistant"]);

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

function toGeminiBody(messages) {
  const system = messages
    .filter((m) => m.role === "system")
    .map((m) => m.content)
    .join("\n");
  const contents = messages
    .filter((m) => m.role !== "system")
    .map((m) => ({
      role: m.role === "assistant" ? "model" : "user",
      parts: [{ text: m.content }],
    }));
  const body = { contents };
  if (system) body.systemInstruction = { parts: [{ text: system }] };
  return body;
}

function extractGemini(j) {
  const parts = j?.candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) return "";
  return parts.map((p) => (p.thought ? "" : p.text || "")).join("");
}

function extractOpenAI(j) {
  return j?.choices?.[0]?.delta?.content || "";
}

// Превращает SSE провайдера в единый SSE {"t": "..."}
function normalizeStream(extract) {
  const dec = new TextDecoder();
  const enc = new TextEncoder();
  let buf = "";
  return new TransformStream({
    transform(chunk, ctrl) {
      buf += dec.decode(chunk, { stream: true });
      const lines = buf.split("\n");
      buf = lines.pop();
      for (const raw of lines) {
        const line = raw.trim();
        if (!line.startsWith("data:")) continue; // комментарии вида ": OPENROUTER PROCESSING" игнорируем
        const payload = line.slice(5).trim();
        if (!payload || payload === "[DONE]") continue;
        try {
          const t = extract(JSON.parse(payload));
          if (t) ctrl.enqueue(enc.encode("data: " + JSON.stringify({ t }) + "\n\n"));
        } catch (_) {
          /* битый фрагмент пропускаем */
        }
      }
    },
    flush(ctrl) {
      ctrl.enqueue(enc.encode("data: [DONE]\n\n"));
    },
  });
}

async function errorFromUpstream(res) {
  let detail = "";
  try {
    const text = await res.text();
    try {
      const j = JSON.parse(text);
      detail = j?.error?.message || j?.error || j?.message || text;
    } catch (_) {
      detail = text;
    }
  } catch (_) {}
  if (typeof detail !== "string") detail = JSON.stringify(detail);
  return json({ error: detail.slice(0, 300) || "Ошибка провайдера" }, res.status);
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }
    if (request.method !== "POST") {
      return json({ error: "Метод не поддерживается" }, 405);
    }

    // Необязательная защита: wrangler secret put APP_TOKEN
    if (env.APP_TOKEN && request.headers.get("X-Menchant-Token") !== env.APP_TOKEN) {
      return json({ error: "Нет доступа" }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch (_) {
      return json({ error: "Некорректный JSON" }, 400);
    }

    const { model, messages } = body || {};
    if (!Array.isArray(messages) || messages.length === 0) {
      return json({ error: "Поле messages обязательно" }, 400);
    }
    let total = 0;
    for (const m of messages) {
      if (!m || !ROLES.has(m.role) || typeof m.content !== "string") {
        return json({ error: "Некорректное сообщение" }, 400);
      }
      total += m.content.length;
    }
    if (total > MAX_CHARS) return json({ error: "Слишком длинный запрос" }, 413);

    let upstream;
    let extract;
    try {
      if (model === "gemini") {
        if (!env.GEMINI_KEY) return json({ error: "GEMINI_KEY не настроен" }, 503);
        upstream = await fetch(GEMINI_URL, {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_KEY },
          body: JSON.stringify(toGeminiBody(messages)),
        });
        extract = extractGemini;
      } else if (OPENAI_COMPAT[model]) {
        const p = OPENAI_COMPAT[model];
        const key = env[p.keyName];
        if (!key) return json({ error: p.keyName + " не настроен" }, 503);
        upstream = await fetch(p.url, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: "Bearer " + key },
          body: JSON.stringify({ model: p.model, messages, stream: true }),
        });
        extract = extractOpenAI;
      } else {
        return json({ error: "Неизвестная модель" }, 400);
      }
    } catch (e) {
      return json({ error: "Провайдер недоступен" }, 502);
    }

    if (!upstream.ok) return errorFromUpstream(upstream);
    if (!upstream.body) return json({ error: "Пустой ответ провайдера" }, 502);

    return new Response(upstream.body.pipeThrough(normalizeStream(extract)), {
      status: 200,
      headers: {
        ...CORS,
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
      },
    });
  },
};