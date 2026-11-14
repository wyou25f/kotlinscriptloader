package ru.privateserver.ksl

import java.io.File
import java.nio.file.Files

/**
 * Script discovery and explicit reloads must never follow symlinks out of scripts/.
 * Rejecting symlinks entirely also prevents loops and ambiguous duplicate script IDs.
 */
internal object KSLScriptPathPolicy {
    fun isSafeChild(root: File, candidate: File): Boolean {
        return try {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val candidatePath = candidate.toPath().toAbsolutePath().normalize()
            if (candidatePath == rootPath || !candidatePath.startsWith(rootPath)) {
                false
            } else {
                var current = rootPath
                var hasSymlink = false
                for (part in rootPath.relativize(candidatePath)) {
                    current = current.resolve(part)
                    if (Files.isSymbolicLink(current)) {
                        hasSymlink = true
                        break
                    }
                }

                if (hasSymlink) {
                    false
                } else {
                    val realRoot = root.canonicalFile.toPath()
                    val realCandidate = candidate.canonicalFile.toPath()
                    realCandidate != realRoot && realCandidate.startsWith(realRoot)
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
