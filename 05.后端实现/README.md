# kyle-sales-agent

智能销售数据分析 Agent —— 后端项目

## 项目信息

| 项 | 值 |
|---|---|
| Spring Boot | 3.5.11 |
| Java | 21 |
| Maven | 项目结构 |
| 主包 | `salesAgent` |

## 依赖清单

| 依赖 | 用途 |
|---|---|
| Spring Web | MVC 栈（SSE 流式输出）|
| Spring Data JPA | ORM |
| MySQL Driver | 数据库驱动 |
| Lombok | 减少样板代码 |
| Spring Boot Actuator | 健康检查 |
| Spring Data Redis | 缓存 / ChatMemory 持久化 |
| Validation | 请求参数校验 |
| Spring Reactive Web | ⚠️ 当前保留但未启用（见下方说明）|

## ⚠️ WebFlux 当前未启用

本项目同时引入 `spring-boot-starter-web` 和 `spring-boot-starter-webflux`。

Spring Boot 自动配置的规则：**当两个都在 classpath 时，默认启用 MVC（Servlet 栈）**。所以 WebFlux 依赖被加载但不会被实际启用。

**当前影响**：
- `spring-boot-starter-webflux` 增加 jar 体积但不发挥功能
- 未来若要真正启用响应式（用 `Mono`/`Flux` 写 Controller），需移除 `spring-boot-starter-web`

**为什么不现在删**：项目当前需求简单，SSE 用 MVC 的 `SseEmitter` 完全够用。删掉 WebFlux 是更干净的选择，但用户选择保留——记录在此以便后续决策。

## 本地启动

### 前置条件
- MySQL 8.0+（数据库名 `sales_agent`，用户名/密码见 `application.yml`）
- Redis 6.0+
- JDK 21
- Maven 3.9+

### 步骤
```bash
# 1. 创建数据库
mysql -u root -p
> CREATE DATABASE sales_agent DEFAULT CHARSET utf8mb4;

# 2. 启动项目
mvn spring-boot:run
```

当前 `application.yml` 使用 `spring.sql.init.mode=never`，启动不会自动执行 `schema.sql` 或 `data.sql`。仅在确认目标是**可重建的本地测试库**后，才临时启用初始化；共享库和正式环境不得运行会重灌测试数据的 `data.sql`。

### 已有库升级

`sa_sales_rep` 表新增了 `password` 列。老库执行一次 ALTER（新库跳过，schema.sql 已含此列）：

```sql
ALTER TABLE sa_sales_rep ADD COLUMN password VARCHAR(72) NOT NULL COMMENT '密码(BCrypt 哈希)' AFTER role;
```

当前配置下重启不会重灌 `data.sql`；已有库需要由维护者明确执行迁移和初始化。

**账号管理升级（2026-10-02）**：新版代码要求 `sa_sales_rep.active` 列，且系统管理员不属于销售大区。务必先备份并确认连接的目标数据库，再手工执行 [迁移 SQL](src/main/resources/db/migration-20261002-account-admin.sql)。`schema.sql` 的 `IF NOT EXISTS` 不会修改已存在的表；当前 `spring.sql.init.mode=never` 也不会自动执行迁移。未迁移前不要重启运行新版代码。

当前应用账号 `sales_agent_app` 没有 `ALTER` 权限，不能直接执行此迁移。请由 DBA 或临时具备目标表 DDL 权限的维护者在 `sales_agent` 库运行该 SQL；不要把 `ALTER` 权限永久赋予运行中的应用账号。

### 独立管理员初始化与账号管理

本项目没有自助注册。系统管理员角色为 `SYS_ADMIN`，只可管理销售账号，不能调用销售数据 Agent。首次部署且完成上述迁移后，在**后端进程环境变量**中临时设置 `APP_BOOTSTRAP_ADMIN_PASSWORD` 为自行生成的独立强密码（至少 12 位、最多 72 字节）。启动时仅当数据库尚无 `SYS_ADMIN` 时创建账号，日志只打印新账号 ID，不打印密码。登录页用该 ID 和设置的密码登录，随后立即移除此环境变量。不要把密码写进 Git、启动参数、配置样例或聊天记录。

管理员页面 `/admin` 支持创建销售员、主管和总监账号，停用/启用账号以及重置密码；不会创建第二个管理员。新密码由管理员通过安全渠道单独告知本人。普通用户在 `/change-password` 自行改密，成功后原有登录会话失效。管理员忘记密码时，目前需要维护者走受控的线下恢复流程，**没有公开的找回密码接口**。

创建账号时，大区选项由需要管理员权限的 `GET /admin/accounts/regions` 从数据库读取；页面显示大区名称，提交对应 ID。后端创建接口仍检查 ID 是否存在，不能仅依赖前端选项防止非法值。

账号状态和角色每次请求从数据库读取，避免使用过期的 Session 权限。登录与改密采用账号 + 来源 IP 双维度限流；限流当前仅支持单实例。多实例部署必须换共享限流机制，并将管理员首次创建改为统一的受控迁移/运维流程。

### 仅供本地演示的测试账号

以下统一密码及预置用户不能用于共享环境或正式环境。正式部署前必须创建独立账号、使用不同密码，并确认测试数据没有被导入目标库。

| repId | 姓名 | 角色 | 密码 |
| --- | --- | --- | --- |
| 1 | 李明 | SALES_MANAGER（华东区主管） | 123456 |
| 2 | 张伟 | SALES_REP（华东区销售员） | 123456 |
| 8 | 张磊 | SALES_REP（华北区销售员） | 123456 |
| 13 | 黄总 | SALES_DIRECTOR（总监） | 123456 |

全部 13 个账号密码均为 `123456`（BCrypt 哈希存于 data.sql）。登录：

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"repId": 13, "password": "123456"}'
```

登录后每个请求在 header 携带 token：`Authorization: <token值>`。登录端点按账号 ID 和来源 IP 分别限流，默认在 60 秒内最多尝试 5 次/账号、30 次/IP；超过返回 429。当前限流为单实例内存实现，多实例部署前需要改用共享存储。

## 项目结构

按架构文档 v1.0.2 第 5 节约定：

```
src/main/java/com/kyle/kyle_sales_agent/
├── KyleSalesAgentApplication.java    # 启动类
├── agent/                            # Agent 层（@AiService）
├── tool/                             # 工具层（@Tool）
├── service/                          # Service 层
├── dto/                              # 入参出参
├── entity/                           # JPA 实体
├── repository/                       # JPA Repository
├── controller/                       # 接入层
├── config/                           # 配置
├── security/                         # Sa-Token + UserContext
├── audit/                            # 审计日志
├── exception/                        # 全局异常
└── memory/                           # ChatMemory 持久化

src/main/resources/
├── application.yml
└── db/
    ├── schema.sql                    # 建表 DDL
    └── data.sql                      # 测试数据（待补）
```

## 关联文档

- [业务需求分析 v1.1.1](../01业务需求分析/智能销售数据分析Agent-业务需求分析文档.md)
- [技术架构设计 v1.0.2](../02.技术架构设计/智能销售数据分析Agent-技术架构设计文档.md)
- [qwen 四特性 Spike v1.0](../03.技术验证Spike/qwen四特性Spike-验证方案.md)
