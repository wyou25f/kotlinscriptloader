package ru.privateserver.ksl

/**
 * Runs sequential configuration migrations on a working copy.
 *
 * [persist] is called exactly once, only after every migration succeeds.
 * The caller controls how the working copy is created, versioned and persisted.
 */
internal object KSLConfigMigrationRunner {
    fun <T> migrate(
        currentVersion: Int,
        targetVersion: Int,
        migrations: Map<Int, T.() -> Unit>,
        createWorkingCopy: () -> T,
        setVersion: (T, Int) -> Unit,
        persist: (T) -> Unit
    ): Int {
        require(currentVersion >= 0) { "currentVersion must be >= 0" }
        require(targetVersion >= 0) { "targetVersion must be >= 0" }
        require(currentVersion <= targetVersion) {
            "Config version $currentVersion is newer than supported version $targetVersion"
        }
        if (currentVersion == targetVersion) return currentVersion

        val missingVersions = (currentVersion + 1..targetVersion).filterNot(migrations::containsKey)
        require(missingVersions.isEmpty()) {
            "Missing config migrations for versions: ${missingVersions.joinToString()}"
        }

        val workingCopy = createWorkingCopy()
        for (version in currentVersion + 1..targetVersion) {
            migrations.getValue(version).invoke(workingCopy)
            setVersion(workingCopy, version)
        }
        persist(workingCopy)
        return targetVersion
    }
}
