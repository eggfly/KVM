package eggfly.kvm.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.util.AttributeSet
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import eggfly.kvm.R
import eggfly.kvm.core.DecryptFile
import eggfly.kvm.core.KVMAndroid
import eggfly.kvm.core.classToSignature
import kotlinx.android.synthetic.main.activity_main.*
import quickpatch.sdk.ReflectionBridge
import java.io.File
import java.lang.StringBuilder

const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG2 = "MainActivity"

        // Used to load the 'native-lib' library on application startup.
        init {
            Log.d(TAG2, "init()")
            System.loadLibrary("native-lib")
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        ReflectionBridge.callSuperMethodNative(
            this,
            "onCreate",
            "(Landroid/os/Bundle;)V",
            arrayOf(savedInstanceState)
        )
        // super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TestJavaClass().foo()
        button.setOnClickListener {
            val aa = Class.forName("[[I")
            val stringArrayClass =
                Class.forName("[Ljava.lang.String;")

            KVMAndroid.invoke(
                classToSignature(MainActivity::class.java),
                "test",
                listOf(),
                true,
                arrayOf(this@MainActivity)
            )
            KVMAndroid.dumpUsedOpCodes()
            // test()
        }
        // Example of a call to a native method
        sample_text.text = stringFromJNI()
        KotlinTest.test()
        val result = KotlinTest.foo(-1, 5)
        Log.d(TAG, "" + result)
        TestJavaClass().foo()
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        Log.d(TAG, "onCreateOptionsMenu")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)
        Log.d(TAG, "onCreate")
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        Log.d(TAG, "onCreateView")
        return super.onCreateView(name, context, attrs)
    }

    private fun test() {
        val t1 = KotlinTest.testTime()
        val t2 = KVMAndroid.invokeTestMethodTime(
            KotlinTest,
            classToSignature(KotlinTest::class.java),
            "test"
        )
        arrayOf<Array<Array<Int>>>()
        intArrayOf(8)
        Array(5) { Array(2) { IntArray(4) } }
        arrayOfNulls<Activity>(9)
        arrayOfNulls<Array<Int>>(9)

        val floatValue = 100000000.123456F
        val doubleValue = 11111111.3456789
        val builder = StringBuilder()
        builder.appendln(floatValue)
        builder.appendln(doubleValue)
        Toast.makeText(this, "directly: $t1 ms\nmy interpreter: $t2 ms", Toast.LENGTH_LONG).show()
        Log.d(TAG, builder.toString())
        // println(Int::javaClass) // this cause kotlin compiler error  
        println(Int::class.java)
        KotlinTest.testInvokeVirtualRange("0", 1, 2L, 3.3, arrayOf(4), arrayListOf(5))
    }

    override fun onResume() {
        super.onResume()
        val encFile = File(filesDir, "encrypted.enc")
        DecryptFile.main(encFile, this)
        test()
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    private external fun stringFromJNI(): String

}
