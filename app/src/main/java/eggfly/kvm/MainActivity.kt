package eggfly.kvm

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*

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

        // Example of a call to a native method
        sample_text.text = stringFromJNI()
        KotlinTest.test()
        val result = KotlinTest.foo(-1, 5)
        Log.d(TAG, "" + result)
        TestJavaClass.foo()
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    private external fun stringFromJNI(): String

}
