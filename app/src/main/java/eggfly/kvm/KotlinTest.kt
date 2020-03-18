package eggfly.kvm

import android.util.Log

object KotlinTest {
    private const val TAG = "KotlinTest"

    init {
        Log.d(TAG, "init()")
    }

    fun test() {
        val start = 1234567890123L
        val result = foo(-1, start)
        Log.d(TAG, result.toString())
    }

    fun foo2(): Int {
        return foo2()
    }

    fun foo(step: Int, value: Long): Long {
        Log.d(TAG, "foo()")
        if (value == 0L) {
            return 0L
        }
        return 1000 + foo(step, value + step)
    }
}