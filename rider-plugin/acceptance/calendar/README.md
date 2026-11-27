# 第一阶段刷新日历

核对日期为 2026-11-26。下表中的时刻均为交易地时间，时段含开始时刻，到终止时刻停止周期轮询。刷新间隔指一次请求完成至下次请求最少等待的时间。

| 行情代码 | 自动刷新条件 | 交易日归属与覆盖 |
| --- | --- | --- |
| 沪、深、京 A 股 | 集合竞价 09:15–09:25，09:30–11:30、13:00–15:00。科创板及创业板另有 15:05–15:30 盘后固定价格交易。沪深主板从 2026-07-06 起加入同一盘后时段 | 内地 2026 交易日。北交所按已实施的竞价交易时段 |
| 港股 | 09:00–12:00、13:00–16:10。半日市 09:00–12:10。尾段覆盖收市竞价的随机收市窗口 | 香港 2026–2027 证券市场交易日 |
| `nf_IF*` | 中金所 09:30–11:30、13:00–15:00 | 当天，内地 2026 交易日 |
| `nf_RB*`、`nf_AO*`、`nf_AU*` | 上期所日盘 09:00–10:15、10:30–11:30、13:30–15:00。夜盘 21:00 开始，螺纹钢 23:00、氧化铝次日 01:00、沪金次日 02:30 停止 | 夜盘归下一个交易日，包括周五夜盘归下周一。节前最后一个工作日晚和节后首日凌晨无夜盘 |
| `nf_FG*` | 郑商所玻璃连续及指定月份合约。日盘 09:00–10:15、10:30–11:30、13:30–15:00，夜盘 21:00–23:00 | 日盘归当天，夜盘归下一交易日，跳过节前夜盘 |
| 国内期货在线查询 | 用交易所和品种的在线目录取得合约资料中的交易时间。同品种的连续和月份合约共享常规时段。碳酸锂日盘分为 09:00–10:15、10:30–11:30、13:30–15:00，白银夜盘到次日 02:30 | 使用内地交易日历，跨午夜部分归入开始夜盘的同一交易日 |
| `hf_OIL` | 新浪布伦特原油 CFD，以 ICE 为参考。纽约周日 18:00 开周一盘，其他日从前一晚 20:00 开盘，通常当日 18:00 收。ICE 提前收市采用伦敦时间 | 已核实 ICE 交易日至 2028-01-03。夏令时错位周的维护窗口跟随纽约当地时钟 |
| `hf_CHA50CFD` | 新浪富时中国 A50 期货 CFD，以 SGX 为参考。日盘 09:00–16:35，晚盘 17:00–次日 05:15。SGX 晚盘从 16:45 开始，新浪分时配置与实测分时从 17:00 开始 | 日盘归当天。晚盘归下一交易日，周五晚至周六凌晨归下周一。2026 年度股指衍生品公告仅列 1 月 1 日全日休市，内地和新加坡其他工作日假期仍可交易 |
| 六位基金代码 | 天天基金已公布净值，北京时间 18:00–24:00 每小时查一次，其余每六小时查一次。周末保留低频查询以捕捉延迟披露 | 时间表是插件的查询策略。份额净值日期、日涨跌幅均取返回记录，不用系统日期推算 |

除基金外，开市时每 25 秒请求一次。无匹配时段的合约、尚未核实的年度、交界处无法确定的下一交易日都可手动查询。沪深、北交所与期货的临时停牌或交易所临时通告需要以新公告更新日历。即使交易所处于开市窗口，行情提供方也可能延迟或缺少成交。插件显示源记录时间。

国内期货可对单个自选合约指定北京时间刷新时段。每段含起点、在终点停止。跨午夜写作一段，例如 `21:00-01:00`。跨午夜或从 21:00 起的时段按夜盘处理，起始日和下一交易日都须开市。自定义时段跳过周末和已收录的节假日，日历范围以外按周一至周五运行。

## 依据

- 东方财富期货公布各交易所品种的日盘、夜盘及盘中休息安排。插件查询其国内交易所目录、品种目录和合约资料，对照返回的品种代码，读取交易时间。商品合约资料中合并显示的上午时段会扣除 10:15–10:30 的休息。[国内交易所目录](https://futsse-static.eastmoney.com/redis?msgid=gnweb) · [碳酸锂合约资料](https://futsse-static.eastmoney.com/redis?msgid=225_lc_info) · [东方财富期货交易时间](https://qhweb.eastmoney.com/tradinghours) · [广期所碳酸锂合约与交易小节](https://www.gfex.com.cn/gfex/tslcpjt/202608/8ac6f2a81b754582876d921cafa64e20/files/af7181a46b4f4d91a50abe2c306151e9.pdf)
- 郑商所的玻璃期货业务细则第八条列出日盘休息和夜盘收市时间。适用于 2711 及后续合约的修订沿用相同时段。[玻璃业务细则](https://www.czce.com.cn/cn/uploadfile/2024/02/07/20240207103949576.pdf) · [2711 及后续合约细则](https://www.czce.com.cn/cn/content_file/flfg/zcjywgz/pzxz/2026/11/dc5ca295ad8e44d9b28c44a055675142.pdf)
- 沪深股票、北交所竞价窗口、盘后实施日期与休市日。[上交所交易规则](https://www.sse.com.cn/lawandrules/sselawsrules2025/stocks/exchange/c/c_20260424_10816482.shtml) · [上交所年度休市](https://www.sse.com.cn/disclosure/dealinstruc/closed/) · [深交所交易规则](https://docs.static.szse.cn/www/lawrules/rule/trade/W020260424690713155663.pdf) · [北交所交易规则](https://www.bse.cn/jygl_list/200028217.html) · [北交所分期实施公告](https://www.bse.cn/jygl_list/200029608.html)
- 港股收市竞价覆盖随机收市的最后两分钟。2026 年的半日市是 2 月 16 日、12 月 24 日及 12 月 31 日，2027 年的是 2 月 5 日、12 月 24 日及 12 月 31 日。[港交所证券交易时间](https://www.hkex.com.hk/Services/Trading-hours-and-Severe-Weather-Arrangements/Trading-Hours/Securities-Market?sc_lang=en) · [2026 证券日历](https://www.hkex.com.hk/-/media/HKEX-Market/Services/Circulars-and-Notices/Participant-and-Members-Circulars/SEHK/2025/ce_SEHK_CT_075_2025.pdf) · [2027 证券日历](https://www.hkex.com.hk/-/media/HKEX-Market/Services/Circulars-and-Notices/Participant-and-Members-Circulars/SEHK/2026/ce_SEHK_CT_077_2026.pdf)
- 上期所夜盘表及节前无夜盘公告。氧化铝上市通知和现行品种时间表均明确其 21:00–次日 01:00 的交易时段。中金所 IF 采用日盘连续交易时段。[上期所各品种交易时间](https://www.shfe.com.cn/services/calenderandholidays/tradinghours/) · [氧化铝期货上市通知](https://www.shfe.com.cn/publicnotice/notice/202306/t20230606_800293.html) · [上期所 2026 休市公告](https://www.shfe.com.cn/services/calenderandholidays/holiday/) · [中金所 IF 合约](http://www.cffex.com.cn/hs300/)
- 新浪布伦特报价页标记为 CFD。ICE 的 2026 感恩节及次日，布伦特分别于伦敦时间 18:30 和 20:00 收市。圣诞前夕为 19:00，除夕为 20:00。[新浪 OIL](https://finance.sina.com.cn/futures/quotes/OIL.shtml) · [ICE Brent 合约](https://www.ice.com/products/219/Brent-Crude-Futures) · [ICE 年度假期与代码说明 B/C/D](https://www.ice.com/publicdocs/Trading_Schedule.pdf) · [夏令时错位周](https://www.ice.com/publicdocs/futures/Trading_Schedule_Temporary_Trading_Hours_for_DST.pdf) · [圣诞元旦特别通告](https://www.ice.com/publicdocs/futures/IFEu_Christmas_New_Year_Trading_Hours_2026.pdf)
- ICE 2026 年 11 月版年度安排附录 2 第 13 页将 2027-12-24 布伦特期货的交易终止时间列为 13:00 GMT。第 14 页列出 12-27 和 12-28 的例外产品，布伦特正常交易。第 15 页列出 12-31 布伦特期货 20:00 GMT 收市。第 16 页的 2028-01-03 为正常交易日，例外列表没有布伦特。测试对照期货合约所在表格行及收市一列。[ICE 附录 2](https://www.ice.com/publicdocs/Trading_Schedule.pdf#page=13)
- 新浪返回的交易所标识分别为 ICE 和 SGX。SGX 将白天预收市和不可撤单阶段列至 16:35，年度股指衍生品日历与新加坡现金股票日历各自独立。[新浪 A50](https://finance.sina.com.cn/futures/quotes/CHA50CFD.shtml) · [SGX A50 合约](https://www.sgx.com/derivatives/products/chinaA50) · [SGX 2026 衍生品假期公告](https://api2.sgx.com/sites/default/files/2025-12/DTAM%20109%20of%202025%20-%20SGX-DT%20Derivatives%20Trading%20Holiday%20Schedule%20for%20Year%202026.pdf)
- 基金管理人以开放日净值为基础进行披露。插件在晚间加密查询，每次仅使用已公布的记录。[招商中证白酒披露规则](https://static.cninfo.com.cn/finalpage/2020-09-09/1208412594.PDF) · [样本历史净值](https://fundf10.eastmoney.com/jjjz_161725.html)

## 证据类型

| 实测资料 | 内容 |
| --- | --- |
| [新浪分时配置](sina-hours.json) | 2026-11-26 实际请求两个源代码返回的交易所、季节字段和分时时段。SGX 没有夏令时，调度始终采用新加坡时区 |
| [新浪原始分时节选](sina-session-samples.json) | A50 的 11 月 26 日分时序列首笔标注前一日 17:00，覆盖前一日 23:59、当天 00:00、05:14、09:00。布伦特 11 月 23 日周一首笔为 07:00，24 至 26 日首笔均为 09:00。表内时间来自接口 |
| [固定时钟测试](../../src/test/kotlin/leek/SessionTest.kt) | 以明确的 UTC+8 时刻回放三种上期所夜盘、假期前后、港股半日市、A50 延迟开盘、布伦特感恩节提前收盘、夏令时与未核实合约 |
| [新浪氧化铝批量报价](https://hq.sinajs.cn/list=nf_AO0,nf_AO2702) | 2026-11-26 13:32:58 实测。连续合约最新价 2553、昨结 2552；指定 2702 合约最新价 2559、昨结 2558。涨跌幅各为 +0.04%。链接返回最新数据 |

上述分时节选证明行情源在这些时间提供了记录。Rider 插件运行时的自动轮询由独立的 IDE 实测记录验证。
