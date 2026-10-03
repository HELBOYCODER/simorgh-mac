package com.simorgh.mac

import java.io.File

object Paths {
    val dataDir: File by lazy {
        File(System.getProperty("user.home"), "Library/Application Support/Simorgh").apply { mkdirs() }
    }
    val feedsCacheDir: File by lazy { File(dataDir, "cache/feeds").apply { mkdirs() } }

    /** Same search order as ref-tungate's bundled-helper lookup. */
    fun bundledTool(name: String): File? {
        val candidates = mutableListOf<File>()
        val codeBase = runCatching {
            File(Paths::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        if (codeBase != null) {
            val base = if (codeBase.isDirectory) codeBase else codeBase.parentFile
            base?.let {
                candidates.add(File(it, "resources/$name"))
                val contents = it.parentFile
                if (contents != null) {
                    candidates.add(File(contents, "Resources/$name"))
                    candidates.add(File(contents, "Resources/app-resources/macos/$name"))
                }
            }
        }
        candidates.add(File(System.getProperty("user.dir"), "vendor/$name"))
        candidates.add(File(dataDir, "vendor/$name"))
        return candidates.firstOrNull {
            if (!it.isFile) return@firstOrNull false
            runCatching { it.setExecutable(true, false) }
            true
        }
    }
}
