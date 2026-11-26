from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import csv
import json
import re


ROOT = Path(__file__).resolve().parent


def sina(name):
    body = (ROOT / name).read_text(encoding="gb18030")
    return {
        code: fields.split(",")
        for code, fields in re.findall(r'var hq_str_(\w+)="([^"\r\n]*)";', body)
    }


def read_json(name, encoding="utf-8"):
    return json.loads((ROOT / name).read_text(encoding=encoding))


def market(code, name, price, prior, time, basis_label, source_trade=None, bid_fallback=False):
    percent = (Decimal(price) / Decimal(prior) * 100 - 100).quantize(
        Decimal("0.01"), rounding=ROUND_HALF_UP
    )
    return {
        "code": code,
        "name": name,
        "price": price,
        "percent": str(percent),
        "sourceTime": time,
        "basisLabel": basis_label,
        "basis": prior,
        "rawTrade": price if source_trade is None else source_trade,
        "bidFallback": str(bid_fallback).lower(),
    }


def overseas(snapshot):
    records = []
    for code in ("hf_OIL", "hf_CHA50CFD"):
        row = snapshot[code]
        trade, bid, ask = (Decimal(row[index]) for index in (0, 2, 3))
        assert bid <= ask
        use_bid = not bid <= trade <= ask
        quote = market(code, row[13], row[2] if use_bid else row[0], row[7], f"{row[12]} {row[6]}", "昨结算价", row[0], use_bid)
        records.append({**quote, "bid": row[2], "ask": row[3]})
    return records


def fund(code):
    data = read_json(f"fund-{code}.raw")["Data"]["LSJZList"][0]
    names = read_json(f"fund-name-{code}.raw")["Datas"]
    name = next(item["NAME"] for item in names if (item.get("FundBaseInfo") or {}).get("FCODE") == code)
    return {
        "code": code,
        "name": name,
        "price": data["DWJZ"],
        "percent": data["JZZZL"],
        "sourceTime": data["FSRQ"],
        "basisLabel": "披露日涨跌幅",
        "basis": "",
        "rawTrade": "",
        "bidFallback": "false",
    }


def main():
    snapshot = sina("sina.raw")
    a = snapshot["sh600000"]
    hk = read_json("tencent.raw", "gb18030")["r_hk00700"]
    index = snapshot["nf_IF0"]
    expected = [
        market("sh600000", a[0], a[3], a[2], f"{a[30]} {a[31]}", "昨收"),
        market("hk00700", hk[1], hk[3], hk[4], hk[30], "昨收"),
        fund("000001"),
        fund("161725"),
        market("nf_IF0", index[49], index[3], index[13], f"{index[36]} {index[37]}", "昨收"),
    ]
    for code in ("nf_RB0", "nf_RB2701"):
        row = snapshot[code]
        local = datetime.strptime(row[1], "%H%M%S").time().isoformat()
        expected.append(market(code, row[0], row[8], row[10], f"{row[17]} {local}", "昨结算价"))
    initial_overseas = overseas(snapshot)
    expected.extend({key: value for key, value in item.items() if key not in {"bid", "ask"}} for item in initial_overseas)
    with (ROOT / "kotlin-captured.tsv").open(encoding="utf-8-sig") as file:
        next(file)
        actual = list(csv.DictReader(file, delimiter="\t"))
    checks = [{"code": row["code"], "matched": row == observed} for row, observed in zip(expected, actual, strict=True)]

    cycles = [overseas(sina(f"overseas-cycle-{number}.raw")) for number in (1, 2)]
    for previous, current in zip(*cycles, strict=True):
        assert current["sourceTime"] > previous["sourceTime"]
        assert current["rawTrade"] != previous["rawTrade"]

    report = {
        "description": "Independent calculation from captured live response bytes, compared with Kotlin decoding of the same bytes",
        "checks": checks,
        "expected": expected,
        "initialOverseasBidAsk": initial_overseas,
        "liveSourceCycleStartTimes": (ROOT / "cycles.txt").read_text(encoding="utf-8-sig").splitlines(),
        "liveSourceCycles": cycles,
    }
    (ROOT / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert all(item["matched"] for item in checks), checks
    print(f"{len(checks)} of {len(expected)} captured market quotes matched; both overseas quotes moved across two live source samples")


if __name__ == "__main__":
    main()
