# Findings — Migrare Mobile-Harness: Ubuntu 20.04 → 26.04.1 base (ARM64)

Data: 2026-10-04. Branch: `ubuntu-26.04-base-migration` (base `222ddc3`, tree curat).

## 1. Rootfs-ul țintă există, fără desktop

| Sursă | Variantă | Desktop | Dimensiune | SHA256 |
|-------|----------|---------|-----------|--------|
| cdimage 20.04 (curent) | `ubuntu-base-20.04.5-base-arm64.tar.gz` | nu | 26.257.800 B (25 MiB) | `f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52` |
| cdimage 26.04.1 (țintă) | `ubuntu-base-26.04-base-arm64.tar.gz` | nu | 35.094.845 B (34 MiB) | `b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab` |

URL țintă: `https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/ubuntu-base-26.04-base-arm64.tar.gz`
Checksum oficial: `https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/SHA256SUMS`

Descărcat și verificat local în `~/9remote-uploads/opencode/` — checksum **OK**.
Există și `ubuntu-base-26.04.1-base-arm64.tar.gz` (SHA `5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd`);
alegem varianta `26.04` pentru a avea un canal `-release/` identic cu cel al 20.04.

## 2. Andronix: da, dar fără desktop nu ajută

`andronix list` (Andronix Go, binar în `/usr/bin/andronix`):

- Instalat: `ubuntu — Ubuntu 26.04 · XFCE` → `~/.andronix/distros/ubuntu` (4.2 GB, `EDITION=legacy-ubuntu-xfce`, `SOURCE=modded`, `PREBUILT=yes`)
- Disponibile: alpine, arch, debian13, fedora44, kali, manjaro, ubuntu 26.04, ubuntu24, void
- Desktops: kde, lxqt, mate, **none (command line only)**, xfce

Andronix folosește imagini Docker, nu cdimage:
`DISTRO_IMAGE="docker.io/library/ubuntu:26.04"` și `"docker.io/library/ubuntu:24.04"`.

**Decizie: nu folosim Andronix.** Rootfs-ul Andronix e XFCE + modat (4.2 GB vs 120 MB
dezarhivat pentru base). Pentru Mobile-Harness avem nevoie de imaginea oficială
nemodificată, fiindcă bundle-ul se construiește din ea și se publică cu SHA256 propriu.
Decizia „fără desktop” se rezolvă direct din cdimage `ubuntu-base`, fără Andronix.

Bonus: containerul local `proot-distro` este deja Ubuntu 26.04.1 (resolute), glibc 2.43 —
util ca gazdă de test, dar nu ca sursă de bundle (e modificat de proot-distro).

## 3. Rulează sub proot — confirmat

Test pe rootfs-ul Andronix 26.04.1 cu `proot -0`:
```
GUEST_OK / VERSION_ID="26.04" / ldd 2.43 / Python 3.14.4 / no-node
```
Așadar 26.04 + proot + Android merge. Ne putem baza pe asta.

## 4. Blocere — analiză revizuită (B1 și B2 RETRASE)

### ~~B1 — Hard links~~ **RETRAS: FALS ALARM. Nu e blocker.**

26.04 are într-adevăr **115 hard links** (20.04 are doar 4), iar `toybox tar` / `tar` din
Termux eșuează pe `link()`:
```
tar: usr/bin/perl5.40.1: Cannot hard link to 'usr/bin/perl': Permission denied   (×116)
```
Cauza: fișierele-sursă sunt `root/root` în tarball, iar `/data` interzice `link()` spre
fișiere pe care procesul nu le deține (`fs.protected_hardlinks`). Nu e corupție de download
(SHA256 OK) și nici limitare a tarball-ului.

**Dar app-ul NU dezarhivă cu `tar`.** `extractZstdTar()` (`RuntimeInstaller.kt:2162-2204`)
folosește commons-compress și tratează `entry.isLink` prin **copiere de bytes**:
```kotlin
entry.isLink -> {
    if (linkTarget.exists()) linkTarget.inputStream().use { i -> FileOutputStream(target).use { i.copyTo(it) } }
    else deferredLinks += target to linkTarget      // rezolvat la final, tot prin copiere
}
```
Deci `link()` nu e apelat niciodată. În plus, proot pornește cu `--link2symlink`
(`RuntimeInstaller.kt:1790`, `emulateHardLinks = true` implicit), deci și symlink-urile
rămân tratate ca atare.

**Probă empirică.** Am replicat exact logica Kotlin (`~/9remote-uploads/opencode/simulate-extract.sh`):
```
--- hard links copiate: 114     (0 erori, 0 targete lipsă)
```
Apoi boot cu proot pe rootfs-ul obținut:
```
BOOT_OK
/usr/bin/cat --version  → cat (uutils coreutils) 0.8.0     (era dangling, acum merge)
/usr/bin/grep --version → grep (GNU grep) 3.12
apt-get update -qq       → fără erori
```
Concluzie: **B1 nu există**. Migrarea nu necesită nicio modificare de cod pentru hard links.

### ~~B2 — Python 3.8~~ **RETRA(S) PARȚIAL: nu e blocker de upgrade**

`removePythonStack()` (`RuntimeInstaller.kt:971-985`) șterge explicit path-uri Python 3.8.
Dar **stack-ul Python nu vine din rootfs**, vine din overlay bundle
(`installRuntimeOverlay(PYTHON_BUNDLE, ...)`, `RuntimeInstaller.kt:1045`), iar
`ubuntu-base` nu conține deloc `python3` sau `git` (verificat: `/usr/bin/python*` absent).

Deci rootfs-ul nou nu trebuie să coincidă cu Python-ul din overlay. Rămâne totuși o
curățenie: `removePythonStack()` va lăsa în urmă `usr/lib/python3.14`, `usr/bin/python3.14`
etc. pe un guest 26.04. **Impact redus, cosmetizare necesară, nu blocare.**

Test: `apt-get install -y python3-minimal git ca-certificates` pe 26.04 → **Python 3.14.4,
git 2.53.0**, ambele OK.

### B3 — glibc 2.31 → 2.43 (rămâne, risc mic)

Confirmat 2.43 pe 26.04. Glibc e backward-compatible, deci binarele vechi din bundle ar
trebui să meargă. **De verificat efectiv pe device**, nu presupus.

### ~~B5~~ **reclasificat: build-ul se poate face fără root** (vezi §9)

### Despre ipoteza „upgrade-ul rezolvă problema cu /proc" — măsurată, nu confirmată

**Clarificare necesară (user a obiectat corect la formulare).** Guestul **este** Ubuntu —
`/etc/os-release` → `Ubuntu 26.04 LTS`, `apt` → 3.2.0, `python3` → 3.14.4. Userland-ul e
100% Ubuntu. Dar asta nu e în contradicție cu faptul că `/proc` vine de la kernel-ul gazdei:
sunt două straturi diferite.

Măsurare directă, un singur output:
```
/proc/self/mountinfo → 1000001 1 0:1 / /proc rw,relatime - bind /proc rw,relatime
uname -a            → Linux localhost 6.6.102-android15-8-ge6a1e5cb500c-ab16017573-4k ...
/etc/os-release     → PRETTY_NAME="Ubuntu 26.04 LTS"
```
`0:1` = device `proc` al **hostului**, bind-mountat în guest. `uname` raportează kernel-ul
Android. Deci:

| Strat | Cine îl furnizează | Se schimbă la upgrade? |
|-------|--------------------|------------------------|
| Userland (`/bin`, `/usr`, `apt`, `python3`, `ps`) | rootfs-ul Ubuntu | **DA** |
| Kernel + `/proc`, `/sys`, `/dev` | kernel-ul Android 6.6.102-android15 | **NU** |

**Cea mai clară dovadă**: din guestul Ubuntu se văd procese ale gazdei:
```
/proc/14514 → .../com.termux/.../gradle-daemon-main-8.14.jar (Gradle daemon din Termux)
/proc/15974 → /data/data/com.termux/files/usr/bin/bash -l
```
Guestul Ubuntu vede procesele Termux. Nu poate fi „Ubuntu 20.04 vs 26.04" — ăsta e
comportamentul kernel-ului.

**Ce e funcțional și ce nu e, măsurat în guest 26.04:**
```
/proc/self/status    → OK   (CapEff: 0, Seccomp: 2, Seccomp_filters: 2)
/proc/self/cmdline   → OK
/proc/self/maps      → OK
/proc/self/stat      → OK   ← ce folosește mtop.py
/proc/[0-9]*/stat    → OK   ← mtop.py, procwatch
ps aux               → merge (uutils 0.8.0), dar: "Unable to get system boot time"
/proc/stat           → Permission denied   ← global
/proc/uptime         → Permission denied   ← global
/proc/loadavg        → Permission denied   ← global
/proc/self/net/tcp   → Permission denied   ← global
```
Lectura per-PID merge; **fișierele globale sunt blocate de SELinux** pe host. Exact
diagnosticul din `ensureMtop()` (`RuntimeInstaller.kt:1368-1371`):
„citesc /proc direct, fara /proc/stat pe care SELinux il interzice aplicatiilor".

Deci: **upgrade-ul la 26.04 nu repară blocajele SELinux** ( `/proc/stat`, `/uptime`,
`/loadavg`, `/net/*` rămân blocate — erau blocate și în 20.04). Poate schimba *simptomele*:
`ps` trece de GNU (20.04) la uutils 0.8.0 (26.04), deci mesajul de eroare se poate schimba,
dar permisiunea rămâne refuzată.

### htop — reprodus explicit pe ambele distro-uri

Pe rootfs-ul **26.04** dezarhivat prin calea app-ului:
```
apt-get install -y htop   → INSTALL_EXIT=0, /usr/bin/htop, htop 3.4.1
htop -d 5                 → Cannot open /proc/stat: Permission denied
```
Același test pe rootfs-ul **20.04**:
```
head -1 /proc/stat        → Permission denied
cat /proc/uptime          → Permission denied
```
**Identic.** Nu e regresie, nu e rezolvabil prin schimbarea de distro. Cauza: SELinux pe
gazda Android blochează fișierele `/proc` globale pentru orice proces ne-rut.

**Workaround-ul existent funcționează.** `mtop.py` (copiat din `app/src/main/assets/`)
rulate pe 26.04:
```
 Procwatch 21:55:30  refresh=1.00s sort=cpu cores=8
RAM 6.5G/11.2G (58%)  SWAP 4.8G/8.4G (57%)  procese 21  orfani 5
CPU total indisponibil: /proc/stat este ascuns de Android; CPU% este per proces/nucleu.
    PID    PPID   UID PRI  NI  THR ST   CPU%   MEM%       RSS   Command
  31206   31124 10387  20   0   22 R  104.0   6.32    727.3M   /data/data/com.termux/files...
  15974   15804 10387  20   0   11 S   32.0   1.23    141.5M   omnirush
```
Deci `/proc/[pid]/*` e citibil (per-PID), `/proc/<global>` e blocat. `mtop.py` citește
exact partea accesibilă și spune explicit ce lipsește. Funcționează neschimbat pe 26.04.

**Concluție**: dacă problema raportată e „htop/w nu merg", cauza e SELinux pe gazdă și
nu se repară prin schimbarea de distro. `mtop.py` există exact ca workaround. O soluție
reală ar cere Shizuku/root pe gazdă (nu se schimbă prin rootfs).

## 4b. Blocere care rămân reale

### B2' — Python 3.8 paths în `removePythonStack()` (cosmetizare)
### B3 — glibc 2.43: binarele vechi din bundle (de verificat pe device)
### B4 — Bundle-ul e prebuilt; `ROOTFS_URL` nu e folosit la runtime (REAL, rămâne)

`ROOTFS_URL` nu apare decât ca prefix în constantele de la `RuntimeInstaller.kt:2375-2377`,
folosite **doar** pentru marker-ul `.pocket-rootfs-version`. La runtime aplicația instalează
bundle-ul Core prebuilt prin `obtainRuntimeBundle(CORE_BUNDLE, ...)`. Schimbarea URL-ului
nu are efect fără rebuild al bundle-ului.

Detalii în §5-§6 de mai jos (net afectate de revizuire).

## 4c. Concluție după măsurători

Rămân **2 lucruri reale**, nu 5:
1. **B4** — trebuie reconstruit bundle-ul Core cu rootfs 26.04 (altfel schimbarea e cosmetică).
2. **B3** — de verificat pe device real că binarele din bundle merg pe glibc 2.43.

Plus 1 curățenie (B2') și UI-ul inconsistent.

`removePythonStack()` listează explicit path-uri Python 3.8:
`RuntimeInstaller.kt:974-981` — `usr/bin/python3.8`, `usr/lib/aarch64-linux-gnu/libpython3.8.so.1`,
`usr/lib/python3.8`, `usr/local/lib/python3.8`, `usr/share/python3-wheels`, etc.

26.04 livrează Python 3.14.4 (verificat în rootfs Andronix). Stack-ul Python trebuie
să devină version-agnostic sau să folosească `python3`/`libpython3.*`.

### B3 — glibc 2.31 → 2.43

20.04 are glibc 2.31; 26.04 are 2.43. Binarele existente în bundle (Node 24.19.0,
npm 11.17.0, Claude Code 2.1.263, gh CLI, Antigravity CLI, aapt2/d8 din Android SDK,
DSH) au fost compilate pe glibc mai vechi. Glibc e backward-compatible, deci binarele
vechi ar trebui să meargă — dar trebuie verificate efectiv, nu presupus.

### B4 — Bundle-ul e prebuilt; ROOTFS_URL nu e folosit la runtime

Constantele din `RuntimeInstaller.kt:2374-2377` sunt folosite **doar** pentru marker-ul
`.pocket-rootfs-version`. La runtime aplicația instalează bundle-ul Core prebuilt
(`pocketdev-core-arm64-2026.09.5.tar.zst`, 72.185.773 B comprimat / 369.172.480 B
dezarhivat) prin `obtainRuntimeBundle(CORE_BUNDLE, ...)`.

Deci **nu e suficient să schimbăm URL-ul**. Migrarea reală = rebuild al bundle-ului
prin `scripts/runtime-bundles/build-core-on-rooted-android.sh`, care hardcodează:
- `ROOTFS_FILE="ubuntu-base-20.04.5-base-arm64.tar.gz"`
- `ROOTFS_SHA256="f9b999af..."`
- `ROOTFS_URL=".../20.04/release/..."`
- marker `printf 'ubuntu-20.04.5-arm64\n' > /.pocket-rootfs-version`

Scriptul cere **telefon ARM64 rootat** (`adb` + `su -c` + `chroot`). Dispozitivul
conectat acum e `emulator-5554` (Android 16), fără root ⇒ build-ul nu se poate face aici.

### B5 — `do-release-upgrade` 20.04 → 26.04 nu e o cale suportată

`runSystemMaintenance()` face doar maintenance in-place (`dpkg --configure -a`,
`apt-get -f install`, `apt-get update`, `apt-get upgrade`) — nu schimbă seria.
Ubuntu nu permite saltul peste LTS-uri; upgrade-ul oficial ar fi 20.04 → 22.04 → 24.04 → 26.04,
deci inutil aici: înlocuim rootfs-ul cu unul nou, nu upgradăm unul existent.

## 5. Fișiere de atins

| Fișier | Ce trebuie schimbat |
|--------|---------------------|
| Fișier | Ce trebuie schimbat |
|--------|---------------------|
| `scripts/runtime-bundles/build-core-on-rooted-android.sh` | `ROOTFS_FILE`, `ROOTFS_SHA256`, `ROOTFS_URL`, markerul `.pocket-rootfs-version`. **Fără** fix de hard links (necasar) |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt` | `ROOTFS_VERSION` (2374), `ROOTFS_FILE/URL/SHA256` (2375-2377), `removePythonStack()` (971-985, listă python3.14), `SYSTEM_UPGRADE_VERSION` (bump la v2) |
| `dist/runtime-bundles/manifest.json` | `bundles.core.version`, `file`, `sha256`, `compressedBytes`, `uncompressedBytes`, `base: "Ubuntu 20.04.5 ARM64"` → 26.04.1 |
| `app/.../ui/TerminalScreen.kt` | text „Ubuntu 24.04” — inconsistent cu 20.04, corectăm odată cu migrarea |
| `app/.../ui/SettingsScreen.kt` | „Ubuntu 20.04 PRoot” |
| `app/.../ui/SettingsScreenModern.kt` | „Ubuntu 20.04 PRoot · ARM64” |
| teste (unit + instrumented) | aserțiuni pe 20.04 / python3.8 |

`extractZstdTar()` **nu se modifică** — deja tratează corect hard links prin copiere.
`ensureRootfsCompatibilityLinks()` (`RuntimeInstaller.kt:1689+`) reface deja
`bin`/`lib`/`sbin` → `usr/*`; 26.04 e merged-`/usr`, deci se aplică la fel.

`ensureRootfsCompatibilityLinks()` (`RuntimeInstaller.kt:1689+`) reface deja
`bin`/`lib`/`sbin` → `usr/*`; 26.04 e merged-`/usr`, deci se aplică la fel.

## 6. Strategia aleasă (Phase 2)

Înlocuire side-by-side a rootfs-ului, nu upgrade în loc:
1. Rootfs nou, extras în `staging` (`ubuntu.installing`), activat doar dacă trece verificările.
2. Rootfs-ul vechi rămâne neatins până când noul rootfs e valid
   (`RuntimeInstaller.kt:157-178` — deja face staging + rename).
3. Datele de utilizator se migrează explicit, nu implicit: `/workspace`, proiecte,
   settings, hooks, marker-uri de tool-uri.
4. Rollback = rootfs vechi + marker vechi; nimic nu se șterge definitiv în faza de migrare.

## 7. Ce NU putem verifica aici

- `gradlew assembleDebug` pe telefon: interzis (rulează în CI).
- Testarea reală a binarelor din bundle pe glibc 2.43: necesită rulare pe 26.04 real
  (build-ul bundle-ului e faisabil local, vezi §9).

## 9. Build-ul bundle-ului FĂRĂ root — posibil

`build-core-on-rooted-android.sh` cere root doar pentru trei lucruri: `chroot`,
`mount --bind /dev /proc /sys`, și ownership root-owned. Toate au înlocuitori în proot,
deja validați în §3-§4:

| Nevoia cu root | Înlocuitor cu proot |
|----------------|---------------------|
| `chroot $REMOTE/rootfs` | `proot -0 -r $REMOTE/rootfs` |
| `mount --bind /dev /proc /sys` | `proot -b /dev -b /proc -b /sys` |
| fișiere root-owned pentru hard links | `extractZstdTar`-style copy (114/114 OK) |
| DNS | `resolv.conf` scris în rootfs |

deci build-ul se poate rula **din Termux, cu proot**, fără telefon rootat și fără chroot.
`toybox tar` (singurul punct real unde hard links contează) trebuie înlocuit cu aceeași
logică de copiere.

## 10. Surse

- `https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/` (listare + SHA256SUMS)
- `~/.andronix/distros/ubuntu/install.conf` → `EDITION=legacy-ubuntu-xfce`, `SOURCE=modded`
- strings pe binarul Andronix → `docker.io/library/ubuntu:26.04`
- `extractZstdTar` / `--link2symlink` în `RuntimeInstaller.kt:2162-2204` / `:1790`
- măsurători proprii în `~/9remote-uploads/opencode/`: `simulate-extract.sh` (114/114 hard
  links), boot proot 26.04 (`/proc` OK, `apt-get update` OK, python3 3.14.4, git 2.53.0,
  glibc 2.43, apt 3.2.0)

## 11. htop în Termux — control: nicio distro nu repara

`htop` e deja instalat în Termux (`htop 3.5.3`). Rulează, dar cu metricile kernel goale:
```
8 nuclee:  toate "offline"
CPU total: 0.0%
Load avg:  nan nan nan
Uptime:    (unknown)
CPU% col:  N/A pentru fiecare proces
Mem:       5.98G/11.2G    ← funcționează
Swp:       4.75G/8.43G    ← funcționează
Tasks:     18, 110 thr     ← funcționează
```
În guestul Ubuntu 26.04 htop **moare complet** (`Cannot open /proc/stat`), pentru că
Termux are `htop` deja instalat și rulează, iar proot-ul n-are. Diferența dintre Termux și
guest e doar *disponibilitatea binarului*, nu *permisiunile kernel*.

Cauza, măsurată:
```
/proc/self/attr/current → u:r:untrusted_app_27:s0:c131,c257,c512,c768
/proc/stat    → Permission denied
/proc/uptime  → Permission denied
/proc/loadavg → Permission denied
/proc/self/stat → -r--r--r-- (permis)
```
`untrusted_app_27` = categoria SELinux pentru aplicații Android nereciclate. Blocarea e
**categorică**, nu per-app. Nicio distro, niciun userland, nicio versiune de htop nu o ocolește.

### Tabel final — cele trei medii măsurate

| Mediu | userland | `/proc/stat` | htop | mtop.py |
|-------|----------|--------------|------|---------|
| Termux (gazdă) | Termux | **Permission denied** | pornește, CPU/load/uptime `N/A`/`nan` | n/a |
| Guest Ubuntu 20.04 | Ubuntu 20.04 | **Permission denied** | neinstalat; ar muri la fel | funcționează |
| Guest Ubuntu 26.04 | Ubuntu 26.04 | **Permission denied** | `Cannot open /proc/stat` | **funcționează** |

Concluzia e aceeași în toate trei: **limitație SELinux a gazdei Android**, identică
indiferent de distro. Upgrade-ul 20.04 → 26.04 nu schimbă nimic aici. Singurele lucruri
care chiar se îmbunătățește: unelte noi (uutils `ps`) și `mtop.py`, care raportează explicit
limitarea în loc să tacă.

## Construirea bundle-ului în Termux cu PRoot — bariere reale și soluții

Toate măsurătoarele de mai jos sunt din `~/9remote-uploads/core-build` pe telefon.

### 1. `-i` și `-0` sunt mutual exclusive

```
$ proot -0 -i 10387:10387 ...
proot info: only the last -i/-0/-S option is enabled
uid=10387 ; chown → Operation not permitted
$ proot -0 ...
uid=0 ; chown → OK ; write în /var/lib/dpkg → OK
```
Cu doar `-i`, dpkg dă `requested operation requires superuser privilege`. Deci `-0` simplu
e singura variantă: root virtual, ownership-ul real rămâne al utilizatorului Termux.

### 2. dpkg are nevoie de hard link(2), pe care `/data` îl refuză

`ln a b` în proot → eșec. `dpkg` face „backup link" la despachetare
(`unable to make backup link of './usr/lib/aarch64-linux-gnu/gconv/ARMSCII-8.so'`), apoi
mai mult eșuează pe `/var/lib/dpkg/status-old`.

Rezolvare: `--link2symlink` **doar la pașii apt**. Over-global rupe multi-call binaries:
proot cere ca numele utilitarului să coincidă cu numele fișierului rezolvat, deci
`env -> /data/.../.l2s.coreutils...` moare cu
`Requested utility 'env' does not match executable name`.

### 3. Shims-urile `.l2s` trebuie materializate

După fiecare apt, dpkg lasă symlink-uri **absolute** spre `/data/...`:
`usr/bin/coreutils -> /data/.../usr/bin/.l2s.coreutils.dpkg-new0001` (120 de intrări).
Două probleme: (a) blochează pornirea guestului, (b) nu supraviețuiesc instalării pe
device, unde calea e alta. `fix_shims()` le înlocuiește cu copii reale, exact cum face
`extractZstdTar` pentru hard link-uri.

Detaliu care a costat timp: `find` rulează într-un subshell cu `cd "$ROOTFS"`, deci
produce căi **relative**; `readlink -f` le rezolvă față de cwd-ul scriptului și nu găsește
nimic, iar funcția ieșea tăcut cu `return 0`. Fix: prefix `$ROOTFS/` înainte de rezolvare.

### 4. `/proc`, `/sys`, `/dev` erau arhivate

Scriptul canonic rulează în chroot real, unde acestea nu sunt vizibile. În proot sunt
bind-uri, deci `tar -C / .` le includea și arhiva eșua. Fix: exclude-uri `--exclude=./proc`
etc. plus bind pe `/output`, ca bundle-ul să nu fie scris în `/tmp`, care e în arbore.

### 5. Node era rupt — și în scriptul canonic

Arhiva Node extrage în `node-v24.19.0-linux-arm64/`, dar scriptul crea
`ln -sfn /usr/local/lib/nodejs/node-v24.19.0 /usr/local/lib/nodejs/current` ⇒ dangling ⇒
`node: command not found`. Aplicația extrage **plat**
(`extractNodeArchive`, `substringAfter('/', "")` ⇒ `/usr/local/lib/nodejs/bin/node`) și
verifică `File(rootfs, "usr/local/bin/node").isFile`, deci ar fi eșuat cu
„Guest Node.js is missing". Fix în ambele scripturi: `--strip-components=1` + linkuri
relative ca cele din `installNodeIfNeeded`.

### 6. 486 MB → 85 MB: fereastra zstd

Binarul multi-call `usr/bin/coreutils` (uutils, 10.643.488 B) are **115 hard link-uri** în
tarball. Cum `/data` nu permite `link(2)`, extragerea produce 115 copii reale = 1,2 GB.
zstd `-19` are fereastra implicită de 8 MB, mai mică decât un fișier, deci nu putea
potrivi duplicatele:

| | 3 copii | rezultat |
|---|---|---|
| `zstd -19` | 10.594.494 B | fără deduplicare |
| `zstd -19 --long=23` | 10.594.160 B | fereastră tot prea mică |
| `zstd -19 --long=24` | 3.574.017 B | deduplică corect |

Roundtrip `--long=24` verificat: 31.930.464 B = 3 × 10.643.488.

Compactarea fermei în symlink-uri relative (rootfs 1,6 GB → 445 MB) **nu e utilizabilă**:
proot respinge `coreutils/env -> basename` cu
`Requested utility 'env' does not match executable name`. În schimb `extractZstdTar` poate
crea hard link-uri reale pe stocarea internă a app-ului, deci rootfs-ul extras e 445 MB.

### Bundle rezultat

`pocketdev-core-arm64-2026.10.1.tar.zst` — 84.766.979 B comprimat, 1.654.446.080 B necomprimat
(fără deduplicarea link-urilor), SHA256 `62fcc178df8846448a648aff2842d48a21b299261ecc79eb6a8149f632c3cb49`.

Verificat pe o extragere curată, cu flag-urile app-ului (`-0 --link2symlink`):

```
Ubuntu 26.04.1 LTS, VERSION_ID=26.04, glibc 2.43, bash 5.3.9
git 2.53.0, node v24.19.0, npm 11.17.0
python3: absent (overlay optional, PYTHON:false în .pocket-dev-stacks.json)
/bin,/sbin,/lib -> usr/*, /workspace și /opt/pocketdev prezente
/usr/local/bin/node: isFile și canExecute OK
0 symlink-uri spre /data, 0 fișiere .l2s, 0 intrări proc/sys/dev
marcatori: ubuntu-26.04.1-arm64, core-bundle-2026.10.1, ubuntu-maintenance-v2
```
