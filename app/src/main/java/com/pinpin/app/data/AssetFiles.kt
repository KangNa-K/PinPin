package com.pinpin.app.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object AssetFiles {
    /** Only direct regular files in the exact private directory are owned. */
    fun ownedFile(root: File, candidate: File): File? = runCatching {
        val absoluteRoot = root.absoluteFile
        val absolute = candidate.absoluteFile
        if (absoluteRoot.canonicalFile != absoluteRoot ||
            absolute.canonicalFile != absolute ||
            absolute.parentFile != absoluteRoot || !absolute.isFile
        ) null else absolute
    }.getOrNull()

    fun writeAtomically(target: File, write: (FileOutputStream) -> Unit) {
        val parent = requireNotNull(target.parentFile)
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create asset directory")
        if (parent.canonicalFile != parent.absoluteFile) throw IOException("Symlink asset directory")
        val temporary = File(parent, ".${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                write(output)
                output.flush()
                output.fd.sync()
            }
            if (temporary.length() == 0L) throw IOException("Empty asset")
            if (!temporary.renameTo(target)) throw IOException("Cannot finalize asset")
        } finally {
            temporary.delete()
        }
    }
}
