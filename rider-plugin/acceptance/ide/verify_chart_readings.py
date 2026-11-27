"""Compare real Rider chart tooltips with the independent HTTP observations from the same run."""
import json
import re
import sys
from datetime import date
from decimal import Decimal
from itertools import groupby
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def decimal(value):
    return None if value in (None, "", "--", "-", "—") else Decimal(str(value))


def observations(note, folder):
    result = {}
    for request in note["wire"]:
        if request.get("status") != 200:
            continue
        url = urlparse(request["url"])
        args = {key: values[0] for key, values in parse_qs(url.query).items()}
        data = json.loads((folder / request["file"]).read_bytes())
        if "secid" in args:
            exchange, number = args["secid"].split(".")
            code = {"0": "sz", "1": "sh", "116": "hk"}[exchange] + number
            if number == "588200":
                code = number
            key = (code, {"101": "DAY", "102": "WEEK"}.get(args["klt"], "M" + args["klt"]))
            rows = [line.split(",")[:6] for line in (data.get("data") or {}).get("klines", [])]
        elif "FCODE" in args:
            key = (args["FCODE"], "DAY" if args["pageSize"] == "400" else "WEEK")
            rows = [[r["FSRQ"], None, r["DWJZ"], None, None, None] for r in data["Datas"]]
        else:
            foreign = "Global" in url.path
            code = ("hf_" if foreign else "nf_") + args["symbol"]
            key = (code, "M" + args["type"] if "type" in args else "DAY")
            fields = ("date open close high low volume" if foreign and key[1] == "DAY" else "d o c h l v").split()
            rows = [[r.get(f) for f in fields] for r in data]
        normalized = {row[0][:16]: tuple(decimal(value) for value in row[1:]) for row in rows if row[0]}
        if not normalized:
            continue
        bars = sorted(normalized.items())
        if key[0].startswith(("nf_", "hf_")) and key[1] == "DAY":
            result.setdefault((key[0], "WEEK"), []).append(weekly(bars))
        elif "FCODE" in args and key[1] == "WEEK":
            bars = weekly(bars)
        result.setdefault(key, []).append(bars)
    return result


def weekly(bars):
    weeks = []
    for _, items in groupby(bars, lambda bar: date.fromisoformat(bar[0][:10]).isocalendar()[:2]):
        group = list(items)
        stamp, last = group[-1]
        if last[0] is None:
            weeks.append((stamp, last))
            continue
        first, closes, highs, lows, volumes = zip(*(row for _, row in group))
        weeks.append((stamp, (first[0], closes[-1], max(highs), min(lows),
                              None if None in volumes else sum(volumes))))
    return weeks


def compare(tooltip, candidates):
    fields = tooltip.removeprefix("<html>").removesuffix("</html>").split("<br>")
    stamp = fields[0]
    text = " ".join(fields[1:])
    aliases = {"开盘": "开", "收盘": "收", "最高": "高", "最低": "低"}
    labels = {aliases.get(k, k): v for k, v in re.findall(
        r"(每万份收益|单位净值|开盘|收盘|最高|最低|开|收|高|低|成交量|MA\d+)\s+([\d.—-]+)", text)}
    assert labels, tooltip
    for bars in candidates:
        bars = bars[-400:]
        index = next((i for i, bar in enumerate(bars) if bar[0] == stamp), None)
        if index is None:
            continue
        values = bars[index][1]
        opening, close, high, low, _ = values
        invalid_range = opening is not None and (low > min(opening, close) or high < max(opening, close))
        if invalid_range != ("源开高低收不一致" in tooltip):
            continue
        expected = dict(zip(("开", "收", "高", "低", "成交量"), values)) if values[0] is not None else {
            next(label for label in ("单位净值", "每万份收益") if label in labels): values[1]}
        for key in labels:
            if key.startswith("MA"):
                period = int(key[2:])
                expected[key] = sum(bar[1][1] for bar in bars[index-period+1:index+1]) / period if index+1 >= period else None
        if labels.keys() == expected.keys() and all(
            (labels[k] == "—" if want is None else
             decimal(labels[k]) is not None and abs(decimal(labels[k]) - want) <= Decimal("0.000051"))
            for k, want in expected.items()
        ):
            return {"bar": stamp, "readings": labels}
    raise AssertionError(f"No independent observation matches {tooltip}")


def verify_inspection(evidence):
    note = json.loads((evidence / "chart-inspect.json").read_text("utf-8"))
    assert note["ok"], note.get("failure")
    folder = evidence / "wire-chart-inspect"
    history = [r for r in note["wire"] if urlparse(r["url"]).hostname in {
        "stock2.finance.sina.com.cn", "push2his.eastmoney.com", "fundmobapi.eastmoney.com"} and r.get("status") == 200]
    sources = observations({"wire": history}, folder)
    inspection = note["checks"]["inspection"]
    matches = {}
    for side in ("left", "right"):
        record = inspection[side]
        matches[side] = compare(record["tooltip"], sources["nf_AO2702", "M60"])
        fields = dict(item.split(" ", 1) for item in record["tooltip"][6:-7].split("<br>")[1:] if " " in item)
        # Confirm the per-bar changes and open interest independently of the Kotlin calculations.
        for request in history:
            params = parse_qs(urlparse(request["url"]).query)
            if params.get("symbol") != ["AO2702"] or params.get("type") != ["60"]:
                continue
            raw = json.loads((folder / request["file"]).read_bytes())
            bars = sorted({r["d"]: r for r in raw}.values(), key=lambda r: r["d"])
            index = next((i for i, r in enumerate(bars) if r["d"][:16] == record["date"]), None)
            if not index:
                continue
            bar, previous = bars[index], bars[index - 1]
            base, interest = decimal(previous["c"]), decimal(previous["p"])
            change = decimal(bar["c"]) - base
            delta = decimal(bar["p"]) - interest
            expected = {"涨跌": change, "涨幅": change * 100 / abs(base),
                        "振幅": (decimal(bar["h"]) - decimal(bar["l"])) * 100 / abs(base),
                        "持仓": decimal(bar["p"]), "较前柱增仓": delta, "增仓幅": delta * 100 / interest}
            if all(abs(decimal(fields[k].removesuffix("%")) - v) <= Decimal("0.005") for k, v in expected.items()):
                matches[side].update({k: fields[k] for k in expected})
                break
        else:
            raise AssertionError("Unmatched futures detail panel: " + record["tooltip"])
    for code in ("sh600000", "000001"):
        reading = note["checks"][code]["tooltip"]
        matches[code] = compare(reading, sources[code, "DAY"])
        if code == "sh600000":
            amount = decimal(re.search(r"成交额 ([\d.]+)", reading).group(1))
            stamp = matches[code]["bar"]
            assert any(decimal(row.split(",")[6]) == amount for request in history
                       if parse_qs(urlparse(request["url"]).query).get("secid") == ["1.600000"]
                       for row in json.loads((folder / request["file"]).read_bytes())["data"]["klines"]
                       if row.split(",")[0] == stamp)
            matches[code]["成交额"] = str(amount)
    output = {"version": note["plugin"]["version"], "rider": note["build"], "checkedAt": note["ended"],
              "details": matches, "polling": note["checks"]["polling"], "gestures": note["checks"]["gestures"]}
    (evidence / "chart-inspection.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Both docked panels, selected-bar MAs, traded amount, NAV and recorded polling intervals verified.")


def main():
    module = Path(__file__).resolve().parents[2]
    evidence = module / "build/acceptance"
    if sys.argv[1:] == ["--inspection"]:
        verify_inspection(evidence)
        return
    charts = json.loads((evidence / "charts.json").read_text("utf-8"))
    restart = json.loads((evidence / "charts-restart.json").read_text("utf-8"))
    assert charts["ok"] and restart["ok"]
    assert restart["checks"]["historyRecoveredWithSpotOffline"]
    assert charts["plugin"] == restart["plugin"] and charts["pid"] != restart["pid"]
    sources = observations(charts, evidence / "wire-charts")
    checked = {}
    for code, intervals in charts["checks"].items():
        if code == "gestures":
            continue
        if code in ("custom", "recovered"):
            checked[code] = compare(intervals["tooltip"], sources["nf_AO2702", "M120"])
        else:
            checked[code] = {period: compare(value["tooltip"], sources[code, period]) for period, value in intervals.items()}
    restored = restart["checks"]["restored"]
    checked["restart"] = compare(restored["tooltip"], observations(restart, evidence / "wire-charts-restart")["nf_AO2702", "M120"])
    for key in ("custom", "recovered", "restart"):
        assert {label for label in checked[key]["readings"] if label.startswith("MA")} == {"MA3", "MA8", "MA34"}
    count = sum(len(periods) for code, periods in checked.items() if code not in ("custom", "recovered", "restart"))
    output = {"version": charts["plugin"]["version"], "rider": charts["build"], "checkedAt": restart["ended"],
              "instrumentPeriodPairs": count, "historyRecoveredWithSpotOffline": True,
              "gestures": charts["checks"]["gestures"], "checks": checked}
    (evidence / "chart-readings.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{count} instrument/period pairs, custom MAs, offline recovery and restart matched the source records")


if __name__ == "__main__":
    main()
