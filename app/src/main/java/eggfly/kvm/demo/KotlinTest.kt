package eggfly.kvm.demo

import android.util.Log
import java.util.ArrayList
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
        val result = foo(-1, start)
        Log.d(TAG, result.toString())
        testMultiThread()
    }

    private fun testMultiThread() {
        val t1 = Thread {
            foo(-1, 7)
        }
        val t2 = Thread {
            foo(-1, 2)
        }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        Log.d(TAG, "t1 and t2 finished!")
    }

    fun testTime(): Long {
        val startTime = System.currentTimeMillis()
        test()
        val timeDelta = System.currentTimeMillis() - startTime
        Log.d("KVMAndroid", "It costs $timeDelta ms to run test() directly.")
        return timeDelta
    }

    data class TestClass(val test: Any?)

    open class BaseClass(private val test: Any?) {
        constructor() : this(1) {
            Log.d(TAG, "value=$test")
        }
    }

    class TestDerivedClass(val test2: Any) : BaseClass()

    @Suppress("EXPERIMENTAL_API_USAGE")
    data class TestSimpleClass(val value: UInt)

    @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS", "EXPERIMENTAL_API_USAGE")
    fun foo(
        step: Int,
        value: Long
    ): Long {
        // Log.d(TAG, "foo()")
        if (value == 0L) {
            val l = TestDerivedClass(12222222uL)
            Log.d(TAG, "finally return: $l, test2=" + l.test2)
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

    fun testInvokeVirtualRange(
        s: String,
        i: Int,
        l: Long,
        d: Double,
        arrayOf: Array<Int>,
        arrayListOf: ArrayList<Int>
    ) {
        Log.d(TAG, "$s, $i, $l, $d, $arrayOf, $arrayListOf")
    }
}
