# Audit suprafață de tool-uri Mobile-Harness — 2026-11-05

Audit read-only, fără build local (conform regulii proiectului). Branch: `main` @ `39b7b8b`.

Metodă: skill `agent-harness-construction` (action space, granularitate, observation design,
error-recovery contract, context budget). Aplicat pe stratul de tool-uri al aplicației, nu pe
CLI-urile guest.

**Sursa măsurată:** `app/src/main/java/com/jarves/mh/tools/` (42 fișiere Kotlin, recursive),
`app/src/main/assets/tool_catalog/{en,ro}.json`, `app/src/main/assets/skills/` (47 skill-uri
bundle-uite).

---

## 1. Suprafața de acțiune

| Cantitate | Valoare |
|---|---|
| Modul tool (`.kt` recursive) | 42 |
| Submodule | `calc` 5, `device` 6, `file` 4, `memory` 3, `network` 6, `skill` 4, `terminal` 4, `climode` 1, rădăcină 9 |
| Tool-uri în catalogul user-facing | 31 (`en.json`) / 31 (`ro.json`) — seturi identice |
| Tool-uri clasificate în `ToolRiskCatalog` | 32 |
| Skill-uri bundle-uite | 47 (inclusiv acest skill) |
| Bridge-uri de runtime | 41 fișiere în `runtime/` (Hermes, Claude, DSH, OpenCode, Antigravity) |

`ToolRiskCatalog.kt` clasifică 32 de tool-uri pe 5 niveluri:

| Nivel | Nr. | Tool-uri |
|---|---|---|
| `LOW` | 10 | Read, Glob, Grep, Calculate, Sleep, UseSkill, SkillList, MemorySearch, MemoryList, MemoryLinkList |
| `SENSITIVE_READ` | 4 | ClipboardRead, MemoryRead, UiReadTree, UiStatus |
| `DEVICE_CONTROL` | 14 | Write, Edit, NotebookEdit, MemoryCreate/Update/Delete, MemoryLink/Unlink, ClipboardCopy, DeviceToast, DeviceNotification, DeviceOpenHttpsLink, UiClick, VisitWeb |
| `EXTERNAL_EFFECT` | 4 | Bash, HttpRequest, DownloadFile, ShizukuExec |
| `DESTRUCTIVE` | **0** | — |

**Verdict — action space: sănătos.** Nume stabile și explicite, granularitate corectă
(micro-tool-uri pentru operații cu efect — `ShizukuExec`, `DownloadFile` — față de read-only),
fallback documentat la `DEVICE_CONTROL` pentru tool-uri necunoscute, și o poartă de aprobare
separată (`ToolPermissionGate` → `Allowed` / `NeedsApproval` / `Blocked`) cu hook-uri de
lifecycle (`AIToolHook`).

## 2. Contractul de observare — finding central

Fiecare submodul își declară propriul tip de rezultat. **Nouă** tipuri `sealed interface`,
incompatibile între ele:

`FileToolResult` · `TerminalToolResult` · `CalcToolResult` · `DeviceToolResult` ·
`NetworkToolResult` · `MemoryToolResult` · `SkillToolResult` · `ToolPermissionGateDecision` ·
`AIToolHookDecision`

Câmpurile contractului țintă (skill-ul cere `status` + `summary` + `next_actions` +
`artifacts`), măsurate pe cele 42 de fișiere:

| Câmp | Apariții reale ca *contract* | Notă |
|---|---|---|
| `status` | **0** | cele 4 potriviri text sunt `statusCode` HTTP și `accessibilityStatus()` — nu câmp de envelope |
| `summary` | **0** | cele 7 potriviri sunt nume de variabilă locală și parametru de hook, nu câmp de rezultat |
| `next_actions` | **0** | absent |
| `artifacts` | **0** | absent |

Ce există în schimb, pe tipuri: `exitCode`/`timeoutMs` (doar `TerminalToolResult`),
`truncated` (file, device, network), coduri de eroare tipizate (`DeviceErrorCode`,
`NetworkErrorCode`, `SkillErrorCode`).

**Deficiențe reale, în funcție de tip:**

1. `FileToolError`, `TerminalToolResult.Error` și `CalcToolResult.Error` poartă doar un
   `message: String` — **fără cod de eroare**. `Device`, `Network` și `Skill` au coduri tipizate.
   Asimetria obligă consumatorul să parseze text pentru a distinge „approval required" de
   „path escapes sandbox" sau „old text not found".
2. Trunchierea nu garantează corectitudinea totalurilor: `GrepMatches` are `truncated` dar
   niciun `totalCount` (nici real, nici lower-bound), spre deosebire de `MemoryToolResult.SearchResults`
   care are `total`.
3. `data class GrepMatch` este membru al `FileToolResult` — un rezultat de tip *match*, nu de tip
   *răspuns*, amestecat în aceeași ierarhie.

**Ce trebuie păstrat și generalizat** (deja mai bun decât media):

- `FileTools.kt:39` — `result()` derivă un `summary` text per tip și îl trimite la hook-uri.
  Contractul există, dar trăiește în hook, nu în tipul returnat către model.
- `ToolRiskLevel.isAutoEligible` — o singură proprietate care codifică regula dură „doar `LOW`
  poate rula automat", într-un singur loc.
- `DeviceErrorCode.APPROVAL_REQUIRED` — refuzul de aprobare e o eroare de primă clasă, nu un text.

## 3. Contractul de recuperare la eroare

Mesajele de eroare sunt raportate ca text simplu. Exemple reale:

| Locație | Mesaj |
|---|---|
| `FileTools.kt:80/117/144` | `Path escapes sandbox: $path` |
| `FileTools.kt:149` | `old text not found` |
| `FileTools.kt:142` | `old text is empty` |
| `FileTools.kt:177` | `Invalid regex: ${e.message}` |
| `TerminalTools.kt:36` | `Approval required (ASK)` |
| `TerminalTools.kt:54` | `error.message ?: "Terminal execution failed"` |

Niciunul nu poartă **hint de cauză**, **instrucțiune de retry sigur** sau **condiție de stop**.
`old text not found` este cazul cel mai frecvent recuperabil în orice agent de cod — îi lipsește
exact textul care ar spune modelului ce să facă (re-citește fișierul, lărgește contextul).

Comparație de referință: Hermes are un strat dedicated de hints
(`tools/terminal_hints.py`) cu tabel de exit-code (126/137/124), potriviri de pattern
(`ModuleNotFoundError`, rate limit, permission denied) și detecție de *masked success*
(`cmd | tail` cu exit 0 care ascunde un eșec real). MH nu are echivalent.

## 4. Buget de context

- Niciun tool nu este deferat: toate cele 31 din catalog sunt disponibile eager. La scara actuală
  e corect (31 e mic); mecanismul nu există dacă va crește.
- Skill-uri bundle-uite: 47, încărcate prin `UseSkill` (activare la cerere — corect, nu în prompt).
- Limite bune și centralizate: `DeviceToolLimits`, `NetworkToolLimits`, `SkillToolLimits`,
  `TerminalToolLimits` — plafoane explicite per tool (ex. `MAX_CLIPBOARD_TEXT_CHARS = 65_536`,
  `MAX_HTTP_RESPONSE_BYTES = 2_097_152`, `MAX_UI_TREE_NODES = 400`).
- `SkillToolLimits.MAX_SKILLS = 500`, `MAX_METADATA_LINES = 40` — liste plafonate, cu `truncated`.

**Slăbiciune:** absența unui mecanism de deferral înseamnă că o creștere a catalogului (31 → 100+)
intră integral în promptul fiecărei ture, fără supapă. Nu e o problemă azi; e o lipsă de mecanism.

## 5. Finding-uri suplimentare (neprevăzute de skill, ieșite la măsurare)

**F1 — `DESTRUCTIVE` e un nivel definit fără niciun membru.** `ToolRiskLevel.DESTRUCTIVE` există
și e documentat („Permanently destroys data"), dar `ToolRiskCatalog` nu atribuie nivelul nimănui.
Deci nicio operație nu e tratată ca distructivă — inclusiv `Write` și `Edit`, care pot
suprascrie ireversibil, sunt `DEVICE_CONTROL`. Fie nivelul e aspirational (de curățat sau de
documentat ca atare), fie lipsește cel puțin o atribuire evidentă.

**F2 — `SkillList` e clasificat în `ToolRiskCatalog` dar absent din catalogul user-facing.**
`tool_catalog/{en,ro}.json` are 31 de intrări (conține `UseSkill`, nu și `SkillList`), în timp ce
`ToolRiskCatalog` clasifică 32. O suprafață de tip listare de skill-uri nu e descrisă unde
utilizatorul o vede.

**F3 — direcția cealaltă e curată.** Toate cele 31 de tool-uri din catalogul user-facing sunt
clasificate, deci în practică nu se ajunge la fallback-ul silențios `DEVICE_CONTROL`. Fallback-ul
rămâne o plasă de siguranță, nu un drum folosit.

**F4 — coduri de eroare taxonomice, dar incomplet folosite.** `DeviceErrorCode` (10 membri) și
`NetworkErrorCode` (10 membri) sunt bune; `SkillErrorCode` (9) și el. Potrivit §2, `file`,
`terminal` și `calc` nu au echivalent — deci taxonomia e consistentă în 5 submodule din 8.

## 6. Selecție de verificări făcute

| Verificare | Rezultat |
|---|---|
| Contract de observare unificat | **Lipsă** — 9 tipuri `sealed interface` paralele |
| `next_actions` / `artifacts` emise | 0 din 42 fișiere |
| Tool-uri fără clasificare de risc | 0 din 31 (catalog) |
| Mesaje de eroare cu hint de recuperare | 0 identificate |
| Plafoane explicite per tool | Prezente, 4 obiecte `*Limits` |
| `AGENTS.md` la rădăcină | **Absent** |
| Contract de tool documentat | Parțial (`docs/play/DATA_SAFETY.md` atinge zona) |

## 7. Propuneri prioritizate

**R1 — un envelope de rezultat, aditiv, fără a sparge tipurile existente.**
Adaugă un interfațat de bază (`ToolResult`) cu `tool: String`, `ok: Boolean`, `nextStep: String?`,
`artifacts: List<String>`, și lasă tipurile actuale să îl implementeze. Câștig imediat: `message`
singur nu mai e singurul canal de eroare. Cost: mecanic, per submodul.

**R2 — coduri de eroare și la `file`/`terminal`/`calc`.**
Modelează după `DeviceErrorCode`, care e deja corect: `PATH_ESCAPES_SANDBOX`, `NOT_FOUND`,
`MATCH_NOT_FOUND`, `INVALID_REGEX`, `APPROVAL_REQUIRED`. `APPROVAL_REQUIRED` există la device
dar nu la file — deși `FileTools.check()` produce exact acel caz.

**R3 — `nextStep` doar pe căile de eroare.**
Nu pe succes (acolo e costul de token). Pentru `old text not found` → „re-citește fișierul, apoi
include linii de context în jurul potrivirii". Refuzul de aprobare → „re-încearcă cu aprobare
explicită sau alege un tool `LOW`".

**R4 — `totalCount` pe rezultatele trunchiate.**
`GrepMatches` și `FoundFiles` au `truncated` dar niciun total. Adaugă `totalCount` +
`totalCountIsLowerBound`, în oglindă cu `SearchResult` din Hermes. Altfel un consumator nu poate
distinge „am primit tot" de „am primit o felie".

**R5 — clarifică `DESTRUCTIVE`.**
Fie atribuie nivelul operațiilor ireversibile reale (suprascriere fără backup, ștergere
recursivă), fie documentează explicit în `ToolRiskLevel` de ce nivelul există nefolosit.

**R6 — `AGENTS.md` la rădăcină.**
MH bundlează 47 de skill-uri (inclusiv `agent-harness-construction`) dar nu are fișier de
instrucțiuni la nivel de proiect. Convențiile din `CONTEXT.md` sunt glosar de domeniu, nu ghid de
operare.

## 8. Ce nu poate fi afirmat din auditul ăsta

Fără execuție nu pot spune care dintre R1–R6 mută efectiv rata de completare. Nu există în repo
retries-per-task înregistrate. R1/R2/R4 sunt argumentabile din structura tipurilor; R3 și R5
necesită un probe înainte/după ca să devină dovedite, nu plauzibile.

---

*Document de audit. Nu este dovadă de execuție, de review sau de stare de merge.*
