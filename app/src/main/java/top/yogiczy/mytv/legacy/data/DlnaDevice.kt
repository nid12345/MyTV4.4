package top.yogiczy.mytv.legacy.data

/**
 * DLNA 媒体渲染设备（智能电视、电视盒子、投影仪等）。
 *
 * 移植自 MyTV 1.7（源码 tag `custom-v1.7`）
 * `core/data/entities/dlna/DlnaDevice.kt` —— 字段与派生属性逐条保留。
 */
data class DlnaDevice(
    /** 设备名称，如「客厅电视」。 */
    val friendlyName: String = "",
    /** 设备型号。 */
    val modelName: String = "",
    /** 设备唯一标识（UDN）。 */
    val udn: String = "",
    /** 设备描述地址（SSDP 响应里的 LOCATION）。 */
    val location: String = "",
    /** 设备 IP。 */
    val host: String = "",
    /** AVTransport 服务类型（含版本号，用于构造 SOAPACTION）。 */
    val avTransportServiceType: String = "",
    /** AVTransport 控制地址。 */
    val avTransportControlUrl: String = "",
) {
    /** 设备唯一标识。 */
    val id: String get() = udn.ifBlank { location }

    /** 展示用设备名。 */
    val displayName: String
        get() = friendlyName.ifBlank { host.ifBlank { "未知设备" } }

    /** 展示用设备信息。 */
    val displayInfo: String
        get() = listOf(host, modelName).filter { it.isNotBlank() }.joinToString(" · ")
}
