package kvm.core;

@SuppressWarnings("unused")
public class VMProxy {
    public static Object invoke() {
        return null;
    }

    public static Object invoke(String classOfMethod, String methodName) {
        return null;
    }

    public static Object invoke(String classOfMethod, String methodName, Class[] argTypes,
                                boolean isStatic, Object thisObject, Object[] args) {
        return VMProxyInner.INSTANCE.invoke(classOfMethod, methodName, argTypes, isStatic, thisObject, args);
    }

    public static native Object nativeInvoke();
}
