package com.ayush.ftpclient

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object StorageUtils {
    fun getStorageVolumes(context: Context): List<File> {
        val volumes = mutableListOf<File>()
        val primaryStorage = Environment.getExternalStorageDirectory()
        if (primaryStorage.exists()) {
            volumes.add(primaryStorage)
        }

        try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (dir in externalDirs) {
                if (dir != null) {
                    val root = getVolumeRoot(dir)
                    if (root != null && root.exists() && !volumes.contains(root)) {
                        volumes.add(root)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles { f ->
                    f.isDirectory && f.canRead() && !f.name.equals("self", true) && !f.name.equals("emulated", true)
                }?.forEach { sdDir ->
                    if (!volumes.contains(sdDir)) {
                        volumes.add(sdDir)
                    }
                }
            }
        } catch (_: Exception) {}

        return volumes
    }

    private fun getVolumeRoot(file: File): File? {
        var current: File? = file
        while (current != null) {
            val parent = current.parentFile
            if (parent != null && parent.absolutePath == "/storage") {
                return current
            }
            current = parent
        }
        return null
    }

    fun compressToZip(sourceFile: File, onComplete: (File?) -> Unit) {
        try {
            val zipFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_archive.zip")
            val fos = FileOutputStream(zipFile)
            val zos = ZipOutputStream(fos)

            if (sourceFile.isDirectory) {
                zipDirectory(sourceFile, sourceFile.name, zos)
            } else {
                val fis = FileInputStream(sourceFile)
                val entry = ZipEntry(sourceFile.name)
                zos.putNextEntry(entry)
                fis.copyTo(zos)
                zos.closeEntry()
                fis.close()
            }

            zos.close()
            fos.close()
            onComplete(zipFile)
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(null)
        }
    }

    private fun zipDirectory(folder: File, parentFolder: String, zos: ZipOutputStream) {
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                zipDirectory(file, "$parentFolder/${file.name}", zos)
            } else {
                val fis = FileInputStream(file)
                val entry = ZipEntry("$parentFolder/${file.name}")
                zos.putNextEntry(entry)
                fis.copyTo(zos)
                zos.closeEntry()
                fis.close()
            }
        }
    }
}
