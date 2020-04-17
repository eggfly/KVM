package eggfly.kvm.core

import android.util.Log
import eggfly.kvm.core.util.AppContext
import eggfly.kvm.core.util.AssetsUtils
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.ReferenceType
import org.jf.dexlib2.dexbacked.DexBackedClassDef
import org.jf.dexlib2.dexbacked.DexBackedMethod
import org.jf.dexlib2.dexbacked.DexReader
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction21c
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction21t
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction22c
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.*
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.io.File
import java.util.*

object KVMAndroid {
    class Frame
    class StackFrame : Stack<Frame>()

    private const val TAG = "KVMAndroid"
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
                val dexClasses = dexNames.map { dex ->
                    val entry = apk.getEntry(dex)
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
        val state = InterpreterState(null, false, null, null)
        do {
            val instruction =
                state.jumpInstruction
                    ?: DexBackedInstruction.readFrom(reader) as DexBackedInstruction
            // clear
            state.jumpInstruction = null
            interpretInstruction(reader, instruction, registers, state)
        } while (!state.returned)
        logV("returned")
        return state.returnValue
    }

    /**
     * 表示单条指令执行后的结果状态
     */
    data class InterpreterState(
        var jumpInstruction: DexBackedInstruction?,
        var returned: Boolean,
        var returnValue: Any?,
        var invokeTempReturnValue: Any?
    )

    object VoidReturnValue

    private fun interpretInstruction(
        reader: DexReader,
        instruction: DexBackedInstruction,
        registers: Array<Any?>,
        interpreterState: InterpreterState
    ) {
        logInstruction(instruction)
        when (instruction.opcode) {
            // 0x00
            Opcode.NOP -> {
                instruction as Instruction10x
            }
            // 0x07
            Opcode.MOVE_OBJECT -> {
                val i = instruction as Instruction12x
                val srcValue = registers[i.registerB]
                registers[i.registerA] = srcValue
            }
            // 0x0b
            Opcode.MOVE_RESULT_WIDE -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = interpreterState.invokeTempReturnValue
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
                logV("MOVE_RESULT_WIDE:${interpreterState.invokeTempReturnValue}")
            }
            // 0x0c
            Opcode.MOVE_RESULT_OBJECT -> {
                val i = instruction as Instruction11x
                registers[i.registerA] = interpreterState.invokeTempReturnValue
                logV("MOVE_RESULT_OBJECT:${interpreterState.invokeTempReturnValue}")
            }
            // 0x0e
            Opcode.RETURN_VOID -> {
                instruction as Instruction10x
                interpreterState.returned = true
                interpreterState.returnValue = VoidReturnValue
                logV("RETURN_VOID: null")
            }
            // 0x10
            Opcode.RETURN_WIDE -> {
                val i = instruction as Instruction11x
                val value = registers[i.registerA] as Long
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
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            // 0x17
            Opcode.CONST_WIDE_32 -> {
                val i = instruction as Instruction31i
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
                    clazz.cast(value)
                } else {
                    throw IllegalArgumentException("referenceType is not TYPE")
                }
            }
            // 0x22
            Opcode.NEW_INSTANCE -> {
                val i = instruction as Instruction21c
                // 早期胚胎对象
                val embryoInstance = handleNewInstanceInstruction(i)
                registers[i.registerA] = embryoInstance
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
                val rawValue = registers[i.registerA]
                val isZero = if (rawValue == null) {
                    true
                } else {
                    rawValue == 0
                }
                if (!isZero) {
                    logV("" + i.codeOffset)
                    reader.offset = i.instructionStart + i.codeOffset * 2
                    interpreterState.jumpInstruction =
                        DexBackedInstruction.readFrom(reader) as DexBackedInstruction
                    logV("IF_NEZ: " + interpreterState.jumpInstruction)
                }
            }
            // 0x54
            Opcode.IGET_OBJECT -> {
                val i = instruction as DexBackedInstruction22c
                getInstanceFieldWithCheck(registers, i, Object::class.java)
            }
            // 0x5a
            Opcode.IPUT_WIDE -> {
                val i = instruction as DexBackedInstruction22c
                setInstanceFieldWithCheck(registers, i, null)
            }
            // 0x5b
            Opcode.IPUT_OBJECT -> {
                val i = instruction as DexBackedInstruction22c
                setInstanceFieldWithCheck(registers, i, Object::class.java)
            }
            // 0x5c
            Opcode.IPUT_BOOLEAN -> {
                val i = instruction as DexBackedInstruction22c
                setInstanceFieldWithCheck(registers, i, Boolean::class.java)
            }
            // 0x62
            Opcode.SGET_OBJECT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.GET, registers, i, Object::class.java)
            }
            // 0x69
            Opcode.SPUT_OBJECT -> {
                val i = instruction as DexBackedInstruction21c
                accessStaticFieldWithCheck(AccessType.SET, registers, i, Object::class.java)
            }
            // 0x6e
            Opcode.INVOKE_VIRTUAL -> {
                interpreterState.invokeTempReturnValue =
                    handleInstruction35c(instruction, registers, true)
                logV("INVOKE_VIRTUAL")
            }
            // 0x70
            Opcode.INVOKE_DIRECT -> {
                interpreterState.invokeTempReturnValue =
                    handleInstruction35c(instruction, registers, true)
                logV("INVOKE_DIRECT")
            }
            // 0x71
            Opcode.INVOKE_STATIC -> {
                interpreterState.invokeTempReturnValue =
                    handleInstruction35c(instruction, registers, false)
                logV("INVOKE_STATIC")
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
            // 0xbc
            Opcode.SUB_LONG_2ADDR -> {
                val i = instruction as Instruction12x
                val srcValue = registers[i.registerB] as Long
                val targetValue = registers[i.registerA] as Long - srcValue
                registers[i.registerA] = targetValue
                registers[i.registerA + 1] = SecondSlotPlaceHolderOf64BitValue
            }
            else -> {
                val msg = instructionToString(instruction) + " not supported yet"
                logE(msg)
                throw NotImplementedError(msg)
            }
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

    object LazyInitializeSystemClassInstance


    enum class KVMClassState { UNBORN, LOADING, LOADED }

    private fun handleNewInstanceInstruction(instruction: Instruction21c): Any {
        return if (instruction.referenceType == ReferenceType.TYPE) {
            val type = (instruction.reference as TypeReference).type
            logV("NEW_INSTANCE: $type")
            val clazz = loadClassBySignatureUsingClassLoader(type)
            logV("NEW_INSTANCE instruction: lazy handle class: $clazz")
            LazyInitializeSystemClassInstance
        } else {
            throw IllegalArgumentException("referenceType is not TYPE")
        }
    }

    private fun logE(msg: String) {
        Log.e(TAG, msg)
    }

    private fun handleInstruction35c(
        instruction: Instruction,
        registers: Array<Any?>,
        needThisObj: Boolean
    ): Any? {
        val i = instruction as Instruction35c
        if (i.referenceType != ReferenceType.METHOD) {
            throw IllegalArgumentException("referenceType is not METHOD")
        }
        val methodRef = i.reference as MethodReference
        val definingClass = methodRef.definingClass
        val clazz = loadClassBySignatureUsingClassLoader(definingClass)
        val parameterTypes = convertToTypes(methodRef.parameterTypes)
        val invokeParams = parametersFromRegister(i, registers)
        val realParams = remove64BitPlaceHolders(invokeParams)
        return if ("<init>" == methodRef.name) {
            // must be invoke-direct here, and must after a new-instance?
            val constructor = clazz.getConstructor(*parameterTypes)
            constructor.isAccessible = true
            val realParamsWithoutFirst = realParams.sliceArray(1 until realParams.size)
            val newObj = constructor.newInstance(*realParamsWithoutFirst)
            // new java.lang.Object() maybe no use here
            if (registers[i.registerC] is LazyInitializeSystemClassInstance) {
                // replace LazyInitializeSystemClassInstance to the real system class object
                registers[i.registerC] = newObj
            }
            newObj
        } else {
            val method = clazz.getDeclaredMethod(methodRef.name, *parameterTypes)
            method.isAccessible = true
            logV("" + method)
            // static or virtual?
            // start to invoke, 山口山~!
            if (needThisObj) {
                val firstParamIsThisObj = realParams[0]
                val realParamsWithoutFirst = realParams.sliceArray(1 until realParams.size)
                method.invoke(firstParamIsThisObj, *realParamsWithoutFirst)
            } else {
                method.invoke(null, *realParams)
            }
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
            loadClassBySignatureUsingClassLoader(type.toString())
        }.toTypedArray()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun logInstruction(instruction: DexBackedInstruction) {
        // logV(instructionToString(instruction))
    }

    private fun instructionToString(instruction: DexBackedInstruction) =
        "${instruction.instructionStart}: opcode=${instruction.opcode}, ${instruction.javaClass.simpleName}"

    @Suppress("NOTHING_TO_INLINE")
    private inline fun logV(msg: String) {
        // Log.v(TAG, msg)
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

    fun invokeByReflection(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        thisObj: Any?,
        args: Array<Any?>
    ): Any? {
        val method = clazz.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        return method.invoke(thisObj, *args)
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
        if (method == null) {
            throw NoSuchMethodError("found class but cannot find method: $method")
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

//    private fun dumpInstruction(instruction: Instruction?): String {
//        instruction.runCatching { }
//    }
}
