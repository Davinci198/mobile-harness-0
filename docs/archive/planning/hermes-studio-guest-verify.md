# Verificare plan `hermes-studio-guest.md` (doar citire — planul NU a fost modificat)

Data: 2026-09-24 · Metodă: confruntare punct-cu-punct cu repo-ul, sursa Studio și MEMORY.md
Model urmat: `key-db-model-filter.md` (același format: Goals → Părți → Do not touch → Delivery)

## Verdict

Planul e **viabil și bine fundamentat** — structura (bundle → RuntimeInstaller →
StudioServerManager → tab UI → test → CI → PR) respectă convențiile existente.
Cele mai multe afirmații sunt confirmate exact. Un singur **gap major** (arhitectura
runner-ului din pasul 2) + 5 inexactități minore de corectat la implementare.

## Confirmat cu dovezi (14)

| # | Afirmație din plan | Dovezi |
|---|---|---|
| 1 | Branch `feat/hermes-studio-guest` din `main` @ `dd7343b` | branch există, tracks `mh0/main` = `dd7343b` (ref-ul local `main` e stale `fe14a65`, inofensiv) |
| 2 | Sursă: `~/hermes-web-ui-unlocked`, Ekko Studio v0.7.21, npm `hermes-web-ui`, BSL-1.1 | `package.json`: name/version/license + `repository → EKKOLearnAI/hermes-studio`; fork local origin `Davinci198/hermes-web-ui-unlocked`, branch `locale_ro` |
| 3 | `dist/server/index.js` 11.8MB bundle, `dist/client` SPA Vue 3, `bin/hermes-web-ui.mjs` launcher | 11.844.759 bytes; client = Vue `^3.5.32`; launcher conține `DEFAULT_PORT`, `PID_FILE`, `LOG_FILE` |
| 4 | Externals: `node-pty`, `sharp`, `socket.io`, `node:sqlite`; restul bundle-uit | **exact**: `scripts/build-server.mjs:21` → `external: ['node-pty', 'node:sqlite', 'sharp', 'socket.io']`, `target: 'node23'` |
| 5 | Engines Node >=23; guest 24.19.0; host Node 26 | `package.json` engines `>=23.0.0`; MEMORY (guest v24.19.0); host `node -v` = v26.4.0 |
| 6 | `node-pty` fără prebuild linux-arm64 | `node_modules/node-pty/prebuilds/` = doar `darwin-{arm64,x64}`, `win32-{arm64,x64}` |
| 7 | `sharp` are prebuilt `@img/sharp-linuxarm64` pe npm | da (optional dep platform-filtered); **absent** din node_modules-ul local (doar `@img/colour`) |
| 8 | Port 8648, login `admin/123456`, `HERMES_WEB_UI_HOME`, `server.pid` | `bin/hermes-web-ui.mjs:17-24` (`WEB_UI_HOME` din env, `PID_FILE=server.pid`, `DEFAULT_PORT=8648`); `users-store.ts:40-41` `DEFAULT_USERNAME='admin'`, `DEFAULT_PASSWORD='123456'` |
| 9 | Kill-sweep ar putea ucide Studio | există (`RuntimeInstaller.kt:1897`, doar `ppid==1`), apelat din `HeadlessCliBridge.kt:141,178,311` — **dar vezi inexactitate A** |
| 10 | UI: reutilizează infrastructura `PreviewTab` | `PocketDevApp.kt:5358` `PreviewTab(...)`, allowlist host `127.0.0.1/localhost/0.0.0.0` la `:5505`; tab nou = intrare nouă în `enum WorkspaceTab` (`:236`) |
| 11 | `runtimeReleaseBaseUrl` hardcodat pe techjarves | `app/build.gradle.kts:29-30` |
| 12 | Pattern `RuntimeBundle` + sha256 + `RuntimeLaunchConfigBuilderTest` + `SettingsScreenModern` | `RuntimeInstaller.kt:48,1096,2212+` (`CORE_BUNDLE` etc.); testul există în `app/src/test/.../runtime/`; `SettingsScreenModern.kt:114 fun SettingsScreen(` |
| 13 | Patch DSH 30s→240s de aplicat ca step sed | **confirmat**: `dist/server/index.js` conține „readiness timed out after 240 seconds", **sursa** (`packages/server/src/modules/coding-agents/services/dsh/management.ts:81`) are `30_000` / „30 seconds" → patch-ul există doar în dist. Ținta sed trebuie să prindă AMBELE șiruri (mesaj + constantă) |
| 14 | Portul 8648 liber; 8642/8700/9119/20128 ocupate de altceva | `ss -ltn` gol pe gazdă; 8642/8700/9119/20128 nefolosite în app (doar în MEMORY) |

## Inexactități minore (corectează la implementare)

**A. Rationamentul markere-lor (decizie de arhitectură #2) e incorect.**
Planul: „markers includ substring `hermes` și `hermes-agent` → orice cale care conține
«hermes» ar fi ucisă". Real (`RuntimeInstaller.kt:1898`):
```
["libproot.so", "/root/.opencode/bin/opencode", "/usr/local/bin/hermes",
 "/usr/local/bin/claude", "hermes-agent", "opencode serve"]
```
Sunt **path-uri întregi**, nu substrin-gul „hermes". Un cmdline de genul
`node /usr/local/lib/studio/dist/server/index.js` sau `.../hermes-web-ui.mjs` nu conține
niciunul dintre markere → sweep-ul NU l-ar ucide oricum. Decizia de a numi directoarele
`studio` rămâne bună (curățenie, evită confuzia cu agentul), dar riscul era supraevaluat;
 scrierea ei ca fapt „critic" induce în eroare. (Nu există altă listă de markers în
`HeadlessCliBridge` — doar apelează `killGuestOrphans()`.)

**B. Porturi lipsă din matricea de coliziune.** Pe lângă 8648, sursa mai folosește
`8650` (`PREVIEW_BACKEND_PORT`) și `18650` (`PREVIEW_AGENT_BRIDGE_PORT`) —
`version-preview-manager.ts:33-35`. Verificate: libere, nefolosite în app → de adăugat
în documentarea riscului.

**C. „Serverul rulează deja pe host"** — în acest moment nu rulează (niciun proces,
niciun ascultător). Aptitudinea e reală (Node 26 prezent), dar e stare trecută.

**D. Health-check.** Planul zice „health GET `http://127.0.0.1:8648`"; ruta reală e
`/health` (`modules/studio/routes/health.ts:7`, montată în `bootstrap/routes.ts:83`)
→ `http://127.0.0.1:8648/health`.

**E. Lista de pruning poate fi insuficientă.** În bundle apar și
`require("ajv-formats/dist/formats")` + `require("ajv/dist/runtime/*")` (string-uri de
codegen ajv). `utf-8-validate` e gardat în try/catch (ok, opțional). → rulează un test de
fum cu `node_modules` pruneat, sau include `ajv`+`ajv-formats` (JS pur, ieftin).

## GAP MAJOR — pasul 2 (CI bundle) e incomplet pe arhitectură

Runner „ubuntu" implicit = **x86_64**, iar bundle-ul trebuie să conțină binare
**linux-arm64**:

- `sharp` → npm instalează `@img/sharp-linux-x64` (opționalele se filtrează pe os/cpu),
  **nu** `@img/sharp-linuxarm64` — exact varianta pe care planul o cere;
- `node-pty` → compilat pentru x86_64 (sau deloc) → mort în guest-ul aarch64.

Soluții (în ordine de curățenie):

1. **Runner `ubuntu-24.04-arm`** — repo-ul e **PUBLIC** (verificat `gh repo view`), deci
   runner-ele arm64 sunt gratuite; compilare nativă, fără cross-toolchain, `sharp` și
   `node-pty` ies direct arm64. **Recomandat** — de adăugat în pasul 2 al planului.
2. `npm ci --os=linux --cpu=arm64` (npm 12.0.2 are cheile `os`/`cpu`/`libc` — verificat)
   rezolvă `sharp`, dar `node-pty` cere oricum cross-compilare node-gyp → greșit +
   netestabil pe x64.
3. Fallback deja menționat în plan: compilezi `node-pty` o dată în guest și vendor-ezi
   binarul `.node` în bundle.

Notă `npm pack`: `hermes-web-ui@0.7.21` există pe registry (tarball URL verificat), dar
pachetul public conține dist **fără** patch-ul de 240s → sed țintit pe dist
(„30 seconds"→„240 seconds" + `30_000`→`240_000`) sau build din checkout-ul fork-ului cu
sed pe sursă.

## Ce NU s-a atins

- `hermes-studio-guest.md` — nemodificat (ciorna originală, fișier neurmărit git).
- Nicio modificare de cod, niciun workflow GitHub Actions (regula #pin: aprobare
  explicită înainte de orice workflow — pasul 2 rămâne blocat până la „da").
- Instrumentul planning-with-files e blocat (`PLAN TAMPERED`, fișier scris în afara
  lui) — raportul de mai sus e alternativa la task_plan/findings.

## Delivery (preluat din model + ajustat)

CI `dany-debug-apk` verde → PR `feat/hermes-studio-guest` → review user → merge în main.
Pasul 2 (workflow bundle) **doar cu aprobare explicită**.
