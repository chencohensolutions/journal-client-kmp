package com.chencohensolution.journal.swap

/** [count] bytes from the platform's cryptographic random source (SecureRandom, SecRandomCopyBytes). */
internal expect fun secureRandomBytes(count: Int): ByteArray
