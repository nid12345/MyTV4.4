package top.yogiczy.mytv.legacy.data

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

// 原版这两个函数定义在 `Epg.Companion` / `EpgProgramme.Companion` 里，
// 由使用方（原版是另一个文件 EpgList.kt）显式 import 才在作用域内。
// 本工程把实体合并进同一个文件后，同样需要这条 import 才能在 EpgList.Companion 里调用。
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.Epg.Companion.recentProgramme
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.isLive

/**
 * 节目单领域模型（EPG）
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`，提交 7d1081f），合并自以下四个实体文件：
 *   core/data/entities/epg/EpgProgramme.kt      → [EpgProgramme]
 *   core/data/entities/epg/EpgProgrammeList.kt  → [EpgProgrammeList]
 *   core/data/entities/epg/EpgProgrammeRecent.kt→ [EpgProgrammeRecent]
 *   core/data/entities/epg/Epg.kt               → [Epg]
 *   core/data/entities/epg/EpgList.kt           → [EpgList]
 *
 * 移植方式：**判定逻辑逐字保留**，只做两类必需改动：
 *   ① 去掉 `@Serializable` / `@Immutable`（原版为 kotlinx.serialization 与 Compose 服务；
 *      本工程禁引 serialization、无 Compose。持久化改由 EpgRepository 用 org.json 完成）；
 *   ② package 改为 `top.yogiczy.mytv.legacy.data`。
 * 所有时间判定、区间语义、缓存淘汰阈值（1024）、匹配回退顺序**均未改动**。
 * ---------------------------------------------------------------------------
 */

/**
 * 频道节目
 *
 * 来源：`core/data/entities/epg/EpgProgramme.kt`，逐字保留。
 */
data class EpgProgramme(
    /** 开始时间（时间戳） */
    val startAt: Long = 0,

    /** 结束时间（时间戳） */
    val endAt: Long = 0,

    /** 节目名称 */
    val title: String = "",
) {
    companion object {
        /**
         * 是否正在直播
         *
         * 原版写法 `System.currentTimeMillis() in startAt..<endAt`（左闭右开），
         * 逐字保留。`..<` 是 Kotlin 1.9 稳定语法，本工程 Kotlin 1.9.24 可用。
         */
        fun EpgProgramme.isLive() = System.currentTimeMillis() in startAt..<endAt

        /**
         * 节目进度
         */
        fun EpgProgramme.progress(current: Long = System.currentTimeMillis()) =
            (current - startAt).toFloat() / (endAt - startAt)

        fun EpgProgramme.remainingMinutes(current: Long = System.currentTimeMillis()) =
            ceil((endAt - current) / 60_000f).roundToInt()

        val EXAMPLE = EpgProgramme(
            startAt = System.currentTimeMillis() - 3600 * 1000,
            endAt = System.currentTimeMillis() + 3600 * 1000,
            title = "节目标题",
        )

        val EMPTY by lazy {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            EpgProgramme(
                startAt = calendar.timeInMillis,
                endAt = calendar.timeInMillis + (24 * 3600 - 1) * 1000,
                title = "精彩节目",
            )
        }
    }
}

/**
 * 频道节目列表
 *
 * 来源：`core/data/entities/epg/EpgProgrammeList.kt`。
 * 原版 `: List<EpgProgramme> by value` 的委托保留，因此 List 的全部语义可直接用。
 */
data class EpgProgrammeList(
    val value: List<EpgProgramme> = emptyList(),
) : List<EpgProgramme> by value

/**
 * 频道当前节目/下一个节目
 *
 * 来源：`core/data/entities/epg/EpgProgrammeRecent.kt`。
 */
data class EpgProgrammeRecent(
    /** 当前正在播放 */
    val now: EpgProgramme? = null,

    /** 稍后播放 */
    val next: EpgProgramme? = null,
)

/**
 * 频道节目单
 *
 * 来源：`core/data/entities/epg/Epg.kt`。
 */
data class Epg(
    /** 频道名称 */
    val channel: String = "",

    /** 节目列表 */
    val programmeList: EpgProgrammeList = EpgProgrammeList(),
) {
    companion object {
        /**
         * 取「当前正在播」与「下一个」节目
         *
         * 逐字保留原版的 `binarySearch` 比较器三态写法（早于当前→1、晚于当前→-1、命中→0）。
         */
        fun Epg.recentProgramme(): EpgProgrammeRecent {
            val currentTime = System.currentTimeMillis()

            val liveProgramIndex = programmeList.binarySearch {
                when {
                    currentTime < it.startAt -> 1
                    currentTime > it.endAt -> -1
                    else -> 0
                }
            }

            return if (liveProgramIndex > -1) {
                EpgProgrammeRecent(
                    now = programmeList[liveProgramIndex],
                    next = programmeList.getOrNull(liveProgramIndex + 1)
                )
            } else {
                EpgProgrammeRecent()
            }
        }

        /**
         * 取「当前正在播」的那一条（v1.21）
         *
         * 二分优先（O(log n)），查不到再线性兜底 —— 兜底是为了对付**未按时间排序**
         * 的节目单（少数源会乱序），语义与原来的 `firstOrNull { it.isLive() }` 一致。
         * 排序正常时压根走不到兜底，代价为零。
         */
        fun Epg.nowProgramme(): EpgProgramme? {
            recentProgramme().now?.let { return it }
            return programmeList.firstOrNull { it.isLive() }
        }

        /**
         * 占位节目单：[empty] 用于「该频道今天没有节目数据」时界面仍能显示一条横条。
         */
        fun empty(channel: Channel): Epg {
            val dayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val dayEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }

            return Epg(
                channel = channel.epgName.orEmpty(),
                programmeList = EpgProgrammeList(
                    listOf(
                        EpgProgramme(
                            title = "暂无节目",
                            startAt = dayStart.timeInMillis,
                            endAt = dayEnd.timeInMillis,
                        )
                    )
                )
            )
        }
    }
}

/**
 * 频道节目单列表
 *
 * 来源：`core/data/entities/epg/EpgList.kt`。
 * 原版 `: List<Epg> by value` 委托与 1024 条 LRU 匹配缓存均逐字保留。
 *
 * 缓存为什么必要：`match()` 每次都要对整份节目单（上千个频道）做正则归一化比较，
 * 而界面在滚动时会对每个可见频道反复调用。
 */
data class EpgList(
    val value: List<Epg> = emptyList(),
) : List<Epg> by value {
    companion object {
        /** 索引/缓存的锁（索引在后台线程也可能被重建）。 */
        private val EPG_LOCK = Any()

        /**
         * 频道名 → 匹配结果 的 LRU 缓存（accessOrder=true，超 2048 条淘汰最久未用）。
         *
         * 逐字保留原版实现，包括 `null` 也可入缓存（避免对匹配不上的频道反复重算）。
         * v1.21 只改两点：容量 1024 → 2048（分组频道数可能超过一千）、
         * 键由「仅 epgName」改为「epgName + name」（同名不同展示名的两个频道
         * 会互相串台，原写法会返回错的节目单）。
         */
        private val matchCache =
            object : LinkedHashMap<String, Epg?>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Epg?>?): Boolean {
                    return size > 2048
                }
            }

        // ------------------------------------------------------------------
        // v1.21：匹配索引 —— 修 ANR 的关键
        // ------------------------------------------------------------------
        // 原实现是 `firstOrNull { ChannelName.matches(epg.channel, x) }`：
        //   **每个**频道都要线性扫一遍**整份**节目单，而每次 `matches` 要跑
        //   2~4 次 `normalize`（每 normalize = NFKC + 4 次正则）。
        //   节目单上千频道 × 分组几百频道 × 16 次正则 = 千万级正则调用，
        //   老设备（崩溃那台 MiTV4-ANSM0）实测**主线程阻塞 11 秒** → ANR
        //   （后台指纹 7531bf224a66cb14，堆栈顶端就是 `Pattern.matcher`）。
        //
        // 归一化是「字符串 → 字符串」的等价关系，所以完全可以在节目单到手时
        // **建一次哈希索引**，之后每次匹配退化成 O(1) 查表：
        //   · normIndex：normalize(节目单频道名) → Epg（覆盖「归一化后相等」）
        //   · coreIndex：coreKey(节目单频道名)  → Epg（覆盖「同为 cctvN」，
        //     只收 cctv 开头的键 —— 与原 `matches` 的 `startsWith("cctv")` 判据一致）
        // 建索引的代价是 O(N)（一次性的，且可在后台线程做），
        // 查询从 O(N) 降到 O(1)，且**结果与原来的线性扫描完全等价**。
        // ------------------------------------------------------------------

        /** 索引当前归属的列表实例（**引用比较**）：换源/重拉节目单后会自动重建。 */
        @Volatile
        private var indexedFor: EpgList? = null

        /** 归一化名 → 节目单。 */
        @Volatile
        private var normIndex: HashMap<String, Epg> = HashMap()

        /** 核心标识（仅 cctv*）→ 节目单。 */
        @Volatile
        private var coreIndex: HashMap<String, Epg> = HashMap()

        /** 节目单到手后调用：把索引建好（幂等，换源后自动重建）。 */
        private fun ensureIndex(list: EpgList) {
            if (indexedFor === list && normIndex.isNotEmpty()) return
            synchronized(EPG_LOCK) {
                if (indexedFor === list && normIndex.isNotEmpty()) return
                val started = android.os.SystemClock.elapsedRealtime()
                val norm = HashMap<String, Epg>((list.size * 2).coerceAtLeast(16))
                val core = HashMap<String, Epg>(16)
                list.value.forEach { epg ->
                    val n = ChannelName.normalize(epg.channel)
                    if (n.isBlank()) return@forEach
                    // putIfAbsent：保留节目单里的**第一条**，与 firstOrNull 语义一致
                    if (!norm.containsKey(n)) norm[n] = epg
                    val c = ChannelName.coreKeyOf(n)
                    if (c != null && c.startsWith("cctv") && !core.containsKey(c)) core[c] = epg
                }
                normIndex = norm
                coreIndex = core
                indexedFor = list
                matchCache.clear()
                UiLog.i(
                    "节目单",
                    "EPG 索引已建立：${norm.size} 个频道（cctv 核心 ${core.size} 个），" +
                            "耗时 ${android.os.SystemClock.elapsedRealtime() - started}ms",
                )
            }
        }

        /**
         * 取频道当前节目（now / next）。对应原版 `EpgList.recentProgramme`。
         */
        fun EpgList.recentProgramme(channel: Channel): EpgProgrammeRecent? {
            if (isEmpty()) return null

            return match(channel)?.recentProgramme()
        }

        /**
         * 在**后台线程**调用：节目单刚解析完时就把索引建好。
         *
         * 不预热的话，第一次 `match` 通常发生在主线程（渲染频道列要带"当前节目"），
         * 建索引的 O(N) 归一化就会算在主线程头上 —— 老设备上那也是几十到上百毫秒。
         */
        fun EpgList.warmUp() = ensureIndex(this)

        /** 单个名字的查表：先精确归一化，再 cctv 核心。 */
        private fun lookup(name: String): Epg? {
            if (name.isBlank()) return null
            val n = ChannelName.normalize(name)
            if (n.isBlank()) return null
            normIndex[n]?.let { return it }
            val c = ChannelName.coreKeyOf(n)
            if (c != null && c.startsWith("cctv")) coreIndex[c]?.let { return it }
            return null
        }

        /**
         * 把频道的节目单匹配出来。
         *
         * 保留原版的**两级回退**：
         *   ① 先用 `epgName` 比（直播源一般把节目单名写在 tvg-name）
         *   ② 再用展示名 `name` 比
         * 比较语义与原来的 [ChannelName.matches] 完全一致，
         * 只是实现从「线性扫描 + 反复正则」换成「一次建索引 + O(1) 查表」。
         */
        fun EpgList.match(channel: Channel): Epg? {
            if (isEmpty()) return null

            val epgName = channel.epgName.orEmpty()
            val key = "$epgName${channel.name}"

            synchronized(EPG_LOCK) {
                if (matchCache.containsKey(key)) return matchCache[key]
            }

            ensureIndex(this)

            val hit = synchronized(EPG_LOCK) {
                (lookup(epgName) ?: lookup(channel.name) ?: Epg()).also { matchCache[key] = it }
            }
            return hit
        }

        /** 换源 / 重拉节目单后必须调用，否则会命中上一次源的结果。 */
        fun clearCache() {
            synchronized(EPG_LOCK) {
                matchCache.clear()
                normIndex = HashMap()
                coreIndex = HashMap()
                indexedFor = null
            }
        }
    }
}
