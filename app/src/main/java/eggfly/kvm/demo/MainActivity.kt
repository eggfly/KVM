package eggfly.kvm.demo

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.PersistableBundle
import android.util.AttributeSet
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.Toast
import eggfly.kvm.R
import eggfly.kvm.core.DecryptFile
import eggfly.kvm.core.KVMAndroid
import eggfly.kvm.core.classToSignature
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TestJavaClass().foo()
        button.setOnClickListener {
            KVMAndroid.invoke(
                classToSignature(MainActivity::class.java),
                "test",
                listOf(),
                true,
                arrayOf(this@MainActivity)
            )
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
        val t2 = KVMAndroid.invokeTestMethodTime(KotlinTest, classToSignature(KotlinTest::class.java), "test")
        Toast.makeText(this, "directly: $t1 ms\nmy interpreter: $t2 ms", Toast.LENGTH_LONG).show()
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
