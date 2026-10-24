package com.nervus.packaging.validation

object PathValidator {

    private val VALID_RELATIVE_PATH = Regex("^[^/][^\\x00]*[^/]$|^[^/]$")

    fun validate(path: String): Result {
        if (path.isBlank()) {
            return Result.failure("path must not be blank")
        }

        if (path.startsWith("/")) {
            return Result.failure("path must not be absolute: '$path'")
        }

        if (path.contains("..")) {
            return Result.failure("path must not contain '..': '$path'")
        }

        if (path.contains("\u0000")) {
            return Result.failure("path must not contain null byte")
        }

        return Result.success()
    }

    class Result private constructor(
        val isValid: Boolean,
        val errorMessage: String? = null,
    ) {
        companion object {
            fun success() = Result(true)
            fun failure(message: String) = Result(false, message)
        }
    }
}
