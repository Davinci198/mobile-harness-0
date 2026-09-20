# PLAN — Terminal PTY real in mobile-harness-0

Scop: inlocuieste TerminalScreen (linii text, fara fullscreen) cu emulator VT adevarat,
 ca htop/vim/freebuff-TUI sa randeze corect in aplicatie.

## Piese disponibile local (nu se scriu de la zero)
- `~/termux-app/terminal-emulator` (917K): `TerminalEmulator`, `TerminalBuffer`,
  `TerminalColors`, `KeyHandler`, `ByteQueue`, `JNI` — parser VT100/ANSI complet.
- `~/termux-app/terminal-view` (502K): `TerminalView`, `TerminalRenderer`,
  selectie text, gesture/scale — View Android gata.
- `~/termux-app/app/src/main/cpp/termux.c` (referinta): cum se face fork+PTY via JNI.
- Mobile-Harness are deja: `pocket_spawn.c` (posix_spawn, pipe pump), PRoot Ubuntu,
  foreground service + WakeLock.

## Pasi
0. **Inventar** (acum): lista fisiere/dependinte exacte ale celor 2 module + JNI PTY din termux.c.
1. **Copiere module**: `terminal-emulator/`, `terminal-view/` in mobile-harness-0
   (`third_party/termux-terminal/`), `settings.gradle` include, build verde, fara utilizare inca.
2. **JNI PTY**: extinde `pocket_spawn.c` (sau modul JNI nou) cu `pty_fork()` dupa modelul
   `termux.c`: master fd → `TerminalSession`, slave → proces in PRoot. Spike test: `ls`, apoi `vim`.
3. **Renderer**: `TerminalScreen.kt` (545 linii, LazyColumn text) → gazduieste `TerminalView`
   prin `AndroidView` interop; input existent (butoane) ramane, se adauga `KeyHandler`.
4. **Input complet**: tastatura soft, taste speciale ( ceilalti pasi: ESC/CTRL/ALT exista deja ca butoane),
   scrollback, marime font, culori — mapate pe API-ul TerminalView.
5. **APK + validare pe telefon**: htop, vim, `freebuff` TUI (opencode are nevoie de PTY real).
   Criteriu de accept: cele 3 randeaza fullscreen fara artefacte.

## Riscuri
- Licenta: codul Termux e GPL — repo-ul nostru e privat, deci OK intern; la distributie
  publica ar trebui sursa deschisa (GPL). Decidem atunci.
- Compose interop cu TerminalView (View clasic): pattern standard `AndroidView`, risc mic.
- PTY in PRoot: slave pty trebuie vizibil in sandbox (bind /dev/pts) — de verificat la pasul 2.
- Marime APK: +~1.5MB cod, neglijabil.

## Non-obiective (acum)
- Nu rescriem agentii/providerele; nu atingem Ubuntu rootfs; nu publicam nimic.
