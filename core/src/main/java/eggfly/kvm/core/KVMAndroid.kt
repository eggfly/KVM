package eggfly.kvm.core

import android.content.Context
import android.util.Log
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.ReferenceType
import org.jf.dexlib2.dexbacked.DexBackedClassDef
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.dexbacked.DexBackedMethod
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction21t
import org.jf.dexlib2.iface.MultiDexContainer
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.*
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import java.io.File
import java.util.*

object KVMAndroid {
    class Frame
    class StackFrame : Stack<Frame>()

    private const val TAG = "KVMAndroid"
    private lateinit var apk: MultiDexContainer<out DexBackedDexFile>
    private lateinit var dexNames: MutableList<String>
    private lateinit var dexClasses: List<DexBackedClassDef>
    private val threadLocalStackFrame = object : ThreadLocal<StackFrame>() {
        override fun initialValue(): StackFrame {
            return StackFrame()
        }
    }

    init {
        logW("init()")
    }

    fun init(context: Context) {
        val apkPath = context.packageCodePath
        apk = DexFileFactory.loadDexContainer(File(apkPath), Opcodes.getDefault())
        dexNames = apk.dexEntryNames
        dexClasses = dexNames.map { dex ->
            val entry = apk.getEntry(dex)
            entry!!.classes
        }.flatten()
    }

    private fun getStackFrame(): StackFrame {
        return threadLocalStackFrame.get()!!
    }

    fun invokeTestMethod() {
        val kotlinTestClass = dexClasses.first {
            @Suppress("SpellCheckingInspection")
            it.type == "Leggfly/kvm/KotlinTest;"
        }
        val testMethod = kotlinTestClass.methods.first {
            it.parameterNames.iterator()
//                it.name == "foo" && it.parameters.size == 2 && it.parameterTypes[0] == "I" && it.parameterTypes[1] == "J"
//                it.name == "foo2"
            it.name == "test"
        }
        val newFrame = Frame()
        getStackFrame().push(newFrame)
        val mockParams = arrayOf<Any?>(this)
        invokeMethod(testMethod, true, mockParams)
    }

    private fun calculate32BitCount(parameterTypes: MutableList<String>): Int {
        // long or double need 64 bits
        return parameterTypes.sumBy { if ("D" == it || "J" == it) 2 else 1 }
    }

    /**
     * 一个取巧的方法，当遇到64位值的时候，用来给第二个寄存器槽做占位，是个单例
     */
    object SecondSlotPlaceHolderOf64BitValue

    private fun invokeMethod(
        method: DexBackedMethod,
        needThisObj: Boolean,
        parameters: Array<Any?>
    ): Any? {
        logW("invoke method: $method")
        val impl = method.implementation!!
        val parameterCountBy32BitsWithoutThisObj = calculate32BitCount(method.parameterTypes)
        val parameterCount =
            if (needThisObj) parameterCountBy32BitsWithoutThisObj + 1 else parameterCountBy32BitsWithoutThisObj
        if (parameters.size != parameterCount) {
            throw InternalError("args count not match, declare count=$parameterCount, actual count=${parameters.size}")
        }
        val registers = arrayOfNulls<Any>(impl.registerCount)
        parameters.forEachIndexed { index, parameter ->
            registers[impl.registerCount - parameterCount + index] = parameter
        }
        val firstInstruction: DexBackedInstruction =
            impl.instructions.first() as DexBackedInstruction
        // 里面每次都是new DexReader, 可能可以优化
        val reader = method.dexFile.readerAt(firstInstruction.instructionStart)
        do {
            val instruction = DexBackedInstruction.readFrom(reader) as DexBackedInstruction
            val end: Boolean = handleOneInstruction(instruction, registers, method)
            if (end) {
                logW("end")
                break
            }
        } while (true)
        // TODO
        return null
    }

    private fun handleOneInstruction(
        instruction: DexBackedInstruction,
        registers: Array<Any?>,
        method: DexBackedMethod
    ): Boolean {
        var returnValue: Any? = null
        var end = false
        logInstruction(instruction)
        when (instruction.opcode) {
            Opcode.CONST_4 -> {
                // Why NarrowWideLiteralInstruction extends WideLiteralInstruction?
                val i = instruction as Instruction11n
                registers[i.registerA] = i.narrowLiteral
            }
            Opcode.CONST_16 -> {
                // Why NarrowWideLiteralInstruction extends WideLiteralInstruction?
                val i = instruction as Instruction21s
                registers[i.registerA] = i.narrowLiteral
            }
            Opcode.CONST_WIDE -> {
                val i = instruction as Instruction51l
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            Opcode.CONST_WIDE_16 -> {
                val i = instruction as Instruction21s
                // wide need long
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            Opcode.CONST_STRING -> {
                val i = instruction as Instruction21c
                if (i.referenceType == ReferenceType.STRING) {
                    registers[i.registerA] = (i.reference as StringReference).string
                } else {
                    throw IllegalArgumentException("referenceType is not STRING")
                }
                logW("" + i)
            }
            Opcode.INVOKE_STATIC -> {
                returnValue = handleInstruction35c(instruction, registers, false)
                logW("INVOKE_STATIC: $returnValue")
            }
            Opcode.INVOKE_VIRTUAL -> {
                returnValue = handleInstruction35c(instruction, registers, true)
                logW("INVOKE_VIRTUAL: $returnValue")
            }
            Opcode.CMP_LONG -> {
                val i = instruction as Instruction23x
                val value1 = registers[i.registerB] as Long
                val value2 = registers[i.registerC] as Long
                registers[i.registerA] = value1.compareTo(value2)
                logW("" + i)
            }
            Opcode.IF_NEZ -> {
                val i = instruction as DexBackedInstruction21t
                val value = registers[i.registerA] as Int
                if (value != 0) {
                    val newInstructionOffset = i.instructionStart + i.codeOffset * 2
                    logW("" + i.codeOffset)
                    val reader = method.dexFile.readerAt(newInstructionOffset);
                    val nextInstruction = DexBackedInstruction.readFrom(reader);
                    logW("" + nextInstruction)
                }
            }
            Opcode.MOVE_RESULT_WIDE -> {
                val i = instruction as Instruction11x
                val value = registers[i.registerA] as Long
                // TODO
                logW("" + i)
            }
            Opcode.RETURN_WIDE -> {
                val i = instruction as Instruction11x
                val value = registers[i.registerA] as Long
                // TODO
                logW("" + i)
                // break
            }
            else -> {
                val msg = "" + instruction + "not supported yet"
                logE(msg)
                throw NotImplementedError(msg)
            }
        }
        return end
    }

    private fun logE(msg: String) {
        Log.e(TAG, msg)
    }

    //@Suppress("NOTHING_TO_INLINE")
    private fun handleInstruction35c(
        instruction: Instruction,
        registers: Array<Any?>,
        needThisObj: Boolean
    ): Any? {
        val i = instruction as Instruction35c
        if (i.referenceType == ReferenceType.METHOD) {
            val methodRef = i.reference as MethodReference
            val classInDex =
                dexClasses.firstOrNull { it.type == methodRef.definingClass }
            if (classInDex == null) {
                // It's a library class, not an application class
                val libraryClass = loadClassBySignature(methodRef.definingClass)
                val parameterTypes = convertToTypes(methodRef.parameterTypes)
                val method = libraryClass.getDeclaredMethod(methodRef.name, *parameterTypes)
                logW("" + method)
                val invokeParams = parametersFromRegister(i, registers)
                // static or virtual?
                // Start to invoke, 山口山~!
                return method.invoke(null, *invokeParams)
            } else {
                val method = classInDex.methods.firstOrNull {
                    it.name == methodRef.name && it.parameterTypes == methodRef.parameterTypes
                }
                if (method == null) {
                    throw NoSuchMethodError("found class but cannot find method: $method")
                } else {
                    val invokeParams = parametersFromRegister(i, registers)
                    return invokeMethod(method, needThisObj, invokeParams)
                }
            }
        } else {
            throw IllegalArgumentException("referenceType is not METHOD")
        }
    }

    private fun parametersFromRegister(
        i: Instruction35c,
        registers: Array<Any?>
    ): Array<Any?> {
        val invokeParams = arrayOfNulls<Any?>(i.registerCount)
        if (i.registerCount > 0) {
            invokeParams[0] = registers[i.registerC]
        }
        if (i.registerCount > 1) {
            invokeParams[1] = registers[i.registerD]
        }
        if (i.registerCount > 2) {
            invokeParams[2] = registers[i.registerE]
        }
        if (i.registerCount > 3) {
            invokeParams[3] = registers[i.registerF]
        }
        if (i.registerCount > 4) {
            invokeParams[4] = registers[i.registerG]
        }
        return invokeParams
    }

    private fun convertToTypes(parameterTypes: List<CharSequence>): Array<Class<*>?> {
        return parameterTypes.map { type ->
            loadClassBySignature(type.toString())
        }.toTypedArray()
    }

    private fun loadClassBySignature(classSignature: String) =
        Thread.currentThread()
            .contextClassLoader!!.loadClass(convertClassSignatureToClassName(classSignature))

    fun setup() {
        val names = apk.dexEntryNames
        names.forEach { dex ->
            val entry = apk.getEntry(dex)
            val dexClasses = entry!!.classes
            val myClasses = dexClasses.filter { it.startsWith("Leggfly") }
            val dexMethods = entry.methods
            val dexFields = entry.fields
            myClasses.forEach { classDef ->
                val classFields = classDef.fields
                val classMethods = classDef.methods
                classMethods.forEach { method ->
                    logW("Method: $method")
                    val methodImpl = method.implementation
                    if (methodImpl == null) {
                        logW("$method has no implementation")
                    } else {
                        methodImpl.instructions.forEach { instruction ->
                            logInstruction(instruction)
                            handleInstruction(instruction)
                        }
                    }
                }
                val size = classDef.size
                logW("class:$classDef size:$size")
            }
            logW("" + entry)
        }
        main()
    }

    private fun logInstruction(instruction: Instruction) {
        logW(
            "${instruction.javaClass.simpleName}, opcode: ${instruction.opcode}, codeUnits: ${instruction.codeUnits}"
        )
    }

    private fun handleInstruction(instruction: Instruction) {
        val opcode = instruction.opcode
        when (opcode) {
            Opcode.CONST_STRING -> {
                val i = instruction as Instruction21c
                logW("${i.registerA}, ${i.reference}, ${i.referenceType}")
            }
            Opcode.INVOKE_VIRTUAL -> {
                val i = instruction as Instruction35c
                logW("${i.registerCount}, ${i.reference}, ${i.referenceType}")
            }
            else -> {
            }
        }
        logW("$opcode")
    }

    private fun logW(msg: String) {
        Log.w(TAG, msg)
    }

//    private fun dumpInstruction(instruction: Instruction?): String {
//        instruction.runCatching { }
//    }
}
