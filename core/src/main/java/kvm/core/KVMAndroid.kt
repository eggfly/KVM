package kvm.core

import android.util.Log
import com.google.common.primitives.Primitives
import kvm.core.util.AppContext
import kvm.core.util.AssetsUtils
import kvm.core.util.JavaTypes
import kvm.core.util.JavaUtils
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.ReferenceType
import org.jf.dexlib2.dexbacked.DexBackedClassDef
import org.jf.dexlib2.dexbacked.DexBackedMethod
import org.jf.dexlib2.dexbacked.DexReader
import org.jf.dexlib2.dexbacked.instruction.*
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.instruction.formats.*
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import quickpatch.sdk.ReflectionBridge
import java.io.File
import java.lang.StringBuilder
import java.lang.reflect.Method
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

private val Any?.representation: String
    get() {
        val it = this
        return when {
            it == null -> "null"
            it::class.java.isPrimitive ->
                it.toString()
            Primitives.isWrapperType(it::class.java) ->
                it.toString()
            it::class.java.declaringClass === KVMAndroid::class.java ->
                it.toString()
            it is String ->
                "\"$it\""
            else -> "${it::class.java.name}@${System.identityHashCode(it)}"
        }
    }

object KVMAndroid {
    class Frame
    class StackFrame : Stack<Frame>()

    private val usedOpCodes = LinkedHashSet<Opcode>()

    fun dumpUsedOpCodes() {
        val used = usedOpCodes
        Log.w(TAG, "used opcodes: count=${used.size}, all:\n${used.joinToString("\n")}")
    }

    private const val TAG = "kvm"
    private const val DEBUG = true

    private lateinit var dexClassesMap: Map<String, DexBackedClassDef>
    private val threadLocalStackFrame = object : ThreadLocal<StackFrame>() {
        override fun initialValue(): StackFrame {
            return StackFrame()
        }
    }

    @Volatile
    private var initialized = false

    init {
        logV("init()")
        tryInit()
    }

    @Synchronized
    private fun tryInit() {
        if (initialized) {
            return
        }
        val context = AppContext.getApplicationUsingActivityThread()
        if (context != null) {
            val dex = File(context.filesDir, "code.dex")
            if (AssetsUtils.exists(context, "code.dex")) {
                AssetsUtils.copyAssets(context, "code.dex", dex)
                val dexFile = DexFileFactory.loadDexFile(dex, Opcodes.getDefault())
                dexClassesMap = dexFile.classes.map {
                    it.type to it
                }.toMap()
                Log.i(TAG, "KVM initialized in real world")
            } else {
                val apkPath = context.packageCodePath
                val apk = DexFileFactory.loadDexContainer(File(apkPath), Opcodes.getDefault())
                val dexNames = apk.dexEntryNames
                val dexClasses = dexNames.map { dexName ->
                    val entry = apk.getEntry(dexName)
                    entry!!.classes
                }.flatten()
                dexClassesMap = dexClasses.map { it.type to it }.toMap()
                Log.i(TAG, "KVM initialized in simulation mode")
            }
            Log.i(TAG, "KVM initialized, class count: ${dexClassesMap.size}")
            initialized = true
        }
    }

    private fun getStackFrame(): StackFrame {
        return threadLocalStackFrame.get()!!
    }

    private fun invokeTestMethod(thisObj: Any?, className: String, methodName: String) {
        @Suppress("SpellCheckingInspection")
        val kotlinTestClass = findDexClassDef(className)
        val testMethod = kotlinTestClass!!.methods.first {
            it.parameterNames.iterator()
//                it.name == "foo" && it.parameters.size == 2 && it.parameterTypes[0] == "I" && it.parameterTypes[1] == "J"
//                it.name == "foo2"
            it.name == methodName
        }
        val newFrame = Frame()
        getStackFrame().push(newFrame)
        val mockParams = arrayOf(thisObj)
        invokeMethod(testMethod, true, mockParams)
    }

    private fun calculate32BitCount(parameterTypes: MutableList<String>): Int {
        // long or double need 64 bits
        return parameterTypes.sumBy { if ("D" == it || "J" == it) 2 else 1 }
    }

    /**
     * 一个取巧的方法，当遇到64位值的时候，用来给第二个寄存器槽做占位，是个单例
     */
    object WideValuePlaceHolder {
        override fun toString(): String {
            return "Wide2ndSlot"
        }
    }

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
        val finalParameters = addSecondSlotPlaceHolders(parameters)
        if (finalParameters.size != parameterCount) {
            throw InternalError("args count not match, declare count=$parameterCount, actual count=${finalParameters.size}")
        }
        val registers = arrayOfNulls<Any>(impl.registerCount)
        finalParameters.forEachIndexed { index, parameter ->
            registers[impl.registerCount - parameterCount + index] = parameter
        }
        val firstInstruction: DexBackedInstruction =
            impl.instructions.first() as DexBackedInstruction
        // 里面每次都是new DexReader, 可能可以优化
        val reader = method.dexFile.readerAt(firstInstruction.instructionStart)
        val state = InterpreterState(null, false, null, null, method, firstInstruction)
        do {
            val instruction =
                state.jumpInstruction
                    ?: DexBackedInstruction.readFrom(reader) as DexBackedInstruction
            // clear
            state.jumpInstruction = null
            interpretInstruction(reader, instruction, registers, state)
        } while (!state.returned)
        logV("returned with value: ${state.returnValue}")
        return state.returnValue
    }

    private fun addSecondSlotPlaceHolders(parameters: Array<Any?>): Array<Any?> {
        val result = ArrayList<Any?>()
        parameters.forEach {
            result.add(it)
            if (it is Long || it is Double) {
                result.add(WideValuePlaceHolder)
            }
        }
        return result.toArray()
    }

    /**
     * 表示单条指令执行后的结果状态
     */
    data class InterpreterState(
        var jumpInstruction: DexBackedInstruction?,
        var returned: Boolean,
        var returnValue: Any?,
        var tempResultObject: Any?,
        var methodForDebug: DexBackedMethod,
        var firstInstruction: DexBackedInstruction
    )

    object VoidReturnValue {
        override fun toString(): String {
            return "VoidReturnValue"
        }
    }

    @Suppress("SpellCheckingInspection")
    private fun interpretInstruction(
        reader: DexReader,
        instruction: DexBackedInstruction,
        registers: Array<Any?>,
        interpreterState: InterpreterState
    ) {
        if (DEBUG) {
            logRegisters(registers)
            logInstruction(registers, instruction, interpreterState)
        }
        when (instruction.opcode) {
            // 0x00
            Opcode.NOP -> {
                instruction as Instruction10x
            }
            // 0x01
            Opcode.MOVE -> {
                val i = instruction as Instruction12x
                registers[i.registerA] = registers[i.registerB]
            }
            // 0x07
            Opcode.MOVE_OBJECT -> {
                val i = instruction as Instruction12x
                val srcValue = registers[i.registerB]
                registers[i.registerA] = srcValue
            }
            // 0x08
            Opcode.MOVE_OBJECT_FROM16 -> {
                val i = instruction as Instruction22x
                val srcValue = registers[i.registerB]
                registers[i.registerA] = srcValue
            }
            // 0x09
            Opcode.MOVE_OBJECT_16 -> {
                val i = instruction as Instruction32x
                val srcValue = registers[i.registerB]
                registers[i.registerA] = srcValue
            }
            // 0x0a
            Opcode.MOVE_RESULT -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = interpreterState.tempResultObject
                logV("MOVE_RESULT: ${interpreterState.tempResultObject}")
            }
            // 0x0b
            Opcode.MOVE_RESULT_WIDE -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = interpreterState.tempResultObject
                registers[i.registerA + 1] = WideValuePlaceHolder
                logV("MOVE_RESULT_WIDE: ${interpreterState.tempResultObject}")
            }
            // 0x0c
            Opcode.MOVE_RESULT_OBJECT -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = interpreterState.tempResultObject
                logV("MOVE_RESULT_OBJECT: ${interpreterState.tempResultObject}")
            }
            // 0x0e
            Opcode.RETURN_VOID -> {
                instruction as Instruction10x
                interpreterState.returned = true
                interpreterState.returnValue = VoidReturnValue
                logV("RETURN_VOID: null")
            }
            // 0x0f
            Opcode.RETURN -> {
                val i = instruction as Instruction11x
                val value = registers[i.registerA]
                interpreterState.returned = true
                interpreterState.returnValue = value
                logV("RETURN: $value")
            }
            // 0x10
            Opcode.RETURN_WIDE -> {
                val i = instruction as Instruction11x
                // TODO: double ?
                val value = registers[i.registerA]
                interpreterState.returned = true
                interpreterState.returnValue = value
                logV("RETURN_WIDE: $value")
            }
            // 0x11
            Opcode.RETURN_OBJECT -> {
                val i = instruction as Instruction11x
                val value = registers[i.registerA]
                interpreterState.returned = true
                interpreterState.returnValue = value
                logV("RETURN_OBJECT: $value")
            }
            // 0x12
            Opcode.CONST_4 -> {
                // Why NarrowWideLiteralInstruction extends WideLiteralInstruction?
                val i = instruction as Instruction11n
                registers[i.registerA] = i.narrowLiteral
            }
            // 0x13
            Opcode.CONST_16 -> {
                val i = instruction as Instruction21s
                registers[i.registerA] = i.narrowLiteral
            }
            // 0x14
            Opcode.CONST -> {
                val i = instruction as Instruction31i
                registers[i.registerA] = i.narrowLiteral
            }
            // 0x15
            Opcode.CONST_HIGH16 -> {
                val i = instruction as Instruction21ih
                registers[i.registerA] = i.narrowLiteral
            }
            // 0x16
            Opcode.CONST_WIDE_16 -> {
                val i = instruction as Instruction21s
                // wide need long
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = WideValuePlaceHolder
            }
            // 0x17
            Opcode.CONST_WIDE_32 -> {
                val i = instruction as Instruction31i
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = WideValuePlaceHolder
            }
            // 0x18
            Opcode.CONST_WIDE -> {
                val i = instruction as Instruction51l
                registers[i.registerA] = i.wideLiteral
                registers[i.registerA + 1] = WideValuePlaceHolder
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
            // 0x1c
            Opcode.CONST_CLASS -> {
                val i = instruction as Instruction21c
                if (i.referenceType == ReferenceType.TYPE) {
                    val type = (i.reference as TypeReference).type
                    val clazz = loadClassBySignatureUsingClassLoader(type)
                    registers[i.registerA] = clazz
                } else {
                    throw IllegalArgumentException("referenceType is not TYPE")
                }
            }
            // 0x1f
            Opcode.CHECK_CAST -> {
                val i = instruction as Instruction21c
                if (i.referenceType == ReferenceType.TYPE) {
                    val type = (i.reference as TypeReference).type
                    val value = registers[i.registerA]
                    val clazz = loadClassBySignatureUsingClassLoader(type)
                    if (Enum::class.java.isAssignableFrom(clazz)) {
                        logV("enum: $value")
                    } else {
                        clazz.cast(value)
                    }
                } else {
                    throw IllegalArgumentException("referenceType is not TYPE")
                }
            }
            // 0x21
            Opcode.ARRAY_LENGTH -> {
                val i = instruction as Instruction12x
                // val arr = registers[i.registerB] as Array<*> // don't use this
                val arr = registers[i.registerB]
                registers[i.registerA] = java.lang.reflect.Array.getLength(arr!!)
            }
            // 0x22
            Opcode.NEW_INSTANCE -> {
                val i = instruction as Instruction21c
                // 早期胚胎对象
                val embryoInstance = handleNewInstanceInstruction(i)
                registers[i.registerA] = embryoInstance
            }
            // 0x23
            Opcode.NEW_ARRAY -> {
                val i = instruction as Instruction22c
                val arrayLength = registers[i.registerB] as Int
                val newArrayObj = handleNewArray(i, arrayLength)
                registers[i.registerA] = newArrayObj
            }
            // 0x24
            Opcode.FILLED_NEW_ARRAY -> {
                val i = instruction as Instruction35c
                val newArrayObj = handleNewArray(i, i.registerCount)
                fillNewArray(i, newArrayObj, registers)
                interpreterState.tempResultObject = newArrayObj
            }
            // 0x28
            Opcode.GOTO -> {
                val i = instruction as DexBackedInstruction10t
                reader.offset = i.instructionStart + i.codeOffset * 2
                interpreterState.jumpInstruction =
                    DexBackedInstruction.readFrom(reader) as DexBackedInstruction
            }
            // 0x31
            Opcode.CMP_LONG -> {
                val i = instruction as Instruction23x
                val value1 = registers[i.registerB] as Long
                val value2 = registers[i.registerC] as Long
                registers[i.registerA] = value1.compareTo(value2)
                logV("" + i)
            }
            // 0x32 - 0x37
            Opcode.IF_EQ,
            Opcode.IF_NE,
            Opcode.IF_LT,
            Opcode.IF_GE,
            Opcode.IF_GT,
            Opcode.IF_LE -> {
                handleCompare(instruction, registers, reader, interpreterState)
            }
            // 0x38 - 0x3d
            Opcode.IF_EQZ,
            Opcode.IF_NEZ,
            Opcode.IF_LTZ,
            Opcode.IF_GEZ,
            Opcode.IF_GTZ,
            Opcode.IF_LEZ -> {
                handleZeroCompare(instruction, registers, reader, interpreterState)
            }
            // 0x44 - 0x4a
            Opcode.AGET,
            Opcode.AGET_WIDE,
            Opcode.AGET_OBJECT,
            Opcode.AGET_BOOLEAN,
            Opcode.AGET_BYTE,
            Opcode.AGET_CHAR,
            Opcode.AGET_SHORT -> {
                val i = instruction as DexBackedInstruction23x
                val array = registers[i.registerB]
                val index = registers[i.registerC] as Int
                registers[i.registerA] = JavaUtils.arrayGet(array, index)
                if (i.opcode == Opcode.AGET_WIDE) {
                    registers[i.registerA + 1] = WideValuePlaceHolder
                }
            }
            // 0x4b - 0x51
            Opcode.APUT,
            Opcode.APUT_WIDE,
            Opcode.APUT_OBJECT,
            Opcode.APUT_BOOLEAN,
            Opcode.APUT_BYTE,
            Opcode.APUT_CHAR,
            Opcode.APUT_SHORT -> {
                val i = instruction as DexBackedInstruction23x
                val value = registers[i.registerA]
                val array = registers[i.registerB]
                val index = registers[i.registerC] as Int
                JavaUtils.arraySet(array, index, value)
            }
            // 0x52
            Opcode.IGET -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x53
            Opcode.IGET_WIDE -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x54
            Opcode.IGET_OBJECT -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x55
            Opcode.IGET_BOOLEAN -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x56
            Opcode.IGET_BYTE -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x57
            Opcode.IGET_CHAR -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x58
            Opcode.IGET_SHORT -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, null)
            }
            // 0x59 - 0x5f
            Opcode.IPUT,
            Opcode.IPUT_WIDE,
            Opcode.IPUT_OBJECT,
            Opcode.IPUT_BOOLEAN,
            Opcode.IPUT_BYTE,
            Opcode.IPUT_CHAR,
            Opcode.IPUT_SHORT -> {
                val i = instruction as DexBackedInstruction22c
                setInstanceFieldWithCheck(registers, i, null)
            }
            // 0x60
            Opcode.SGET -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, JavaTypes.INTEGER_OBJECT)
            }
            // 0x61
            Opcode.SGET_WIDE -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, null)
            }
            // 0x62
            Opcode.SGET_OBJECT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, JavaTypes.OBJECT)
            }
            // 0x63
            Opcode.SGET_BOOLEAN -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, JavaTypes.BOOLEAN_OBJECT)
            }
            // 0x64
            Opcode.SGET_BYTE -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, JavaTypes.BYTE_OBJECT)
            }
            // 0x65
            Opcode.SGET_CHAR -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, JavaTypes.CHARACTER_OBJECT)
            }
            // 0x66
            Opcode.SGET_SHORT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, JavaTypes.SHORT_OBJECT)
            }
            // 0x67
            Opcode.SPUT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, JavaTypes.INTEGER_OBJECT)
            }
            // 0x68
            Opcode.SPUT_WIDE -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, null)
            }
            // 0x69
            Opcode.SPUT_OBJECT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, JavaTypes.OBJECT)
            }
            // 0x6a
            Opcode.SPUT_BOOLEAN -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, JavaTypes.BOOLEAN_OBJECT)
            }
            // 0x6b
            Opcode.SPUT_BYTE -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, JavaTypes.BYTE_OBJECT)
            }
            // 0x6c
            Opcode.SPUT_CHAR -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, JavaTypes.CHARACTER_OBJECT)
            }
            // 0x6d
            Opcode.SPUT_SHORT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, JavaTypes.SHORT_OBJECT)
            }
            // 0x6e
            Opcode.INVOKE_VIRTUAL -> {
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, true)
            }
            // 0x6f
            Opcode.INVOKE_SUPER -> {
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, true)
            }
            // 0x70
            Opcode.INVOKE_DIRECT -> {
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, true)
            }
            // 0x71
            Opcode.INVOKE_STATIC -> {
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, false)
            }
            // 0x74
            Opcode.INVOKE_VIRTUAL_RANGE -> {
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, true)
            }
            // 0x75
            Opcode.INVOKE_SUPER_RANGE -> {
                // TODO: check valid
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, true)
            }
            // 0x76
            Opcode.INVOKE_DIRECT_RANGE -> {
                // TODO: check valid
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, false)
            }
            // 0x77
            Opcode.INVOKE_STATIC_RANGE -> {
                // TODO: check valid
                interpreterState.tempResultObject =
                    handleInstruction35cOrInstruction3rc(instruction, registers, false)
            }
            // 0x81
            Opcode.INT_TO_LONG -> {
                val i = instruction as Instruction12x
                val value = registers[i.registerB] as Int
                registers[i.registerA] = value.toLong()
                registers[i.registerA + 1] = WideValuePlaceHolder
            }
            // 0x95
            Opcode.AND_INT -> {
                val i = instruction as Instruction23x
                registers[i.registerA] =
                    (registers[i.registerB] as Int) and (registers[i.registerC] as Int)
            }
            // 0x96
            Opcode.OR_INT -> {
                val i = instruction as Instruction23x
                registers[i.registerA] =
                    (registers[i.registerB] as Int) or (registers[i.registerC] as Int)
            }
            // 0x97
            Opcode.XOR_INT -> {
                val i = instruction as Instruction23x
                registers[i.registerA] =
                    (registers[i.registerB] as Int) xor (registers[i.registerC] as Int)
            }
            // 0x98
            Opcode.SHL_INT -> {
                val i = instruction as Instruction23x
                registers[i.registerA] =
                    (registers[i.registerB] as Int) shl (registers[i.registerC] as Int)
            }
            // 0x99
            Opcode.SHR_INT -> {
                val i = instruction as Instruction23x
                registers[i.registerA] =
                    (registers[i.registerB] as Int) shr (registers[i.registerC] as Int)
            }
            // 0x9a
            Opcode.USHR_INT -> {
                val i = instruction as Instruction23x
                registers[i.registerA] =
                    (registers[i.registerB] as Int) ushr (registers[i.registerC] as Int)
            }
            // 0xbb
            Opcode.ADD_LONG_2ADDR -> {
                val i = instruction as Instruction12x
                val srcValue = registers[i.registerB] as Long
                val targetValue = registers[i.registerA] as Long + srcValue
                registers[i.registerA] = targetValue
                registers[i.registerA + 1] = WideValuePlaceHolder
            }
            // 0xbc
            Opcode.SUB_LONG_2ADDR -> {
                val i = instruction as Instruction12x
                val srcValue = registers[i.registerB] as Long
                val targetValue = registers[i.registerA] as Long - srcValue
                registers[i.registerA] = targetValue
                registers[i.registerA + 1] = WideValuePlaceHolder
            }
            // 0xd0
            Opcode.ADD_INT_LIT16 -> {
                val i = instruction as Instruction22s
                registers[i.registerA] = registers[i.registerB] as Int + i.narrowLiteral
            }
            // 0xd8
            Opcode.ADD_INT_LIT8 -> {
                val i = instruction as Instruction22b
                registers[i.registerA] = registers[i.registerB] as Int + i.narrowLiteral
            }
            else -> {
                val msg = instructionToString(
                    registers,
                    instruction,
                    interpreterState.firstInstruction
                ) + " not supported yet"
                logE(msg)
                dumpUsedOpCodes()
                throw NotImplementedError(msg)
            }
        }
        usedOpCodes.add(instruction.opcode)
    }

    private fun logRegisters(registers: Array<Any?>) {
        // 不要随意就调用toString()可能会递归造成StackOverflow
        val arr = registers.map {
            it.representation
        }.toTypedArray()
        logI("registers size: ${registers.size}, values: ${arr.contentToString()}")
    }

    private fun handleCompare(
        instruction: DexBackedInstruction,
        registers: Array<Any?>,
        reader: DexReader,
        interpreterState: InterpreterState
    ) {
        val i = instruction as DexBackedInstruction22t
        val rawValue1 = registers[i.registerA]
        val rawValue2 = registers[i.registerB]
        // TODO: different types
        val value1 = if (rawValue1 is Enum<*>) rawValue1.ordinal else rawValue1 as Int
        val value2 = if (rawValue2 is Enum<*>) rawValue2.ordinal else rawValue2 as Int
        val condition = when (instruction.opcode) {
            Opcode.IF_EQ -> value1 == value2
            Opcode.IF_NE -> value1 != value2
            Opcode.IF_LT -> value1 < value2
            Opcode.IF_GE -> value1 >= value2
            Opcode.IF_GT -> value1 > value2
            Opcode.IF_LE -> value1 <= value2
            else -> throw IllegalArgumentException("?")
        }
        if (condition) {
            reader.offset = i.instructionStart + i.codeOffset * 2
            interpreterState.jumpInstruction =
                DexBackedInstruction.readFrom(reader) as DexBackedInstruction
        }
    }

    private fun handleZeroCompare(
        instruction: DexBackedInstruction,
        registers: Array<Any?>,
        reader: DexReader,
        interpreterState: InterpreterState
    ) {
        val i = instruction as DexBackedInstruction21t

        @Suppress("MoveVariableDeclarationIntoWhen")
        val rawValue = registers[i.registerA]
        // regard null pointer as 0 in register, int as int, non-null object as 1
        val value: Int = when (rawValue) {
            null -> 0
            is Int -> rawValue
            else -> 1 // trick?
        }
        val condition = when (instruction.opcode) {
            Opcode.IF_EQZ -> value == 0
            Opcode.IF_NEZ -> value != 0
            Opcode.IF_LTZ -> value < 0
            Opcode.IF_GEZ -> value >= 0
            Opcode.IF_GTZ -> value > 0
            Opcode.IF_LEZ -> value <= 0
            else -> throw IllegalArgumentException("?")
        }
        if (condition) {
            reader.offset = i.instructionStart + i.codeOffset * 2
            interpreterState.jumpInstruction =
                DexBackedInstruction.readFrom(reader) as DexBackedInstruction
        }
    }

    enum class AccessType { GET, SET }

    private fun accessStaticFieldWithCheck(
        accessType: AccessType,
        registers: Array<Any?>,
        i: DexBackedInstruction21c,
        checkingType: Class<*>?
    ) {
        if (i.referenceType != ReferenceType.FIELD) {
            throw IllegalArgumentException("referenceType is not FIELD")
        }
        val fieldRef = i.reference as FieldReference
        val definingClass = fieldRef.definingClass
        val clazz = loadClassBySignatureUsingClassLoader(definingClass)
        val field = clazz.getDeclaredField(fieldRef.name)
        field.isAccessible = true
        when (accessType) {
            AccessType.GET -> {
                val value = field.get(null)
                if (value != null && checkingType != null
                    && !checkingType.isAssignableFrom(value.javaClass)
                ) {
                    throw IllegalStateException("staticField to get from $clazz is ${value.javaClass}, not assignable to $checkingType")
                }
                registers[i.registerA] = value
            }
            AccessType.SET -> {
                val value = registers[i.registerA]
                if (value != null && checkingType != null && !checkingType.isAssignableFrom(
                        value.javaClass
                    )
                ) {
                    throw IllegalStateException("staticField to set into $clazz is ${value.javaClass}, not assignable to $checkingType")
                }
                field.set(null, value)
            }
        }
    }

    class BadByteCodeError(s: String) : VirtualMachineError()

    private fun getInstanceFieldWithCheck(
        registers: Array<Any?>,
        i: DexBackedInstruction22c,
        checkingType: Class<*>?
    ) {
        val targetObject = registers[i.registerB]
        if (i.referenceType != ReferenceType.FIELD) {
            throw IllegalArgumentException("referenceType is not FIELD")
        }
        val fieldRef = i.reference as FieldReference
        val definingClass = fieldRef.definingClass

        val clazz = loadClassBySignatureUsingClassLoader(definingClass)
        val field = clazz.getDeclaredField(fieldRef.name)
        field.isAccessible = true
        val fieldValue = field.get(targetObject)
        if (fieldValue != null && checkingType != null && !checkingType.isAssignableFrom(
                fieldValue.javaClass
            )
        ) {
            throw IllegalStateException("fieldValue to get from $targetObject is ${fieldValue.javaClass}, not assignable to $checkingType")
        }
        registers[i.registerA] = fieldValue
    }

    private fun setInstanceFieldWithCheck(
        registers: Array<Any?>,
        i: DexBackedInstruction22c,
        checkingType: Class<*>?
    ) {
        val targetObject = registers[i.registerB]
        val fieldValue = registers[i.registerA]
        if (fieldValue != null && checkingType != null && !checkingType.isAssignableFrom(fieldValue.javaClass)) {
            throw IllegalStateException("fieldValue to set into $targetObject is ${fieldValue.javaClass}, not assignable to $checkingType")
        }
        if (i.referenceType != ReferenceType.FIELD) {
            throw IllegalArgumentException("referenceType is not FIELD")
        }
        val fieldRef = i.reference as FieldReference
        val definingClass = fieldRef.definingClass

        val clazz = loadClassBySignatureUsingClassLoader(definingClass)
        val field = clazz.getDeclaredField(fieldRef.name)
        field.isAccessible = true
        when (field.type) {
            Boolean::class.java -> field.setBoolean(targetObject, fieldValue as Boolean)
            Byte::class.java -> field.setByte(targetObject, fieldValue as Byte)
            Int::class.java -> field.setInt(targetObject, fieldValue as Int)
            Long::class.java -> {
                val a = (fieldValue as Long)
                field.setLong(targetObject, a)
                println("setLong")
            }
            else -> {
                field.set(targetObject, fieldValue)
                println("setObject")
            }
        }
    }


    private fun monitorEnter(obj: Any) {
    }

    private fun monitorExit(obj: Any) {}

    object LazyInitializeInstance {
        override fun toString(): String {
            return "LazyInstance"
        }
    }


    private fun handleNewInstanceInstruction(instruction: Instruction21c): Any {
        return if (instruction.referenceType == ReferenceType.TYPE) {
            val type = (instruction.reference as TypeReference).type
            logV("NEW_INSTANCE: $type")
            val clazz = loadClassBySignatureUsingClassLoader(type)
            logV("NEW_INSTANCE instruction: lazy handle class: $clazz")
            LazyInitializeInstance
        } else {
            throw IllegalArgumentException("referenceType is not TYPE")
        }
    }

    private fun handleNewArray(instruction: ReferenceInstruction, arrayLength: Int): Any {
        if (instruction.referenceType != ReferenceType.TYPE) {
            throw IllegalArgumentException("referenceType is not TYPE")
        }
        val type = (instruction.reference as TypeReference).type
        logV("create array: $type")
        val dimensionCount = type.lastIndexOf('[') + 1
        if (dimensionCount <= 0) {
            throw IllegalArgumentException("$type is not an array?")
        }
        val innerType = type.substring(dimensionCount)
        val clazz = loadClassBySignatureUsingClassLoader(innerType)
        val dimensions = IntArray(dimensionCount)
        dimensions[0] = arrayLength
        return java.lang.reflect.Array.newInstance(clazz, *dimensions)
    }

    private fun fillNewArray(i: Instruction35c, newArrayObj: Any, registers: Array<Any?>) {
        val initValues = arrayOfNulls<Any?>(i.registerCount)
        if (i.registerCount > 0) {
            initValues[0] = registers[i.registerC]
        }
        if (i.registerCount > 1) {
            initValues[1] = registers[i.registerD]
        }
        if (i.registerCount > 2) {
            initValues[2] = registers[i.registerE]
        }
        if (i.registerCount > 3) {
            initValues[3] = registers[i.registerF]
        }
        if (i.registerCount > 4) {
            initValues[4] = registers[i.registerG]
        }
        initValues.forEachIndexed { index, value ->
            JavaUtils.arraySet(newArrayObj, index, value)
        }
    }

    private fun logE(msg: String) {
        Log.e(TAG, msg)
    }

    private fun handleInstruction35cOrInstruction3rc(
        instruction: Instruction,
        registers: Array<Any?>,
        needThisObj: Boolean
    ): Any? {
        val i = instruction as ReferenceInstruction
        if (i.referenceType != ReferenceType.METHOD) {
            throw IllegalArgumentException("referenceType is not METHOD")
        }
        val methodRef = i.reference as MethodReference
        val definingClass = methodRef.definingClass
        val clazz = loadClassBySignatureUsingClassLoader(definingClass)
        val parameterTypes = convertToTypes(methodRef.parameterTypes)

        val invokeParams = when (instruction) {
            is Instruction3rc -> parametersFromRegisterRange(instruction, registers)
            is Instruction35c -> parametersFromFiveRegister(instruction, registers)
            else -> throw IllegalArgumentException("?")
        }
        val realParams = remove64BitPlaceHolders(invokeParams)
        val thisObj = if (needThisObj) realParams[0] else null
        val params =
            if (needThisObj) realParams.sliceArray(1 until realParams.size)
            else realParams
        normalizePrimitiveTypes(parameterTypes, params)
        return if ("<init>" == methodRef.name) {
            // must be invoke-direct here, and must after a new-instance?
            val constructor = clazz.getDeclaredConstructor(*parameterTypes)
            constructor.isAccessible = true
            val newObj = constructor.newInstance(*params)
            // new java.lang.Object() maybe no use here
            // here is a trick
            replaceLazyInitializeObjectInRegister(registers, i, newObj)
            newObj
        } else {
            if (needThisObj) {
                if (instruction.opcode == Opcode.INVOKE_SUPER) {
                    ReflectionBridge.callSuperMethodNative(
                        thisObj,
                        methodRef.name,
                        getMethodSignature(methodRef),
                        params
                    )
                } else {
                    ReflectionBridge.callThisMethodNative(
                        thisObj,
                        methodRef.name,
                        getMethodSignature(methodRef),
                        params
                    )
//                    val method = clazz.findMethod(methodRef.name, parameterTypes)
//                    method.isAccessible = true
//                    // start to invoke, 山口山~!
//                    method.invoke(thisObj, *params)
                }
            } else {
                val method = clazz.findMethod(methodRef.name, parameterTypes)
                method.isAccessible = true
                // start to invoke, 山口山~!
                method.invoke(null, *params)
            }
        }
    }

    private fun replaceLazyInitializeObjectInRegister(
        registers: Array<Any?>, i: Instruction, newObj: Any?
    ) {
        val firstIndex = when (i) {
            is Instruction35c -> i.registerC
            is Instruction3rc -> i.startRegister
            else -> throw IllegalArgumentException("?")
        }
        if (registers[firstIndex] is LazyInitializeInstance) {
            // replace LazyInitializeSystemClassInstance to the real object
            registers[firstIndex] = newObj
        }
    }

    private fun normalizePrimitiveTypes(
        parameterTypes: Array<Class<*>?>,
        params: Array<Any?>
    ) {
        parameterTypes.forEachIndexed { index, type ->
            val param = params[index]
            if (param != null && type != null) {
                when (param) {
                    is Int -> {
                        when (type) {
                            Boolean::class.java -> params[index] = param != 0
                            Byte::class.java -> params[index] = param.toByte()
                            Char::class.java -> params[index] = param.toChar()
                            Short::class.java -> params[index] = param.toShort()
                        }
                    }
                    is Long -> {
                        logV("LONG")
                    }
                    is Double -> {
                        logV("DOUBLE")
                    }
                }
            }
        }
    }

    private fun getMethodSignature(methodRef: MethodReference): String =
        "(" + methodRef.parameterTypes.joinToString("") + ")" + methodRef.returnType

    private fun remove64BitPlaceHolders(invokeParams: Array<Any?>) =
        invokeParams.filter {
            // need reference equals
            it !== WideValuePlaceHolder
        }.toTypedArray()

    private fun parametersFromFiveRegister(
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


    private fun parametersFromRegisterRange(i: Instruction3rc, registers: Array<Any?>) =
        registers.sliceArray(i.startRegister until i.startRegister + i.registerCount)


    private fun convertToTypes(parameterTypes: List<CharSequence>): Array<Class<*>?> {
        return parameterTypes.map { type ->
            loadClassBySignatureUsingClassLoader(type.toString())
        }.toTypedArray()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun logInstruction(
        registers: Array<Any?>,
        instruction: DexBackedInstruction,
        state: InterpreterState
    ) {
        logI(
            "" + state.methodForDebug + " " + instructionToString(
                registers,
                instruction,
                state.firstInstruction
            )
        )
    }

    private fun instructionToString(
        registers: Array<Any?>,
        instruction: DexBackedInstruction,
        firstInstruction: DexBackedInstruction
    ) =
        "${instruction.getRelativeOffsetTo(firstInstruction)}: ${instruction.opcode}, ${instruction.opcode.format}, ${instruction.getDetail(
            registers
        )}"

    @Suppress("NOTHING_TO_INLINE")
    private inline fun logV(msg: String) {
        Log.v(TAG, msg)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun logI(msg: String) {
        Log.i(TAG, msg)
    }

    fun invokeTestMethodTime(thisObj: Any?, className: String, methodName: String): Long {
        return try {
            val startTime = System.currentTimeMillis()
            invokeTestMethod(thisObj, className, methodName)
            val timeDelta = System.currentTimeMillis() - startTime
            Log.d(TAG, "It costs $timeDelta ms to run test() method by my interpreter.")
            timeDelta
        } catch (t: Throwable) {
            Log.wtf(TAG, t)
            0
        }
    }

    fun invoke(
        className: String,
        methodName: String,
        parameterTypes: List<String>?,
        needThisObj: Boolean,
        invokeParams: Array<Any?>
    ): Any? {
        val dexClass = findDexClassDef(className)
            ?: throw NotImplementedError("this invoke function should call class method in app dex.")
        val method = dexClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes == parameterTypes
        }
        @Suppress("FoldInitializerAndIfToElvis")
        if (method == null) {
            throw NoSuchMethodError("found class but cannot find method: $methodName")
        }
        return invokeMethod(method, needThisObj, invokeParams)
    }

    private fun findDexClassDef(definingClass: String): DexBackedClassDef? {
        return if (definingClass.startsWith(KVMAndroid::class.java.`package`!!.name)) {
            // fake null
            null
        } else {
            dexClassesMap[definingClass]
        }
    }
}

private fun Instruction.getDetail(registers: Array<Any?>): String {
    val builder = StringBuilder()
    if (this is OneRegisterInstruction) {
        builder.append("A=$registerA(${registers[registerA].representation}) ")
    }
    if (this is TwoRegisterInstruction) {
        builder.append("B=$registerB(${registers[registerB].representation}) ")
    }
    if (this is ThreeRegisterInstruction) {
        builder.append("C=$registerC(${registers[registerC].representation}) ")
    }
    // no 4 register
    if (this is FiveRegisterInstruction) {
        builder.append("C=$registerC(${registers[registerC].representation}) ")
        builder.append("D=$registerD(${registers[registerD].representation}) ")
        builder.append("E=$registerE(${registers[registerE].representation}) ")
        builder.append("F=$registerF(${registers[registerF].representation}) ")
        builder.append("G=$registerG(${registers[registerG].representation}) ")
        builder.append("count=$registerCount ")
    }
    if (this is ReferenceInstruction) {
        builder.append("${ReferenceType.toString(referenceType)}=$reference ")
    }
    if (this is WideLiteralInstruction) {
        builder.append("wideLiteral: $wideLiteral ")
    }
    if (this is NarrowLiteralInstruction) {
        builder.append("narrowLiteral: $narrowLiteral ")
    }
    return builder.toString()
}

private fun DexBackedInstruction.getRelativeOffsetTo(instruction: DexBackedInstruction): Int {
    return this.instructionStart - instruction.instructionStart
}


private fun <T> Class<T>.findMethod(name: String, parameterTypes: Array<Class<*>?>): Method {
    return try {
        getDeclaredMethod(name, *parameterTypes)
    } catch (e: NoSuchMethodException) {
        try {
            getMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            throw e
        }
    }
}
