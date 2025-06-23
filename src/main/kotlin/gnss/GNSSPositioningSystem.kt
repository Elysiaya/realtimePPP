package gnss

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.example.database.batchInsertSatelliteData
import org.example.database.insertSatelliteData
import org.example.gnss.PositionResult
import org.example.gnss.RawRtcmMessage
import org.example.gnss.RtcmStreamProcessor
import org.example.gnss.SPP
import org.example.gnss.SatelliteData
import org.example.parser.*
import java.io.File


class GNSSPositioningSystem {
    var stationcoordinates: Coordinates? = null

    // 在类中定义统一的作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 配置热流缓存（保留最近30秒数据，假设每秒10个数据点）
    private val _rawDataStream = MutableSharedFlow<RawRtcmMessage>(
        replay = 300, // 缓存容量
        extraBufferCapacity = 50, // 额外缓冲区
        onBufferOverflow = BufferOverflow.DROP_OLDEST //当缓冲区满的时候丢弃旧数据
    )

    // 公开为只读热流
    val rawDataStream: SharedFlow<RawRtcmMessage> = _rawDataStream

    private val _pMsm7Stream = MutableSharedFlow<Msm7Message>(
        replay = 300, // 缓存容量
        extraBufferCapacity = 50, // 额外缓冲区
        onBufferOverflow = BufferOverflow.DROP_OLDEST //当缓冲区满的时候丢弃旧数据
    )
    val pMsm7Stream: SharedFlow<Msm7Message> = _pMsm7Stream

    private val _pGpsEphemerisStream = MutableSharedFlow<GPSEphemerisMessage>(
        replay = 300, // 缓存容量
        extraBufferCapacity = 50, // 额外缓冲区
        onBufferOverflow = BufferOverflow.DROP_OLDEST //当缓冲区满的时候丢弃旧数据
    )
    val pGpsEphemerisStream: SharedFlow<GPSEphemerisMessage> = _pGpsEphemerisStream

    // 网络数据接收器
    fun startDataCollection() {
        // 配置NTRIP客户端参数
        val mountPoint1 = "BCEP00BKG0"
        val mountPoint2 = "ALIC00AUS0"

        val login = UserInfo(
            serverUrl = "ntrip.data.gnss.ga.gov.au",
            password = "zhangxv123@A",
            port =  2101,
            username =  "zhangxu0072")


        val ntripClient1 = NTRIPClient(
            login,
            mountPoint1,
        )
        val ntripClient2 = NTRIPClient(
            login,
            mountPoint2,
        )
        //第一个网络连接
        val (inputStream, closeConnection) = try {
            ntripClient1.connectStream()
        } catch (e: Exception) {
            println("Failed to connect to NTRIP server: ${e.message}")
            throw e
        }
        //第二个网络连接
        val (inputStream2, closeConnection2) = try {
            ntripClient2.connectStream()
        } catch (e: Exception) {
            println("Failed to connect to NTRIP server: ${e.message}")
            throw e
        }

        val processor = RtcmStreamProcessor(inputStream)
        val processor2 = RtcmStreamProcessor(inputStream2)

        processor.messageFlow
            .catch { e -> println("Error in RTCM stream: ${e.message}") }
            .onEach { message -> _rawDataStream.emit(message) }
            .launchIn(scope)

        processor2.messageFlow
            .catch { e -> println("Error in RTCM stream: ${e.message}") }
            .onEach { message -> _rawDataStream.emit(message) }
            .launchIn(scope)
    }

    suspend fun createPositionFlow() {
        val m = Msm7Parser()
        var pendingMessage: Msm7Message? = null
        _rawDataStream.collect { s ->
            when (s.header.messageType) {
                1077 -> {
                    try {
                        val msm7: Msm7Message = m.parse(s) as Msm7Message
                        //这里有问题，multipleMessageBit总是为true
                        if (msm7.satellites.size<5) {
                            pendingMessage = msm7
                        }else{val completeMessage = if (pendingMessage != null) {
                                mergeMessages(pendingMessage!!,msm7).also {
                                    pendingMessage = null
                                }
                            }else{
                                msm7
                            }
                            _pMsm7Stream.emit(completeMessage)
                        }

                    } catch (e: Exception) {
                        println("MSM7解析失败[${s.payload.size}字节]: ${e.message}")
                    }
                }

                1019 -> {
                    try {
                        if (_pGpsEphemerisStream.replayCache.size < 32) {
                            val eph = GPSEphemerisParser().parse(s) as GPSEphemerisMessage
                            _pGpsEphemerisStream.emit(eph)
                        }

                    } catch (e: Exception) {
                        println("星历解析失败: ${e.message}")
                    }
                }

                else -> {
//                    println("未处理的消息类型: ${s.header.messageType}")
                }
            }
        }
    }
    // 合并多个MSM7消息的函数
    private fun mergeMessages(first: Msm7Message, second: Msm7Message): Msm7Message {
        // 实现合并逻辑，例如合并卫星数据等
        return Msm7Message(
            header = first.header,
            stationId = first.stationId,
            tow = first.tow, // 使用第一个消息的时间
            multipleMessageBit = second.multipleMessageBit, // 使用最后一个消息的标志
            satellites = first.satellites + second.satellites // 合并卫星数据
        )
    }


    suspend fun calculatePosition(): Flow<PositionResult> {
        val allGPSEphemeris = pGpsEphemerisStream.take(32).toList().map {
            it.gpsEphemeris
        }
        //核心的计算过程
        return pMsm7Stream.map { message ->
            calculatePositionImpl(message, allGPSEphemeris)
        }
    }

    //实现核心的计算逻辑
    private fun calculatePositionImpl(
        msm7: Msm7Message,
        ephList: List<GpsEphemeris>
    ): PositionResult {
        //筛选可用星历
        var validSatellites = msm7.satellites.mapNotNull { satellite ->
            ephList.find { it.prn == satellite.prn }?.let { eph ->
                SatelliteData(
                    satellite.prn,
                    satellite.toSignalObservationData(),
                    ephemeris = eph
                )
            }
        }
//        batchInsertSatelliteData(validSatellites)
//        File("debug_snapshot.json").writeText(Json.encodeToString(saveSatelliteData[0]))
        //过滤明显异常的值
//        validSatellites = validSatellites.filter { satellite ->
//            satellite.obs.signalTypes.size > 1 && satellite.obs.pseudorange.all { it > 10000 }
//        }
        return SPP().SPP(validSatellites)
    }
}