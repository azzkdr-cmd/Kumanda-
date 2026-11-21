package com.kumanda.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Kucuk bir protobuf yazici (yalnizca bu protokolun ihtiyaci kadar). */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    fun int(field: Int, value: Int): ProtoWriter {
        raw((field.toLong() shl 3))
        raw(value.toLong())
        return this
    }

    fun bytes(field: Int, data: ByteArray): ProtoWriter {
        raw((field.toLong() shl 3) or 2)
        raw(data.size.toLong())
        out.write(data)
        return this
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, inner: ProtoWriter) = bytes(field, inner.build())

    fun build(): ByteArray = out.toByteArray()

    /** Uzunluk onekli cerceve. */
    fun frame(): ByteArray {
        val payload = build()
        val w = ProtoWriter()
        w.raw(payload.size.toLong())
        w.out.write(payload)
        return w.build()
    }
}

object Proto {
    /** Alan numarasi -> degerler (Long veya ByteArray). */
    fun parse(data: ByteArray): Map<Int, List<Any>> {
        val result = HashMap<Int, MutableList<Any>>()
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (true) {
                if (pos >= data.size) throw EOFException("bozuk mesaj")
                val b = data[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                if (shift > 63) throw EOFException("bozuk varint")
            }
        }
        while (pos < data.size) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            val value: Any = when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> { pos += 8; 0L }
                5 -> { pos += 4; 0L }
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || pos + len > data.size) throw EOFException("bozuk mesaj")
                    val b = data.copyOfRange(pos, pos + len)
                    pos += len
                    b
                }
                else -> throw EOFException("bilinmeyen tel tipi")
            }
            result.getOrPut(field) { ArrayList() }.add(value)
        }
        return result
    }

    fun readFrame(input: InputStream): ByteArray {
        var shift = 0
        var len = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("baglanti kapandi")
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw EOFException("bozuk cerceve")
        }
        if (len < 0 || len > 1 shl 20) throw EOFException("bozuk cerceve")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("baglanti kapandi")
            off += n
        }
        return buf
    }
}
