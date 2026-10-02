#!/usr/bin/env python3
"""Monitor de procese bazat pe /proc, pentru sandbox-uri Android/PRoot.

Nu este dependent de ps sau htop. În modul interactiv oferă sortare, selecție,
refresh, culori, detalii despre proces, vizualizare arborescentă, căutare și
trimitere de semnale. Coloanele se adaptează automat la lățimea terminalului.

Utilizare (local, citește /proc direct):

    python3 2procwatch.py
    python3 2procwatch.py --sort mem --interval 0.5
    python3 2procwatch.py --pid 6786
    python3 2procwatch.py --tree
    python3 2procwatch.py --once --no-color

Utilizare de la distanță — monitorizează /proc al unui telefon conectat
prin adb (ex. serverul Shizuku, expus pe 127.0.0.1:PORT):

    python3 2procwatch.py --adb                       # primul dispozitiv
    python3 2procwatch.py --adb --serial 127.0.0.1:40383
    python3 2procwatch.py --adb --once --no-color     # o mostră, text simplu

Fișier de configurare (opțional): ~/.procwatchrc — INI simplu, ex.:

    [procwatch]
    interval = 0.5
    sort = mem
    tree = true
    reverse = false
    cpu_alert = 80
    rss_alert_mib = 512

Mod log (JSONL): --log FIȘIER sau tasta l scrie fiecare mostră ca linie JSON
( „kind": "sample" ) cu procesele vizibile; Ctrl+Oprit închide curat.

Taste (mod interactiv):
    c=cpu m/r=memorie t=fire i=IO p=PID N=nume  sortare
    s    vizualizare simplă: doar PID, CPU%, RAM și Nume;
         comanda completă a procesului selectat apare în panoul de detalii
    y    salvează comanda completă a procesului selectat în /root
    Y    la fel, plus environ-ul procesului (linii export VAR=...)
    T    comută vizualizarea arborescentă
    z    subarbore: doar descendenții procesului selectat (zoom)
    /    căutare live în comandă/nume/PID; n=salt la următoarea potrivire
    k    SIGTERM procesului selectat (apasă de două ori pentru confirmare)
    K    SIGKILL procesului selectat (confirmare după k, sau K de două ori)
    e    exportă snapshot-ul curent (JSON/CSV, alternativ) în /root
    l    pornește/oprește logarea JSONL (fișier cerut în status)
    < / >   scade/crește pragul CPU de alertă cu 5
    , / .   scade/crește pragul RSS de alertă cu 64 MiB
    space pauză/reia actualizarea (istoricul rămâne afișat)
    R    ordine inversă; +/-=interval; q=ieșire; Esc=anulează
    mouse click selectează rândul; rotița derulează selecția
"""

import argparse
import configparser
import curses
import csv
import glob
import io as io_module
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

try:
    CLOCK_TICKS = os.sysconf("SC_CLK_TCK")
    PAGE_SIZE = os.sysconf("SC_PAGE_SIZE")
except (ValueError, OSError):
    CLOCK_TICKS = 100
    PAGE_SIZE = 4096

STATE_NAMES = {
    "R": "Running",
    "S": "Sleeping",
    "D": "Uninterruptible wait",
    "Z": "Zombie",
    "T": "Stopped",
    "t": "Tracing stop",
    "I": "Idle",
    "X": "Dead",
    "x": "Dead",
    "K": "Wakekill",
    "W": "Waking",
    "P": "Parked",
}

# ------------------------------------------------------------------ coloane
# Fiecare coloană are lățimea fixă (inclusiv un spațiu separator la final).
COLUMN_WIDTHS = {
    "pid": 8,
    "ppid": 8,
    "uid": 6,
    "pri": 4,
    "ni": 4,
    "thr": 5,
    "st": 3,
    "cpu": 7,
    "mem": 7,
    "rss": 10,
    "virt": 10,
    "read": 9,
    "write": 9,
    "time": 10,
    "swap": 8,
}
COLUMN_HEADERS = {
    "pid": "PID",
    "ppid": "PPID",
    "uid": "UID",
    "pri": "PRI",
    "ni": "NI",
    "thr": "THR",
    "st": "ST",
    "cpu": "CPU%",
    "mem": "MEM%",
    "rss": "RSS",
    "virt": "VIRT",
    "read": "READ",
    "write": "WRITE",
    "time": "TIME",
    "swap": "SWAP",
}
# Ordinea de afișare a coloanelor.
ALL_COLUMNS = [
    "pid", "ppid", "uid", "pri", "ni", "thr", "st",
    "cpu", "mem", "rss", "virt", "read", "write", "time", "swap",
]
# Ordinea de prioritate: coloanele mai importante intră primele pe ecrane mici.
COLUMN_PRIORITY = [
    "pid", "st", "cpu", "mem", "rss", "ppid", "thr",
    "time", "uid", "ni", "pri", "virt", "write", "read", "swap",
]
MIN_COMMAND_WIDTH = 12
MIN_TREE_WIDTH = 70  # sub această lățime tree-ul revine automat la listă

# Lățimi pentru vizualizarea simplă (tasta s): PID, CPU%, RAM, Nume.
SIMPLE_COLUMNS = ["pid", "cpu", "rss"]
SIMPLE_HEADERS = {"pid": "PID", "cpu": "CPU%", "rss": "RAM"}
SIMPLE_WIDTHS = {"pid": 8, "cpu": 7, "rss": 10}


def simple_command_width(width: int) -> int:
    """În modul simplu, comanda primește tot spațiul rămas (fără truncare devreme)."""
    used = sum(SIMPLE_WIDTHS.values()) + 2
    return max(width - used, MIN_COMMAND_WIDTH)
# Caractere pentru mini-graficele istorice (sparkline).
SPARK_CHARS = "▁▂▃▄▅▆▇█"
# Mapare sortare -> coloană evidențiată în header.
SORT_COLUMN_MAP = {
    "cpu": "cpu",
    "mem": "mem",
    "threads": "thr",
    "io": "read",
    "pid": "pid",
    "name": "command",
}
# Stări care indică procese problemă, evidențiate aparte.
ZOMBIE_STATES = {"Z", "X", "x"}
# Praguri implicite de alertă (suprascriabile din ~/.procwatchrc sau CLI).
DEFAULT_CPU_ALERT = 80.0   # % CPU (100% = un nucleu complet)
DEFAULT_RSS_ALERT_MIB = 512.0  # MiB rezidenți
# Comenzile mai lungi de atât (wrapper-e PRoot etc.) primesc evidențiere violet.
LONG_COMMAND_THRESHOLD = 80
# Sufixe booleene acceptate în fișierul de configurare.
TRUE_WORDS = {"1", "true", "yes", "on", "da"}
FALSE_WORDS = {"0", "false", "no", "off", "nu"}
# BUTTON5_PRESSED lipsește din curses în Python < 3.10; folosim valoarea ncurses.
BUTTON5_PRESSED = getattr(curses, "BUTTON5_PRESSED", 1 << 21)  # 0x200000


@dataclass
class Process:
    pid: int
    ppid: int
    uid: int
    name: str
    state: str
    command: str
    threads: int
    nice: int
    priority: int
    rss_kib: int
    vms_kib: int
    swap_kib: int
    cpu_ticks: int
    children_ticks: int
    voluntary_switches: int
    nonvoluntary_switches: int
    read_bytes: Optional[int]
    write_bytes: Optional[int]
    exe: str
    cwd: str
    cpu_percent: Optional[float] = None
    read_rate: Optional[float] = None
    write_rate: Optional[float] = None
    switch_rate: Optional[float] = None

    @property
    def state_name(self) -> str:
        return STATE_NAMES.get(self.state, self.state)

    @property
    def context_switches(self) -> int:
        return self.voluntary_switches + self.nonvoluntary_switches

    @property
    def is_zombie(self) -> bool:
        return self.state in ZOMBIE_STATES

    @property
    def is_orphan(self) -> bool:
        # Orfan: părintele nu mai există în snapshot (nu e iniț cu pid 1).
        return self.ppid not in (0, 1, self.pid)


def read_text(path: str) -> str:
    if ADB is not None:
        return ADB.read_text(path)
    with open(path, "r", errors="replace") as handle:
        return handle.read()


def parse_key_values(text: str) -> Dict[str, str]:
    result = {}
    for line in text.splitlines():
        key, separator, value = line.partition(":")
        if separator:
            result[key] = value.strip()
    return result


def integer(value: str, default: int = 0) -> int:
    try:
        return int(value.split()[0])
    except (ValueError, IndexError):
        return default


def safe_readlink(path: str) -> str:
    if ADB is not None:
        return ADB.readlink(path)
    try:
        return os.readlink(path)
    except OSError:
        return "-"


def read_memory() -> Dict[str, int]:
    values = {}
    try:
        for key, value in parse_key_values(read_text("/proc/meminfo")).items():
            if value:
                values[key] = integer(value)
    except OSError:
        pass
    return values


def device_cpu_count() -> int:
    """Nucleele dispozitivului monitorizat (locale sau prin adb)."""
    global ADB_CORES
    if ADB is not None:
        if ADB_CORES is None:
            ADB_CORES = ADB.cpu_count()
        return ADB_CORES
    return os.cpu_count() or 1


# ------------------------------------------------------------------ backend ADB
# Când rulează cu --adb, scriptul nu mai citește /proc local: trimite comenzi
# către telefon prin `adb shell`. Latența USB/TCP face o citire individuală
# pe proces inacceptabil de lentă, deci scanarea face UN singur apel
# subprocess pe mostră: un shell concatenează toate fișierele /proc/PID/*
# cu delimitatori, iar parsarea aici reia blocurile.

ADB_DELIMITER = "\n__PROCWATCH_EOF__\n"


class AdbBackend:
    """Adapter: expune aceleași primitive ca citirea locală a /proc."""

    def __init__(self, serial: Optional[str] = None) -> None:
        self.serial = serial
        self._failed = False
        base = ["adb"]
        if serial:
            base += ["-s", serial]
        self._base = base

    # ---- intern -------------------------------------------------------
    def _run(self, remote_command: str, timeout: float = 15.0) -> str:
        command = self._base + ["shell", remote_command]
        try:
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=timeout,
            )
        except (OSError, subprocess.TimeoutExpired):
            self._failed = True
            return ""
        self._failed = result.returncode != 0
        return result.stdout

    # ---- primitive ----------------------------------------------------
    def read_text(self, path: str) -> str:
        return self._run(f"cat '{path}' 2>/dev/null")

    def readlink(self, path: str) -> str:
        out = self._run(f"readlink '{path}' 2>/dev/null").strip()
        return out if out else "-"

    def kill(self, pid: int, signum: int) -> None:
        self._run(f"kill -{signum} {pid} 2>/dev/null")

    def cpu_count(self) -> int:
        out = self._run("nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo").strip()
        try:
            return int(out.split()[0])
        except (ValueError, IndexError):
            return os.cpu_count() or 1

    def ping(self) -> Tuple[bool, str]:
        out = self._run("echo procwatch-ok", timeout=10.0).strip()
        if "procwatch-ok" in out:
            label = self.serial or "implicit (primul dispozitiv)"
            return True, label
        return False, "adb shell nu răspunde (dispozitiv conectat? serverul Shizuku pornit?)"


# Instances globales configurate de main(); None = citire locală din /proc.
ADB: Optional[AdbBackend] = None
ADB_CORES: Optional[int] = None


def set_adb_backend(backend: Optional[AdbBackend]) -> None:
    global ADB, ADB_CORES
    ADB = backend
    ADB_CORES = None


def adb_available() -> bool:
    return ADB is not None


def _parse_process(
    pid: int,
    status: Dict[str, str],
    stat_text: str,
    raw_cmdline: str,
    exe: str,
    cwd: str,
    io_text: str,
) -> Process:
    """Construiește Process din conținutul deja citit al fișierelor /proc/PID."""
    opening = stat_text.find("(")
    closing = stat_text.rfind(")")
    if opening < 0 or closing < opening:
        raise ValueError("invalid /proc/PID/stat")

    name = stat_text[opening + 1:closing]
    fields = stat_text[closing + 2:].split()
    if len(fields) < 22:
        raise ValueError("short /proc/PID/stat")

    command_parts = [part for part in raw_cmdline.split("\0") if part and part.strip()]
    command = " ".join(command_parts)

    io_values = parse_key_values(io_text)
    read_bytes = integer(io_values.get("read_bytes", "-1"), -1)
    write_bytes = integer(io_values.get("write_bytes", "-1"), -1)
    if read_bytes < 0:
        read_bytes = None
    if write_bytes < 0:
        write_bytes = None

    rss_kib = integer(status.get("VmRSS", "0"))
    if not rss_kib:
        rss_kib = (int(fields[21]) * PAGE_SIZE) // 1024
    vms_kib = int(fields[20]) // 1024

    return Process(
        pid=pid,
        ppid=int(fields[1]),
        uid=integer(status.get("Uid", "0")),
        name=name,
        state=fields[0],
        command=command or exe or f"[{name}]",
        threads=int(fields[17]),
        nice=int(fields[16]),
        priority=int(fields[15]),
        rss_kib=rss_kib,
        vms_kib=vms_kib,
        swap_kib=integer(status.get("VmSwap", "0")),
        cpu_ticks=int(fields[11]) + int(fields[12]),
        children_ticks=int(fields[13]) + int(fields[14]),
        voluntary_switches=integer(status.get("voluntary_ctxt_switches", "0")),
        nonvoluntary_switches=integer(status.get("nonvoluntary_ctxt_switches", "0")),
        read_bytes=read_bytes,
        write_bytes=write_bytes,
        exe=exe,
        cwd=cwd,
    )


def read_process(pid: int, path: str) -> Process:
    status = parse_key_values(read_text(f"{path}/status"))
    stat_text = read_text(f"{path}/stat")
    raw_cmdline = read_text(f"{path}/cmdline")
    io_text = ""
    try:
        io_text = read_text(f"{path}/io")
    except OSError:
        pass
    return _parse_process(
        pid,
        status,
        stat_text,
        raw_cmdline,
        safe_readlink(f"{path}/exe"),
        safe_readlink(f"{path}/cwd"),
        io_text,
    )


def _scan_one(process_path: str) -> Optional[Process]:
    name = os.path.basename(process_path)
    if not name.isdigit():
        return None
    try:
        return read_process(int(name), process_path)
    except (OSError, ValueError, IndexError):
        return None


def scan_processes_local() -> Tuple[Dict[int, Process], int]:
    """Citire directă din /proc (rulare locală, fără --adb)."""
    paths = glob.glob("/proc/[0-9]*")
    processes: Dict[int, Process] = {}

    if len(paths) >= 64:
        try:
            with ThreadPoolExecutor(max_workers=8) as executor:
                for process in executor.map(_scan_one, paths):
                    if process is not None:
                        processes[process.pid] = process
        except OSError:
            processes = {}
            for process_path in paths:
                process = _scan_one(process_path)
                if process is not None:
                    processes[process.pid] = process
    else:
        for process_path in paths:
            process = _scan_one(process_path)
            if process is not None:
                processes[process.pid] = process

    return processes, len(paths) - len(processes)


def _parse_ps_output(ps_output: str) -> Tuple[Dict[int, str], Dict[int, int]]:
    """Extrage comenzile și UID-urile din `ps -A -o PID,UID,ARGS`."""
    commands: Dict[int, str] = {}
    uids: Dict[int, int] = {}
    for index, line in enumerate(ps_output.splitlines()):
        if index == 0 and "PID" in line:
            continue  # antetul
        parts = line.strip().split(None, 2)
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        pid = int(parts[0])
        if parts[1].isdigit():
            uids[pid] = int(parts[1])
        commands[pid] = parts[2].strip()
    return commands, uids


# Detalii extra per PID (status/wchan/environ) citite prin adb, cu cache TTL
# scurt: panoul de detalii se redesenează des, dar nu trebuie să coste 3
# apeluri adb la fiecare redare pentru aceleași date.
_ADB_DETAIL_CACHE: Dict[int, Tuple[float, Dict[str, str], str, List[str]]] = {}
_ADB_DETAIL_TTL = 2.0


def _adb_read_details(pid: int) -> Tuple[Dict[str, str], str, List[str]]:
    """status + wchan + environ pentru UN PID, într-un singur apel adb."""
    cached = _ADB_DETAIL_CACHE.get(pid)
    now = time.monotonic()
    if cached is not None and now - cached[0] < _ADB_DETAIL_TTL:
        return cached[1], cached[2], cached[3]
    assert ADB is not None
    raw = ADB._run(
        f'echo __PW_S__; cat /proc/{pid}/status 2>/dev/null; '
        f'echo __PW_W__; cat /proc/{pid}/wchan 2>/dev/null; '
        f'echo __PW_E__; cat /proc/{pid}/environ 2>/dev/null',
        timeout=10.0,
    )
    status_text, wchan_text, environ_text = "", "", ""
    section = None
    markers = (("__PW_S__", "s"), ("__PW_W__", "w"), ("__PW_E__", "e"))
    for line in raw.splitlines(keepends=True):
        stripped = line.rstrip("\n")
        core = stripped.strip()
        # Marker pe linie proprie sau lipit de conținut (când fișierul citit
        # nu se termină cu newline — ex. wchan, environ).
        matched = None
        for marker, name in markers:
            if core == marker:
                matched = (marker, name, "")
                break
            if stripped.endswith(marker) and core.endswith(marker):
                matched = (marker, name, stripped[: -len(marker)])
                break
        if matched is not None:
            marker, name, remainder = matched
            if remainder and section is not None:
                if section == "s":
                    status_text += remainder + "\n"
                elif section == "w":
                    wchan_text += remainder + "\n"
                elif section == "e":
                    environ_text += remainder + "\n"
            section = name
            if name == "s":
                status_text = ""
            elif name == "w":
                wchan_text = ""
            else:
                environ_text = ""
            continue
        if section == "s":
            status_text += line
        elif section == "w":
            wchan_text += line
        elif section == "e":
            environ_text += line
    variables = [part for part in environ_text.split("\0") if part and part.strip()]
    result = (parse_key_values(status_text), wchan_text.strip(), variables)
    if len(_ADB_DETAIL_CACHE) > 256:
        _ADB_DETAIL_CACHE.clear()  # limită de siguranță pentru sesiuni lungi
    _ADB_DETAIL_CACHE[pid] = (now, *result)
    return result


def scan_processes_adb() -> Tuple[Dict[int, Process], int]:
    """Scanare prin adb shell: 2-3 apeluri subprocess pe mostră.

    Bucla shell per-PID (un `cat` pe fișier × ~900 PID-uri) duce la peste o
    mie de spawn-uri remote — inutilizabil de lent prin adb. În schimb:
    - `cat /proc/[0-9]*/stat` — globul e expandat de kernel; fiecare linie
      începe cu "<pid> (com) ...", deci e auto-etichetată și conține tot ce
      ține de tabel: stare, ppid, tick-uri CPU, thread-uri, prio, nice, vsize;
    - `ps -A -o PID,UID,ARGS` — comandă completă + UID (Android ascunde
      Uid: din status pentru shell, iar cmdline e adesea gol/ascuns);
    - `cat /proc/meminfo` — se citește separat, la cerere (read_memory).
    Câmpurile care nu au sursă ieftină prin adb rămân 0/"-": swap, I/O,
    context switches, cwd. Detaliile unui proces ales le citește individual
    (read_text e rutat prin adb) — latența nu contează pentru un singur PID.
    """
    assert ADB is not None
    stat_output = ADB._run("cat /proc/[0-9]*/stat 2>/dev/null", timeout=20.0)
    ps_output = ADB._run("ps -A -o PID,UID,ARGS 2>/dev/null", timeout=20.0)
    if not stat_output.strip() and not ps_output.strip():
        return {}, 0

    commands, uids = _parse_ps_output(ps_output)
    processes: Dict[int, Process] = {}
    total = 0
    for line in stat_output.splitlines():
        if not line.strip():
            continue
        total += 1
        prefix = line.split("(", 1)[0].strip()
        if not prefix.isdigit():
            continue
        pid = int(prefix)
        command = commands.get(pid, "")
        # exe aproximat din primul token absolut al comenzii; cwd nu e disponibil.
        first_token = command.split(" ", 1)[0] if command else ""
        exe = first_token if first_token.startswith("/") else "-"
        try:
            process = _parse_process(
                pid,
                {"Uid": str(uids.get(pid, 0))},
                line,
                command,
                exe,
                "-",
                "",
            )
        except (ValueError, IndexError):
            continue
        processes[process.pid] = process
    return processes, max(0, total - len(processes))


def scan_processes() -> Tuple[Dict[int, Process], int]:
    """Citește toate procesele; la listă mare citește în paralel (local)."""
    if ADB is not None:
        return scan_processes_adb()
    return scan_processes_local()


def calculate_rates(
    current: Dict[int, Process],
    previous: Dict[int, Process],
    previous_time: float,
    current_time: float,
) -> None:
    elapsed = max(current_time - previous_time, 0.001)
    for pid, process in current.items():
        old = previous.get(pid)
        if old is None:
            continue
        if process.cpu_ticks >= old.cpu_ticks:
            process.cpu_percent = (
                (process.cpu_ticks - old.cpu_ticks) / CLOCK_TICKS / elapsed * 100.0
            )

        switches = process.context_switches
        old_switches = old.context_switches
        if switches >= old_switches:
            process.switch_rate = (switches - old_switches) / elapsed

        if process.read_bytes is not None and old.read_bytes is not None:
            if process.read_bytes >= old.read_bytes:
                process.read_rate = (process.read_bytes - old.read_bytes) / elapsed
        if process.write_bytes is not None and old.write_bytes is not None:
            if process.write_bytes >= old.write_bytes:
                process.write_rate = (process.write_bytes - old.write_bytes) / elapsed


def orphan_count(processes: Dict[int, Process]) -> int:
    """Procese vii al căror părinte a dispărut (excl. PID 1 și iniț)."""
    return sum(
        1
        for process in processes.values()
        if not process.is_zombie
        and process.ppid not in (0, 1)
        and process.ppid not in processes
    )

def filter_processes(
    processes: Dict[int, Process], root_pid: Optional[int]
) -> List[Process]:
    if root_pid is None:
        return list(processes.values())
    if root_pid not in processes:
        return []

    children = {}
    for process in processes.values():
        children.setdefault(process.ppid, []).append(process.pid)

    selected = {root_pid}
    pending = [root_pid]
    while pending:
        parent = pending.pop()
        for child in children.get(parent, []):
            if child not in selected:
                selected.add(child)
                pending.append(child)
    return [processes[pid] for pid in selected]


def matches_search(process: Process, term: str) -> bool:
    lowered = term.lower()
    return (
        lowered in process.command.lower()
        or lowered in process.name.lower()
        or lowered == str(process.pid)
    )


def sort_key(process: Process, sort_mode: str):
    if sort_mode == "pid":
        return process.pid
    if sort_mode == "mem":
        return process.rss_kib
    if sort_mode == "threads":
        return process.threads
    if sort_mode == "io":
        return (process.read_rate or 0) + (process.write_rate or 0)
    if sort_mode == "name":
        return process.command.lower()
    return process.cpu_percent if process.cpu_percent is not None else -1.0


def sort_processes(
    processes: List[Process], sort_mode: str, descending: bool = True
) -> List[Process]:
    return sorted(processes, key=lambda process: sort_key(process, sort_mode), reverse=descending)


def tree_order(
    processes: List[Process], sort_mode: str, descending: bool = True
) -> List[Tuple[Process, int]]:
    """Ordonare topologică părinte-copil; frații păstrează sortarea curentă."""
    by_pid = {process.pid: process for process in processes}
    ordered = sorted(processes, key=lambda process: sort_key(process, sort_mode), reverse=descending)
    children: Dict[int, List[Process]] = {}
    for process in ordered:
        parent = process.ppid if process.ppid in by_pid else 0
        children.setdefault(parent, []).append(process)

    result: List[Tuple[Process, int]] = []
    visited = set()

    def walk(pid: int, depth: int) -> None:
        for child in children.get(pid, ()):
            if child.pid in visited:
                continue
            visited.add(child.pid)
            result.append((child, depth))
            walk(child.pid, depth + 1)

    walk(0, 0)  # rădăcini (părinte inexistent sau vizibil incomplet)
    for process in ordered:  # siguranță la cicluri / orfani rămași
        if process.pid not in visited:
            visited.add(process.pid)
            result.append((process, 0))
            walk(process.pid, 0)
    return result


def compute_columns(width: int) -> Tuple[List[str], int]:
    """Alege coloanele care încape, în ordine de prioritate, și lățimea pt. comandă."""
    active: List[str] = []
    used = 2  # separatorul dublu dinaintea coloanei Command
    for column in COLUMN_PRIORITY:
        column_width = COLUMN_WIDTHS[column]
        if width - (used + column_width) < MIN_COMMAND_WIDTH:
            break
        active.append(column)
        used += column_width
    command_width = max(width - used, MIN_COMMAND_WIDTH)
    active.sort(key=ALL_COLUMNS.index)
    return active, command_width


def column_value(process: Process, column: str, memory_total_kib: int) -> str:
    if column == "pid":
        return f"{process.pid:>7} "
    if column == "ppid":
        return f"{process.ppid:>7} "
    if column == "uid":
        return f"{process.uid:>5} "
    if column == "pri":
        return f"{process.priority:>3} "
    if column == "ni":
        return f"{process.nice:>3} "
    if column == "thr":
        return f"{process.threads:>4} "
    if column == "st":
        return f"{process.state:>2} "
    if column == "cpu":
        return f"{percent(process.cpu_percent):>6} "
    if column == "mem":
        memory_pct = process.rss_kib / memory_total_kib * 100 if memory_total_kib else 0
        return f"{memory_pct:>6.2f} "
    if column == "rss":
        return f"{process.rss_kib / 1024:>8.1f}M "
    if column == "virt":
        return f"{process.vms_kib / 1024:>8.1f}M "
    if column == "read":
        return f"{format_rate(process.read_rate):>8} "
    if column == "write":
        return f"{format_rate(process.write_rate):>8} "
    if column == "time":
        return f"{format_duration(process.cpu_ticks / CLOCK_TICKS):>9} "
    if column == "swap":
        return f"{process.swap_kib / 1024:>6.1f}M "
    return " " * COLUMN_WIDTHS.get(column, 1)


def header_segments(
    columns: List[str], command_width: int, simple_mode: bool = False
) -> List[Tuple[str, Optional[str]]]:
    """Segmentele header-ului, cu cheia coloanei pentru evidențierea sortării."""
    if simple_mode:
        segments = [
            (f"{SIMPLE_HEADERS[column]:>{SIMPLE_WIDTHS[column] - 1}} ", column)
            for column in SIMPLE_COLUMNS
        ]
    else:
        segments = [
            (f"{COLUMN_HEADERS[column]:>{COLUMN_WIDTHS[column] - 1}} ", column)
            for column in columns
        ]
    segments.append(("  " + f"{'Command':<{command_width}}", "command"))
    return segments


def render_header(columns: List[str], command_width: int) -> str:
    return "".join(text for text, _ in header_segments(columns, command_width))


def sparkline(values, width: int = 40) -> Tuple[str, float]:
    """Mini-grafic ASCII din ultimele `width` valori; întoarce (bara, maximul)."""
    points = [value for value in list(values)[-width:] if value is not None]
    if not points:
        return "", 0.0
    peak = max(points)
    scale = peak if peak > 0 else 1.0
    top = len(SPARK_CHARS) - 1
    bar = "".join(
        SPARK_CHARS[min(int(value / scale * top), top)] for value in points
    )
    return bar, peak


def render_row(
    process: Process,
    depth: int,
    columns: List[str],
    command_width: int,
    memory_total_kib: int,
    simple_mode: bool = False,
) -> str:
    if simple_mode:
        parts = [column_value(process, column, memory_total_kib) for column in SIMPLE_COLUMNS]
    else:
        parts = [column_value(process, column, memory_total_kib) for column in columns]
    prefix = ""
    if depth:
        prefix = "  " * min(depth - 1, 8) + "└─ "
    command = process.command.replace("\t", " ")
    available = max(command_width - len(prefix), 1)
    if len(command) > available:
        command = command[: max(available - 3, 1)] + "..."
    return "".join(parts) + "  " + prefix + command


def format_duration(seconds: float) -> str:
    seconds = max(0, int(seconds))
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"


def format_size(kib: float) -> str:
    if kib >= 1024 * 1024:
        return f"{kib / (1024 * 1024):.1f}G"
    if kib >= 1024:
        return f"{kib / 1024:.1f}M"
    return f"{kib:.0f}K"


def format_bytes(value: Optional[int]) -> str:
    if value is None:
        return "-"
    value = float(value)
    if value >= 1024 * 1024 * 1024:
        return f"{value / (1024 * 1024 * 1024):.1f}G"
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.1f}M"
    if value >= 1024:
        return f"{value / 1024:.1f}K"
    return f"{value:.0f}B"


def format_rate(bytes_per_second: Optional[float]) -> str:
    if bytes_per_second is None:
        return "-"
    value = float(bytes_per_second)
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.1f}M/s"
    if value >= 1024:
        return f"{value / 1024:.1f}K/s"
    return f"{value:.0f}B/s"


def percent(value: Optional[float]) -> str:
    return "-" if value is None else f"{value:.1f}"


def memory_summary(
    memory: Dict[str, int],
    visible: List[Tuple[Process, int]],
    unreadable: int,
    zombies: int = 0,
    orphans: int = 0,
) -> str:
    total = memory.get("MemTotal", 0)
    available = memory.get("MemAvailable", memory.get("MemFree", 0))
    used = max(total - available, 0)
    memory_pct = used / total * 100 if total else 0

    swap_total = memory.get("SwapTotal", 0)
    swap_free = memory.get("SwapFree", 0)
    swap_used = max(swap_total - swap_free, 0)
    swap_pct = swap_used / swap_total * 100 if swap_total else 0

    visible_rss_kib = sum(process.rss_kib for process, _ in visible)
    summary = (
        f"RAM {format_size(used)}/{format_size(total)} ({memory_pct:.0f}%)  "
        f"SWAP {format_size(swap_used)}/{format_size(swap_total)} ({swap_pct:.0f}%)  "
        f"RSS vizibil {format_size(visible_rss_kib)}  "
        f"procese {len(visible)}  neaccesibile/failure {unreadable}"
    )
    if zombies:
        summary += f"  zombie {zombies}"
    if orphans:
        summary += f"  orfani {orphans}"
    return summary


def process_details(
    process: Process,
    history=None,
    cpu_alert: Optional[float] = None,
    rss_alert_kib: Optional[float] = None,
    simple_mode: bool = False,
    wrap_width: Optional[int] = None,
) -> List[str]:
    cpu_time = format_duration(process.cpu_ticks / CLOCK_TICKS)
    children_time = format_duration(process.children_ticks / CLOCK_TICKS)
    switch_rate = "-" if process.switch_rate is None else f"{process.switch_rate:.1f}/s"

    lines: List[str] = []
    alert = None
    if cpu_alert is not None and rss_alert_kib is not None:
        alert = alert_state(process, cpu_alert, rss_alert_kib)
    if alert == "both":
        lines.append(
            f"⚠ ALERTĂ: CPU {percent(process.cpu_percent)}% ≥ prag și "
            f"RSS {format_size(process.rss_kib)} ≥ prag"
        )
    elif alert == "cpu":
        lines.append(f"⚠ ALERTĂ: CPU {percent(process.cpu_percent)}% ≥ prag")
    elif alert == "rss":
        lines.append(f"⚠ ALERTĂ: RSS {format_size(process.rss_kib)} ≥ prag")
    if simple_mode:
        # Completă, fără truncare, împărțită pe linii dacă e nevoie.
        cmd_text = f"CMD> {process.command}"
        effective = wrap_width or 120
        for start in range(0, len(cmd_text), effective):
            lines.append(cmd_text[start:start + effective])
    lines.extend([
        f"PID {process.pid}/{process.ppid}  UID {process.uid}  "
        f"state={process.state} ({process.state_name})  threads={process.threads}  "
        f"nice={process.nice}  priority={process.priority}",
        f"CPU total={cpu_time} (cu copii={children_time})  "
        f"switches={process.context_switches} ({switch_rate})",
        f"RSS={process.rss_kib / 1024:.1f}M  VIRT={process.vms_kib / 1024:.1f}M  "
        f"SWAP={process.swap_kib / 1024:.1f}M",
        f"READ total={format_bytes(process.read_bytes)}  "
        f"WRITE total={format_bytes(process.write_bytes)}  exe={process.exe}",
        f"cwd={process.cwd}",
    ])

    # Rezumat de mediu (environ) — doar un rezumat, fără valori sensitive.
    env_preview = read_environ(process.pid, limit=8)
    if env_preview:
        names = [item.split("=", 1)[0] for item in env_preview]
        lines.append(f"env ({len(names)}+): {', '.join(names)}  (tasta y salvează tot)")

    # Graficele istorice merg imediat după liniile de bază, ca să nu fie
    # tăiate de limita panoului de detalii.
    hist = history.get(process.pid)
    if hist is not None:
        cpu_bar, cpu_peak = sparkline(hist[0])
        rss_bar, rss_peak = sparkline(hist[1])
        if cpu_bar:
            lines.append(f"CPU istoric  {cpu_bar}  max {cpu_peak:.0f}%")
        if rss_bar:
            lines.append(f"RSS istoric  {rss_bar}  max {format_size(rss_peak)}")

    # Detalii opționale, citite doar pentru procesul selectat.
    if ADB is not None:
        status, wchan, _ = _adb_read_details(process.pid)
    else:
        try:
            status = parse_key_values(read_text(f"/proc/{process.pid}/status"))
        except OSError:
            status = {}
        wchan = ""
        try:
            wchan = read_text(f"/proc/{process.pid}/wchan").strip()
        except OSError:
            pass
    if status:
        caps = [status.get(key) for key in ("CapEff", "CapBnd")]
        if any(caps):
            lines.append(f"CapEff={caps[0] or '-'}  CapBnd={caps[1] or '-'}")
        signals = [status.get(key) for key in ("SigBlk", "SigIgn", "SigCgt")]
        if any(signals):
            lines.append(f"SigBlk={signals[0] or '-'}  SigIgn={signals[1] or '-'}  SigCgt={signals[2] or '-'}")
        if "NSpid" in status:
            lines.append(f"NSpid={status['NSpid']}  NStgid={status.get('NStgid', '-')}")
        lines.append(f"FDSize={status.get('FDSize', '-')}  "
                     f"voluntary={process.voluntary_switches}  "
                     f"nonvoluntary={process.nonvoluntary_switches}")
    if wchan:
        lines.append(f"wchan={wchan}")

    if not simple_mode:  # în modul simplu comanda e deja arătată complet sus
        lines.append(f"command={process.command}")
    return lines


def load_config(path: Optional[str] = None) -> dict:
    """Citește ~/.procwatchrc (sau calea dată); întoarce un dict validat."""
    config: dict = {
        "interval": None,
        "sort": None,
        "tree": None,
        "reverse": None,
        "cpu_alert": None,
        "rss_alert_mib": None,
    }
    if path is None:
        path = os.path.join(os.path.expanduser("~"), ".procwatchrc")
    parser = configparser.ConfigParser()
    try:
        read = parser.read(path)
    except (configparser.Error, OSError):
        return config
    if not read or not parser.has_section("procwatch"):
        return config

    def as_float(key: str):
        try:
            return float(parser.get("procwatch", key))
        except (ValueError, TypeError, configparser.Error):
            return None

    def as_bool(key: str):
        word = parser.get("procwatch", key, fallback="").strip().lower()
        if word in TRUE_WORDS:
            return True
        if word in FALSE_WORDS:
            return False
        return None

    config["interval"] = as_float("interval")
    sort_value = parser.get("procwatch", "sort", fallback="").strip().lower()
    config["sort"] = sort_value if sort_value in ("cpu", "mem", "threads", "io", "pid", "name") else None
    config["tree"] = as_bool("tree")
    config["reverse"] = as_bool("reverse")
    config["cpu_alert"] = as_float("cpu_alert")
    config["rss_alert_mib"] = as_float("rss_alert_mib")
    return config


def apply_config(args, config: dict) -> None:
    """Completază argumentele CLI din config; CLI are întotdeauna prioritate."""
    if config.get("interval") is not None and args.interval is None:
        args.interval = config["interval"]
    if config.get("sort") is not None and args.sort is None:
        args.sort = config["sort"]
    if config.get("tree") is not None and not args.tree:
        args.tree = config["tree"]
    if config.get("reverse") is not None and not args.reverse:
        args.reverse = config["reverse"]
    if config.get("cpu_alert") is not None and args.cpu_alert is None:
        args.cpu_alert = config["cpu_alert"]
    if config.get("rss_alert_mib") is not None and args.rss_alert_mib is None:
        args.rss_alert_mib = config["rss_alert_mib"]


def alert_state(process: Process, cpu_threshold: float, rss_threshold_kib: float) -> Optional[str]:
    """'cpu' / 'rss' / 'both' dacă procesul depășește pragurile, altfel None."""
    cpu_over = process.cpu_percent is not None and process.cpu_percent >= cpu_threshold
    rss_over = process.rss_kib >= rss_threshold_kib
    if cpu_over and rss_over:
        return "both"
    if cpu_over:
        return "cpu"
    if rss_over:
        return "rss"
    return None


def log_sample(
    handle,
    visible: List[Tuple[Process, int]],
    memory: Dict[str, int],
    unreadable: int,
) -> None:
    """Scrie o mostră JSONL în fișierul de log (o linie JSON pe mostră)."""
    record = {
        "kind": "sample",
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "unreadable": unreadable,
        "mem_total_kib": memory.get("MemTotal", 0),
        "mem_available_kib": memory.get("MemAvailable", memory.get("MemFree", 0)),
        "processes": [
            {
                "pid": process.pid,
                "ppid": process.ppid,
                "state": process.state,
                "threads": process.threads,
                "cpu_percent": (
                    round(process.cpu_percent, 2)
                    if process.cpu_percent is not None
                    else None
                ),
                "rss_kib": process.rss_kib,
                "command": process.command[:200],
                "depth": depth,
            }
            for process, depth in visible
        ],
    }
    handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    handle.flush()


def read_environ(pid: int, limit: int = 8) -> List[str]:
    """Primele `limit` variabile de mediu ale procesului (codificate NUL)."""
    if ADB is not None:
        return _adb_read_details(pid)[2][:limit]
    try:
        raw = read_text(f"/proc/{pid}/environ")
    except OSError:
        return []
    variables = [part for part in raw.split("\0") if part]
    return variables[:limit]


def copy_command(process: Process, include_environ: bool = False) -> Tuple[bool, str]:
    """Scrie comanda completă (și opțional environ) într-un fișier în cwd."""
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    path = f"procwatch-cmd-{process.pid}-{timestamp}.txt"
    try:
        with open(path, "w") as handle:
            handle.write(process.command + "\n")
            if include_environ:
                for variable in read_environ(process.pid, limit=200):
                    handle.write(f"export {variable}\n")
    except OSError as error:
        return False, f"copiere eșuată: {error}"
    return True, path


def send_signal(process: Process, signum: int) -> Tuple[bool, str]:
    name = "SIGKILL" if signum == 9 else "SIGTERM"
    if ADB is not None:
        ADB.kill(process.pid, signum)
        # Nu putem distinge permisiunea de PID inexistent lafel de ieftin;
        # re-scanarea din buclă va scoate oricum procesul din listă.
        return True, f"{name} trimis (adb) către {process.pid} ({process.name})"
    try:
        os.kill(process.pid, signum)
        return True, f"{name} trimis către {process.pid} ({process.name})"
    except PermissionError:
        return False, f"{name} către {process.pid}: acces refuzat"
    except ProcessLookupError:
        return False, f"{name} către {process.pid}: procesul nu mai există"
    except OSError as error:
        return False, f"{name} către {process.pid}: {error}"


def subtree_rows(
    processes: List[Process], root_pid: int, sort_mode: str, descending: bool
) -> List[Tuple[Process, int]]:
    """Doar descendenții (inclusiv) ai root_pid, în ordine arborescentă."""
    by_pid = {process.pid: process for process in processes}
    if root_pid not in by_pid:
        return []
    depth = {root_pid: 0}
    ordered = sorted(
        processes, key=lambda process: sort_key(process, sort_mode), reverse=descending
    )
    rows: List[Tuple[Process, int]] = []
    changed = True
    while changed:
        changed = False
        for process in ordered:
            if process.pid in depth:
                continue
            parent_depth = depth.get(process.ppid)
            if parent_depth is not None:
                depth[process.pid] = parent_depth + 1
                rows.append((process, depth[process.pid]))
                changed = True
    root = by_pid[root_pid]
    rows.sort(key=lambda item: (item[1], sort_key(item[0], sort_mode)), reverse=False)
    return [(root, 0)] + rows


def export_snapshot(
    processes: Dict[int, Process],
    visible: Sequence[Tuple[Process, int]],
    memory: Dict[str, int],
    fmt: str,
) -> str:
    """Scrie snapshot-ul vizibil în /root și întoarce calea fișierului."""
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    path = f"/root/procwatch-{timestamp}.{fmt}"
    entries = []
    for process, depth in visible:
        entries.append(
            {
                "pid": process.pid,
                "ppid": process.ppid,
                "uid": process.uid,
                "state": process.state,
                "state_name": process.state_name,
                "threads": process.threads,
                "nice": process.nice,
                "priority": process.priority,
                "cpu_percent": (
                    round(process.cpu_percent, 2)
                    if process.cpu_percent is not None
                    else None
                ),
                "rss_kib": process.rss_kib,
                "vms_kib": process.vms_kib,
                "swap_kib": process.swap_kib,
                "cpu_time_s": round(process.cpu_ticks / CLOCK_TICKS, 1),
                "read_bytes": process.read_bytes,
                "write_bytes": process.write_bytes,
                "exe": process.exe,
                "cwd": process.cwd,
                "command": process.command,
                "tree_depth": depth,
            }
        )
    if fmt == "csv":
        with open(path, "w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(entries[0].keys()))
            writer.writeheader()
            writer.writerows(entries)
    else:
        payload = {
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "memory": {
                "mem_total_kib": memory.get("MemTotal", 0),
                "mem_available_kib": memory.get(
                    "MemAvailable", memory.get("MemFree", 0)
                ),
                "swap_total_kib": memory.get("SwapTotal", 0),
                "swap_free_kib": memory.get("SwapFree", 0),
            },
            "processes": entries,
        }
        with open(path, "w") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
    return path


def build_view(
    processes: Dict[int, Process],
    args,
    sort_mode: str,
    descending: bool,
    tree_mode: bool,
    search_term: str,
    zoom_pid: Optional[int] = None,
) -> Tuple[List[Tuple[Process, int]], int]:
    """Aplică filtrul PID + zoom + căutarea, apoi sortarea/arborele."""
    if zoom_pid is not None and zoom_pid in processes:
        # subtree_rows are nevoie de tot snapshot-ul ca să parcurgă copiii.
        rows = subtree_rows(
            list(processes.values()), zoom_pid, sort_mode, descending
        )
        visible_rss = sum(process.rss_kib for process, _ in rows)
        return rows, visible_rss
    base = filter_processes(processes, args.pid)
    if search_term:
        base = [process for process in base if matches_search(process, search_term)]
    use_tree = tree_mode
    if args.pid is None and shutil.get_terminal_size((140, 30)).columns < MIN_TREE_WIDTH:
        use_tree = False
    if use_tree:
        rows = tree_order(base, sort_mode, descending)
    else:
        rows = [(process, 0) for process in sort_processes(base, sort_mode, descending)]
    visible_rss = sum(process.rss_kib for process, _ in rows)
    return rows, visible_rss


def plain_run(args) -> None:
    previous, _ = scan_processes()
    previous_time = time.monotonic()
    history: Dict[int, Tuple[deque, deque]] = {}
    log_handle = None
    if args.log:
        try:
            log_handle = open(args.log, "a")
        except OSError:
            log_handle = None
    completed = 0
    try:
        while args.samples <= 0 or completed < args.samples:
            time.sleep(args.interval)
            now = time.monotonic()
            processes, unreadable = scan_processes()
            calculate_rates(processes, previous, previous_time, now)
            for pid_key, proc in processes.items():
                entry = history.setdefault(pid_key, (deque(maxlen=60), deque(maxlen=60)))
                entry[0].append(proc.cpu_percent)
                entry[1].append(proc.rss_kib)
            rows, visible_rss = build_view(
                processes, args, args.sort, not args.reverse, args.tree, ""
            )
            memory = read_memory()
            memory_total = memory.get("MemTotal", 0)
            zombies_total = sum(1 for process in processes.values() if process.is_zombie)
            orphans_total = orphan_count(processes)
            if log_handle:
                log_sample(log_handle, rows, memory, unreadable)

            width = shutil.get_terminal_size((140, 30)).columns
            columns, command_width = compute_columns(width)
            title = f" Procwatch {time.strftime('%H:%M:%S')} "
            title += f" refresh={args.interval:.2f}s sort={args.sort} cores={device_cpu_count()} "
            if args.tree:
                title += "tree "
            if args.pid:
                title += f"pid={args.pid} "
            print(f"\n{title}\n{'-' * min(width, 180)}")
            print(memory_summary(memory, rows, unreadable, zombies_total, orphans_total)[:width])
            if not rows:
                print("Niciun proces: filtrul PID nu există sau căutarea nu are potriviri.")
            print(
                "CPU total indisponibil: /proc/stat este ascuns de Android; "
                "CPU% este per proces/nucleu."[:width]
            )
            print()
            print(render_header(columns, command_width))
            for process, depth in rows:
                print(render_row(process, depth, columns, command_width, memory_total))
            if rows:
                print("\nDetalii primul proces:")
                for line in process_details(
                    rows[0][0], history, args.cpu_alert, args.rss_alert_mib * 1024
                ):
                    print(line[:width])  # fără wrap: taie la lățimea terminalului

            previous = processes
            previous_time = now
            completed += 1
    except KeyboardInterrupt:
        print("\nProcwatch oprit.")
    finally:
        if log_handle:
            log_handle.close()


def safe_add(window, row: int, column: int, text: str, attribute: int = 0) -> None:
    height, width = window.getmaxyx()
    if row < 0 or row >= height or column >= width:
        return
    available = width - column - 1
    if available <= 0:
        return
    try:
        window.addnstr(row, column, text, available, attribute)
    except curses.error:
        pass


def curses_run(args) -> None:
    def interface(screen) -> None:
        curses.curs_set(0)
        screen.timeout(150)
        try:
            curses.mousemask(
                curses.BUTTON1_CLICKED
                | curses.BUTTON1_DOUBLE_CLICKED
                | curses.BUTTON4_PRESSED
                | BUTTON5_PRESSED
            )
        except curses.error:
            pass  # mediu fără mouse: tastele rămân singura metodă
        use_color = curses.has_colors() and not args.no_color
        if use_color:
            curses.start_color()
            curses.use_default_colors()
            curses.init_pair(1, curses.COLOR_GREEN, -1)
            curses.init_pair(2, curses.COLOR_YELLOW, -1)
            curses.init_pair(3, curses.COLOR_RED, -1)
            curses.init_pair(4, curses.COLOR_CYAN, -1)
            curses.init_pair(5, curses.COLOR_BLUE, -1)
            curses.init_pair(6, curses.COLOR_MAGENTA, -1)

        sort_mode = args.sort
        descending = not args.reverse
        interval = args.interval
        tree_mode = args.tree
        simple_mode = False
        paused = False
        selected = 0
        search_term = ""
        input_mode = False
        input_buffer = ""
        pending_signal: Optional[str] = None
        zoom_pid: Optional[int] = None
        export_format = "json"
        log_handle = None
        log_path: Optional[str] = None
        if args.log:  # log cerut din CLI pornește automat și în modul interactiv
            try:
                log_handle = open(args.log, "a")
                log_path = args.log
            except OSError:
                log_handle = None
        logged_this_frame = False
        status_message = ""
        status_until = 0.0
        previous: Dict[int, Process] = {}
        previous_time = time.monotonic()
        history: Dict[int, Tuple[deque, deque]] = {}
        next_sample = 0.0
        processes: Dict[int, Process] = {}
        unreadable = 0
        running = True

        def set_status(text: str) -> None:
            nonlocal status_message, status_until
            status_message = text
            status_until = time.monotonic() + 3.0

        while running:
            now = time.monotonic()
            logged_this_frame = False
            if not paused and now >= next_sample:
                processes, unreadable = scan_processes()
                calculate_rates(processes, previous, previous_time, now)
                previous = processes
                previous_time = now
                next_sample = now + interval
                for pid_key, proc in processes.items():
                    entry = history.setdefault(
                        pid_key, (deque(maxlen=60), deque(maxlen=60))
                    )
                    entry[0].append(proc.cpu_percent)
                    entry[1].append(proc.rss_kib)
                if len(history) > 400:
                    alive = set(processes)
                    for dead_pid in [p for p in history if p not in alive]:
                        del history[dead_pid]
                logged_this_frame = True

            memory = read_memory()
            rows, visible_rss = build_view(
                processes, args, sort_mode, descending, tree_mode, search_term,
                zoom_pid,
            )
            selected = min(selected, max(len(rows) - 1, 0))
            zombies_total = sum(1 for process in processes.values() if process.is_zombie)
            orphans_total = orphan_count(processes)
            if log_handle and logged_this_frame:
                log_sample(log_handle, rows, memory, unreadable)

            height, width = screen.getmaxyx()
            screen.erase()
            title_attr = curses.color_pair(4) if use_color else curses.A_BOLD
            header_attr = curses.color_pair(5) if use_color else curses.A_REVERSE
            selected_attr = curses.color_pair(5) if use_color else curses.A_REVERSE
            red_attr = curses.color_pair(3) if use_color else curses.A_BOLD

            tree_active = tree_mode and (args.pid is not None or width >= MIN_TREE_WIDTH)
            safe_add(screen, 0, 0, f" Procwatch {time.strftime('%Y-%m-%d %H:%M:%S')} ", title_attr)
            if paused:
                safe_add(screen, 0, 30, "❚❚ PAUZĂ", red_attr)
            controls = (
                f" sort={sort_mode} refresh={interval:.2f}s "
                f"{'simplu ' if simple_mode else ''}"
                f"{'tree ' if tree_active else ''}"
                f"{'ZOOM=' + str(zoom_pid) + ' ' if zoom_pid else ''}"
                f"{'LOG ' if log_handle else ''}"
                f"space={'reia' if paused else 'pauză'} "
                f"q=quit arrows=select "
            )
            safe_add(screen, 0, max(width - len(controls) - 1, 28), controls)
            safe_add(
                screen,
                1,
                0,
                memory_summary(memory, rows, unreadable, zombies_total, orphans_total),
                header_attr,
            )
            safe_add(
                screen,
                2,
                0,
                f" CPU total: N/A (/proc/stat ascuns); CPU% = 100% dintr-un nucleu. "
                f"Praguri: CPU≥{args.cpu_alert:.0f}% RSS≥{args.rss_alert_mib:.0f}M. "
                f"Filtru PID: {args.pid or 'none'}"
                f"{'  [Z] iese din zoom' if zoom_pid else ''}",
            )

            if input_mode:
                safe_add(screen, 3, 0, f"Căutare: /{input_buffer}▌", curses.A_BOLD)
            elif pending_signal:
                target = rows[selected][0] if rows else None
                target_text = f" {target.pid} ({target.name})" if target else ""
                safe_add(
                    screen,
                    3,
                    0,
                    f"Confirmă {'SIGKILL' if pending_signal == 'KILL' else 'SIGTERM'}"
                    f"{target_text}: apasă {'K' if pending_signal == 'KILL' else 'k'} "
                    f"din nou (Esc anulează)",
                    red_attr,
                )
            elif status_message and now < status_until:
                safe_add(screen, 3, 0, status_message, curses.A_BOLD)
            else:
                safe_add(
                    screen,
                    3,
                    0,
                    "c=cpu m=mem t=thr i=io p=pid N=nume /=caută T=tree "
                    "s=simplu z=zoom e=export l=log y=comandă Y=cmd+env "
                    "k=kill K=kill -9 R=rev <>=CPU ,.=RSS q=ieșire",
                )

            columns, command_width = compute_columns(width)
            if simple_mode:
                command_width = simple_command_width(width)
            column_position = 0
            active_sort_columns = (
                {"read", "write"} if sort_mode == "io" else {SORT_COLUMN_MAP[sort_mode]}
            )
            for segment_text, segment_key in header_segments(
                columns, command_width, simple_mode
            ):
                attribute = header_attr
                if segment_key in active_sort_columns:
                    attribute = curses.color_pair(2) | curses.A_BOLD if use_color else header_attr | curses.A_BOLD
                safe_add(screen, 4, column_position, segment_text, attribute)
                column_position += len(segment_text)

            details = (
                process_details(
                    rows[selected][0],
                    history,
                    args.cpu_alert,
                    args.rss_alert_mib * 1024,
                    simple_mode,
                    width - 1,
                )
                if rows
                else []
            )
            detail_lines = (
                min(
                    len(details),
                    (12 if simple_mode else 10) if height >= 20 else 8,
                )
                if height >= 16
                else 0
            )
            first_row = 5
            last_row = max(first_row, height - detail_lines - 1)
            visible_rows = max(last_row - first_row + 1, 0)

            render_start = 0
            if not rows:
                safe_add(
                    screen,
                    first_row,
                    0,
                    "Niciun proces: filtrul PID nu există, procesul s-a terminat "
                    "sau căutarea nu are potriviri.",
                    curses.A_BOLD,
                )
            elif visible_rows:
                render_start = max(0, selected - visible_rows // 2)
                for row_index, (process, depth) in enumerate(rows[render_start:render_start + visible_rows]):
                    line = render_row(
                        process,
                        depth,
                        columns,
                        command_width,
                        memory.get("MemTotal", 0),
                        simple_mode,
                    )
                    attribute = curses.A_NORMAL
                    if alert_state(process, args.cpu_alert, args.rss_alert_mib * 1024):
                        attribute = red_attr
                    if process.is_zombie:
                        attribute = curses.color_pair(2) | curses.A_BOLD if use_color else curses.A_BOLD
                    elif process.is_orphan:
                        attribute = curses.color_pair(1) | curses.A_BOLD if use_color else curses.A_BOLD
                    elif len(process.command) > LONG_COMMAND_THRESHOLD:
                        # Comenzi foarte lungi (tipic PRoot): violet, ușor de observat.
                        attribute = curses.color_pair(6) | curses.A_BOLD if use_color else curses.A_BOLD
                    if render_start + row_index == selected:
                        attribute = attribute | selected_attr
                    safe_add(screen, first_row + row_index, 0, line, attribute)

            if rows and detail_lines:
                detail_top = last_row + 1
                for line_index, line in enumerate(details[:detail_lines]):
                    safe_add(screen, detail_top + line_index, 0, line)
            elif height < 16:
                safe_add(
                    screen,
                    height - 1,
                    0,
                    "c=CPU m=MEM r=RSS t=threads i=IO p=PID N=name T=tree /=search k=kill q=quit",
                )

            screen.refresh()
            key = screen.getch()

            if input_mode:
                if key in (10, 13, curses.KEY_ENTER):
                    search_term = input_buffer.strip()
                    input_mode = False
                    selected = 0
                    if search_term:
                        matches = sum(
                            1 for process in filter_processes(processes, args.pid)
                            if matches_search(process, search_term)
                        )
                        set_status(f"Căutare '{search_term}': {matches} potriviri (n=următoarea)")
                    else:
                        set_status("Căutare.resetată" if search_term else "Căutare anulată")
                elif key in (27, curses.KEY_CANCEL):
                    input_mode = False
                    set_status("Căutare anulată")
                elif key in (127, 8, curses.KEY_BACKSPACE):
                    input_buffer = input_buffer[:-1]
                elif 32 <= key <= 126:
                    input_buffer += chr(key)
                continue

            if pending_signal:
                signum = 15 if pending_signal == "TERM" else 9
                confirm_key = ord("k") if pending_signal == "TERM" else ord("K")
                if key == -1:
                    pass  # timeout fără tastă: confirmarea rămâne activă
                elif key in (ord("q"), ord("Q")):
                    running = False
                elif key == 27:
                    pending_signal = None
                    set_status("Semnal anulat")
                elif rows and key == confirm_key:
                    ok, message = send_signal(rows[selected][0], signum)
                    set_status(("OK: " if ok else "Eroare: ") + message)
                    pending_signal = None
                    next_sample = min(next_sample, time.monotonic())
                else:
                    pending_signal = None
                continue

            if key in (ord("q"), ord("Q")):
                running = False
            elif key == curses.KEY_UP:
                selected = max(0, selected - 1)
            elif key == curses.KEY_DOWN:
                selected = min(len(rows) - 1, selected + 1) if rows else 0
            elif key == curses.KEY_PPAGE:
                selected = max(0, selected - max(visible_rows - 1, 1))
            elif key == curses.KEY_NPAGE:
                selected = min(len(rows) - 1, selected + max(visible_rows - 1, 1)) if rows else 0
            elif key == curses.KEY_RESIZE:
                # Forțează repaint fizic complet după redimensionare (clearok),
                # ca să evite artefactele lăsate de diff-urile parțiale.
                screen.clear()
            elif key == curses.KEY_MOUSE:
                try:
                    _, _, mouse_y, _, mouse_bstate = curses.getmouse()
                except curses.error:
                    mouse_bstate = 0
                if mouse_bstate & curses.BUTTON4_PRESSED:  # rotiță sus
                    selected = max(0, selected - 3)
                elif mouse_bstate & BUTTON5_PRESSED:  # rotiță jos
                    selected = min(len(rows) - 1, selected + 3) if rows else 0
                elif mouse_bstate & (
                    curses.BUTTON1_CLICKED | curses.BUTTON1_DOUBLE_CLICKED
                ):
                    if rows and visible_rows and first_row <= mouse_y < first_row + visible_rows:
                        clicked = render_start + (mouse_y - first_row)
                        if 0 <= clicked < len(rows):
                            selected = clicked
            elif key in (ord("c"),):
                sort_mode = "cpu"
            elif key in (ord("m"), ord("r")):
                sort_mode = "mem"
            elif key in (ord("t"),):
                sort_mode = "threads"
            elif key in (ord("i"),):
                sort_mode = "io"
            elif key in (ord("p"),):
                sort_mode = "pid"
            elif key in (ord("N"),):
                sort_mode = "name"
            elif key in (ord("T"),):
                tree_mode = not tree_mode
                selected = 0
            elif key in (ord("s"), ord("S")):
                simple_mode = not simple_mode  # s: doar PID, CPU%, RAM, Nume
                selected = 0
            elif key in (ord("z"), ord("Z")):
                if zoom_pid is not None:
                    zoom_pid = None  # Z sau z iese din zoom
                    selected = 0
                elif rows:
                    zoom_pid = rows[selected][0].pid
                    selected = 0
                    set_status(f"Zoom pe subarborele PID {zoom_pid} (Z iese)")
                else:
                    set_status("Niciun proces pentru zoom")
            elif key in (ord("/"),):
                input_mode = True
                input_buffer = ""
            elif key in (ord("n"),):
                if search_term and rows:
                    match_indices = [
                        index for index, (process, _) in enumerate(rows)
                        if matches_search(process, search_term)
                    ]
                    if match_indices:
                        following = [index for index in match_indices if index > selected]
                        selected = following[0] if following else match_indices[0]
                        set_status(
                            f"Potrivire {match_indices.index(selected) + 1}/{len(match_indices)} "
                            f"pentru '{search_term}'"
                        )
                    else:
                        set_status(f"Nicio potrivire pentru '{search_term}'")
                else:
                    sort_mode = "name"
            elif key in (ord("k"), ord("K")):
                if not rows:
                    set_status("Niciun proces selectat")
                else:
                    pending_signal = "KILL" if key == ord("K") else "TERM"
            elif key in (ord("y"), ord("Y")):
                if not rows:
                    set_status("Niciun proces de copiat")
                else:
                    ok, message = copy_command(rows[selected][0], include_environ=key == ord("Y"))
                    set_status(("Copiat în " if ok else "") + message if ok else message)
            elif key in (ord("R"),):
                descending = not descending
            elif key in (ord("e"), ord("E")):
                if not rows:
                    set_status("Nimic de exportat: lista e goală")
                else:
                    try:
                        path = export_snapshot(processes, rows, memory, export_format)
                        set_status(f"Export {export_format.upper()}: {path}")
                        export_format = "csv" if export_format == "json" else "json"
                    except OSError as error:
                        set_status(f"Export eșuat: {error}")
            elif key in (ord("l"), ord("L")):
                if log_handle:
                    log_handle.close()
                    log_handle = None
                    set_status(f"Log oprit: {log_path}")
                else:
                    log_path = args.log or f"procwatch-log-{time.strftime('%Y%m%d-%H%M%S')}.jsonl"
                    try:
                        log_handle = open(log_path, "a")
                        set_status(f"Log pornit: {log_path}")
                    except OSError as error:
                        log_handle = None
                        set_status(f"Log eșuat: {error}")
            elif key in (ord("<"),):
                args.cpu_alert = max(0.0, args.cpu_alert - 5.0)
                set_status(f"Prag CPU: ≥ {args.cpu_alert:.0f}%")
            elif key in (ord(">"),):
                args.cpu_alert = min(200.0, args.cpu_alert + 5.0)
                set_status(f"Prag CPU: ≥ {args.cpu_alert:.0f}%")
            elif key in (ord(","),):
                args.rss_alert_mib = max(0.0, args.rss_alert_mib - 64.0)
                set_status(f"Prag RSS: ≥ {args.rss_alert_mib:.0f}M")
            elif key in (ord("."),):
                args.rss_alert_mib = min(4096.0, args.rss_alert_mib + 64.0)
                set_status(f"Prag RSS: ≥ {args.rss_alert_mib:.0f}M")
            elif key == ord(" "):
                paused = not paused
                if not paused:
                    # Reia cu o bază proaspătă, ca ratele să nu sară peste pauză.
                    processes, unreadable = scan_processes()
                    previous = processes
                    previous_time = time.monotonic()
                    next_sample = previous_time + interval
            elif key in (ord("+"),):
                interval = min(max(interval - 0.1, 0.1), 10.0)
                next_sample = min(next_sample, time.monotonic())
            elif key in (ord("-"),):
                interval = min(max(interval + 0.1, 0.1), 10.0)
                next_sample = min(next_sample, time.monotonic())

        if log_handle:
            log_handle.close()
        screen.clear()
        screen.refresh()

    curses.wrapper(interface)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Monitor de procese bazat pe /proc pentru Android/PRoot.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            " taste: c=CPU m/R=memorie t=fire i=IO p=PID N=nume T=arbore s=simplu z=zoom /=căutare "
            "n=următoarea potrivire k=SIGTERM K=SIGKILL (confirmare din nou) "
            "e=export l=log y=comandă Y=cmd+env <>=pragCPU ,.=pragRSS space=pauză +/-=refresh R=ordine q=ieșire"
        ),
    )
    parser.add_argument("-i", "--interval", type=float, default=None, help="interval în secunde (implicit din config sau 1.0)")
    parser.add_argument("-n", "--samples", type=int, default=0, help="număr de mostre; 0=continuu")
    parser.add_argument("-s", "--sort", choices=("cpu", "mem", "threads", "io", "pid", "name"), default=None, help="sortare (implicit din config sau cpu)")
    parser.add_argument("-p", "--pid", type=int, help="arată doar PID și descendenții săi")
    parser.add_argument("-t", "--tree", action="store_true", help="vizualizare arborescentă părinte-copil")
    parser.add_argument(
        "--export",
        choices=("json", "csv"),
        help="exportă o mostră în FIȘIER și iese (implică --once --plain)",
    )
    parser.add_argument(
        "--cpu-alert",
        type=float,
        default=None,
        metavar="PROC",
        help="prag de alertă CPU%% (implicit din config sau 80)",
    )
    parser.add_argument(
        "--rss-alert-mib",
        type=float,
        default=None,
        metavar="MIB",
        help="prag de alertă RSS în MiB (implicit din config sau 512)",
    )
    parser.add_argument(
        "--config",
        default=None,
        metavar="FIȘIER",
        help="cale alternativă pentru fișierul de configurare (implicit ~/.procwatchrc)",
    )
    parser.add_argument(
        "--log",
        default=None,
        metavar="FIȘIER",
        help="fișier JSONL în care se scrie istoricul mostrelor (poate fi pornit/oprit și cu tasta l)",
    )
    parser.add_argument("--reverse", action="store_true", help="ordine inversă pentru sortare")
    parser.add_argument(
        "--adb",
        action="store_true",
        help="monitorizează /proc al unui telefon prin adb shell (nu cel local)",
    )
    parser.add_argument(
        "--serial",
        default=None,
        metavar="SERIE",
        help="serialul dispozitivului adb (ex. 127.0.0.1:40383 pentru Shizuku); "
        "implicit primul dispozitiv conectat",
    )
    parser.add_argument("--once", action="store_true", help="execută o singură mostră")
    parser.add_argument("--plain", action="store_true", help="dezactivează interfața full-screen")
    parser.add_argument("--no-color", action="store_true", help="dezactivează culorile când este posibil")
    args = parser.parse_args()
    apply_config(args, load_config(args.config))
    if args.interval is None:
        args.interval = 1.0
    if args.sort is None:
        args.sort = "cpu"
    if args.cpu_alert is None:
        args.cpu_alert = DEFAULT_CPU_ALERT
    if args.rss_alert_mib is None:
        args.rss_alert_mib = DEFAULT_RSS_ALERT_MIB
    if args.interval <= 0:
        parser.error("--interval trebuie să fie mai mare decât 0")
    if args.samples < 0:
        parser.error("--samples nu poate fi negativ")
    if args.cpu_alert < 0 or args.rss_alert_mib < 0:
        parser.error("pragurile de alertă nu pot fi negative")
    if args.once:
        args.samples = 1
    return args


def _probe_serial(serial: str) -> bool:
    """True dacă `adb -s SERIE shell echo` răspunde repede."""
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "echo procwatch-ok"],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=5,
        )
        return "procwatch-ok" in result.stdout
    except (OSError, subprocess.TimeoutExpired):
        return False


def _first_device_serial() -> Optional[str]:
    """Primul dispozitiv cu starea „device” din `adb devices`."""
    try:
        result = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def _tcp_open(host: str, port: str, timeout: float = 0.5) -> bool:
    """Verificare ieftină: portul acceptă conexiuni TCP?"""
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except (OSError, ValueError):
        return False


def _adb_run(args: List[str], timeout: float = 2.0) -> None:
    try:
        subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        pass


def _cleanup_offline_transports() -> None:
    """Deconectează intrările 127.0.0.1:* rămase „offline” în adb devices."""
    try:
        result = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=3,
        )
    except (OSError, subprocess.TimeoutExpired):
        return
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "offline" and parts[0].startswith("127.0.0.1:"):
            _adb_run(["disconnect", parts[0]])


def auto_detect_serial() -> Optional[str]:
    """Caută un server Shizuku local (adb over TCP) și îl întoarce ca serial.

    Ordinea: portul din .mh-shizuku-port (cwd sau ~), apoi porturile uzuale
    20000-20006. Dacă nimic nu răspunde, întoarce None și rămâne adb clasic
    (primul dispozitiv din `adb devices`).
    """
    _cleanup_offline_transports()
    candidates: List[str] = []
    for base in (os.getcwd(), os.path.expanduser("~")):
        try:
            with open(os.path.join(base, ".mh-shizuku-port")) as handle:
                port = handle.read().strip()
            if port.isdigit() and f"127.0.0.1:{port}" not in candidates:
                candidates.append(f"127.0.0.1:{port}")
        except OSError:
            pass
    candidates += [f"127.0.0.1:{port}" for port in range(20000, 20007)]
    for serial in candidates:
        host, _, port = serial.rpartition(":")
        # Port închis → sărim imediat; fără asta, fiecare candidat mort ar
        # costa secunde de timeout-uri adb secvențiale.
        if not _tcp_open(host, port):
            continue
        _adb_run(["connect", serial])
        if _probe_serial(serial):
            return serial
        # Fără forward, portul de pe loopback-ul telefonului nu e vizibil de
        # pe calculator (cazul Shizuku: serverul ascultă DOAR pe telefon).
        _adb_run(["forward", f"tcp:{port}", f"tcp:{port}"])
        _adb_run(["connect", serial])
        if _probe_serial(serial):
            return serial
        # Curățenie: un transport conectat dar care nu vorbește adb rămâne
        # „offline” în `adb devices` și strică apelurile fără -s („more than
        # one device”). Îl deconectăm explicit după un probe eșuat.
        _adb_run(["disconnect", serial])
        _adb_run(["forward", "--remove", f"tcp:{port}"])
    return None


def main() -> int:
    args = parse_args()
    if args.export:
        args.once = True
        args.plain = True
    if args.adb:
        serial = args.serial
        if serial is None:
            serial = auto_detect_serial()
            if serial is not None:
                print(f"Shizuku detectat automat: {serial}", file=sys.stderr)
                args.serial = serial
        backend = AdbBackend(serial=serial)
        ok, message = backend.ping()
        if not ok and serial is None:
            # „more than one device/emulator”: adb refuză apelurile fără -s;
            # alegem automat primul dispozitiv disponibil.
            serial = _first_device_serial()
            if serial is not None:
                backend = AdbBackend(serial=serial)
                ok, message = backend.ping()
                if ok:
                    args.serial = serial
                    print(f"Dispozitiv ales automat: {serial}", file=sys.stderr)
        if not ok:
            print(f"Eroare adb: {message}", file=sys.stderr)
            return 2
        set_adb_backend(backend)
    if args.pid is not None:
        existing, _ = scan_processes()
        if args.pid not in existing:
            print(
                f"Eroare: PID {args.pid} nu există sau nu este accesibil. "
                f"Folosește o valoare din /proc.",
                file=sys.stderr,
            )
            return 2
    try:
        if args.export:
            processes, _ = scan_processes()
            calculate_rates(
                processes, {}, time.monotonic() - 0.001, time.monotonic()
            )
            rows, _ = build_view(
                processes, args, args.sort, not args.reverse, args.tree, ""
            )
            if not rows:
                print("Niciun proces de exportat.", file=sys.stderr)
                return 1
            memory = read_memory()
            path = export_snapshot(processes, rows, memory, args.export)
            print(f"Snapshot exportat: {path}")
            return 0
        if sys.stdout.isatty() and not args.plain and not args.once:
            curses_run(args)
        else:
            plain_run(args)
    except KeyboardInterrupt:
        return 130
    except curses.error as error:
        print(f"Interfața terminalului nu este compatibilă: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
