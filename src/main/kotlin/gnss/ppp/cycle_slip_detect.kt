package org.example.gnss.ppp

/**
 * 周跳检测算法
 * @param int
 * @return true发生周跳，false没有发生周跳
 */
fun cycle_slip_detect(int: Int):Boolean {
    return (int == 1)
}