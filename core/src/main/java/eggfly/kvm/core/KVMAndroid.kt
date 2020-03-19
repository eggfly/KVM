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
import org.jf.dexlib2.dexbacked.DexReader
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
        logV("init()")
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
        logV("invoke method: $method")
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
        // TODO: state设计比较丑陋且性能堪忧
        var state = InterpreterState(null, false, null, null)
        do {
            val instruction =
                state.jumpInstruction
                    ?: DexBackedInstruction.readFrom(reader) as DexBackedInstruction
            state =
                interpretInstruction(reader, instruction, registers, state.invokeTempReturnValue)
        } while (!state.returned)
        logV("returned")
        return state.returnValue
    }

    /**
     * 表示单条指令执行后的结果状态
     */
    data class InterpreterState(
        val jumpInstruction: DexBackedInstruction?,
        val returned: Boolean,
        val returnValue: Any?,
        val invokeTempReturnValue: Any?
    )

    private fun interpretInstruction(
        reader: DexReader,
        instruction: DexBackedInstruction,
        registers: Array<Any?>,
        lastInvokeTempReturnValue: Any?
    ): InterpreterState {
        var returnValue: Any? = null
        var invokeTempReturnValue: Any? = null
        var returned = false
        var jumpInstruction: DexBackedInstruction? = null
        logInstruction(instruction)
        when (instruction.opcode) {
            // 0x0b
            Opcode.MOVE_RESULT_WIDE -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = lastInvokeTempReturnValue
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
                logV("MOVE_RESULT_WIDE:$lastInvokeTempReturnValue")
            }
            // 0x0c
            Opcode.MOVE_RESULT_OBJECT -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = lastInvokeTempReturnValue
                logV("MOVE_RESULT_OBJECT:$lastInvokeTempReturnValue")
            }
            // 0x0e
            Opcode.RETURN_VOID -> {
                instruction as Instruction10x
                returned = true
                returnValue = null
                logV("RETURN_VOID: null")
            }
            // 0x10
            Opcode.RETURN_WIDE -> {
                val i = instruction as Instruction11x
                val value = registers[i.registerA] as Long
                returned = true
                returnValue = value
                logV("RETURN_WIDE: $value")
            }
            // 0x12
            Opcode.CONST_4 -> {
                // Why NarrowWideLiteralInstruction extends WideLiteralInstruction?
                val i = instruction as Instruction11n
                registers[i.registerA] = i.narrowLiteral
            }
            // 0x13
            Opcode.CONST_16 -> {
                // Why NarrowWideLiteralInstruction extends WideLiteralInstruction?
                val i = instruction as Instruction21s
                registers[i.registerA] = i.narrowLiteral
            }
            // 0x16
            Opcode.CONST_WIDE_16 -> {
                val i = instruction as Instruction21s
                // wide need long
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            // 0x18
            Opcode.CONST_WIDE -> {
                val i = instruction as Instruction51l
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            // 0x1a
            Opcode.CONST_STRING -> {
                val i = instruction as Instruction21c
                if (i.referenceType == ReferenceType.STRING) {
                    registers[i.registerA] = (i.reference as StringReference).string
                } else {
                    throw IllegalArgumentException("referenceType is not STRING")
                }
                logV("" + i)
            }
            // 0x31
            Opcode.CMP_LONG -> {
                val i = instruction as Instruction23x
                val value1 = registers[i.registerB] as Long
                val value2 = registers[i.registerC] as Long
                registers[i.registerA] = value1.compareTo(value2)
                logV("" + i)
            }
            // 0x39
            Opcode.IF_NEZ -> {
                val i = instruction as DexBackedInstruction21t
                val value = registers[i.registerA] as Int
                if (value != 0) {
                    logV("" + i.codeOffset)
                    reader.offset = i.instructionStart + i.codeOffset * 2
                    jumpInstruction = DexBackedInstruction.readFrom(reader) as DexBackedInstruction
                    logV("" + jumpInstruction)
                }
            }
            // 0x6e
            Opcode.INVOKE_VIRTUAL -> {
                invokeTempReturnValue = handleInstruction35c(instruction, registers, true)
                logV("INVOKE_VIRTUAL: $returnValue")
            }
            // 0x71
            Opcode.INVOKE_STATIC -> {
                invokeTempReturnValue = handleInstruction35c(instruction, registers, false)
                logV("INVOKE_STATIC: $returnValue")
            }
            // 0x81
            Opcode.INT_TO_LONG -> {
                val i = instruction as Instruction12x
                val value = registers[i.registerB] as Int
                registers[i.registerA] = value.toLong()
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            // 0xbb
            Opcode.ADD_LONG_2ADDR -> {
                val i = instruction as Instruction12x
                val srcValue = registers[i.registerB] as Long
                val targetValue = registers[i.registerA] as Long + srcValue
                registers[i.registerA] = targetValue
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            else -> {
                val msg = instructionToString(instruction) + " not supported yet"
                logE(msg)
                throw NotImplementedError(msg)
            }
        }
        return InterpreterState(
            jumpInstruction,
            returned,
            returnValue,
            invokeTempReturnValue
        )
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
                val libraryClass = loadBootClassBySignature(methodRef.definingClass)
                // TODO: 参数遇到非boot class需要处理
                val parameterTypes = convertToTypes(methodRef.parameterTypes)
                val method = libraryClass.getDeclaredMethod(methodRef.name, *parameterTypes)
                logV("" + method)
                val invokeParams = parametersFromRegister(i, registers)
                val realParams = remove64BitPlaceHolders(invokeParams)
                // static or virtual?
                // Start to invoke, 山口山~!
                return method.invoke(null, *realParams)
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

    private fun remove64BitPlaceHolders(invokeParams: Array<Any?>) =
        invokeParams.filter { it != SecondSlotPlaceHolderOf64BitValue }.toTypedArray()

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
            loadBootClassBySignature(type.toString())
        }.toTypedArray()
    }

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
                    logV("Method: $method")
                    val methodImpl = method.implementation
                    if (methodImpl == null) {
                        logV("$method has no implementation")
                    } else {
                        methodImpl.instructions.forEach { instruction ->
                            logInstruction(instruction as DexBackedInstruction)
                            handleInstruction(instruction)
                        }
                    }
                }
                val size = classDef.size
                logV("class:$classDef size:$size")
            }
            logV("" + entry)
        }
        main()
    }

    private fun logInstruction(instruction: DexBackedInstruction) {
        logV(instructionToString(instruction))
    }

    private fun instructionToString(instruction: DexBackedInstruction) =
        "${instruction.instructionStart}: opcode=${instruction.opcode}, ${instruction.javaClass.simpleName}"

    private fun handleInstruction(instruction: Instruction) {
        val opcode = instruction.opcode
        when (opcode) {
            Opcode.CONST_STRING -> {
                val i = instruction as Instruction21c
                logV("${i.registerA}, ${i.reference}, ${i.referenceType}")
            }
            Opcode.INVOKE_VIRTUAL -> {
                val i = instruction as Instruction35c
                logV("${i.registerCount}, ${i.reference}, ${i.referenceType}")
            }
            else -> {
            }
        }
        logV("$opcode")
    }

    private fun logV(msg: String) {
        Log.v(TAG, msg)
    }

//    private fun dumpInstruction(instruction: Instruction?): String {
//        instruction.runCatching { }
//    }
}
