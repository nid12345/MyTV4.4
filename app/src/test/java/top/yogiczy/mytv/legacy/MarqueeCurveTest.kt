package top.yogiczy.mytv.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.legacy.ui.ChannelCardView

/**
 * v1.22 需求 3：标题跑马灯的进度曲线。
 *
 * 需求原话：「标题太长的话，当光标在的位置，标题要横向循环跑马灯的形式展现」。
 * 曲线必须满足三件事，否则长标题在电视上根本读不完：
 *   ① 开头停一拍（看清从哪开始滚）；
 *   ② 结尾停一拍（读完最后几个字再回到起点）；
 *   ③ 中间匀速（不要忽快忽慢）。
 */
class MarqueeCurveTest {

    /** 与 [ChannelCardView] 里一致的停顿占比。 */
    private val hold = 0.18f

    @Test
    fun 端点与边界值() {
        assertEquals(0f, ChannelCardView.marqueeFraction(0f, hold, hold), 1e-6f)
        assertEquals(1f, ChannelCardView.marqueeFraction(1f, hold, hold), 1e-6f)
        // 超出范围也要收敛到端点（ValueAnimator 不会越界，但别把逻辑写脆）
        assertEquals(0f, ChannelCardView.marqueeFraction(-0.5f, hold, hold), 1e-6f)
        assertEquals(1f, ChannelCardView.marqueeFraction(1.5f, hold, hold), 1e-6f)
    }

    @Test
    fun 首尾各停一拍() {
        // 开头 hold 占比内必须完全不动
        assertEquals(0f, ChannelCardView.marqueeFraction(hold * 0.5f, hold, hold), 1e-6f)
        assertEquals(0f, ChannelCardView.marqueeFraction(hold, hold, hold), 1e-6f)
        // 结尾 hold 占比内必须停在终点
        assertEquals(1f, ChannelCardView.marqueeFraction(1f - hold * 0.5f, hold, hold), 1e-6f)
        assertEquals(1f, ChannelCardView.marqueeFraction(1f - hold, hold, hold), 1e-6f)
        // 停顿与滚动之间不能有跳变：hold 时刻紧接着的进度应接近 0
        val afterHold = ChannelCardView.marqueeFraction(hold + 0.001f, hold, hold)
        assertTrue("停顿结束后进度应接近 0，实际 $afterHold", afterHold < 0.05f)
    }

    @Test
    fun 中间匀速() {
        // 在滚动段内等间隔取样，进度增量应相等（LinearInterpolator 的等价物）
        val span = 1f - 2 * hold
        val n = 10
        var prev = 0f
        for (i in 1..n) {
            val t = hold + span * i / n
            val v = ChannelCardView.marqueeFraction(t, hold, hold)
            val expect = i.toFloat() / n
            assertEquals("t=$t 处进度应线性映射到 $expect", expect, v, 1e-4f)
            assertTrue("进度必须单调不减", v >= prev - 1e-6f)
            prev = v
        }
    }

    @Test
    fun 无停顿时退化为匀速() {
        // holdStart = holdEnd = 0 → 纯线性
        for (i in 0..10) {
            val t = i / 10f
            assertEquals(t, ChannelCardView.marqueeFraction(t, 0f, 0f), 1e-6f)
        }
    }

    @Test
    fun 停顿吃满整个周期也不能除零崩溃() {
        // holdStart + holdEnd >= 1：整个周期都在"停"，不能崩、不能出现 NaN
        val v = ChannelCardView.marqueeFraction(0.5f, 0.6f, 0.6f)
        assertTrue("不能出现 NaN", !v.isNaN())
        assertEquals(0f, v, 1e-6f)
        assertEquals(1f, ChannelCardView.marqueeFraction(0.9f, 0.6f, 0.6f), 1e-6f)
    }
}
