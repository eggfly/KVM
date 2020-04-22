package kvm.demo;

import android.util.Log;

import java.util.HashMap;

public class TestJavaClass {
    private static final String TAG = "TestJavaClass";

    public void foo() {
        HashMap a = new HashMap();
        a.put("key", this);
        // int value = (int) a.get("key");
        Log.d(TAG, "foo()");
    }
}
