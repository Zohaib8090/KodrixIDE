package com.kodrix.zohaib.platform

expect object Platform {
    val name: String
    val isAndroid: Boolean
    val fileSeparator: String
}
