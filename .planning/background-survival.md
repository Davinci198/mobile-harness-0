# Plan: feat/background-survival — MH să stea viu în background

Branch: `feat/background-survival` (din `main` @ 73136ea)
Stare: **IMPLEMENTAT** (2026-09-25) — vezi „Implementare livrată" mai jos.
Date: 2026-09-25
Sursă: raport complet `.planning/background-survival-report.md` (investigație
delegată pe lane `space`, verificată file:line împotriva repo-ului).

## Diagnostic (pe scurt)

- Toate cele 5 bridge-uri pornesc DEJA `RuntimeExecutionService` (FGS `specialUse`)
  înainte de spawn PRoot, cu PARTIAL_WAKE_LOCK 90 min.
- Căi FĂRĂ FGS (proces → Cached Apps Freezer la focus loss): PTY terminal,
  Studio server (`StudioServerManager`), install/build, Antigravity OAuth.
- Exit 130 = SIGINT de la recovery-ul propriu al agentului CLI după rețea moartă,
  NU kill Android (pocket_spawn.c face 128+signal).
- Chiar și cu FGS activ: Doze / Low Power Standby / OEM (Motorola) pot tăia
  rețeaua și pot ignora WakeLock.
- PR #20 (settings) e doar UI cosmetic — battery allowlist status, zero control runtime.
- Nu se adaugă WorkManager/JobScheduler/BOOT_COMPLETED — nu țin procesul viu.

## Pas 0 (ÎNAINTE de orice cod) — disambiguare pe device

În timpul unui task headless activ (ex. Hermes chat lung):
```bash
adb shell dumpsys activity services com.jarves.mh   # FGS efectiv activ?
adb shell dumpsys power                              # wakelock prezent?
adb shell dumpsys deviceidle whitelist | grep jarves # allowlist?
adb shell dumpsys deviceidle                         # Doze state
```
Dacă FGS apare activ când moare DNS → problema e OEM/Doze, nu codul nostru.

## Pași de implementare (prioritizați, din raport)

1. **Centralizare lifecycle** (`RuntimeExecutionService.kt`): registry cu
   `sessionId` + refcount; FGS-ul se oprește doar la ultimul task. Per-session
   stop callback, nu un singur global. Efort 1-2z.
2. **FGS acknowledgment înainte de spawn**: startForegroundService e async —
   confirmă promovarea efectivă (sessionId ack) sau eșuează cu mesaj clar
   înainte de `RuntimeInstaller.process()`. Efort 0.5-1z.
3. **Type explicit**: `ServiceCompat.startForeground(..., TYPE_SPECIAL_USE)` pe
   API 34+, `0` sub. Rămâne `specialUse` (NU dataSync — limită 6h pe Android 15).
   Efort 0.5z.
4. **WakeLock pe refcount** în loc de timeout fix 90 min; release la
   complete/fail/cancel + onDestroy. Efort 0.5z.
5. **Extindere FGS la PTY + Studio server** (decizie user: serverul Studio
   long-running merită FGS? implică notificare persistentă vizibilă). Efort 1-2z.
6. **Battery-exemption robust** (`PocketDevApp.kt:490-712`):
   `canRequestIgnoreBatteryOptimizations()` check + fallback app details;
   pe IS_PLAY_BUILD fără dialog direct. Copy: „Battery optimization: exempt",
   nu „background execution active". Efort 0.5-1z.
7. **OEM hints** (Motorola pe device-ul de test; Xiaomi/Huawei generic) în
   SettingsScreenModern. Efort 0.5-1z.
8. **Notificare corectă**: nu afișa „Claude Code is working" pentru celelalte
   agenți; arată numărul de task-uri active. Efort 0.25z.

## Dovadă device (2026-09-25, adb) — ce face ro-operit și MH nu

Comparație reală după închiderea ambelor app-uri de user:
- ro-operit: proces VIU (pid 447) — `FloatingChatService` START_STICKY +
  `onTaskRemoved` restart
- MH: proces MORT, zero servicii — `RuntimeExecutionService` e
  `START_NOT_STICKY` și nu are `onTaskRemoved`
- Ambii pe battery allowlist → allowlist nu e diferența

**Straturile lipsă în MH (adăugate la plan):**
- **S1. `START_STICKY`** pe `RuntimeExecutionService` (acum NOT_STICKY,
  `RuntimeExecutionService.kt:81`) — Android re-crează service-ul după kill.
- **S2. `onTaskRemoved()`** → `startForegroundService()` (NU startService ca
  ro-operit — ăla e bug-ul lor pe Android 12+). Supraviețuire la swipe.
- **S3. Service deține procesele**: RuntimeExecutionService ține PID-urile
  PRoot active; la service restart (STICKY), readoptă orfanii (ppid=1 cu
  marker, NU-i omoară ca sweep-ul actual) și marchează task-urile
  „interrupted — agent alive, reopen to resume

- Studio server: FGS permanent (notificare persistentă) sau on-demand doar în tab?
- Task-uri > 90 min cu screen off: acceptăm WakeLock fără timeout sau UX cu limită?
- Play publicare reală? (decide riscul `specialUse` review + battery dialog policy)

## Non-obiective

- Fără WorkManager/AlarmManager/BOOT_COMPLETED pentru keepalive.
- Fără overlay 1×1 sau trucuri vendor (ro-operit are unul borderline — NU-l copiem).
- Fără resume transparent după process death (Android nu-l garantează; doar
  marcare „interrupted" + retry clar).

## Implementare livrată (2026-09-25)

| Strat | Ce s-a făcut |
|---|---|
| **S1. STICKY + refcount** | `RuntimeExecutionService` rescris: `START_STICKY` pe acțiunile non-terminale, registry `RuntimeTaskController` pe `sessionId` (refcount; un rezultat stale nu mai omoară un task mai nou), FGS + WakeLock atârnă de count, nu de un timeout fix. Pașii 1/3/4 din plan intră aici. |
| **S2. onTaskRemoved** | Repromovează FGS cu `startForegroundService` (corect pe Android 12+, spre deosebire de ro-operit) când există task activ; cu keepalive Studio, reanunță `ACTION_KEEPALIVE`. |
| **S3. Recovery după process death** | Nou: `RuntimeRecoveryState` — ledger pe disc (`filesDir/runtime-recovery-tasks.txt`) scris la begin/end sesiune. La STICKY restart (`intent == null`), service-ul îl consumă: arată notificare „agentul poate fi viu", armăază `RuntimeTaskController.recoveryActive` → `killGuestOrphans()` e oprit cu gardă ca să NU omoare agentul supraviețuitor. Task nou = takeover (sweep reactivat); „Stop task" = curățenie completă. `GuestOrphanScan` partajează markerele dintre sweep și recovery counting. |
| **S4. Keepalive Studio** | Când serverul Studio e READY, `StudioTab` trimite `ACTION_KEEPALIVE` → FGS „Mobile Harness Studio" + watchdog (ping `/health` la 30s, tolerant la boot lent; dă jos FGS-ul când serverul dispare). Buton „Stop Studio" în notificare (`ACTION_STOP_STUDIO`). La STICKY restart, serverul Studio supraviețuitor e readoptat automat. |
| **Bridge-uri (4)** | `HeadlessCliBridge`, `ClaudeRuntimeBridge`, `DshRuntimeBridge`, `AntigravityRuntimeBridge` trimit `EXTRA_SESSION_ID` la start + la finish/cancel — sesiunile concurente sunt independente. |
| **Teste** | `RuntimeExecutionServiceTest` (JUnit pur, fără Robolectric): refcount cu rezultate stale, ledger round-trip + linii malformate, markere orfani. |

Restă (din plan, neatinse aici): pasul 2 complet (acknowledgment async al promovării FGS înainte de spawn), pasul 6 (battery-exemption wizard robust), pasul 7 (OEM hints), pasul 8 (notificare cu numărul task-urilor).
