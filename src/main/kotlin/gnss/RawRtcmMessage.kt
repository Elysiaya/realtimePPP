package org.example.gnss

// RTCM 消息完整结构
data class RawRtcmMessage(
    val header: RtcmHeader,
    val payload: ByteArray,
    val crcValid: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawRtcmMessage
        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        if (crcValid != other.crcValid) return false
        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + crcValid.hashCode()
        return result
    }
}

// RTCM 消息头结构
data class RtcmHeader(
    val messageType: Int,
    val length: Int,
    val crc: Int
)
