package com.chencohensolution.journal.discovery

/**
 * A server's DHT record as journal-server-go's `discovery` package writes it (made with its own code on Nov 7, 2026),
 * for the phones' DHT client to read back exactly. All hex.
 */
object DhtFixtures {
    /** The server's ed25519 public key in the DHT (`serverInfo.discovery.key`). */
    const val KEY = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"

    /** The server's fingerprint (`serverInfo.discovery.fingerprint`): the seal's key is made from it. */
    const val FINGERPRINT = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    /** BEP 44 salt, as text. */
    const val SALT = "journal-server/v1"

    /** SHA-1(KEY bytes + SALT bytes): the DHT target to `get`. */
    const val TARGET = "65993ae955c0eca58f223f83d2e44b8bdd0b9a6b"

    const val SEQ = 1794078000L

    /** The item's value `v` (a bencoded byte string in the reply): 24-byte nonce, then XChaCha20-Poly1305 ciphertext. */
    const val SEALED = "9034d77c73a8f9499a2132b22c12bfec0a3ca6bec71c1572f6ca8f5c8721530b418a3ddde54b6c2d4b5b9c6640c3255fc10856403e085a60c811772ac7d933e4131a11e0c73a693d049e52088b1189e87da82565b742c2e4b922a43f6ceb4cb097cbd0c12bd0a515076191fb665b439d187380d3aa96099865749f67d61629568d33bec586b86dc773d491b10ae23db4424fa50cc51ae0436b015cbe2d1716d64076930e2c65b4"

    /** The ed25519 signature over BEP 44's "4:salt17:journal-server/v13:seqi<SEQ>e1:v<len>:<SEALED>". */
    const val SIG = "75682317a99ef9a4f5176a943f8b33180f7a289131d500f2f52b7373dd3b8b2988e7174959276eeb7a62468fdbe17b61f6748434f3571923729b018f77a04101"

    /** A whole KRPC `get` reply holding the item, bencoded, as a DHT node sends it. */
    const val REPLY = "64313a7264323a696432303a0000000000000000000000000000000000000000313a6b33323a79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664333a736571693137393430373830303065333a73696736343a75682317a99ef9a4f5176a943f8b33180f7a289131d500f2f52b7373dd3b8b2988e7174959276eeb7a62468fdbe17b61f6748434f3571923729b018f77a04101353a746f6b656e333a746f6b313a763136373a9034d77c73a8f9499a2132b22c12bfec0a3ca6bec71c1572f6ca8f5c8721530b418a3ddde54b6c2d4b5b9c6640c3255fc10856403e085a60c811772ac7d933e4131a11e0c73a693d049e52088b1189e87da82565b742c2e4b922a43f6ceb4cb097cbd0c12bd0a515076191fb665b439d187380d3aa96099865749f67d61629568d33bec586b86dc773d491b10ae23db4424fa50cc51ae0436b015cbe2d1716d64076930e2c65b465313a74323a6161313a79313a7265"

    /** What the sealed record opens to. */
    val ADDRESSES = listOf("https://203.0.113.7:56398", "https://[2001:db8::7]:56398", "https://192.168.1.20:56398")
    const val AT = "2026-11-07T19:00:00Z"
}
