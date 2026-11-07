package com.chencohensolution.journal.discovery

/**
 * Bencode (BEP 3), the format of the DHT's KRPC messages: byte strings, integers, lists and dictionaries with byte-string
 * keys in sorted order. Strings stay bytes ([ByteArray]): ids, tokens and values are binary.
 */
internal object Bencode {
    class FormatException(message: String) : Exception(message)

    fun encode(value: Any): ByteArray = Writer().apply { write(value) }.bytes()

    /** Reads one value: [ByteArray], [Long], [List] or [Map] (keys as text). Throws [FormatException] on bad input. */
    fun decode(bytes: ByteArray): Any {
        val reader = Reader(bytes)
        val value = reader.read(depth = 0)
        if (reader.pos != bytes.size) throw FormatException("trailing bytes")
        return value
    }

    private class Writer {
        private val out = ArrayList<Byte>()

        fun bytes() = out.toByteArray()

        private fun ascii(s: String) = s.encodeToByteArray().forEach { out.add(it) }

        fun write(value: Any) {
            when (value) {
                is ByteArray -> { ascii("${value.size}:"); value.forEach { out.add(it) } }
                is String -> write(value.encodeToByteArray())
                is Int -> write(value.toLong())
                is Long -> ascii("i${value}e")
                is List<*> -> { ascii("l"); value.forEach { write(it!!) }; ascii("e") }
                is Map<*, *> -> {
                    ascii("d")
                    // Keys sorted as raw bytes; ours are ASCII, so as text.
                    value.entries.sortedBy { it.key as String }.forEach { (k, v) -> write(k as String); write(v!!) }
                    ascii("e")
                }
                else -> throw IllegalArgumentException("can't bencode ${value::class}")
            }
        }
    }

    private class Reader(private val b: ByteArray) {
        var pos = 0

        private fun peek(): Int = if (pos < b.size) b[pos].toInt() and 0xff else throw FormatException("truncated")

        fun read(depth: Int): Any {
            if (depth > 32) throw FormatException("too deep")
            return when (val c = peek().toChar()) {
                'i' -> { pos++; number('e').also { pos++ } }
                'l' -> {
                    pos++
                    val list = ArrayList<Any>()
                    while (peek().toChar() != 'e') list.add(read(depth + 1))
                    pos++
                    list
                }
                'd' -> {
                    pos++
                    val map = LinkedHashMap<String, Any>()
                    while (peek().toChar() != 'e') {
                        val key = string().decodeToString()
                        map[key] = read(depth + 1)
                    }
                    pos++
                    map
                }
                in '0'..'9' -> string()
                else -> throw FormatException("unexpected '$c' at $pos")
            }
        }

        private fun string(): ByteArray {
            val length = number(':')
            pos++ // ':'
            if (length < 0 || length > b.size - pos) throw FormatException("bad string length")
            return b.copyOfRange(pos, pos + length.toInt()).also { pos += length.toInt() }
        }

        /** Digits (and a sign) up to [end], which is left for the caller. */
        private fun number(end: Char): Long {
            val start = pos
            while (peek().toChar() != end) pos++
            val text = b.copyOfRange(start, pos).decodeToString()
            if (text.length > 19) throw FormatException("number too long")
            return text.toLongOrNull() ?: throw FormatException("bad number '$text'")
        }
    }
}
