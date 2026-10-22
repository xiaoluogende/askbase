#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
多轮对话评估：对比「带历史」和「不带历史」

--------------------------------------------------------------------------
为什么必须做对照：
  只看"带历史能答对几题"没有意义——有些追问不带历史也能蒙对
  （比如"只看互联网行业"，模型可能直接猜出要过滤 industry）。
  真正要证明的是：**同一批追问，带上下文比不带强多少**。

方法：
  每条用例先按顺序跑 context 里的前置问题（真实调用，拿到 SQL），
  然后用两种方式跑同一个追问：
    A. 带历史（把前面几轮的问题 + SQL 传给服务端）
    B. 不带历史（只传追问本身，等价于单轮）
  两种方式都和 gold SQL 的结果集比对。

用法：
    python eval_multiturn.py
    python eval_multiturn.py --verbose
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eval_askbase import http_json, extract_rows, rows_equal  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def ask(base, token, question, history=None):
    payload = {"question": question}
    if history:
        payload["history"] = history
    resp = http_json("POST", f"{base}/api/query", payload, token)
    if resp["code"] != 0:
        raise RuntimeError(resp["msg"])
    return resp["data"]


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
    except Exception as e:
        print(f"连不上服务：{e}")
        return 2
    token = login["data"]["token"]

    cases = [json.loads(l) for l in
             open(os.path.join(HERE, "multiturn_set.jsonl"), encoding="utf-8") if l.strip()]

    with_ctx_ok = without_ctx_ok = errored = 0
    total = 0

    for c in cases:
        print(f"\n[{c['id']}] 追问：{c['question']}")

        # ① 跑前置轮次，累积历史
        history = []
        try:
            for q in c["context"]:
                d = ask(args.base, token, q, history if history else None)
                history.append({"question": q, "sql": d["sql"]})
                print(f"    前置：{q}  →  {len(d['sql'].split())} 个词")
        except Exception as e:
            print(f"    ! 前置轮次失败：{e}")
            errored += 1
            total += 1
            continue

        # ② gold 结果
        gold = http_json("POST", f"{args.base}/api/query/execute",
                         {"sql": c["gold_sql"]}, token)
        if gold["code"] != 0:
            print(f"    ! gold 执行失败：{gold['msg'][:80]}")
            errored += 1
            total += 1
            continue
        gold_rows = extract_rows(gold["data"]["rows"])

        total += 1
        results = {}

        for mode, hist in (("带历史", history), ("不带历史", None)):
            try:
                d = ask(args.base, token, c["question"], hist)
                ok = rows_equal(extract_rows(d["rows"]), gold_rows)
                results[mode] = ok
                if ok:
                    if mode == "带历史":
                        with_ctx_ok += 1
                    else:
                        without_ctx_ok += 1
                mark = "✓" if ok else "✗"
                print(f"    {mark} {mode}：{d['rowCount']} 行 | {d['totalMs']}ms")
                if args.verbose and not ok:
                    print(f"        SQL: {' '.join(d['sql'].split())[:180]}")
            except Exception as e:
                results[mode] = False
                print(f"    ✗ {mode}：{str(e)[:80]}")

    print("\n" + "=" * 60)
    print(f"合计 {total} 条追问")
    print(f"  带历史   ：{with_ctx_ok}/{total}  = {with_ctx_ok / total:.0%}")
    print(f"  不带历史 ：{without_ctx_ok}/{total}  = {without_ctx_ok / total:.0%}")
    if errored:
        print(f"  （其中 {errored} 条因执行失败未计入对比）")
    diff = with_ctx_ok - without_ctx_ok
    print(f"\n多轮上下文带来的净提升：{diff:+d} 题"
          f"（{(diff / total * 100):+.0f} 个百分点）")
    print()
    print("说明：不带历史那一列是 baseline。如果两者差不多，")
    print("      说明这批追问不够依赖上下文，应该换更省略的问法。")

    return 0


if __name__ == "__main__":
    sys.exit(main())
