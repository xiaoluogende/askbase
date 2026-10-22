#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Askbase 端到端评估：Execution Accuracy

--------------------------------------------------------------------------
和 verify_gold.py 的区别：
  verify_gold.py     验证「gold SQL 本身写得对不对」（不含模型）
  本脚本              验证「模型生成的 SQL 结果对不对」——这才是真正的准确率

评估口径（EA，Execution Accuracy）：
  把模型生成的 SQL 和 gold SQL 分别执行，比较**结果集**是否等价。
  比较时忽略列顺序和列名，只比行内容的多重集合。

  为什么不用「SQL 字符串完全一致」（Exact Match）：
  同一个问题可以有很多种正确写法。`AVG(x)` 和 `SUM(x)/COUNT(x)` 结果一样，
  但字符串不同。业务关心的是答案对不对，不是写法像不像。

用法：
    python eval_askbase.py
    python eval_askbase.py --base http://localhost:8080 --user admin --password admin123
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def http_json(method, url, payload=None, token=None, timeout=180):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def normalize_value(v):
    """保留数值类型（用于带容差比较），其余转字符串"""
    if v is None:
        return None
    if isinstance(v, bool):
        return str(v)
    if isinstance(v, (int, float)):
        return float(v)
    return str(v).strip()


def extract_rows(rows):
    """把 API 返回的行转成「每行一个值列表」"""
    return [[normalize_value(v) for v in row.values()] for row in rows]


def values_equal(a, b, tol):
    if a is None or b is None:
        return a is None and b is None
    if isinstance(a, float) and isinstance(b, float):
        # 相对容差 + 绝对下限：3.173 和 3.1731234 应该算同一个答案
        return abs(a - b) <= max(tol, abs(a) * tol)
    return str(a) == str(b)


def multiset_match(a_vals, b_vals, tol):
    """忽略列顺序比较两行的值"""
    if len(a_vals) != len(b_vals):
        return False
    remaining = list(b_vals)
    for x in a_vals:
        for i, y in enumerate(remaining):
            if values_equal(x, y, tol):
                remaining.pop(i)
                break
        else:
            return False
    return True


def rows_equal(model_rows, gold_rows, tol=0.01):
    """
    忽略行序和列序比较两个结果集，数值带 1% 容差。

    ⚠️ 这个函数曾经用「格式化成 4 位小数字符串再比」的写法，导致
    gold 写了 ROUND(...,3) 而模型没写 ROUND 时被判错（3.1730 vs 3.1731）。
    把精度差异当成答案错误，会系统性地低估准确率。
    """
    if len(model_rows) != len(gold_rows):
        return False
    remaining = list(gold_rows)
    for mr in model_rows:
        for i, gr in enumerate(remaining):
            if multiset_match(mr, gr, tol):
                remaining.pop(i)
                break
        else:
            return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin123")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    try:
        login = http_json("POST", f"{args.base}/api/auth/login",
                          {"username": args.user, "password": args.password})
    except urllib.error.URLError as e:
        print(f"连不上服务：{e}")
        print("提示：先启动应用 —— java -jar target/askbase-0.1.0-SNAPSHOT.jar --server.port=8080")
        return 2
    token = login["data"]["token"]

    cases = [json.loads(l) for l in
             open(os.path.join(HERE, "golden_set.jsonl"), encoding="utf-8") if l.strip()]

    passed = failed = errored = 0
    corrected = 0        # 靠纠错才成功的题数
    rounds_total = 0     # 总轮次，用于算平均每题的模型调用次数
    details = []

    for c in cases:
        q = c["question"]
        try:
            # ① gold SQL 的真实结果
            gold = http_json("POST", f"{args.base}/api/query/execute",
                             {"sql": c["gold_sql"]}, token)
            gold_rows = extract_rows(gold["data"]["rows"]) if gold["code"] == 0 else None

            # ② 模型端到端的结果
            ask = http_json("POST", f"{args.base}/api/query", {"question": q}, token)
            if ask["code"] != 0:
                errored += 1
                print(f"  [{c['id']:>2}] ✗ 生成失败  {q}")
                print(f"        {ask['msg'][:120]}")
                details.append((c["id"], q, "error", ask["msg"][:120]))
                continue

            model_rows = extract_rows(ask["data"]["rows"])
            rounds = len(ask["data"].get("attempts") or [])
            rounds_total += rounds
            if rounds > 1:
                corrected += 1

            if gold_rows is None:
                print(f"  [{c['id']:>2}] ? gold 执行失败，跳过  {q}")
                continue

            round_tag = f" ({rounds}轮)" if rounds > 1 else ""
            if rows_equal(model_rows, gold_rows):
                passed += 1
                print(f"  [{c['id']:>2}] ✓ EA  {ask['data']['totalMs']:>5}ms  "
                      f"{ask['data']['rowCount']} 行{round_tag}  {q}")
            else:
                failed += 1
                print(f"  [{c['id']:>2}] ✗ 结果不一致  {q}{round_tag}")
                print(f"        模型 {len(model_rows)} 行 / gold {len(gold_rows)} 行")
                if args.verbose:
                    print(f"        模型 SQL: {' '.join(ask['data']['sql'].split())[:200]}")
                    print(f"        召回表:   {ask['data']['recalledTables']}")
                    for a in (ask["data"].get("attempts") or []):
                        note = "成功" if a.get("failure") is None else a["failure"][:80]
                        print(f"        第{a['index']}轮 {a.get('rowCount')} 行：{note}")
                details.append((c["id"], q, "mismatch",
                                f"模型 {len(model_rows)} 行 vs gold {len(gold_rows)} 行"))
        except Exception as e:
            errored += 1
            print(f"  [{c['id']:>2}] ! 异常  {q}  -> {e}")
            details.append((c["id"], q, "exception", str(e)[:120]))

    total = passed + failed + errored
    print()
    print(f"合计 {total} 条：EA 通过 {passed}，结果不一致 {failed}，生成失败 {errored}")
    if total:
        print(f"Execution Accuracy = {passed / total:.1%}")
    if rounds_total:
        print(f"纠错情况：{corrected} 条触发重试，平均 {rounds_total / total:.2f} 轮/题")
    print()
    print("说明：这个数字就是简历上要写的那个。换模型、改 prompt、调召回策略之后")
    print("      重跑本脚本，数字的变化就是你的优化效果。")
    print()
    print("提示：如果「触发重试」很多但 EA 没涨，说明模型在重复犯同样的错——")
    print("      该改的是 prompt 里的口径，而不是加大重试次数。")

    return 0 if failed == 0 and errored == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
