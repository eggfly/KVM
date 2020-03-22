package eggfly.kvm

import android.util.Log
import eggfly.kvm.core.KVMAndroid
import kotlin.RuntimeException

object KotlinTest {
    private const val TAG = "KotlinTest"

    init {
        Log.d(TAG, "init()")
    }

    /**
     * start = 30000L 左右 会StackOverflow
     */
    @Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_UNSIGNED_LITERALS")
    fun test() {
        val start = 3L
        val result =
            foo(-1, start)
        Log.d(TAG, result.toString())
    }

    fun testTime(): Long {
        val startTime = System.currentTimeMillis()
        test()
        val timeDelta = System.currentTimeMillis() - startTime
        Log.d("KVMAndroid", "It costs $timeDelta ms to run test() directly.")
        return timeDelta
    }

    data class TestClass(val test: Any?)

    @Suppress("EXPERIMENTAL_API_USAGE")
    data class TestSimpleClass(val value: UInt)

    @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS", "EXPERIMENTAL_API_USAGE")
    fun foo(
        step: Int,
        value: Long
    ): Long {
        // Log.d(TAG, "foo()")
        if (value == 0L) {
            val l = TestClass(1)
            Log.d(TAG, "finally return: $l")
            return 9999999
        }
        return 10000000 + foo(step, value + step)
    }

    private fun testTryCatch() {
        try {
        } catch (e: RuntimeException) {
            Log.d(TAG, "RuntimeException")
            e.printStackTrace()
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "Throwable")
            e.printStackTrace()
            throw e
        } finally {
            Throwable().printStackTrace()
        }
    }
}
