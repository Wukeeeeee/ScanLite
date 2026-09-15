package com.scan.qr.scanner

/** 实时扫码的判定结果 */
sealed interface GateResult {
    /** 忽略，不改动界面 */
    data object Ignore : GateResult

    /** 清除多码浮层 */
    data object Clear : GateResult

    /** 稳定的单码 → 直接进结果页 */
    data class Single(val raw: String) : GateResult

    /** 多码 → 显示位置框与箭头 */
    data class Multi(val hits: List<BarcodeHit>, val contentWidth: Int, val contentHeight: Int) : GateResult
}

/**
 * 实时扫描节流 + 单/多码判定（纯逻辑，可单测）。
 *
 * 稳定性设计（避免多码浮层闪烁 / 抖动 / 误跳）：
 * - 单码：需连续稳定 [stableMs] 才进结果页，避免多码场景抖动时误跳。
 * - 多码首次出现：需稳定 [multiStableMs] 才显示浮层，避免镜头扫过时闪一下。
 * - **宽限期 [multiGraceMs]**：浮层显示期间，若某帧只识别到更少的码（或一帧没识别到），
 *   不立刻收起浮层，而是宽限一段时间——因为 ML Kit 逐帧结果本就不稳定，
 *   这是多码浮层“闪烁”的根因。宽限期内保持现状；宽限期后才真正收起，
 *   此时若只剩 1 个码，则回到单码流程（重新计时后进结果页）。
 * - 浮层刷新：**只有识别到的内容集合变化时才重画**；内容不变时最多每 [repositionMs]
 *   跟随一次位置（低频，足以跟手又不会每帧乱抖）。
 * - 画面无码：清除浮层。
 */
class HitGate(
    private val stableMs: Long = 450L,
    private val multiStableMs: Long = 200L,
    private val repositionMs: Long = 300L,
    private val multiGraceMs: Long = 600L
) {
    private var singleSince: Long? = null
    private var multiSince: Long? = null
    private var degradedSince: Long? = null
    private var lastReposition = 0L
    private var showingMulti = false
    private var lastKeys: List<String> = emptyList()

    fun onHits(
        hits: List<BarcodeHit>,
        now: Long,
        contentWidth: Int,
        contentHeight: Int
    ): GateResult {
        // ---- 浮层显示中：对“码数减少”加宽限，抗逐帧抖动 ----
        if (showingMulti && hits.size < lastKeys.size) {
            val since = degradedSince ?: now.also { degradedSince = it }
            if (now - since < multiGraceMs) {
                return GateResult.Ignore // 宽限期内保持浮层不动，避免闪烁
            }
            degradedSince = null
            if (hits.size >= 2) {
                return emitMulti(hits, now, contentWidth, contentHeight)
            }
            // 宽限期后仍不足 2 个码：收起浮层，回到单码流程
            showingMulti = false
            lastKeys = emptyList()
            multiSince = null
            singleSince = if (hits.size == 1) now else null
            return GateResult.Clear
        }
        degradedSince = null

        if (hits.isEmpty()) {
            singleSince = null
            multiSince = null
            return if (showingMulti) {
                showingMulti = false
                lastKeys = emptyList()
                GateResult.Clear
            } else {
                GateResult.Ignore
            }
        }

        if (hits.size == 1) {
            multiSince = null
            if (showingMulti) {
                // 从多码变回单码：先清浮层，再重新计时
                showingMulti = false
                lastKeys = emptyList()
                singleSince = now
                return GateResult.Clear
            }
            val since = singleSince
            if (since == null) {
                singleSince = now
                return GateResult.Ignore
            }
            if (now - since < stableMs) return GateResult.Ignore
            return GateResult.Single(hits.first().raw)
        }

        // ---- 多码（>= 2）----
        singleSince = null
        val keys = hits.map { it.raw }.sorted()

        if (!showingMulti) {
            val since = multiSince
            if (since == null) {
                multiSince = now
                return GateResult.Ignore
            }
            if (now - since < multiStableMs) return GateResult.Ignore
            return emitMulti(hits, now, contentWidth, contentHeight)
        }

        // 已在显示浮层：内容变化立即更新，否则只低频跟随位置
        if (keys != lastKeys) {
            return emitMulti(hits, now, contentWidth, contentHeight)
        }
        if (now - lastReposition >= repositionMs) {
            return emitMulti(hits, now, contentWidth, contentHeight)
        }
        return GateResult.Ignore
    }

    private fun emitMulti(
        hits: List<BarcodeHit>,
        now: Long,
        contentWidth: Int,
        contentHeight: Int
    ): GateResult.Multi {
        showingMulti = true
        degradedSince = null
        lastKeys = hits.map { it.raw }.sorted()
        lastReposition = now
        return GateResult.Multi(hits, contentWidth, contentHeight)
    }

    fun reset() {
        singleSince = null
        multiSince = null
        degradedSince = null
        lastReposition = 0L
        showingMulti = false
        lastKeys = emptyList()
    }
}
