SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 造纸厂 · 制浆抄纸与纸卷切裁管理
-- 库名 paper_mill；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS paper_mill
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE paper_mill;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS cutting_issue_attempt;
DROP TABLE IF EXISTS cutting_issue;
DROP TABLE IF EXISTS finished_bundle;
DROP TABLE IF EXISTS cutting_order;
DROP TABLE IF EXISTS paper_roll;
DROP TABLE IF EXISTS paper_machine;
DROP TABLE IF EXISTS pulp_chest;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 制浆工段 · 浆池台账 ----------------
CREATE TABLE pulp_chest (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    chest_code    VARCHAR(32)  NOT NULL UNIQUE,
    chest_name    VARCHAR(64)  NOT NULL,
    pulp_type     VARCHAR(32)  NOT NULL COMMENT '针叶浆/阔叶浆/废纸浆/竹浆',
    capacity_m3   DECIMAL(10,2) NOT NULL,
    current_m3    DECIMAL(10,2) NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL COMMENT '在用/检修/停用',
    remark        VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 抄纸工段 · 纸机台账 ----------------
CREATE TABLE paper_machine (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    machine_code    VARCHAR(32) NOT NULL UNIQUE,
    machine_name    VARCHAR(64) NOT NULL,
    design_width_mm INT         NOT NULL COMMENT '设计幅宽，纸卷幅宽不得超过此值',
    design_speed_mpm INT        NOT NULL COMMENT '设计车速 m/min',
    status          VARCHAR(16) NOT NULL COMMENT '运行/停机/检修',
    last_maintain_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 抄纸工段 · 纸卷（母卷）----------------
CREATE TABLE paper_roll (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    roll_no      VARCHAR(32)  NOT NULL UNIQUE,
    machine_id   BIGINT       NOT NULL,
    grammage_gsm INT          NOT NULL COMMENT '定量 g/m2',
    width_mm     INT          NOT NULL,
    length_m     INT          NOT NULL COMMENT '剩余长度，切裁会从这里扣',
    weight_kg    DECIMAL(10,2) NOT NULL,
    grade        VARCHAR(16)  NOT NULL COMMENT '一等/二等/等外',
    status       VARCHAR(16)  NOT NULL COMMENT '待检/可用/已切完/报废',
    produced_at  DATETIME,
    CONSTRAINT fk_roll_machine FOREIGN KEY (machine_id) REFERENCES paper_machine (id),
    CONSTRAINT ck_roll_length_nonnegative CHECK (length_m >= 0),
    CONSTRAINT ck_roll_weight_nonnegative CHECK (weight_kg >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 切纸工段 · 切纸订单 ----------------
CREATE TABLE cutting_order (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no         VARCHAR(32)  NOT NULL UNIQUE,
    customer_name    VARCHAR(64)  NOT NULL,
    target_grammage  INT          NOT NULL COMMENT '目标定量',
    target_width_mm  INT          NOT NULL COMMENT '目标幅宽，必须 <= 母卷幅宽',
    target_weight_kg DECIMAL(10,2) NOT NULL,
    done_weight_kg   DECIMAL(10,2) NOT NULL DEFAULT 0,
    assigned_roll_id BIGINT       NULL COMMENT '已排产母卷',
    status           VARCHAR(16)  NOT NULL COMMENT '待排产/切裁中/已完成/已取消',
    deadline         DATE,
    CONSTRAINT fk_order_assigned_roll FOREIGN KEY (assigned_roll_id) REFERENCES paper_roll (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 切纸工段 · 切裁领料成功记录 ----------------
CREATE TABLE cutting_issue (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL UNIQUE,
    order_id         BIGINT       NOT NULL,
    roll_id          BIGINT       NOT NULL,
    issue_length_m   INT          NOT NULL COMMENT '本次领料长度（米）',
    issue_weight_kg  DECIMAL(10,2) NOT NULL COMMENT '本次领料重量（千克）',
    before_length_m  INT          NOT NULL COMMENT '领料前母卷余长',
    before_weight_kg DECIMAL(10,2) NOT NULL COMMENT '领料前母卷余重',
    after_length_m   INT          NOT NULL COMMENT '领料后母卷余长',
    after_weight_kg  DECIMAL(10,2) NOT NULL COMMENT '领料后母卷余重',
    operated_at      DATETIME     NOT NULL,
    operator_name    VARCHAR(32)  NOT NULL,
    CONSTRAINT fk_issue_order FOREIGN KEY (order_id) REFERENCES cutting_order (id),
    CONSTRAINT fk_issue_roll FOREIGN KEY (roll_id) REFERENCES paper_roll (id),
    CONSTRAINT ck_issue_length_positive CHECK (issue_length_m > 0),
    CONSTRAINT ck_issue_weight_positive CHECK (issue_weight_kg > 0),
    CONSTRAINT ck_issue_after_length CHECK (after_length_m >= 0),
    CONSTRAINT ck_issue_after_weight CHECK (after_weight_kg >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 切纸工段 · 领料请求结果（含失败原因，用于幂等与刷新追溯） ----------------
CREATE TABLE cutting_issue_attempt (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL UNIQUE,
    order_id         BIGINT       NULL,
    roll_id          BIGINT       NULL,
    issue_length_m   INT          NULL,
    issue_weight_kg  DECIMAL(10,2) NULL,
    operator_name    VARCHAR(32)  NULL,
    payload_hash     CHAR(64)     NOT NULL,
    success          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0处理中, 1成功, 2失败',
    failure_reason   VARCHAR(512) NULL,
    issue_id         BIGINT       NULL,
    created_at       DATETIME     NOT NULL,
    finished_at      DATETIME     NULL,
    CONSTRAINT fk_attempt_issue FOREIGN KEY (issue_id) REFERENCES cutting_issue (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_cutting_issue_order_time ON cutting_issue (order_id, operated_at DESC);
CREATE INDEX idx_cutting_issue_roll_time ON cutting_issue (roll_id, operated_at DESC);
CREATE INDEX idx_issue_attempt_order_time ON cutting_issue_attempt (order_id, created_at DESC);
CREATE INDEX idx_issue_attempt_roll_time ON cutting_issue_attempt (roll_id, created_at DESC);

-- ---------------- 成品库 · 成品件与出入库 ----------------
CREATE TABLE finished_bundle (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    bundle_no     VARCHAR(32)  NOT NULL UNIQUE,
    order_id      BIGINT       NOT NULL,
    grammage_gsm  INT          NOT NULL,
    width_mm      INT          NOT NULL,
    sheet_count   INT          NOT NULL,
    weight_kg     DECIMAL(10,2) NOT NULL,
    warehouse_zone VARCHAR(32) NOT NULL COMMENT '库区 A/B/C',
    status        VARCHAR(16)  NOT NULL COMMENT '已入库/已出库',
    stored_at     DATETIME,
    outbound_at   DATETIME     NULL,
    CONSTRAINT fk_bundle_order FOREIGN KEY (order_id) REFERENCES cutting_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO pulp_chest (chest_code, chest_name, pulp_type, capacity_m3, current_m3, status, remark) VALUES
('PC-01', '1号针叶浆池', '针叶浆', 120.00,  86.50, '在用', '长纤维，配抄高强度纸'),
('PC-02', '2号阔叶浆池', '阔叶浆', 100.00,  92.00, '在用', '接近满池，补浆极易触发容量校验'),
('PC-03', '3号废纸浆池', '废纸浆', 150.00,  40.00, '在用', '脱墨浆'),
('PC-04', '4号竹浆池',   '竹浆',    80.00,   0.00, '检修', '内壁衬胶更换中'),
('PC-05', '5号备用浆池', '针叶浆',  60.00,  12.00, '停用', '停用池不允许补浆');

INSERT INTO paper_machine (machine_code, machine_name, design_width_mm, design_speed_mpm, status, last_maintain_at) VALUES
('PM-01', '1号纸机', 3200, 650, '运行', UTC_TIMESTAMP() - INTERVAL 12 DAY),
('PM-02', '2号纸机', 2400, 480, '运行', UTC_TIMESTAMP() - INTERVAL 30 DAY),
('PM-03', '3号纸机', 1800, 320, '停机', UTC_TIMESTAMP() - INTERVAL 90 DAY),
('PM-04', '4号纸机', 2800, 560, '检修', UTC_TIMESTAMP() - INTERVAL  5 DAY);

-- 纸卷：PM-01 设计幅宽 3200，PM-02 设计幅宽 2400
INSERT INTO paper_roll (roll_no, machine_id, grammage_gsm, width_mm, length_m, weight_kg, grade, status, produced_at) VALUES
('R-2026001', 1, 70, 3150, 12000, 2646.00, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('R-2026002', 1, 70, 3150,  9500, 2094.75, '二等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('R-2026003', 1, 80, 2400,  6000, 1152.00, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('R-2026004', 2, 60, 2350,  4200,  591.60, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('R-2026005', 2, 60, 2350,     0,    0.00, '二等', '已切完', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('R-2026006', 2, 90, 1200,  1500,  162.00, '等外', '待检',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('R-2026007', 1, 70, 3150,  8000, 1764.00, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

INSERT INTO cutting_order (order_no, customer_name, target_grammage, target_width_mm, target_weight_kg, done_weight_kg, assigned_roll_id, status, deadline) VALUES
('CO-2026001', '华东彩印', 70,  787, 1800.00,  620.00, 1, '切裁中', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 6 DAY),
('CO-2026002', '新华印务', 70, 1092, 2400.00, 2400.00, NULL, '已完成', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 3 DAY),
('CO-2026003', '时代包装', 60,  640,  900.00,    0.00, NULL, '待排产', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 10 DAY),
('CO-2026004', '联合书刊', 80,  850, 1500.00,  430.00, 3, '切裁中', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 4 DAY),
('CO-2026005', '老客户A',  90, 1500,  500.00,    0.00, NULL, '待排产', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 8 DAY);

INSERT INTO finished_bundle (bundle_no, order_id, grammage_gsm, width_mm, sheet_count, weight_kg, warehouse_zone, status, stored_at, outbound_at) VALUES
('FB-2026001', 1, 70, 787,  12000, 620.00, 'A区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL),
('FB-2026002', 2, 70, 1092, 18000, 1400.00, 'A区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, NULL),
('FB-2026003', 2, 70, 1092, 12000, 1000.00, 'B区', '已出库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
('FB-2026004', 4, 80,  850,  9000, 430.00, 'B区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL),
('FB-2026005', 1, 70, 787,  8000, 410.00, 'C区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, NULL);
