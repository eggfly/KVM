package kvm.core.util;

import java.util.Arrays;

public class JavaTypes {
    public static final Class<?> BOOLEAN_OBJECT = Boolean.class;
    public static final Class<?> BYTE_OBJECT = Byte.class;
    public static final Class<?> CHARACTER_OBJECT = Character.class;
    public static final Class<?> DOUBLE_OBJECT = Double.class;
    public static final Class<?> FLOAT_OBJECT = Float.class;
    public static final Class<?> INTEGER_OBJECT = Integer.class;
    public static final Class<?> LONG_OBJECT = Long.class;
    public static final Class<?> SHORT_OBJECT = Short.class;
    public static final Class<?> OBJECT = Object.class;

    public static Class[] WRAPPER_CLASSES = new Class<?>[]{
            BOOLEAN_OBJECT,
            BYTE_OBJECT,
            CHARACTER_OBJECT,
            DOUBLE_OBJECT,
            FLOAT_OBJECT,
            INTEGER_OBJECT,
            LONG_OBJECT,
            SHORT_OBJECT
    };

    public static boolean isWrapperClassInstance(Object obj) {
        if (obj == null) {
            return false;
        }
        return Arrays.asList(WRAPPER_CLASSES).contains(obj.getClass());
    }
}
