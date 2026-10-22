-- ============================================================================
-- Askbase 演示数据集 —— 高校培养与就业分析
-- ----------------------------------------------------------------------------
-- 用途：NL2SQL Agent 的演示库 + 评估集底座
-- 兼容：MySQL 8.0+
--
-- 【重要】表注释和字段注释不是装饰，它们会被 SchemaCollector 读进 prompt，
--         直接决定 LLM 选表和写字段的准确率。注释写得好，准确率能高 10 个点。
--         这是你在这个项目里最早能感受到的"注释即 prompt"的工程细节。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS askbase
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE askbase;

-- ============================================================================
-- ⚠️ 这一行不能删，也不能挪到最后。
--
-- MySQL 客户端的默认字符集是 auto，会读操作系统 locale。Docker 容器里通常
-- 没有 LANG，locale 退化成 C/POSIX，客户端就选了 latin1。结果是：这个 UTF-8
-- 编码的 .sql 文件被当成 latin1 读入，再转成 utf8mb4 存进库 —— 数据变成
-- 「双重编码」，查出来是 è®¡ç®—æœºå­¦é™¢ 这种乱码，而且
-- WHERE name = '计算机学院' 永远匹配不上（存的和查的不是同一串字节）。
--
-- 这个坑极隐蔽：表结构、@@character_set_server 看起来全是 utf8mb4，
-- 只有数据是坏的。所以必须在文件里显式 SET NAMES，不能依赖容器配置。
-- ============================================================================
SET NAMES utf8mb4;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS employment_stat;
DROP TABLE IF EXISTS offer;
DROP TABLE IF EXISTS job_application;
DROP TABLE IF EXISTS job_posting;
DROP TABLE IF EXISTS company;
DROP TABLE IF EXISTS course_enrollment;
DROP TABLE IF EXISTS course;
DROP TABLE IF EXISTS student;
DROP TABLE IF EXISTS major;
DROP TABLE IF EXISTS college;
SET FOREIGN_KEY_CHECKS = 1;


-- ---------------------------------------------------------------------------
-- 1. 学院
-- ---------------------------------------------------------------------------
CREATE TABLE college (
  id            INT           NOT NULL AUTO_INCREMENT COMMENT '学院ID',
  name          VARCHAR(64)   NOT NULL                COMMENT '学院名称，如"计算机学院""通信与信息工程学院"',
  code          VARCHAR(16)   NOT NULL                COMMENT '学院代码，如 CS、CE',
  dean          VARCHAR(32)   DEFAULT NULL            COMMENT '院长姓名',
  founded_year  SMALLINT      DEFAULT NULL            COMMENT '成立年份',
  PRIMARY KEY (id),
  UNIQUE KEY uk_college_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='学院基本信息表，一个学院下设多个专业';


-- ---------------------------------------------------------------------------
-- 2. 专业
-- ---------------------------------------------------------------------------
CREATE TABLE major (
  id             INT          NOT NULL AUTO_INCREMENT COMMENT '专业ID',
  college_id     INT          NOT NULL                COMMENT '所属学院ID，关联 college.id',
  name           VARCHAR(64)  NOT NULL                COMMENT '专业名称，如"软件工程""计算机科学与技术"',
  code           VARCHAR(16)  NOT NULL                COMMENT '专业代码',
  degree_type    VARCHAR(16)  NOT NULL                COMMENT '学位类型：工学 / 理学 / 管理学',
  duration_years TINYINT      NOT NULL DEFAULT 4      COMMENT '学制年限，本科通常为4年',
  PRIMARY KEY (id),
  UNIQUE KEY uk_major_code (code),
  KEY idx_major_college (college_id),
  CONSTRAINT fk_major_college FOREIGN KEY (college_id) REFERENCES college (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='专业表，一个学院下设多个专业，一个专业有多个学生';


-- ---------------------------------------------------------------------------
-- 3. 学生
-- ---------------------------------------------------------------------------
CREATE TABLE student (
  id                INT          NOT NULL AUTO_INCREMENT COMMENT '学生ID',
  student_no        VARCHAR(20)  NOT NULL                COMMENT '学号',
  name              VARCHAR(32)  NOT NULL                COMMENT '学生姓名',
  gender            VARCHAR(4)   NOT NULL                COMMENT '性别：男 / 女',
  major_id          INT          NOT NULL                COMMENT '所属专业ID，关联 major.id',
  enroll_year       SMALLINT     NOT NULL                COMMENT '入学年份，如 2021',
  graduation_year   SMALLINT     NOT NULL                COMMENT '毕业年份，如 2025。毕业届别按此字段判断',
  hometown_province VARCHAR(32)  NOT NULL                COMMENT '生源省份，如"陕西""河南"',
  gpa               DECIMAL(3,2) DEFAULT NULL            COMMENT '平均绩点，满分4.00，范围 0.00~4.00',
  PRIMARY KEY (id),
  UNIQUE KEY uk_student_no (student_no),
  KEY idx_student_major (major_id),
  KEY idx_student_grad_year (graduation_year),
  KEY idx_student_province (hometown_province),
  CONSTRAINT fk_student_major FOREIGN KEY (major_id) REFERENCES major (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='学生基本信息表。统计"某届毕业生"时用 graduation_year 字段';


-- ---------------------------------------------------------------------------
-- 4. 课程
-- ---------------------------------------------------------------------------
CREATE TABLE course (
  id          INT          NOT NULL AUTO_INCREMENT COMMENT '课程ID',
  code        VARCHAR(16)  NOT NULL                COMMENT '课程编号，如 CS201',
  name        VARCHAR(64)  NOT NULL                COMMENT '课程名称，如"数据结构""数据库系统原理"',
  college_id  INT          NOT NULL                COMMENT '开课学院ID，关联 college.id',
  credit      DECIMAL(3,1) NOT NULL                COMMENT '学分，如 3.0、4.5',
  course_type VARCHAR(16)  NOT NULL                COMMENT '课程类型：必修 / 选修 / 实践',
  PRIMARY KEY (id),
  UNIQUE KEY uk_course_code (code),
  KEY idx_course_college (college_id),
  CONSTRAINT fk_course_college FOREIGN KEY (college_id) REFERENCES college (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='课程表。course_type 用于区分必修课与选修课';


-- ---------------------------------------------------------------------------
-- 5. 选课与成绩
-- ---------------------------------------------------------------------------
CREATE TABLE course_enrollment (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '选课记录ID',
  student_id INT          NOT NULL                COMMENT '学生ID，关联 student.id',
  course_id  INT          NOT NULL                COMMENT '课程ID，关联 course.id',
  term       VARCHAR(16)  NOT NULL                COMMENT '学期，格式 YYYY-YYYY-N，如"2023-2024-1"表示2023学年第一学期',
  score      DECIMAL(5,2) DEFAULT NULL            COMMENT '百分制成绩，范围 0~100。NULL 表示未出分',
  is_retake  TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否重修：1=是，0=否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_enroll (student_id, course_id, term),
  KEY idx_enroll_course (course_id),
  KEY idx_enroll_term (term),
  CONSTRAINT fk_enroll_student FOREIGN KEY (student_id) REFERENCES student (id),
  CONSTRAINT fk_enroll_course  FOREIGN KEY (course_id)  REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='学生选课与成绩表，学生与课程的多对多关联。统计课程平均分时注意排除 score 为 NULL 的记录';


-- ---------------------------------------------------------------------------
-- 6. 企业
-- ---------------------------------------------------------------------------
CREATE TABLE company (
  id        INT          NOT NULL AUTO_INCREMENT COMMENT '企业ID',
  name      VARCHAR(128) NOT NULL                COMMENT '企业名称，如"字节跳动""华为技术有限公司"',
  industry  VARCHAR(32)  NOT NULL                COMMENT '所属行业：互联网 / 通信 / 金融 / 制造业 / 教育',
  scale     VARCHAR(32)  DEFAULT NULL            COMMENT '企业规模：0-99人 / 100-499人 / 500-999人 / 1000-4999人 / 5000人以上',
  city      VARCHAR(32)  NOT NULL                COMMENT '总部所在城市，如"北京""西安""深圳"',
  is_listed TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否上市：1=是，0=否',
  PRIMARY KEY (id),
  KEY idx_company_industry (industry),
  KEY idx_company_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='招聘企业信息表';


-- ---------------------------------------------------------------------------
-- 7. 招聘岗位
-- ---------------------------------------------------------------------------
CREATE TABLE job_posting (
  id                INT           NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  company_id        INT           NOT NULL                COMMENT '招聘企业ID，关联 company.id',
  title             VARCHAR(128)  NOT NULL                COMMENT '岗位名称，如"Java后端开发工程师""算法工程师"',
  city              VARCHAR(32)   NOT NULL                COMMENT '工作城市，即学生实际就业地点。判断"留在某地就业"用这个字段，不要用 company.city（那是企业总部城市）',
  salary_min        INT           DEFAULT NULL            COMMENT '月薪下限，单位：元',
  salary_max        INT           DEFAULT NULL            COMMENT '月薪上限，单位：元',
  headcount         SMALLINT      NOT NULL DEFAULT 1      COMMENT '招聘人数',
  required_major_id INT           DEFAULT NULL            COMMENT '目标专业ID，关联 major.id。NULL 表示不限专业',
  job_type          VARCHAR(16)   NOT NULL                COMMENT '岗位类型：校招 / 社招 / 实习',
  publish_date      DATE          NOT NULL                COMMENT '岗位发布时间',
  PRIMARY KEY (id),
  KEY idx_job_company (company_id),
  KEY idx_job_city (city),
  KEY idx_job_major (required_major_id),
  KEY idx_job_publish (publish_date),
  CONSTRAINT fk_job_company FOREIGN KEY (company_id) REFERENCES company (id),
  CONSTRAINT fk_job_major   FOREIGN KEY (required_major_id) REFERENCES major (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='招聘岗位表。薪资为月薪区间，单位元；统计平均薪资时用 (salary_min+salary_max)/2';


-- ---------------------------------------------------------------------------
-- 8. 投递记录
-- ---------------------------------------------------------------------------
CREATE TABLE job_application (
  id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '投递记录ID',
  student_id INT         NOT NULL                COMMENT '投递学生ID，关联 student.id',
  job_id     INT         NOT NULL                COMMENT '投递岗位ID，关联 job_posting.id',
  apply_date DATE        NOT NULL                COMMENT '投递日期',
  status     VARCHAR(16) NOT NULL                COMMENT '当前状态：已投递 / 笔试 / 面试 / 已录用 / 已拒绝',
  channel    VARCHAR(16) DEFAULT NULL            COMMENT '投递渠道：官网 / 招聘平台 / 内推 / 校招宣讲',
  PRIMARY KEY (id),
  UNIQUE KEY uk_apply (student_id, job_id),
  KEY idx_apply_student (student_id),
  KEY idx_apply_job (job_id),
  KEY idx_apply_status (status),
  CONSTRAINT fk_apply_student FOREIGN KEY (student_id) REFERENCES student (id),
  CONSTRAINT fk_apply_job     FOREIGN KEY (job_id)     REFERENCES job_posting (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='学生投递记录表。状态是漏斗：已投递 > 笔试 > 面试 > 已录用（或已拒绝）';


-- ---------------------------------------------------------------------------
-- 9. Offer 记录
-- ---------------------------------------------------------------------------
CREATE TABLE offer (
  id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Offer ID',
  student_id     INT         NOT NULL                COMMENT '学生ID，关联 student.id',
  company_id     INT         NOT NULL                COMMENT '发放Offer的企业ID，关联 company.id',
  job_id         INT         DEFAULT NULL            COMMENT '对应岗位ID，关联 job_posting.id',
  monthly_salary INT         NOT NULL                COMMENT '月薪，单位：元。统计"起薪"时用此字段',
  offer_date     DATE        NOT NULL                COMMENT 'Offer发放日期',
  sign_status    VARCHAR(16) NOT NULL                COMMENT '签约状态：已签约 / 未签约 / 已违约',
  PRIMARY KEY (id),
  KEY idx_offer_student (student_id),
  KEY idx_offer_company (company_id),
  KEY idx_offer_date (offer_date),
  CONSTRAINT fk_offer_student FOREIGN KEY (student_id) REFERENCES student (id),
  CONSTRAINT fk_offer_company FOREIGN KEY (company_id) REFERENCES company (id),
  CONSTRAINT fk_offer_job     FOREIGN KEY (job_id)     REFERENCES job_posting (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Offer发放记录表。一个学生可能拿到多个Offer。统计就业薪资时通常只看 sign_status=''已签约''';


-- ---------------------------------------------------------------------------
-- 10. 就业统计（月度快照）
-- ---------------------------------------------------------------------------
CREATE TABLE employment_stat (
  id              INT          NOT NULL AUTO_INCREMENT COMMENT '统计记录ID',
  stat_month      VARCHAR(7)   NOT NULL                COMMENT '统计月份，格式 YYYY-MM，如"2025-06"',
  college_id      INT          NOT NULL                COMMENT '学院ID，关联 college.id',
  major_id        INT          DEFAULT NULL            COMMENT '专业ID，关联 major.id。NULL 表示学院级汇总',
  graduated_count INT          NOT NULL DEFAULT 0      COMMENT '该月已毕业/应届人数',
  employed_count  INT          NOT NULL DEFAULT 0      COMMENT '已就业人数（含签约、灵活就业、升学）',
  further_study_count INT      NOT NULL DEFAULT 0      COMMENT '升学人数（考研、保研、出国）',
  avg_salary      INT          DEFAULT NULL            COMMENT '该批次平均月薪，单位：元',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stat (stat_month, college_id, major_id),
  KEY idx_stat_month (stat_month),
  KEY idx_stat_college (college_id),
  CONSTRAINT fk_stat_college FOREIGN KEY (college_id) REFERENCES college (id),
  CONSTRAINT fk_stat_major   FOREIGN KEY (major_id)   REFERENCES major (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='就业统计月度快照表。就业率 = employed_count / graduated_count，employed_count 含签约、灵活就业和升学。同时含学院级（major_id 为 NULL）和专业级两种粒度，按专业统计需过滤 major_id IS NOT NULL';


-- ============================================================================
-- 只读账号（安全沙箱的第一层防线，也是最可靠的一层）
-- ----------------------------------------------------------------------------
-- 这个账号只能 SELECT，从数据库层面断掉 LLM 生成 DROP/DELETE 的可能性。
-- 这是"纵深防御"的最后一层——就算应用层代码全写错了，库也删不掉。
--
-- ⚠️ 密码是本地开发用的弱口令，任何真实部署都必须换掉。
-- ============================================================================
CREATE USER IF NOT EXISTS 'askbase_ro'@'%' IDENTIFIED BY 'askbase_ro123';
GRANT SELECT ON askbase.* TO 'askbase_ro'@'%';
FLUSH PRIVILEGES;
