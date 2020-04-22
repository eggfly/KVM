package kvm.demo.util;

import android.util.Log;

public class JavaUtils {
    public static int[][][] test3() {
        return new int[5][2][4];
    }

    public static int[][][][] test4() {
        return new int[][][][]{};
    }

    public static String test5() {
        return "0" + 1 + "2";
    }

    public static Object[][] test6() {
        return new Object[1][0];
    }

    public static Object[][] test7() {
        return new Object[0][1];
    }

    public static void test8() {
        final String type = "[[[I";
        int count = type.lastIndexOf('[') + 1;
        String clazz = type.substring(count);
        Log.d("eggfly", clazz + "," + count);
    }
}
