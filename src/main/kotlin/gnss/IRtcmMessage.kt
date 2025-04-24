package gnss

import org.example.gnss.RtcmHeader

interface IRtcmMessage{
    val header: RtcmHeader
    fun toHumanReadable(): String
}
