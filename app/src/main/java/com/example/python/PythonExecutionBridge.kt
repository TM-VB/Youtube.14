package com.example.python

/**
 * Low-level execution contract for invoking Python modules or commands
 * within the embedded CPython runtime.
 */
interface PythonExecutionBridge {
    suspend fun executeScript(scriptName: String, args: List<String>): Result<String>
    suspend fun executeModule(moduleName: String, args: List<String>): Result<String>
}
