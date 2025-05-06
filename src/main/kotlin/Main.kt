package org.example

import gnss.GNSSPositioningSystem
import gnss.SatelliteData
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.example.gnss.RawRtcmMessage
import org.example.parser.GPSEphemerisMessage
import org.example.parser.GpsEphemeris
import org.example.parser.Msm7Message
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

fun main() {
    runBlocking {

        // 使用线程安全集合
        val rawMessages = mutableListOf<RawRtcmMessage>()
        val msm7Messages = mutableListOf<Msm7Message>()
        val GPSEphemeris = mutableListOf<GPSEphemerisMessage>()

        val system = GNSSPositioningSystem()
        val testDuration = 12.5.seconds // 测试持续时间
        // 启动所有协程
        val dataCollectionJob = launch { system.startDataCollection() }
        val positionFlowJob = launch { system.createPositionFlow() }

        val rawDataCollector = launch {
            system.rawDataStream.collect { message ->
                rawMessages.add(message)
            }
        }

        val msm7Collector = launch {
            system.pMsm7Stream.collect { message ->
                msm7Messages.add(message)
            }
        }

        launch {
            system.calculatePosition().collect {
                println("<UNK> $it")
            }
        }





        println("测试运行中，将持续${testDuration.inWholeSeconds}秒...")


        // 模拟运行一段时间
        // 主线程同时可以做其他事情
        try {
            withTimeout(testDuration) {
                while (true) {
                    delay(500)
                    // 可以在这里打印进度或其他监控信息
                    println("已收集: ${rawMessages.size}原始消息, ${msm7Messages.size}MSM7消息")
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 正常结束
        }

        // 取消所有协程
        dataCollectionJob.cancel()
        positionFlowJob.cancel()
        rawDataCollector.cancel()
        msm7Collector.cancel()

        println(rawMessages.size)
        println(msm7Messages.size)
        println(system)
    }
}
//fun main(){
//    val a:  List<SatelliteData> = Json.decodeFromString(File("debug_snapshot.json").readText())
//    a.map {
//        (prn, pseudorange, ephemeris) ->
//        println("prn: $prn, pseudorange: $pseudorange")
//        println("===================================")
//    }
//}