package com.hexnext.engine.frro

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire-format reader (varint, 64-bit, length-delimited, 32-bit). */
internal class ProtoReader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {
    sealed class Field {
        abstract val number: Int
        data class Varint(override val number: Int, val value: Long) : Field()
        data class Bytes(override val number: Int, val value: ByteArray) : Field()
        data class Fixed(override val number: Int, val value: Long) : Field()
    }

    fun fields(): List<Field> {
        val out = mutableListOf<Field>()
        while (pos < end) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            if (number <= 0) throw FrroFormatException("invalid protobuf field number $number")
            out += when ((tag and 7).toInt()) {
                0 -> Field.Varint(number, varint())
                1 -> Field.Fixed(number, fixed(8))
                2 -> {
                    val len = varint()
                    if (len < 0 || pos + len > end) throw FrroFormatException("protobuf field $number overruns message")
                    Field.Bytes(number, bytes.copyOfRange(pos, pos + len.toInt())).also { pos += len.toInt() }
                }
                5 -> Field.Fixed(number, fixed(4))
                else -> throw FrroFormatException("unsupported protobuf wire type ${tag and 7}")
            }
        }
        return out
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw FrroFormatException("truncated protobuf varint")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw FrroFormatException("protobuf varint too long")
        }
    }

    private fun fixed(n: Int): Long {
        if (pos + n > end) throw FrroFormatException("truncated protobuf fixed field")
        var v = 0L
        for (i in 0 until n) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += n
        return v
    }
}

/** Minimal protobuf wire-format writer. Default (zero/empty) scalars are omitted, as in proto3. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(number: Int, value: Long): ProtoWriter = apply {
        if (value != 0L) { tag(number, 0); rawVarint(value) }
    }

    /** Writes the varint even when it is zero (explicit presence). */
    fun forceVarint(number: Int, value: Long): ProtoWriter = apply { tag(number, 0); rawVarint(value) }

    fun bool(number: Int, value: Boolean): ProtoWriter = varint(number, if (value) 1 else 0)

    fun string(number: Int, value: String?): ProtoWriter = apply {
        if (!value.isNullOrEmpty()) bytes(number, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(number: Int, value: ByteArray): ProtoWriter = apply {
        tag(number, 2); rawVarint(value.size.toLong()); out.write(value)
    }

    fun message(number: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(number, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(number: Int, wire: Int) = rawVarint(((number shl 3) or wire).toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
