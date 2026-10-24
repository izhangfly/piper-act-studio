package com.nervus.packaging

sealed class NspkgException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InvalidPackageIdException(id: String, reason: String) :
    NspkgException("invalid package_id '$id': $reason")

class InvalidComponentException(componentId: String, reason: String) :
    NspkgException("invalid component '$componentId': $reason")

class SignatureException(message: String, cause: Throwable? = null) :
    NspkgException(message, cause)

class DigestMismatchException(path: String, expected: String, actual: String) :
    NspkgException("digest mismatch for '$path': expected $expected, got $actual")

class LineageException(message: String) : NspkgException(message)

class PackageException(message: String) : NspkgException(message)
