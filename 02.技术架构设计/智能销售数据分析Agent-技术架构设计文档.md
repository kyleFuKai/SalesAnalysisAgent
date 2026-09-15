# 智能销售数据分析 Agent —— 技术架构设计文档

| 文档属性 | 内容 |
| --- | --- |
| 项目名称 | 智能销售数据分析 Agent |
| 文档版本 | v1.0.3 |
| 编写日期 | 2026-09-14 |
| 文档状态 | 初稿（待评审） |
| 上游文档 | [《业务需求分析文档 v1.1.1》](../01业务需求分析/智能销售数据分析Agent-业务需求分析文档.md) |

> **变更记录**
>
> | 版本 | 变更内容 |
> | --- | --- |
> | v1.0 | 初版：技术选型（LangChain4j vs Spring AI）、7 层架构、目录结构、请求流转示例 |
> | v1.0.1 | 评审修订：补充 Service 层 fail-closed 原则、Agent 执行护栏（最大工具轮数/LLM 超时预算）、ChatMemory 归属校验、"无权限 ≠ 无数据"区分原则；收敛 LangChain4j vs Spring AI 选型理由（修正对 Spring AI 的过时表述）；限流单实例前提与 SSE 部署要求；3.2.7 缓存表述与 4.3 对齐；目录补充 dto/audit/exception 包；待办新增 qwen 三特性 spike |
> | v1.0.2 | 二轮评审：Agent 层补"模型参数（temperature=0）"；第 4 章新增 4.4 "权限结果的结构化标记"（无权限 ≠ 无数据升级为独立决策，含 status 判定方式）与 4.5 "模型确定性约束"；第 7 章补 4.4 矩阵、多轮对话、成本控制等需求映射；待办扩 UserContext 跨线程候选方案、qwen spike 加多轮下权限切换第四特性、审计日志补 PII 脱敏与保留周期；修正 3.2.4 与 4.4 返回类型表述矛盾、评测集路径 |
| v1.0.3 | ChatMemory 持久化策略明确为"Redis 热缓存 + MySQL `sa_chat_memory` 冷存储"；3.2.7 存储层 Redis 用途、第 5 章 memory 包注释与包职责表同步更新（schema.sql 已落库） |

---

## 1. 概述

### 1.1 文档目的

本文档把"业务需求"翻译成"代码怎么组织"。重点回答三件事：

1. **用什么技术**——技术栈选型与对比
2. **怎么分层**——7 层架构每一层干什么
3. **请求怎么走**——一次完整的 Agent 调用数据流

### 1.2 阅读对象

后端开发、架构评审人、QA。预期读者已熟悉 Spring Boot 基础，不要求已了解 AI Agent 框架——核心概念会在文中解释。

---

## 2. 技术选型

### 2.1 整体技术栈一览

| 层 | 技术选型 | 说明 |
| --- | --- | --- |
| 用户层 | Vue 3 + SSE + ECharts | 极简前端，SSE 流式输出，ECharts 渲染图表 |
| 接入层 | Spring Boot + Sa-Token + Guava RateLimiter | 认证拦截、限流、路由 |
| Agent 层 | **LangChain4j** + qwen-max | AI 大脑核心，ReAct 循环 |
| 工具层 | LangChain4j `@Tool` 注解 | 5 个工具即"AI 的 5 只手" |
| Service 层 | Spring Boot Service | 统一业务调度 |
| 数据访问层 | Spring Data JPA Repository | 不写原生 SQL（除非性能要求） |
| 存储层 | MySQL + Redis | MySQL 存业务数据，Redis 做缓存 |

### 2.2 为什么用 LangChain4j 而不用 Spring AI？

**核心结论**：本项目的形态是"一个 `@AiService` 接口 + 5 个 `@Tool` 工具"的声明式多工具 Agent，**LangChain4j 的 AiServices 编程模型与这个形态最贴合**，多工具编排的文档、示例与社区实践更丰富，所以选它。

| 对比项 | LangChain4j | Spring AI |
| --- | --- | --- |
| 声明式 Agent（@AiService 接口） | ✅ 核心特性，开箱即用 | ChatClient 编程式组装为主 |
| 多工具编排的文档与社区示例 | ✅ 更丰富 | 有，相对较少 |
| 工具声明 | `@Tool` 注解 | `@Tool` 注解（1.0 GA 后具备） |
| Spring 生态融合 | 良好 | ✅ 天然融合 |
| 迭代模式 | 社区驱动，迭代快 | Spring 官方，1.0 后趋稳 |

> **选型逻辑**：
> - 两者都能实现本项目，差距不在"能不能"，而在**与项目形态的贴合度和资料丰富度**
> - 注意：Spring AI 自 1.0 GA 起已具备 `@Tool`、声明式 ChatClient 等能力，**不要用"Spring AI 功能缺失"作为选型理由**——真实差异是编程模型偏好与社区资料积累
> - 风险对冲：Agent 层通过 `@AiService` 接口隔离，框架细节不向工具层/Service 层渗透；若未来需要迁移到 Spring AI，改动收敛在 `agent` 与 `config` 两个包

---

## 3. 系统分层架构

### 3.1 全景图

从用户视角到存储视角，本系统分 7 层，**全链路纵向贯穿**：

| # | 层级 | 角色 | 类比 |
| --- | --- | --- | --- |
| 1 | **用户层** | Vue 3 极简界面（SSE 流 + ECharts） | 用户看得见的前端 |
| 2 | **接入层** | 认证与路由 | 大门保安 |
| 3 | **Agent 层** | AI 大脑核心 | 思考的"脑" |
| 4 | **工具层** | AI 的 5 只手 | 干活的"手" |
| 5 | **Service 层** | 统一入口调度 | 干活前的"工头" |
| 6 | **数据访问层** | JPA Repository | 拿数据的"搬运工" |
| 7 | **存储层** | MySQL / Redis | 存数据的"仓库" |

**核心三层的关系**（用颜色高亮表示）：

- **Agent 层**（黄色高亮）——AI 大脑，决定"要不要调工具、调哪个"
- **工具层**——AI 的手，把模型的想法翻译成方法调用
- **Service 层**（黄色高亮）——真正的"工头"，管权限、缓存、日志

### 3.2 各层职责详解

#### 3.2.1 用户层（Vue 3 + SSE + ECharts）

- **职责**：提供聊天界面，渲染流式响应与图表
- **为什么用 SSE 而不是 WebSocket**：单向数据流（服务端 → 客户端）场景 SSE 更简单，HTTP 友好
- **为什么用 ECharts**：图表需求复杂（折线、饼、柱），ECharts 中文文档完善

#### 3.2.2 接入层（Controller）

三件事：

| 功能 | 选型 | 作用 |
| --- | --- | --- |
| 认证 | **Sa-Token 拦截器** | 校验 Token，把 `userId / role / regionId` 写入 `ThreadLocal UserContext`，供后续层使用 |
| 限流 | **Guava RateLimiter** | 按用户 ID 限制请求频率，防滥用 |
| 路由 | Spring MVC | 普通请求走 `/agent/chat`（同步），长任务走 `/agent/chat/stream`（SSE 流式） |

> **UserContext 的关键作用**：把"当前用户是谁"通过 ThreadLocal 传下去，Service 层可以无感知地拿到当前用户的权限范围，是"行级权限自动注入"的物理基础。

> **部署前提**：Guava RateLimiter 是单实例内存态（重启清零、多实例不共享），本系统按**单实例部署**设计；若未来多实例，限流需切换 Redis 实现。
>
> **SSE 部署要求**：Nginx 反代需关闭缓冲（`X-Accel-Buffering: no`）并调大异步请求超时，否则多步推理的长连接会被中间层掐断。

#### 3.2.3 Agent 层

项目的核心，**主要就是 `SalesAgent` 这一个 `@AiService` 接口**。

| 组件 | 作用 |
| --- | --- |
| **System Prompt** | 告诉模型它是什么、能做什么、不能做什么，以及当前用户的权限范围；明确"权限外对象直接拒答，不回退成『没有数据』" |
| **ChatMemory** | 多轮对话记忆，Redis 持久化；**memoryId 与 userId 绑定归属、请求时校验**，防止改 ID 读到他人对话（IDOR） |
| **ReAct 循环** | LangChain4j 内部自动完成：`模型 → 工具 → 模型 → ... → 最终回答` |
| **执行护栏** | 最大工具调用轮数（5~8 轮）、单次 LLM 调用超时与重试预算、超限兜底话术——防止模型循环调工具，拖垮 Token 成本与 15 秒目标 |
| **模型参数** | `temperature ≤ 0.1`（推荐 0），杜绝随机凑数；详见 4.5 模型确定性约束 |

> **关键设计**：SalesAgent 内部不写任何业务逻辑，只做"意图理解 + 工具调用编排"。所有真实查询都委托给工具层。
>
> **"无权限" ≠ "无数据"**：销售员查张磊时，Service 过滤后返回空集，模型若不辨别会答成"张磊这个月没有成交"——事实错误，直接威胁"越权拦截率 100%"验收指标。原则：**权限外对象由 System Prompt 引导直接拒答**；工具结果用结构化标记区分"范围内无数据"与"范围外被过滤"。在 System Prompt 与工具接口详细设计中落地。

#### 3.2.4 工具层（5 个工具）

每个工具是一个 `@Component`，里面的 `@Tool` 方法就是 Agent 的"手"。

```java
@Component
public class SalesSummaryTool {
    @Tool("获取指定范围/维度的销售汇总（金额、排名、完成率等）")
    public SalesSummary getSummary(SummaryQuery query) {
        // 参数解析和结果格式化
        return salesQueryService.summarize(query);
    }
}
```

**职责边界**：

- ✅ 参数解析与校验
- ✅ 调用 Service 层
- ✅ 返回结构化 DTO（见 4.4），由框架序列化后交给模型
- ❌ 不做权限过滤（Service 层兜底）
- ❌ 不直接调 Repository

#### 3.2.5 Service 层

`SalesQueryService` 是所有工具的**统一入口**：

- 封装 JPA 查询方法
- **注入当前用户权限，自动加 WHERE 条件**（从 UserContext 读取）
- 缓存热点查询（Redis）
- 写审计日志

#### 3.2.6 数据访问层（JPA Repository）

- 不写原生 SQL（除非性能要求）
- 只暴露"按主键查"、"按索引查"这类原子方法
- 组合查询在 Service 层用 Specification 或 QueryDSL 拼装

#### 3.2.7 存储层

| 存储 | 用途 | 数据 |
| --- | --- | --- |
| **MySQL** | 业务数据 | 订单、用户、SKU、退单、销售目标等 |
| **Redis** | 热缓存 | ① 对话历史**热缓存**（MySQL `sa_chat_memory` 做冷备份，双层持久化）；② 查询结果/回答缓存——**Key 必须含权限维度，详见 4.3** |

---

## 4. 关键技术决策

### 4.1 为什么工具不直接调 Repository？

> **如果工具直接调 Repository，权限控制、缓存、日志就要在每个工具里重复写。统一走 Service 层，只写一遍。**

| 关注点 | 工具直调 Repository | 工具 → Service → Repository |
| --- | --- | --- |
| 权限注入 | 每个工具自己写 | **Service 层一处实现** |
| 缓存逻辑 | 每个工具自己写 | **Service 层一处实现** |
| 审计日志 | 每个工具自己写 | **Service 层一处实现** |
| 代码总量 | 5 份重复 | **1 份** |
| 出 bug 风险 | 高 | 低 |

> **设计原则**：横切关注点（cross-cutting concerns）下沉到 Service 层，工具层只做"翻译"工作。

### 4.2 双保险权限设计

承接需求文档第 6 节，本架构落地为：

```
System Prompt（引导层）
  ↓ 告诉模型"你是谁、不能给什么数据"
  ↓
Agent 决策（不调越权工具）
  ↓
工具层（无权限感知，只调 Service）
  ↓
Service 层（强制层）
  ↓ 从 UserContext 取权限过滤条件
  ↓ 自动注入 WHERE 条件
  ↓ 即使被越权，DB 也查不到
```

两层缺一不可——只靠提示词防不住刻意诱导，只靠数据过滤则用户体验差（模型不知道自己该拒绝）。

> **强制层的第三条军规（fail-closed）**：Service 层从 UserContext **取不到用户身份时，必须拒绝查询**，绝不允许"无过滤查询"。这是应对 ThreadLocal 失效（SSE 流式下工具执行线程切换、`@Async` 等场景）的最后底线——**宁可报错，不可越权**。开发早期需写集成测试专门验证：流式请求下权限过滤依然生效。

### 4.3 缓存策略

依据需求文档 7.2 数据新鲜度：

| 数据范围 | TTL |
| --- | --- |
| 当日 / 近 30 天数据 | ≤ 5 分钟 |
| 月度 / 季度 / 年度历史数据 | 缓存至每日数据更新完成 |

**Key 设计**：所有缓存 Key 必须包含 `role + regionId + repId`，否则会出现"销售员A 缓存命中但拿到的却是销售员B 的数据"的越权事故。

### 4.4 权限结果的结构化标记（"无权限 ≠ 无数据"）

承接 4.2 的强制层——Service 层过滤后，工具需要**区分两种"空"**：

| 工具返回的"空" | 模型应该回答 |
| --- | --- |
| 范围内查不到数据 | "您本月暂无成交" |
| 范围外被过滤掉 | "该数据您无权限查看" |

**如果不区分**：模型把"被过滤"理解成"没有数据"，就会答成"张磊这个月没卖"——既不是事实（他可能卖了很多），也直接威胁需求文档 8.2 的"越权拦截率 100%"验收指标。

**结构化标记方案**：

- 工具返回 `Result<T>`，含 `status` 字段：
  - `OK`：正常返回数据
  - `IN_SCOPE_EMPTY`：查询正常执行、范围内结果为空（如"您本月暂无成交"）
  - `NOT_FOUND`：目标对象本身不存在（如查无此人）
  - `OUT_OF_SCOPE_FILTERED`：目标对象存在但不在当前用户范围内（用户越权）
- **`OUT_OF_SCOPE_FILTERED` 的判定方式**：Service 先校验目标对象（如 repId=张磊）是否存在、是否在当前用户范围内，**再执行查询**——对象存在但在范围外 → `OUT_OF_SCOPE_FILTERED`；对象不存在 → `NOT_FOUND`；范围内查无 → `IN_SCOPE_EMPTY`。具体实现留到工具接口详细设计
- System Prompt 明确：根据 `status` 决定回答方式，禁止自行推断"没有 = 没卖"
- **这是为什么"行级权限 Service 兜底"还不够**——光过滤不够，还要让模型知道被过滤了

> **落到第 5 章**：工具的返回类型必须是结构化 DTO，不是裸 String 或裸 List。这样模型拿到的不是"[]"而是带语义标签的对象。

### 4.5 模型确定性约束

销售数据查询需要**确定性**——同一问题在不同时间问应拿到同一数（除非底层数据真的变了）。模型温度过高会"凑数"。

| 约束 | 取值 | 理由 |
| --- | --- | --- |
| 温度 | `temperature ≤ 0.1`（推荐 0） | 杜绝随机凑数 |
| 数字来源 | **必须来自工具结果** | 幻觉防御的第一道 |
| 表达层 | 允许模型润色措辞 | 用户体验不僵化 |

> **配套 System Prompt 约束**："所有数字必须由工具返回，禁止凭直觉生成；如工具无结果，明确告知无数据，不得编造。"——这条与 4.4 的 status 标记配套使用。

---

## 5. 目录结构

```
SalesAnalysisAgent/
├── src/main/java/com/kyle/SalesAnalysisAgent/
│   ├── agent/              # Agent 层：SalesAgent (@AiService 接口)
│   ├── tool/               # 工具层：5 个 @Component（@Tool 方法）
│   ├── service/            # Service 层：业务逻辑 + 权限注入 + 缓存 + 日志
│   ├── dto/                # 工具/接口的入参出参对象（SummaryQuery 等）
│   ├── entity/             # JPA 实体类（Order、User、Sku、Refund...）
│   ├── repository/         # JPA Repository 接口
│   ├── controller/         # 接入层：SalesAgentController + 限流配置
│   ├── config/             # 配置：LangChain4j 配置 / Redis 配置 / 拦截器注册
│   ├── security/           # Sa-Token 拦截器、UserContext（ThreadLocal）
│   ├── audit/              # 审计日志：AOP 切面 + 异步写入
│   ├── exception/          # 全局异常处理 + SSE 错误事件
│   ├── memory/             # ChatMemory 持久化（Redis 热缓存 + MySQL sa_chat_memory 冷备份）
│   └── SalesAnalysisAgentApplication.java
├── src/main/resources/
│   ├── application.yml             # 主配置
│   ├── application-dev.yml         # 开发环境
│   └── db/
│       ├── schema.sql              # 建表 DDL
│       └── data.sql                # 测试数据
└── pom.xml
```

**包职责一句话总结**：

| 包 | 一句话职责 |
| --- | --- |
| `agent` | AI 大脑，编排工具调用 |
| `tool` | 把模型的话翻译成方法调用 |
| `service` | 干活的工头，管权限/缓存/日志 |
| `entity` | 数据库表的 Java 映射 |
| `repository` | 原子数据访问 |
| `controller` | HTTP 入口 |
| `config` | 各种 Bean 注册 |
| `security` | 认证 + 当前用户上下文 |
| `memory` | ChatMemory 持久化（Redis 热缓存 + MySQL `sa_chat_memory` 冷备份） |
| `dto` | 工具/接口的入参出参对象 |
| `audit` | 审计日志切面与异步落库 |
| `exception` | 全局异常与 SSE 错误事件 |

---

## 6. 数据流转示例：一次完整的 Agent 请求

以"上个月华东区 Top 3 销售员是谁？"为例，看数据是怎么一站一站传过去的：

### 6.1 时序图（文字版）

```
[用户]               [Controller]              [Agent]            [Tool]            [Service]          [Redis/MySQL]
  │  1.发送问题       │                          │                   │                  │                    │
  │──────────────────→│                          │                   │                  │                    │
  │                   │  2.Sa-Token 校验         │                   │                  │                    │
  │                   │  → UserContext:{userId=101, role=SALES_MANAGER, regionId=华东区}
  │                   │  3.RateLimiter 通过       │                   │                  │                    │
  │                   │  4.调用 Agent.chat()      │                   │                  │                    │
  │                   │─────────────────────────→│                   │                  │                    │
  │                   │                          │  5.构建 messages(System + 历史 + 用户)
  │                   │                          │  6.传入 5 个工具描述
  │                   │                          │  7.qwen-max 推理   │                  │                    │
  │                   │                          │  决定调 SalesSummaryTool.getTopReps()
  │                   │                          │──────────────────→│                  │                    │
  │                   │                          │                   │  8.调用 Service.getTopRepsByRegion("华东区", 3, lastMonth)
  │                   │                          │                   │─────────────────→│                    │
  │                   │                          │                   │                  │  9.权限检查：华东区主管 ✓
  │                   │                          │                   │                  │  10.Redis 检查
  │                   │                          │                   │                  │──────────────────→│
  │                   │                          │                   │                  │  未命中
  │                   │                          │                   │                  │  11.查 MySQL
  │                   │                          │                   │                  │──────────────────→│
  │                   │                          │                   │                  │  返回数据
  │                   │                          │                   │                  │  12.写入 Redis 缓存
  │                   │                          │                   │  ← 返回结果      │                    │
  │                   │                          │  ← 工具结果       │                  │                    │
  │                   │                          │  13.模型生成最终回答
  │                   │  ← 返回回答              │                   │                  │                    │
  │  ← SSE 流式输出  │                          │                   │                  │                    │
  │  14.写审计日志    │                          │                   │                  │                    │
```

### 6.2 关键节点解读

| 步骤 | 关键点 |
| --- | --- |
| **2. Sa-Token 拦截** | 校验 Token → 写入 UserContext。这是权限的"启动点"，没有这一步后续 Service 层拿不到用户身份 |
| **7. 模型推理** | LangChain4j 内部完成 ReAct 决策；System Prompt 里已注入当前用户角色，模型知道自己只能看"华东区" |
| **9. 权限检查** | 双保险的第二道（强制层）：Service 自动加 `WHERE region_id = ?`；第一道是 System Prompt 引导层。另有兜底军规：UserContext 缺失即拒绝查询（见 4.2 fail-closed） |
| **10-12. 缓存** | Redis 命中直接返回；未命中查 MySQL 后回写缓存。注意缓存 Key 必须含权限维度 |
| **14. 审计日志** | 异步写日志：用户、问题、Token 消耗、调用的工具、耗时。详见非功能需求 7.5 |

---

## 7. 与需求文档的对应关系

| 需求章节 | 架构落地点 |
| --- | --- |
| 1. 项目背景 | — |
| 2. 指标口径 | Service 层统一封装（口径分散在 Repository 易失控） |
| 3. 用户角色分析 | UserContext（security 包） |
| 4. 用例清单 | 工具层 5 个工具 + Agent System Prompt |
| 4.2 多轮对话 | ChatMemory（Redis 持久化 + 归属校验，见 3.2.3） |
| 4.4 用例×角色预期行为矩阵 | 评测集（`src/test/java/.../eval/`）+ System Prompt 的拒绝判断规则 |
| 5. 工具边界划分 | `tool` 包下 5 个 `@Component` |
| 6. 权限模型 | 双保险：System Prompt（agent 包）+ Service 注入（service 包） |
| 6.3 字段级敏感数据 | Service 层对敏感字段直接不提供 Repository 方法 |
| "无权限 ≠ 无数据"原则（承接 8.2 越权拦截率 100%） | 工具返回结构化 `status` 标记 + System Prompt 拒答引导（详见 4.4） |
| 7.1 响应时间 | Redis 缓存 + 流式输出 + 异步日志 |
| 7.2 数据新鲜度 | 缓存 TTL 配置（按数据范围分两档） |
| 7.3 成本控制 | Redis 缓存 + 执行护栏工具轮数上限（见 3.2.3） |
| 7.4 安全性 | Sa-Token（认证）+ Service 过滤（行级）+ 工具不暴露敏感字段（字段级） |
| 7.5 可追溯性 | Service 层统一写审计日志（AOP 切面更佳） |
| 8. 验收标准 | 评测集 → 集成测试 → 线上抽检 |

---

## 8. 后续工作（待排期）

- [ ] **技术验证 spike（最高优先级，框架搭建前完成）**：qwen-max + LangChain4j 的"流式输出 + 工具调用 + 多轮记忆 + **多轮下权限切换**"四特性叠加验证——全项目最高风险点。第四特性尤其关键：销售员问完自己的，会话里又问"李明呢"，多轮下权限仍生效
- [ ] 数据库表结构设计（订单、用户、SKU、退单、销售目标表等；建议引入 Flyway 管理表结构版本）
- [ ] System Prompt 详细设计（含权限注入模板、拒绝话术模板、口径说明模板、"无权限 vs 无数据"处理规则）
- [ ] 5 个工具的接口详细设计（入参、出参、异常处理、缓存策略、结构化权限标记）
- [ ] UserContext 设计（ThreadLocal 生命周期、跨线程传递、**fail-closed 兜底**）——SSE 流式 + 工具执行常跨线程，ThreadLocal 不传过去会触发 fail-closed 误拦截所有流式请求。候选方案：① InheritableThreadLocal（线程池复用会丢，不彻底）；② TaskDecorator 包裹 Runnable（Spring 标准做法，**推荐**）；③ 全程不切线程（违背流式初衷，放弃）
- [ ] Redis Key 规范与缓存失效策略（Key 含权限维度 + TTL 分档）
- [ ] ChatMemory 归属校验设计（memoryId ↔ userId 绑定）
- [ ] 审计日志表设计与异步写入方案（含 TokenUsage 采集方式：TokenStream / 响应 metadata）——**同时定 PII 脱敏策略（用户问题原文可能含客户姓名/手机号/订单号）与保留周期**（建议 90 天滚动删除，满足合规最低线）
- [ ] SQL 注入与 Prompt 注入防护专项方案
- [ ] 评测集建设（≥ 50 条，按需求文档 4.4 矩阵标注）