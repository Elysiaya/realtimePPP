package org.example.gnss.ppp
import org.example.database.getAllTows
import org.example.database.getEphemerisByToc
import org.example.database.getObsbyTow
import org.example.database.initDatabase
import org.example.gnss.SatelliteData

data class Epoch(
    val date: List<SatelliteData>,
)
fun getdata():MutableList<Epoch>{
    initDatabase()
    val o = getObsbyTow(144458)
    val g = getAllTows()
    val epochs= mutableListOf<Epoch>()

    for (t in g){
        val obs = getObsbyTow(t)

        val v = obs.map {observation ->
            SatelliteData(
                prn = observation.prn,
                obs = observation,
                ephemeris = getEphemerisByToc(observation.tow).first { ephemeris ->
                    observation.prn == ephemeris.prn }
            )
        }

        epochs.add(
            Epoch(
                date = v
            )
        )
    }
    return epochs
}

