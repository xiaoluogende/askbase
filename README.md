# Askbase

**用自然语言查询数据库的分析 Agent。**

问一句中文，自动召回相关的表、生成 SQL、通过安全校验、执行查询，返回结果表格和建议的图表类型。

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.8-6DB33F)
![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## 目录

- [功能](#功能)
- [快速开始](#快速开始)
- [使用示例](#使用示例)
- [架构](#架构)
- [技术栈](#技术栈)
- [评估结果](#评估结果)
- [项目进度](#项目进度)
- [目录结构](#目录结构)
- [关键设计决策](#关键设计决策)
- [已知限制](#已知限制)

---

## 功能

**核心链路**

- 自然语言 → SQL，支持多表 JOIN、聚合、子查询、窗口函数
- 表结构自动采集：表/字段注释、枚举取值、外键关系、样例行
- 三种召回策略可切换：关键词（无需 API Key）/ 向量 / 混合
- 结构化输出：SQL、自然语言解释、建议图表类型
- **自纠错**：执行报错或结果为空时，把原始错误回喂模型重试；每一轮的 SQL 和失败原因都记录在响应里

**安全**

- SQL 必须通过四层校验：单条语句、必须是 SELECT（AST 判定而非字符串匹配）、危险函数黑名单、系统库拦截
- 执行走独立只读账号，数据库层面无法写入——即使应用层校验全部失效
- 5 秒查询超时、1000 行结果上限、超限标记 `truncated`

**工程**

- 统一响应体、全局异常处理、JWT 认证、OpenAPI 文档
- 端到端 Execution Accuracy 评估脚本
- Docker Compose 一键启动，GitHub Actions 持续集成
- 49 个单元测试

---

## 快速开始

### 前置要求

| 依赖 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Docker | 任意近期版本 |
| Docker Compose | v2+ |

### 启动

```bash
git clone https://github.com/<your-name>/askbase.git
cd askbase
cp .env.example .env

# 起 MySQL(3307) + Redis(6379)
# 首次启动会自动建 13 张表并灌入演示数据，约 40 秒
docker compose --profile mysql up -d

# 启动应用
mvn spring-boot:run
```

打开 **http://localhost:8080/swagger-ui.html**，用 `admin / admin123` 登录。

> MySQL 容器映射到 **3307** 而不是 3306，避免和本机已有的 MySQL 冲突。

### 配置模型（可选）

在 `.env` 里填入：

```bash
DEEPSEEK_API_KEY=sk-xxx      # 生成 SQL
DASHSCOPE_API_KEY=sk-xxx     # 表结构向量化
```

| 配置情况 | 行为 |
|---|---|
| 两个 Key 都填 | 混合召回 + 端到端问答，全部功能可用 |
| 只填 DEEPSEEK | 关键词召回 + 端到端问答 |
| 都不填 | 可浏览表结构与调试召回，问答接口提示未配置模型 |

**没配 Key 应用也能正常启动**——模型 Bean 按条件创建，缺配置只是少一个能力。

---

## 使用示例

### 端到端问答

```bash
# 登录拿 token
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .data.token)

# 提问
curl -s -X POST localhost:8080/api/query \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"question":"各专业平均薪资是多少"}'
```

返回：

```json
{
  "code": 0,
  "data": {
    "recalledTables": ["job_posting", "major", "employment_stat", "offer"],
    "sql": "SELECT m.`name` AS `专业名称`, ROUND(AVG(o.`monthly_salary`), 2) AS `平均薪资` FROM `offer` o JOIN `student` s ON o.`student_id` = s.`id` JOIN `major` m ON s.`major_id` = m.`id` GROUP BY m.`name`",
    "explanation": "按专业分组统计已签约 offer 的平均月薪",
    "chartType": "bar",
    "columns": ["专业名称", "平均薪资"],
    "rows": [
      {"专业名称": "软件工程", "平均薪资": 13868.83},
      {"专业名称": "计算机科学与技术", "平均薪资": 13126.92},
      {"专业名称": "通信工程", "平均薪资": 11155.26}
    ],
    "rowCount": 6,
    "truncated": false,
    "modelMs": 1356,
    "totalMs": 1700,
    "promptTokens": 1535,
    "completionTokens": 178
  }
}
```

### 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/query` | 端到端问答 |
| POST | `/api/query/validate` | 只校验 SQL，不执行 |
| POST | `/api/query/execute` | 执行给定的 SQL |
| GET | `/api/schema/tables` | 列出已索引的表 |
| GET | `/api/schema/tables/{name}` | 查看单表的完整描述 |
| GET | `/api/schema/search?q=` | 查看表召回结果 |
| GET | `/api/schema/prompt?q=` | **预览将要发给模型的 schema 文本** |
| POST | `/api/schema/reindex` | 重建表结构索引 |
| GET | `/api/health`、`/api/health/db` | 健康检查（含数据库连通性） |

完整可执行请求见 [`docs/api-tests.http`](docs/api-tests.http)（IDEA HTTP Client 格式，点绿三角直接发，token 自动传递）。

---

## 架构

```
                    ┌─────────────────────┐
                    │  客户端 / Swagger UI │
                    └──────────┬──────────┘
                               │ JWT
┌──────────────────────────────▼──────────────────────────────────┐
│  Spring Boot 3.5 (Java 21)                                      │
│                                                                 │
│  QueryService ── ① 表召回 ──► SchemaRetriever                   │
│       │                       ├ keyword  (bigram + IDF)         │
│       │                       ├ vector   (embedding 余弦)       │
│       │                       └ hybrid   (加权融合)             │
│       │                                                         │
│       ├── ② 生成 ──► SqlGenerator (Spring AI → DeepSeek)        │
│       │                                                         │
│       ├── ③ 校验 ──► SqlValidator (JSqlParser AST)              │
│       │              单语句 / 必须 SELECT / 危险函数 / 系统库    │
│       │                                                         │
│       └── ④ 执行 ──► SqlExecutor (只读连接池)                   │
│                      5s 超时 · 1000 行上限 · 截断标记           │
└───────┬──────────────┬──────────────┬───────────────────────────┘
        │              │              │
   ┌────▼─────┐  ┌─────▼──────┐  ┌───▼──────────────┐
   │ MySQL 8.4│  │ Redis Stack│  │ DashScope        │
   │ 业务库    │  │ 文档+向量   │  │ Embedding API    │
   │ 只读账号  │  │ 索引缓存    │  │ (仅建索引时调用) │
   └──────────┘  └────────────┘  └──────────────────┘
```

---

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| JDK | Java 21 | LTS |
| 框架 | Spring Boot 3.5.16 | 见[设计决策](#关键设计决策) |
| AI | Spring AI 1.1.8 | `ChatClient` + `EmbeddingModel`，手工装配双模型 |
| 大模型 | DeepSeek `deepseek-chat` | SQL 生成，约 ¥1/百万 token |
| Embedding | 阿里云百炼 `text-embedding-v3` | 1024 维 |
| 数据库 | MySQL 8.4.11 | 容器运行，映射 3307 |
| 向量存储 | Redis Stack 7.4 | 文档与向量持久化 |
| ORM | MyBatis-Plus 3.5.17 | |
| SQL 解析 | JSqlParser 5.4 | AST 级校验 |
| 接口文档 | springdoc 2.8.17 | Swagger UI |
| 构建 | Maven（单模块） | |
| 部署 | Docker Compose + GitHub Actions | |

---

## 评估结果

内置 **50 条**评测用例（[`eval/golden_set.jsonl`](eval/golden_set.jsonl)），每条都带 gold SQL。

| 维度 | 分布 |
|---|---|
| 难度 | 简单 18 / 中等 22 / 困难 10 |
| 涉及表数 | 单表 21 / 两表 14 / 三表 11 / 四表 4 |
| 覆盖能力 | 聚合、分组、HAVING、JOIN、子查询、窗口函数、条件聚合 |

```bash
cd eval && python eval_askbase.py
```

**Execution Accuracy = 92%（46/50）**，实测区间 90–94%（同一配置多轮运行的波动来自模型 API 的偶发失败）。
单题耗时 1.0–2.2 秒，约 1500 输入 token + 180 输出 token。

### 准确率的演进

| 阶段 | 改动 | 题量 | EA |
|---|---|---|---|
| 初始 | gold 含诊断列 + 召回 topK=4 | 10 | 10% |
| 修评测集 | gold 只返回答案列 + topK=6 | 10 | 50% |
| **修评估脚本** | 浮点比较加容差 | 10 | **70%** |
| 修 gold | Q5 的 avg_score 口径写错 | 10 | **80%** |
| 扩充评测集 | 10 → 50 条 | 50 | 84% |
| 修题目歧义 | 5 处表述不严谨 | 50 | **92%** |

**教训一：优化之前先怀疑尺子。** 前三次"提升"（10%→80%）全部来自修评估本身，
没有一行代码改动模型行为。三个 bug 合计让准确率被低估 30 个百分点。

**教训二：扩充评测集之后，新题目的歧义立刻暴露。** 从 10 条扩到 50 条时，
有 5 道题因为表述不严谨被判错，模型其实答的是另一种合理口径：

| 题 | 歧义 |
|---|---|
| 「招收最多」 | 岗位条数 vs 招聘人数 |
| 「多少人被录用」 | 记录数 72 vs 去重人数 63（应按人算） |
| 「上市企业占比」 | 返回 0.55 还是 55.00？模型还额外返回了分子分母 |
| 「招聘人数最多的岗位」 | 单个岗位的最大值 vs 按岗位名汇总（8 个岗位并列 25 人，Top-3 本身不稳定） |
| 「哪个渠道录用率最高」 | 只返回第一名 vs 返回全部渠道 |

**这些不是模型错，是题目没写清楚。** 评测集的表述质量直接决定指标可信度。

### 剩余 3 条稳定失败

| 题 | 症状 | 根因 |
|---|---|---|
| 各专业就业率排名 | 返回 0 行 | `employment_stat` 只有学院级数据（16 行 `major_id` 全为 NULL），JOIN 专业表必然为空 |
| 生源地陕西留陕就业比例 | 模型拒答 | 数据里没有"学生就业地点"字段，只能用企业总部城市近似。**模型拒绝了一个需要假设的口径，比测试集更严谨** |
| 上市企业占比 | 返回 3 列而非 1 列 | 模型多返回了上市数和企业总数。数值 55.0 是对的，属于"答对了但多给了信息" |

前两条是**数据模型缺口**，第三条是**输出形状**问题——都不是"模型不会写 SQL"。

> 试过加 prompt 规则"只 SELECT 问题所问的列"，想修掉第三条。结果模型把本该展示的列
> 也删了（"每个城市薪资最高的岗位"只返回岗位名不返回城市），EA 从 88% 掉回 84%。
> **约束"少返回"的代价大于容忍"多返回"**，已回滚。

### 消融实验：两个功能最后都被关掉了

用同一套 50 条评测集做对照：

| 组 | 口径规则 | 自纠错 | EA |
|---|---|---|---|
| A | 关 | 关 | 基准 |
| D | 关 | 开 | 与 A 持平 |
| E | 1 条 | 关 | 低于 A |

- **自纠错零收益**：它修不了"SQL 执行成功但语义错"这类失败——没有信号可以回喂
- **口径规则负收益**：规则占用 prompt 注意力，模型开始幻觉字段名（实测出现 `o.job_posting_id`）

所以最终配置：口径层默认关闭（机制保留，可开对照），自纠错保留（对测试集之外的新问题有价值）。

### 关于可复现性

同一配置连续跑两次，逐题结果基本一致；偶有单题因模型 API 瞬时失败而翻转，
对 50 条的影响约 ±2 个百分点。所以上表 2 个百分点以内的差异不应被当成真实效应——
**这也是为什么扩充评测集比微调 prompt 更值得做**。

---

## 项目进度

按功能完整度划分。

| 模块 | 状态 |
|---|---|
| 工程骨架（认证 / 统一响应 / 全局异常 / 接口文档 / 容器化 / CI） | ✅ 完成 |
| 表结构采集与索引（注释 / 枚举 / 外键 / 样例） | ✅ 完成 |
| 三策略表召回（关键词 / 向量 / 混合） | ✅ 完成 |
| SQL 安全校验（AST + 黑名单 + 系统库拦截） | ✅ 完成 |
| 只读执行与结果保护（超时 / 行数 / 截断） | ✅ 完成 |
| 端到端问答 | ✅ 完成 |
| 自纠错：执行失败 / 结果为空时回喂模型重试，最多 N 轮 | ✅ 完成 |
| 业务口径层（可配置，默认关闭——见[消融实验](#消融实验为什么最后把功能关掉了)） | ✅ 完成 |
| 评估脚本与 50 条评测集（双引擎自检） | ✅ 完成 |
| 扩充评测集到 100+ 条 | 计划中 |
| 多轮对话：指代消解与问题改写 | 计划中 |
| 行级权限：按用户限制可访问的表 | 计划中 |
| 前端界面（对话页 + 图表渲染） | 计划中 |
| 线上部署 | 计划中 |

---

## 目录结构

```
askbase/
├── src/main/java/com/askbase/
│   ├── ai/           模型装配、prompt 构造、SQL 生成与解析
│   ├── schema/       表结构采集、文档渲染、索引存储
│   │   └── retriever/  关键词 / 向量 / 混合三种召回实现
│   ├── query/        SQL 校验、只读执行、端到端编排
│   ├── auth/         JWT 签发与拦截
│   ├── common/       统一响应体、错误码、全局异常
│   ├── config/       配置绑定与 Web 装配
│   └── system/       健康检查
├── src/test/         49 个单元测试
├── db/
│   ├── schema.sql        10 张业务表 DDL
│   ├── schema_auth.sql   用户 / 权限 / 查询日志
│   └── seed_sample.sql   演示数据（约 1.2 万行）
├── eval/
│   ├── golden_set.jsonl     10 条评测用例
│   ├── eval_askbase.py      端到端 EA 评估
│   └── verify_gold_mysql.py 评测集自检（连真实 MySQL）
├── docs/api-tests.http   接口测试用例
├── docker-compose.yml
└── pom.xml
```

---

## 关键设计决策

**为什么用 Spring Boot 3.5 而不是 4.x**

Spring 官方 2026 年 5 月统计：4.x 生产环境采用率不足 5%，3.x 刚过半，仍有超过 45% 的项目在 2.x。用 3.5 才是跟上企业现状。另外 Spring AI 2.0 强制要求 Boot 4，且 2026 年 8 月才 GA，把项目稳定性押在刚发布的库上不划算。

**为什么不用 Knife4j**

Knife4j 最新版 4.5.0 按 springdoc 2.3 编译，在 Boot 3.5 上会抛 `NoSuchMethodError`；把 springdoc 降回 2.3 又不支持 Boot 3.5。改用 springdoc 2.8.17 + Swagger UI，功能一致。

**为什么单独建一个只读数据源**

应用主库要写 `sys_user`、`query_log`，必须是读写账号；而执行模型生成的 SQL 必须用只有 SELECT 权限的账号。两者混用等于把删库权限交给了模型。这是纵深防御里最可靠的一层——即使应用层校验全部失效，`DELETE` 也会被数据库拒绝。

**为什么向量检索在内存里算余弦，而不是用 RediSearch KNN 索引**

只有 10 张表，全量比对是微秒级，上 KNN 索引属于过度设计。召回策略已抽象成 `SchemaRetriever` 接口，表数上万时新增一个 Redis KNN 实现即可，调用方不用改。

**为什么表注释写得这么啰嗦**

表注释和字段注释会被采集进 prompt，直接决定召回准确率和 SQL 正确性。用户问「薪资」，字段叫 `monthly_salary`，只有注释能桥接这个语义鸿沟。同一份 schema，注释写「岗位表」和写「招聘岗位表。薪资为月薪区间」，召回效果差一个数量级。

**为什么 SQL 文件里要写 `SET NAMES utf8mb4`**

MySQL 客户端的默认字符集取决于操作系统 locale。容器里没有 `LANG` 时会退化为 latin1，UTF-8 编码的 SQL 文件被当作 latin1 读入，中文会双重编码——数据看起来正常，但 `WHERE name = '计算机学院'` 永远匹配不上。在文件里显式声明，不依赖任何环境配置。

---

## 已知限制

- **评测集 50 条**，覆盖 10 张表的主要查询形态，但对边界条件和多轮追问覆盖不足
- **数据模型有缺口**：缺专业级就业快照（`employment_stat` 只有学院级）、缺学生就业地点字段，
  这直接导致 2 条评测题无法用现有表正确回答
- **结果正确性无自检**：SQL 执行成功但语义错了（漏过滤条件、JOIN 位置不对）无法被发现，
  自纠错也修不了——这类错误没有可观测的信号
- **只支持 MySQL 单库**，不支持多数据源或跨库查询
- **没有多轮对话**，每次提问都是独立的
- **权限只有表级白名单**，没有列级或行级过滤
- **演示数据是生成的**，业务相关性由生成器人工设定，不代表真实分布

---

## License

[MIT](LICENSE)
