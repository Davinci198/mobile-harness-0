# Plan: feat/key-db-model-filter

Branch: `feat/key-db-model-filter` from `main` @ `4a483fe`
Date: 2026-09-23

## Goals
1. Room DB for API keys — AES-GCM field encryption, AndroidKeystore wrapping key, 100% local privacy
2. Batch model health scan (Kotlin port of `/storage/emulated/0/Operit/memorie-operit/modele/1test.py`)
3. Toggle: hide models that don't work
4. Auto-scan on first provider integration + manual "Test all models" button
5. All settings live in Agent menu; scan output visible as terminal-style log panel

## Part 1 — Room for keys
- `data/db/MhDatabase.kt`, `SecretEntity(providerId, iv, ciphertext)`, `SecretDao`
- `ApiKeyVault` keeps public API; reads/writes Room; lazy migrate from `pocket_secrets`
- Gradle: room-runtime, room-ktx, KSP; exclude DB from backup

## Part 2 — validateModels
- `ProviderApiClient.validateModels(baseUrl, key, protocol, models, onProgress)`
- Port of 1test.py: POST chat/completions, max_tokens 20, timeout 30s, concurrency 5
- `onProgress(modelId, status, latencyMs, httpCode)` streams to UI

## Part 3 — Prefs
- `provider_<agent>_broken_models` — JSON set of broken ids
- `provider_<agent>_hide_broken` — boolean toggle
- `provider_<agent>_auto_scan_done` — first-integration flag
- `provider_<agent>_auto_scan_enabled` — default true

## Part 4 — Agent UI
In AgentScreen provider/model sheet:
- Switch "Hide models that don't work"
- Switch "Auto-scan on first connect"
- Button "Test all models"
- `ModelScanTerminal` — black mono panel, green/red lines, auto-scroll (in-sheet, not project WorkspaceTab)

## Part 5 — Auto-scan
After first successful provider key save/validate for a provider kind: discover + validateModels once, set auto_scan_done.

## Do not touch
- Gateways, runtime bridges, NVIDIA key in MEMORY.md
- Known backlog (Claude 3s stdin, AGY install)

## Delivery
CI `dany-debug-apk` green → PR to mh0 → user review → merge to main
