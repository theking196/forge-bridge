# Forge Bridge — Build Phases

## Phase 0 — Foundation ✅ COMPLETE

**Goal:** Bare Android project compiles, NanoHTTPD responds on `localhost:8745`, encrypted Keystore, ForegroundService, BootReceiver, MainActivity.

### Files created
| File | Purpose |
|------|---------|
| `app/build.gradle` | Dependencies: NanoHTTPD, OkHttp, Gson, EncryptedSharedPreferences, Security-Crypto |
| `AndroidManifest.xml` | INTERNET, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED |
| `ForgeBridgeApp.kt` | Application subclass, AppContainer DI root |
| `di/AppContainer.kt` | Lazy singletons: Database, VaultManager, OkHttpClient, AdapterRegistry, BridgeServer |
| `data/local/Database.kt` | SQLiteOpenHelper — providers + audit_log tables |
| `data/local/VaultManager.kt` | EncryptedSharedPreferences — storeApiKey / getApiKey / storeSessionToken / getSessionToken |
| `data/remote/server/BridgeServer.kt` | NanoHTTPD on 127.0.0.1:8745 |
| `service/BridgeService.kt` | ForegroundService — starts BridgeServer, posts notification |
| `service/BootReceiver.kt` | BroadcastReceiver — auto-starts BridgeService on boot |
| `ui/MainActivity.kt` | Start/stop service button, provider status list, stats card |
| `res/layout/activity_main.xml` | Main screen layout |

### API endpoints at end of Phase 0
| Route | Status |
|-------|--------|
| `GET /api/v1/healthz` | ✅ OK |
| `GET /api/v1/providers` | ✅ Returns 5 seeded providers |
| `POST /api/v1/providers/{id}/connect` | ✅ Saves key to Keystore |
| `POST /api/v1/providers/{id}/disconnect` | ✅ Wipes key |
| `POST /api/v1/generate` | ⚠️ Stub only |

---

## Phase 1 — Official API Provider Adapters ✅ COMPLETE

**Goal:** Real AI responses via the official APIs of 5 providers, with live SSE streaming

### Files created
| File | Purpose |
|------|---------|
| `data/remote/adapters/ProviderAdapter.kt` | Interface + SSE helpers (`writeSseChunk`, `writeSseDone`, `writeSseError`) |
| `data/remote/adapters/OpenAIAdapter.kt` | `api.openai.com/v1/chat/completions` — SSE delta streaming |
| `data/remote/adapters/AnthropicAdapter.kt` | `api.anthropic.com/v1/messages` — SSE content_block_delta streaming |
| `data/remote/adapters/GeminiAdapter.kt` | `generativelanguage.googleapis.com` — SSE chunk accumulation |
| `data/remote/adapters/OpenRouterAdapter.kt` | `openrouter.ai/api/v1/chat/completions` — same as OpenAI format |
| `data/remote/adapters/OllamaAdapter.kt` | `localhost:11434/api/chat` — NDJSON streaming + model list |
| `data/remote/adapters/AdapterRegistry.kt` | Maps provider IDs to adapter instances |
| `data/model/Models.kt` | Data classes: GenerateRequest, Message, ConnectRequest, ProviderRow, AuditLogEntry, BridgeStats |

### Files updated
| File | Change |
|------|--------|
| `BridgeServer.kt` | `handleGenerate` — real dispatch to adapters, piped-stream threading, blocking + streaming modes |
| `BridgeServer.kt` | `handleTestProvider` — calls `adapter.testConnection()` |

### API endpoints at end of Phase 1
| Route | Phase 0 | Phase 1 |
|-------|---------|---------|
| `POST /api/v1/generate` | ⚠️ Stub | ✅ Real streaming |
| `POST /api/v1/providers/{id}/test` | ⚠️ Stub | ✅ Real connection ping, latency measurement |

---

## Phase 2 — Settings UI ✅ COMPLETE

**Goal:** Users can connect/disconnect providers from within the app — no curl required

### Files created
| File | Purpose |
|------|---------|
| `ui/providers/ProviderListActivity.kt` | RecyclerView list of all providers, handles connect/disconnect/test callbacks |
| `ui/providers/ProviderListAdapter.kt` | `ListAdapter` with `DiffUtil` — status dot, tier badge, default badge, action buttons per row |
| `ui/providers/ConnectDialogFragment.kt` | `MaterialAlertDialog` — API key input (password toggle), "Set as default" checkbox, Ollama special-case |
| `res/layout/activity_provider_list.xml` | CoordinatorLayout + RecyclerView |
| `res/layout/item_provider.xml` | Provider card: status dot, name, tier badge, default badge, description, Connect/Test/Disconnect buttons |
| `res/layout/dialog_connect.xml` | TextInputLayout (monospace, password) + Ollama hint text + default checkbox |
| `res/drawable/dot_connected.xml` | Green oval shape |
| `res/drawable/dot_disconnected.xml` | Gray oval shape |
| `res/drawable/badge_tier.xml` | Rounded rect for API/proxy/browser tier labels |
| `res/drawable/badge_default.xml` | Outlined rounded rect in forge_orange for DEFAULT badge |

### Files updated
| File | Change |
|------|--------|
| `ui/MainActivity.kt` | Added "Manage →" button → opens ProviderListActivity |
| `res/layout/activity_main.xml` | Added Manage button in provider list card header |
| `AndroidManifest.xml` | Registered `ProviderListActivity` with `parentActivityName` for back-nav |

### UX flow
1. User opens app → taps **Manage** in the providers card
2. `ProviderListActivity` shows all providers with live status
3. Tap **Connect** → `ConnectDialogFragment` opens, enter API key → saved to Keystore, SQLite updated
4. Tap **Test** → background call to `adapter.testConnection()` → Toast shows latency + model name
5. Tap **Disconnect** → key deleted from Keystore, SQLite status set to `disconnected`
6. Returning to `MainActivity` re-polls SQLite and updates the status list

---

## Phase 3 — Proxy Tier / Session Capture ✅ COMPLETE

**Goal:** ChatGPT Plus/Pro and Claude Pro work without any API key by capturing the session cookie from a WebView login

### Files created
| File | Purpose |
|------|---------|
| `ui/browser/WebLoginActivity.kt` | Fullscreen WebView per provider; detects successful login by URL pattern + DOM check; extracts cookies via `CookieManager`; fetches access token (ChatGPT) or org UUID (Claude) via OkHttp while cookies are live; persists to VaultManager; finishes with `RESULT_OK` |
| `data/remote/adapters/ProxyProviderAdapter.kt` | Sub-interface of `ProviderAdapter` adding `streamWithCookies(req, accessToken, cookies, out)` |
| `data/remote/adapters/ChatGPTProxyAdapter.kt` | `chatgpt.com/backend-api/conversation` — cookies + Bearer token + OpenAI-Sentinel-Chat-Requirements-Token + Oai-Device-Id; accumulative delta parsing |
| `data/remote/adapters/ClaudeProxyAdapter.kt` | `claude.ai/api/organizations/{org}/chat_conversations/{conv}/completion` — per-request conversation creation; SSE completion events |
| `res/layout/activity_web_login.xml` | Fullscreen WebView with progress bar and Cancel button |

### Files updated
| File | Change |
|------|--------|
| `data/remote/adapters/AdapterRegistry.kt` | Added `chatgpt-proxy` and `claude-proxy` entries; `isProxy()` helper |
| `data/local/Database.kt` | `DB_VERSION` bumped 1→2; `onUpgrade` adds proxy providers via `CONFLICT_IGNORE`; `seedProxyProviders()` also called in fresh installs |
| `data/remote/server/BridgeServer.kt` | `streamingResponse` detects `ProxyProviderAdapter` and calls `streamWithCookies(accessToken, cookies, out)`; proxy providers return 400 from `/connect` endpoint with helpful message |
| `ui/providers/ProviderListActivity.kt` | `onConnectClick` checks `tier == "proxy"` → launches `WebLoginActivity` for result via `ActivityResultLauncher`; stores `pendingLoginProviderId` |
| `ui/providers/ProviderListAdapter.kt` | Proxy rows show **Login** button instead of **Connect**; Test button hidden for proxy providers |
| `AndroidManifest.xml` | Registered `WebLoginActivity` with `configChanges` and `parentActivityName` |

### Login detection (hardened in post-Phase-3 session)
Detection is two-step for both providers:
1. **URL check** — matches known post-login URL patterns (ChatGPT: `chatgpt.com/` not under `/auth/`; Claude: `/new`, `/chat/*`, or root)
2. **DOM check** — `evaluateJavascript` confirms a `textarea` or send button is present before triggering save

---

## Phase 4 — Browser Tier / WebView Automation ✅ COMPLETE

**Goal:** Full web app features via JavaScript bridge — most resilient tier, works even if internal APIs change

### Files created
| File | Purpose |
|------|---------|
| `data/remote/browser/BrowserChunk.kt` | Data class for the chunk queue: `text`, `error`, `done` sentinel |
| `data/remote/browser/JavaScriptBridge.kt` | `@JavascriptInterface` exposed as `window.ForgeBridge` — `onChunk()`, `onDone()`, `onError()`, `log()` |
| `data/remote/browser/BrowserProviderManager.kt` | Manages persistent headless WebViews per provider; dispatches to main thread via `Handler`; restores session cookies from VaultManager into `CookieManager` on WebView init; injects JS assets with placeholder substitution |
| `data/remote/adapters/BrowserTierAdapter.kt` | `ProviderAdapter` wrapping `BrowserProviderManager`; polls `LinkedBlockingQueue<BrowserChunk>` and writes SSE |
| `assets/injectors/chatgpt_injector.js` | Same-origin `fetch()` to `/backend-api/conversation`; accumulative delta parsing; correct SSE event loop with buffer |
| `assets/injectors/claude_injector.js` | Same-origin `fetch()` — org lookup → conversation create → stream completion; handles all SSE event types |

### Files updated
| File | Change |
|------|--------|
| `di/AppContainer.kt` | Added `browserProviderManager` lazy singleton; passed to `AdapterRegistry` |
| `data/remote/adapters/AdapterRegistry.kt` | Added `chatgpt-browser` and `claude-browser` entries; `isBrowser()` helper |
| `data/local/Database.kt` | `DB_VERSION` bumped 2→3; `onUpgrade` seeds browser providers; `seedBrowserProviders()` also in `onCreate` |
| `service/BridgeService.kt` | Touches `browserProviderManager` on start (ensures WebViews initialize on main thread); calls `destroy()` on service stop |
| `ui/browser/WebLoginActivity.kt` | After successful proxy login, also marks the browser-tier counterpart as `connected` |
| `ui/providers/ProviderListAdapter.kt` | Browser-tier rows: no Login button (auto-activated by proxy login); shows descriptive hint when not yet connected |

---

## Phase 5 — Forge OS Integration ✅ COMPLETE

**Goal:** Forge OS discovers Forge Bridge automatically via mDNS and sends structured AI requests via broadcast intents

### Files created
| File | Purpose |
|------|---------|
| `service/MdnsAdvertiser.kt` | Advertises `_forge-bridge._tcp` on port 8745 via Android `NsdManager`; `start()`/`stop()` tied to `BridgeService` lifecycle |
| `service/ForgeIntentReceiver.kt` | Handles `com.forge.ACTION_AI_REQUEST` broadcasts; trust-gate via VaultManager; dispatches to `127.0.0.1:8745/api/v1/generate` on background thread; sends result back via `PendingIntent` |
| `ui/permissions/PermissionActivity.kt` | Dialog-themed Activity shown on first Forge OS request; "Allow / Deny" stores decision in VaultManager (`forge_os_trusted`) |

### Files updated
| File | Change |
|------|--------|
| `AndroidManifest.xml` | Declared `com.forge.bridge.permission.SEND_AI_REQUEST` (protectionLevel=normal); registered `ForgeIntentReceiver` with that permission; registered `PermissionActivity` with dialog theme |
| `di/AppContainer.kt` | Added `mdnsAdvertiser` lazy singleton |
| `service/BridgeService.kt` | Calls `mdns.start()` on server start, `mdns.stop()` on stop; notification text updated to show "mDNS active" |
| `data/local/VaultManager.kt` | Added `storeForgeOsTrust()`, `isForgeOsTrusted()`, `revokeForgeOsTrust()` |
| `data/remote/server/BridgeServer.kt` | Version bumped 0.3.0→0.4.0; `handleForgeAction` now dispatches real `generate`/`list_providers`/`status` actions; added `POST /api/v1/forge/trust` and `DELETE /api/v1/forge/trust` endpoints; `handleForgeHandshake` returns mDNS info, intent action, and trust status |

### Security model
| Layer | Mechanism |
|-------|-----------|
| Permission gate | `android:permission="com.forge.bridge.permission.SEND_AI_REQUEST"` on receiver — only apps declaring `<uses-permission>` can send the broadcast |
| Trust gate | `VaultManager.isForgeOsTrusted()` — requires explicit user Allow via `PermissionActivity` on first use |
| Network | Server is bound to `127.0.0.1` only — no inbound network access possible regardless of trust |

### New API endpoints (Phase 5)
| Route | Method | Purpose |
|-------|--------|---------|
| `/api/v1/forge/handshake` | GET | Returns version, connected providers, mDNS config, intent action, trust status |
| `/api/v1/forge/action` | POST | Dispatches `generate` / `list_providers` / `status` actions |
| `/api/v1/forge/trust` | POST | Programmatically grant Forge OS trust (`{"allowed": true}`) |
| `/api/v1/forge/trust` | DELETE | Revoke Forge OS trust |

### Forge OS integration flow
1. Forge OS resolves `_forge-bridge._tcp.local.` → gets host `127.0.0.1` and port `8745`
2. **Optional HTTP path:** `GET /api/v1/forge/handshake` — confirms bridge version + capabilities
3. **Intent path:** send `com.forge.ACTION_AI_REQUEST` with extras `message`, optional `provider`/`model`, and a `replyPendingIntent`
4. First time → `PermissionActivity` appears asking user to Allow or Deny
5. After Allow: `ForgeIntentReceiver` POSTs to `127.0.0.1:8745/api/v1/generate` on a background thread and fires the reply `PendingIntent` with the JSON response

### Usage examples
```bash
# Forge OS HTTP path
curl -s http://127.0.0.1:8745/api/v1/forge/handshake | jq .

# Forge action endpoint
curl -s -X POST http://127.0.0.1:8745/api/v1/forge/action \
  -H 'Content-Type: application/json' \
  -d '{"action":"generate","message":"Summarise this code","provider":"openai-api"}'

# Grant trust programmatically (e.g., from adb during dev)
curl -s -X POST http://127.0.0.1:8745/api/v1/forge/trust \
  -H 'Content-Type: application/json' \
  -d '{"allowed":true}'
```

```bash
# Send intent from adb (Forge OS simulator)
adb shell am broadcast \
  -a com.forge.ACTION_AI_REQUEST \
  -p com.forge.bridge \
  --es message "Hello from Forge OS" \
  --es provider "openai-api"
```

---

## Phase 6 — Polish + Distribution ✅ COMPLETE

**Goal:** Production-ready APK, minimal size, GitHub Actions CI, F-Droid metadata

### Files created
| File | Purpose |
|------|---------|
| `.github/workflows/forge-bridge-release.yml` | GitHub Actions: build + sign release APK on tag push (`v*.*.*`), upload to GitHub Releases, upload artifact for inspection, print APK size to step summary |
| `fastlane/metadata/android/en-US/title.txt` | F-Droid app title |
| `fastlane/metadata/android/en-US/short_description.txt` | F-Droid 80-char summary |
| `fastlane/metadata/android/en-US/full_description.txt` | F-Droid full store listing |
| `fastlane/metadata/android/en-US/changelogs/4.txt` | Changelog for versionCode 4 (v0.4.0) |
| `fastlane/metadata/android/en-US/changelogs/2.txt` | Changelog for versionCode 2 (v0.2.0) |
| `fastlane/metadata/android/categories.txt` | F-Droid category: Productivity |

### Files updated
| File | Change |
|------|--------|
| `app/proguard-rules.pro` | Comprehensive keep rules: NanoHTTPD, Gson (models + TypeAdapterFactory), OkHttp + SSE, AndroidX Security/Tink, Kotlin coroutines, `@JavascriptInterface` methods, all Android components; `-renamesourcefileattribute` for readable crash traces |
| `app/build.gradle.kts` | `versionCode` 2→4, `versionName` "0.2.0"→"0.4.0"; `isShrinkResources = true` on release; conditional signing config reading from env vars (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); `packaging.resources.excludes` for OkHttp/Okio META-INF duplicates |

### R8 size strategy
| Technique | Effect |
|-----------|--------|
| `isMinifyEnabled = true` | Dead code removal + identifier obfuscation |
| `isShrinkResources = true` | Strips unused drawables, layouts, strings |
| `proguard-android-optimize.txt` | Aggressive inlining from AAPT base rules |
| Custom `proguard-rules.pro` | Tight keeps for reflection-heavy libs; broad `-dontwarn` suppression |
| Target APK size | ≤ 2 MB release, ≤ 3 MB debug |

### CI/CD workflow
```
git tag v0.4.1 && git push --tags
         ↓
GitHub Actions: forge-bridge-release.yml
  1. Checkout + JDK 17 (Temurin)
  2. Decode RELEASE_KEYSTORE_B64 secret → temp .keystore file
  3. ./gradlew :app:assembleRelease (R8 + resource shrink + signing)
  4. Rename: app-release.apk → forge-bridge-v0.4.1.apk
  5. Print APK size to step summary
  6. Upload as workflow artifact (30-day retention)
  7. Create GitHub Release with auto-generated notes + APK attached
```

### GitHub Secrets required for signed releases
| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_B64` | `base64 -w0 release.keystore` output |
| `KEYSTORE_PASSWORD` | Keystore store password |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Key password |

Unsigned builds still work locally and in CI if no secrets are configured (APK is built but not signed).

---

## Active endpoint map (end of Phase 5)

| Route | Method | Status | Notes |
|-------|--------|--------|-------|
| `/api/v1/healthz` | GET | ✅ | uptime, version 0.4.0 |
| `/api/v1/providers` | GET | ✅ | all 9 providers with status |
| `/api/v1/providers/{id}` | GET | ✅ | single provider detail |
| `/api/v1/providers/{id}/connect` | POST | ✅ | API-tier only; proxy returns 400 |
| `/api/v1/providers/{id}/disconnect` | POST | ✅ | clears key + session token |
| `/api/v1/providers/{id}/test` | POST | ✅ | pings adapter, returns latency + model |
| `/api/v1/generate` | POST | ✅ | streaming + blocking; proxy always streams |
| `/api/v1/audit-log` | GET | ✅ | last N entries, filterable by provider |
| `/api/v1/stats` | GET | ✅ | request counts, latency, token usage |
| `/api/v1/forge/handshake` | GET | ✅ | version, providers, mDNS info, trust status |
| `/api/v1/forge/action` | POST | ✅ | generate / list_providers / status actions |
| `/api/v1/forge/trust` | POST | ✅ | grant Forge OS trust |
| `/api/v1/forge/trust` | DELETE | ✅ | revoke Forge OS trust |
