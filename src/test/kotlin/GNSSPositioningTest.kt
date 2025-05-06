import gnss.GNSSPositioningSystem
import gnss.SatelliteData
import kotlinx.serialization.json.Json
import org.example.gnss.RtcmHeader
import org.example.parser.Msm7Message
import org.example.parser.Msm7Satellite
import org.example.parser.SignalType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
class GNSSPositioningTest {

    @Test
    fun satelliteDataTest()
    {
        val gnssdata: List<SatelliteData> = Json.decodeFromString(File("debug_snapshot.json").readText())

        val obs_p_list = gnssdata.map {
            it.obs.pseudorange[0]
        }


        println(gnssdata)


    }
}