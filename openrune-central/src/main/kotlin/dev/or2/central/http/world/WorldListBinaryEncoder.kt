package dev.or2.central.http.world

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.charset.Charset

object WorldListBinaryEncoder {
    private val cp1252: Charset = Charset.forName("Windows-1252")

    fun encode(worlds: List<WorldRow>): ByteArray {
        val buffer = Unpooled.buffer()
        val pos = buffer.writerIndex()
        buffer.writeInt(0)
        buffer.writeShort(worlds.size)
        for (world in worlds) {
            validate(world)
            buffer.writeShort(world.worldId)
            buffer.writeInt(world.properties)
            buffer.pjstr( world.host)
            buffer.pjstr( world.activity)
            buffer.writeByte(world.location)
            buffer.writeShort(world.population)
        }
        val end = buffer.writerIndex()
        buffer.writerIndex(pos)
        buffer.writeInt(end - pos - Int.SIZE_BYTES)
        buffer.writerIndex(end)
        val out = ByteArray(buffer.readableBytes())
        buffer.readBytes(out)
        buffer.release()
        return out
    }

    private fun validate(world: WorldRow) {
        require(world.host.indexOf('\u0000') < 0 && world.activity.indexOf('\u0000') < 0) {
            "strings must not contain NUL"
        }
        require(world.location in 0..255) { "location must fit in unsigned byte" }
        require(world.worldId in 0..65535) { "worldId must fit in unsigned short" }
        require(world.population in Short.MIN_VALUE..Short.MAX_VALUE) {
            "population must fit in signed 16-bit"
        }
    }


    public fun ByteBuf.pjstr(
        s: CharSequence,
        charset: Charset = Cp1252Charset,
    ): ByteBuf {
        writeCharSequence(s, charset)
        writeByte(0)
        return this
    }
}
