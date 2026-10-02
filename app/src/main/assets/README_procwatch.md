# Procwatch

Monitor de procese bazat exclusiv pe `/proc`, gândit pentru sandbox-uri
Android/PRoot unde `ps`, `htop` sau `/proc/stat` global nu sunt disponibile.
Zero dependențe externe — doar biblioteca standard Python 3.

```bash
python3 /root/procwatch.py
```

## Monitorizare prin adb (2procwatch.py)

`2procwatch.py` este varianta care rulează **pe calculator** și monitorizează
`/proc` al unui telefon conectat prin adb (inclusiv prin serverul Shizuku
care ascultă pe `127.0.0.1:PORT`):

```bash
python3 2procwatch.py --adb                        # primul dispozitiv conectat
python3 2procwatch.py --adb --serial 127.0.0.1:40383   # serverul Shizuku
python3 2procwatch.py --adb --once --plain --no-color  # o mostră, text simplu
python3 2procwatch.py --adb --export json          # snapshot JSON și ieșire
python3 2procwatch.py --adb --sort mem             # interfața interactivă
```

Cum funcționează:

- **Detectare Shizuku automată** — fără `--serial`, scriptul caută portul din
  `.mh-shizuku-port` (din cwd sau `~`), apoi porturile 20000–20006; primul care
  răspunde e folosit. Fiecare candidat e filtrat întâi printr-un probe TCP de
  0,5 s (portul închis e sărit instant), iar transporturile rămase „offline”
  în `adb devices` sunt curățate automat. Dacă niciun candidat nu e activ,
  cade pe adb clasic (primul dispozitiv din `adb devices`).
- O mostră = **2 apeluri adb**: `cat /proc/[0-9]*/stat` (globul e expandat de
  kernel, fiecare linie fiind auto-etichetată cu PID-ul) și `ps -A -o PID,UID,ARGS`
  (comenzi complete + UID, pentru că Android ascunde `cmdline` și `Uid:` din
  `status` pentru userul shell).
- Câmpurile fără sursă ieftină prin adb rămân `-`/0: swap pe proces, I/O,
  context switches, cwd. Detaliile unui proces selectat (status, wchan,
  environ) vin într-un **singur apel adb**, cu cache TTL de 2 s — panoul de
  detalii se poate redesena fără cost suplimentar.
- Tastele `k`/`K` trimit SIGTERM/SIGKILL prin `adb shell kill`.
- Fără `--adb`, scriptul se comportă identic cu `procwatch.py` (citire locală).

Diferențe cunoscute față de modul local: CPU% și TIME funcționează normal,
dar coloanele READ/WRITE/SWAP sunt goale, iar vizualizarea arborescentă
folosește PPID din `stat` (nemodificat).

## Funcții

- **Tabel adaptiv** — coloanele intră progresiv pe terminale înguste; linia
  comenzi umple restul spațiului, fără wrap la orice lățime
- **Sortare** — CPU, memorie, thread-uri, I/O, PID, nume (din taste sau CLI)
- **Tree view** — ierarhie părinte-copil cu indentare `└─`
- **Vizualizare simplă** — tasta `s`: doar PID, CPU%, RAM și Nume, ca un htop minimal
- **Zoom pe subarbore** — doar descendenții procesului selectat
- **Căutare live** — după comandă, nume sau PID, cu navigare între potriviri
- **Semnale** — SIGTERM/SIGKILL cu confirmare din două taste
- **Export** — snapshot JSON sau CSV, din CLI sau din interfață
- **Log JSONL** — istoric continuu al mostrelor pentru analiză ulterioară
- **Sparkline** — mini-grafice CPU% și RSS pentru procesul selectat
- **Pauză** — îngheață ecranul pentru inspectare
- **Zombie/orfani** — evidențiate vizual + contor în sumar
- **Copiere comandă** — salvează comanda completă (și environ-ul) a procesului selectat
- **Comenzi lungi** — wrapper-ele PRoot (comenzi > 80 char) evidențiate cu violet
- **Praguri de alertă** — culori și avertismente la depășirea CPU%/RSS
- **Configurare** — `~/.procwatchrc` cu valori implicite
- **Mouse** — click pentru selecție, rotiță pentru derulare

## Utilizare din linia de comandă

```bash
python3 /root/procwatch.py                        # interfață interactivă
python3 /root/procwatch.py --sort mem --interval 0.5
python3 /root/procwatch.py --pid 6786             # doar PID + descendenți
python3 /root/procwatch.py --tree                 # pornire în tree view
python3 /root/procwatch.py --once --plain         # o mostră, text simplu
python3 /root/procwatch.py --log /root/istoric.jsonl   # log JSONL continuu
python3 /root/procwatch.py --export json          # snapshot JSON și ieșire
python3 /root/procwatch.py --export csv
python3 /root/procwatch.py --log /root/istoric.jsonl   # log JSONL continuu
python3 /root/procwatch.py --cpu-alert 90 --rss-alert-mib 1024
python3 /root/procwatch.py --config /cale/la.conf # alt fișier de configurare
```

## Taste (mod interactiv)

| Tasta | Acțiune |
|---|---|
| `c` `m`/`r` `t` `i` `p` `N` | sortare: CPU, memorie, thread-uri, I/O, PID, nume |
| `T` | pornește/oprește tree view |
| `s` | vizualizare simplă: doar PID, CPU%, RAM, Nume (reapasă pentru tabel complet) |
| `z` / `Z` | zoom pe subarborele procesului selectat / ieșire din zoom |
| `/` | căutare live; `n` = următoarea potrivire; `Esc` = anulează |
| `k` | SIGTERM procesului selectat (a doua apăsare confirmă) |
| `K` | SIGKILL (confirmare identică) |
| `y` | salvează comanda completă a procesului selectat în /root |
| `Y` | la fel, plus environ-ul (linii `export VAR=...`) |
| `e` | exportă snapshot-ul (alternează JSON → CSV) |
| `l` | pornește/oprește logarea JSONL |
| `<` / `>` | scade/crește pragul CPU de alertă cu 5% |
| `,` / `.` | scade/crește pragul RSS de alertă cu 64 MiB |
| `space` | pauză / reia |
| `+` / `-` | interval de refresh mai mic / mai mare |
| `R` | inversează ordinea sortării |
| săgeți / PgUp / PgDn | navigare |
| mouse | click = selecție, rotiță = derulare |
| `q` | ieșire |

## Fișier de configurare `~/.procwatchrc`

```ini
[procwatch]
interval = 0.5        ; secunde între mostre (implicit 1.0)
sort = cpu            ; cpu|mem|threads|io|pid|name
tree = false          ; pornește în vizualizare arborescentă
reverse = false       ; ordine inversă la sortare
cpu_alert = 80        ; prag alertă CPU% (100% = un nucleu)
rss_alert_mib = 512   ; prag alertă RSS în MiB
```

Argumentele din linia de comandă au întotdeauna prioritate față de config.
Un exemplu comentat există deja în `/root/.procwatchrc`.

## Formatul logului JSONL

Fiecare mostră este o linie JSON:

```json
{
  "kind": "sample",
  "timestamp": "2026-09-27 19:55:01",
  "unreadable": 0,
  "mem_total_kib": 11770240,
  "mem_available_kib": 3943116,
  "processes": [
    {"pid": 123, "ppid": 1, "state": "S", "threads": 4,
     "cpu_percent": 12.5, "rss_kib": 33672, "command": "...", "depth": 0}
  ]
}
```

Analiză rapidă:

```bash
python3 - <<'EOF'
import json
peaks = {}
for line in open("/root/istoric.jsonl"):
    for p in json.loads(line)["processes"]:
        name = p["command"][:40]
        peaks[name] = max(peaks.get(name, 0), p["cpu_percent"] or 0)
for name, cpu in sorted(peaks.items(), key=lambda kv: -kv[1])[:10]:
    print(f"{cpu:6.1f}%  {name}")
EOF
```

## Coduri de ieșire

- `0` — succes
- `1` — eroare (interfață incompatibilă, export fără procese)
- `2` — PID-ul dat prin `--pid` nu există sau nu e accesibil
- `130` — întrerupt cu Ctrl+C

## Coduri culoare (procese)

- **Roșu** — peste pragurile de alertă CPU/RSS
- **Galben** — zombie
- **Verde** — orfani (părinte dispărut)
- **Violet** — comenzi foarte lungi (tipic wrapper-e PRoot)
- Inversat/albastru — procesul selectat

## Limitări pe Android/PRoot

- `/proc/stat` global e ascuns de Android, deci nu există CPU% total;
  CPU% per proces este relativ la un singur nucleu (100% = un nucleu întreg)
- Procesele altor UID apar cu câmpuri parțiale; contoarele de I/O
  (`/proc/PID/io`) pot lipsi
- Conteaurul „neaccesibile" din sumar arată câte procesoare n-au putut fi citite

## Testare

```bash
python3 /root/test_procwatch_pty.py
```

Suita rulează 39 de verificări interactive printr-un pty real: kill flow,
căutare, tree/zoom, pauză, export, log, mouse, config, praguri, vizualizarea
simplă și copierea comenzilor.
