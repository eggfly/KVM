package eggfly.kvm.demo

import java.lang.StringBuilder

@Suppress("SpellCheckingInspection")
fun leet117() {
    val input = arrayOf(1, 2, 3, 4, 5, null, 7)
    // 期待结果: 1, #, 2, 3, #, 4, 5, 7, #
    fillNextPointerAndPrint(input)
}

/**
 * 这个思路很土，而且依赖这道题的按层的输入数据
 */
fun fillNextPointerAndPrint(input: Array<Int?>) {
    var level = 1 // 第几层
    val builder = StringBuilder()
    input.forEachIndexed { index, value ->
        if (value != null) {
            builder.append("$value,")
        }
        if (index + 1 == (1 shl level) - 1 /* 2的(level-1)次方,先不管溢出 */) {
            builder.append("#,")
            level++
        }
    }
    System.err.println(builder.toString())
}
