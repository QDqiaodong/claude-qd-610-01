-- =====================================================================
-- 废品回收站（再生资源回收中转站）初始化脚本
-- ---------------------------------------------------------------------
-- 设计口径：
--   1. ddl-auto 关着（application.yml 里 spring.jpa.hibernate.ddl-auto=none），
--      表结构与初始数据全靠这份脚本；字段名必须和 JPA 实体上的 @Column 一一对应。
--   2. 演示数据一律以 CURDATE() 为基准往前推，保证"今天"永远有数据，
--      首页指标卡片和日结对账报表不会出现空盘。
--   3. 金额口径：回收支出 = Σ(明细重量 × 当日回收单价)，
--      出售收入 = Σ(明细重量 × 当日出售单价)，毛利 = 收入 - 支出。
--      演示数据按这个口径算过：磅单 total_amount = Σ明细小计，
--      明细重量合计 ≤ 毛重 - 皮重（差额是扣杂）。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------- 1. 建表 ----------------------------

CREATE TABLE IF NOT EXISTS recycle_category (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_no   VARCHAR(32)  NOT NULL COMMENT '品类编号',
    category_name VARCHAR(50)  NOT NULL COMMENT '品类名称',
    unit          VARCHAR(10)  NOT NULL COMMENT '计量单位：KG 公斤 / SET 台',
    buy_price     DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '当日回收单价（元）',
    sell_price    DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '当日出售单价（元）',
    stock_flag    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否收储：1 收储 / 0 停收（列名避开 MySQL 关键字 STORED）',
    remark        VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    updated_at    DATETIME              DEFAULT NULL COMMENT '最后一次调价时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_no (category_no),
    KEY idx_category_stored (stock_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回收品类与回收价';

CREATE TABLE IF NOT EXISTS weigh_ticket (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_no           VARCHAR(32)  NOT NULL COMMENT '磅单号：WB + yyyyMMdd + 三位序号',
    customer_name       VARCHAR(50)  NOT NULL COMMENT '客户（个人或商户）',
    customer_type       VARCHAR(20)  NOT NULL COMMENT 'PERSONAL 个人 / MERCHANT 商户',
    weigh_time          DATETIME     NOT NULL COMMENT '过磅时间',
    stat_date           DATE         NOT NULL COMMENT '归属统计日期',
    plate_no            VARCHAR(20)           DEFAULT NULL COMMENT '车牌',
    gross_weight        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '毛重（原始建档值，永不覆盖）',
    tare_weight         DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '皮重（原始建档值，永不覆盖）',
    net_weight          DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '净重 = 毛重 - 皮重（原始建档值，永不覆盖）',
    total_amount        DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '总金额 = Σ明细小计（原始建档金额，永不覆盖）',
    status              VARCHAR(20)  NOT NULL COMMENT 'PENDING 待结算 / SETTLED 已结算 / VOID 作废',
    remark              VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    created_at          DATETIME              DEFAULT NULL COMMENT '建档时间',
    -- 复称闭环（模块六）：原始三项重量与 total_amount 永不改写，结算与报表一律以"当前有效版本"为准
    frozen_flag         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '结算冻结：1 复称处理中冻结 / 0 可结算',
    frozen_reason       VARCHAR(200)          DEFAULT NULL COMMENT '冻结原因（给付款台与过磅台看的人话）',
    frozen_reweigh_no   VARCHAR(32)           DEFAULT NULL COMMENT '造成冻结的复称单号',
    active_version_no   INT          NOT NULL DEFAULT 1 COMMENT '当前有效重量版本号：1=原建档版本，复称放行后指向新版本',
    effective_net_weight   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '当前有效净重（结算口径）',
    effective_total_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '当前有效应付金额（结算口径）',
    lock_version        BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本（旧页面提交结算时据此识别数据已变化）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    KEY idx_ticket_stat_date (stat_date),
    KEY idx_ticket_status (status),
    KEY idx_ticket_frozen (frozen_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='过磅单主表';

-- 明细行：复合主键（磅单号 + 行号），对应实体里的 @IdClass(WeighTicketLineId.class)
CREATE TABLE IF NOT EXISTS weigh_ticket_line (
    ticket_no     VARCHAR(32)  NOT NULL COMMENT '磅单号（复合主键之一，外键指向 weigh_ticket.ticket_no）',
    line_no       INT          NOT NULL COMMENT '行号（复合主键之一，同一磅单内从 1 递增）',
    category_no   VARCHAR(32)  NOT NULL COMMENT '品类编号',
    category_name VARCHAR(50)  NOT NULL COMMENT '品类名称快照',
    unit          VARCHAR(10)  NOT NULL COMMENT '计量单位快照',
    weight        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '重量 / 台数',
    unit_price    DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '当日回收单价快照',
    subtotal      DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '小计 = 重量 × 单价',
    PRIMARY KEY (ticket_no, line_no),
    KEY idx_line_category (category_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='过磅单明细行';

CREATE TABLE IF NOT EXISTS vehicle (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    plate_no      VARCHAR(20)  NOT NULL COMMENT '车牌',
    driver_name   VARCHAR(30)  NOT NULL COMMENT '司机',
    phone         VARCHAR(20)           DEFAULT NULL COMMENT '随车电话',
    load_capacity DECIMAL(8,2) NOT NULL DEFAULT 0.00 COMMENT '额定载重（吨）',
    status        VARCHAR(20)  NOT NULL COMMENT 'IDLE 待命 / ON_ROAD 在途 / REPAIR 维修',
    required_license_class VARCHAR(10)      DEFAULT NULL COMMENT '派车所需最低准驾车型：C1/B2/A2/A1',
    pending_dispatch_flag  TINYINT(1) NOT NULL DEFAULT 0 COMMENT '待调度处置：在途期间生效证照被撤销/换版时置 1',
    pending_dispatch_reason VARCHAR(200)          DEFAULT NULL COMMENT '待调度处置原因（给调度员的人话）',
    lock_version  BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本（旧页面派车提交据此识别资料已变化）',
    trip_date     DATE                  DEFAULT NULL COMMENT '趟次归属日期（换了日期就重新计数）',
    today_trips   INT          NOT NULL DEFAULT 0 COMMENT '今日趟次',
    remark        VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vehicle_plate (plate_no),
    KEY idx_vehicle_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收运车辆与司机';

-- =====================================================================
-- 模块七：证照有效期联锁台 + 派车资格快照
-- ---------------------------------------------------------------------
-- 口径：
--   1. 证照按 司机证照 / 车辆证照 分开管理，每个业务键（owner + 证件类型）一条版本链，
--      草稿 → 待复核 → 生效 →（驳回/撤销/到期/被新版本替代），历史版本只追加不改写。
--   2. 同一业务键至多一份 EFFECTIVE：active_slot 是生成列（EFFECTIVE 时=业务键，否则 NULL），
--      uk_cred_active_slot 唯一索引在数据库层兜底——两名复核人同时放行新旧版本、
--      网络超时重试、旧页面重复提交，都只能落一份当前生效版本，落败方收 VERSION_CONFLICT。
--   3. 派车只认当前生效版本，并要求有效期覆盖到计划回场当日；出车瞬间把采用的版本
--      原样拷进 dispatch_credential_snapshot。之后换版/撤销/到期只给在途车打
--      “待调度处置”标志，历史快照永久不变。
-- =====================================================================

CREATE TABLE IF NOT EXISTS driver (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    driver_name   VARCHAR(30)  NOT NULL COMMENT '司机姓名',
    phone         VARCHAR(20)           DEFAULT NULL COMMENT '联系电话',
    license_class VARCHAR(10)           DEFAULT NULL COMMENT '登记准驾车型（权威口径以生效驾驶证证照为准）',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 在岗 / RESTING 休息 / RESIGNED 离职',
    remark        VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_name (driver_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='司机花名册';

CREATE TABLE IF NOT EXISTS credential_doc (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner_type      VARCHAR(10)  NOT NULL COMMENT 'DRIVER 司机证照 / VEHICLE 车辆证照',
    owner_id        BIGINT       NOT NULL COMMENT 'driver.id 或 vehicle.id',
    owner_name      VARCHAR(30)  NOT NULL COMMENT '司机姓名 / 车牌（快照冗余，列表免回表）',
    doc_type        VARCHAR(30)  NOT NULL COMMENT 'CRED_DRIVER_LICENSE 驾驶证 / CRED_QUALIFICATION 从业资格证 / CRED_ANNUAL_INSPECTION 年检 / CRED_INSURANCE 交强险 / CRED_TRANSPORT_PERMIT 道路运输证',
    version_no      INT          NOT NULL COMMENT '同一业务键版本号，从 1 递增',
    group_uuid      VARCHAR(36)  NOT NULL COMMENT '版本链归并号（同一证件换版沿用）',
    doc_number      VARCHAR(60)  NOT NULL COMMENT '证件号码',
    license_class   VARCHAR(10)           DEFAULT NULL COMMENT '准驾车型（仅驾驶证）',
    subject_desc    VARCHAR(60)           DEFAULT NULL COMMENT '适用车型/车辆说明',
    valid_from      DATE         NOT NULL COMMENT '有效期起',
    valid_until     DATE         NOT NULL COMMENT '有效期止',
    attachment_note VARCHAR(200)          DEFAULT NULL COMMENT '附件说明（扫描件/份数/纸质存放位置）',
    status          VARCHAR(20)  NOT NULL COMMENT 'DRAFT 草稿 / PENDING_REVIEW 待复核 / EFFECTIVE 生效 / REJECTED 驳回 / REVOKED 撤销 / EXPIRED 到期 / SUPERSEDED 被新版本替代',
    handler         VARCHAR(30)  NOT NULL COMMENT '经办人',
    reviewer        VARCHAR(30)           DEFAULT NULL COMMENT '复核人（不得是经办人本人）',
    reason          VARCHAR(200)          DEFAULT NULL COMMENT '驳回/撤销原因（必填，永久留痕）',
    created_at      DATETIME     NOT NULL COMMENT '草稿录入时间',
    submitted_at    DATETIME              DEFAULT NULL COMMENT '提交复核时间',
    reviewed_at     DATETIME              DEFAULT NULL COMMENT '复核时间',
    closed_at       DATETIME              DEFAULT NULL COMMENT '撤销/到期/被替代时间',
    active_slot     VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status = 'EFFECTIVE' THEN CONCAT(owner_type, '#', owner_id, '#', doc_type) ELSE NULL END) STORED COMMENT '生效占位（生成列）：唯一索引保证一个业务键只有一份生效版本',
    lock_version    BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本（超时重试/双复核并发据此报资料版本已变化）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cred_active_slot (active_slot),
    KEY idx_cred_owner (owner_type, owner_id, doc_type, version_no),
    KEY idx_cred_group (group_uuid),
    KEY idx_cred_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证照资料版本（旧版本永久保留，不被新内容改掉）';

CREATE TABLE IF NOT EXISTS credential_doc_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_id      BIGINT       NOT NULL COMMENT '证照版本主键',
    group_uuid  VARCHAR(36)  NOT NULL COMMENT '版本链归并号',
    action      VARCHAR(20)  NOT NULL COMMENT 'CREATE/SUBMIT/APPROVE/REJECT/REVOKE/EXPIRE/SUPERSEDE',
    from_status VARCHAR(20)           DEFAULT NULL COMMENT '动作前状态',
    to_status   VARCHAR(20)           DEFAULT NULL COMMENT '动作后状态',
    operator    VARCHAR(30)  NOT NULL COMMENT '操作人（到期扫描为 SYSTEM）',
    reason      VARCHAR(200)          DEFAULT NULL COMMENT '驳回/撤销/替代/到期原因',
    created_at  DATETIME     NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_event_doc (doc_id),
    KEY idx_event_group (group_uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证照版本变化轨迹（只追加）';

CREATE TABLE IF NOT EXISTS dispatch_trip (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trip_no                VARCHAR(32)  NOT NULL COMMENT '派车单号：PC + yyyyMMdd + 三位序号',
    vehicle_id             BIGINT       NOT NULL COMMENT '车辆',
    plate_no               VARCHAR(20)  NOT NULL COMMENT '车牌快照',
    driver_id              BIGINT       NOT NULL COMMENT '本次司机',
    driver_name            VARCHAR(30)  NOT NULL COMMENT '本次司机姓名快照',
    driver_license_class   VARCHAR(10)           DEFAULT NULL COMMENT '派车时司机准驾车型快照',
    required_license_class VARCHAR(10)           DEFAULT NULL COMMENT '车辆要求准驾车型快照',
    dispatched_at          DATETIME     NOT NULL COMMENT '派车确认时间',
    planned_return_at      DATETIME     NOT NULL COMMENT '计划回场时间（证照必须覆盖到此时点）',
    returned_at            DATETIME              DEFAULT NULL COMMENT '实际回场时间',
    status                 VARCHAR(20)  NOT NULL COMMENT 'ON_ROAD 在途 / RETURNED 已回场',
    pending_dispatch_flag  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '待调度处置：所采用证照在途期间被撤销/换版',
    pending_reason         VARCHAR(200)          DEFAULT NULL COMMENT '待调度处置原因',
    dispatcher             VARCHAR(30)  NOT NULL COMMENT '调度员',
    remark                 VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    lock_version           BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_no (trip_no),
    KEY idx_trip_vehicle (vehicle_id, status),
    KEY idx_trip_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出车单（一次派车一条，历史永久保留）';

CREATE TABLE IF NOT EXISTS dispatch_credential_snapshot (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trip_id                BIGINT                DEFAULT NULL COMMENT '所属出车单',
    owner_type             VARCHAR(10)  NOT NULL COMMENT 'DRIVER / VEHICLE',
    owner_id               BIGINT       NOT NULL COMMENT '司机/车辆主键',
    owner_name             VARCHAR(30)  NOT NULL COMMENT '姓名/车牌快照',
    doc_type               VARCHAR(30)  NOT NULL COMMENT '证件类型快照',
    doc_id                 BIGINT       NOT NULL COMMENT '采用的证照版本主键',
    doc_version_no         INT          NOT NULL COMMENT '采用版本号快照',
    doc_number             VARCHAR(60)  NOT NULL COMMENT '证件号码快照',
    license_class          VARCHAR(10)           DEFAULT NULL COMMENT '准驾车型快照',
    subject_desc           VARCHAR(60)           DEFAULT NULL COMMENT '适用车型/车辆快照',
    valid_from             DATE         NOT NULL COMMENT '有效期起快照',
    valid_until            DATE         NOT NULL COMMENT '有效期止快照',
    attachment_note        VARCHAR(200)          DEFAULT NULL COMMENT '附件说明快照',
    doc_status_at_dispatch VARCHAR(20)  NOT NULL DEFAULT 'EFFECTIVE' COMMENT '派车当时版本状态',
    handler                VARCHAR(30)           DEFAULT NULL COMMENT '经办人快照',
    reviewer               VARCHAR(30)           DEFAULT NULL COMMENT '复核人快照',
    PRIMARY KEY (id),
    KEY idx_snapshot_trip (trip_id),
    KEY idx_snapshot_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派车资格快照（出车当时采用的证照版本原样固化，换版不改历史）';

CREATE TABLE IF NOT EXISTS settlement (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    settle_no      VARCHAR(32)  NOT NULL COMMENT '结算单号：JS + yyyyMMdd + 四位序号',
    ticket_no      VARCHAR(32)  NOT NULL COMMENT '关联磅单号',
    customer_name  VARCHAR(50)  NOT NULL COMMENT '收款客户快照',
    pay_mode       VARCHAR(20)  NOT NULL COMMENT 'CASH 现金 / TRANSFER 转账 / DEDUCT 抵扣',
    payable_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '应付金额',
    paid_amount    DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '实付金额',
    amount_version INT          NOT NULL DEFAULT 1 COMMENT '付款时采用的磅单金额版本号（复称放行后会变）',
    settle_time    DATETIME     NOT NULL COMMENT '结算时间',
    operator       VARCHAR(30)  NOT NULL COMMENT '操作员',
    remark         VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_settle_no (settle_no),
    KEY idx_settle_ticket (ticket_no),
    KEY idx_settle_time (settle_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结算单';

-- =====================================================================
-- 模块六：复称复核台（发起 → 补录 → 复核放行/驳回 → 恢复结算 闭环）
-- ---------------------------------------------------------------------
-- 口径：
--   1. 只有 PENDING 磅单能发起；已结算/已作废不得倒退。
--   2. 同一磅单任一时刻只允许一条"未结束"复称：靠 uk_open_reweigh 部分唯一索引兜底
--      （open_flag=1 的行每磅单唯一，已结束的历史行 open_flag=0 不参与约束），
--      重复点击 / 双窗口并发 / 超时重试都不可能落下第二条。
--   3. 原始重量与金额永不覆盖；放行/通过后生成新的 weigh_ticket_version(+line)，
--      驳回则磅单回指 V1；结算与报表只认磅单 active_version_no 指向的版本。
-- =====================================================================

CREATE TABLE IF NOT EXISTS reweigh_order (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    reweigh_no            VARCHAR(32)  NOT NULL COMMENT '复称单号：FC + yyyyMMdd + 三位序号',
    ticket_no             VARCHAR(32)  NOT NULL COMMENT '关联磅单号',
    -- 发起时从磅单原样拷贝的快照：原毛/皮/净、原金额在本单内永久留痕
    origin_gross_weight   DECIMAL(12,2) NOT NULL COMMENT '原毛重快照',
    origin_tare_weight    DECIMAL(12,2) NOT NULL COMMENT '原皮重快照',
    origin_net_weight     DECIMAL(12,2) NOT NULL COMMENT '原净重快照',
    origin_total_amount   DECIMAL(14,2) NOT NULL COMMENT '原金额快照（按原单单价快照）',
    -- 复称后三项重量：先发起（可能只带车回皮读数，也允许一次录全），补录阶段补齐
    re_gross_weight       DECIMAL(12,2)          DEFAULT NULL COMMENT '复称毛重',
    re_tare_weight        DECIMAL(12,2)          DEFAULT NULL COMMENT '复称皮重',
    re_net_weight         DECIMAL(12,2)          DEFAULT NULL COMMENT '复称净重 = 复称毛重 - 复称皮重',
    diff_net_weight       DECIMAL(12,2)          DEFAULT NULL COMMENT '复称净重 - 原净重（带正负）',
    diff_within_threshold TINYINT(1)             DEFAULT NULL COMMENT '补录时判定：1 差异在 2kg 且 5‰ 内可现场放行 / 0 必须待复核',
    diff_reason           VARCHAR(200)           DEFAULT NULL COMMENT '差异原因（扣杂争议、地磅误差、车辆复称等）',
    -- INITIATED 已发起待补录 / AWAIT_RELEASE 待现场放行（差异小）/
    -- AWAIT_REVIEW 待复核（差异超限）/ RELEASED 现场放行 / APPROVED 复核通过 / REJECTED 复核驳回
    status                VARCHAR(20)  NOT NULL COMMENT '复称单状态',
    initiator             VARCHAR(30)  NOT NULL COMMENT '发起人（过磅人员）',
    initiated_at          DATETIME     NOT NULL COMMENT '发起时间',
    supplemented_at       DATETIME              DEFAULT NULL COMMENT '补录时间',
    releaser              VARCHAR(30)           DEFAULT NULL COMMENT '现场放行确认人（另一名当班人员）',
    released_at           DATETIME              DEFAULT NULL COMMENT '现场放行时间',
    reviewer              VARCHAR(30)           DEFAULT NULL COMMENT '复核人（不得是发起人本人）',
    reviewed_at           DATETIME              DEFAULT NULL COMMENT '复核时间',
    review_result         VARCHAR(20)           DEFAULT NULL COMMENT 'APPROVED 通过 / REJECTED 驳回',
    reject_reason         VARCHAR(200)          DEFAULT NULL COMMENT '驳回原因（驳回时必填，随单永久留痕）',
    adopted_version_no    INT                   DEFAULT NULL COMMENT '放行/通过后生成并采用的磅单版本号',
    client_request_id     VARCHAR(64)           DEFAULT NULL COMMENT '幂等键：同一发起人重复提交只落一条',
    open_flag             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否未结束：1 处理中 / 0 已放行通过或驳回',
    -- 部分唯一索引的载体（MySQL 没有 partial index，用生成列模拟）：
    -- 未结束时 = 磅单号，已结束时 = NULL；UNIQUE 忽略 NULL，于是"每磅单最多一条未结束"，历史行不限条数。
    open_slot             VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN open_flag = 1 THEN ticket_no ELSE NULL END) STORED COMMENT '未结束占位（生成列）',
    lock_version          BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本（审核改判并发时据此报 VERSION_CONFLICT）',
    created_at            DATETIME     NOT NULL COMMENT '落库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reweigh_no (reweigh_no),
    -- 幂等：同一发起人带同一 client_request_id 的重试返回同一处理结果
    UNIQUE KEY uk_reweigh_idempotent (client_request_id, initiator),
    -- 关键：同一磅单只允许一条未结束复称（open_slot 为生成列，已结束行是 NULL 不受约束）
    UNIQUE KEY uk_open_reweigh (open_slot),
    KEY idx_reweigh_status (status),
    KEY idx_reweigh_initiator (initiator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复称单（一张磅单的一次复称处理链）';

-- 复称单的明细重分配方案：补录时提交。品类、单位、单价全部沿用原单快照，
-- 只有重量允许重新分配；合计不得超过复称净重，金额按原单单价快照重算。
CREATE TABLE IF NOT EXISTS reweigh_line (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    reweigh_id      BIGINT       NOT NULL COMMENT '复称单主键',
    line_no         INT          NOT NULL COMMENT '行号（沿用原单行号）',
    category_no     VARCHAR(32)  NOT NULL COMMENT '品类编号（原单快照）',
    category_name   VARCHAR(50)  NOT NULL COMMENT '品类名称（原单快照）',
    unit            VARCHAR(10)  NOT NULL COMMENT '计量单位（原单快照）',
    origin_weight   DECIMAL(12,2) NOT NULL COMMENT '原明细数量快照',
    unit_price      DECIMAL(10,2) NOT NULL COMMENT '原单价快照（绝不取后来调过的牌价）',
    new_weight      DECIMAL(12,2)          DEFAULT NULL COMMENT '重新分配后的数量（补录时填）',
    subtotal        DECIMAL(14,2)          DEFAULT NULL COMMENT '重算小计 = 新数量 × 原单价快照',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reweigh_line (reweigh_id, line_no),
    KEY idx_reweigh_line_cat (category_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复称单明细：原数量/单价快照 + 重分配数量';

-- 磅单的有效重量版本：V1=建档版本（数据仍以 weigh_ticket / weigh_ticket_line 为准），
-- V2 起每放行/通过一次复称就新增一行；原始数据不可覆盖，版本链永久保留。
CREATE TABLE IF NOT EXISTS weigh_ticket_version (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_no      VARCHAR(32)  NOT NULL COMMENT '磅单号',
    version_no     INT          NOT NULL COMMENT '版本号：1 原始建档 / ≥2 复称生效版本',
    source_reweigh_no VARCHAR(32)         DEFAULT NULL COMMENT '本版本由哪张复称单产生（V1 为空）',
    gross_weight   DECIMAL(12,2) NOT NULL COMMENT '本版本毛重',
    tare_weight    DECIMAL(12,2) NOT NULL COMMENT '本版本皮重',
    net_weight     DECIMAL(12,2) NOT NULL COMMENT '本版本净重',
    total_amount   DECIMAL(14,2) NOT NULL COMMENT '本版本总金额（按原单单价快照算）',
    created_by     VARCHAR(30)  NOT NULL COMMENT '版本产生操作人（发起人）',
    created_at     DATETIME     NOT NULL COMMENT '版本产生时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_version (ticket_no, version_no),
    KEY idx_version_reweigh (source_reweigh_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='磅单重量版本链（原版本不可覆盖）';

-- 版本明细：V2+ 的重分配明细。V1 不复制（直接读 weigh_ticket_line），避免双写。
CREATE TABLE IF NOT EXISTS weigh_ticket_version_line (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    version_id    BIGINT       NOT NULL COMMENT '所属磅单版本主键',
    ticket_no     VARCHAR(32)  NOT NULL COMMENT '磅单号',
    version_no    INT          NOT NULL COMMENT '版本号',
    line_no       INT          NOT NULL COMMENT '行号',
    category_no   VARCHAR(32)  NOT NULL COMMENT '品类编号',
    category_name VARCHAR(50)  NOT NULL COMMENT '品类名称快照',
    unit          VARCHAR(10)  NOT NULL COMMENT '计量单位快照',
    weight        DECIMAL(12,2) NOT NULL COMMENT '本版本数量',
    unit_price    DECIMAL(10,2) NOT NULL COMMENT '单价（沿用原单快照）',
    subtotal      DECIMAL(14,2) NOT NULL COMMENT '小计',
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_line (ticket_no, version_no, line_no),
    KEY idx_vline_version (version_id),
    KEY idx_vline_category (category_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='磅单版本明细（V2+）';

-- 状态轨迹：append-only，任何环节都只追加不改写；页面完整时间线就靠它。
CREATE TABLE IF NOT EXISTS reweigh_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    reweigh_id   BIGINT       NOT NULL COMMENT '复称单主键',
    ticket_no    VARCHAR(32)  NOT NULL COMMENT '磅单号（冗余，方便按磅单查轨迹）',
    event_type   VARCHAR(30)  NOT NULL COMMENT 'INITIATED 发起 / SUPPLEMENTED 补录 / RELEASED 现场放行 / APPROVED 复核通过 / REJECTED 复核驳回 / SETTLEMENT_FROZEN 冻结 / SETTLEMENT_RESUMED 恢复结算',
    from_status  VARCHAR(20)           DEFAULT NULL COMMENT '变更前状态',
    to_status    VARCHAR(20)           DEFAULT NULL COMMENT '变更后状态',
    operator     VARCHAR(30)  NOT NULL COMMENT '操作人',
    note         VARCHAR(200)          DEFAULT NULL COMMENT '说明（差异原因 / 驳回原因等）',
    created_at   DATETIME     NOT NULL COMMENT '事件时间',
    PRIMARY KEY (id),
    KEY idx_event_reweigh (reweigh_id),
    KEY idx_event_ticket (ticket_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复称状态轨迹（只追加）';

-- ---------------------- 2. 演示数据：日期变量 ----------------------

SET @today     = CURDATE();
SET @d1        = DATE_SUB(CURDATE(), INTERVAL 1 DAY);
SET @d2        = DATE_SUB(CURDATE(), INTERVAL 2 DAY);
SET @d3        = DATE_SUB(CURDATE(), INTERVAL 3 DAY);
SET @d4        = DATE_SUB(CURDATE(), INTERVAL 4 DAY);
SET @d5        = DATE_SUB(CURDATE(), INTERVAL 5 DAY);

-- 磅单号里的日期串，避免每条 INSERT 都重复写一长串 DATE_FORMAT
SET @k0 = DATE_FORMAT(@today, '%Y%m%d');
SET @k1 = DATE_FORMAT(@d1,    '%Y%m%d');
SET @k2 = DATE_FORMAT(@d2,    '%Y%m%d');
SET @k3 = DATE_FORMAT(@d3,    '%Y%m%d');
SET @k4 = DATE_FORMAT(@d4,    '%Y%m%d');
SET @k5 = DATE_FORMAT(@d5,    '%Y%m%d');

-- ---------------------- 3. 回收品类（13 个） ----------------------

INSERT INTO recycle_category
    (category_no, category_name, unit, buy_price, sell_price, stock_flag, remark, updated_at) VALUES
    ('RC001', '黄板纸',     'KG',  1.35,  1.75, 1, '打包压块后直供造纸厂',   NOW()),
    ('RC002', '书报纸',     'KG',  0.85,  1.15, 1, '含水率超 12% 要扣点',   NOW()),
    ('RC003', '瓦楞纸箱',   'KG',  1.10,  1.45, 1, '电商退货旺季价偏高',     NOW()),
    ('RC004', 'PET 瓶砖',   'KG',  2.60,  3.40, 1, '去标去盖后可上浮 0.1',  NOW()),
    ('RC005', 'HDPE 硬塑料','KG',  3.20,  4.20, 1, '需破碎清洗',             NOW()),
    ('RC006', '废铁',       'KG',  2.05,  2.65, 1, '重料，走地磅二次复称',   NOW()),
    ('RC007', '废铝',       'KG',  9.80, 11.60, 1, '易拉罐与型材同价',       NOW()),
    ('RC008', '紫铜',       'KG', 46.00, 52.00, 1, '高值品类，当日结清',     NOW()),
    ('RC009', '碎玻璃',     'KG',  0.28,  0.42, 1, '利润薄，靠量走',         NOW()),
    ('RC010', '废旧家电',   'SET', 25.00, 38.00, 1, '按台计，含氟需单独登记', NOW()),
    ('RC011', '废旧衣物',   'KG',  0.60,  1.05, 1, '夏装出口，冬装内销',     NOW()),
    ('RC012', '泡沫塑料',   'KG',  1.50,  2.30, 0, '库容不足，本日停收',     NOW()),
    ('RC013', '废旧小家电', 'SET',  8.00, 14.00, 1, '电饭煲 / 风扇 / 台灯',   NOW());

-- ---------------------- 4. 车辆与司机（12 台） ----------------------

INSERT INTO vehicle
    (plate_no, driver_name, phone, load_capacity, status, required_license_class,
     pending_dispatch_flag, lock_version, trip_date, today_trips, remark) VALUES
    ('沪C·7T215', '刘德海', '13801770021',  4.50, 'ON_ROAD', 'C1', 0, 0, @today, 3, '废纸专线，上午跑商超'),
    ('沪C·3M812', '孙立刚', '13801770034',  8.00, 'ON_ROAD', 'B2', 0, 0, @today, 2, '重料车，带随车吊'),
    ('沪A·8J773', '吴天明', '13801770046',  3.20, 'ON_ROAD', 'C1', 0, 0, @today, 2, '塑料瓶砖专线'),
    ('沪D·9K015', '朱建华', '13801770058',  6.00, 'ON_ROAD', 'B2', 0, 0, @today, 1, '家电拆解收运'),
    ('沪E·5R026', '钱志强', '13801770075', 12.00, 'ON_ROAD', 'B2', 0, 0, @today, 1, '工地废钢尾料'),
    ('苏E·2Q903', '张伟民', '13801770075',  1.80, 'IDLE',    'C1', 0, 0, @today, 2, '三轮轻卡，走街串巷'),
    ('苏E·4B209', '李国荣', '13801770082',  2.50, 'IDLE',    'C1', 0, 0, @today, 1, '社区回收点驳接'),
    ('苏U·6X401', '周春生', '13801770093',  3.00, 'IDLE',    'C1', 0, 0, @today, 1, '散户上门收运'),
    ('苏E·9Z118', '马成林', '13801770105',  4.00, 'IDLE',    'C1', 0, 0, @today, 0, '待命，接班司机 15:00 到'),
    ('沪C·0Y642', '顾永康', '13801770117',  9.50, 'REPAIR',  'B2', 0, 0, @today, 0, '后桥漏油，预计周五修好'),
    ('沪B·1N530', '姚红兵', '13801770126',  7.50, 'REPAIR',  'B2', 0, 0, @today, 0, '变速箱异响，返厂'),
    ('沪F·2B758', '谢金泉', '13801770134',  5.00, 'IDLE',    'B2', 0, 0, @today, 0, '年检到期，暂停派单');

-- ---------------------- 5. 过磅单主表（17 张） ----------------------

INSERT INTO weigh_ticket
    (ticket_no, customer_name, customer_type, weigh_time, stat_date, plate_no,
     gross_weight, tare_weight, net_weight, total_amount, status, remark, created_at) VALUES
    -- 今天：4 张，其中 1 张已结算，用来喂首页指标卡片
    (CONCAT('WB', @k0, '001'), '宏顺废品回收站', 'MERCHANT', TIMESTAMP(@today, '08:20:00'), @today,
     '沪C·7T215',  6420.00, 3920.00, 2500.00, 3465.50, 'PENDING', '整车纸塑混装，待复称', TIMESTAMP(@today, '08:25:00')),
    (CONCAT('WB', @k0, '002'), '张建军', 'PERSONAL', TIMESTAMP(@today, '09:45:00'), @today,
     '苏E·2Q903',  1180.00,  320.00,  860.00, 1068.00, 'SETTLED', '散户铁件 + 玻璃',      TIMESTAMP(@today, '09:50:00')),
    (CONCAT('WB', @k0, '003'), '城南家电拆解点', 'MERCHANT', TIMESTAMP(@today, '11:10:00'), @today,
     '沪D·9K015',  5200.00, 4600.00,  600.00,  320.00, 'PENDING', '按台计，含音箱 4 台',   TIMESTAMP(@today, '11:15:00')),
    (CONCAT('WB', @k0, '004'), '王丽娟', 'PERSONAL', TIMESTAMP(@today, '14:05:00'), @today,
     '散户自送',    460.00,   60.00,  400.00,  278.00, 'PENDING', '旧衣 + 报纸，现场结清', TIMESTAMP(@today, '14:10:00')),

    -- 昨天：4 张，1 张作废演示
    (CONCAT('WB', @k1, '001'), '宏顺废品回收站', 'MERCHANT', TIMESTAMP(@d1, '07:50:00'), @d1,
     '沪C·7T215',  8100.00, 4980.00, 3120.00, 3705.00, 'SETTLED', '纸类为主',             TIMESTAMP(@d1, '07:55:00')),
    (CONCAT('WB', @k1, '002'), '华宁路回收点', 'MERCHANT', TIMESTAMP(@d1, '10:30:00'), @d1,
     '沪C·3M812',  1210.00,  590.00,  620.00, 5233.00, 'SETTLED', '有色金属为主，高价品类单独过磅', TIMESTAMP(@d1, '10:35:00')),
    (CONCAT('WB', @k1, '003'), '陈国平', 'PERSONAL', TIMESTAMP(@d1, '13:20:00'), @d1,
     '苏U·6X401',   720.00,  180.00,  540.00,  808.00, 'PENDING', '塑料瓶砖 + 硬塑',       TIMESTAMP(@d1, '13:25:00')),
    (CONCAT('WB', @k1, '004'), '陈国平', 'PERSONAL', TIMESTAMP(@d1, '16:40:00'), @d1,
     '苏U·6X401',   300.00,  100.00,  200.00,   67.50, 'VOID',    '客户临时取消，作废重开', TIMESTAMP(@d1, '16:45:00')),

    -- 前天：3 张，其中一张是跨日未结算的单子
    (CONCAT('WB', @k2, '001'), '宏顺废品回收站', 'MERCHANT', TIMESTAMP(@d2, '08:05:00'), @d2,
     '沪C·7T215',  7680.00, 4520.00, 3160.00, 3400.00, 'SETTLED', '雨天，纸价按湿料扣点', TIMESTAMP(@d2, '08:10:00')),
    (CONCAT('WB', @k2, '002'), '福明菜场保洁组', 'PERSONAL', TIMESTAMP(@d2, '09:15:00'), @d2,
     '沪A·8J773',  2050.00,  420.00, 1630.00, 2046.00, 'SETTLED', '瓶砖 + 布条，抵扣月结', TIMESTAMP(@d2, '09:20:00')),
    (CONCAT('WB', @k2, '003'), '新河湾工地', 'MERCHANT', TIMESTAMP(@d2, '15:30:00'), @d2,
     '沪E·5R026', 12800.00, 7400.00, 5400.00, 8210.00, 'SETTLED', '工地废钢，挂账核验后付', TIMESTAMP(@d2, '15:35:00')),

    -- 大前天：3 张
    (CONCAT('WB', @k3, '001'), '宏顺废品回收站', 'MERCHANT', TIMESTAMP(@d3, '08:40:00'), @d3,
     '沪C·7T215',  9240.00, 5280.00, 3960.00, 5030.00, 'SETTLED', '四品类混装车',          TIMESTAMP(@d3, '08:45:00')),
    (CONCAT('WB', @k3, '002'), '周敏', 'PERSONAL', TIMESTAMP(@d3, '11:25:00'), @d3,
     '苏E·9Z118',   640.00,  140.00,  500.00,   98.00, 'SETTLED', '家电，按台计',          TIMESTAMP(@d3, '11:30:00')),
    (CONCAT('WB', @k3, '003'), '通源包装厂', 'MERCHANT', TIMESTAMP(@d3, '16:10:00'), @d3,
     '沪C·0Y642', 11200.00, 6100.00, 5100.00, 6300.00, 'SETTLED', '纸厂直送，量大价优',    TIMESTAMP(@d3, '16:15:00')),

    -- 更早两天：3 张
    (CONCAT('WB', @k4, '001'), '宏顺废品回收站', 'MERCHANT', TIMESTAMP(@d4, '09:00:00'), @d4,
     '沪C·7T215',  8900.00, 5010.00, 3890.00, 3382.00, 'SETTLED', '含玻璃，重车慢卸',      TIMESTAMP(@d4, '09:05:00')),
    (CONCAT('WB', @k4, '002'), '丁伟光', 'PERSONAL', TIMESTAMP(@d4, '14:50:00'), @d4,
     '苏E·4B209',   980.00,  210.00,  770.00, 1128.00, 'PENDING', '塑料类，等待二次分拣',  TIMESTAMP(@d4, '14:55:00')),
    (CONCAT('WB', @k5, '001'), '宏顺废品回收站', 'MERCHANT', TIMESTAMP(@d5, '10:05:00'), @d5,
     '沪C·7T215',  7300.00, 4240.00, 3060.00, 2925.00, 'SETTLED', '纸塑混装',              TIMESTAMP(@d5, '10:10:00'));

-- ---------------------- 6. 过磅单明细行（46 行） ----------------------
-- 每行的小计 = 重量 × 当日回收单价；同一张单的量、价与上面主表的 total_amount 保持一致。

INSERT INTO weigh_ticket_line
    (ticket_no, line_no, category_no, category_name, unit, weight, unit_price, subtotal) VALUES
    -- 今天 001：3 行
    (CONCAT('WB', @k0, '001'), 1, 'RC001', '黄板纸',   'KG', 1250.00, 1.35, 1687.50),
    (CONCAT('WB', @k0, '001'), 2, 'RC003', '瓦楞纸箱', 'KG',  860.00, 1.10,  946.00),
    (CONCAT('WB', @k0, '001'), 3, 'RC004', 'PET 瓶砖', 'KG',  320.00, 2.60,  832.00),
    -- 今天 002：2 行
    (CONCAT('WB', @k0, '002'), 1, 'RC006', '废铁',     'KG',  480.00, 2.05,  984.00),
    (CONCAT('WB', @k0, '002'), 2, 'RC009', '碎玻璃',   'KG',  300.00, 0.28,   84.00),
    -- 今天 003：2 行（按台计）
    (CONCAT('WB', @k0, '003'), 1, 'RC010', '废旧家电',   'SET',  8.00, 25.00, 200.00),
    (CONCAT('WB', @k0, '003'), 2, 'RC013', '废旧小家电', 'SET', 15.00,  8.00, 120.00),
    -- 今天 004：2 行
    (CONCAT('WB', @k0, '004'), 1, 'RC011', '废旧衣物', 'KG',  180.00, 0.60,  108.00),
    (CONCAT('WB', @k0, '004'), 2, 'RC002', '书报纸',   'KG',  200.00, 0.85,  170.00),

    -- 昨天 001：4 行
    (CONCAT('WB', @k1, '001'), 1, 'RC001', '黄板纸',   'KG', 1600.00, 1.35, 2160.00),
    (CONCAT('WB', @k1, '001'), 2, 'RC002', '书报纸',   'KG',  900.00, 0.85,  765.00),
    (CONCAT('WB', @k1, '001'), 3, 'RC003', '瓦楞纸箱', 'KG',  520.00, 1.10,  572.00),
    (CONCAT('WB', @k1, '001'), 4, 'RC004', 'PET 瓶砖', 'KG',   80.00, 2.60,  208.00),
    -- 昨天 002：3 行
    (CONCAT('WB', @k1, '002'), 1, 'RC007', '废铝', 'KG', 260.00,  9.80, 2548.00),
    (CONCAT('WB', @k1, '002'), 2, 'RC008', '紫铜', 'KG',  45.00, 46.00, 2070.00),
    (CONCAT('WB', @k1, '002'), 3, 'RC006', '废铁', 'KG', 300.00,  2.05,  615.00),
    -- 昨天 003：2 行
    (CONCAT('WB', @k1, '003'), 1, 'RC004', 'PET 瓶砖',    'KG', 200.00, 2.60, 520.00),
    (CONCAT('WB', @k1, '003'), 2, 'RC005', 'HDPE 硬塑料', 'KG',  90.00, 3.20, 288.00),
    -- 昨天 004：2 行（已作废，不进报表）
    (CONCAT('WB', @k1, '004'), 1, 'RC009', '碎玻璃', 'KG', 150.00, 0.28, 42.00),
    (CONCAT('WB', @k1, '004'), 2, 'RC002', '书报纸', 'KG',  30.00, 0.85, 25.50),

    -- 前天 001：3 行
    (CONCAT('WB', @k2, '001'), 1, 'RC001', '黄板纸',   'KG', 1400.00, 1.35, 1890.00),
    (CONCAT('WB', @k2, '001'), 2, 'RC003', '瓦楞纸箱', 'KG', 1100.00, 1.10, 1210.00),
    (CONCAT('WB', @k2, '001'), 3, 'RC011', '废旧衣物', 'KG',  500.00, 0.60,  300.00),
    -- 前天 002：3 行
    (CONCAT('WB', @k2, '002'), 1, 'RC004', 'PET 瓶砖', 'KG', 620.00, 2.60, 1612.00),
    (CONCAT('WB', @k2, '002'), 2, 'RC009', '碎玻璃',   'KG', 700.00, 0.28,  196.00),
    (CONCAT('WB', @k2, '002'), 3, 'RC002', '书报纸',   'KG', 280.00, 0.85,  238.00),
    -- 前天 003：2 行
    (CONCAT('WB', @k2, '003'), 1, 'RC006', '废铁',     'KG', 3200.00, 2.05, 6560.00),
    (CONCAT('WB', @k2, '003'), 2, 'RC003', '瓦楞纸箱', 'KG', 1500.00, 1.10, 1650.00),

    -- 大前天 001：4 行
    (CONCAT('WB', @k3, '001'), 1, 'RC001', '黄板纸',   'KG', 1800.00, 1.35, 2430.00),
    (CONCAT('WB', @k3, '001'), 2, 'RC002', '书报纸',   'KG', 1200.00, 0.85, 1020.00),
    (CONCAT('WB', @k3, '001'), 3, 'RC004', 'PET 瓶砖', 'KG',  520.00, 2.60, 1352.00),
    (CONCAT('WB', @k3, '001'), 4, 'RC011', '废旧衣物', 'KG',  380.00, 0.60,  228.00),
    -- 大前天 002：2 行（按台计）
    (CONCAT('WB', @k3, '002'), 1, 'RC013', '废旧小家电', 'SET', 6.00,  8.00, 48.00),
    (CONCAT('WB', @k3, '002'), 2, 'RC010', '废旧家电',   'SET', 2.00, 25.00, 50.00),
    -- 大前天 003：3 行
    (CONCAT('WB', @k3, '003'), 1, 'RC003', '瓦楞纸箱',    'KG', 2600.00, 1.10, 2860.00),
    (CONCAT('WB', @k3, '003'), 2, 'RC001', '黄板纸',      'KG', 1600.00, 1.35, 2160.00),
    (CONCAT('WB', @k3, '003'), 3, 'RC005', 'HDPE 硬塑料', 'KG',  400.00, 3.20, 1280.00),

    -- 四天前 001：3 行
    (CONCAT('WB', @k4, '001'), 1, 'RC001', '黄板纸', 'KG', 1500.00, 1.35, 2025.00),
    (CONCAT('WB', @k4, '001'), 2, 'RC002', '书报纸', 'KG', 1300.00, 0.85, 1105.00),
    (CONCAT('WB', @k4, '001'), 3, 'RC009', '碎玻璃', 'KG',  900.00, 0.28,  252.00),
    -- 四天前 002：3 行
    (CONCAT('WB', @k4, '002'), 1, 'RC005', 'HDPE 硬塑料', 'KG', 150.00, 3.20, 480.00),
    (CONCAT('WB', @k4, '002'), 2, 'RC004', 'PET 瓶砖',    'KG', 180.00, 2.60, 468.00),
    (CONCAT('WB', @k4, '002'), 3, 'RC011', '废旧衣物',    'KG', 300.00, 0.60, 180.00),
    -- 五天前 001：3 行
    (CONCAT('WB', @k5, '001'), 1, 'RC003', '瓦楞纸箱', 'KG', 1800.00, 1.10, 1980.00),
    (CONCAT('WB', @k5, '001'), 2, 'RC002', '书报纸',   'KG',  900.00, 0.85,  765.00),
    (CONCAT('WB', @k5, '001'), 3, 'RC011', '废旧衣物', 'KG',  300.00, 0.60,  180.00);

-- ---------------------- 7. 结算单（11 张） ----------------------

INSERT INTO settlement
    (settle_no, ticket_no, customer_name, pay_mode, payable_amount, paid_amount, settle_time, operator, remark) VALUES
    (CONCAT('JS', @k0, '0001'), CONCAT('WB', @k0, '002'), '张建军',         'CASH',     1068.00, 1065.00, TIMESTAMP(@today, '10:05:00'), '李会计', '抹零 3 元'),
    (CONCAT('JS', @k1, '0001'), CONCAT('WB', @k1, '001'), '宏顺废品回收站', 'CASH',     3705.00, 3700.00, TIMESTAMP(@d1,    '08:40:00'), '李会计', '抹零 5 元'),
    (CONCAT('JS', @k1, '0002'), CONCAT('WB', @k1, '002'), '华宁路回收点',   'TRANSFER', 5233.00, 5230.00, TIMESTAMP(@d1,    '11:20:00'), '李会计', '对公转账，扣 3 元手续费'),
    (CONCAT('JS', @k2, '0001'), CONCAT('WB', @k2, '001'), '宏顺废品回收站', 'CASH',     3400.00, 3400.00, TIMESTAMP(@d2,    '08:55:00'), '王站长', '现金当面点清'),
    (CONCAT('JS', @k2, '0002'), CONCAT('WB', @k2, '002'), '福明菜场保洁组', 'DEDUCT',   2046.00,    0.00, TIMESTAMP(@d2,    '09:40:00'), '王站长', '月结抵扣，挂往来'),
    (CONCAT('JS', @k2, '0003'), CONCAT('WB', @k2, '003'), '新河湾工地',     'TRANSFER', 8210.00, 8208.00, TIMESTAMP(@d2,    '16:10:00'), '李会计', '挂账核验后付'),
    (CONCAT('JS', @k3, '0001'), CONCAT('WB', @k3, '001'), '宏顺废品回收站', 'TRANSFER', 5030.00, 5028.00, TIMESTAMP(@d3,    '09:15:00'), '李会计', '抹零 2 元'),
    (CONCAT('JS', @k3, '0002'), CONCAT('WB', @k3, '002'), '周敏',           'CASH',       98.00,   98.00, TIMESTAMP(@d3,    '11:40:00'), '王站长', '现场付讫'),
    (CONCAT('JS', @k3, '0003'), CONCAT('WB', @k3, '003'), '通源包装厂',     'TRANSFER', 6300.00, 6300.00, TIMESTAMP(@d3,    '16:40:00'), '李会计', '次月结'),
    (CONCAT('JS', @k4, '0001'), CONCAT('WB', @k4, '001'), '宏顺废品回收站', 'CASH',     3382.00, 3380.00, TIMESTAMP(@d4,    '09:30:00'), '王站长', '抹零 2 元'),
    (CONCAT('JS', @k5, '0001'), CONCAT('WB', @k5, '001'), '宏顺废品回收站', 'CASH',     2925.00, 2925.00, TIMESTAMP(@d5,    '10:35:00'), '王站长', '现金结清');

-- ---------------------- 8. 复称闭环字段回填（模块六） ----------------------
-- 历史演示磅单的"当前有效口径"初始等于原建档口径（V1）；amount_version 默认 1。
UPDATE weigh_ticket
   SET effective_net_weight   = net_weight,
       effective_total_amount = total_amount
 WHERE effective_net_weight = 0 OR effective_total_amount = 0;

-- =====================================================================
-- 9. 模块七演示数据：司机花名册 + 证照版本链 + 在途出车/派车资格快照
-- ---------------------------------------------------------------------
-- 为把“草稿/待复核/驳回/撤销/到期/被替代/双版本差异”各条链路都喂出数据，
-- 下面用存储过程逐个业务键造版本，保证版本号、group_uuid、轨迹事件一致。
-- =====================================================================

INSERT INTO driver (id, driver_name, phone, license_class, status, remark) VALUES
    (1,  '刘德海', '13801770021', 'B2', 'ACTIVE', '废纸专线主力'),
    (2,  '孙立刚', '13801770034', 'A2', 'ACTIVE', '重料车带随车吊'),
    (3,  '吴天明', '13801770046', 'C1', 'ACTIVE', '塑料瓶砖专线'),
    (4,  '朱建华', '13801770058', 'B2', 'ACTIVE', '家电拆解收运'),
    (5,  '钱志强', '13801770067', 'A2', 'ACTIVE', '工地废钢尾料'),
    (6,  '张伟民', '13801770075', 'C1', 'ACTIVE', '三轮轻卡'),
    (7,  '李国荣', '13801770082', 'C1', 'RESTING', '今天休息，证照演示用'),
    (8,  '周春生', '13801770093', 'C1', 'ACTIVE', '散户上门'),
    (9,  '马成林', '13801770105', 'C1', 'ACTIVE', '接班司机'),
    (10, '顾永康', '13801770117', 'B2', 'ACTIVE', '车在维修，证照齐全'),
    (11, '姚红兵', '13801770126', 'B2', 'ACTIVE', '准驾演示：仅 C1'),
    (12, '谢金泉', '13801770134', 'B2', 'ACTIVE', '对应年检异常车辆');

-- 姚红兵驾驶证只 C1，但其车 沪B·1N530 要求 B2 → 准驾不匹配演示
UPDATE driver SET license_class = 'C1' WHERE id = 11;

DELIMITER $$

-- 造一条证照版本 + 对应轨迹事件
DROP PROCEDURE IF EXISTS sp_add_cred$$
CREATE PROCEDURE sp_add_cred(
    IN p_owner_type VARCHAR(10), IN p_owner_id BIGINT, IN p_owner_name VARCHAR(30),
    IN p_doc_type VARCHAR(30), IN p_version INT, IN p_group VARCHAR(36),
    IN p_number VARCHAR(60), IN p_license VARCHAR(10), IN p_subject VARCHAR(60),
    IN p_from DATE, IN p_until DATE, IN p_attach VARCHAR(200),
    IN p_status VARCHAR(20), IN p_handler VARCHAR(30), IN p_reviewer VARCHAR(30),
    IN p_reason VARCHAR(200), IN p_created DATETIME, IN p_submitted DATETIME,
    IN p_reviewed DATETIME, IN p_closed DATETIME)
BEGIN
    DECLARE v_doc_id BIGINT;
    INSERT INTO credential_doc
        (owner_type, owner_id, owner_name, doc_type, version_no, group_uuid,
         doc_number, license_class, subject_desc, valid_from, valid_until, attachment_note,
         status, handler, reviewer, reason, created_at, submitted_at, reviewed_at, closed_at, lock_version)
    VALUES
        (p_owner_type, p_owner_id, p_owner_name, p_doc_type, p_version, p_group,
         p_number, p_license, p_subject, p_from, p_until, p_attach,
         p_status, p_handler, p_reviewer, p_reason, p_created, p_submitted, p_reviewed, p_closed, 0);
    SET v_doc_id = LAST_INSERT_ID();
    INSERT INTO credential_doc_event
        (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
    VALUES
        (v_doc_id, p_group, 'CREATE', NULL, 'DRAFT', p_handler, NULL, p_created);
    IF p_status IN ('PENDING_REVIEW','EFFECTIVE','REJECTED','REVOKED','EXPIRED','SUPERSEDED') THEN
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES
            (v_doc_id, p_group, 'SUBMIT', 'DRAFT', 'PENDING_REVIEW', p_handler, NULL, p_submitted);
    END IF;
    IF p_status = 'EFFECTIVE' THEN
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'APPROVE', 'PENDING_REVIEW', 'EFFECTIVE', p_reviewer, NULL, p_reviewed);
    ELSEIF p_status = 'REJECTED' THEN
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'REJECT', 'PENDING_REVIEW', 'REJECTED', p_reviewer, p_reason, p_reviewed);
    ELSEIF p_status = 'REVOKED' THEN
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'APPROVE', 'PENDING_REVIEW', 'EFFECTIVE', p_reviewer, NULL, p_reviewed);
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'REVOKE', 'EFFECTIVE', 'REVOKED', p_reviewer, p_reason, p_closed);
    ELSEIF p_status = 'EXPIRED' THEN
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'APPROVE', 'PENDING_REVIEW', 'EFFECTIVE', p_reviewer, NULL, p_reviewed);
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'EXPIRE', 'EFFECTIVE', 'EXPIRED', 'SYSTEM',
                CONCAT('有效期止于 ', p_until, '，系统自动转到期'), p_closed);
    ELSEIF p_status = 'SUPERSEDED' THEN
        INSERT INTO credential_doc_event
            (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
        VALUES (v_doc_id, p_group, 'APPROVE', 'PENDING_REVIEW', 'EFFECTIVE', p_reviewer, NULL, p_reviewed);
    END IF;
END$$

-- 在旧版本被替代/撤销时补一条旧版本的轨迹
DROP PROCEDURE IF EXISTS sp_close_old$$
CREATE PROCEDURE sp_close_old(IN p_doc_id BIGINT, IN p_action VARCHAR(20),
                              IN p_to VARCHAR(20), IN p_operator VARCHAR(30),
                              IN p_reason VARCHAR(200), IN p_at DATETIME)
BEGIN
    INSERT INTO credential_doc_event
        (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
    SELECT id, group_uuid, p_action, 'EFFECTIVE', p_to, p_operator, p_reason, p_at
      FROM credential_doc WHERE id = p_doc_id;
END$$

DELIMITER ;

-- ---------- 司机证照 ----------
-- 1 刘德海：驾驶证 V2 生效（V1 被替代，演示版本差异）；从业资格证生效
CALL sp_add_cred('DRIVER', 1, '刘德海', 'CRED_DRIVER_LICENSE', 1, 'g-d1-lic',
    '310101000001', 'B2', NULL, DATE_SUB(@today, INTERVAL 6 YEAR), DATE_SUB(@today, INTERVAL 40 DAY),
    '驾驶证正本扫描件 01.pdf', 'SUPERSEDED', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 220 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 219 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 218 DAY), '10:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 30 DAY), '08:30:00');
SET @old1 = (SELECT id FROM credential_doc WHERE owner_type='DRIVER' AND owner_id=1 AND doc_type='CRED_DRIVER_LICENSE' AND version_no=1);
CALL sp_close_old(@old1, 'SUPERSEDE', 'SUPERSEDED', '钱复核', '期满换证，由 V2 替代',
    TIMESTAMP(DATE_SUB(@today, INTERVAL 30 DAY), '08:30:00');
CALL sp_add_cred('DRIVER', 1, '刘德海', 'CRED_DRIVER_LICENSE', 2, 'g-d1-lic',
    '310101000002', 'B2', NULL, DATE_SUB(@today, INTERVAL 30 DAY), DATE_ADD(@today, INTERVAL 9*365 DAY),
    '驾驶证正本扫描件 02.pdf（换证后 10 年期）', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 31 DAY), '14:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 30 DAY), '08:10:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 30 DAY), '08:30:00'), NULL);
CALL sp_add_cred('DRIVER', 1, '刘德海', 'CRED_QUALIFICATION', 1, 'g-d1-qua',
    'QG310001', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 2 YEAR),
    DATE_ADD(@today, INTERVAL 1 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 700 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 699 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 698 DAY), '10:00:00'), NULL);

-- 2 孙立刚 A2；两证生效且覆盖远期
CALL sp_add_cred('DRIVER', 2, '孙立刚', 'CRED_DRIVER_LICENSE', 1, 'g-d2-lic',
    '310102000001', 'A2', NULL, DATE_SUB(@today, INTERVAL 3 YEAR), DATE_ADD(@today, INTERVAL 7 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1000 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 999 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 998 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 2, '孙立刚', 'CRED_QUALIFICATION', 1, 'g-d2-qua',
    'QG310002', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 3 吴天明：从业资格证 V1 生效但 5 天后到期（演示“计划回场晚于有效期”）
CALL sp_add_cred('DRIVER', 3, '吴天明', 'CRED_DRIVER_LICENSE', 1, 'g-d3-lic',
    '310103000001', 'C1', NULL, DATE_SUB(@today, INTERVAL 2 YEAR), DATE_ADD(@today, INTERVAL 4 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 700 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 699 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 698 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 3, '吴天明', 'CRED_QUALIFICATION', 1, 'g-d3-qua',
    'QG310003', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 3 YEAR),
    DATE_ADD(@today, INTERVAL 5 DAY), '从业资格证.jpg（即将到期，未续）', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1000 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 999 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 998 DAY), '10:00:00'), NULL);

-- 4 朱建华：两证生效，从业资格证 12 天后到期（配合车4道路运输证演示“计划回场晚于有效期”）
CALL sp_add_cred('DRIVER', 4, '朱建华', 'CRED_DRIVER_LICENSE', 1, 'g-d4-lic',
    '310104000001', 'B2', NULL, DATE_SUB(@today, INTERVAL 4 YEAR), DATE_ADD(@today, INTERVAL 2 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 900 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 899 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 898 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 4, '朱建华', 'CRED_QUALIFICATION', 1, 'g-d4-qua',
    'QG310004', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 3 YEAR),
    DATE_ADD(@today, INTERVAL 12 DAY), '从业资格证.jpg（12 天后到期，换证中）', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1000 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 999 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 998 DAY), '10:00:00'), NULL);

-- 5 钱志强 A2，两证生效
CALL sp_add_cred('DRIVER', 5, '钱志强', 'CRED_DRIVER_LICENSE', 1, 'g-d5-lic',
    '310105000001', 'A2', NULL, DATE_SUB(@today, INTERVAL 1 YEAR), DATE_ADD(@today, INTERVAL 5 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 5, '钱志强', 'CRED_QUALIFICATION', 1, 'g-d5-qua',
    'QG310005', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 2 YEAR),
    DATE_ADD(@today, INTERVAL 1 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 600 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 599 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 598 DAY), '10:00:00'), NULL);

-- 6 张伟民 C1 两证生效
CALL sp_add_cred('DRIVER', 6, '张伟民', 'CRED_DRIVER_LICENSE', 1, 'g-d6-lic',
    '310106000001', 'C1', NULL, DATE_SUB(@today, INTERVAL 2 YEAR), DATE_ADD(@today, INTERVAL 4 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 700 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 699 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 698 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 6, '张伟民', 'CRED_QUALIFICATION', 1, 'g-d6-qua',
    'QG310006', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 7 李国荣：驾驶证待复核（演示待复核队列 + “仍待复核”拦截）
CALL sp_add_cred('DRIVER', 7, '李国荣', 'CRED_DRIVER_LICENSE', 1, 'g-d7-lic',
    '310107000001', 'C1', NULL, DATE_SUB(@today, INTERVAL 2 YEAR), DATE_ADD(@today, INTERVAL 4 YEAR),
    '驾驶证.pdf', 'PENDING_REVIEW', '赵经办', NULL, NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 3 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1 DAY), '09:20:00'), NULL, NULL);
CALL sp_add_cred('DRIVER', 7, '李国荣', 'CRED_QUALIFICATION', 1, 'g-d7-qua',
    'QG310007', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 8 周春生：驾驶证已过期（EXPIRED，演示“证照已过期”），从业资格证生效
CALL sp_add_cred('DRIVER', 8, '周春生', 'CRED_DRIVER_LICENSE', 1, 'g-d8-lic',
    '310108000001', 'C1', NULL, DATE_SUB(@today, INTERVAL 8 YEAR), DATE_SUB(@today, INTERVAL 20 DAY),
    '驾驶证旧证.pdf（过期未换）', 'EXPIRED', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 2900 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 2899 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 2898 DAY), '10:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 19 DAY), '00:05:00');
CALL sp_add_cred('DRIVER', 8, '周春生', 'CRED_QUALIFICATION', 1, 'g-d8-qua',
    'QG310008', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 9 马成林：两证生效
CALL sp_add_cred('DRIVER', 9, '马成林', 'CRED_DRIVER_LICENSE', 1, 'g-d9-lic',
    '310109000001', 'C1', NULL, DATE_SUB(@today, INTERVAL 1 YEAR), DATE_ADD(@today, INTERVAL 5 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 9, '马成林', 'CRED_QUALIFICATION', 1, 'g-d9-qua',
    'QG310009', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 3 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 10 顾永康（维修车司机）：两证生效齐全
CALL sp_add_cred('DRIVER', 10, '顾永康', 'CRED_DRIVER_LICENSE', 1, 'g-d10-lic',
    '310110000001', 'B2', NULL, DATE_SUB(@today, INTERVAL 3 YEAR), DATE_ADD(@today, INTERVAL 3 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 800 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 799 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 798 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 10, '顾永康', 'CRED_QUALIFICATION', 1, 'g-d10-qua',
    'QG310010', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 11 姚红兵：准驾仅 C1（其车要求 B2）；两证生效，专门演示准驾不匹配
CALL sp_add_cred('DRIVER', 11, '姚红兵', 'CRED_DRIVER_LICENSE', 1, 'g-d11-lic',
    '310111000001', 'C1', NULL, DATE_SUB(@today, INTERVAL 2 YEAR), DATE_ADD(@today, INTERVAL 4 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 700 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 699 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 698 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 11, '姚红兵', 'CRED_QUALIFICATION', 1, 'g-d11-qua',
    'QG310011', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- 12 谢金泉：两证生效
CALL sp_add_cred('DRIVER', 12, '谢金泉', 'CRED_DRIVER_LICENSE', 1, 'g-d12-lic',
    '310112000001', 'B2', NULL, DATE_SUB(@today, INTERVAL 3 YEAR), DATE_ADD(@today, INTERVAL 3 YEAR),
    '驾驶证.pdf', 'EFFECTIVE', '赵经办', '钱复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 800 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 799 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 798 DAY), '10:00:00'), NULL);
CALL sp_add_cred('DRIVER', 12, '谢金泉', 'CRED_QUALIFICATION', 1, 'g-d12-qua',
    'QG310012', NULL, '道路货物运输驾驶员', DATE_SUB(@today, INTERVAL 1 YEAR),
    DATE_ADD(@today, INTERVAL 2 YEAR), '从业资格证.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 299 DAY), '09:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 298 DAY), '10:00:00'), NULL);

-- ---------- 车辆证照（年检 / 交强险 / 道路运输证） ----------
-- 车1 沪C·7T215：三证生效，年检 V2 生效（V1 被替代，演示复核台新旧版本差异）
CALL sp_add_cred('VEHICLE', 1, '沪C·7T215', 'CRED_ANNUAL_INSPECTION', 1, 'g-v1-insp',
    'NJ3101-1-OLD', NULL, '轻型厢式货车 4.5 吨', DATE_SUB(@today, INTERVAL 2 YEAR),
    DATE_SUB(@today, INTERVAL 15 DAY), '年检标旧.jpg', 'SUPERSEDED', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 700 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 699 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 698 DAY), '10:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 10 DAY), '11:00:00');
SET @oldv1 = (SELECT id FROM credential_doc WHERE owner_type='VEHICLE' AND owner_id=1 AND doc_type='CRED_ANNUAL_INSPECTION' AND version_no=1);
CALL sp_close_old(@oldv1, 'SUPERSEDE', 'SUPERSEDED', '李复核', '车辆年检通过，换发新检验合格标志',
    TIMESTAMP(DATE_SUB(@today, INTERVAL 10 DAY), '11:00:00');
CALL sp_add_cred('VEHICLE', 1, '沪C·7T215', 'CRED_ANNUAL_INSPECTION', 2, 'g-v1-insp',
    'NJ3101-1-NEW', NULL, '轻型厢式货车 4.5 吨', DATE_SUB(@today, INTERVAL 10 DAY),
    DATE_ADD(@today, INTERVAL 1 YEAR), '年检标新.jpg（检验合格至明年）', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 12 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 10 DAY), '10:30:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 10 DAY), '11:00:00'), NULL);
CALL sp_add_cred('VEHICLE', 1, '沪C·7T215', 'CRED_INSURANCE', 1, 'g-v1-ins',
    'BX2026-0001', NULL, '交强险保单', DATE_SUB(@today, INTERVAL 200 DAY),
    DATE_ADD(@today, INTERVAL 165 DAY), '交强险保单.pdf', 'EFFECTIVE', '周经办', '吴复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 210 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 205 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 200 DAY), '10:00:00'), NULL);
CALL sp_add_cred('VEHICLE', 1, '沪C·7T215', 'CRED_TRANSPORT_PERMIT', 1, 'g-v1-permit',
    'YS3101-0001', NULL, '普通货运 4.5 吨', DATE_SUB(@today, INTERVAL 3 YEAR),
    DATE_ADD(@today, INTERVAL 1 YEAR), '道路运输证.jpg', 'EFFECTIVE', '周经办', '吴复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1000 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 999 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 998 DAY), '10:00:00'), NULL);

-- 车2~车3：三证生效
DROP PROCEDURE IF EXISTS sp_vehicle_ok;
CREATE PROCEDURE sp_vehicle_ok(IN p_id BIGINT, IN p_name VARCHAR(30), IN p_subject VARCHAR(60),
                               IN p_until_days INT)
BEGIN
    CALL sp_add_cred('VEHICLE', p_id, p_name, 'CRED_ANNUAL_INSPECTION', 1, CONCAT('g-v', p_id, '-insp'),
        CONCAT('NJ3101-', p_id), NULL, p_subject, DATE_SUB(@today, INTERVAL 300 DAY),
        DATE_ADD(@today, INTERVAL p_until_days DAY), '年检标.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
        TIMESTAMP(DATE_SUB(@today, INTERVAL 310 DAY), '09:00:00'),
        TIMESTAMP(DATE_SUB(@today, INTERVAL 305 DAY), '09:20:00'),
        TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '10:00:00'), NULL);
    CALL sp_add_cred('VEHICLE', p_id, p_name, 'CRED_INSURANCE', 1, CONCAT('g-v', p_id, '-ins'),
        CONCAT('BX2026-', LPAD(p_id, 4, '0')), NULL, '交强险保单', DATE_SUB(@today, INTERVAL 200 DAY),
        DATE_ADD(@today, INTERVAL 165 DAY), '交强险保单.pdf', 'EFFECTIVE', '周经办', '吴复核', NULL,
        TIMESTAMP(DATE_SUB(@today, INTERVAL 210 DAY), '09:00:00'),
        TIMESTAMP(DATE_SUB(@today, INTERVAL 205 DAY), '09:20:00'),
        TIMESTAMP(DATE_SUB(@today, INTERVAL 200 DAY), '10:00:00'), NULL);
    CALL sp_add_cred('VEHICLE', p_id, p_name, 'CRED_TRANSPORT_PERMIT', 1, CONCAT('g-v', p_id, '-permit'),
        CONCAT('YS3101-', LPAD(p_id, 4, '0')), NULL, '普通货运', DATE_SUB(@today, INTERVAL 900 DAY),
        DATE_ADD(@today, INTERVAL 700 DAY), '道路运输证.jpg', 'EFFECTIVE', '周经办', '吴复核', NULL,
        TIMESTAMP(DATE_SUB(@today, INTERVAL 910 DAY), '09:00:00'),
        TIMESTAMP(DATE_SUB(@today, INTERVAL 905 DAY), '09:20:00'),
        TIMESTAMP(DATE_SUB(@today, INTERVAL 900 DAY), '10:00:00'), NULL);
END$$
DELIMITER ;

CALL sp_vehicle_ok(2, '沪C·3M812', '重型厢式货车 8 吨', 400);
CALL sp_vehicle_ok(3, '沪A·8J773', '轻型货车 3.2 吨', 400);
-- 车5、车6~车11 三证生效
CALL sp_vehicle_ok(5, '沪E·5R026', '重型货车 12 吨', 500);
-- 车5交强险 V1 演示在途被撤销（在下方出车造完快照后撤销）；V2 新车保单已提交待复核
CALL sp_add_cred('VEHICLE', 5, '沪E·5R026', 'CRED_INSURANCE', 2, 'g-v5-ins',
    'BX2026-0005-NEW', NULL, '交强险新保单（批改后）', @today,
    DATE_ADD(@today, INTERVAL 365 DAY), '新车保单扫描件.pdf', 'PENDING_REVIEW', '周经办', NULL, NULL,
    TIMESTAMP(@today, '09:10:00'), TIMESTAMP(@today, '09:20:00'), NULL, NULL);
CALL sp_vehicle_ok(6, '苏E·2Q903', '三轮轻卡 1.8 吨', 400);
CALL sp_vehicle_ok(7, '苏E·4B209', '轻型货车 2.5 吨', 400);
CALL sp_vehicle_ok(8, '苏U·6X401', '轻型货车 3 吨', 400);
CALL sp_vehicle_ok(9, '苏E·9Z118', '轻型货车 4 吨', 400);
CALL sp_vehicle_ok(10, '沪C·0Y642', '重型货车 9.5 吨', 400);
CALL sp_vehicle_ok(11, '沪B·1N530', '重型货车 7.5 吨', 400);

-- 车4 沪D·9K015：道路运输证生效但 10 天后到期（演示“计划回场晚于有效期”）
CALL sp_add_cred('VEHICLE', 4, '沪D·9K015', 'CRED_ANNUAL_INSPECTION', 1, 'g-v4-insp',
    'NJ3101-4', NULL, '中型货车 6 吨', DATE_SUB(@today, INTERVAL 300 DAY),
    DATE_ADD(@today, INTERVAL 400 DAY), '年检标.jpg', 'EFFECTIVE', '孙经办', '李复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 310 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 305 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '10:00:00'), NULL);
CALL sp_add_cred('VEHICLE', 4, '沪D·9K015', 'CRED_INSURANCE', 1, 'g-v4-ins',
    'BX2026-0004', NULL, '交强险保单', DATE_SUB(@today, INTERVAL 200 DAY),
    DATE_ADD(@today, INTERVAL 165 DAY), '交强险保单.pdf', 'EFFECTIVE', '周经办', '吴复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 210 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 205 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 200 DAY), '10:00:00'), NULL);
CALL sp_add_cred('VEHICLE', 4, '沪D·9K015', 'CRED_TRANSPORT_PERMIT', 1, 'g-v4-permit',
    'YS3101-0004', NULL, '普通货运 6 吨', DATE_SUB(@today, INTERVAL 4 YEAR),
    DATE_ADD(@today, INTERVAL 10 DAY), '道路运输证.jpg（10 天后到期，换证办理中）', 'EFFECTIVE',
    '周经办', '吴复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1400 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1399 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1398 DAY), '10:00:00'), NULL);
-- 车4 的年检新版本草稿（演示同业务键 V2 草稿与生效版并存）
CALL sp_add_cred('VEHICLE', 4, '沪D·9K015', 'CRED_ANNUAL_INSPECTION', 2, 'g-v4-insp',
    'NJ3101-4-NEW', NULL, '中型货车 6 吨（换标后）', DATE_ADD(@today, INTERVAL 380 DAY),
    DATE_ADD(@today, INTERVAL 750 DAY), '新年检标草稿.pdf（草稿，待提交复核）', 'DRAFT', '孙经办', NULL, NULL,
    TIMESTAMP(@today, '08:30:00'), NULL, NULL, NULL);

-- 车12 沪F·2B758：年检 V1 生效后被撤销（演示撤销生效资料 → 在途车辆待调度处置），
-- V2 已提交待复核（待复核队列）；保险、道路运输证生效。当前无生效年检，车辆不可派。
CALL sp_add_cred('VEHICLE', 12, '沪F·2B758', 'CRED_ANNUAL_INSPECTION', 1, 'g-v12-insp',
    'NJ3101-12-OLD', NULL, '中型货车 5 吨', DATE_SUB(@today, INTERVAL 300 DAY),
    DATE_ADD(@today, INTERVAL 65 DAY), '年检标旧.jpg（后被撤销）', 'REVOKED', '孙经办', '李复核',
    '事后核查发现年检时尾气检测数据造假，撤销该检验合格标志',
    TIMESTAMP(DATE_SUB(@today, INTERVAL 310 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 305 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 300 DAY), '10:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1 DAY), '16:00:00');
CALL sp_add_cred('VEHICLE', 12, '沪F·2B758', 'CRED_ANNUAL_INSPECTION', 2, 'g-v12-insp',
    'NJ3101-12-NEW', NULL, '中型货车 5 吨（复检）', DATE_SUB(@today, INTERVAL 1 DAY),
    DATE_ADD(@today, INTERVAL 364 DAY), '复检合格标志扫描件.pdf', 'PENDING_REVIEW', '孙经办', NULL, NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 1 DAY), '17:00:00'),
    TIMESTAMP(@today, '08:40:00'), NULL, NULL);
CALL sp_add_cred('VEHICLE', 12, '沪F·2B758', 'CRED_INSURANCE', 1, 'g-v12-ins',
    'BX2026-0012', NULL, '交强险保单', DATE_SUB(@today, INTERVAL 200 DAY),
    DATE_ADD(@today, INTERVAL 165 DAY), '交强险保单.pdf', 'EFFECTIVE', '周经办', '吴复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 210 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 205 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 200 DAY), '10:00:00'), NULL);
CALL sp_add_cred('VEHICLE', 12, '沪F·2B758', 'CRED_TRANSPORT_PERMIT', 1, 'g-v12-permit',
    'YS3101-0012', NULL, '普通货运 5 吨', DATE_SUB(@today, INTERVAL 900 DAY),
    DATE_ADD(@today, INTERVAL 700 DAY), '道路运输证.jpg', 'EFFECTIVE', '周经办', '吴复核', NULL,
    TIMESTAMP(DATE_SUB(@today, INTERVAL 910 DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 905 DAY), '09:20:00'),
    TIMESTAMP(DATE_SUB(@today, INTERVAL 900 DAY), '10:00:00'), NULL);

-- ---------- 在途出车 + 派车资格快照 ----------
-- 5 辆在途车各有一条未结束出车；新派车会被“车辆已有未结束出车”联锁拦下。
-- 快照统一在造完证照后按“当时生效版本”拷贝；个别车先把将撤销的版本保留进快照再撤销。

-- 车5 沪E·5R026 钱志强：多日长途（计划回场在 8 天后），其交强险随后被撤销，
-- 快照保留原生效版本，出车打“待调度处置”，演示撤销不影响历史快照。
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', @k0, '001'), 5, '沪E·5R026', 5, '钱志强', 'A2', 'B2',
     TIMESTAMP(DATE_SUB(@today, INTERVAL 2 DAY), '06:40:00'), TIMESTAMP(DATE_ADD(@today, INTERVAL 6 DAY), '19:00:00'),
     NULL, 'ON_ROAD', 0, NULL, '王调度', '工地废钢多日长途', 0);
SET @t5 = LAST_INSERT_ID();
-- 先拷贝车5当前生效 5 证（含即将被撤销的交强险版本）
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @t5, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE d.status = 'EFFECTIVE'
   AND ((d.owner_type='DRIVER' AND d.owner_id=5) OR (d.owner_type='VEHICLE' AND d.owner_id=5));
-- 撤销车5交强险：在途车打待调度处置，快照不动
SET @t5_ins = (SELECT id FROM credential_doc
                WHERE owner_type='VEHICLE' AND owner_id=5 AND doc_type='CRED_INSURANCE' AND status='EFFECTIVE');
UPDATE credential_doc SET status='REVOKED', closed_at=TIMESTAMP(@today, '09:30:00'),
       reason='保险公司告知保单批改变更，原保单撤销，新车保单尚待复核'
 WHERE id = @t5_ins;
INSERT INTO credential_doc_event
    (doc_id, group_uuid, action, from_status, to_status, operator, reason, created_at)
SELECT id, group_uuid, 'REVOKE', 'EFFECTIVE', 'REVOKED', '吴复核',
       '保险公司告知保单批改变更，原保单撤销，新车保单尚待复核', TIMESTAMP(@today, '09:30:00')
  FROM credential_doc WHERE id = @t5_ins;
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', @k0, '002'), 1, '沪C·7T215', 1, '刘德海', 'B2', 'C1',
     TIMESTAMP(@today, '08:10:00'), TIMESTAMP(@today, '17:30:00'), NULL, 'ON_ROAD',
     0, NULL, '王调度', '上午商超废纸专线', 0);
SET @t1 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @t1, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE d.status = 'EFFECTIVE'
   AND ((d.owner_type='DRIVER' AND d.owner_id=1) OR (d.owner_type='VEHICLE' AND d.owner_id=1));

INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', @k0, '003'), 2, '沪C·3M812', 2, '孙立刚', 'A2', 'B2',
     TIMESTAMP(@today, '07:50:00'), TIMESTAMP(@today, '18:00:00'), NULL, 'ON_ROAD',
     0, NULL, '王调度', '重料随车吊', 0);
SET @t2 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @t2, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE d.status = 'EFFECTIVE'
   AND ((d.owner_type='DRIVER' AND d.owner_id=2) OR (d.owner_type='VEHICLE' AND d.owner_id=2));

-- 车3 吴天明：在途。计划回场在今天 20:00，从业资格证 5 天后才到期 → 本车可放行；
-- 派车弹窗若把回场选到 6 天后，就会触发“计划回场晚于有效期”。
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', @k0, '004'), 3, '沪A·8J773', 3, '吴天明', 'C1', 'C1',
     TIMESTAMP(@today, '07:30:00'), TIMESTAMP(@today, '20:00:00'), NULL, 'ON_ROAD',
     0, NULL, '王调度', '瓶砖线', 0);
SET @t3 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @t3, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE d.status = 'EFFECTIVE'
   AND ((d.owner_type='DRIVER' AND d.owner_id=3) OR (d.owner_type='VEHICLE' AND d.owner_id=3));

-- 车4 朱建华：在途，道路运输证 10 天后到期，今天回场没问题
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', @k0, '005'), 4, '沪D·9K015', 4, '朱建华', 'B2', 'B2',
     TIMESTAMP(@today, '08:20:00'), TIMESTAMP(@today, '18:30:00'), NULL, 'ON_ROAD',
     0, NULL, '王调度', '家电拆解', 0);
SET @t4 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @t4, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE d.status = 'EFFECTIVE'
   AND ((d.owner_type='DRIVER' AND d.owner_id=4) OR (d.owner_type='VEHICLE' AND d.owner_id=4));

-- 把车5出车与车辆都标成待调度处置（与撤销交强险动作一致）
UPDATE dispatch_trip SET pending_dispatch_flag = 1,
       pending_reason = '生效资料「机动车交强险 V1」已撤销：保险公司告知保单批改变更，原保单撤销'
 WHERE id = @t5;
UPDATE vehicle SET pending_dispatch_flag = 1,
       pending_dispatch_reason = '在途期间交强险被撤销，计划提前回场补办新车保单'
 WHERE id = 5;

-- 历史已回场出车 2 条：快照永久保留，其中车3昨天那趟的从业资格证如今临近到期也不影响它
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', DATE_FORMAT(@d1, '%Y%m%d'), '001'), 3, '沪A·8J773', 3, '吴天明', 'C1', 'C1',
     TIMESTAMP(@d1, '08:00:00'), TIMESTAMP(@d1, '16:30:00'), TIMESTAMP(@d1, '16:10:00'), 'RETURNED',
     0, NULL, '王调度', '昨日瓶砖线（历史快照保留）', 0);
SET @h3 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @h3, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE (d.owner_type='DRIVER' AND d.owner_id=3)
    OR (d.owner_type='VEHICLE' AND d.owner_id=3);
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', DATE_FORMAT(@d2, '%Y%m%d'), '001'), 6, '苏E·2Q903', 6, '张伟民', 'C1', 'C1',
     TIMESTAMP(@d2, '08:30:00'), TIMESTAMP(@d2, '17:00:00'), TIMESTAMP(@d2, '16:50:00'), 'RETURNED',
     0, NULL, '王调度', '前天三轮轻卡走街串巷（历史快照保留）', 0);
SET @h6 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @h6, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE (d.owner_type='DRIVER' AND d.owner_id=6) OR (d.owner_type='VEHICLE' AND d.owner_id=6);

-- 车1年检 V1 被 V2 替代发生在更早之前，当时那趟历史出车保留 V1 快照（历史不受换版影响）
INSERT INTO dispatch_trip
    (trip_no, vehicle_id, plate_no, driver_id, driver_name, driver_license_class,
     required_license_class, dispatched_at, planned_return_at, returned_at, status,
     pending_dispatch_flag, pending_reason, dispatcher, remark, lock_version)
VALUES
    (CONCAT('PC', DATE_FORMAT(@d3, '%Y%m%d'), '001'), 1, '沪C·7T215', 1, '刘德海', 'B2', 'C1',
     TIMESTAMP(@d3, '08:10:00'), TIMESTAMP(@d3, '17:20:00'), TIMESTAMP(@d3, '17:05:00'), 'RETURNED',
     0, NULL, '王调度', '年检换版前的出车，资格快照保留年检 V1', 0);
SET @h1 = LAST_INSERT_ID();
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @h1, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE (d.owner_type='DRIVER' AND d.owner_id=1)
    OR (d.owner_type='VEHICLE' AND d.owner_id=1 AND d.doc_type <> 'CRED_ANNUAL_INSPECTION');
INSERT INTO dispatch_credential_snapshot
    (trip_id, owner_type, owner_id, owner_name, doc_type, doc_id, doc_version_no, doc_number,
     license_class, subject_desc, valid_from, valid_until, attachment_note,
     doc_status_at_dispatch, handler, reviewer)
SELECT @h1, d.owner_type, d.owner_id, d.owner_name, d.doc_type, d.id, d.version_no, d.doc_number,
       d.license_class, d.subject_desc, d.valid_from, d.valid_until, d.attachment_note,
       'EFFECTIVE', d.handler, d.reviewer
  FROM credential_doc d
 WHERE d.owner_type='VEHICLE' AND d.owner_id=1 AND d.doc_type='CRED_ANNUAL_INSPECTION' AND d.version_no=1;

DROP PROCEDURE IF EXISTS sp_add_cred;
DROP PROCEDURE IF EXISTS sp_close_old;
DROP PROCEDURE IF EXISTS sp_vehicle_ok;

