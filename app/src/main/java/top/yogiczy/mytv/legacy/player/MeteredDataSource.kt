package top.yogiczy.mytv.legacy.player

import android.net.Uri
import android.os.SystemClock
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener

/**
 * 播放链路的**实时下载速率**统计。
 *
 * 背景（v1.7，用户反馈"网速要实时网速的显示"）：之前 `PlayerController.speedKBps`
 * 用的是 ExoPlayer 的带宽估计（`DefaultBandwidthMeter.bitrateEstimate`），那是**滑动
 * 平均的估值** —— 播放平稳时读数长期不动，缓冲充满后也不会归零，跟"当前网速"是两回事。
 *
 * 这里改为**真实测速**：在播放器的 DataSource 外面包一层 [MeteredDataSource]，
 * 每次 `read()` 读到多少字节就往这里累加；查询速率时取**最近 2 秒窗口**的字节差。
 * 缓冲充满（直播切片暂停下载）时读数自然归零，暂停时也是 0 —— 都是真实状态。
 *
 * 线程约定：`read()` 跑在播放器内部线程，`recentBytesPerSec()` 跑在 UI 线程；
 * 内部用 synchronized 保护。
 */
object NetMeter {

    private const val WINDOW_MS = 2000L
    /** 采样点最小间隔：太密会让队列膨胀，太疏会让短窗口失真。 */
    private const val SAMPLE_INTERVAL_MS = 200L

    private class Point(val at: Long, val bytes: Long)

    private val points = ArrayDeque<Point>()
    private var totalBytes = 0L

    /** 播放线程调用：本次 read 到了 [n] 个字节。 */
    fun add(n: Int) {
        if (n <= 0) return
        synchronized(this) {
            totalBytes += n
            val now = SystemClock.elapsedRealtime()
            val last = points.lastOrNull()
            if (last == null || now - last.at >= SAMPLE_INTERVAL_MS) {
                points.addLast(Point(now, totalBytes))
                // 只保留窗口内的点，防队列无限增长
                while (points.size > 2 && now - points.first().at > WINDOW_MS + 1_000L) {
                    points.removeFirst()
                }
            }
        }
    }

    /**
     * 最近 [WINDOW_MS] 毫秒的真实下载速率（字节/秒）。
     *
     * 窗口里没有采样（一直没有数据下载，比如暂停 / 缓冲充满）时返回 0。
     */
    fun recentBytesPerSec(): Long {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            while (points.size > 1 && now - points.first().at > WINDOW_MS) {
                points.removeFirst()
            }
            val first = points.firstOrNull() ?: return 0L
            val last = points.last()
            val dt = last.at - first.at
            if (dt <= 0) return 0L
            val bytes = last.bytes - first.bytes
            if (bytes <= 0) return 0L
            return bytes * 1000L / dt
        }
    }

    /** 换线路 / 换频道时调用：清空窗口，避免把上一条线路的读数带进新线路。 */
    fun reset() {
        synchronized(this) {
            points.clear()
            points.addLast(Point(SystemClock.elapsedRealtime(), totalBytes))
        }
    }

    /**
     * 累计读到的总字节数（自进程启动起，不随窗口滑动清零）。
     *
     * v1.9 用途：播放卡死看门狗 —— 记下 prepare 时的值，过一段时间再比一次，
     * **一个字节都没涨**才判定为真卡死（只靠 playbackState 会把"慢但在下数据"误判成卡死）。
     */
    fun total(): Long = synchronized(this) { totalBytes }
}

/**
 * 包装播放器的 [DataSource]，把每次 read 的字节数喂给 [NetMeter]。
 *
 * 清单、分片、渐进式流全部走同一个 DataSource 工厂，所以这一个包装点
 * 就覆盖了播放链路的全部下载量。
 */
class MeteredDataSource(private val inner: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        inner.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = inner.open(dataSpec)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = inner.read(buffer, offset, length)
        if (n > 0) NetMeter.add(n)
        return n
    }

    override fun close() = inner.close()

    override fun getUri(): Uri? = inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
}
