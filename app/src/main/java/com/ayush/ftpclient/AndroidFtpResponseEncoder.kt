package com.ayush.ftpclient

import org.apache.mina.core.buffer.IoBuffer
import org.apache.mina.core.session.IoSession
import org.apache.mina.filter.codec.ProtocolCodecFactory
import org.apache.mina.filter.codec.ProtocolDecoder
import org.apache.mina.filter.codec.ProtocolEncoder
import org.apache.mina.filter.codec.ProtocolEncoderAdapter
import org.apache.mina.filter.codec.ProtocolEncoderOutput
import org.apache.mina.filter.codec.textline.TextLineDecoder
import java.nio.charset.StandardCharsets

/**
 * Custom MINA ProtocolEncoder for Apache FtpServer on Android.
 *
 * Apache FtpServer's default FtpResponseEncoder uses a static CharsetEncoder instance
 * which triggers CharsetEncoderICU bugs and IndexOutOfBoundsException crashes on Android 12+.
 *
 * This encoder converts response strings directly to UTF-8 byte arrays safely without
 * relying on stateful or thread-unsafe CharsetEncoderICU instances.
 */
class AndroidFtpResponseEncoder : ProtocolEncoderAdapter() {
    override fun encode(session: IoSession, message: Any, out: ProtocolEncoderOutput) {
        val value = message.toString()
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val buf = IoBuffer.allocate(bytes.size)
        buf.put(bytes)
        buf.flip()
        out.write(buf)
    }
}

class AndroidFtpServerProtocolCodecFactory : ProtocolCodecFactory {
    private val decoder: ProtocolDecoder = TextLineDecoder(StandardCharsets.UTF_8)
    private val encoder: ProtocolEncoder = AndroidFtpResponseEncoder()

    override fun getDecoder(session: IoSession): ProtocolDecoder = decoder
    override fun getEncoder(session: IoSession): ProtocolEncoder = encoder
}
