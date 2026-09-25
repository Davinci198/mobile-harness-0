# Mobile Harness — background execution: diagnostic și plan

## Rezumat

- Există **doar două Android `Service` classes**, ambele declarate și pornite ca foreground services: `RuntimeExecutionService` și `RuntimeSetupService`, ambele cu `foregroundServiceType="specialUse"` (`app/src/main/AndroidManifest.xml:34-49`). Nu există alte servicii, inclusiv servicii pentru terminal sau Studio.
- Pentru Agent Execution-urile normale de chat — Claude, DSH, OpenCode, Hermes și Antigravity — codul **încearcă deja să pornească `RuntimeExecutionService` înainte de spawn-ul PRoot**. Așadar, afirmația „FGS nu rulează deloc în timpul unui headless task” este falsă pentru calea normală `HeadlessCliBridge`; limita reală este că FGS-ul este doar un companion, nu owner-ul procesului, și multe alte căi care apelează direct `RuntimeInstaller.process()` nu au FGS.
- `RuntimeExecutionService` ține un `PARTIAL_WAKE_LOCK` cu timeout de **90 de minute**, iar setupul unul de **45 de minute** (`RuntimeExecutionService.kt:133-142,168`; `RuntimeSetupService.kt:345-355,374`). Nu există `WifiLock`, `NetworkLock`, WorkManager, JobScheduler, AlarmManager, boot receiver sau restart receiver.
- PR #20 **nu adaugă un mecanism de keep-alive**. `dd7343b` adaugă permission-ul și dialogul package-specific pentru battery-optimization exemption; `79d29aa` adaugă doar un status UI derivat din `PowerManager.isIgnoringBatteryOptimizations()`; `de620e6` doar importă `PocketGreen`. Niciun commit nu schimbă service type, FGS lifecycle, WakeLock, process priority sau spawn behavior.
- Procesele guest sunt copii `fork()` + `execve(libproot.so)` ai procesului Android, în același UID/cgroup, nu servicii Android separate și nu procese `isolatedProcess` (`pocket_spawn.c:65-105`; `RuntimeInstaller.kt:1688-1749`). Activity, ViewModel, ambele servicii și JNI-ul trăiesc în procesul aplicației principal.
- Pierderea focus-ului **nu omoară explicit procesul**: `MainActivity` nu are `onPause/onStop/onDestroy` care să facă cleanup (`MainActivity.kt:14-26`), iar bridge-urile intră în `NonCancellable`. Totuși, procesul poate fi oprit de Android/OEM sau poate ajunge în cached/frozen state când nu are componentă activă.
- Diagnosticul trebuie formulat cu precizie: **Cached Apps Freezer este cauza probabilă pentru procesele pornite fără FGS**, dar, dacă FGS-ul este efectiv activ, nu este cauza pe AOSP pentru un Headless normal, fiindcă procesul nu este cached și App Standby consideră un long-running FGS drept active. Pentru screen lock, explicațiile mai plauzibile sunt **Doze / Low Power Standby / OEM network restriction**; acestea pot deconecta rețeaua și pot ignora WakeLock chiar și cu FGS.
- Exit `130` nu este rezultatul unui Android process kill. `pocket_spawn.c:127-136` transformă un process oprit prin signal în `128 + signal`; `130` înseamnă `SIGINT`. Cache freeze ar suspenda procesul până la unfreeze, iar LMK/OEM kill l-ar elimina, de regulă, fără ca agentul să emită propriul `result.exit_code=130`. Fereastra de 60–90 secunde este, după cod, un timeout/recovery al CLI-ului după rețeau pierdută, nu un timer MH de 60–90 secunde.
- Planul minim recomandat este: centralizarea lifecycle-ului în `RuntimeExecutionService` cu `sessionId` și reference counting; păstrarea `specialUse` pentru coding task; `ServiceCompat.startForeground(..., TYPE_SPECIAL_USE)` pe API 34+; un singur service-owned `PARTIAL_WAKE_LOCK` cât timp există task activ; pornire FGS confirmată înainte de spawn; battery-exemption prompt îmbunătățit; și acoperirea căilor guest fără FGS. Nu trebuie schimbat în `connectedDevice` sau `dataSync` doar pentru că agentul folosește internet.

## Tabel mecanisme existente

Legenda: **Standard** = mecanism user-visible, declanșat de utilizator și oprit la final; **Agresiv** = ține CPU/network-ul sau modifică restricțiile sistemului; **Absent** = căutare fără implementare în checkout.

| Mecanism | File:line | Standard / agresiv | Trigger | Notițe |
|---|---|---|---|---|
| `RuntimeExecutionService`, FGS `specialUse` | `app/src/main/AndroidManifest.xml:34-41`; implementare `runtime/RuntimeExecutionService.kt:22-81` | Standard, cu risc de review pentru `specialUse` | La fiecare Claude/DSH/OpenCode/Hermes/Antigravity task normal și la GitHub device login | Rulează în procesul principal; nu deține `Process`, PID sau coroutine. Notification continuă ID 41 cu progress + `Stop task` (`RuntimeExecutionService.kt:84-103`), rezultat ID 42 (`:106-122`). |
| Pornirea FGS pentru Claude | `runtime/ClaudeRuntimeBridge.kt:128-175,704-710` | Standard | User trimite prompt în chat | Se apelează înainte de `RuntimeInstaller.process()`. |
| Pornirea FGS pentru DSH | `runtime/DshRuntimeBridge.kt:87-123,533-540` | Standard | User trimite prompt DSH | Aceeași ordine: stop callback → FGS → guest process. |
| Pornirea FGS pentru OpenCode/Hermes | `runtime/HeadlessCliBridge.kt:120-168,495-502` | Standard | User trimite prompt către OpenCode/Hermes | FGS este pornit la `:133`, înainte de spawn la `:159`; un exception la start intră în failure path și procesul nu este pornit. |
| Pornirea FGS pentru Antigravity | `runtime/AntigravityRuntimeBridge.kt:278-299,409-416` | Standard | Task Antigravity normal | OAuth/probe/model scan folosesc alte căi și **nu** pornesc automat FGS. |
| GitHub login reutilizează execution FGS | `ui/MainViewModel.kt:2583-2649,2846-2866` | Standard | User apasă GitHub sign-in | Pornește procesul PRoot din ViewModel, dar primește FGS cu `canStop=false`; la final trimite `ACTION_CANCELLED`, fără rezultat distinct. |
| `RuntimeSetupService`, FGS `specialUse` | `AndroidManifest.xml:42-49`; `runtime/RuntimeSetupService.kt:233-285` | Standard | Install/repair explicit al runtime-ului sau snapshot păstrat `RUNNING` | De această dată service-ul deține efectiv installer coroutine. Notification continuă ID 51 cu progress + `Stop setup` (`:295-320`), rezultat ID 52 (`:322-336`). |
| WakeLock coding task | `runtime/RuntimeExecutionService.kt:133-142,168` | Agresiv, dar service-owned și user-visible | La `ACTION_START` | `PARTIAL_WAKE_LOCK`, tag `com.jarves.mh:active-coding-task`, timeout 90 min. Timeout-ul expiră silent și nu oprește FGS/task-ul. |
| WakeLock setup | `runtime/RuntimeSetupService.kt:345-355,374` | Agresiv, dar service-owned și user-visible | La pornirea setup-ului | `PARTIAL_WAKE_LOCK`, tag `com.jarves.mh:runtime-setup`, timeout 45 min. |
| `WifiLock` / `NetworkLock` | Manifest permissions: `AndroidManifest.xml:3-10`; căutare repo-wide fără match | Absent | — | Nu există `WifiManager.WifiLock`, `createWifiLock` sau `NetworkLock`. Nu ar rezolva Doze și n-ar fi recomandat implicit. |
| `View.keepScreenOn` | `ui/PocketDevApp.kt:1484-1487,1621-1627,4511-4517` | Standard, dar scade după ieșirea din Compose | Setup/loading/chat | Ține ecranul pornit numai cât timp UI-ul este vizibil; nu este background protection și este eliminat pe `onDispose`. |
| Battery-optimization permission | `AndroidManifest.xml:9` | Agresiv la nivel de system policy | Declarat la install | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permite dialogul, nu acordă exemption automat. |
| Battery-exemption wizard | `ui/PocketDevApp.kt:490-712` | Agresiv, opțional | First-run; user poate skip | Verifică `isIgnoringBatteryOptimizations()` (`:505`) și deschide package-specific request (`:666-680`). Nu blochează pornirea task-ului. |
| PR #20 „Background execution” | `ui/SettingsScreenModern.kt:148-173,258-285`; color import `:110` | UI standard, fără control runtime | Deschiderea Settings și revenirea din Settings | State local Compose = battery allowlist status. Nu pornește/oprește FGS, nu schimbă preference, proces sau prioritate. Textul „Active for coding tasks” este mai puternic decât realitatea. |
| `backgroundSetupComplete` | `data/AppPreferences.kt:34-36`; state `MainViewModel.kt:149-160,328-335`; write `:1509-1512`; routing `PocketDevApp.kt:271-276,328-333` | Standard, onboarding | User termină sau skip-ează wizard | Marchează doar faptul că wizard-ul a fost închis. Nu reține notification/battery grant și nu activează mecanisme. |
| Process spawn PRoot | `runtime/RuntimeInstaller.kt:1663-1749`; `NativeSpawnProcess.kt:54-90`; `cpp/pocket_spawn.c:65-124` | Standard tehnic, nesurvivabil implicit | Orice apel `RuntimeInstaller.process()` | `fork()`, process group nou, `execve(libproot.so)`. `RuntimeInstaller.process()` nu pornește FGS; survival depinde de caller. |
| NonCancellable în bridges | `HeadlessCliBridge.kt:83-90`; echivalente Claude/DSH/Antigravity | Agresiv pentru cancellation | Orice normal task | Agentul nu este oprit prin simpla cancelare a `viewModelScope`, dar nu reprezintă ownership FGS și nu supraviețuiește unui process kill. |
| Activity lifecycle | `MainActivity.kt:14-26` | Standard | Pause/stop/destroy | Nu există callbackuri de oprire. `collectAsStateWithLifecycle` (:20) oprește doar colectarea UI. |
| Local OpenAI proxy | `runtime/LocalOpenAiProxy.kt:24-59,99-129,226-229`; wiring `HeadlessCliBridge.kt:142-150` | Standard, parțial protector | OpenCode/Hermes cu NVIDIA/custom OpenAI route | Mută TLS-ul upstream în procesul app și lasă guest-ul să folosească loopback. Nu ajută DNS-ul guestului și nici provider-ii direct; procesul app rămâne subject power policy. |
| Orphan sweep | `RuntimeInstaller.kt:1935-1984`; apel `HeadlessCliBridge.kt:139-141,175-178,302-311` | Standard cleanup | Înainte/după headless run și stop | Curăță doar ppid=1 și cmdline markers. Nu menține procesul viu. |
| WorkManager / JobScheduler / AlarmManager | Manifest + dependencies + căutare repo-wide: 0 match | Absent | — | Nu există mecanism care să reanunțeze sau să rezume taskuri după proces kill/boot. |
| `BOOT_COMPLETED` / package replacement | `AndroidManifest.xml:59-61`; căutare: 0 match | Absent | — | Receiverul existent este exclusiv pentru rezultatul Android package installer, fără intent filter. |
| Service restart semantics | `RuntimeExecutionService.kt:81`; `RuntimeSetupService.kt:246-285` | Standard, recovery limitat | Service system kill | Coding task: `START_NOT_STICKY`. Setup: `START_REDELIVER_INTENT`; nu garantează restart și nu rulează după boot. |
| `onCachedAppFreezer`, `onTrimMemory`, process lifecycle | Manifest fără custom `Application`; căutare: 0 match | Absent | — | App nu observă freeze-ul și nu face cleanup. Un callback `onCachedAppFreezer` nu ar fi soluția de keep-alive; ar veni tardiv, când procesul este deja cached. |

## De ce moare guest-ul la focus loss (diagnostic cu dovezi din cod)

### 1. Ce nu este oprit de cod la pierderea focus-ului

`MainActivity` implementează numai `onCreate()` (`MainActivity.kt:14-26`). Nu există `onPause`, `onStop`, `onDestroy` sau `onSaveInstanceState` care să apeleze bridge-ul. În plus, coroutine body-ul fiecărui bridge este `withContext(Dispatchers.IO + NonCancellable)`; exemplul headless este `HeadlessCliBridge.kt:83-90`.

Rezultă următoarea chains:

```text
Activity loses focus
  -> UI state collection stops temporarily
  -> MainViewModel / bridge coroutine remains
  -> no explicit Process.destroy()
  -> guest may continue
```

Focus loss **nu este, prin urmare, un stop request**. Dacă procesul dispare brusc, cauza este Android/OEM sau un failure path din altă parte, nu `Activity.onStop()`.

### 2. FGS coverage real: „întotdeauna” este fals

Pentru calea normală de chat, FGS este cerut înainte de spawn:

| Cale | FGS înainte de spawn? | Status |
|---|---:|---|
| Claude task | Da, `ClaudeRuntimeBridge.kt:128-175` | Protejat dacă `startForeground()` este acceptat |
| DSH task | Da, `DshRuntimeBridge.kt:87-123` | Protejat dacă `startForeground()` este acceptat |
| OpenCode/Hermes headless | Da, `HeadlessCliBridge.kt:120-168` | Protejat dacă `startForeground()` este acceptat |
| Antigravity task | Da, `AntigravityRuntimeBridge.kt:278-299` | Protejat dacă `startForeground()` este acceptat |
| Runtime setup | Da; service-ul deține installer coroutine | Protejat |
| GitHub device login | Da, dar procesul rămâne în ViewModel | Protejat |
| Agent/dev-stack install/update | Nu, din `MainViewModel` | Cached/OEM-sensitive |
| Android build / Git clone / legacy terminal | Nu, din `MainViewModel` | Cached/OEM-sensitive |
| PTY terminal | Nu; registry top-level este deliberat persistent (`PtyTerminalScreen.kt:89-110,255-305`) | Cached/OEM-sensitive |
| Studio/Ekko server | Nu; pornit direct din `StudioServerManager.kt:39-84` | Cached/OEM-sensitive |
| Antigravity OAuth/probe/model scan | Nu | Cached/OEM-sensitive |

Așadar, `RuntimeInstaller.process()` nu este un execution boundary. Un caller poate crea exact același guest proces, cu aceeași rețea și aceleași riscuri, fără FGS și fără WakeLock.

### 3. PR #20 — ce controlează exact

Reconstrucția commiturilor este următoarea:

1. **`dd7343b` — fix/background app settings / PR #19**
   - adaugă `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` în `AndroidManifest.xml`;
   - schimbă intentul din lista globală în `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` cu `package:com.jarves.mh`;
   - păstrează app-details ca fallback.
   - Nu adaugă FGS, nu schimbă tipul și nu acordă exemption.

2. **`79d29aa` — show background execution status in settings / feature în PR #20**
   - adaugă secțiunea Settings și un `remember` local bazat pe `isIgnoringBatteryOptimizations()` (`SettingsScreenModern.kt:148-173,258-285`);
   - butonul doar deschide Settings;
   - nu există preference, ViewModel state, toggle sau effect asupra task-ului.

3. **`de620e6` — import background status color**
   - singura schimbare este importul `PocketGreen` în `SettingsScreenModern.kt:110`, necesar pentru referința de la statusul colorat (`:266-271`);
   - nu schimbă notificarea, FGS sau statusul real.

4. **`c6c9b8a`**
   - merge-ul PR #20; nu adaugă logică suplimentară.

Practic, feature-ul înseamnă: **„package-ul este pe Android battery allowlist?”**. Nu înseamnă: „există FGS?”, „CPU-ul rămâne awake?”, „rețeaua este permisă?”, „task-ul rulează?” sau „procesul este protejat de freezer?”. Statusul verde poate exista fără task, iar statusul roșu poate exista în timp ce un FGS este activ.

### 4. Cached Apps Freezer: unde este cauza probabilă și unde nu poate fi confirmată doar din cod

Android Cached Apps Freezer:

- oprește execuția proceselor **cached**;
- este relevant din Android 11 / API 30;
- procesul cu FGS este user-perceptible, nu cached;
- un proces cu `onCachedAppFreezer` absent nu înseamnă automat că este frozen — handlerul nu controlează freeze-ul, doar îl observă.

Pentru căi precum PTY, Studio, install/update și build, care nu pornesc FGS, pierderea Activity-ului poate permite intrarea procesului principal în cached state și apoi freeze. Copiii PRoot nu sunt Android components, dar sunt descendenți ai aceluși proces/UID și nu primesc un tratament de survivability independent. Pe AOSP, Cached Apps Freezer este explicația pentru oprirea imediată a CPU-ului în aceste căi.

Pentru **Headless normal însă**, `RuntimeExecutionService.startForeground()` este apelat înainte de spawn. Dacă acel apel a avut efect, procesul nu trebuie clasificat cached. Prin urmare:

> Pentru un task OpenCode/Hermes pornit exact prin `HeadlessCliBridge.startSession()`, Cached Apps Freezer nu poate fi declarat cauza pe baza codului AOSP. Fie FGS-ul nu a fost efectiv promovat, fie este implicată o excepție/OEM policy sau o altă restricție de rețea.

Captura necesară pentru această disambiguare este `dumpsys activity services com.jarves.mh` în timpul taskului, nu un simplu checkmark din Settings.

### 5. App Standby, Doze, Low Power Standby și OEM

**App Standby** limitează jobs/alarms/network pentru app-uri inactive. Un long-running FGS ține app-ul în bucket-ul active, deci App Standby standard nu ar trebui să oprească un Headless normal cu FGS efectiv.

**Doze** suspendă network access și ignoră WakeLock când device-ul este unplugged, screen-off și stationary. **Low Power Standby**, pe device-urile noi, poate dezactiva network-ul și ignora WakeLock chiar și pentru procese cu FGS, în afara maintenance windows.

Asta explică de ce soluția actuală nu este suficientă:

- FGS semnalizează importanță și user-visible work; nu garantează CPU-ul pornit și nu garantează network-ul.
- WakeLock-ul este ignorat în Doze/Low Power Standby.
- Battery allowlist este parțial: permite network și WakeLock în Doze/App Standby, dar nu este o scută împotriva memory pressure, task-manager stop, force-stop, reboot sau OEM kill.
- `svc power stayon true` păstrează device-ul awake/interactiv în test, deci nu exercitează exact aceeași policy ca screen-off real.
- MH nu monitorizează `ACTION_DEVICE_IDLE_MODE_CHANGED`, App Standby bucket, Low Power Standby, `ActivityManager.isBackgroundRestricted()` sau OEM battery manager.

Pe NotificationShade, pe un device AOSP compliant, simpla pierdere de focus nu ar trebui să intre în Doze. Dacă reprodusul este imediat și pe Motorola/OEM testat, ordinea suspects este:

1. procesul/taskul respectiv nu avea FGS efectiv;
2. OEM network/power restriction aplicată UID-ului;
3. vendor process freezer mai agresiv decât AOSP;
4. bug DNS/PRoot/networking declanșat de suspendarea conexiunii.

### 6. Exit 130 și timeline-ul de 60–90 secunde

`pocket_spawn.c:131-136` întoarce:

- exit code normal dacă procesul a fost terminat cu `WIFEXITED`;
- `128 + WTERMSIG(status)` dacă a fost omorât prin signal.

Astfel, `130 = 128 + SIGINT(2)`. Bridge-ul headless nu are timer de 60–90 secunde; singurul timeout relevant din MH este WakeLock-ul de 90 **minute**. Proxy-ul are read timeout de 180 secunde (`LocalOpenAiProxy.kt:106-110`).

Prin urmare, chain-ul compatibil cu simptomul este:

```text
focus loss / screen lock
  -> Android or OEM suspends guest networking or freezes an unprotected process
  -> DNS/socket/provider request stalls
  -> guest agent's own retry/recovery expires
  -> agent emits an interrupt/result with exit_code 130
```

Un Cached Apps Freeze propriu-zis nu ar genera un exit normal cu `130`: ar îngheața execuția și ar relua la unfreeze. Un LMK/OEM SIGKILL ar produce de regulă dispariția procesului / status 137, nu un result CLI 130.

### 7. De ce procesul poate dispărea fără Activity cleanup

`RuntimeExecutionService` nu conține PID, `Process`, bridge, session ID sau binder; are doar metadata și un callback global (`RuntimeExecutionService.kt:14-27,150`). `onDestroy()` release-ează doar WakeLock (`:145-147`).

Mai mult:

- `RuntimeExecutionService` este `START_NOT_STICKY` (`:81`);
- task state activ nu este persistat și reconstruit;
- `MainViewModel` nu are `onCleared()` care să salveze/reia procesul;
- Android poate opri procesul la memorie sau OEM poate ignora importanța FGS;
- PRoot wrapper poate muri și lăsa guest children reparented la PID 1, de unde apare sweep-ul narrow de la `RuntimeInstaller.kt:1935-1984`.

Aceasta este limita fundamentală: FGS + WakeLock reduc probabilitatea și impactul throttling-ului, dar nu oferă „immortality”. Force-stop, user Task Manager stop, reboot, storage failure sau OEM kill rămân terminale.

## Plan de modificări (prioritizat)

| Schimbare | Fișiere și anchor-e | Efort | Risc / rezultat |
|---|---|---:|---|
| **0. Capture obligatorie înainte de code change** | Fără modificare repo: `adb shell dumpsys activity services com.jarves.mh`, `dumpsys power`, `dumpsys deviceidle whitelist`, App Standby/Low Power Standby și logcat în timpul unui task Headless | 0.5 zi | Foarte mic. Distinge FGS absent, FGS activ, allowlist și OEM throttling. Nu este necesar pentru design, dar evită repararea mecanismului greșit. |
| **1. Centralizare „always-on during active sessions”** | `runtime/RuntimeExecutionService.kt:14-20,34-81`; `HeadlessCliBridge.kt:120-168,495-533`; `ClaudeRuntimeBridge.kt:128-175,704-743`; `DshRuntimeBridge.kt:87-123,533-571`; `AntigravityRuntimeBridge.kt:278-299,409-440` | 1–2 zile | Transformă execution service-ul dintr-un companion cu un singur global stop callback într-un registry cu `sessionId`, active count și per-session stop callback. FGS-ul se oprește doar când ultimul task real este terminated/cancelled/failed. Întrebarea de concurrency trebuie rezolvată explicit. |
| **2. Start FGS confirmat înainte de PRoot spawn** | `RuntimeExecutionService.kt:72-79`; helper-ul comun de start din bridges | 0.5–1 zi | `startForegroundService()` este async și, singur, nu confirmă `onStartCommand()` + `startForeground()`. Un acknowledgment local cu `sessionId`, completat numai după promotion reușit, permite bridge-ului să nu spawn-eze procesul unprotected sau să emită un error clar. Timeout scurt și tratament explicit pentru `ForegroundServiceStartNotAllowedException` / `SecurityException`. |
| **3. Tip FGS explicit și corect** | `AndroidManifest.xml:34-49`; `RuntimeExecutionService.kt:72-79`; documente Play `docs/play/FOREGROUND_SERVICE_DECLARATION.md:1-27` | 0.5 zi | Pentru coding task, păstrați `specialUse`. Pe API 34+ folosiți `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`, iar pe API inferioară tipul `0`. Nu folosiți `connectedDevice` pentru un cloud AI provider și nu folosiți `dataSync` doar pentru că agentul face HTTP. `RuntimeSetupService` poate rămâne `specialUse` în patch-ul minim; `dataSync`/UIDT este o optimizare semantică separată pentru download-ul runtime-ului. |
| **4. WakeLock service-owned, cât timp rulează agentul** | `RuntimeExecutionService.kt:133-168` | 0.5 zi | Înlocuiește timeout-ul rigid de 90 min cu un singur WakeLock acoperit de active-session count; release pe complete/fail/cancel și `onDestroy`. La terminal real, procesul este oprit normal; la `onDestroy` sau Task Manager stop, anulează explicit child processes înainte de release. WakeLock singur nu repară network-ul și poate fi ignorat de Doze/Low Power Standby. |
| **5. Refcount și ownership defensiv** | `RuntimeExecutionService.kt:14-20,34-81,106-148`; bridges' terminal paths | 1 zi | `ACTION_COMPLETE/FAILED/CANCELLED` trebuie să poarte `sessionId`; o sesiune nouă nu trebuie oprită de rezultatul uneia vechi. Notification poate afișa numărul de taskuri active. Pentru proces death, `ApplicationExitInfo`/logging poate ajuta diagnosticul, dar nu poate face resume transparent. |
| **6. Extinderea FGS la alte procese guest care trebuie să supraviețuiască** | `ui/PtyTerminalScreen.kt:89-110,255-305`; `runtime/StudioServerManager.kt:20-24,39-84,109-118`; `runtime/AntigravityAuthController.kt:47-80,225-239`; install/build paths în `ui/MainViewModel.kt:1552-1695,1831-1951` | 1–2 zile | Nu porni FGS global la app launch. Pornește execution FGS când user-ul creează PTY/Studio/login și oprește la explicit close. Pentru un server Studio mereu pornit, un FGS permanent ar avea cost și risc Play; decide dacă acesta este „user-visible ongoing work”. |
| **7. Battery-exemption flow robust, fără gate obligatoriu** | `ui/PocketDevApp.kt:490-712`; `ui/SettingsScreenModern.kt:148-173,258-285`; `app/build.gradle.kts:15-16,90-108` | 0.5–1 zi | Verifică `canRequestIgnoreBatteryOptimizations()` înainte de direct request; fallback la global battery list/app details. Pentru `IS_PLAY_BUILD`, evită dialogul direct până când există justificare Play explicită; sideload poate păstra package dialog. Textul UI trebuie să spună „Battery optimization: exempt”, nu „background execution active”. |
| **8. OEM auto-start/battery hints** | `ui/SettingsScreenModern.kt:148-173,258-285,522-536`; posibil `ui/PocketDevApp.kt:666-680` | 0.5–1 zi | Pentru Xiaomi/Redmi/POCO, Huawei/Honor și alte familii, afișează instrucțiuni pentru Auto-start/Background manager. Deschide un OEM screen doar dacă intentul rezolvă și activity-ul există; altfel trimite la App info. Nu folosi componente hard-coded necunoscute fără test pe fiecare ROM. |
| **9. Notification și copy corecte** | `RuntimeExecutionService.kt:84-122`; `PocketDevApp.kt:537-550`; `SettingsScreenModern.kt:258-285` | 0.25 zi | Elimnă default-ul „Claude Code is working” pentru DSH/OpenCode/Hermes/Antigravity. Afișează explicit battery/OEM warnings, dar nu promite imortalitate. Păstrează Open/Stop action. |
| **10. Tests și device matrix** | Noi/extensions lângă service și bridges; manual pe Android 11–16 | 1 zi | Teste pentru register/unregister, double finish, failure înainte de spawn, două sesiuni, notification permission denial, process recreation. Device matrix: foreground, Home, shade, 15 min screen-off, Doze, Low Power Standby, battery saver, allowlist on/off și OEM autostart off/on. |

### Secvență de implementare recomandată

1. Capture device state + teste manuale ale stării FGS.
2. Reference-counted session lifecycle în `RuntimeExecutionService`.
3. Promotion explicită cu acknowledgment înainte de spawn.
4. WakeLock legat de același count.
5. Extindere la PTY/Studio doar dacă sunt în scope-ul de survival cerut.
6. Battery/OEM UX și copy.
7. Test matrix și doar apoi decizia Play despre `specialUse`.

Nu este necesară adăugarea WorkManager, JobScheduler, AlarmManager sau BOOT_COMPLETED pentru acest caz. Acestea nu pot ține un proces interactiv viu și pot aduce restricții suplimentare.

### Alegerea FGS type

| Type | Potrivire pentru Mobile Harness | Decizie |
|---|---|---|
| `specialUse` | Nu există type standard pentru „user-started coding agent care rulează comenzi locale și poate dura ore”; manifestul și Play draft declară deja acest caz | **Recomandat pentru `RuntimeExecutionService`**, cu subtype precis și review |
| `dataSync` | Descrie transfer/procesare user-initiated; ar putea acoperi descărcarea rootfs-ului, dar un coding task nu este doar sync și poate depăși limita Android 15 | Nu pentru coding service; posibil pentru setup, separat |
| `connectedDevice` | Necesită o relație cu Bluetooth/NFC/IR/USB/network device și unul dintre prerequisites; un AI API remote nu este un companion device | Nu |
| `shortService` |aproximativ 3 minute, cu ANR după timeout | Nu |
| `mediaProcessing` | Doar media assets | Nu |
| UIDT `JobInfo.setUserInitiated()` | Potrivit pentru un transfer de date inițiat de user, nu pentru un agent arbitrar care editează code, rulează builds și păstreze conversație | Nu înlocuiește FGS-ul pentru coding task |

## Caveats versiuni Android (API 31/34/35+)

### API 28–30

- FGS și `PARTIAL_WAKE_LOCK` sunt disponibile.
- Cached Apps Freezer apare pe Android 11/API 30 și afectează procesele fără componentă activă.
- Direct APK-ul ține în prezent `targetSdk=28` (`app/build.gradle.kts:90-96`), dar comportamentul poate fi modificat de vendor power manager.

### API 31 / Android 12

- Pentru apps care target API 31+, FGS nu poate fi pornit din background în afara excepțiilor documentate; altfel apare `ForegroundServiceStartNotAllowedException`.
- MH pornește FGS ca reacție la Send/Install, cât app-ul este vizibil, deci fluxul normal este permis.
- Nu adăugați boot-based sau callback-based FGS start în această etapă.

### API 33 / Android 13

- `POST_NOTIFICATIONS` poate fi denied. FGS-ul nu trebuie tratat ca fiind complet oprit când notification permission lipsește, dar user vizibility și OEM behavior se schimbă.
- Notification permission flow trebuie să rămână explicit și poate deschide Settings după denial (`PocketDevApp.kt:647-665`).

### API 34 / Android 14

- Pentru target 34+, fiecare FGS trebuie să declare type și type-specific permission. Manifestul actual are `FOREGROUND_SERVICE_SPECIAL_USE` și subtype (`AndroidManifest.xml:6-7,34-49`).
- La promotion, type-ul trebuie să fie compatibil cu manifestul; recomandat `ServiceCompat.startForeground()` cu `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` pe API 34+.
- FGS trebuie să devină foreground în câteva secunde de la `startForegroundService()`.

### API 35 / Android 15 și Android 16

- `dataSync` și `mediaProcessing` au limite de aproximativ 6 ore/24h pentru target 35+, trackuite separat per type, cu `Service.onTimeout()`; implementarea actuală nu are `onTimeout`. Este un motiv suplimentar să nu mutăm coding task-ul în `dataSync`.
- `BOOT_COMPLETED` nu poate porni anumite FGS types pe target 35+. Nu există boot receiver în MH, deci planul nu introduce această problemă.
- Android 15 poate respinge network requests pornite în afara unui lifecycle valid; FGS este alternativa pentru user-visible ongoing task, dar acesta trebuie să fie efectiv activ.
- Android 16 aplică job quotas mai strict inclusiv când work-ul rulează cu FGS. Nu folosim WorkManager pentru această operație, deci impactul este mic.
- Low Power Standby poate deconecta network-ul și ignora WakeLock chiar și cu FGS. Battery exemption/OEM settings rămân necesare pentru experiența reală, dar nu reprezintă garanție.

### Play policy și target SDK

- Build-ul direct are target 28; build-ul cu `-PplayBuild=true` are target 36 (`app/build.gradle.kts:15-16,90-96`).
- Direct APK poate beneficia de comportamente legacy, dar nu de o garanție contra OEM-urilor.
- Pentru Play, `specialUse`, subtype description, notification video și user impact trebuie să fie coerente cu `docs/play/FOREGROUND_SERVICE_DECLARATION.md:1-27`.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` este policy-sensitive. Google Play permite request direct numai când core function este afectat și justificarea este validă; altfel preferați global settings/app info.
- Google a început în 2026 treatment pentru excessive partial wake locks. Un lock nemărginit peste task-uri lungi poate produce warnings sau reduceri de discovery; el trebuie să fie user-visible, legat strict de task și eliberat impecabil.

Surse oficiale folosite pentru caveat-uri:

- FGS types: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- FGS background-start restrictions: <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- FGS timeouts: <https://developer.android.com/develop/background-work/services/fgs/timeout>
- Doze/App Standby: <https://developer.android.com/training/monitoring-device-state/doze-standby>
- Cached Apps Freezer: <https://source.android.com/docs/core/perf/cached-apps-freezer>
- Play FGS declaration: <https://support.google.com/googleplay/android-developer/answer/13392821?hl=en>
- 2026 wake-lock quality enforcement: <https://developer.android.com/blog/posts/battery-technical-quality-enforcement-is-here-how-to-optimize-common-wake-lock-use-cases>
- UIDT: <https://developer.android.com/develop/background-work/background-tasks/uidt>

## Întrebări deschise

1. Failing-ul este sigur un task normal `HeadlessCliBridge`, sau include PTY, Studio, OAuth, install sau Android build prin `RuntimeInstaller.process()`? Răspunsul schimbă diagnosticul Cached Apps Freezer.
2. Pe device-ul de test, `RuntimeExecutionService` este raportat activ de `dumpsys activity services` în momentul când DNS-ul moare? Dacă da, Cached Apps Freezer standard trebuie exclus.
3. Ce API/OEM/build este folosit: Android 11–16, Motorola sau alt vendor, battery allowlist granted sau nu, Battery Saver/Low Power Standby on/off?
4. MH trebuie să suporte taskuri de peste 90 minute sau peste 2 ore cu screen off? Aceasta decide dacă un WakeLock fără timeout este acceptabil sau trebuie un UX explicit și limite.
5. Este garantat un singur Agent Execution activ sau pot exista simultan mai multe proiecte/agenți? Codul actual are un singur `RuntimeTaskController.stopAction`, deci concurrency trebuie fie interzisă explicit, fie izolată per session.
6. Trebuie ca PTY și Studio Server să rămână active după ce user-ul închide ecranul, sau survival este cerut numai pentru Chat Agent Execution? Un FGS permanent pentru Studio ar fi un produs și policy decision diferit.
7. După process death, reboot, force-stop sau user Task Manager stop, aplicația trebuie să reia automat task-ul, sau este suficient să îl marcheze clar ca interrupted și să ofere retry? Resume transparent nu este garantat de Android.
8. Publicarea Google Play este un obiectiv real? Dacă da, trebuie acceptat riscul de review pentru `specialUse` și un caz scris pentru battery exemption înainte de a păstra dialogul direct.
9. Ce comportament dorit pentru notificare când `POST_NOTIFICATIONS` este denied: allow FGS cu warning și Task Manager affordance, sau refuz să pornească task-ul?

Notă de trasabilitate: relevance scan a verificat `CONTEXT.md`, `docs/play/`, `README.md`, `PLAN-TERMINAL.md` și `.planning/`; nu exista un raport anterior despre acest subject, iar mecanismele din acest document au fost extinse, nu recreate în repo.
