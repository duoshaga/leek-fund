# 韭菜盒子 · Rider

LeekBox 1.0.7 在 Rider 侧栏中将自选分为 A 股、港股、基金、国内期货和海外期货。每行并排显示涨跌幅、价格和名称，选中项用粗体标识。已验证 Windows 版 Rider 2026.3.0.2（RD-263.7307.355）。

## 安装与使用

1. 在 Rider 按 `Ctrl+Alt+S`，打开 **Plugins → ⚙ → Manage Plugin Repositories…**，添加 `file:///J:/Tools/Leek/rider-plugin/dist/updatePlugins.xml`。如果工程在其他目录，请填写该目录中 [updatePlugins.xml](dist/updatePlugins.xml) 的文件 URL。
2. 在插件页搜索 **LeekBox**，从该仓库安装。已从磁盘安装的用户，在 **Installed → LeekBox → Additional Info → Updates from** 选择同一仓库，即可从插件页更新。[1.0.7 安装包](dist/leekbox-rider-1.0.7.zip) · [Rider 更新来源说明](https://www.jetbrains.com/help/rider/Managing_Plugins.html)
3. 从 **View → Tool Windows → 韭菜盒子** 打开右侧面板。输入 `AO` 或「氧化铝」，直接点击「氧化铝2702 · nf_AO2702」，也可用方向键选择后按回车。完整代码如 `nf_RB2701` 可直接回车添加。输入 `sh000001` 或「上证指数」可找到上证指数，输入 `000001` 可在上证指数、平安银行和华夏成长混合之间选择。
4. 选中一行，点图钉将其固定到状态栏。点击底部报价可以重新打开侧栏。圆形箭头刷新全部自选，减号删除选中项。
5. 上下拖动分组标题可调整五个分组的顺序。拖动标的到同组另一行的上半部或下半部，松开后放在该行之前或之后。刷新和重启 Rider 都会保留顺序。
6. 添加国内期货或打开已有自选时，插件会按品种从东方财富期货查询交易时段。悬停合约可查看北京时间的日盘、夜盘及来源。连续合约和不同月份共用查询结果，重启后也可使用缓存。
7. 要覆盖某个合约的自动安排，选中它并点击刷新箭头右侧的时钟，按北京时间输入，例如 `09:00-10:15, 10:30-11:30, 13:30-15:00, 21:00-01:00`。手填设置随自选保留，清空即可恢复自动安排。

首次使用时自选为空。在同一 Rider 实例的各项目窗口中，自选、固定项和报价共享。Rider 重启后会恢复自选、固定项和已缓存的源报价。

## 报价怎么看

| 分组 | 输入示例 | 价格与涨跌口径 |
| --- | --- | --- |
| A 股 | `sh000001`、`sh600000` | 包含沪深指数。按昨收计算涨跌幅，保留行情源报价时间 |
| 港股 | `hk00700` | 按昨收计算涨跌幅，保留行情源报价时间 |
| 基金 · 沪深场内 ETF | `588200`、`159915` | 显示「现价」、相对昨收的涨跌幅和交易所行情时间。输入 `sh588200` 和 `588200` 可找到同一条基金自选 |
| 基金 · 其他基金 | `000001`、`007300`、`161725` | 显示新浪的盘中「估值」、估算涨跌幅和估值时间，悬停可看上次公布净值。ETF 联接基金和以六位基金代码添加的 LOF 也使用估值 |
| 国内期货 | `nf_IF0`、`nf_RB0`、`nf_RB2701` | 股指期货按昨收，螺纹钢按昨结算。连续与指定月份各占一条自选 |
| 海外期货 | `hf_OIL`、`hf_CHA50CFD` | 新浪 CFD 指示报价按昨结算计算。有效成交价优先，回退到买一时，两处都显示「买一」 |

悬停可查看源时间、涨跌基准、今开、最高、最低及源端提供的成交量和成交额。基金缺少估值时，插件查询天天基金的最新披露，标明「净值」和净值日期。每万份收益型货币基金显示「每万份收益」。已有更新的估值会保留。若外盘成交价缺失，或成交价位于有效买卖区间之外，使用可用的买一价，并在提示中单列源成交价。缺失的价格或百分比显示「—」。行情源返回空记录时显示「暂无报价」。请求失败时保留上一笔价格及源时间。[新浪基金估值](https://money.finance.sina.com.cn/fund/view/vFR_Quotes.php?orderby=symbol&ordertype=ASC&page=1)

输入代码片段或中文名称时，面板先显示本地匹配，再补充在线候选。随包国内期货目录采集了 84 个品种、829 个代码；`AO0` 和实际挂牌月份可离线检索。[目录证据](acceptance/search/futures-directory.json) · [在线搜索样本](acceptance/quotes/search-live.tsv)

股票、场内 ETF 和期货在开市时每 25 秒刷新一次。其他基金在内地连续交易时段每分钟查询估值，晚间 18:00 起每小时查询，其余时段每六小时查询。国内期货的在线时段每 24 小时重新查询，失败会保留上次结果并在五分钟后重试。首次查询期间可使用已有内置安排。内地及 SGX A50 的交易日历覆盖 2026 年，港股覆盖至 2027 年底，ICE 布伦特覆盖至 2028 年 1 月 3 日。手填时段的国内期货按周一至周五及已收录的内地节假日轮询，超出日历范围时按周一至周五执行。[时段、节假日与交易日归属](acceptance/calendar/README.md)

## 构建

在此模块目录中使用 PowerShell 和目标 Rider 随附的 JBR 25。Gradle Wrapper 固定了 Gradle 版本和下载校验值。首次构建需要访问 Gradle、Maven 和 JetBrains 依赖仓库。

```powershell
$env:RIDER_HOME = 'C:\Program Files\JetBrains\JetBrains Rider 2026.3.0.2'
$env:JAVA_HOME = Join-Path $env:RIDER_HOME 'jbr'
.\gradlew.bat test buildPlugin verifyPlugin
```

构建产物在 `build/distributions/`。官方 Plugin Verifier 直接检查 `RIDER_HOME` 指向的本地 Rider。[1.0.7 行情验证结果](acceptance/ide/results/1.0.7/fund-quotes.json)

## 验收

- 在内地交易时段运行 `.\acceptance\ide\Run-RiderAcceptance.ps1 -Phase fund-quotes -Version 1.0.7 -Install` 可核对沪深 ETF 现价、混合基金与联接基金估值、货币基金收益和自动刷新。要复验旧自选升级，先运行 `-Phase fund-legacy -Version 1.0.6 -Install`，两次指定相同的 `-ProfileName`。[基金侧栏](acceptance/ide/results/1.0.7/fund-quotes.png)
- 在同一验收配置依次运行 `hours-legacy`（1.0.5）、`hours-discovery` 和 `hours-cache`（1.0.6），可核对旧自选升级、添加新品种、共用时段与重启恢复。[Rider 实测](acceptance/ide/results/1.0.6/hours-discovery.json)
- 在收录的内地交易日运行 `.\acceptance\ide\Run-RiderAcceptance.ps1 -Phase refresh-hours -Version 1.0.5 -Install` 可检查玻璃侧栏与自定义时段的连续自动请求。实例退出后，运行 `-Phase hours-restart -Version 1.0.5` 可复验保存与清空时段。[侧栏实测](acceptance/ide/results/1.0.5/refresh-hours-sidebar.png) · [时段编辑框](acceptance/ide/results/1.0.5/refresh-hours-editor.png)
- 在新的验收配置中运行 `.\acceptance\ide\Run-RiderAcceptance.ps1 -Phase watch-order -Version 1.0.4 -Install`。该实例退出后，使用同一配置运行 `-Phase order-restart -Version 1.0.4` 可核对保存的顺序。[拖动排序后的侧栏](acceptance/ide/results/1.0.4/watch-order.png)
- 运行 `.\acceptance\ide\Run-RiderAcceptance.ps1 -Phase index-search -Version 1.0.3 -Install` 可在原工程复验指数搜索、同码候选和添加后的报价。[Rider 侧栏实测](acceptance/ide/results/1.0.3/index-search.png)
- 运行 `.\acceptance\ide\Run-RiderAcceptance.ps1 -Phase appearance -Version 1.0.2 -Install` 可在原工程复验分组、选中和悬停时的行背景。[深色主题实机图](acceptance/ide/results/1.0.2/sidebar.png)
- 运行 `.\acceptance\ide\Run-RiderAcceptance.ps1 -Phase update-source -Version 1.0.1 -ProfileName rider-263-update-source -Install` 可检查旧版插件卡片和 Rider 从本地仓库下载的更新包。[更新来源实测](acceptance/ide/results/1.0.2/update-source.json)
- 原始响应与独立复算覆盖九个验收标的。[行情证据](acceptance/quotes/README.md)
- 固定时钟测试串联实际自选轮询与交易日判定，覆盖螺纹钢 23:00 收市、沪金跨零点和 02:30 收市。[时段依据](acceptance/calendar/README.md)
- 真实 Rider 验收打开本仓库及其中一个已有子目录，用独立配置从磁盘安装同一份 ZIP。验收脚本通过原生组件和 Rider 公开 API 操作两个窗口。[1.0.1 验收记录](acceptance/ide/results/1.0.1/README.md)
