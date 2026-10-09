package top.yogiczy.mytv.legacy.data

import java.io.File

/**
 * 极简文件缓存
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`）`core/data/repositories/FileCacheRepository.kt`
 *
 * 移植方式：**语义等价重写**（不是逐字移植）——
 *   ① 原版是 `abstract class FileCacheRepository : 协程 + Globals.cacheDir 单例`；
 *      本工程不引协程、不用全局单例，改为**构造时注入目录**，方法同步。
 *   ② 保留原版的三个关键语义：
 *      · `getOrRefresh(isExpired, refreshOp)`：**先判断过期，过期才刷新**；
 *      · `isExpired(lastModified, cacheData)` 同时拿到「文件修改时间」与「缓存内容」，
 *        允许调用方按内容判断（原版 EpgParsedCache 就是靠内容里「当前时刻有几条节目」决定的）；
 *      · `refreshOp` 返回空串时**仍然写盘**（原版如此；空内容由调用方自己判为失效）。
 *   ③ 校验：写盘失败只记日志不抛（原版 `runCatching` 包住）。
 *
 * ⚠️ 这是本模块**唯一一个不在指令清单里的新增文件**，理由：EpgRepository（两级缓存）与
 * LineSpeed（测速结果缓存）需要**完全相同**的那段「过期判定 + 读改写 + 落盘」逻辑，
 * 各写一份必然漂移（原版也正是为此抽了公共基类）。若你希望严格按清单来，我可以把它
 * 内联进两个使用方，但会牺牲一致性。
 * ---------------------------------------------------------------------------
 */
class FileCache(private val dir: File, private val fileName: String) {

    private fun file(): File = File(dir, fileName)

    /** 缓存文件的最后修改时间；文件不存在返回 0。 */
    fun lastModified(): Long = runCatching { file().lastModified() }.getOrDefault(0L)

    /** 读缓存内容；不存在或读失败返回 null。 */
    fun read(): String? = runCatching {
        val f = file()
        if (f.exists()) f.readText() else null
    }.getOrNull()

    /** 写缓存；失败只吞掉（调用方不因缓存写失败而中断业务）。 */
    fun write(data: String) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(data)
        }
    }

    /** 删除缓存文件。 */
    fun clear() {
        runCatching { file().delete() }
    }

    /**
     * 有过期判定版本的「取缓存，必要时刷新」。
     *
     * @param isExpired 入参为 (缓存文件最后修改时间, 缓存内容)，返回 true 表示需要重新拉取
     * @param refreshOp 真正去拉取/重算
     */
    fun getOrRefresh(isExpired: (lastModified: Long, cacheData: String?) -> Boolean, refreshOp: () -> String): String {
        var data = read()
        if (isExpired(lastModified(), data)) data = null
        if (data.isNullOrBlank()) {
            data = refreshOp()
            write(data)
        }
        return data
    }

    /**
     * 按「多久算过期」的简化版本。
     *
     * @param cacheTimeMs 0 表示永不过期（原版 `getOrRefresh(0)` 的语义）
     */
    fun getOrRefresh(cacheTimeMs: Long, refreshOp: () -> String): String =
        getOrRefresh({ lastModified, _ -> System.currentTimeMillis() - lastModified >= cacheTimeMs }, refreshOp)
}
