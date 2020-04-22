package kvm.core.util;

import java.lang.reflect.Array;

public class JavaUtils {
    private static final String TAG = "JavaUtils";

    public static void arraySet(Object array, int index, Object value) {
        if (array == null) {
            throw new NullPointerException("array value is null");
        }
        Class<?> component = array.getClass().getComponentType();
        if (component == null) {
            throw new IllegalArgumentException("component type is null, maybe not an array?");
        }
        if (component == boolean.class) {
            Array.setBoolean(array, index, (Boolean) value);
        } else if (component == byte.class) {
            Array.setByte(array, index, (Byte) value);
        } else if (component == char.class) {
            Array.setChar(array, index, (Character) value);
        } else if (component == double.class) {
            Array.setDouble(array, index, (Double) value);
        } else if (component == float.class) {
            Array.setFloat(array, index, (Float) value);
        } else if (component == int.class) {
            Array.setInt(array, index, (Integer) value);
        } else if (component == long.class) {
            Array.setLong(array, index, (Long) value);
        } else if (component == short.class) {
            Array.setShort(array, index, (Short) value);
        } else {
            Array.set(array, index, value);
        }
    }

    public static Object arrayGet(Object array, int index) {
        if (array == null) {
            throw new NullPointerException("array value is null");
        }
        Class<?> component = array.getClass().getComponentType();
        if (component == null) {
            throw new IllegalArgumentException("component type is null, maybe not an array?");
        }
        if (component == boolean.class) {
            return Array.getBoolean(array, index);
        } else if (component == byte.class) {
            return Array.getByte(array, index);
        } else if (component == char.class) {
            return Array.getChar(array, index);
        } else if (component == double.class) {
            return Array.getDouble(array, index);
        } else if (component == float.class) {
            return Array.getFloat(array, index);
        } else if (component == int.class) {
            return Array.getInt(array, index);
        } else if (component == long.class) {
            return Array.getLong(array, index);
        } else if (component == short.class) {
            return Array.getShort(array, index);
        } else {
            return Array.get(array, index);
        }
    }

}
