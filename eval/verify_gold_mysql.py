#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gold SQL 在真实 MySQL 上的验证
--------------------------------------------------------------------------
和 verify_gold.py 的区别：
  verify_gold.py      用 SQLite 内存库，快，适合本地随手改随手验
  verify_gold_mysql.py 连真实 MySQL，慢，但能发现方言差异

为什么两个都要：
  SQLite 只做「语法与语义自检」。MySQL 和 SQLite 在窗口函数、聚合、
  类型隐式转换上有真实差异——比如在窗口函数的 ORDER BY 里用聚合函数，
  SQLite 允许，MySQL 可能报 "Invalid use of group function"。
  评测集的 gold SQL 必须在项目实际使用的数据库上跑通，否则你测出来的
  准确率是假的。

用法：
    python verify_gold_mysql.py
    python verify_gold_mysql.py --host 127.0.0.1 --port 3307 --user askbase --password askbase123 --db askbase
"""

import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def run_sql(args, sql):
    """执行一条 SQL，返回 (是否成功, 输出或错误信息)

    注意：SQL 走 stdin 传，不用 -e 参数。
    Windows 上把含中文的字符串作为命令行参数传给原生程序会被转成 ANSI 代码页，
    中文字面量就废了（表现为 WHERE 匹配不上）。用 stdin + UTF-8 字节最稳。
    """
    cmd = [
        "mysql",
        "-h", args.host,
        "-P", str(args.port),
        "-u", args.user,
        f"-p{args.password}",
        "--default-character-set=utf8mb4",
        "-N", "-B",
        args.db,
    ]
    try:
        p = subprocess.run(cmd, input=sql.encode("utf-8"),
                           capture_output=True, timeout=30)
    except FileNotFoundError:
        return False, "找不到 mysql 客户端，请确认已在 PATH 中"
    except subprocess.TimeoutExpired:
        return False, "执行超时（>30s）"

    out = p.stdout.decode("utf-8", errors="replace").strip()
    err = p.stderr.decode("utf-8", errors="replace").strip()
    if p.returncode != 0:
        # mysql 会把 "Using a password on the command line" 之类的警告写进 stderr，
        # 真正的错误才带 ERROR 前缀，这里只保留后者
        lines = [l for l in err.splitlines() if "ERROR" in l or "error" in l]
        return False, "\n".join(lines) or err
    return True, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "3307")))
    ap.add_argument("--user", default=os.getenv("DB_USER", "askbase"))
    ap.add_argument("--password", default=os.getenv("DB_PASSWORD", "askbase123"))
    ap.add_argument("--db", default=os.getenv("DB_NAME", "askbase"))
    args = ap.parse_args()

    ok0, msg = run_sql(args, "SELECT 1")
    if not ok0:
        print(f"连不上数据库：{msg}")
        print("提示：先确认容器已起 —— docker compose --profile mysql up -d")
        return 2

    cases = [json.loads(l) for l in
             open(os.path.join(HERE, "golden_set.jsonl"), encoding="utf-8") if l.strip()]

    ok = fail = empty = 0
    for c in cases:
        success, out = run_sql(args, c["gold_sql"])
        if not success:
            fail += 1
            print(f"  [{c['id']:>2}] 报错  {c['question']}")
            print(f"        {out[:300]}")
        elif not out:
            empty += 1
            print(f"  [{c['id']:>2}] 空结果  {c['question']}")
        else:
            ok += 1
            lines = out.splitlines()
            preview = lines[0][:100]
            print(f"  [{c['id']:>2}] OK  {len(lines)} 行  {preview}")

    print()
    print(f"合计 {len(cases)} 条：通过 {ok}，空结果 {empty}，报错 {fail}")
    if fail:
        print("报错通常是方言差异（SQLite 能跑 MySQL 不能），需要改 gold SQL")
    return 0 if fail == 0 and empty == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
