package top.yogiczy.mytv.legacy.data

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

// 原版这两个函数定义在 `Epg.Companion` / `EpgProgramme.Companion` 里，
// 由使用方（原版是另一个文件 EpgList.kt）显式 import 才在作用域内。
// 本工程把实体合并进同一个文件后，同样需要这条 import 才能在 EpgList.Companion 里调用。
import top.yogiczy.mytv.legacy.data.Epg.Companion.recentProgramme

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
        /**
         * 频道名 → 匹配结果 的 LRU 缓存（accessOrder=true，超 1024 条淘汰最久未用）。
         * 逐字保留原版实现，包括 `null` 也可入缓存（避免对匹配不上的频道反复重算）。
         */
        private val matchCache =
            object : LinkedHashMap<String, Epg?>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Epg?>?): Boolean {
                    return size > 1024
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
         * 把频道的节目单匹配出来。
         *
         * 逐字保留原版的**两级回退**：
         *   ① 先用 `epgName` 比（直播源一般把节目单名写在 tvg-name）
         *   ② 再用展示名 `name` 比
         * 比较一律走 [ChannelName.matches]（`CCTV-1` ↔ `CCTV1` 的归一化在此生效）。
         */
        fun EpgList.match(channel: Channel): Epg? {
            if (isEmpty()) return null

            return matchCache.getOrPut(channel.epgName.orEmpty()) {
                // 直播源与节目单的频道命名常不一致（CCTV-1 / CCTV1），做归一化比较
                firstOrNull { epg -> ChannelName.matches(epg.channel, channel.epgName.orEmpty()) }
                    ?: firstOrNull { epg -> ChannelName.matches(epg.channel, channel.name) }
                    ?: Epg()
            }
        }

        /** 换源 / 重拉节目单后必须调用，否则会命中上一次源的结果。 */
        fun clearCache() {
            matchCache.clear()
        }
    }
}
