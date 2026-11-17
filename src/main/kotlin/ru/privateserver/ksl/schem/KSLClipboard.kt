package ru.privateserver.ksl.schem

/**
 * An in-memory WorldEdit clipboard with bounded dimensions.
 *
 * The WorldEdit implementation is kept behind an opaque handle so scripts and addons
 * can pass a clipboard around without exposing WorldEdit internals in the public API.
 */
class KSLClipboard internal constructor(
    internal val nativeClipboard: Any,
    val width: Int,
    val height: Int,
    val length: Int,
    val blockCount: Long,
    val minimumX: Int,
    val minimumY: Int,
    val minimumZ: Int
) {
    val volume: Long get() = blockCount

    override fun toString(): String =
        "KSLClipboard(${width}x${height}x${length}, blocks=$blockCount, min=$minimumX,$minimumY,$minimumZ)"
}
