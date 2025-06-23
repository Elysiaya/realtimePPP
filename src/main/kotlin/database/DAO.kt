package org.example.database

import kotlinx.serialization.json.Json
import org.example.database.SatelliteEphemeris
import org.example.database.SatelliteEphemeris.af0
import org.example.database.SatelliteEphemeris.af1
import org.example.database.SatelliteEphemeris.af2
import org.example.database.SatelliteEphemeris.cic
import org.example.database.SatelliteEphemeris.cis
import org.example.database.SatelliteEphemeris.crc
import org.example.database.SatelliteEphemeris.crs
import org.example.database.SatelliteEphemeris.cuc
import org.example.database.SatelliteEphemeris.cus
import org.example.database.SatelliteEphemeris.deltaN
import org.example.database.SatelliteEphemeris.e
import org.example.database.SatelliteEphemeris.i0
import org.example.database.SatelliteEphemeris.idot
import org.example.database.SatelliteEphemeris.m0
import org.example.database.SatelliteEphemeris.omega
import org.example.database.SatelliteEphemeris.omega0
import org.example.database.SatelliteEphemeris.omegaDot
import org.example.gnss.SatelliteData
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.example.database.SatelliteEphemeris.prn
import org.example.database.SatelliteEphemeris.satelliteClockBias
import org.example.database.SatelliteEphemeris.sqrtA
import org.example.database.SatelliteEphemeris.tgd
import org.example.database.SatelliteEphemeris.toc
import org.example.database.SatelliteEphemeris.week
import org.example.database.SignalObservation.cnr
import org.example.database.SignalObservation.halfCycleAmbiguity
import org.example.database.SignalObservation.lockTime
import org.example.database.SignalObservation.phaserange
import org.example.database.SignalObservation.pseudorange
import org.example.database.SignalObservation.signalType
import org.example.database.SignalObservation.tow
import org.example.parser.GpsEphemeris
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll

fun insertSatelliteData(satelliteData: SatelliteData) = transaction{
    val ephemeris = satelliteData.ephemeris
    val obs = satelliteData.obs

    // 先检查是否存在
    val exists = SatelliteEphemeris
        .select { (prn eq ephemeris.prn) and (toc eq ephemeris.toc.toLong()) }
        .empty()
        .not()

    if (!exists){
        SatelliteEphemeris.insert{
            it[prn] = ephemeris.prn
            it[toc] = ephemeris.toc.toLong()
            it[week] = ephemeris.week
            it[sqrtA] = ephemeris.sqrtA
            it[e] = ephemeris.e
            it[i0] = ephemeris.i0
            it[omega0] = ephemeris.omega0
            it[m0] = ephemeris.m0
            it[omega] = ephemeris.omega
            it[deltaN] = ephemeris.deltaN
            it[idot] = ephemeris.idot
            it[omegaDot] = ephemeris.omegaDot
            it[cuc] = ephemeris.cuc
            it[cus] = ephemeris.cus
            it[crc] = ephemeris.crc
            it[crs] = ephemeris.crs
            it[cic] = ephemeris.cic
            it[cis] = ephemeris.cis
            it[tgd] =  ephemeris.tgd
            it[af0] = ephemeris.af0
            it[af1] = ephemeris.af1
            it[af2] = ephemeris.af2
            it[satelliteClockBias] = 0.0
        }
    }



    SignalObservation.insert {
        it[prn] = obs.prn
        it[tow] = obs.tow.toLong()
        it[signalType] = Json.encodeToString(obs.signalTypes)
        it[pseudorange] = Json.encodeToString(obs.pseudoranges)
        it[phaserange] = Json.encodeToString(obs.phaseranges)
//        it[wavelengthIf] = 0.0
        it[cnr] = Json.encodeToString(obs.cnrs)
        it[lockTime] = Json.encodeToString(obs.lockTimes)
        it[halfCycleAmbiguity] = Json.encodeToString(obs.halfCycleAmbiguities)

    }
}

fun batchInsertSatelliteData(dataList: List<SatelliteData>) = transaction {
    // Pre-check existing records in a single query for better performance
    val existingRecords = SatelliteEphemeris
        .select { (prn inList dataList.map { it.ephemeris.prn }) and (toc inList dataList.map { it.ephemeris.toc.toLong() }) }
        .associateBy { it[prn] to it[toc] }

    // Batch insert ephemeris data
    SatelliteEphemeris.batchInsert(
        dataList.filterNot {
            existingRecords.containsKey(it.ephemeris.prn to it.ephemeris.toc.toLong())
        }
    ) { satellite ->
        this[prn] = satellite.ephemeris.prn
        this[toc] = satellite.ephemeris.toc.toLong()
        this[week] = satellite.ephemeris.week
        this[sqrtA] = satellite.ephemeris.sqrtA
        this[e] = satellite.ephemeris.e
        this[i0] = satellite.ephemeris.i0
        this[omega0] = satellite.ephemeris.omega0
        this[m0] = satellite.ephemeris.m0
        this[omega] = satellite.ephemeris.omega
        this[deltaN] = satellite.ephemeris.deltaN
        this[idot] = satellite.ephemeris.idot
        this[omegaDot] = satellite.ephemeris.omegaDot
        this[cuc] = satellite.ephemeris.cuc
        this[cus] = satellite.ephemeris.cus
        this[crc] = satellite.ephemeris.crc
        this[crs] = satellite.ephemeris.crs
        this[cic] = satellite.ephemeris.cic
        this[cis] = satellite.ephemeris.cis
        this[tgd] = satellite.ephemeris.tgd
        this[af0] = satellite.ephemeris.af0
        this[af1] = satellite.ephemeris.af1
        this[af2] = satellite.ephemeris.af2
        this[satelliteClockBias] = 0.0
    }

    // Batch insert observations
    SignalObservation.batchInsert(dataList) { satellite ->
        val obs = satellite.obs
        this[prn] = obs.prn
        this[tow] = obs.tow.toLong()
        this[signalType] = Json.encodeToString(obs.signalTypes)
        this[pseudorange] = Json.encodeToString(obs.pseudoranges)
        this[phaserange] = Json.encodeToString(obs.phaseranges)
//        this[wavelengthIf] = 0.0
        this[cnr] = Json.encodeToString(obs.cnrs)
        this[lockTime] = Json.encodeToString(obs.lockTimes)
        this[halfCycleAmbiguity] = Json.encodeToString(obs.halfCycleAmbiguities)
    }
}

fun getEphemerisByToc(toc: Long): List<GpsEphemeris> = transaction {
    SatelliteEphemeris
//        .select { (SatelliteEphemeris.prn eq prn) and (SatelliteEphemeris.toc eq toc) }
        .select{(SatelliteEphemeris.toc greaterEq (toc - 7200)) and
                (SatelliteEphemeris.toc lessEq (toc + 7200)) }
        .map { row ->
            GpsEphemeris(
                prn = row[SatelliteEphemeris.prn],
                toc = row[SatelliteEphemeris.toc].toInt(),
                week = row[week],
                sqrtA = row[sqrtA],
                e = row[e],
                i0 = row[i0],
                omega0 = row[omega0],
                m0 = row[m0],
                omega = row[omega],
                deltaN = row[deltaN],
                idot = row[idot],
                omegaDot = row[omegaDot],
                cuc = row[cuc],
                cus = row[cus],
                crc = row[crc],
                crs = row[crs],
                cic = row[cic],
                cis = row[cis],
                tgd = row[tgd],
                af0 = row[af0],
                af1 = row[af1],
                af2 = row[af2],
            )
        }
}

fun getObsbyTow(tow: Long): List<SignalObservationData> = transaction {
    SignalObservation
        .select { SignalObservation.tow eq tow }
        .orderBy(SignalObservation.prn)  // 按PRN排序
        .map { SignalObservationData.fromResultRow(it) }
}

fun getAllTows(): List<Long> = transaction {
    SignalObservation
        .slice(SignalObservation.tow)
        .selectAll()
        .distinctBy { it[SignalObservation.tow] }
        .map { it[SignalObservation.tow] }
}


fun main(){
    initDatabase()
    val o = getObsbyTow(144458)
    val e = getEphemerisByToc(144458)
    val g = getAllTows()
    println(g.take(1500).last()-g.take(1500).first())
}