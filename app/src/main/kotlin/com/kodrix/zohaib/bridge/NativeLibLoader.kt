package com.kodrix.zohaib.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object NativeLibLoader {
    private var isInitialized = false

    fun init(context: Context) {
        val logFile = File(context.filesDir, "loader_log.txt")
        logFile.writeText("init() called at ${java.util.Date()}\n")
        Log.i("NativeLibLoader", "init() called")
        if (isInitialized) return
        
        try {
            val libDir = File(context.filesDir, "lib")
            if (!libDir.exists()) libDir.mkdirs()

            // Only re-copy if APK version has changed
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionStamp = "${packageInfo.versionName}_${packageInfo.longVersionCode}"
            val stampFile = File(libDir, ".copy_stamp")
            if (stampFile.exists() && stampFile.readText() == versionStamp) {
                // Already copied for this version, skip expensive I/O
                logFile.appendText("Libs already copied for $versionStamp, skipping\\n")
            } else {
                // Copy all native libraries
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                logFile.appendText("Processing all libs in $nativeLibDir...\\n")
                
                File(nativeLibDir).listFiles { f -> f.name.endsWith(".so") && !f.name.endsWith("_bin.so") }?.forEach { srcFile ->
                    val name = srcFile.name
                    // Restore original name for versioned libs (e.g. libz.so.1.so -> libz.so.1)
                    val actualName = if (name.contains(".so.") && name.endsWith(".so")) {
                        name.removeSuffix(".so")
                    } else name
                    
                    logFile.appendText("Handling $name -> $actualName\\n")
                    val dstFile = File(libDir, actualName)
                    srcFile.inputStream().use { input ->
                        dstFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // Security Fix: Prevent Native Library Injection
                    dstFile.setWritable(false, false)
                    dstFile.setExecutable(true, false)
                }
                stampFile.writeText(versionStamp)
            }

            val loadOrder = listOf(
                "libc++_shared.so",
                "libiconv1.so",
                "libexpat1.so",
                "libz_9.so",
                "libcare.so",
                "libngh2.so",
                "libngh3.so",
                "libngtp2.so",
                "libngtcp2_crypto_ossl.so",
                "libbrcm.so",
                "libbrdec.so",
                "libbrenc.so",
                "libcrypto_9.so",
                "libssl_9.so",
                "libssh9.so",
                "libcur9.so",
                "libicudata_99.so",
                "libicuuc_99.so",
                "libicui18n_99.so",
                "libsqlite9.so",
                "libpcre28.so",
                "libnode.so",
                "libgit2.so",
                "libgit_bin.so",
                "libgit_remote_http_bin.so",
                "libnghttp3.so",
                "libngtcp2.so",
                "libngtcp2_crypto_ossl.so",
                "libnative-lib.so"
            )



            
            val loadedSet = mutableSetOf<String>()
            
            loadOrder.forEach { name ->
                val f = File(libDir, name)
                if (f.exists()) {
                    try {
                        System.load(f.absolutePath)
                        logFile.appendText("Loaded $name\n")
                        loadedSet.add(name)
                    } catch (e: Throwable) {
                        logFile.appendText("Failed to load $name: ${e.message}\n")
                    }
                } else {
                    logFile.appendText("File not found to load: $name\n")
                }
            }

            // 2. Try loading the rest (trial and error loops)
            var madeProgress = true
            while (madeProgress) {
                madeProgress = false
                libDir.listFiles { f -> f.name.endsWith(".so") && !f.name.endsWith("_bin.so") }?.forEach { f ->
                    if (f.name !in loadedSet) {
                        try {
                            System.load(f.absolutePath)
                            logFile.appendText("Loaded ${f.name}\n")
                            loadedSet.add(f.name)
                            madeProgress = true
                        } catch (e: Throwable) {
                            // ignore for now, will try again if progress is made
                        }
                    }
                }
            }

            isInitialized = true
            logFile.appendText("NativeLibLoader initialized successfully\n")
        } catch (e: Throwable) {
            logFile.appendText("CRITICAL ERROR: ${e.message}\n")
            logFile.appendText(e.stackTraceToString() + "\n")
        }
    }
}
