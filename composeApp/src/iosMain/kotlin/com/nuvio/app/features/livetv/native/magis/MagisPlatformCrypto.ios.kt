package com.nuvio.app.features.livetv.native.magis

/**
 * iOS: not implemented yet, and deliberately loud.
 *
 * The desktop/iOS client has never shipped Magis, and a silent no-op here would produce bodies
 * the portal rejects with an untraceable error. When iOS becomes a target, implement the four
 * primitives with CommonCrypto (`CCCrypt` for DES-ede3-ECB, `CC_MD5`, `SecRandomCopyBytes`) and
 * base64 from `NSData`.
 */
internal actual fun createMagisCryptoPort(): MagisCryptoPort =
    error(
        "Magis on iOS: no crypto port yet. Implement MagisCryptoPort with CommonCrypto " +
            "(CCCrypt kCCAlgorithm3DES/kCCOptionECBMode, CC_MD5, SecRandomCopyBytes).",
    )
