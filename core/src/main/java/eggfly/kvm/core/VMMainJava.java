package eggfly.kvm.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class VMMainJava {
    public static void main(String[] args) {

    }

    public static String getApkName(Context context) {
        String packageName = context.getPackageName();
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return ai.publicSourceDir;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
