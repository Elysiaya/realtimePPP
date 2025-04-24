package gnss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.example.gnss.RawRtcmMessage
import org.example.gnss.RtcmStreamProcessor
import org.example.parser.*

class GNSSPositioningSystem {
    var stationcoordinates: Coordinates? = null

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
        val mountPoint = "BCEP00BKG0"
//        val mountPoint = "NARE00AUS0"
        val username = "zhangxu0072"
        val password = "zhangxv123@A"

        val ntripClient = NTRIPClient(
            serverUrl = serverUrl,
            port = port,
            mountPoint = mountPoint,
            username = username,
            password = password
        )

        val (inputStream, closeConnection) = try {
            ntripClient.connectStream()
        }catch (e:Exception){
            println("Failed to connect to NTRIP server: ${e.message}")
            throw e
        }

        val processor = RtcmStreamProcessor(inputStream)

        processor.messageFlow
            .flowOn(Dispatchers.IO)
            .catch { e -> println("Error in RTCM stream: ${e.message}") }
            .collect { message ->
                _rawDataStream.emit(message)
            }
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
                        val eph = GPSEphemerisParser().parse(s)
                        _pGpsEphemerisStream.emit(eph as GPSEphemerisMessage)
                    } catch (e: Exception) {
                        println("星历解析失败: ${e.message}")
                    }
                }
                else -> println("未处理的消息类型: ${s.header.messageType}")
            }
        }
    }


    //定位处理
//    fun createPositionFlow(): Flow<IRtcmMessage> {
//        val r = _rawDataStream.transform<RawRtcmMessage,IRtcmMessage> {
//            s ->
//            when(s.header.messageType){
//                1077 -> Msm7Parser().parse(s)
//                1019 -> GPSEphemerisParser().parse(s)
//            }
//        }
//        return r
//    }
//    // 定位处理器
//    fun createPositionFlow(): Flow<Position> = flow {
//        // 收集所需时间窗口的数据（如最近5秒）
//        val windowData = mutableListOf<GNSSData>()
//
//        rawDataStream.collect { newData ->
//            windowData.add(newData)
//
//            // 移除过时数据（滑动窗口）
//            val currentTime = getCurrentTime() // 你的时间获取实现
//            windowData.removeAll { currentTime - it.timestamp > 5000 }
//
//            // 当有足够数据时计算位置
//            if (windowData.size >= MIN_DATA_POINTS) {
//                val position = calculatePosition(windowData)
//                emit(position)
//            }
//        }
//    }
    
//    private fun calculatePosition(data: List<GNSSData>): Position {
//        // 你的定位算法实现
//    }
}