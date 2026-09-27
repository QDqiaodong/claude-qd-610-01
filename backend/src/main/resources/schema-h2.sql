-- H2（MySQL 兼容模式）本地验证用表结构 + 演示数据。
-- 生产 MySQL 的完整 schema 在 schema.sql；这里只建模块七联锁链路 + 车辆表，
-- 用于无 Docker/MySQL 环境下真实启动 Spring Boot 跑端到端验证。
SET MODE MySQL;

CREATE TABLE IF NOT EXISTS vehicle (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    plate_no      VARCHAR(20)  NOT NULL,
    driver_name   VARCHAR(30)  NOT NULL,
    phone         VARCHAR(20),
    load_capacity DECIMAL(8,2) NOT NULL DEFAULT 0.00,
    status        VARCHAR(20)  NOT NULL,
    required_license_class VARCHAR(10),
    pending_dispatch_flag  TINYINT NOT NULL DEFAULT 0,
    pending_dispatch_reason VARCHAR(200),
    lock_version  BIGINT       NOT NULL DEFAULT 0,
    trip_date     DATE,
    today_trips   INT          NOT NULL DEFAULT 0,
    remark        VARCHAR(100),
    PRIMARY KEY (id),
    CONSTRAINT uk_vehicle_plate UNIQUE (plate_no)
);

CREATE TABLE IF NOT EXISTS driver (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    driver_name   VARCHAR(30)  NOT NULL,
    phone         VARCHAR(20),
    license_class VARCHAR(10),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark        VARCHAR(100),
    PRIMARY KEY (id),
    CONSTRAINT uk_driver_name UNIQUE (driver_name)
);

CREATE TABLE IF NOT EXISTS credential_doc (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    owner_type      VARCHAR(10)  NOT NULL,
    owner_id        BIGINT       NOT NULL,
    owner_name      VARCHAR(30)  NOT NULL,
    doc_type        VARCHAR(30)  NOT NULL,
    version_no      INT          NOT NULL,
    group_uuid      VARCHAR(36)  NOT NULL,
    doc_number      VARCHAR(60)  NOT NULL,
    license_class   VARCHAR(10),
    subject_desc    VARCHAR(60),
    valid_from      DATE         NOT NULL,
    valid_until     DATE         NOT NULL,
    attachment_note VARCHAR(200),
    status          VARCHAR(20)  NOT NULL,
    handler         VARCHAR(30)  NOT NULL,
    reviewer        VARCHAR(30),
    reason          VARCHAR(200),
    created_at      DATETIME     NOT NULL,
    submitted_at    DATETIME,
    reviewed_at     DATETIME,
    closed_at       DATETIME,
    active_slot     VARCHAR(80) GENERATED ALWAYS AS
                        (CASE WHEN status = 'EFFECTIVE'
                              THEN CONCAT(owner_type, '#', CAST(owner_id AS VARCHAR), '#', doc_type)
                              ELSE NULL END),
    lock_version    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cred_active_slot UNIQUE (active_slot)
);
CREATE INDEX IF NOT EXISTS idx_cred_owner ON credential_doc(owner_type, owner_id, doc_type, version_no);
CREATE INDEX IF NOT EXISTS idx_cred_group ON credential_doc(group_uuid);
CREATE INDEX IF NOT EXISTS idx_cred_status ON credential_doc(status);

CREATE TABLE IF NOT EXISTS credential_doc_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT       NOT NULL,
    group_uuid  VARCHAR(36)  NOT NULL,
    action      VARCHAR(20)  NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20),
    operator    VARCHAR(30)  NOT NULL,
    reason      VARCHAR(200),
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_event_group ON credential_doc_event(group_uuid, created_at);

CREATE TABLE IF NOT EXISTS dispatch_trip (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    trip_no                VARCHAR(32)  NOT NULL,
    vehicle_id             BIGINT       NOT NULL,
    plate_no               VARCHAR(20)  NOT NULL,
    driver_id              BIGINT       NOT NULL,
    driver_name            VARCHAR(30)  NOT NULL,
    driver_license_class   VARCHAR(10),
    required_license_class VARCHAR(10),
    dispatched_at          DATETIME     NOT NULL,
    planned_return_at      DATETIME     NOT NULL,
    returned_at            DATETIME,
    status                 VARCHAR(20)  NOT NULL,
    pending_dispatch_flag  TINYINT      NOT NULL DEFAULT 0,
    pending_reason         VARCHAR(200),
    dispatcher             VARCHAR(30)  NOT NULL,
    remark                 VARCHAR(100),
    lock_version           BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_trip_no UNIQUE (trip_no)
);
CREATE INDEX IF NOT EXISTS idx_trip_vehicle ON dispatch_trip(vehicle_id, status);

CREATE TABLE IF NOT EXISTS dispatch_credential_snapshot (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    trip_id                BIGINT,
    owner_type             VARCHAR(10)  NOT NULL,
    owner_id               BIGINT       NOT NULL,
    owner_name             VARCHAR(30)  NOT NULL,
    doc_type               VARCHAR(30)  NOT NULL,
    doc_id                 BIGINT       NOT NULL,
    doc_version_no         INT          NOT NULL,
    doc_number             VARCHAR(60)  NOT NULL,
    license_class          VARCHAR(10),
    subject_desc           VARCHAR(60),
    valid_from             DATE         NOT NULL,
    valid_until            DATE         NOT NULL,
    attachment_note        VARCHAR(200),
    doc_status_at_dispatch VARCHAR(20)  NOT NULL DEFAULT 'EFFECTIVE',
    handler                VARCHAR(30),
    reviewer               VARCHAR(30),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_snapshot_doc ON dispatch_credential_snapshot(doc_id);

-- ---------------- 演示数据（CURRENT_DATE 为基准，保证跨天都有效） ----------------
INSERT INTO vehicle (id, plate_no, driver_name, phone, load_capacity, status, required_license_class,
                     pending_dispatch_flag, lock_version, trip_date, today_trips, remark) VALUES
    (1, '沪C·7T215', '刘德海', '13801770021', 4.50, 'IDLE', 'C1', 0, 0, CURRENT_DATE, 0, '废纸专线'),
    (2, '沪C·3M812', '孙立刚', '13801770034', 8.00, 'IDLE', 'B2', 0, 0, CURRENT_DATE, 0, '重料车'),
    (3, '沪A·8J773', '吴天明', '13801770046', 3.20, 'IDLE', 'C1', 0, 0, CURRENT_DATE, 0, '瓶砖专线'),
    (4, '沪D·9K015', '朱建华', '13801770058', 6.00, 'IDLE', 'B2', 0, 0, CURRENT_DATE, 0, '家电拆解'),
    (5, '沪C·0Y642', '顾永康', '13801770117', 9.50, 'REPAIR', 'B2', 0, 0, CURRENT_DATE, 0, '维修中'),
    (6, '苏E·2Q903', '张伟民', '13801770075', 1.80, 'IDLE', 'C1', 0, 0, CURRENT_DATE, 0, '三轮轻卡'),
    (7, '沪F·2B758', '谢金泉', '13801770134', 5.00, 'IDLE', 'B2', 0, 0, CURRENT_DATE, 0, '年检异常车');

INSERT INTO driver (id, driver_name, phone, license_class, status, remark) VALUES
    (1, '刘德海', '13801770021', 'B2', 'ACTIVE', '废纸专线主力'),
    (2, '孙立刚', '13801770034', 'A2', 'ACTIVE', '重料车'),
    (3, '吴天明', '13801770046', 'C1', 'ACTIVE', '瓶砖专线'),
    (4, '朱建华', '13801770058', 'C1', 'ACTIVE', '准驾不足 B2（演示不匹配）'),
    (5, '顾永康', '13801770117', 'B2', 'ACTIVE', '车维修'),
    (6, '张伟民', '13801770075', 'C1', 'ACTIVE', '三轮轻卡'),
    (7, '谢金泉', '13801770134', 'B2', 'ACTIVE', '年检异常车');

-- 司机证照：每人 2 证
-- 1 刘德海 B2 两证长期有效
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (1, 'DRIVER', 1, '刘德海', 'CRED_DRIVER_LICENSE', 1, 'g-d1-lic', 'D310101000001', 'B2', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'EFFECTIVE',
     '赵经办', '钱复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (2, 'DRIVER', 1, '刘德海', 'CRED_QUALIFICATION', 1, 'g-d1-qua', 'QG310001', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '资格证.jpg', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);
-- 2 孙立刚 A2 两证长期
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (3, 'DRIVER', 2, '孙立刚', 'CRED_DRIVER_LICENSE', 1, 'g-d2-lic', 'D310102000001', 'A2', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'EFFECTIVE',
     '赵经办', '钱复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (4, 'DRIVER', 2, '孙立刚', 'CRED_QUALIFICATION', 1, 'g-d2-qua', 'QG310002', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '资格证.jpg', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);
-- 3 吴天明 C1：驾驶证长期，资格证 5 天后到期（计划回场晚于有效期演示）
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (5, 'DRIVER', 3, '吴天明', 'CRED_DRIVER_LICENSE', 1, 'g-d3-lic', 'D310103000001', 'C1', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'EFFECTIVE',
     '赵经办', '钱复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (6, 'DRIVER', 3, '吴天明', 'CRED_QUALIFICATION', 1, 'g-d3-qua', 'QG310003', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('DAY', 5, CURRENT_DATE), '资格证.jpg（5 天后到期）', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);
-- 4 朱建华 仅 C1（准驾不匹配演示），两证长期
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (7, 'DRIVER', 4, '朱建华', 'CRED_DRIVER_LICENSE', 1, 'g-d4-lic', 'D310104000001', 'C1', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'EFFECTIVE',
     '赵经办', '钱复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (8, 'DRIVER', 4, '朱建华', 'CRED_QUALIFICATION', 1, 'g-d4-qua', 'QG310004', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '资格证.jpg', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);
-- 5 顾永康 B2 两证（车维修）
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (9, 'DRIVER', 5, '顾永康', 'CRED_DRIVER_LICENSE', 1, 'g-d5-lic', 'D310105000001', 'B2', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'EFFECTIVE',
     '赵经办', '钱复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (10, 'DRIVER', 5, '顾永康', 'CRED_QUALIFICATION', 1, 'g-d5-qua', 'QG310005', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '资格证.jpg', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);
-- 6 张伟民 C1 两证
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (11, 'DRIVER', 6, '张伟民', 'CRED_DRIVER_LICENSE', 1, 'g-d6-lic', 'D310106000001', 'C1', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'EFFECTIVE',
     '赵经办', '钱复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (12, 'DRIVER', 6, '张伟民', 'CRED_QUALIFICATION', 1, 'g-d6-qua', 'QG310006', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '资格证.jpg', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);
-- 7 谢金泉 B2：驾驶证待复核（“资料仍待复核”演示），资格证生效
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (13, 'DRIVER', 7, '谢金泉', 'CRED_DRIVER_LICENSE', 1, 'g-d7-lic', 'D310107000001', 'B2', NULL,
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 4, CURRENT_DATE), '驾驶证.pdf', 'PENDING_REVIEW',
     '赵经办', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, 0),
    (14, 'DRIVER', 7, '谢金泉', 'CRED_QUALIFICATION', 1, 'g-d7-qua', 'QG310007', NULL, '货运驾驶员',
     DATEADD('YEAR', -1, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '资格证.jpg', 'EFFECTIVE',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);

-- 车辆证照：车1~车6 三证齐全生效（车4 道路运输证 10 天后到期）；车7 年检撤销 + V2 待复核
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, reason, lock_version)
SELECT 100 + v.id * 10 + t.seq AS id, 'VEHICLE', v.id, v.plate_no, t.doc_type, 1,
       CONCAT('g-v', CAST(v.id AS VARCHAR), '-', t.suffix),
       CONCAT('NJ-BX-YS-', CAST(v.id AS VARCHAR), '-', t.suffix), NULL,
       CONCAT('车辆证照-', t.doc_name),
       DATEADD('DAY', -300, CURRENT_DATE), DATEADD('DAY', 400, CURRENT_DATE),
       CONCAT(t.doc_name, '.pdf'), 'EFFECTIVE', '周经办', '吴复核',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, 0
  FROM vehicle v
  CROSS JOIN (
      SELECT 'CRED_ANNUAL_INSPECTION' AS doc_type, 'insp' AS suffix, '年检' AS doc_name, 1 AS seq
      UNION ALL SELECT 'CRED_INSURANCE', 'ins', '交强险', 2
      UNION ALL SELECT 'CRED_TRANSPORT_PERMIT', 'permit', '道路运输证', 3
  ) t
 WHERE v.id IN (1, 2, 3, 5, 6);

-- 车4：三证，其中道路运输证 10 天后到期
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, lock_version) VALUES
    (201, 'VEHICLE', 4, '沪D·9K015', 'CRED_ANNUAL_INSPECTION', 1, 'g-v4-insp', 'NJ-4', NULL, '年检',
     DATEADD('DAY', -300, CURRENT_DATE), DATEADD('DAY', 400, CURRENT_DATE), '年检.jpg', 'EFFECTIVE',
     '周经办', '吴复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (202, 'VEHICLE', 4, '沪D·9K015', 'CRED_INSURANCE', 1, 'g-v4-ins', 'BX-4', NULL, '交强险',
     DATEADD('DAY', -200, CURRENT_DATE), DATEADD('DAY', 165, CURRENT_DATE), '保单.pdf', 'EFFECTIVE',
     '周经办', '吴复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0),
    (203, 'VEHICLE', 4, '沪D·9K015', 'CRED_TRANSPORT_PERMIT', 1, 'g-v4-permit', 'YS-4', NULL, '道路运输证',
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('DAY', 10, CURRENT_DATE), '运输证.jpg（10 天后到期）', 'EFFECTIVE',
     '周经办', '吴复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0);

-- 车7：年检 V1 已撤销、V2 待复核；保险/运输证生效
INSERT INTO credential_doc (id, owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
    doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note, status,
    handler, reviewer, created_at, submitted_at, reviewed_at, closed_at, reason, lock_version) VALUES
    (301, 'VEHICLE', 7, '沪F·2B758', 'CRED_ANNUAL_INSPECTION', 1, 'g-v7-insp', 'NJ-7-OLD', NULL, '年检旧',
     DATEADD('DAY', -300, CURRENT_DATE), DATEADD('DAY', 65, CURRENT_DATE), '年检旧.jpg', 'REVOKED',
     '孙经办', '李复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
     '尾气检测数据造假，撤销检验合格标志', 0),
    (302, 'VEHICLE', 7, '沪F·2B758', 'CRED_ANNUAL_INSPECTION', 2, 'g-v7-insp', 'NJ-7-NEW', NULL, '年检复检',
     DATEADD('DAY', -1, CURRENT_DATE), DATEADD('DAY', 364, CURRENT_DATE), '复检标志.pdf', 'PENDING_REVIEW',
     '孙经办', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, NULL, 0),
    (303, 'VEHICLE', 7, '沪F·2B758', 'CRED_INSURANCE', 1, 'g-v7-ins', 'BX-7', NULL, '交强险',
     DATEADD('DAY', -200, CURRENT_DATE), DATEADD('DAY', 165, CURRENT_DATE), '保单.pdf', 'EFFECTIVE',
     '周经办', '吴复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, 0),
    (304, 'VEHICLE', 7, '沪F·2B758', 'CRED_TRANSPORT_PERMIT', 1, 'g-v7-permit', 'YS-7', NULL, '道路运输证',
     DATEADD('YEAR', -2, CURRENT_DATE), DATEADD('YEAR', 2, CURRENT_DATE), '运输证.jpg', 'EFFECTIVE',
     '周经办', '吴复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, 0);
