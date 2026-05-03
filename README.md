# Forge Bridge — Android

A lightweight Android app that serves a unified AI provider API on `localhost:8745`.

## Quick Start

1. Open this folder in **Android Studio** (Iguana or newer)
2. Let Gradle sync complete
3. Run on a device or emulator (minSdk 26 / Android 8.0+)
4. Tap **Start Server**
5. Verify: `adb shell curl http://127.0.0.1:8745/api/v1/healthz`

## API Endpoints (Phase 0)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/healthz` | Server health check |
| `GET` | `/api/v1/providers` | List all providers + status |
| `GET` | `/api/v1/providers/{id}` | Single provider detail |
| `POST` | `/api/v1/providers/{id}/connect` | Save API key, mark connected |
| `POST` | `/api/v1/providers/{id}/disconnect` | Remove credentials |
| `POST` | `/api/v1/providers/{id}/test` | Ping provider connection |
| `POST` | `/api/v1/generate` | Route a completion request |
| `GET` | `/api/v1/audit-log` | Recent request log |
| `GET` | `/api/v1/stats` | Dashboard statistics |
| `GET` | `/api/v1/forge/handshake` | Forge OS discovery |
| `POST` | `/api/v1/forge/action` | Forge OS AI action |

## Connect a Provider (example)

```bash
# Connect OpenAI
curl -X POST http://127.0.0.1:8745/api/v1/providers/openai-api/connect \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"sk-...", "setAsDefault": true}'

# Test it
curl -X POST http://127.0.0.1:8745/api/v1/providers/openai-api/test

# Generate (Phase 1 adds real adapters)
curl -X POST http://127.0.0.1:8745/api/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello"}]}'
```

## Stack

| Component | Choice | Reason |
|-----------|--------|--------|
| HTTP Server | NanoHTTPD 2.3.1 | 50KB, zero deps, localhost-only |
| HTTP Client | OkHttp 4.12 | Used in Phase 1 provider adapters |
| JSON | Gson 2.11 | Lightweight, no codegen |
| Storage | Raw SQLite | 2 tables, Room is overkill |
| Credentials | EncryptedSharedPreferences | Keystore-backed AES-256-GCM |
| DI | Manual (`AppContainer`) | ~5 injectables, no framework |
| Background | ForegroundService | Survives screen off / process death |

## Build Phases

- **Phase 0** ✅ — Foundation: server, DB, vault, service, basic UI
- **Phase 1** — Official API adapters (OpenAI, Anthropic, Gemini, OpenRouter, Ollama)
- **Phase 2** — Settings UI: connect/disconnect providers from within the app
- **Phase 3** — Proxy tier: session capture via WebView
- **Phase 4** — Browser tier: WebView JS bridge automation
- **Phase 5** — Forge OS integration: mDNS discovery + intent system
- **Phase 6** — Polish: R8, GitHub Actions APK build, F-Droid metadata

## Security

- Server binds exclusively to `127.0.0.1` — never reachable from outside the device
- CORS restricted to `http://localhost` and `http://127.0.0.1`
- API keys stored in Android Keystore via `EncryptedSharedPreferences` (AES-256-GCM)
- No network calls to any Forge infrastructure
- Audit log records hashed token metadata only, never key content
