import org.example.tools.AlignedBitReader
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.ByteBuffer
import kotlin.test.Test

class BitReaderTest {
    // ------------------- 测试工具函数 -------------------
    private fun createTestReader(bytes: ByteArray, startBitOffset: Int = 0): AlignedBitReader {
        return AlignedBitReader(bytes, startBitOffset)
    }


    @Test
    fun test1(){
        // 0x8F (10001111)
        val reader = createTestReader(byteArrayOf(0x8F.toByte()))
        println(0x8f)
        assertEquals(0x8F, reader.read_n_Bit(8))
    }

    @Test
    fun `test readSignedBits - 读取8位有符号正数`() {
        // 0x3F (+63)
        val reader = createTestReader(byteArrayOf(0x3F.toByte()))
        assertEquals(63, reader.readSignedBits(8))
    }

    @Test
    fun `test readSignedBits - 读取32位有符号负数`() {
        // 0x80000000 -> -2147483648
        val bytes = ByteBuffer.allocate(4).putInt(-2147483648).array()
        val reader = createTestReader(bytes)
        assertEquals(-2147483648, reader.readSignedBits(32))
    }


    @Test
    fun `test readSignedBits - 读取8位有符号负数`() {
        // 0x83 (10000011) -> -125
        val reader = createTestReader(byteArrayOf(0x83.toByte()))
        assertEquals(-125, reader.readSignedBits(8))
    }
}