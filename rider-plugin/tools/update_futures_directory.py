"""Refresh the small bundled futures lookup from Sina's public contract lists."""
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
import json
from pathlib import Path
import re
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


NODES_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/view/js/qihuohangqing.js"
LIST_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQFuturesData"
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/leek/domestic-futures.txt"


def read_url(url):
    request = Request(url, headers={"Referer": "https://finance.sina.com.cn/"})
    for attempt in range(3):
        try:
            with urlopen(request, timeout=15) as response:
                return response.read()
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def list_contracts(node):
    rows = []
    page = 1
    while True:
        args = urlencode(dict(node=node, page=page, num=100, sort="symbol", asc=1))
        result = json.loads(read_url(f"{LIST_URL}?{args}").decode("gb18030"))
        if not isinstance(result, list):
            raise ValueError(f"Unexpected response for {node}, page {page}")
        rows.extend(result)
        if len(result) < 100:
            return rows
        page += 1


def main():
    # Only parse the product-tree declaration, excluding commented-out exchanges.
    source = read_url(NODES_URL).decode("gb18030").split("};", 1)[0]
    source = re.sub(r"(?m)^\s*//.*$", "", source)
    nodes = {node: name for name, node in re.findall(r"\[\s*'([^']+)'\s*,\s*'(\w+_qh)'", source)}
    # Listed in the quote service but missing from the site's navigation tree.
    nodes["tl_qh"] = "30年期国债期货"
    with ThreadPoolExecutor(max_workers=5) as executor:
        responses = dict(zip(nodes, executor.map(list_contracts, nodes)))

    # Dormant products can remain in the API for years. Keep recently quoted contracts only.
    dated = [(date.fromisoformat(row["tradedate"]), row["symbol"], nodes[node])
             for node, rows in responses.items() for row in rows
             if re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(row.get("tradedate")))]
    as_of = max(record[0] for record in dated)
    families = defaultdict(set)
    for trade_date, code, product in dated:
        match = re.fullmatch(r"([A-Z]+)(0|\d{4})", code)
        if match and trade_date >= as_of - timedelta(days=7):
            root, month = match.groups()
            if month == "0" or month >= as_of.strftime("%y%m"):
                families[root, product].add(month)

    lines = [f"# Sina contract directory / source trade date {as_of}",
             "# product code|product name|listed contract suffixes (0 = continuous)"]
    for (root, product), months in sorted(families.items()):
        lines.append(f"{root}|{product}|{' '.join(sorted(months))}")
    text = "\n".join(lines) + "\n"
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".tmp")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(OUTPUT)
    print(f"{as_of}: {len(nodes)} nodes, {len(families)} products, {sum(map(len, families.values()))} contracts")


if __name__ == "__main__":
    main()
