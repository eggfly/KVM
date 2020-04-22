package kvm.core.util;

import android.annotation.SuppressLint;
import android.app.Application;

public class AppContext {
    @SuppressLint("PrivateApi")
    public static Application getApplicationUsingAppGlobals() throws Exception {
        return (Application) Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication").invoke(null, (Object[]) null);
    }

    @SuppressLint("PrivateApi")
    public static Application getApplicationUsingActivityThread() throws Exception {
        return (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null, (Object[]) null);
    }
}
