package org.example.parser

import gnss.IRtcmMessage
import org.example.gnss.RawRtcmMessage

interface IRtcmMessageParser {
    fun parse(
        rawRtcmMessage: RawRtcmMessage,
    ): IRtcmMessage
}
