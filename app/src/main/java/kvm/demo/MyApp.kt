package kvm.demo

import android.app.Application
import android.content.Context
import android.util.Log

@Suppress("unused")
class MyApp : Application() {
    companion object {
        private const val TAG = "MyApp"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Log.d(TAG, "attachBaseContext")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        leet117()
        // KVMAndroid.init(this)
        // KVMAndroid.setup()
    }
}
