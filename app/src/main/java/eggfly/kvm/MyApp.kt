package eggfly.kvm

import android.app.Application
import android.content.Context
import android.util.Log
import eggfly.kvm.core.KVMAndroid

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
        KVMAndroid.init(this)
        // KVMAndroid.setup()
        KVMAndroid.invokeTestMethod()
    }
}
