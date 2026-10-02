-- 仅供维护者在备份后，对已有数据库手工执行一次。
-- 当前 spring.sql.init.mode=never，不会自动运行本文件。
ALTER TABLE sa_sales_rep ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号是否启用' AFTER password;
ALTER TABLE sa_sales_rep MODIFY COLUMN region_id BIGINT NULL COMMENT '所属大区；系统管理员为空';
