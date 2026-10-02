# 智能销售数据分析 Agent

[![CI](https://github.com/kyleFuKai/SalesAnalysisAgent/actions/workflows/ci.yml/badge.svg)](https://github.com/kyleFuKai/SalesAnalysisAgent/actions/workflows/ci.yml)

用自然语言查询销售数据的练习项目。销售员可以看自己的业绩，主管可以看所属大区，总监可以看全公司；系统管理员负责账号管理，不参与销售数据查询。

## 目前能做什么

- 查询订单、销售额、排名、同比/环比和异常情况。
- 生成折线图、柱状图和饼图；支持同步问答与 SSE 流式回答。
- 按角色限制数据范围，保存对话记忆，并记录问答审计日志。
- 提供登录、修改密码和管理员账号管理页面。

图表并非支持所有维度：目前“Top 10 产品销售额柱状图”尚不能生成。模型问答也可能超过预期的响应时间。

## 技术栈与目录

后端使用 Java 21、Spring Boot 3.5、LangChain4j、Sa-Token、JPA、MySQL 和 Redis；模型为智谱 GLM-5.3-flash（走 Coding Plan 专属端点，见后端配置）；前端使用 Vue 3、TypeScript、Vite 和 ECharts。

```text
01业务需求分析/       业务需求文档
02.技术架构设计/      架构设计文档
03.技术验证Spike/    技术验证资料
04.前端实现/web/      前端项目
05.后端实现/         Spring Boot 项目
联调验收记录.md       当前联调结果
```

## 本地启动

1. 准备 JDK 21、Maven、Node.js、MySQL 和 Redis。建表、已有库升级及测试数据的使用方法见[后端说明](05.后端实现/README.md)。`spring.sql.init.mode` 默认是 `never`，启动不会自动执行建表或测试数据脚本；不要在共享数据库运行会重灌数据的 `data.sql`。
2. 在 `05.后端实现/` 创建本地 `.env`，配置 MySQL、Redis、`ZHIPUAI_API_KEY` 和 `SA_TOKEN_SECRET`。模型配置以 `application.yml` 为准；`.env` 不要提交到仓库。
3. 进入 `05.后端实现/` 执行 `mvn spring-boot:run`，默认监听 `http://localhost:8080`；可访问 `/actuator/health` 检查数据库和 Redis 状态。
4. 进入 `04.前端实现/web/` 执行 `npm install`、`npm run dev`，打开终端给出的本地地址。前端默认代理到 `http://localhost:8080`；后端端口不同请参考[前端说明](04.前端实现/web/README.md)配置 `.env.local`。

项目没有开放自助注册。首次创建系统管理员、管理销售账号和本地演示账号的说明见后端 README；演示账号及统一密码只适用于隔离的本地测试环境。

## 验证与说明

后端在 `05.后端实现/` 运行 `mvn test`；前端在 `04.前端实现/web/` 运行 `npm run test` 和 `npm run build`。另有一套针对真模型链路的评测集（21 条用例，覆盖权限矩阵、数据对账与拒绝场景），在后端目录运行 `mvn test -Dtest=EvalRunner -Deval.run=true`，默认跳过、需真实环境与 GLM 额度。当前已知限制及联调结果见[联调验收记录](联调验收记录.md)。
