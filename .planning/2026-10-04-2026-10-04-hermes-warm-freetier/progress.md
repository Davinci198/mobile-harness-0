# Progress Log

## Session: 2026-10-06

### Actions Taken
- GitHub verde: `gh workflow run` pe `88a9d7e` → run 37417414710 a PICAT la compile:
  `String.absolutePath.toPath()` nu există în `Link2SymlinkRepairTest` (2 cazuri de
  date au folosit `.absolutePath` pe String în loc de `File.toPath()`). Fix + commit `6aedfc4`.
- Rerun 37417923280 → compile trecut, dar 4 teste `Link2SymlinkRepairTest` picate cu
  `FileNotFoundException` (fișierele de test nu aveau părinți naturali — lipsea `mkdirs`)
  și `AssertionError` la materializarea shim. Rezultat:
  - criteriul vechi `target.startsWith("/data/")` era INCORECT și periculos: țintele
    reale `.l2s.*` stau LÂNGĂ shim, în rootfs, nu sub `/data`; iar potrivirea pe prefixul
    host ar fi copiat în guest și symlinkuri absolute legitime spre `/usr/bin/dash`.
  - Fix `5b08940`: se potrivesc doar numele `.l2s.*` (exact ce creează proot prin
    `--link2symlink`), nu prefixul de host. Fix `0dcf6ea`: rootfs-ul de test se creează o
    singură dată pe test (`by lazy`).
- CI `37419147486` pe `0dcf6ea` → VERDE. APK descărcat (artifact `mh-dany-debug`,
  APK 66.623.639 B, hash `d3e5b1fb…`), `adb install -r` Success pe `emulator-5554`.
- **Antigravity FIX VERIFICAT PE DEVICE**: la 08:47 apk nou + fallback-ul
  embedded→download a instalat `agy` (200 MB, fișier real executabil în
  `root/.local/bin/agy`, marker `.pocket-agy-version` = 1.1.27).
- **dpkg repair VERIFICAT**: baza 244/244 `install ok installed`, zero half-installed.
  `adb` (fostul eșec) instalează acum în `/usr/bin/adb`.
- **CAUZĂ RĂDĂCINĂ HERMES/PHP/JAVA găsită**: bundle-ul PYTHON
  `pocketdev-python-arm64-2026.09.2.tar.zst` e construit pe Ubuntu 20.04 și conține o
  glibc 2.31 completă (libc.so.6→libc-2.31.so, ld-2.31.so, libc.a, python3.8, pip).
  Extras peste rootfs-ul 26.04 a suprascris **107 fișiere de sistem** (libc 2.43→2.31,
  loader, libm, libstdc++, libz, libcrypto1.1, libcurl 4.6 etc.) și a re-îndreptat
  `/usr/bin/python3` spre Python 3.8 — exact de ce „instrumentele nu rămân instalate”.
  Nu există log apt/dpkg pe 10-06: core info, bundle-ul a fost extras la 08:50 împreună
  cu overlay-ul Android (gradle 08:50) — NU prin apt.
- Fix `OverlayCorePathGuardTest` + `isCoreSystemPathOverlayGuarded`:
  overlay-urile sunt doar aditive; nu mai pot înlocui multilib-ul de bază
  (`usr/lib/aarch64-linux-gnu/*`, `usr/lib/gcc/*`) sau python-ul implicit
  (`usr/bin/python3*`, `usr/bin/pip*`). Core-ul rămâne autoritativ.

## Session: 2026-10-06 (dsh 0.2.0-rc.2 migration — vezi AGENTS/task actual)

### Actions Taken
- Cauza buclei „același răspuns”: user updatase dsh la 0.2.0-rc.2, care
  redenumește `settings.yaml` → `settings.yaml.imported` și folosește suprafața
  Cordis home patch. Providerul custom nu mai era înregistrat → fallback
  `deepseek-official` → lipsă `DEEPSEEK_API_KEY` → `MISSING_CREDENTIAL`.
- Decizie (autonom, „sunt la strand”): bridge migrat la 0.2.0-rc.2 + self-heal
  permanent (`DSH_VERSION = "0.2.0-rc.2"` în RuntimeInstaller.kt; `ensureDshInstalled`
  face best-effort updateDsh la marker mismatch, inclusiv după overlay bundle 0.1.2).
- `writeDshSettings` scrie acum și `$DSH_HOME/cordis.patch.yml` (home patch) pentru
  rute custom (`llm-pi-ai` + providers), `deepseek-official` → `"[]\n"`. Funcțiile
  `yamlQuote`/`dshHomePatch` ⚠️ au fost accidental MEMBRII clasei (clasa se închidea
  la 626, funcțiile 385-411) → nu se vedeau din teste; MUTATE după `}`-ul clasei.
- Parser 0.2.0: `assistant/message` replay `data.stream` (text-chunks/reasoning-chunks/
  chunk), dedup `assistant/message` vs delte streamate (fix: `text == streamedTextSinceMessage`
  → Ignored); `tool/result` citește `message.toolCallId` + `error.reason`; `turn/end`
  adaugă kind `aborted`.
- **Build local deblocat pe Termux**: aapt2 oficial e x86-64, nu rul ează pe aarch64.
  Soluție: sysroot glibc x86-64 (debs Ubuntu: libc6/libstdc++6/libgcc-s1/zlib1g) la
  `~/9remote-uploads/opencode/tmp/kcp/sysroot/x86_64` + wrapper `aapt2` care rulează
  `qemu-x86_64 -L <sysroot> <aapt2> $@`; `~/.gradle/gradle.properties` →
  `android.aapt2FromMavenOverride=/tmp/kcp/aapt2`. AAPT2 sub qemu: 2.20-14304508 OK.
- Teste noi (DshBridgeTest.kt + DshHomePatchTest) scriu și trec local:
  stream compacted, reasoning-only, chunk raw, tool/result 0.2.0, aborted, max-tokens,
  custom patch + empty patch.

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| `testOfflineDebugUnitTest` (543 teste) | green pe modificările dsh-0.2.0 | 541 pass; 2 FAIL pre-existente | PARȚIAL |
| `rewriteGuestHostsIsIdempotentWithSamePin` | pass | FAIL și pe HEAD curat (stash) → ne-legat de modificări | PRE-EXISTENT |
| `downloaderWritesPartThenRenamesAndDoesNotOverwrite` | pass | PASS izolat pe HEAD; FAIL în suite complete (rețea) | FLAKY/ENV |
| `DshSdkProtocolParserTest` + `DshHomePatchTest` + others | pass | ALL PASS | PASS |
| AAPT2 sub qemu wrapper | `version` output | Android Asset Packaging Tool 2.20-14304508 | PASS |

### Errors
| Error | Resolution |
|-------|------------|
| `NoClassDefFoundError: kotlin/reflect/jvm/ReflectJvmMapping` (kotlinc standalone) | nu-i nevoie — Gradle compil ează corect; standalone abandonat |
| `Custom AAPT2 location does not point to an AAPT2 executable` | wrapper-ul trebuie să se termine cu numele `aapt2` (AGP verifică `endsWith(FN_AAPT2)`) |
| test compile: `Unresolved reference 'dshHomePatch'/'yamlQuote'` | funcțiile erau membre ale clasei (clasa se închide la linia 626, nu înainte); mutate după închiderea clasei → `DshRuntimeBridgeKt` gen erat |
| `--tests 'com.jarves.mh.runtime.DshBridgeTest'` → „No tests found” | fișierul nu conține clasă `DshBridgeTest`; clasele reale: `DshSdkProtocolParserTest`, `DshRouteMapperTest`, `AgentProviderPresetTest`, `DshHomePatchTest` |
| test `streamsTextDeltasAndDoesNotRepeatCompletedMessage` a picat | dedup `assistant/message` incorect: `text == streamedTextSinceMessage` trebuia → Ignored (nu doar clear-ul) |

### Next Step
Commit + push pe `ubuntu-26.04-base-migration` → CI (x86_64, rețea OK) rulează suitele
complet; apoi APK din artifacte CI, `adb install -r`, test real de turn dsh cu provider
`nvidia-nim` (home patch scris de app la pornire; pre-fix deja aplicat pe device la
`/data/user/0/com.jarves.mh/files/runtime/ubuntu/root/.dsh/cordis.patch.yml` și verificat
cu `dsh --profile sdk --dump-config`).

## Session: 2026-10-06 (afternoon — device repair + Hermes)

### Actions Taken
- **Overlay guard livrat** în `6266b1f` (extractZstdTar + predicat pure + teste). CI a
  prins bug: `python3-config` (cratimă) scăpa din `startsWith("python3.")`; fixat în
  `890d25d` cu `startsWith("python3")`. Run CI final: `37426951426`.
- **Device repaired**: `tar -xf core-libs.tar` (`usr/lib/aarch64-linux-gnu` pristine
  26.04) peste rootfs-ul device-ului → libc.so.6=1.788.240B, loader 201.872B, libm,
  libstdc++; `ln -sf python3.14 usr/bin/python3`; eliminat `libnss_nis(-plus)-2.31`
  + orfanii `*-2.31.so` (0 rămase).
- **Verificat în guest (proot)**: glibc 2.43-2ubuntu2.4, `python3` 3.14.4, node v24.19,
  npm 11.17, git 2.53, dpkg 1.23.7, agy 200MB — roata ieșită din „env: GLIBC_2.32”.
- **Hermes install**: primul run cu `--link2symlink` reușit la apt/update dar uv a picat
  pe cache-uri vechi de la încercarea cu libc stricat (`.l2s` dangling). După `rm -rf`
  cache+installs, același eșec A RĂMAS pe generație proaspătă: proot `--link2symlink`
  traduce link()-urile de dedup ale uv/pm-runtime în lanțuri `.l2s..l2s.<n>…` → EPERM.
- **Fără `--link2symlink` Hermes se instalează complet** (apt+uv+web UI+builds, main
  @ 3dadeb92). `HERMES_GUEST_PATH` lipsă (installer-ul pune `~/.local/bin/hermes`).
- Aplicativ fix `3639dbc`: `emulateHardLinks=false` la install + `ln -s
  $HOME/.local/bin/hermes -> /usr/local/bin/hermes`.
- **Device**: creat `/usr/local/bin/hermes` + `hermes2` (wrappers), marker
  `.pocket-hermes-version` = `2026.9.24`. `hermes --version` OK și cu `--link2symlink`
  (rulează ca în app).
- **Al doilea bug găsit**: `repairLink2symlinkArtifacts` ștergea TOATE `.l2s.*` din
  rootfs (walkTopDown), inclusiv sidecar-urile live din cache-ul uv → cache plin de
  link-uri dangling. Fix în `3639dbc`: sweep doar în bin dirs.

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| CI 6266b1f (overlay guard) | success | FAIL: python3-config | BLOCKED→fix |
| CI 890d25d | success | în curs | PENDING |
| Guest glibc după repair | 2.43 | Ubuntu GLIBC 2.43-2ubuntu2.4 | PASS |
| python3 / node / git / dpkg în guest | OK | 3.14.4 / v24.19 / 2.53 / 1.23.7 | PASS |
| Hermes install cu link2symlink | ok | EPERM la uv pm-runtime | FAIL |
| Hermes install fără link2symlink | ok | Install complete (main @ 3dadeb92) | PASS |
| `hermes --version` | ok | vgit.3dadeb9 (2026.9.24) | PASS |
| Marker `.pocket-hermes-version` | set | 2026.9.24 | PASS |
| `hermes --version` sub link2symlink | ok | rc=0 | PASS |

### Next Step
Când CI-ul e verde: descarcă APK-ul nou (av30 + 3639dbc + 890d25d), `adb install -r`,
redeschide app-ul → Hermes să se aplice (marker + wrapper deja pregătite). Report final.

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| CI 88a9d7e (prima încercare) | success | fail: `toPath()` pe String | BLOCKED→fix |
| CI 6aedfc4 | success | fail: FileNotFound + Assertion în test | BLOCKED→fix |
| CI 0dcf6ea | success | SUCCESS (Unit tests) | PASS |
| Antigravity pe device (av1) | instalat | 1.1.27, 200 MB real | PASS |
| dpkg state pe device | OK | 244/244 install ok installed | PASS |
| Python overlay peste rootfs 26.04 | fără regresie | regresează glibc 2.43→2.31 (107 fișiere) | FAIL → fix |
| Hermes pe device | instalat | `/usr/local/bin/hermes` lipsește; doar `~/.hermes/hermes-agent` git | FAIL |
| PHP/Java pe device | instalat | php: NO_PHP, java din overlay Android (JDK) | PARȚIAL |

### Errors
| Error | Resolution |
|-------|------------|
| CI: `Unresolved reference 'toPath'` pe `String.absolutePath` | fix `6aedfc4`: `dataTarget.toPath()` direct pe `File` |
| CI: FileNotFound în test la `writeText` | fix `0dcf6ea`: `by lazy` + `parentFile.mkdirs()` |
| CI: „shim must not stay a symlink” | fix `5b08940`: match pe `.l2s.*`, nu pe `/data/` prefix |
| `CANNOT LINK ... libtalloc.so not found` la proot manual | adaugă `LD_LIBRARY_PATH=<nativeLibDir>` |
| `execve("/usr/bin/env"): No such file or directory` la proot manual | lipsesc `PROOT_LOADER` + `PROOT_NO_SECCOMP` — aplicația le setează; manual trebuiau adăugate |
| glibc 2.31 în rootfs 26.04 | python overlay 20.04 suprascrie 107 fișiere; guarded în overlays, device repair pending |

### Notes
- Comenzile manuale proot merg cu
  `LD_LIBRARY_PATH=<lib> PROOT_LOADER=<lib>/libprootloader.so PROOT_NO_SECCOMP=1`.
- ACTIVE NEXT: (1) refine/CI pentru overlay guard, (2) repair device (restaurează 26.04
  libc/loader/python), (3) hermes: fie createw `/usr/local/bin/hermes` catre `~/.hermes`,
  fie reinstall.sh.

### Next Step
Adu guard-ul overlay (commit), rulează CI, apoi repară device-ul cu bibliotecile 26.04
pristine din bundle-ul core local și reverifică Hermes/PHP/Java/AGY.

### 2026-10-06 16:40 — dsh „tot nu merge": CAUZA RĂDĂCINĂ găsită + fix
- Eroare app: `DshBridge: fatal uncaught exception: Error: dsh: host preparation failed:
  No usable native binding found for node-addon-require-builtin-linux-arm64-gnu (auto)`.
- Cauză: loader-ul nativ al dsh (node-addon-native-custom-loader) își materializează binding-ul
  .node într-un cache în `os.tmpdir()` (`/tmp/node-addon-native-custom-loader-$uid/native-cache`)
  printr-un `fs.linkSync`. Sub PRoot `--link2symlink` linkul devine symlink `.l2s.*` care rămâne
  DĂRÂMAT după `rmSync(temp)`; albat apoi pe disc. Run-urile ulterioare fără `--link2symlink`
  (dsh folosește `emulateHardLinks=false`) citesc cache-ul otrăvit → require(dlopen) eșuează →
  „No usable native binding". Verificat empiric: `require("node-addon-require-builtin")` OK cu
  `--link2symlink` + cache `.l2s.` dărâmat; FAIL fără el; OK cu `NARB_DISABLE_NATIVE_CACHE=1` sau
  după `rm -rf /tmp/node-addon-native-custom-loader-*`.
- Fix: env `NARB_DISABLE_NATIVE_CACHE=1` în `DshRuntimeBridge.startSession` (încarcă direct din
  sursă, fără cache/hardlink dance) + curățat cache-ul stricat de pe device →
  retest imediat fără APK nou merge.

### 2026-10-06 17:35 — dsh FLOW COMPLET pe device (nolink) + fix „project_workspace"
- După fix-ul NARB (cache), initialize-ul dsh merge sub nolink (rc=0, serverInfo), iar un
  session/prompt rulează agentul complet → doar 401 AUTH (cheia loopback e falsă) → viața dsh 0.2.0 e OK.
- CONFIRMARE: working dir real al agentului = /workspace/<slug> (exact ce pasează app-ul).
- Problema „/workspace/clever-turing/project_workspace nu există" = ARTEFACT de prompt:
  contextul injectat de app folosea eticheta XML `<project_workspace>...`; modelul mic
  (llama-3.2-11b) a citit-o ca pe un FOLDER la `<cwd>/project_workspace`, a inventat o eroare
  de tool pe acel path fictiv și a căutat cu glob. Harness-ul dsh NU referențiază nicăieri
  `project_workspace` (grep în tot /usr/local/lib/dsh + /root → zero).
- Fix commit: rename `<project_workspace>` → `<project-context>` (deschis+închis) în cele 3
  bridge-uri (Dsh/Claude/Headless). CI verde (37503942051), APK reinstalat (Success). Branch temp șters.
- Caveat: conversația existentă „pong/glob narrative" e salvată de app ca istoric și se reinjectează;
  pentru un test curat de turn dsh: proiect NOU sau ștergere conversație.

### 2026-10-07 — DSH warm session (implementat, CI pending)
- Design (Option B): reutilizează PROCESUL dsh --profile sdk între turnuri; sessionId fresh
  per turn + contextPrompt complet (semantica identică cu azi), fără shutdown între turnuri.
  Boot-ul proot+node+dsh de 8-12s se plătește o singură dată (și în cazul Hermes-warm).
- `DshSdkChannel`: procesul + starea citirii persistate între turnuri (outputOffset, partial line,
  writer, initialized, turns); stdin rămâne deschis pentru session/prompt-urile următoare.
- `runSdkSession`: handshake `initialize` doar pentru proces proaspăt (awaitSdkInitialized, drenează
  tot bufferul); turn re-utilizat → direct `session/prompt`. Sfârșit de turn = Status(running=false)
  după sawRunning SAU Failed; drain 400ms liniște / 3s cap. `SDK_SHUTDOWN_TIMEOUT_MS` eliminat.
- Retry loop: calculează `dshWarmSignature(route, model, workspace, env)` (secretul = în env);
  `adoptChannel` re-utilizează dacă semnătura + isAlive + turns<25, altfel închide vechiul și pornește
  proaspăt (recycle pentru mapa `sessions` din sdkserver, care crește per turn). Eșec → `closeWarmChannel`
  (shutdown best-effort + destroy în thread) și retry curat; `userStopRequested` → throw imediat.
- Success nu mai depinde de exit code-ul procesului (procesul rămâne viu).
- Prewarm: `RuntimeBridge.prewarmSession` default no-op; `HeadlessCliBridge.prewarmSession` → override;
  `AgentWork.prewarm` nu mai face cast `as? HeadlessCliBridge`; `DshRuntimeBridge.prewarmSession`
  pornește canalul + handshake (best-effort). Se declanșează tot la provider save.
- Teste unitare: `DshWarmSignatureTest` (reuse dacă aceleași inputuri; fresh dacă model/proiect/
  cheie/route diferă), `staleSessionEventsFromAPreviousTurnAreIgnored` (parser filtrează tail-ul
  sesiunii vechi din același captur-file).
- Fără build local (cerința user). Validarea = CI `dany-debug-apk` + instalare APK + măsurat.

### 2026-10-07/08 — warm session VERIFICAT pe device + fix „raspuns duplicat" (root-cause parser)
- Warm reuse CONFIRMAT pe moto g57 (emulator-5554): ambele turnuri din chat-ul de test
  (project 759ba410, conv ae1c8a24) au rulat în ACELAȘI proces dsh — log
  `cache/runtime-output-26403000897009.log` conține turn 1 (sessionId efcde6c2) și turn 2
  (e3eaa3cd) cu `session.status idle` între ele și FĂRĂ `initialize(id=1)` la turnul 2.
  Turn 1 workedMillis 14839 (a plătit boot-ul), turn 2 35831 (răspuns lung ~1400 chars,
  dominat de generare — nu e măsură concludentă pentru câștigul warm, dar reutilizarea e
  dovedită de procesul comun).
- User: „raspunde greu dar e bine" — latency neconcludentă pe chat-ul ăsta (răspuns lung);
  câștigul real = fără boot la turnurile 2+.
- BUG „raspuns duplicat": textul apărea de 2× în chat. ROOT CAUSE = în parser, NU la
  warm-session și NU la model: `parseAssistantStream` face blockId din `record.optInt("index")`
  (indexul wrapper-ului), dar pentru recordurile `chunk` index-ul e ÎN INTERIOR
  (`chunk.index`). Deci block-end primea blockId = turn+step (fără index), nu găsea textul
  acumulat de text-chunks (keyed turn+step+index) → `missingSuffix` = tot mesajul → re-append.
  Pre-existent (același bug și pe build retry-overload 2e4974f la 18:40Z).
- Simulare Python pe logul real: fără fix `"Salut!...😊Salut!...😊"`, cu fix `"Salut!...😊"`.
- Fix commit `ce47d5a`: blockId la `chunk` = `turn + step + chunkRecord.optInt("index", 0)`;
  test regresie `blockEndKeyedByNestedIndexDoesNotRepeatTheMessage` (imitează stream-ul real:
  text-chunks top-level index + chunk block-end cu index imbricat, expect text o singură dată).
- CI: branch run `37692772481` VERDE (unit tests incl. noul test), main run `37693810545` VERDE.
  APK `~/9remote-uploads/opencode/apk-dup-fix/app-online-debug.apk` → `adb install -r` Success →
  force-stop + relaunch → user confirmă „o singură dată".
- Merged ff `26c8fb0..ce47d5a` → `mh0/main`; branch fix șters local+remote. Local main = mh0/main.
- Evidence sub `~/9remote-uploads/opencode/warm-verify/` (warm-output.log, t1/t2.jsonl,
  agent-loop.js, sdk-jsonrpc.js, api-client.js).

### 2026-10-08 — warm LATENCY măsurat (confirmare finală)
- Conversație nouă d3f5cd3d/ecf70733 pe APK cu fix duplicat: 6 turnuri, UN proces dsh
  (log runtime-output-28274239126243.log: 0× `initialize` id=1, 6 sessionIds distincte).
- workedMillis: turn1 „salut răspunde în română" 13,900ms (boot+model, 35ch); turn4 „salut"
  2,230ms (25ch); turn5 „bine" 3,074ms; turn6 „ce poți face" 6,557ms (573ch). Turnuri 2-3 cu
  răspunsuri lungi (885ch/158ch) = 21.6s/21.1s (domină generarea+reasoning).
- Același prompt scurt „salut": rece 13.9s → cald 2.2s. OBIECTIV atins: boot 8-12s plătit o
  singură dată la turnul 1, turnurile 2+ doar latenity model (2-7s).
- Evidence: ~/9remote-uploads/opencode/warm-verify/{newchat.json, warm-second-chat.log}.

### 2026-10-08 — audit P1 fixes (parte 1)
- Branch fix/audit-bugfix-2026-10-08-p1 (commit 4006400 + 4a2e80f). CI runs 37702832164 (fail - close override) și 37703586547 (success, 4m43s).
- Bug 1 (P1): LocalFileSystem.copy — detect same canonical path for file copy → return FsError.FAILED (prevents overwrite/delete of source by same target).
- Bug 3 (P1): MainViewModel.createFilesFile — check stat(path); if NOT_FOUND → writeText; if EXISTS → return FsError("File already exists") (no silent truncation).
- Bug 4 (P1): MainViewModel.openFileRoot — clear fsClipboard on root switch.
- Bug 5 (P1): HeadlessCliBridge — on warm turn fail reset warmProxy/warmProxySignature; non-warm path clears cached warm proxy; added Closeable override + proper cleanup.
- All P1 (1,3,4,5) from audit applied; unit tests build/compile OK on CI (dany-debug-apk). No local build, only CI.

### 2026-10-08 — audit P2 final (Bug 7,9) — 9/9 reparate
- Branch fix/audit-bugfix-2026-10-08-p3 (5740700), CI 37746295861 VERDE, merge ff b30ae89..5740700, branch șters.
- Bug7 (ZIP pathing): archiveSelection colectează perechi (cale, entry); ZipEntry cu cale relativă la fsPath; fișier ilizibil = refuză arhiva (nu succes aparent); dedupe nume.
- Bug9 (CLI cold): CliRunResult.sawSuccess pe envelope terminal; succes cold = exit 0 sau sawSuccess; crash nenul nu mai raportează „completed".
- APK audit-p3 (toate 9 fixuri) instalat pe emulator-5554 + relaunch. bug.txt închis 9/9.

### 2026-10-08 — Bug 6 final — audit 9/9 complet
- Branch fix/audit-bugfix-2026-10-08-p4 (8c410ce), CI 37749275286 VERDE, ff 5740700..8c410ce, branch șters, APK p4 instalat + relaunch pe emulator-5554.
- DshRuntimeBridge: warmLock aplicat pe adopt/close/handshake; prewarm guard re-check în lock; closeWarmChannel(expected) owner-guard; handshake single-initializer + timeout 60s.
- bug.txt = 9/9 reparate (P1: 1,3,4,5,6 — P2: 2,7,8,9).

### 2026-10-08 — verificare funcțională Bug 7 pe device (APK p4)
- UI test real: Files → „Workspace-uri" → long-press `ziptest` (select) → „Arhivează" → „Arhivă 2026-10-08.zip · 3 fișiere".
- `unzip -l` pe zip-ul tras: `ziptest/sub/a.txt`, `ziptest/sub2/a.txt`, `ziptest/b.txt` — căi complete relative, basename duplicat în foldere diferite OK. FIX-ul Bug 7 confirmat end-to-end.
- Loguri start p4 curate (proc nou, zero excepții); nicio sesiune DSH după p4 (Bug 6 lock ne-exercitat live încă).
- Dovadă: `~/9remote-uploads/opencode/audit-verify/archive.zip`; device curat; logcat buffer → 8MB.

### 2026-10-08 — verificare funcțională Bug 6 (live) + Bug 9 (kill CLI)
- **Bug 6 live CONFIRMAT** (APK p4, „Wise Lovelace" 5f2404fd): logcat DshBridge — 16:23:08 `Route: nvidia-nim` → 16:23:19 `SDK handshake ready (reused=false)` (unic) → `SDK turn done: completed=true`; turnurile 16:24:04 / 16:24:21 / 16:35:37 / 16:36:17 au DOAR `Route:` + `SDK turn done: completed=true, failure=` — ZERO handshake nou, ZERO respawn, ZERO excepții/deadlock/timeout. Fast-path `channel.initialized` sub `warmLock` funcționează în productie.
- **Bug 9 live CONFIRMAT** (Hermes, Nimble Hopper 1f1d2502/chat b31c4e28): „Ruleaza" → tool-uri reale (search_files + 2× terminal completate) → `run-as com.jarves.mh kill -9 764 771` (libproot + python3 hermes) → logcat `Hermes warm turn done: failed=true, died=true, timeout=false` / `E HeadlessBridge: Session failed` / `IllegalStateException: The Hermes session exited unexpectedly; the phone may have suspended it. Retry with the app in the foreground.` (HeadlessCliBridge.kt:246) → UI „Sarcină oprită · The Hermes session exited unexpectedly…", chat JSON assistant `text:""`, workedMillis=39740 — **fără „finished the task", fără SessionCompleted**. Fix-ul `coldOk`/`sawSuccess` nu lasă un crash să citească drept succes.
- **Diagnoză „răspunde greu" (DSH, amânat la cererea user-ului)**: dsh 0.2.0-rc.2 (= npm latest/next) nu expediază live-chunk-uri pe stdio (`assistant/message` compactează tot streamul în `data.stream` la final; `agent/assistant-stream` e process-local). Turn cald = 19.2s: 1.3s first-token + 15.7s reasoning + 2.1s text. Placeholder „deciding the next useful step" e string de app (MainViewModel.kt:5172). Opțiuni: reasoning effort mai mic (cordis.patch.yml), model non-thinking, UX.
- Rămâne: test prewarm DSH (save provider → `SDK session prewarmed` → tură adopt). Evidence: logcat print în sesiune; `~/9remote-uploads/opencode/audit-verify/` (ui5x-65.xml, screen46-65.png). Device: keyguard securizat + Motorola App Locker blochează testele UI (screencap negru sub FLAG_SECURE).

## Session 2026-10-09 — prewarm live verification (final)

- 5 tentative live contaminate de interferență pe telefon (tap-uri user, force-stop din
  Settings 05:16:41 în timpul prewarm-ului, turn al userului 05:26:19) + 1 eșec „curat"
  la 04:31 nereconstruibil → decizie: jurnal diagnostic prin CI.
- `d307cce` „chore: log prewarm guard decisions for on-device diagnosis" (tag `Prewarm`):
  prewarmWarmSession = gard values; prewarmSession = entry (activeSessionId/kind/secretLen),
  not-installed, settings written. CI `37875364211` verde, APK instalat, ff `2b1fe78..d307cce`.
- **VERIFICAT 05:45:21**: onSave (isRunning=false, activeProject set, DEEPSEEK_HARNESS) →
  launching prewarm (wise-turing) → enter (activeSessionId=null, NVIDIA_NIM, secretLen=70)
  → settings.yaml written → chip „Verificat" → file recreat → canal cald adoptat.
- **Adopție 05:46:19**: turn 2.7s, PID dsh identici (31019/31023), fără handshake nou.
- Rețeta live (mid-turn up-tap early-retain) + capcane UI (keyboard/rotație/FLAG_SECURE)
  documentate în ~/.memory/MEMORY.md.

### 2026-10-09 — fix/devstack-install închis
- `30f47c9` (3 oct) rebased pe main → `58581c1`; conflict rezolvat: workflow = varianta
  main (online-only, fetch bundle-uri offline mort, dropped), RuntimeInstaller = comentariu
  main + `hasEmbeddedBundle`/Log.w; MainViewModel/Settings auto-merge (devStackFailed,
  mesaj final cu culoare eroare, Log.e). CI `37877180450` verde → ff `2a28da9..58581c1`,
  branch șters, APK reinstalat pe device.
- `bug.txt` închis (decizie user). Toate resturile sesiunii = închise.

### 2026-10-09 — fix 404 onboarding (runtime bundle base URL)
- Instalare de la 0 testată: backup 22 KB (secrete/chaturi/prefs) → uninstall → release
  debug-main APK (66.7 MB) install → first-run OK, dar setup-ul a picat cu
  „Download failed with HTTP 404".
- Cauză: `app/build.gradle.kts:31` default `RUNTIME_RELEASE_BASE_URL` = techjarves/
  Mobile-Harness tag `runtime-2026.09.4` (max core 2026.09.5); codul cere
  `pocketdev-core-arm64-2026.10.1.tar.zst` → 404. Bundle-urile 2026.10.1 sunt pe
  `Davinci198/Proxmox-VE-Laptop-Hybrid-Setup` release `runtime-2026.10.1`
  (sha256 = constanta CORE_BUNDLE, verificat).
- Fix: 1 linie → acel release; CI `37879834295` verde → APK instalat pe device
  (`apk-rturl/`) → ff `52d2326..1ed6b9b`, branch șters. Toate bundle-urile (core/claude/
  python/dsh/agy/android) sunt pe același tag.

### 2026-10-09 — DSH approval spike + fix/dsh-interactive-approvals (varianta A)
- Spike pe device (guestrun.sh = proot + base64 transport; dump-config = validator de patch):
  - Patch-ul acceptă **inserție de plugin nou**: `- insert: [- id: X, name: <modul>]`;
    `name` = path absolut `.../index.js` (dir-ul eșuează „failed to import"; numele npm
    funcționează doar din `profiles/*/node_modules` — evitat).
  - `writeDshSettings` scrie `$DSH_HOME/cordis.patch.yml` (canal app-controlled, peste
    profile layer) → acolo intră inserția + override `permission` presets.
  - `!!js` în YAML cere **scalar între ghilimele** (altfel `: ` produce mapping) și
    `defaultPreset` ternar (`undefined` → inferă ca vanilla): presets
    danger-full-access = `process.env.MH_APPROVAL_PORT ? 'ask' : 'never'`.
  - Pluginul se montează (`{"kind":"mounted"}`), vede `MH_APPROVAL_PORT`, transportul
    guest→host loopback verificat cap-coadă (mock python: body `{callId,toolName,reason}`
    → 200 `{"outcome":"allowed-once"}`). Fără port → `never` = comportament vechi
    (HeadlessCliBridge one-shot neatins).
  - `NARB_DISABLE_NATIVE_CACHE=1` obligatoriu la boot (PRoot poisoning) — deja în env.
- Implementare (pe branch): `DshApprovalServer` (ServerSocket loopback, HermesHookServer
  pattern, decide suspend → runBlocking), `DshRuntimeBridge`: pendingApprovals +
  requestApprovalDecision (ToolRequested → ApprovalCard → withTimeoutOrNull 10 min →
  outcome, timeout → ToolRejected), respondToApproval real (allowed-once/rejected),
  cancel la session end, deploy `plugins/mh-approval-answerer/index.js`, buildEnvironment
  + `MH_APPROVAL_PORT`, dshHomePatch extins; `classifyRisk` mutat în Models.kt (shared).
  Teste: DshApprovalServerTest (4), DshHomePatchTest actualizat.
- CI `37887852833` (c368395): eșec de compilare — edit-ul care a scos importul
  `RiskLevel` din ClaudeRuntimeBridge a înghițit și `RuntimeEvent` (match fuzzy) →
  `c455e3b` restore + paranteză în plus curățată în DshApprovalServerTest.
  Re-run `37888290234` verde (primul eșec: flake NDK/dl.google.com 502, 33min).
- ff `46d57b3..c455e3b` pe main, branch șters, APK branch instalat (`apk-dshappr/`,
  66.7 MB). E2E card rămâne: sesiune DSH reală → ask → ApprovalCard → allow/reject.
