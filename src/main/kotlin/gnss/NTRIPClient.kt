package gnss

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class NTRIPClient(
    val userInfo: UserInfo,
    val mountPoint: String,
) {
    private var connection: HttpURLConnection? = null
    private var isRunning = false

    /**
     * 连接到NTRIP服务器并返回输入流
     * @return Pair<InputStream, () -> Unit> 输入流和关闭连接的函数
     * @throws Exception 如果连接失败
     */
    fun connectStream(): Pair<InputStream, () -> Unit> {

        val serverUrl = userInfo.serverUrl
        val port = userInfo.port
        val username = userInfo.username
        val password = userInfo.password


        val url = URL("http://$serverUrl:$port/$mountPoint")
        connection = url.openConnection() as HttpURLConnection
        connection?.apply {
            requestMethod = "GET"
            setRequestProperty("Ntrip-Version", "Ntrip/2.0")
            setRequestProperty("User-Agent", "NTRIP KotlinClient/1.0")

            // 基本认证
            val auth = "$username:$password"
            val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray())
            setRequestProperty("Authorization", "Basic $encodedAuth")

            connectTimeout = 5000
            readTimeout = 0 // 无限读取超时

            connect()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                isRunning = true
                val inputStream = inputStream
                return Pair(inputStream, ::disconnect)
            } else {
                throw Exception("Connection failed with code: $responseCode")
            }
        }
        throw Exception("Failed to establish connection")
    }

    /**
     * 关闭连接
     */
    fun disconnect() {
        isRunning = false
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            println("Error disconnecting: ${e.message}")
        }
        connection = null
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = isRunning
}

data class UserInfo(
    var serverUrl: String,
    var port: Int,
    var username: String,
    var password: String
)
