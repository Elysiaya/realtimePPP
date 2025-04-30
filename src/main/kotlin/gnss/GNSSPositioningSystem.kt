package gnss

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import org.example.gnss.RawRtcmMessage
import org.example.gnss.RtcmStreamProcessor
import org.example.parser.*
import kotlin.math.abs

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
        extraBufferCapacity = 50 // 额外缓冲区
    )
    val pMsm7Stream: SharedFlow<Msm7Message> = _pMsm7Stream

    private val _pGpsEphemerisStream = MutableSharedFlow<GPSEphemerisMessage>(
        replay = 300, // 缓存容量
        extraBufferCapacity = 50 // 额外缓冲区
    )
    val pGpsEphemerisStream: SharedFlow<GPSEphemerisMessage> = _pGpsEphemerisStream
    
    // 网络数据接收器
    suspend fun startDataCollection() {
        // 配置NTRIP客户端参数
        val serverUrl = "ntrip.data.gnss.ga.gov.au"
        val port = 2101
        val mountPoint1 = "BCEP00BKG0"
        val mountPoint2 = "NARE00AUS0"
        val username = "zhangxu0072"
        val password = "zhangxv123@A"

        val ntripClient1 = NTRIPClient(
            serverUrl = serverUrl,
            port = port,
            mountPoint = mountPoint1,
            username = username,
            password = password
        )
        val ntripClient2 = NTRIPClient(
            serverUrl = serverUrl,
            port = port,
            mountPoint = mountPoint2,
            username = username,
            password = password
        )
        //第一个网络连接
        val (inputStream, closeConnection) = try {
            ntripClient1.connectStream()
        }catch (e:Exception){
            println("Failed to connect to NTRIP server: ${e.message}")
            throw e
        }
        //第二个网络连接
        val (inputStream2, closeConnection2) = try {
            ntripClient2.connectStream()
        }catch (e:Exception){
            println("Failed to connect to NTRIP server: ${e.message}")
            throw e
        }

        val processor = RtcmStreamProcessor(inputStream)
        val processor2 = RtcmStreamProcessor(inputStream2)

        processor.messageFlow
            .catch { e -> println("Error in RTCM stream: ${e.message}") }
            .onEach {  message -> _rawDataStream.emit(message) }
            .launchIn(CoroutineScope(Dispatchers.IO))

        processor2.messageFlow
            .catch { e -> println("Error in RTCM stream: ${e.message}") }
            .onEach {  message -> _rawDataStream.emit(message) }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    suspend fun createPositionFlow(){
        _rawDataStream.collect { s ->
            when (s.header.messageType) {
                1077 -> {
                    try {
                        val msm7 = Msm7Parser().parse(s)
                        _pMsm7Stream.emit(msm7 as Msm7Message)
                    } catch (e: Exception) {
                        println("MSM7解析失败[${s.payload.size}字节]: ${e.message}")
                    }
                }
                1019 -> {
                    try {
                        if (_pGpsEphemerisStream.replayCache.size < 32){
                            val eph = GPSEphemerisParser().parse(s) as GPSEphemerisMessage
                            _pGpsEphemerisStream.emit(eph)
                        }

                    } catch (e: Exception) {
                        println("星历解析失败: ${e.message}")
                    }
                }
                else -> println("未处理的消息类型: ${s.header.messageType}")
            }
        }
    }

    
    suspend fun calculatePosition(): Flow<PositionResult> {
        val allGPSEphemeris = pGpsEphemerisStream.take(32).toList().mapNotNull {
            it.gpsEphemeris
        }

        //核心的计算过程
        TODO("这里还需要写点东西，")
    }


    //实现核心的计算逻辑
    fun calculatePositionImpl(
        msm7: Msm7Message,
        ephList: List<GpsEphemeris>
    ): PositionResult {
        //筛选可用星历
        val validSatellites = msm7.satellites.mapNotNull { satellite ->
            ephList.find { it.prn == satellite.prn }?.let {eph->
                SatelliteData(
                    satellite.prn,
                    satellite.pseudorange[0],
                    ephemeris = eph
                )
            }
        }

        TODO("这里还需要写点东西，")

    }
}

data class SatelliteData(
    val prn: Int,
    val pseudorange: Double,
    val ephemeris: GpsEphemeris
)

//密封类，用于表示几种特定的类型，它的所有子类必须再编译期确定，常用于表示固定的几种可能状态
sealed class PositionResult {
    data class Success(
        val x: Double,
        val y: Double,
        val z: Double,
        val accuracy: Double
    ) : PositionResult()

    data class Error(val message: String) : PositionResult()
}