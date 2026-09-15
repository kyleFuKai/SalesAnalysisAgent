# kyle-sales-agent

智能销售数据分析 Agent —— 后端项目

## 项目信息

| 项 | 值 |
|---|---|
| Spring Boot | 3.5.11 |
| Java | 21 |
| Maven | 项目结构 |
| 主包 | `com.kyle.kyle_sales_agent` |

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

启动时 `schema.sql` 自动建表（开发环境配置 `spring.sql.init.mode=always`）。

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