package com.jarves.mh.tools.skill

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * Instalează skill-urile livrate în APK (`assets/skills/<nume>`) în root-urile de
 * skill-uri ale aplicației, de unde le descoperă agenții din runtime.
 *
 * Conținut livrat: 47 de skill-uri din ECC v2.2.3 (`76aaa77d`),
 * https://github.com/affaan-m/ECC, licență MIT (`assets/licenses/ecc-MIT.txt`).
 * Selecția și criteriile de excludere: `MH-CURATED.md` din repo-ul ECC.
 *
 * `gateguard` e scos din selecția ECC: `SKILL.md` are 42 KB, peste
 * [SkillToolLimits.MAX_SKILL_FILE_BYTES], deci [SkillLoader] l-ar respinge cu
 * `MANIFEST_TOO_LARGE` și ar dispărea tăcut din catalog. E oricum un hook
 * `PreToolUse`, fără efect ca simplu text. Regresia e prinsă la build de
 * `BundledSkillsBundleTest`.
 *
 * Model de instalare: `RuntimeInstaller.ensureMtop()` — un marker de versiune în
 * fiecare root face ca apelul de pe căile fierbinți să coste o citire de fișier.
 *
 * Semantica de proprietate:
 *  - skill-urile din bundle aparțin aplicației: la un bump de [VERSION] sunt
 *    rescrise din APK, deci editările locale la ele se pierd;
 *  - skill-urile adăugate de utilizator nu sunt niciodată atinse;
 *  - un skill scos din bundle la un upgrade e șters, ca setul de pe disc să fie
 *    exact cel livrat.
 *
 * Markerul nu e singura dovadă că instalarea e validă: [ensure] verifică și că
 * directoarele există. Root-urile de skill-uri sunt scriibile și de alții — în
 * guest, Hermes are un curator care mută skill-uri între activ/învechit/arhivat
 * — iar un marker care rămâne „curent" peste un catalog golit ar raporta succes
 * la nesfârșit. Cu verificarea, următoarea rulare rescrie ce lipsește.
 */
object BundledSkills {

    /** Directorul din assets care ține skill-urile livrate. */
    const val ASSET_DIR = "skills"

    /**
     * Subdirectorul din `filesDir` pe care îl scanează [SkillLoader]. Sursa de
     * adevăr a skill-urilor pe host; DSH le primește de aici prin plugin-ul
     * `mh-skills`, nu ca fișiere în guest.
     */
    const val HOST_DIR = "skills"

    /**
     * Versiunea setului livrat. Trebuie crescută la orice schimbare a conținutului
     * din `assets/skills`: markerul de pe disc compară doar acest string și numele.
     */
    const val VERSION = "ecc-2.2.3-47"

    /** Marker scris în fiecare root instalat: versiunea pe prima linie, apoi numele. */
    const val MANIFEST_NAME = ".mh-bundled.txt"

    /** Sursa bundle-ului: [AssetManager] în producție, un folder în teste. */
    interface Source {
        /**
         * Copiii lui [path]. Lista e goală atât pentru un fișier, cât și pentru un
         * director gol — [AssetManager] nu le poate distinge prin `list`.
         */
        fun list(path: String): List<String>

        fun open(path: String): InputStream
    }

    data class Report(
        val installed: Int,
        val removed: List<String>,
        val skipped: Boolean,
    )

    fun assetSource(assets: AssetManager): Source = object : Source {
        override fun list(path: String): List<String> = assets.list(path)?.toList().orEmpty()

        override fun open(path: String): InputStream = assets.open(path)
    }

    /**
     * Sincronizează [target] cu bundle-ul. Idempotent: când markerul poartă deja
     * [version] și aceleași nume, iar skill-urile sunt încă pe disc, nu se atinge
     * nimic.
     */
    fun ensure(target: File, source: Source, version: String = VERSION): Report {
        val names = source.list(ASSET_DIR).filter(::isPortableName).sorted()
        val manifest = File(target, MANIFEST_NAME)
        val current = manifest.readTextOrNull() == manifestText(version, names) &&
            names.all { File(target, it).exists() }
        if (current) return Report(names.size, emptyList(), skipped = true)

        val previous = parseManifest(manifest.readTextOrNull())
        if (!target.isDirectory && !target.mkdirs()) {
            return Report(0, emptyList(), skipped = true)
        }

        names.forEach { name -> copyTree(source, "$ASSET_DIR/$name", File(target, name)) }

        val stale = previous.filterNot { it in names }
        stale.forEach { name -> File(target, name).deleteRecursively() }

        manifest.writeText(manifestText(version, names))
        return Report(names.size, stale.sorted(), skipped = false)
    }

    private fun copyTree(source: Source, from: String, to: File) {
        val children = source.list(from)
        if (children.isNotEmpty()) {
            to.mkdirs()
            children.forEach { child -> copyTree(source, "$from/$child", File(to, child)) }
            return
        }
        // `list` e gol și pentru un director gol, nu doar pentru un fișier.
        val input = runCatching { source.open(from) }.getOrNull()
        if (input == null) {
            to.mkdirs()
            return
        }
        input.use { stream ->
            to.parentFile?.mkdirs()
            to.outputStream().use { stream.copyTo(it) }
        }
    }

    /** Numele ajunge într-o cale de fișier, deci nu are voie să iasă din [target]. */
    private fun isPortableName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith(".") && name.none { it == '/' || it == '\\' }

    private fun manifestText(version: String, names: List<String>): String =
        (listOf(version) + names).joinToString(separator = "\n", postfix = "\n")

    private fun parseManifest(text: String?): List<String> =
        text?.lineSequence()?.drop(1)?.filter { it.isNotBlank() }?.toList().orEmpty()

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
