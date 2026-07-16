package com.livepoc.android

import java.io.ByteArrayOutputStream

/**
 * H.264 helpers: avcC (WebCodecs description) ↔ Annex-B conversion.
 * The web broadcaster ships avcC config + AVCC (length-prefixed) frames;
 * Android MediaCodec speaks Annex-B on both ends.
 */
object AvcUtils {
    private val START = byteArrayOf(0, 0, 0, 1)

    class AvcC(val spsList: List<ByteArray>, val ppsList: List<ByteArray>, val nalLengthSize: Int)

    /** Parse an avcC (AVCDecoderConfigurationRecord). NAL payloads returned WITHOUT start codes. */
    fun parseAvcC(d: ByteArray): AvcC? {
        if (d.size < 7 || d[0].toInt() != 1) return null
        val nalLen = (d[4].toInt() and 0x03) + 1
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        var o = 5
        val numSps = d[o].toInt() and 0x1F; o++
        repeat(numSps) {
            if (o + 2 > d.size) return null
            val len = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF); o += 2
            if (o + len > d.size) return null
            sps.add(d.copyOfRange(o, o + len)); o += len
        }
        if (o >= d.size) return AvcC(sps, pps, nalLen)
        val numPps = d[o].toInt() and 0xFF; o++
        repeat(numPps) {
            if (o + 2 > d.size) return null
            val len = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF); o += 2
            if (o + len > d.size) return null
            pps.add(d.copyOfRange(o, o + len)); o += len
        }
        return AvcC(sps, pps, nalLen)
    }

    fun withStartCode(nal: ByteArray): ByteArray = START + nal

    /** Convert AVCC length-prefixed frame data to Annex-B start-code framing. */
    fun avccToAnnexB(data: ByteArray, nalLengthSize: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        var o = 0
        while (o + nalLengthSize <= data.size) {
            var len = 0
            for (i in 0 until nalLengthSize) len = (len shl 8) or (data[o + i].toInt() and 0xFF)
            o += nalLengthSize
            if (len <= 0 || o + len > data.size) break
            out.write(START)
            out.write(data, o, len)
            o += len
        }
        return out.toByteArray()
    }

    fun isAnnexB(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1) return true
        return data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1
    }

    /** Iterate NAL units of an Annex-B stream: fn(offset, length) over payload (past start code). */
    fun forEachNalAnnexB(data: ByteArray, fn: (Int, Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 2 < data.size) {
            val isStart3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
            if (isStart3) {
                if (nalStart >= 0) {
                    var end = i
                    if (end > nalStart && data[end - 1].toInt() == 0) end-- // 4-byte start code
                    fn(nalStart, end - nalStart)
                }
                nalStart = i + 3
                i += 3
            } else i++
        }
        if (nalStart in 0 until data.size) fn(nalStart, data.size - nalStart)
    }

    /** Extract SPS and PPS NALs (without start codes) from an Annex-B buffer. */
    fun extractSpsPps(annexb: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        forEachNalAnnexB(annexb) { off, len ->
            if (len > 0) {
                when (annexb[off].toInt() and 0x1F) {
                    7 -> if (sps == null) sps = annexb.copyOfRange(off, off + len)
                    8 -> if (pps == null) pps = annexb.copyOfRange(off, off + len)
                }
            }
        }
        return Pair(sps, pps)
    }

    /** First NAL type in an Annex-B buffer, or -1. */
    fun firstNalType(annexb: ByteArray): Int {
        var t = -1
        forEachNalAnnexB(annexb) { off, len -> if (t == -1 && len > 0) t = annexb[off].toInt() and 0x1F }
        return t
    }

    /** WebCodecs codec string from a raw SPS NAL (starting with the NAL header byte). */
    fun codecString(sps: ByteArray): String {
        if (sps.size < 4) return "avc1.42001f"
        return String.format("avc1.%02x%02x%02x", sps[1], sps[2], sps[3])
    }
}
