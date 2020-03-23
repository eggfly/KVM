package eggfly.kvm.core

fun loadBootClassBySignature(signature: String): Class<*> {
    val className: String?
    when (signature[0]) {
        'L' -> className = signature.trimStart('L').trimEnd(';').replace('/', '.')
        'Z' -> return Boolean::class.java
        'C' -> return Char::class.java
        'S' -> return Short::class.java
        'I' -> return Int::class.java
        'J' -> return Long::class.java
        'F' -> return Float::class.java
        'D' -> return Double::class.java
        'V' -> return Void::class.java // ?
        else -> throw NotImplementedError(signature)
    }
    return Thread.currentThread().contextClassLoader!!.loadClass(className)
}

fun classToSignature(clazz: Class<*>): String {
    val name = clazz.canonicalName!!
    return "L" + name.replace('.', '/') + ";"
}