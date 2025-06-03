package org.example.gnss.ppp

import kotlinx.serialization.json.Json
import org.example.gnss.SatelliteData
import java.io.File

/**
 * 从Json文件中格式化观测数据
 * @return 格式化之后的数据
 */
fun getdata():MutableList<Epoch>{
    val gnssdata: List<SatelliteData> = Json.decodeFromString(File("debug_snapshotlist.json").readText())

    val time = gnssdata.map { it.obs.tow }.distinct()

    val epochs= mutableListOf<Epoch>()

    for (t in time){
        epochs.add(
            Epoch(gnssdata.filter {  it.obs.tow == t })
        )
    }
    return epochs
}
data class Epoch(
    val date: List<SatelliteData>,
)