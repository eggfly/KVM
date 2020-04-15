package eggfly.kvm.core;

public class VMProxy {
    public static Object invoke() {
        return null;
    }


    public static Object invoke(String classOfMethod, String methodName) {
        return null;
    }

    public static Object invoke(String classOfMethod, String methodName, Class[] argTypes,
                                boolean isStatic, Object thisObject, Object[] args) {
        return null;
    }

    public static native Object nativeInvoke();

}
