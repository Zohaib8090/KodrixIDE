package com.kodrix.zohaib.bridge

import java.io.File
import java.io.RandomAccessFile

object BinaryPatcher {
    fun patchString(file: File, oldStr: String, newStr: String): Boolean {
        if (!file.exists()) return false
        val bytes = file.readBytes()
        val oldBytes = oldStr.toByteArray(Charsets.US_ASCII)
        val newBytes = newStr.toByteArray(Charsets.US_ASCII)
        
        var patched = false
        var i = 0
        while (i <= bytes.size - oldBytes.size) {
            var match = true
            for (j in oldBytes.indices) {
                if (bytes[i + j] != oldBytes[j]) {
                    match = false
                    break
                }
            }
            
            if (match) {
                // Replace with new string and null terminate
                for (j in newBytes.indices) {
                    bytes[i + j] = newBytes[j]
                }
                for (j in newBytes.size until oldBytes.size) {
                    bytes[i + j] = 0 // Null terminator
                }
                patched = true
                // Continue searching in case of multiple occurrences
                i += oldBytes.size
            } else {
                i++
            }
        }
        
        if (patched) {
            file.writeBytes(bytes)
        }
        return patched
    }
}
