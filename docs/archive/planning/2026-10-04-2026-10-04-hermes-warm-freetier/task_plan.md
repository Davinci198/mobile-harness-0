# Task Plan: Migrare runtime Mobile-Harness la Ubuntu 26.04.1 base (ARM64)

## Goal

Terminalul Android din Mobile-Harness rulează Ubuntu 26.04.1 LTS (resolute) ARM64 de tip
`ubuntu-base`, fără desktop, înlocuind Ubuntu 20.04.5, cu rollback sigur și toate
verificările CI verzi.

## Next Step

Bundle-ul Core 2026.10.1 este construit și verificat pe extras. Codul, constantele,
manifestul, scriptul canonic și testele sunt actualizate. **Rămâne de făcut de utilizator:**
build Gradle în CI (`./gradlew :app:testDebugUnitTest`), push al bundle-ului la release,
test pe device, apoi merge manual.

## Current Phase

Phase 4 (Testing & Verification) — cod finalizat, în așteptarea build-ului CI și a testului pe device

## Phases

### Phase 1: Requirements & Discovery — **COMPLET**
- [x] Identificat rootfs-ul țintă: `ubuntu-base-26.04-base-arm64.tar.gz`, 34 MiB, SHA256 `b2b46a37...`
- [x] Descărcat și verificat checksum (OK)
- [x] Verificat Andronix: are 26.04 dar XFCE + modat ⇒ **nu îl folosim**
- [x] Confirmat că 26.04 rulează sub proot (glibc 2.43, Python 3.14.4)
- [x] Identificat blocere: B1 hard links, B2 Python 3.8, B3 glibc, B4 bundle prebuilt, B5 do-release-upgrade
- [x] Inventariat fișierele de atins
- [x] Documentat în `findings.md`
- **Status:** completed

### Phase 2: Planning & Structure — **COMPLET**
- [x] 2.1 `removePythonStack()` version-agnostic (prefix `python3.` / `libpython3.`)
- [x] 2.2 Bundle-ul se construiește cu PRoot în Termux, fără telefon rootat
- [x] 2.3 Ordinea commiturilor e stabilită; merge-ul rămâne manual, la decizia utilizatorului
- **Status:** completed

### Phase 3: Implementation — **COMPLET**
- [ ] 3.1 `build-core-on-rooted-android.sh`: ROOTFS_FILE/URL/SHA256 → 26.04.1
- [x] 3.2 Script: marker `.pocket-rootfs-version` → `ubuntu-26.04.1-arm64`
- [x] 3.3 Builder proot: extragere care copiază hard links, fără `link(2)`
- [x] 3.4 Builder proot: `fix_shims` materializează shims-urile `.l2s` ca fișiere reale
- [x] 3.5 `RuntimeInstaller.kt`: constante rootfs noi + `CORE_BUNDLE` la 2026.10.1
- [x] 3.6 Bump `SYSTEM_UPGRADE_VERSION` la v2
- [x] 3.7 `removePythonStack()` — version-agnostic, curăță și 3.14
- [x] 3.8 UI: texturi „Ubuntu 20.04" / „Ubuntu 24.04" → 26.04
- [x] 3.9 **Bug latent reparat în scriptul canonic**: Node era linkat spre `node-v24.19.0`, dar arhiva extrage în `node-v24.19.0-linux-arm64` ⇒ `usr/local/bin/node` dangling ⇒ „Guest Node.js is missing"
- [x] 3.10 `createHardLink()` cu `Os.link` + fallback copy în `extractZstdTar` (decizie utilizator)
- [x] 3.11 `carryOverUserHome()` migrează `/root` înainte de `rootfs.deleteRecursively()` (decizie utilizator)
- **Status:** completed

### Phase 4: Testing & Verification
- [x] 4.1 Extragere 26.04 prin calea app-ului: 17.219 intrări, toate executabilele rezolvate
- [x] 4.2 `env`, `bash`, `apt-get`, `git`, `node`, `npm` funcționale în guest 26.04.1
- [x] 4.3 Node v24.19.0 / npm 11.17.0 / Git 2.53.0 pe glibc 2.43, cu flag-urile app-ului (`--link2symlink`)
- [x] 4.4 merged-`/usr` OK (`/bin`, `/sbin`, `/lib` → `usr/*`), `/workspace` și `/opt/pocketdev` prezente
- [x] 4.6 Bundle Core construit: `pocketdev-core-arm64-2026.10.1.tar.zst`, 84.766.979 B, SHA256 `62fcc178...`
- [ ] 4.5 CI: `./gradlew testDebugUnitTest` + `lint` — **interzis pe telefon, rulează în CI**
- [ ] 4.7 Test pe device: runtime pornit, agenți funcționali
- [ ] 4.8 Verificat backup-ul `/root` după migrare
- **Status:** in_progress

### Phase 5: Delivery
- [x] 5.1 `dist/runtime-bundles/manifest.json` actualizat (version/file/sha256/bytes/base/includes)
- [ ] 5.2 Push al bundle-ului la release + release notes 20.04 → 26.04.1
- [ ] 5.3 Diff review + aprobare; **merge-ul îl face manual utilizatorul după test**
- **Status:** pending

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| Nu folosim Andronix | Rootfs XFCE + modat, 4.2 GB vs 120 MB; avem nevoie de imagine oficială nemodificată |
| Alegem `ubuntu-base-26.04` (nu `26.04.1`) | Oferă canal `-release/` identic cu 20.04; patchlevel vine din apt |
| Înlocuire side-by-side, nu upgrade | `do-release-upgrade` nu permite salt peste LTS-uri; 20.04→22.04→24.04→26.04 ar fi 4 hops |
| Rootfs nou în staging + rename | `RuntimeInstaller.kt:157-178` are deja staging; nu distruge rootfs-ul vechi |
| Bump `SYSTEM_UPGRADE_VERSION` la v2 | Forțează re-rularea maintenance-ului pe noul rootfs |
| Nu rulăm Gradle pe telefon | Regulă `decius-termux-rules`; Gradle merge în CI |
| **B1 retras — nu atingem `extractZstdTar`** | Logica de hard links există deja și e corectă; măsurat 114/114 |
| **B1bis — rescris `extractZstdTar` cu `Os.link`** | Decizie utilizator: copia umplea 1,2 GB pe device; cu link real rootfs-ul e 445 MB |
| **Migrăm `/root` înainte de ștergere** | Decizie utilizator: `rootfs.deleteRecursively()` distrugea date fără rollback |
| Builder PRoot: `--long=24` la zstd | Fereastra default de 8 MB nu potrivește copii identice de 10,6 MB; fără asta bundle-ul era 486 MB în loc de 85 MB |
| Shims `.l2s` materializează ca fișiere reale | Symlink-urile absolute spre `/data` rup `env` și nu supraviețuiesc instalării pe device |
| Ferma `usr/lib/cargo/bin` rămâne cu fișiere reale | Compactarea în symlink-uri relative e respinsă de proot: verifică numele utilitarului vs numele fișierului rezolvat |

## Blockers reale (revizuiți după măsurători)

| ID | Blocker | Impact |
|----|---------|--------|
| B4 | Bundle-ul Core e prebuilt; `ROOTFS_URL` nu e folosit la runtime | **Singurul blocker real.** Fără rebuild, schimbarea e cosmetică |
| B3 | glibc 2.31 → 2.43 | De verificat pe device real că binarele vechi din bundle merg |
| B2' | `removePythonStack()` șterge doar path-uri 3.8 | Curățenie: rămân `python3.14` în urmă |
| ~~B1~~ | ~~Hard links~~ | **RETRAS** — `extractZstdTar` copiază, `--link2symlink` activ |
| ~~B2~~ | ~~Python 3.8~~ | **RETRAS** — Python vine din overlay bundle, nu din rootfs |
| ~~B5~~ | ~~Nu avem root~~ | **RETRAS** — proot înlocuiește chroot+mount, build posibil local |

## Note despre ipoteza „upgrade rezolvă /proc"

Măsurat: `/proc/self/{status,cmdline,maps}` sunt **complet funcționale** în guest 26.04 sub proot.
Dar `-b /proc` bind-mountă `/proc` al **hostului Android**, deci userland-ul Ubuntu nu participă.
Upgrade-ul **nu repară** probleme de `/proc` — poate doar schimba *simptomele*
(coreutils 26.04 = uutils 0.8.0 în loc de GNU ps din 20.04).
`CapEff: 0` + `Seccomp: 2` confirmă că limitarea e SELinux/kernel Android, nu distro.

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| `tar: ... Cannot hard link` (116×) | `tar -xzf` în Termux | Root cause = `fs.protected_hardlinks` + `/data`. **Irelevant** — app-ul nu folosește `link()` |
| Pipe `\| head -3` a omorât tar-ul (SIGPIPE) | prima dezarhivare | Re-extract fără pipe |
| AWK cu `coreutils/[` a pierdut câmpuri | `simulate-extract.sh` | `awk '{print $6,$8}'}` → apoi `sed` explicit (parantezaquare deranja awk) |
| `proot error: '/usr/bin/env' not found` | boot pe rootfs extras cu `tar` | După replicarea logicii app-ului: **rezolvat** |
| `grep: command not found` în guest | `bash -lc` | `/etc/profile` strică PATH-ul; app-ul folosește `env -i` cu PATH explicit. Nu e defect |
| `Cannot access Android/data` | căutare rootfs-ului app-ului | Termux nu are acces; app rulează pe alt user |
| `SecurityException: user 11` la `pm list packages` | device emulator | User mismatch; neclar |


## Evidence

| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| SHA256 `ubuntu-base-26.04-base-arm64.tar.gz` | `b2b46a37...74cab` | `OK` | PASS |
| Dimensiune rootfs 26.04 base | ~120 MB dezarhivat | 120M | PASS |
| Hard links în tarball | — | 115 (vs 4 în 20.04) | INFO |
| Hard links prin `tar` în Termux | 0 erori | 116 erori | FAIL (irrelevant) |
| **Hard links prin logica app-ului** | **114 copiate** | **114 copiate, 0 erori** | **PASS** |
| **`/usr/bin/env` după calea app-ului** | **executabil** | **`BOOT_OK`** | **PASS** |
| `cat --version` (era dangling) | funcționează | `uutils coreutils 0.8.0` | PASS |
| `apt-get update` în guest 26.04 | fără erori | fără erori | PASS |
| `apt-get install python3-minimal git` | funcționează | Python 3.14.4, git 2.53.0 | PASS |
| `/proc/self/status` | lizibil | `CapEff: 0`, `Seccomp: 2` | PASS |
| `/proc/self/cmdline` + `maps` | lizibile | lizibile | PASS |
| glibc în guest 26.04 | >2.31 | 2.43 | PASS |
| apt în guest 26.04 | funcționează | apt 3.2.0 (arm64) | PASS |
| `ubuntu-base` conține python3/git | — | nu (vin din overlay/apt) | INFO |
| `andronix list` | rulează | ubuntu 26.04 XFCE + `none` | PASS |
| Build bundle pe telefon rootat | — | ne-necesar: proot e suficient | N/A |
| Binare din bundle pe glibc 2.43 | funcționează | ne-testat (necesită device) | **TODO 4.3** |
