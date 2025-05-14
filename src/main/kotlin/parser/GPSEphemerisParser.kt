package org.example.parser

import gnss.IRtcmMessage
import org.example.gnss.RawRtcmMessage
import org.example.gnss.RtcmHeader
import org.example.tools.AlignedBitReader
import kotlin.math.pow

class GPSEphemerisParser():IRtcmMessageParser {
    override fun parse(rawRtcmMessage: RawRtcmMessage): IRtcmMessage {


        val header = rawRtcmMessage.header
        val payload = rawRtcmMessage.payload

        val reader = AlignedBitReader(payload)
        val messagetype = reader.read_n_Bit(12)
        val satelliteID = reader.read_n_Bit(6)
        val weeknumber = reader.read_n_Bit(10)
        val SV_ACCURACY = reader.read_n_Bit(4)
        val code_on_L2 = reader.read_n_Bit(2)
        val IDOT = reader.readSignedBits(14)
        val IODE = reader.read_n_Bit(8)

        val toc = reader.read_n_Bit(16)
        val af2 =reader.readSignedBits(8)
        val af1 = reader.readSignedBits(16)
        val af0 = reader.readSignedBits(22)
        val IODC = reader.read_n_Bit(10)
        val Crs = reader.readSignedBits(16)
        val DELTE_n = reader.readSignedBits(16)

        val M0 = reader.readSignedBits(32)
        val Cuc = reader.readSignedBits(16)
        val e = reader.read_n_Bit(32)
        val Cus = reader.readSignedBits(16)

        val sqrt_A = reader.read_n_Bit(32)?.toLong()?.and(0xFFFFFFFFL)
        val toe = reader.read_n_Bit(16)
        val Cic = reader.readSignedBits(16)
        val OMEGA0 = reader.readSignedBits(32)

        val Cis = reader.readSignedBits(16)
        val i0 = reader.readSignedBits(32)
        val Crc = reader.readSignedBits(16)
        val omega = reader.readSignedBits(32)

        val OMEGADOT = reader.readSignedBits(24)
        val tgd = reader.readSignedBits(8)
        val SV_HEALTH = reader.read_n_Bit(6)
        val L2_P_data_flag = reader.read_n_Bit(1)

        val  Fit_Interval = reader.read_n_Bit(1)


        val singleEph = GpsEphemeris(
            prn = satelliteID?:0,
            toc = toc!!*16,
            week = weeknumber?.plus(2048) ?: 0,
            sqrtA = sqrt_A!! * 2.0.pow(-19),
            e = e!! * 2.0.pow(-33),
            i0 = i0!! * 2.0.pow(-31) * Math.PI,
            omega0 = OMEGA0!! * 2.0.pow(-31)* Math.PI,
            omega = omega!! * 2.0.pow(-31)* Math.PI,
            omegaDot = OMEGADOT!!.times(Math.scalb(Math.PI,-43)),
            m0 = M0!! * 2.0.pow(-31)* Math.PI,
            deltaN = DELTE_n!! * 2.0.pow(-43) * Math.PI,
            idot = IDOT!! * 2.0.pow(-43) * Math.PI,
            cuc =  Cuc!! * 2.0.pow(-29),
            cic = Cic!! * 2.0.pow(-29),
            cis = Cis!! * 2.0.pow(-29),
            crc = Crc!! * 2.0.pow(-5),
            crs = Crs!! * 2.0.pow(-5),
            cus = Cus!! * 2.0.pow(-29),

            tgd = tgd!! * 2.0.pow(-31),
            af0 = af0!! * 2.0.pow(-31),
            af1 = af1!! * 2.0.pow(-43),
            af2 = af2!! * 2.0.pow(-55),
        )

        return GPSEphemerisMessage(
            header = header,
            gpsEphemeris = singleEph,  // 保留单条
        )
    }
}

data class GPSEphemerisMessage(
    override val header: RtcmHeader,
    val gpsEphemeris:GpsEphemeris,
):IRtcmMessage {
    override fun toHumanReadable(): String {
        return """
            gpsEphemeris.prn: ${gpsEphemeris.prn}
            gpsEphemeris.toe: ${gpsEphemeris.toc}
        """.trimIndent()

    }
}