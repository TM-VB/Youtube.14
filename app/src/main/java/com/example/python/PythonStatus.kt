package com.example.python

/**
 * Status information for the embedded Python runtime.
 */
data class PythonStatus(
    val isInitialized: Boolean,
    val version: String,
    val runtimePath: String?,
    val packagesPath: String?,
    val errorMessage: String? = null
)
