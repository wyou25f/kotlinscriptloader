package ru.privateserver.ksl

import java.io.File
import java.nio.file.Files

/**
 * Script discovery and explicit reloads must never follow symlinks out of scripts/.
 * Rejecting symlinks entirely also prevents loops and ambiguous duplicate script IDs.
 */
internal object KSLScriptPathPolicy {
    fun isSafeChild(root: File, candidate: File): Boolean = try {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val candidatePath = candidate.toPath().toAbsolutePath().normalize()
        if (candidatePath == rootPath || !candidatePath.startsWith(rootPath)) return false

        var current = rootPath
        for (part in rootPath.relativize(candidatePath)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
        }

        val realRoot = root.canonicalFile.toPath()
        val realCandidate = candidate.canonicalFile.toPath()
        realCandidate != realRoot && realCandidate.startsWith(realRoot)
    } catch (_: Exception) {
        false
    }
}
