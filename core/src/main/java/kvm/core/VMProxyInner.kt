package kvm.core

import kvm.core.KVMAndroid.invoke

object VMProxyInner {
    fun invoke(
        classOfMethod: String,
        methodName: String,
        argTypes: Array<Class<*>>?,
        isStatic: Boolean,
        thisObject: Any?,
        args: Array<Any?>
    ): Any? {
        val myArgs: Array<Any?>
        if (isStatic) {
            myArgs = args
        } else {
            myArgs = arrayOfNulls(args.size + 1)
            System.arraycopy(args, 0, myArgs, 1, args.size)
            myArgs[0] = thisObject
        }
        // TODO check performance here
        val parameterTypes = argTypes?.map {
            convertToSignature(it)
        }
        return invoke(classOfMethod, methodName, parameterTypes, !isStatic, myArgs)
    }

    fun convertToSignature(it: Class<*>): String {
        return when (it) {
            Boolean::class.java -> "Z"
            Byte::class.java -> "B"
            Char::class.java -> "C"
            Short::class.java -> "S"
            Int::class.java -> "I"
            Long::class.java -> "J"
            Float::class.java -> "F"
            Double::class.java -> "D"
            Void::class.java -> "V" // ?
            else -> {
                if (it.name[0] == '[') {
                    it.name.replace('.', '/')
                } else {
                    'L' + it.name.replace('.', '/') + ';'
                }
            }
        }
    }
}