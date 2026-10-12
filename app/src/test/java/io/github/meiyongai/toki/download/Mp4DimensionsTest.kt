package io.github.meiyongai.toki.download

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp4DimensionsTest {
    private fun box(name: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(payload.size + 8).putInt(payload.size + 8).put(name.toByteArray()).put(payload).array()

    @Test fun portraitDimensionsAreNotMislabeledByTheirLongEdge() {
        val header = ByteBuffer.allocate(92).putInt(92).put("tkhd".toByteArray()).array()
        ByteBuffer.wrap(header).putInt(84, 720 shl 16).putInt(88, 1280 shl 16)
        val handler = box("hdlr", ByteArray(8) + "vide".toByteArray() + ByteArray(12))
        val moov = box("moov", box("trak", header + box("mdia", handler)))
        assertEquals(720 to 1280, Mp4Dimensions.read(moov))
        assertEquals(720 to 1280, Mp4Dimensions.read(ByteArray(13) + moov))
        assertNull(Mp4Dimensions.read(moov.copyOf(moov.size - 2)))
        assertNull(Mp4Dimensions.read(header))
    }

    @Test fun audioHandlersAndFakeHeadersDoNotInventVideoDimensions() {
        val header = ByteBuffer.allocate(92).putInt(92).put("tkhd".toByteArray()).array()
        ByteBuffer.wrap(header).putInt(84, 720 shl 16).putInt(88, 1280 shl 16)
        val handler = box("hdlr", ByteArray(8) + "soun".toByteArray() + ByteArray(12))
        assertNull(Mp4Dimensions.read(box("moov", box("trak", header + box("mdia", handler)))))
    }
}
