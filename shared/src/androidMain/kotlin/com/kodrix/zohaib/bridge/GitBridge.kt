package com.kodrix.zohaib.bridge

import android.util.Log

object GitBridge {
    // NativeLibLoader.init() is called in MainActivity.onCreate

    /**
     * Clones a git repository using libgit2.
     * @param url The HTTPS URL of the repository.
     * @param localPath The local path to clone into.
     * @param username The username for authentication.
     * @param token The personal access token or password.
     * @return "SUCCESS" or an error message starting with "ERROR:".
     */
    external fun cloneRepo(
        url: String,
        localPath: String,
        username: String,
        token: String
    ): String
}
