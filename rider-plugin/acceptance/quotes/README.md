# 行情源验收

- `sina.raw`、`tencent.raw`、`fund-000001.raw` 和 `fund-161725.raw` 是 2026-11-26 12:21（UTC+8）本机取得的真实响应，均为 HTTP 200。`fund-name-*.raw` 来自天天基金的标的搜索，仅用于匹配基金名称。
- `kotlin-captured.tsv` 是 Rider 自带 JBR 运行生产解析器所得的同一批源数据。`python verify_captured.py` 独立核对九个标的的报价、涨跌幅、基准和时间，结果写入 `comparison.json`。
- `kotlin-live.tsv` 是 Rider 自带 JBR 通过生产 HTTP 客户端直接请求所得的快照。开始和结束时间在文件首行。该文件记录的是独立 JBR 进程的真实取数。
- `cycles.txt`、`overseas-cycle-1.raw` 和 `overseas-cycle-2.raw` 是 12:22 的两次真实上游响应。两品种的源时间及成交价均前进，其间 A 股处于午间休市。这两份响应记录的是直接请求行情源的证据。
- `*-empty.raw` 记录实际源的空值形式。新浪在混合批次中明确返回空字符串，腾讯在混合批次中省略未知代码，基金历史接口返回空净值数组。`ProviderTest` 另外用受控输入检查无买一、无基准、无披露涨幅和协议错误。
- `search-live.tsv` 记录 Rider 自带 JBR 通过生产搜索对 12 个查询进行的实际验证，涵盖 AO 和「氧化铝」、股票代码及名称、基金名称、拼音缩写和有歧义的六位代码。AO 本地候选来自随插件交付的期货目录，其他候选可由新浪、腾讯或天天基金的建议接口补充。

新浪和腾讯原始响应采用 GB18030 兼容编码，天天基金响应为 UTF-8。基准字段与原仓库的行情解析对照。基金净值取历史净值的 `DWJZ`、`FSRQ` 和 `JZZZL`。 [新浪行情解析](https://github.com/LeekHub/leek-fund/blob/425bc79484218064e4ecdb1dea272733befabbd4/src/explorer/stockService.ts#L333) · [腾讯行情解析](https://github.com/LeekHub/leek-fund/blob/425bc79484218064e4ecdb1dea272733befabbd4/src/shared/tencentStock.ts) · [天天基金历史净值](https://fundf10.eastmoney.com/jjjz_161725.html)
