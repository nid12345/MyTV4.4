package top.yogiczy.mytv.legacy.ui.qr

/**
 * 二维码（QR Code）编码器 —— 用于「手机扫码隔空填写」（v1.14 需求 4）。
 *
 * ---------------------------------------------------------------------------
 * 为什么要自己写而不是引库：
 *   本工程的构建是**完全离线**的（`--offline`），而本地 Gradle 依赖缓存里
 *   既没有 `com.google.zxing:core`，也没有任何其它二维码实现（已实测确认）。
 *   为一个"生成一张固定内容的二维码"去引入并下载依赖不划算，所以按标准
 *   ISO/IEC 18004 自己实现一份，只做**生成**、只做**字节模式**。
 *
 * 支持范围（刻意收窄，覆盖"扫码打开一个局域网网址"这个唯一用途）：
 *   · 编码模式：byte（UTF-8 原始字节）
 *   · 纠错等级：M（约 15% 冗余，是二维码最常见的档位）
 *   · 版本：1 ~ 6 自适应（容量 14 ~ 106 字节；局域网 URL 最长也就 50 上下）
 *   · 掩码：8 种全算，取惩罚分最低的一种（评分规则见 [penalty]）
 *
 * 实现参照 Nayuki 的 public-domain 参考实现（QR Code generator library）：
 * 它的结构最清晰、也最容易被逐段核对。位序、格式信息 BCH、掩码公式、
 * 四条惩罚规则与之一致。
 *
 * ⚠️ 正确性验证方式（本工程无法用扫码枪验）：
 *   用 Python 的 `qrcode` 库对同一段文本生成标准矩阵，与本类输出的矩阵**逐位比对**。
 *   见 `QrEncoderTest` 的打点（在设备上跑，把矩阵打到 logcat 再比对）。
 * ---------------------------------------------------------------------------
 */
object QrEncoder {

    /**
     * 编码结果：一个 [size] × [size] 的布尔矩阵，`true` = 黑块。
     *
     * [mask] 是最终选中的掩码号（0~7）。它只用于**验证**（拿来和参考实现对齐），
     * 以及排查"扫不出来"时确认掩码是否被选得反常 —— 解码端会自己从格式信息里读它，
     * 不需要调用方关心。
     */
    class Qr(val version: Int, val mask: Int, val size: Int, private val cells: BooleanArray) {

        /** 取某一格。越界一律返回 false（白）。 */
        operator fun get(x: Int, y: Int): Boolean =
            x in 0 until size && y in 0 until size && cells[y * size + x]

        /** 逐行 `0/1` 输出，仅用于调试比对。 */
        fun toDebugString(): String = buildString(size * (size + 1)) {
            for (y in 0 until size) {
                for (x in 0 until size) append(if (this@Qr[x, y]) '1' else '0')
                append('\n')
            }
        }
    }

    /** 支持的最高版本。版本 7 起要多一套"版本信息"图案与三组对齐图案，本用途用不到。 */
    private const val MAX_VERSION = 6

    /**
     * 纠错等级 M 的分块表：`[每块纠错码字数, 块数, 每块数据码字数]`，下标 = 版本 - 1。
     *
     * 三个数相乘/相加即可推出总数据码字数（v1:16、v2:28、v3:44、v4:64、v5:86、v6:108）。
     */
    private val EC_M = arrayOf(
        intArrayOf(10, 1, 16),   // v1  21×21
        intArrayOf(16, 1, 28),   // v2  25×25
        intArrayOf(26, 1, 44),   // v3  29×29
        intArrayOf(18, 2, 32),   // v4  33×33
        intArrayOf(24, 2, 43),   // v5  37×37
        intArrayOf(16, 4, 27),   // v6  41×41
    )

    /**
     * 对齐图案的中心坐标。
     *
     * v2 ~ v6 都只有 2 个候选值（含固定的 6），四个组合里除去与三个定位图案重叠的三个，
     * 实际只会画出**一个**对齐图案。
     */
    private val ALIGN = arrayOf(
        intArrayOf(),        // v1：无对齐图案
        intArrayOf(6, 18),   // v2
        intArrayOf(6, 22),   // v3
        intArrayOf(6, 26),   // v4
        intArrayOf(6, 30),   // v5
        intArrayOf(6, 34),   // v6
    )

    /** 掩码评分的四条规则的权重（ISO/IEC 18004 表 24）。 */
    private const val PENALTY_N1 = 3
    private const val PENALTY_N2 = 3
    private const val PENALTY_N3 = 40
    private const val PENALTY_N4 = 10

    /**
     * 编码。
     *
     * @throws IllegalArgumentException 内容超过版本 6 的容量时抛出
     */
    fun encode(text: String): Qr {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = chooseVersion(data.size)
        val ec = EC_M[version - 1]
        val ecPerBlock = ec[0]
        val blockCount = ec[1]
        val dataPerBlock = ec[2]
        val totalDataCodewords = blockCount * dataPerBlock

        // ---- 1. 位流：模式指示符 + 字符数 + 数据 ----
        val bits = BitBuffer()
        bits.append(0b0100, 4)              // byte 模式
        bits.append(data.size, 8)           // v1~v9 的字符数指示符是 8 位
        data.forEach { bits.append(it.toInt() and 0xFF, 8) }

        // 终止符：最多 4 个 0，但不能超出容量
        val capacityBits = totalDataCodewords * 8
        bits.append(0, minOf(4, capacityBits - bits.size))
        // 补齐到字节边界
        bits.append(0, (8 - bits.size % 8) % 8)
        // 填充字节：0xEC / 0x11 交替
        var pad = 0xEC
        while (bits.size < capacityBits) {
            bits.append(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        val codewords = bits.toBytes()

        // ---- 2. 分块 + Reed-Solomon 纠错 ----
        val dataBlocks = ArrayList<IntArray>(blockCount)
        val ecBlocks = ArrayList<IntArray>(blockCount)
        for (b in 0 until blockCount) {
            val from = b * dataPerBlock
            val block = codewords.copyOfRange(from, from + dataPerBlock)
            dataBlocks += block
            ecBlocks += ReedSolomon.encode(block, ecPerBlock)
        }

        // ---- 3. 交错：先按列取各数据块的同一位置，再按列取纠错块 ----
        val interleaved = IntArray(totalDataCodewords + blockCount * ecPerBlock)
        var k = 0
        for (i in 0 until dataPerBlock) {
            for (b in 0 until blockCount) interleaved[k++] = dataBlocks[b][i]
        }
        for (i in 0 until ecPerBlock) {
            for (b in 0 until blockCount) interleaved[k++] = ecBlocks[b][i]
        }

        return buildMatrix(version, interleaved)
    }

    /**
     * 选能装下 [byteCount] 字节的最小版本。
     *
     * byte 模式下每版本可用字符数 = 总数据码字数 - 2
     * （4 位模式指示符 + 8 位字符数 = 12 位，向上取整正好占掉 2 个字节）。
     */
    private fun chooseVersion(byteCount: Int): Int {
        for (v in 1..MAX_VERSION) {
            val dataCodewords = EC_M[v - 1][1] * EC_M[v - 1][2]
            if (byteCount <= dataCodewords - 2) return v
        }
        throw IllegalArgumentException(
            "内容过长（$byteCount 字节），本编码器最多支持 $MAX_VERSION 版本（106 字节）"
        )
    }

    // =========================================================================
    // 矩阵构造
    // =========================================================================

    private fun buildMatrix(version: Int, codewords: IntArray): Qr {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x !in 0 until size || y !in 0 until size) return
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        // ---- 定位图案（三个角） + 分隔符 ----
        // Chebyshev 距离 0/1 → 中心 3×3 黑；2 → 白圈；3 → 黑边；4 → 分隔符（白）
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(Math.abs(dx), Math.abs(dy))
                    setFunction(cx + dx, cy + dy, dist != 2 && dist != 4)
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // ---- 时序图案（第 6 行 / 第 6 列，黑白交替） ----
        for (i in 0 until size) {
            if (!isFunction[6][i]) setFunction(i, 6, i % 2 == 0)
            if (!isFunction[i][6]) setFunction(6, i, i % 2 == 0)
        }

        // ---- 对齐图案 ----
        val alignPositions = ALIGN[version - 1]
        for (cy in alignPositions) {
            for (cx in alignPositions) {
                if (isFunction[cy][cx]) continue          // 与定位图案重叠的位置跳过
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        setFunction(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
                    }
                }
            }
        }

        // ---- 格式信息（含暗模块），先写 0 号掩码占位，选完掩码再覆盖 ----
        fun drawFormatBits(mask: Int) {
            // 纠错等级 M = 0b00，拼上 3 位掩码号
            val data = (0b00 shl 3) or mask
            // BCH(15,5)：先算 10 位余数，再整体异或固定掩码 0x5412
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1

            // 第一份：左上角（绕过第 6 行/列）
            for (i in 0..5) setFunction(8, i, bit(i))
            setFunction(8, 7, bit(6))
            setFunction(8, 8, bit(7))
            setFunction(7, 8, bit(8))
            for (i in 9..14) setFunction(14 - i, 8, bit(i))
            // 第二份：右上 + 左下
            for (i in 0..7) setFunction(size - 1 - i, 8, bit(i))
            for (i in 8..14) setFunction(8, size - 15 + i, bit(i))
            // 固定的暗模块
            setFunction(8, size - 8, true)
        }
        drawFormatBits(0)

        // ---- 数据位：从右下角起，两列一组，Z 字形往返 ----
        val totalBits = codewords.size * 8
        var idx = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5                    // 跳过竖向时序图案那一列
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x]) {
                        var dark = false
                        if (idx < totalBits) {
                            dark = (codewords[idx ushr 3] ushr (7 - (idx and 7))) and 1 != 0
                        }
                        modules[y][x] = dark
                        idx++
                    }
                }
            }
            right -= 2
        }

        // ---- 8 种掩码各试一遍，取惩罚分最低的 ----
        fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (isFunction[y][x]) continue
                    if (maskBit(mask, x, y)) modules[y][x] = !modules[y][x]
                }
            }
        }

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (m in 0..7) {
            applyMask(m)
            drawFormatBits(m)
            val p = penalty(modules, size)
            if (p < bestPenalty) {
                bestPenalty = p
                bestMask = m
            }
            applyMask(m)                                  // 撤销，试下一种
        }
        applyMask(bestMask)
        drawFormatBits(bestMask)

        val cells = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) cells[y * size + x] = modules[y][x]
        }
        return Qr(version, bestMask, size, cells)
    }

    /** 掩码条件（ISO/IEC 18004 表 10）。[x] 是列、[y] 是行。 */
    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    /**
     * 四条惩罚规则的总分。分越低越"好扫"。
     *
     * ⚠️ 严格说掩码评分**不影响可解码性**（掩码号写在格式信息里，解码端会读出来还原），
     * 只影响扫描的鲁棒性与观感 —— 所以即使这里算错也能扫出来，但既然要实现就实现全。
     */
    private fun penalty(m: Array<BooleanArray>, size: Int): Int {
        var result = 0

        // 规则 1 & 3：行 / 列方向的同色连续段，以及"类定位图案"
        for (y in 0 until size) {
            var runColor = false
            var runLen = 0
            val history = IntArray(7)
            for (x in 0 until size) {
                if (m[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) result += PENALTY_N1
                    else if (runLen > 5) result++
                } else {
                    addRunHistory(history, runLen, size)
                    if (!runColor) result += countFinderPatterns(history) * PENALTY_N3
                    runColor = m[y][x]
                    runLen = 1
                }
            }
            result += terminateRun(runColor, runLen, history, size) * PENALTY_N3
        }
        for (x in 0 until size) {
            var runColor = false
            var runLen = 0
            val history = IntArray(7)
            for (y in 0 until size) {
                if (m[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) result += PENALTY_N1
                    else if (runLen > 5) result++
                } else {
                    addRunHistory(history, runLen, size)
                    if (!runColor) result += countFinderPatterns(history) * PENALTY_N3
                    runColor = m[y][x]
                    runLen = 1
                }
            }
            result += terminateRun(runColor, runLen, history, size) * PENALTY_N3
        }

        // 规则 2：2×2 同色块
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) {
                    result += PENALTY_N2
                }
            }
        }

        // 规则 4：黑白比例偏离 50% 的程度
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) if (m[y][x]) dark++
        val total = size * size
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * PENALTY_N4

        return result
    }

    /** 把一段长度推进"最近 7 段"的历史（首段要把外侧的静默区算进去）。 */
    private fun addRunHistory(history: IntArray, currentRunLength: Int, size: Int) {
        var len = currentRunLength
        if (history[0] == 0) len += size        // 首段：补上左侧静默区的宽度
        System.arraycopy(history, 0, history, 1, history.size - 1)
        history[0] = len
    }

    /** 在最近 7 段里数"1:1:3:1:1 且两侧各留 ≥4 个空白"的类定位图案。 */
    private fun countFinderPatterns(history: IntArray): Int {
        val n = history[1]
        val core = n > 0 && history[2] == n && history[3] == n * 3 &&
            history[4] == n && history[5] == n
        return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
            (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
    }

    /** 行/列扫完之后收尾：把最后一段与右侧静默区一起结算。 */
    private fun terminateRun(
        currentRunColor: Boolean,
        currentRunLength: Int,
        history: IntArray,
        size: Int,
    ): Int {
        var len = currentRunLength
        if (currentRunColor) {                  // 以深色段结尾 → 先把它记进去
            addRunHistory(history, len, size)
            len = 0
        }
        len += size                             // 再补右侧静默区
        addRunHistory(history, len, size)
        return countFinderPatterns(history)
    }

    // =========================================================================
    // 位缓冲
    // =========================================================================

    private class BitBuffer {
        private val bits = ArrayList<Boolean>(256)

        val size: Int get() = bits.size

        /** 追加 [length] 位（高位在前）。 */
        fun append(value: Int, length: Int) {
            if (length <= 0) return
            for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 == 1)
        }

        /** 按 8 位一组打包成字节（调用方保证已补齐到字节边界）。 */
        fun toBytes(): IntArray {
            val out = IntArray(bits.size / 8)
            for (i in bits.indices) {
                if (bits[i]) out[i / 8] = out[i / 8] or (0x80 ushr (i % 8))
            }
            return out
        }
    }

    // =========================================================================
    // Reed-Solomon（GF(256)，本原多项式 0x11D）
    // =========================================================================

    private object ReedSolomon {

        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            // 指数表翻倍长度，乘法里就不用取模了
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }

        private fun mul(a: Int, b: Int): Int =
            if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        /**
         * 生成 `degree` 次生成多项式的系数（**不含**最高次项，升幂排列）。
         *
         * 逐次乘上 `(x - α^i)`，i = 0..degree-1。
         */
        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = mul(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = mul(root, 0x02)
            }
            return result
        }

        /** 求 [data] 除以生成多项式的余数，即 [ecCount] 个纠错码字。 */
        fun encode(data: IntArray, ecCount: Int): IntArray {
            val div = divisor(ecCount)
            val result = IntArray(ecCount)
            for (b in data) {
                val factor = (b xor result[0]) and 0xFF
                System.arraycopy(result, 1, result, 0, ecCount - 1)
                result[ecCount - 1] = 0
                for (i in 0 until ecCount) result[i] = result[i] xor mul(div[i], factor)
            }
            return result
        }
    }
}
