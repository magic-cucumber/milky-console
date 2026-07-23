import co.touchlab.kermit.Logger
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.MILKY_CONSOLE_HOST_ABI_VERSION
import platform.milky_console_interop.MILKY_FALSE
import platform.milky_console_interop.milky_console_host_api
import platform.milky_console_interop.milky_console_plugin_api
import platform.posix.exit
import platform.posix.free
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.protocol.PluginHandshakeError
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.saltify.console.util.dlloader.DLLoader

private val logger = Logger.withTag("PluginLoader")

/** 加载插件动态库并完成 ABI 校验；任何一步失败都走 reject 退出。 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun loadPlugin(): CPointer<milky_console_plugin_api> {
    val loader = try {
        logger.d { "loading plugin dynamic library: ${LoaderApplication.libpath}" }
        DLLoader(LoaderApplication.libpath)
    } catch (e: Throwable) {
        logger.e { "dynamic library load failed: ${e.message}" }
        reject("无法加载插件动态库: ${e.message}", PluginHandshakeError.DYNAMIC_LIBRARY_LOAD_FAILED)
    }
    val getApi = try {
        logger.d { "resolving milky_plugin_get_api" }
        loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
    } catch (e: Throwable) {
        logger.e { "entry point lookup failed: ${e.message}" }
        loader.close()
        reject("无法查找到插件加载函数: ${e.message}", PluginHandshakeError.ENTRY_POINT_NOT_FOUND)
    }
    val pointer = getApi.invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
    logger.d { "plugin API pointer returned: present=${pointer != null && pointer.rawValue != NativePtr.NULL}" }
    if (pointer == null || pointer.rawValue == NativePtr.NULL) {
        reject(
            "无法查找到插件加载函数: 插件加载函数返回值为空指针",
            PluginHandshakeError.NULL_PLUGIN_API,
        )
    }
    val api = pointer.pointed
    logger.d { "plugin API struct received: abi=${api.abi_version}, size=${api.struct_size}" }

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        logger.e { "ABI mismatch: host=$MILKY_CONSOLE_HOST_ABI_VERSION, plugin=${api.abi_version}" }
        reject(
            "插件ABI不匹配。本加载器期望: $MILKY_CONSOLE_HOST_ABI_VERSION，该插件的ABI为${api.abi_version}",
            PluginHandshakeError.ABI_MISMATCH,
        )
    }
    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        logger.e { "API struct too small: actual=${api.struct_size}, expected=${sizeOf<milky_console_plugin_api>()}" }
        reject("插件API不匹配。", PluginHandshakeError.API_MISMATCH)
    }
    if (api.on_load == null) {
        logger.e { "API missing on_load" }
        reject("插件API缺少 on_load", PluginHandshakeError.MISSING_ON_LOAD)
    }
    return pointer
}

/** 在专用 callback 线程上执行插件的 on_load；崩溃或返回 MILKY_FALSE 都走 reject 退出。 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal suspend fun runOnLoad(api: CPointer<milky_console_plugin_api>, hostApi: CPointer<milky_console_host_api>) {
    LoaderApplication.callbackDispatcher = newSingleThreadContext("plugin-callback")
    logger.d { "dispatching on_load" }
    val loaded = try {
        withContext(LoaderApplication.callbackDispatcher) {
            memScoped {
                api.pointed.on_load!!.invoke(LoaderApplication.config.cstr.getPointer(this), hostApi)
            }
        }
    } catch (e: Throwable) {
        logger.a { "plugin on_load crashed loader boundary: ${e.stackTraceToString()}" }
        LoaderApplication.callbackDispatcher.close()
        free(hostApi)
        reject("插件初始化崩溃: ${e.message}", PluginHandshakeError.INITIALIZATION_FAILED)
    }
    logger.d { "on_load completed with result=$loaded" }
    logger.i { "on_load execution result: loaded=$loaded, pendingSendFailure=${PendingPluginApiRequests.lastSendFailure() != null}" }
    PendingPluginApiRequests.lastSendFailure()?.let {
        logger.e { "send_message failed:\n$it" }
    }
    if (loaded == MILKY_FALSE) {
        logger.e { "plugin on_load returned MILKY_FALSE" }
        LoaderApplication.callbackDispatcher.close()
        free(hostApi)
        reject("插件初始化失败", PluginHandshakeError.INITIALIZATION_FAILED)
    }
}

/** 把 HostEvent 分发到插件的 on_message 回调。 */
@OptIn(ExperimentalForeignApi::class)
internal fun CoroutineScope.startHostEventDispatch(api: CPointer<milky_console_plugin_api>): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter hostEvents coroutine" }
        EventBus.subscribe<HostEvent>().collect { message ->
            logger.d { "enter on_message callback: type=${message.event::class.simpleName}" }
            try {
                withContext(LoaderApplication.callbackDispatcher) {
                    memScoped {
                        api.pointed.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                    }
                }
            } catch (e: Throwable) {
                logger.a { "plugin on_message crashed loader boundary: ${e.stackTraceToString()}" }
                throw e
            }
            logger.i { "exit on_message callback successfully: type=${message.event::class.simpleName}" }
        }
        logger.v { "exit hostEvents coroutine" }
    }

/** 注册 HostClose 等待器；UNDISPATCHED 保证订阅在发送 Ready 之前生效。 */
internal fun CoroutineScope.prepareHostCloseWaiter(): Deferred<HostClose> =
    async(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter host close waiter" }
        EventBus.subscribe<HostClose>().first()
    }

/** 停止事件分发并调用 on_unload，随后以其返回值作为退出码结束进程。 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun unloadPlugin(api: CPointer<milky_console_plugin_api>, hostEvents: Job): Nothing {
    hostEvents.cancelAndJoin()
    logger.d { "host event callback collector cancelled: expected=true" }
    logger.i { "enter on_unload callback" }
    val exitCode = try {
        withContext(LoaderApplication.callbackDispatcher) { api.pointed.on_unload?.invoke() ?: 0 }
    } catch (e: Throwable) {
        logger.a { "plugin on_unload crashed loader boundary: ${e.stackTraceToString()}" }
        1
    }
    // `exit` is intentional: a blocked pipe reader is a child of runBlocking and
    // would otherwise keep the loader alive after a successful unload. The OS
    // releases the pipes and loader resources; the exit code is the plugin's
    // on_unload result and is observed by the host lifecycle.
    logger.i { "exit main successfully: plugin exitCode=$exitCode" }
    exit(exitCode)
    error("unreachable")
}
