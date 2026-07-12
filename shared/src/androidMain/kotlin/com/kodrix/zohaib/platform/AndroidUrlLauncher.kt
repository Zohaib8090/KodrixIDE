package com.kodrix.zohaib.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

actual fun openUrl(url: String) {
    // Requires Context - use the global application context
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidContextHolder.context?.startActivity(intent)
}

actual fun openFileInSystem(path: String) {
    val context = AndroidContextHolder.context ?: return
    val file = java.io.File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}

object AndroidContextHolder {
    var context: Context? = null
}
