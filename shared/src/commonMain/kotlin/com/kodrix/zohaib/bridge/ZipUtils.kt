package com.kodrix.zohaib.bridge

import java.io.*
import java.util.zip.*

object ZipUtils {
    fun zipDirectory(sourceDir: File, outZipFile: File) {
        ZipOutputStream(FileOutputStream(outZipFile)).use { zos ->
            val sourcePath = sourceDir.toPath()
            sourceDir.walkTopDown().forEach { file ->
                if (!file.isDirectory) {
                    val relativePath = sourcePath.relativize(file.toPath()).toString()
                    val entry = ZipEntry(relativePath)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                val canonicalTargetDir = targetDir.canonicalPath
                val canonicalDestFile = newFile.canonicalPath
                
                if (!canonicalDestFile.startsWith(canonicalTargetDir + File.separator)) {
                    throw SecurityException("Zip Slip vulnerability detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
    }
}
