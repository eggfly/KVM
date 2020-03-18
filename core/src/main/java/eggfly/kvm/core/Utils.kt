package eggfly.kvm.core

fun convertClassSignatureToClassName(classSignature: String): String {
    return classSignature.trimStart('L').trimEnd(';').replace('/', '.')
}