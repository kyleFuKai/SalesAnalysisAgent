-- ============================================================
-- kyle-sales-agent 建表 DDL
-- 对应文档：数据库设计.md
-- 注意：所有表使用 IF NOT EXISTS，支持服务重启多次执行
-- ============================================================

-- ============================================================
-- 1. 销售大区表
-- ============================================================
CREATE TABLE IF NOT EXISTS sa_sales_region (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '大区ID',
    name             VARCHAR(50)  NOT NULL COMMENT '大区名称，如：华东区',
    parent_region_id BIGINT       DEFAULT NULL COMMENT '上级大区，NULL表示顶级',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售大区';

-- ============================================================
-- 2. 销售员表
-- ============================================================
CREATE TABLE IF NOT EXISTS sa_sales_rep (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '销售员ID',
    name       VARCHAR(50)  NOT NULL COMMENT '姓名',
    region_id  BIGINT       DEFAULT NULL COMMENT '所属大区；系统管理员为空',
    role       VARCHAR(20)  NOT NULL DEFAULT 'SALES_REP'
                            COMMENT '角色：SALES_REP/SALES_MANAGER/SALES_DIRECTOR/SYS_ADMIN',
    password   VARCHAR(72)  NOT NULL COMMENT '密码(BCrypt 哈希)',
    active     BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '账号是否启用',
    email      VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售员';

-- ============================================================
-- 3. 产品表
-- ============================================================
CREATE TABLE IF NOT EXISTS sa_product (
    id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '产品ID',
    sku_code    VARCHAR(50)    NOT NULL COMMENT 'SKU编码',
    name        VARCHAR(200)   NOT NULL COMMENT '产品名称',
    category    VARCHAR(50)    NOT NULL COMMENT '品类：数码产品/家用电器/服装配饰/其他',
    unit_price  DECIMAL(10,2)  NOT NULL COMMENT '售价',
    cost        DECIMAL(10,2)  NOT NULL COMMENT '成本',
    status      VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE'
                               COMMENT '状态：ACTIVE/INACTIVE',
    created_at  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku (sku_code),
    KEY idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品';

-- ============================================================
-- 4. 销售订单表（核心宽表）
-- ============================================================
CREATE TABLE IF NOT EXISTS sa_sales_order (
    id            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    order_no      VARCHAR(50)    NOT NULL COMMENT '订单号',
    rep_id        BIGINT         NOT NULL COMMENT '销售员ID',
    product_id    BIGINT         NOT NULL COMMENT '产品ID',
    region_id     BIGINT         NOT NULL COMMENT '销售大区ID',
    customer_name VARCHAR(100)   NOT NULL COMMENT '客户名称',
    quantity      INT            NOT NULL COMMENT '销售数量',
    unit_price    DECIMAL(10,2)  NOT NULL COMMENT '成交单价',
    amount        DECIMAL(12,2)  NOT NULL COMMENT '成交金额（quantity * unit_price）',
    cost          DECIMAL(12,2)  NOT NULL COMMENT '成本总额',
    profit        DECIMAL(12,2)  NOT NULL COMMENT '毛利（amount - cost）',
    status        VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED'
                                 COMMENT '状态：COMPLETED/REFUNDED/CANCELLED',
    order_date    DATE           NOT NULL COMMENT '下单日期',
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_rep (rep_id),
    KEY idx_product (product_id),
    KEY idx_region (region_id),
    KEY idx_order_date (order_date),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单';

-- ============================================================
-- 5. 对话记忆持久化（冷存储；Redis 做热缓存）
-- ============================================================
CREATE TABLE IF NOT EXISTS sa_chat_memory (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    session_id   VARCHAR(100) NOT NULL COMMENT '会话 ID',
    messages     LONGTEXT     NOT NULL COMMENT '序列化的消息列表（JSON）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记忆持久化（冷存储）';

-- ============================================================
-- 6. 对话审计日志（需求 7.5：谁在什么时间问了什么、花了多少 Token、调了哪些工具）
--    异步写入（audit 包单线程执行器），保留周期 90 天滚动删除（架构 8 节待办）
-- ============================================================
CREATE TABLE IF NOT EXISTS sa_audit_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    user_id       BIGINT       NOT NULL COMMENT '用户ID',
    username      VARCHAR(50)  DEFAULT NULL COMMENT '姓名快照',
    session_id    VARCHAR(120) DEFAULT NULL COMMENT '会话 ID（不含用户前缀）',
    question      TEXT         NOT NULL COMMENT '用户提问原文',
    answer        TEXT         DEFAULT NULL COMMENT 'Agent 回答（失败/流式中断时为空）',
    tool_names    VARCHAR(500) DEFAULT NULL COMMENT '本次调用的工具名，逗号分隔',
    input_tokens  INT          DEFAULT NULL COMMENT '输入 Token（流式路径暂缺）',
    output_tokens INT          DEFAULT NULL COMMENT '输出 Token（流式路径暂缺）',
    duration_ms   BIGINT       DEFAULT NULL COMMENT '总耗时毫秒',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话审计日志';
