package top.yogiczy.mytv.legacy.ui.settings

import androidx.annotation.DrawableRes
import top.yogiczy.mytv.legacy.R

/**
 * 设置页的分类（顺序固定）。
 *
 * 原样移植自 `SettingsCategories.kt`（MyTV 1.7，tag custom-v1.7），三处**用户授权**调整：
 *  1. v1.x 删除 `EPG_RESERVE("预约")`（13 → 12 项）；
 *  2. **v1.8 删除 `UPDATE("更新")`** —— 用户要求「更新入口放到应用里」，
 *     原「更新」分类的三项合并进 [APP]（见 `SettingsCats.app`）；
 *  3. **v1.8 删除 `MORE("推送")`** —— 用户明确「推送入口没啥用，去掉」
 *     （原版那一项只有"安装包管理"，本工程是侧载分发，本来就没这东西）。
 *
 * ⚠️ **图标是替代方案**：原版用的是 Compose Material Icons（ImageVector），
 * 本工程没有 material-icons 依赖，也不允许引入新依赖，因此改为手绘的
 * vector drawable。标题与顺序逐字保持原版。
 *
 * | 分类 | 原版图标 | 本工程替代 |
 * |---|---|---|
 * | 关于 | `Icons.Default.Info` | `ic_set_info`（圆环+i） |
 * | 应用 | `Icons.Default.Settings` | `ic_set_app`（齿轮） |
 * | 直播源 | `Icons.Default.LiveTv` | `ic_set_iptv`（屏+播放三角） |
 * | 节目单 | `Icons.Default.Menu` | `ic_set_epg`（三条横线） |
 * | 界面 | `Icons.Default.DisplaySettings` | `ic_set_ui`（屏+滑块） |
 * | 收藏 | `Icons.Default.Star` | `ic_set_favorite`（五角星） |
 * | 更新 | `Icons.Default.Update` | `ic_set_update`（循环箭头） |
 * | 播放器 | `Icons.Default.SmartDisplay` | `ic_set_player`（屏+三角） |
 * | 网络 | `Icons.Default.Http` | `ic_set_http`（地球） |
 * | 调试 | `Icons.Default.BugReport` | `ic_set_debug`（虫子） |
 * | 日志 | `Icons.Default.FormatListNumbered` | `ic_set_log`（列表+编号） |
 * | 推送 | `Icons.Default.CloudUpload` | `ic_set_more`（云+上箭头） |
 */
enum class SettingsCategory(
    val title: String,
    @DrawableRes val iconRes: Int,
) {
    ABOUT("关于", R.drawable.ic_set_info),
    APP("应用", R.drawable.ic_set_app),
    IPTV("直播源", R.drawable.ic_set_iptv),
    EPG("节目单", R.drawable.ic_set_epg),
    UI("界面", R.drawable.ic_set_ui),
    FAVORITE("收藏", R.drawable.ic_set_favorite),
    VIDEO_PLAYER("播放器", R.drawable.ic_set_player),
    HTTP("网络", R.drawable.ic_set_http),
    DEBUG("调试", R.drawable.ic_set_debug),
    LOG("日志", R.drawable.ic_set_log),
    ;

    companion object {
        val ALL: List<SettingsCategory> = values().toList()
    }
}
