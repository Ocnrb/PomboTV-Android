package com.livepoc.android

import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.min

/**
 * Binary media framing — byte-for-byte compatible with live-poc.html.
 * header(20): magic u8 | flags u8 | fragIdx u8 | fragCnt u8 | timestamp f64 | duration f64
 * All multi-byte fields big-endian (JS DataView default).
 * Keyframe frag0 carries the decoder config as [len u32][json].
 * Each Streamr message is a container of length-prefixed records.
 */
object Wire {
    const val MAGIC = 0xC1
    const val HDR = 20
    const val FLAG_KEY = 1
    const val FLAG_CFG = 2
    // bit de TIPO DE MÉDIA: permite áudio e vídeo na MESMA partição (modos
    // experimentais single-partition/mux) — o recetor demultiplexa por registo.
    // Retrocompatível: viewers antigos ignoram bits desconhecidos.
    const val FLAG_AUD = 4
    const val WIRE_MAX = 240 * 1024
    // v2: batches maiores → menos mensagens/s = menos assinaturas (CPU no
    // telemóvel) por +~30-50ms de latência. Formato de wire inalterado.
    const val VBATCH_MAX = 4
    const val ABATCH_MAX = 6
    const val BATCH_MS = 150L

    fun container(records: List<ByteArray>): ByteArray {
        var total = 0
        for (r in records) total += 4 + r.size
        val buf = ByteBuffer.allocate(total) // big-endian by default
        for (r in records) {
            buf.putInt(r.size)
            buf.put(r)
        }
        return buf.array()
    }

    /** Peek sem desserializar: o registo é ÁUDIO? (demux single-partition) */
    fun isAudioRecord(rec: ByteArray): Boolean =
        rec.size >= 2 && (rec[0].toInt() and 0xFF) == MAGIC && (rec[1].toInt() and FLAG_AUD) != 0

    /** Tolerates both wire formats: raw frame (starts with MAGIC) or container. */
    fun forEachRecord(data: ByteArray, fn: (ByteArray) -> Unit) {
        if (data.isEmpty()) return
        if ((data[0].toInt() and 0xFF) == MAGIC) { fn(data); return }
        val buf = ByteBuffer.wrap(data)
        while (buf.remaining() >= 4) {
            val len = buf.int
            if (len <= 0 || len > buf.remaining()) break
            val rec = ByteArray(len)
            buf.get(rec)
            fn(rec)
        }
    }

    fun packMedia(isKey: Boolean, tsUs: Double, durUs: Double, data: ByteArray, configJson: String?, isAudio: Boolean = false): List<ByteArray> {
        val cfgBytes = if (isKey && configJson != null) configJson.toByteArray(Charsets.UTF_8) else null
        val cfgLen = if (cfgBytes != null) 4 + cfgBytes.size else 0
        val room0 = WIRE_MAX - HDR - cfgLen
        val roomN = WIRE_MAX - HDR
        val fragCnt = if (data.size <= room0) 1 else 1 + ceil((data.size - room0).toDouble() / roomN).toInt()
        val out = ArrayList<ByteArray>(fragCnt)
        var off = 0
        for (i in 0 until fragCnt) {
            val room = if (i == 0) room0 else roomN
            val extra = if (i == 0) cfgLen else 0
            val take = min(data.size - off, room)
            val buf = ByteBuffer.allocate(HDR + extra + take)
            buf.put(MAGIC.toByte())
            buf.put(((if (isKey) FLAG_KEY else 0) or (if (cfgBytes != null) FLAG_CFG else 0) or (if (isAudio) FLAG_AUD else 0)).toByte())
            buf.put(i.toByte())
            buf.put(fragCnt.toByte())
            buf.putDouble(tsUs)
            buf.putDouble(durUs)
            if (i == 0 && cfgBytes != null) {
                buf.putInt(cfgBytes.size)
                buf.put(cfgBytes)
            }
            buf.put(data, off, take)
            off += take
            out.add(buf.array())
        }
        return out
    }

    class Frame(
        val key: Boolean,
        val timestampUs: Double,
        val durationUs: Double,
        val config: JSONObject?,
        val data: ByteArray
    )

    /** Fragment reassembler — same semantics as the JS makeAssembler(). */
    class Assembler {
        private var parts = ArrayList<ByteArray>()
        private var ts: Double? = null
        private var cnt = 0
        private var cfg: JSONObject? = null
        private var key = false
        private var dur = 0.0

        fun add(rec: ByteArray): Frame? {
            if (rec.size < HDR) return null
            val v = ByteBuffer.wrap(rec)
            if ((v.get().toInt() and 0xFF) != MAGIC) return null
            val flags = v.get().toInt() and 0xFF
            val fi = v.get().toInt() and 0xFF
            val fc = v.get().toInt() and 0xFF
            val t = v.double
            val d = v.double
            var off = HDR
            var c: JSONObject? = null
            if (fi == 0 && (flags and FLAG_CFG) != 0) {
                val len = v.getInt(HDR)
                if (len < 0 || HDR + 4 + len > rec.size) return null
                c = try { JSONObject(String(rec, HDR + 4, len, Charsets.UTF_8)) } catch (e: Exception) { null }
                off = HDR + 4 + len
            }
            val payload = rec.copyOfRange(off, rec.size)
            if (fi == 0) {
                parts = arrayListOf(payload); ts = t; cnt = fc; cfg = c
                key = (flags and FLAG_KEY) != 0; dur = d
            } else if (ts == t) parts.add(payload)
            else return null // stray fragment whose frag0 we missed
            if (parts.size < cnt) return null
            var total = 0
            for (p in parts) total += p.size
            val data = ByteArray(total)
            var o = 0
            for (p in parts) { System.arraycopy(p, 0, data, o, p.size); o += p.size }
            return Frame(key, ts ?: 0.0, dur, cfg, data)
        }
    }
}
