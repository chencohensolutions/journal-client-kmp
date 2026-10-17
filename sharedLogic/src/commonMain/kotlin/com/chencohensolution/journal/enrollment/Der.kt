package com.chencohensolution.journal.enrollment

/** The few ASN.1 DER encodings a PKCS#10 certificate request needs. */
internal object Der {
    fun sequence(vararg items: ByteArray): ByteArray = tlv(0x30, concat(items))

    fun set(vararg items: ByteArray): ByteArray = tlv(0x31, concat(items))

    fun integer(value: Int): ByteArray {
        require(value in 0..127) { "only small non-negative integers are supported" }
        return tlv(0x02, byteArrayOf(value.toByte()))
    }

    fun objectIdentifier(dotted: String): ByteArray {
        val arcs = dotted.split('.').map(String::toLong)
        val body = mutableListOf<Byte>()
        encodeBase128(arcs[0] * 40 + arcs[1], body)
        arcs.drop(2).forEach { encodeBase128(it, body) }
        return tlv(0x06, body.toByteArray())
    }

    fun utf8String(value: String): ByteArray = tlv(0x0C, value.encodeToByteArray())

    /** A BIT STRING of whole bytes (no unused bits). */
    fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

    /** Constructed context-specific tag `[number]`. */
    fun contextSpecific(number: Int, vararg items: ByteArray): ByteArray = tlv(0xA0 or number, concat(items))

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(size: Int): ByteArray {
        if (size < 0x80) return byteArrayOf(size.toByte())
        val bytes = generateSequence(size) { it ushr 8 }.takeWhile { it > 0 }.map { it.toByte() }.toList().reversed()
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun encodeBase128(value: Long, out: MutableList<Byte>) {
        val groups = generateSequence(value) { it ushr 7 }.takeWhile { it > 0 }.map { (it and 0x7F).toInt() }.toList()
        if (groups.isEmpty()) {
            out += 0
            return
        }
        groups.reversed().forEachIndexed { index, group ->
            out += (if (index < groups.size - 1) group or 0x80 else group).toByte()
        }
    }

    private fun concat(items: Array<out ByteArray>): ByteArray =
        items.fold(ByteArray(0)) { acc, item -> acc + item }
}
