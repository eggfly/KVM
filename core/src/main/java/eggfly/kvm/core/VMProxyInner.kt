package eggfly.kvm.core

import eggfly.kvm.core.KVMAndroid.invoke

object VMProxyInner {
    inline fun invoke(
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
        val parameterTypes = argTypes?.map { it.name }
        return invoke(classOfMethod, methodName, parameterTypes, !isStatic, myArgs)
    }
}