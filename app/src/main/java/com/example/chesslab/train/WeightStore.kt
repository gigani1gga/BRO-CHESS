package com.example.chesslab.train

import com.example.chesslab.engine.Tables.NF
import java.io.File
import java.nio.ByteBuffer

/** Persists learned weight vectors as raw doubles so learning survives app restarts. */
class WeightStore(private val dir: File) {

    fun load(name: String): DoubleArray? = try {
        val f = File(dir, "$name.bin")
        if (!f.isFile || f.length() != NF * 8L) null
        else {
            val bb = ByteBuffer.wrap(f.readBytes())
            val w = DoubleArray(NF) { bb.getDouble() }
            if (w.all { it.isFinite() }) w else null
        }
    } catch (_: Exception) {
        null
    }

    fun save(name: String, w: DoubleArray) {
        try {
            val bb = ByteBuffer.allocate(NF * 8)
            for (x in w) bb.putDouble(x)
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bb.array())
            if (!tmp.renameTo(File(dir, "$name.bin"))) {
                File(dir, "$name.bin").writeBytes(bb.array())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
