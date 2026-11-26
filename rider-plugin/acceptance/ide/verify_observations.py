"""Compare Rider's values to independently fetched, timestamped source records."""
import argparse
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlparse


def expected(code, fields):
    if code == "hk00700":
        trade, basis, stamp = fields[3], fields[4], fields[30]
    elif code == "sh600000":
        trade, basis, stamp = fields[3], fields[2], fields[30] + " " + fields[31]
    elif code == "nf_IF0":
        trade, basis, stamp = fields[3], fields[13], fields[36] + " " + fields[37]
    elif code.startswith(("nf_RB", "nf_AO")):
        trade, basis = fields[8], fields[10]
        stamp = fields[17] + " " + datetime.strptime(fields[1], "%H%M%S").strftime("%H:%M:%S")
    elif code.startswith("hf_"):
        trade, basis, stamp = fields[0], fields[7], fields[12] + " " + fields[6]
    else:
        return None
    price = trade
    bid = False
    if code.startswith("hf_"):
        best_bid, best_ask = fields[2:4]
        if not Decimal(best_bid) <= Decimal(trade) <= Decimal(best_ask):
            price, bid = best_bid, True
    percent = ((Decimal(price) / Decimal(basis) - 1) * 100).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
    result = dict(price=price, percent=str(percent), time=stamp, basis=basis, rawTrade=trade, bid=bid)
    if code.startswith("nf_AO"):
        result["day"] = dict(open=fields[2], high=fields[3], low=fields[4], volume=fields[14], turnover=None)
    return result


def observations(note, folder):
    rows = []
    for item in note["wire"]:
        if item.get("status") != 200:
            continue
        study = folder / ("wire-" + note["phase"]) / item["file"]
        url = urlparse(item["url"])
        if url.hostname == "hq.sinajs.cn":
            source = study.read_text(encoding="gb18030")
            for code, data in re.findall(r'var\s+hq_str_(\w+)="([^"\r\n]*)"', source):
                if data:
                    value = expected(code, data.split(","))
                    if value:
                        rows.append((code, value, item))
        elif url.hostname == "qt.gtimg.cn":
            data = json.loads(study.read_text(encoding="gb18030")).get("r_hk00700")
            if data:
                rows.append(("hk00700", expected("hk00700", data), item))
        elif url.hostname == "api.fund.eastmoney.com":
            code = parse_qs(url.query)["fundCode"][0]
            data = json.loads(study.read_text(encoding="utf-8"))["Data"]["LSJZList"]
            if data:
                record = data[0]
                value = dict(price=record["DWJZ"], percent=record["JZZZL"], time=record["FSRQ"],
                             rawTrade=None, basis=None, bid=False)
                rows.append((code, value, item))
    return rows


def compare(code, quote, records):
    def equal(value, want, key):
        if key == "day":
            return bool(value) and all(equal(value[field], number, "price") for field, number in want.items())
        if key in ("price", "percent", "basis", "rawTrade") and value is not None and want is not None:
            return Decimal(value) == Decimal(want)
        return value == want

    matches = [event for ticker, data, event in records if ticker == code and
               all(equal(quote[key], want, key) for key, want in data.items())]
    receipt = datetime.fromisoformat(quote["received"])
    if not matches:
        return None
    nearest = min(matches, key=lambda event: abs((datetime.fromisoformat(event["at"]) - receipt).total_seconds()))
    return dict(code=code, sourceTime=quote["time"], price=quote["price"], percent=quote["percent"],
                basis=quote["basis"], rawTrade=quote["rawTrade"], bid=quote["bid"], observation=nearest["file"],
                observationOffsetSeconds=round((datetime.fromisoformat(nearest["at"]) - receipt).total_seconds(), 3))


def verify(file):
    note = json.loads(file.read_text(encoding="utf-8-sig"))
    assert note["ok"], note.get("failure")
    checks = note["checks"]
    records = observations(note, file.parent)
    output = {}
    if "nine" in checks:
        output["nine"] = {code: compare(code, row, records) for code, row in checks["nine"]["model"].items()}
    if "bid" in checks:
        case = checks["bid"]
        output["bid"] = compare(case["code"], case["quote"], records)
    if "cycles" in checks:
        output["cycles"] = {}
        for code, samples in checks["cycles"]["rounds"].items():
            matches = [compare(code, item["quote"], records) for item in samples]
            output["cycles"][code] = matches
    if "hiddenUpdates" in checks:
        output["monthly"] = [compare("nf_RB2701", sample["model"], records) for sample in checks["hiddenUpdates"]]
    if "oxide" in checks:
        output["oxide"] = {code: compare(code, value, records) for code, value in checks["oxide"]["rows"].items()}
    out = file.with_name(file.stem + "-comparison.json")
    out.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if "nine" in output:
        assert len(output["nine"]) == 9 and all(output["nine"].values()), output["nine"]
        assert output["bid"] is not None, "buy-one source tick did not match"
    if "cycles" in output:
        for code, matches in output["cycles"].items():
            assert sum(m is not None and abs(m["observationOffsetSeconds"]) < 10 for m in matches) >= 2, (code, matches)
    if "monthly" in output:
        assert any(output["monthly"]), "no independently confirmed monthly update"
    if "oxide" in output:
        assert len(output["oxide"]) == 2 and all(output["oxide"].values()), output["oxide"]
    return out


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", nargs="+", type=Path)
    for file in parser.parse_args().report:
        print(verify(file))
