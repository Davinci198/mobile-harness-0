# Plan: feat/hermes-studio-guest — Ekko Studio (hermes-web-ui-unlocked) în guest

Branch: `feat/hermes-studio-guest` from `main` @ `dd7343b`
Date: 2026-09-24
Sursă locală: `~/hermes-web-ui-unlocked` (Ekko Studio v0.7.21, npm `hermes-web-ui`, BSL-1.1)

> **Corecții după verificare** (vezi `hermes-studio-guest-verify.md`):
> (1) marker-ele din `killGuestOrphans()` sunt căi complete, nu substring „hermes"
> — riscul de sweep era supraevaluat, numirea `studio` rămâne decizie de curățenie;
> (2) health endpoint real = `/health`; (3) porturi suplimentare în matricea de
> coliziune: 8650, 18650; (4) prune-ul node_modules include și `ajv` + `ajv-formats`;
> (5) bundle CI pe runner nativ `ubuntu-24.04-arm` (repo public → gratuit), altfel
> node-pty/sharp ar ieși x86_64 și moarte în guest.

## Scop

Ekko Studio (web UI multi-agent: chat, workflows, files, terminal web) rulează CA SERVER
în guest-ul Ubuntu PRoot al Mobile Harness și e afișat în app printr-un tab WebView
pe `localhost:8648`. Nu importăm cod în app — integrăm un runtime extern ca bundle.

## Ce este sursa (inventar verificat)

- Monorepo TS build-uit: `dist/server/index.js` = 11.8MB esbuild CJS bundle,
  `dist/client/` = SPA Vue 3, `bin/hermes-web-ui.mjs` = launcher daemon
  (port default 8648, state în `~/.hermes-web-ui`, login `admin/123456` + token).
- Dependențe RUNTIME (esbuild externals): `node-pty`, `sharp`, `socket.io`,
  `node:sqlite` (builtin Node 22.5+). Restul e bundle-uit.
- Engines: Node >=23. Guest-ul are Node 24.19.0 → OK. Hostul Termux are Node 26
  (serverul rulează deja acolo, e patch-uit local: DSH readiness 30s→240s).
- Natives pe linux-arm64: `sharp` are prebuilt `@img/sharp-linuxarm64` (npm).
  `node-pty` NU are prebuild în repo (doar darwin/win32) → de compilat în guest
  (avem build-essential + python) sau de vendor-ui binarul `.node` compilat o dată.
- Repository upstream: EKKOLearnAI/hermes-studio. Fork-ul local nu se modifică.

## Decizii de arhitectură

1. **Bundle nou** `pocketdev-studio-arm64-<date>.tar.zst` + sha256, ca RuntimeBundle
   existent, extras în rootfs de `RuntimeInstaller`. Conținut:
   - `usr/local/lib/studio/dist/` (server + client)
   - `usr/local/lib/studio/bin/`
   - `usr/local/lib/studio/node_modules/` PRUNAT: doar `socket.io` (tree complet),
     `sharp` + `@img/sharp-linuxarm64`, `node-pty` sursă + binar compilat
   - `usr/local/bin/studio` — wrapper `exec node /usr/local/lib/studio/dist/server/index.js`
   - `etc/profile.d/11-studio.sh` (opțional, PATH)
   - Marker: `.pocket-studio-version`
   - ENV: `HERMES_WEB_UI_HOME=/root/.hermes-web-ui` (persistă în rootfs)
2. **NUME CRITIC — evită marker-ele din `killGuestOrphans()`**: markers includ
   substring `hermes` și `hermes-agent`. Orice cale din cmdline care conține
   „hermes" (ex. `hermes-web-ui`) ar fi ucisă de sweep-ul de orfani la fiecare
   sesiune headless. De aceea: directoare `studio` (NU `hermes-web-ui`) pe disc;
   state dir-ul rămâne `~/.hermes-web-ui` (nu apare în cmdline). Procesul Studio
   NU trebuie să fie marker de sweep — e server long-running, supraviețuiește
   sesiunilor; stop se face explicit prin pid (`~/.hermes-web-ui/server.pid`).
3. **Server management**: `StudioServerManager` (nou, `runtime/`) — start detached
   prin proot (setsid, log în `/root/.hermes-web-ui/server.log`), status prin pid
   + health GET `http://127.0.0.1:8648`, stop prin kill pid din server.pid.
   Nu folosește PtySession; nu e legat de ciclul HeadlessCliBridge.
4. **UI**: tab „Studio" nou în `PocketDevApp.kt`, reutilizează infrastructura
   `PreviewTab` (WebView restricționat la localhost, address bar, console
   telemetry) țintit pe `localhost:8648`. Login `admin/123456` o dată; cookie/
   token persistă în WebView + state dir din rootfs.
5. **Hosting bundle**: release pe `Davinci198/mobile-harness-0` (tag
   `runtime-studio-0.7.21`). `runtimeReleaseBaseUrl` din `app/build.gradle.kts`
   e hardcodat pe techjarves → îl facem suprascrisibil prin gradle property
   (`-PruntimeReleaseBaseUrl=…`), fallback la valoarea actuală. Fără schimbare
   de behavior pentru flavor-urile existente.
6. **Build bundle pe GitHub CI** (nu local, nu pe telefon): workflow nou
   `.github/workflows/studio-bundle.yml` — ubuntu runner, Node 23+, `npm ci`,
   `npm run build`, prune node_modules, tar.zst + sha256, upload pe release.
   ⚠️ NECESITĂ APROBAREA TA EXPLICITĂ (regula #pin workflow-uri) — întreb
   separat înainte de a crea fișierul.

## Pasi

1. **Branch + plan** (gata): `feat/hermes-studio-guest` din `mh0/main`, acest document.
2. **Workflow bundle** (cu aprobare): `studio-bundle.yml` care produce artifactul
   `pocketdev-studio-arm64-<ver>.tar.zst` + sha256 din `~/hermes-web-ui-unlocked`
   (sursa e publică pe npm/GitHub; CI face `npm pack hermes-web-ui@0.7.21` sau
   folosește checkout-ul fork-ului EKKOLearnAI/hermes-studio, apoi patch-ul DSH
   timeout aplicat ca step sed).
   Verificare: artifact conține dist/server/index.js + dist/client + node_modules
   prune; `node dist/server/index.js` pornește pe runner (smoke test).
3. **RuntimeInstaller**: `STUDIO_BUNDLE` (label/fileName/sha256/bytes),
   `installStudio()`, marker `.pocket-studio-version`, `isStudioInstalled()`.
4. **StudioServerManager**: start/stop/status/health + fix-uri de mediu deja
   cunoscute în guest (apt sandbox config există; DNS pins există la sesiuni).
5. **UI tab Studio** + settings (enable, port, auto-start) în SettingsScreenModern.
6. **Test unitar**: `StudioServerManagerTest` (pattern ca `RuntimeLaunchConfigBuilderTest`).
7. **CI + APK**: build pe GitHub CI (`dany-debug-apk` / pr-check), instalare pe
   telefon, validare E2E (vezi criteriile).
8. **PR** spre `main` în mobile-harness-0 după validare.

## Criterii de accept

- Tab Studio deschide login, apoi workspace-ul complet pe localhost:8648.
- Serverul supraviețuiește deschidere/închidere de tab-uri și sesiunilor headless
  ale celor 5 agenți (killGuestOrphans NU îl omoară).
- Chat Hermes din Studio răspunde (agentul v0.21.4 deja instalat în guest).
- node-pty funcțional (terminal web din Studio) sau degradare elegantă dacă nu.
- Instalare bundle proaspătă merge end-to-end (download + sha256 + extract + start).
- CI verde; APK semnat cu semnătura stabilă; install -r peste cel existent OK.

## Riscuri

- **Rețeaua guest moare fără foreground** (cunoscut, #pin): UI-ul Studio e local
  și merge, dar apelurile agent→API extern (NVIDIA BYOK) eșuează când app-ul
  pierde prim-planul. Documentat în UI/README; nu e fixabil la nivelul nostru.
- **Memorie**: serverul Node + agentul + PRoot pe același device; dacă e strâns,
  pornim Studio on-demand (nu la boot) și oprim la uninstall.
- **node-pty compilare** în guest durează (~1-2 min) dar e one-shot la install;
  fallback: bundle cu binar precompilat.
- **Port collision** 8648: liber (gateway 8642, agent web 8700/9119, OmniRoute 20128).
- **Licență BSL-1.1**: bundle-ul e descărcat de user de pe release, nu e
  redistribuit în APK; la nevoie se mută hostingul pe un release propriu cu
  notă de licență.
- **BSL/patch local**: patch-ul DSH readiness (30s→240s) trebuie aplicat în
  pipeline-ul de bundle (step dedicat, documentat).

## Non-obiective

- Nu modificăm hermes-web-ui-unlocked mai mult decât patch-ul existent.
- Nu aducem desktop/Electron, platform channels (Telegram/Discord/…), ESP32.
- Nu atingem gateway-urile locale existente (LocalOpenAiProxy/LocalFormatGateway).
- Nu construim nimic Gradle pe telefon (doar CI, regula #pin).
