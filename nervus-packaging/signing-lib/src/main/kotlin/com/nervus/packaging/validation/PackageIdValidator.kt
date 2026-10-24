package com.nervus.packaging.validation

object PackageIdValidator {

    private val SEGMENT_REGEX = Regex("^[a-z][a-z0-9_]*$")
    private const val MAX_LENGTH = 128
    private const val MAX_SEGMENTS = 8

    fun validate(id: String): Result {
        if (id.isBlank()) {
            return Result.failure("package_id must not be blank")
        }

        if (id.length > MAX_LENGTH) {
            return Result.failure("package_id length $id.length exceeds max $MAX_LENGTH")
        }

        val segments = id.split('.')
        if (segments.size > MAX_SEGMENTS) {
            return Result.failure("package_id has $segments.size segments, max $MAX_SEGMENTS")
        }

        for (segment in segments) {
            if (segment.isEmpty()) {
                return Result.failure("package_id contains empty segment")
            }
            if (!SEGMENT_REGEX.matches(segment)) {
                return Result.failure(
                    "package_id segment '$segment' does not match $SEGMENT_REGEX"
                )
            }
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

        fun getOrThrow(): String {
            if (!isValid) throw InvalidPackageIdException(errorMessage!!)
            return errorMessage ?: ""
        }

        fun getOrNull(): String? = if (isValid) null else errorMessage
    }
}

class InvalidPackageIdException(message: String) : Exception(message)
