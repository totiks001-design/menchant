# Menchant

Чат с бесплатными LLM + демо-режим управления телефоном через AccessibilityService.

## Быстрый старт (GitHub Actions)

1. Создай репозиторий на GitHub.
2. Залей все файлы этого проекта.
3. (Опционально) В Settings → Secrets добавь:
   - `PROXY_URL` — URL твоего Cloudflare Worker
   - `APP_TOKEN` — если задавал APP_TOKEN
4. Actions → Build APK → Run workflow
5. Скачай APK из Artifacts

## Cloudflare Worker

```bash
cd worker
wrangler secret put GEMINI_KEY
wrangler secret put GROQ_KEY
wrangler secret put MISTRAL_KEY
wrangler secret put OPENROUTER_KEY
wrangler secret put APP_TOKEN   # необязательно
wrangler deploy
```

После деплоя скопируй URL и передай его в сборку через `-PproxyUrl=...` или секрет `PROXY_URL`.

## Локальная сборка

```bash
./gradlew assembleDebug -PproxyUrl=https://menchant-proxy.xxx.workers.dev
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
