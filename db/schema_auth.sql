-- ============================================================================
-- 应用侧表（不属于业务数据集）
-- ----------------------------------------------------------------------------
-- 和 schema.sql 的区别：
--   schema.sql      = 被分析的业务数据（10 张表，只读，给模型生成 SQL 用）
--   schema_auth.sql = 应用自己的表（用户、权限、查询日志）
--
-- 这两类表必须物理隔离。否则模型生成 SQL 时可能查到 sys_user，
-- 把用户密码哈希当成"业务数据"返回给提问的人。
-- ============================================================================

USE askbase;

-- 强制连接字符集为 utf8mb4，理由见 schema.sql 里的说明。
-- 少了这一行，中文会双重编码，WHERE 匹配中文字面量全部失效。
SET NAMES utf8mb4;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS query_log;
DROP TABLE IF EXISTS sys_table_permission;
DROP TABLE IF EXISTS sys_user;
SET FOREIGN_KEY_CHECKS = 1;


-- ---------------------------------------------------------------------------
-- 系统用户
--   登录目前走 application.yml 里的配置用户，这张表供后续接入数据库认证。
--   先建好，后续接入时不用临时设计。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username      VARCHAR(64)  NOT NULL                COMMENT '登录名',
  password_hash VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希，禁止存明文',
  display_name  VARCHAR(64)  DEFAULT NULL            COMMENT '显示名',
  role          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN / USER',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：1=启用，0=禁用',
  failed_count  TINYINT      NOT NULL DEFAULT 0      COMMENT '连续登录失败次数，用于锁定',
  locked_until  DATETIME     DEFAULT NULL            COMMENT '锁定到期时间',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='系统用户表。密码必须存 BCrypt 哈希';


-- ---------------------------------------------------------------------------
-- 表级权限白名单
--   这是 SQL 沙箱的第二层防线（第一层是数据库只读账号）。
--   模型生成的 SQL 用 JSqlParser 解析出所有涉及的表，逐个比对这张表。
--   注意：默认拒绝。没有记录 = 没权限，不是有权限。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_table_permission (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  user_id         BIGINT       NOT NULL                COMMENT '用户ID，关联 sys_user.id',
  table_name      VARCHAR(64)  NOT NULL                COMMENT '允许访问的业务表名，如 student、offer',
  column_whitelist VARCHAR(512) DEFAULT NULL           COMMENT '允许访问的字段，逗号分隔；NULL 表示整表可访问',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_table (user_id, table_name),
  KEY idx_perm_user (user_id),
  CONSTRAINT fk_perm_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户可访问的业务表白名单。没有记录表示无权限（默认拒绝）';


-- ---------------------------------------------------------------------------
-- 查询日志
--   用途有三个：
--     1. 排查线上问题（用户说"结果不对"，你得能复现他当时的 SQL）
--     2. 攒 few-shot 语料（高频问题 + 正确 SQL 是免费的优质样本）
--     3. 评估体系的补充数据（真实分布 vs 测试集分布）
-- ---------------------------------------------------------------------------
CREATE TABLE query_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  user_id       BIGINT       NOT NULL                COMMENT '提问用户ID',
  question      VARCHAR(1000) NOT NULL               COMMENT '用户原始问题',
  rewritten     VARCHAR(1000) DEFAULT NULL           COMMENT '多轮改写后的独立问题',
  generated_sql TEXT         DEFAULT NULL            COMMENT '模型生成的 SQL（最后一次）',
  retry_count   TINYINT      NOT NULL DEFAULT 0      COMMENT '自纠错重试次数',
  success       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '最终是否成功：1=是',
  error_msg     VARCHAR(500) DEFAULT NULL            COMMENT '失败原因',
  row_count     INT          DEFAULT NULL            COMMENT '结果行数',
  cost_ms       INT          DEFAULT NULL            COMMENT '总耗时（毫秒）',
  token_in      INT          DEFAULT NULL            COMMENT '输入 token 数',
  token_out     INT          DEFAULT NULL            COMMENT '输出 token 数',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_log_user (user_id),
  KEY idx_log_created (created_at),
  KEY idx_log_success (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='查询日志。token 字段用于算成本，retry_count 用于评估自纠错效果';


-- ---------------------------------------------------------------------------
-- 初始化管理员
-- ----------------------------------------------------------------------------
-- 密码是 admin123 的 BCrypt 哈希（cost=10），已实测校验通过。
-- 换密码的方法：new BCryptPasswordEncoder().encode("你的密码") 后替换下面的值。
--
-- ⚠️ 网上抄来的哈希经常是错的（不匹配它声称的明文），务必自己验一次再提交。
--    这个哈希只用于本地开发，上线前必须改。
-- ---------------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, display_name, role)
VALUES ('admin', '$2b$10$aMOsF.PgqihsonMeujR82OSpYWNxDhXzCjNVioWa2JrH3HzDl1eWe', '管理员', 'ADMIN');

-- 管理员默认可访问全部业务表（后续再细分到普通用户）
INSERT INTO sys_table_permission (user_id, table_name)
SELECT 1, t.table_name
FROM information_schema.tables t
WHERE t.table_schema = 'askbase'
  AND t.table_name IN ('college', 'major', 'student', 'course', 'course_enrollment',
                       'company', 'job_posting', 'job_application', 'offer', 'employment_stat');
