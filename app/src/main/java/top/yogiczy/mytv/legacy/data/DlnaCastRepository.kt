package top.yogiczy.mytv.legacy.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * DLNA 投送状态仓库（问题 6 的投屏）。
 *
 * ---------------------------------------------------------------------------
 * 移植自 MyTV 1.7 `core/data/repositories/dlna/DlnaCastRepository.kt`：
 *   维护「局域网内可投送设备列表」+「当前正在投送的设备」，对外暴露扫描/投送/停止。
 *   切换设备时会把上一台停掉，避免两台设备同时播放 —— 该逻辑逐条保留。
 *
 * ⚠️ 与原版的**结构性差异**（必须在报告里说明）：
 *   原版是 `MutableStateFlow` + 协程，本工程（API 19，禁协程）改为
 *   **普通字段 + Handler 回调**：所有字段只在主线程读写，后台线程只回投递。
 *   对调用方的语义等价：scan() 立刻返回、结果通过 [onChanged] 通知。
 * ---------------------------------------------------------------------------
 */
object DlnaCastRepository {

    private const val TAG = "投屏"

    /**
     * 搜索持续时间。
     *
     * v1.21：6s → 8s。原版 6s 是按「只搜标准 MediaRenderer」定的；
     * v1.21 起要额外搜 `ssdp:all`/`upnp:rootdevice`（小米私有设备类型只在
     * 这两个目标下回应），响应更杂也更慢，实测 6s 偶尔收不全。
     */
    private const val SCAN_TIMEOUT_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())

    /** 已发现的设备（只在主线程读写）。 */
    var devices: List<DlnaDevice> = emptyList()
        private set

    /** 是否正在搜索。 */
    var isScanning: Boolean = false
        private set

    /** 正在投送的设备标识（空串 = 未投送）。 */
    var castingDeviceId: String = ""
        private set

    /** 状态变化回调（主线程）。界面对它做整体重绘即可。 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** 正在投送的设备。 */
    val castingDevice: DlnaDevice?
        get() = devices.firstOrNull { it.id == castingDeviceId }

    private var scanToken = 0

    /** 搜索局域网内的投屏设备。可重复调用（会取消上一次的结果）。 */
    fun scan(context: Context?) {
        val token = ++scanToken
        isScanning = true
        devices = emptyList()
        notifyChanged()
        Log.i(TAG, "开始搜索投屏设备…")

        Thread({
            DlnaDiscovery.discover(context?.applicationContext, SCAN_TIMEOUT_MS) { device ->
                // 增量回投（原版 onDeviceFound 的等价物）
                main.post { if (token == scanToken) addDevice(device) }
            }
            main.post {
                if (token == scanToken) {
                    isScanning = false
                    notifyChanged()
                    Log.i(TAG, "搜索结束，共 ${devices.size} 台设备")
                }
            }
        }, "dlna-scan").start()
    }

    private fun addDevice(device: DlnaDevice) {
        if (devices.any { it.id == device.id }) return
        devices = (devices + device).sortedBy { it.displayName }
        notifyChanged()
    }

    /**
     * 把当前频道投送到设备播放。
     *
     * @param urlIdx 线路下标（越界自动收敛到有效范围，与原版一致）
     * @param onResult 主线程回调
     */
    fun cast(device: DlnaDevice, channel: Channel, urlIdx: Int, onResult: (Result<Unit>) -> Unit) {
        val idx = if (channel.urlList.isEmpty()) 0 else urlIdx.coerceIn(0, channel.urlList.size - 1)
        val url = channel.urlList.getOrNull(idx).orEmpty()

        Thread({
            val result = DlnaController.cast(device, url, channel.name)
            if (result.isSuccess) {
                val previous = castingDevice
                if (previous != null && previous.id != device.id) {
                    // 切换设备时停止上一台，避免两台设备同时播放
                    runCatching { DlnaController.stop(previous) }
                }
                main.post {
                    castingDeviceId = device.id
                    notifyChanged()
                }
            }
            main.post { onResult(result) }
        }, "dlna-cast").start()
    }

    /** 停止指定设备的投送。 */
    fun stop(device: DlnaDevice, onResult: (Boolean) -> Unit = {}) {
        Thread({
            val ok = DlnaController.stop(device)
            main.post {
                if (castingDeviceId == device.id) {
                    castingDeviceId = ""
                    notifyChanged()
                }
                onResult(ok)
            }
        }, "dlna-stop").start()
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }
}
