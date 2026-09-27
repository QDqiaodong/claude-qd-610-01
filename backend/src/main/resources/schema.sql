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
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    plate_no          VARCHAR(20)  NOT NULL COMMENT '车牌',
    driver_name       VARCHAR(30)  NOT NULL COMMENT '司机（档案默认司机，派车时可另选）',
    phone             VARCHAR(20)           DEFAULT NULL COMMENT '随车电话',
    load_capacity     DECIMAL(8,2) NOT NULL DEFAULT 0.00 COMMENT '额定载重（吨，也决定最低准驾车型）',
    status            VARCHAR(20)  NOT NULL COMMENT 'IDLE 待命 / ON_ROAD 在途 / REPAIR 维修',
    trip_date         DATE                  DEFAULT NULL COMMENT '趟次归属日期（换了日期就重新计数）',
    today_trips       INT          NOT NULL DEFAULT 0 COMMENT '今日趟次',
    remark            VARCHAR(100)          DEFAULT NULL COMMENT '备注',
    default_driver_id BIGINT                DEFAULT NULL COMMENT '默认司机（指向 driver.id，派车弹窗预选）',
    lock_version      BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本（派车与回场并发时据此识别状态已变化）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vehicle_plate (plate_no),
    KEY idx_vehicle_status (status),
    KEY idx_vehicle_driver (default_driver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收运车辆与司机';

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

-- =====================================================================
-- 模块七：证照有效期联锁台（司机证照 / 车辆证照 + 派车资格快照）
-- ---------------------------------------------------------------------
-- 口径：
--   1. 每份资料（cert_document）归属于一条"主体+证照类型"版本链（cert_series）。
--      旧版本永久保留、行内容不再被任何流程 UPDATE（只有状态列沿生命周期推进），
--      "被新版本替代"是旧版本的终态，不是物理覆盖。
--   2. 同一条版本链任一时刻至多一份 EFFECTIVE、至多一条 AWAIT_REVIEW：
--      靠 effective_slot / pending_slot 两个生成列 + 唯一索引兜底，
--      两名复核员分别处理新旧版本、超时重试、旧页面提交，数据库只可能落下一份生效版本。
--   3. cert_interlock 是单行联锁门：派车确认、复核生效、撤销、回场都在同一事务里
--      先 SELECT ... FOR UPDATE 抢这把钥匙，从而"资质变更"与"派车确认"必有明确先后，
--      绝不会出现车已出发却找不到一组当时有效的资料。
--   4. dispatch_trip + dispatch_trip_cert 是派车资格快照：出车时把采用的每一版资料
--      原样拷进行里；后来换版 / 撤销 / 到期都不改快照，历史出车永远能还原当时依据。
-- =====================================================================

CREATE TABLE IF NOT EXISTS driver (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    driver_no       VARCHAR(32)  NOT NULL COMMENT '司机工号',
    driver_name     VARCHAR(30)  NOT NULL COMMENT '司机姓名',
    phone           VARCHAR(20)           DEFAULT NULL COMMENT '联系电话',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 在岗 / RESTING 休息 / RESIGNED 离职',
    lock_version    BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本',
    created_at      DATETIME     NOT NULL COMMENT '建档时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_no (driver_no),
    KEY idx_driver_name (driver_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='司机（证照主体）';

-- 证照版本链：一条 = 一个主体（司机/车辆）的一类证照；current_document_id 指向当前生效版本
CREATE TABLE IF NOT EXISTS cert_series (
    id                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    series_no            VARCHAR(40) NOT NULL COMMENT '版本链编号：主体类型-主体ID-证照类型',
    subject_type         VARCHAR(10) NOT NULL COMMENT 'DRIVER 司机 / VEHICLE 车辆',
    subject_id           BIGINT      NOT NULL COMMENT '主体主键（driver.id 或 vehicle.id）',
    subject_name         VARCHAR(50) NOT NULL COMMENT '主体名称快照（司机姓名 / 车牌）',
    cert_type            VARCHAR(30) NOT NULL COMMENT '证照类型（驾驶证 / 年检 / 保险 / 道路运输证 等）',
    current_document_id  BIGINT               DEFAULT NULL COMMENT '当前生效版本（cert_document.id）；从未生效过为 NULL',
    next_version_no      INT         NOT NULL DEFAULT 1 COMMENT '下一版版本号（历史版本永久占用号段）',
    lock_version         BIGINT      NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁版本',
    created_at           DATETIME    NOT NULL COMMENT '建档时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cert_series (subject_type, subject_id, cert_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证照版本链（一类证照一条）';

-- 资料版本：每行内容一经复核流转不再改写；状态沿 DRAFT→AWAIT_REVIEW→EFFECTIVE→(REVOKED/EXPIRED/SUPERSEDED) 推进
CREATE TABLE IF NOT EXISTS cert_document (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    series_id         BIGINT       NOT NULL COMMENT '所属版本链',
    version_no        INT          NOT NULL COMMENT '版本号（同一版本链内从 1 递增，永不复用）',
    subject_type      VARCHAR(10)  NOT NULL COMMENT 'DRIVER / VEHICLE',
    subject_id        BIGINT       NOT NULL COMMENT '主体主键（冗余，方便直查）',
    subject_name      VARCHAR(50)  NOT NULL COMMENT '主体名称快照',
    cert_type         VARCHAR(30)  NOT NULL COMMENT '证照类型',
    cert_no           VARCHAR(60)  NOT NULL COMMENT '证件号码',
    license_class     VARCHAR(10)           DEFAULT NULL COMMENT '准驾车型（仅驾驶证）：C2/C1/B2/A2',
    valid_from        DATE         NOT NULL COMMENT '有效期起',
    valid_to          DATE         NOT NULL COMMENT '有效期止（派车核对到计划回场当天）',
    attachment_note   VARCHAR(200)          DEFAULT NULL COMMENT '附件说明（扫描件 / 文件名 / 张数）',
    operator          VARCHAR(30)  NOT NULL COMMENT '经办人（录入人，不能复核自己录的资料）',
    reviewer          VARCHAR(30)           DEFAULT NULL COMMENT '复核人（必须与经办人不同）',
    -- DRAFT 草稿 / AWAIT_REVIEW 待复核 / EFFECTIVE 生效 / REJECTED 驳回 /
    -- REVOKED 撤销 / EXPIRED 到期 / SUPERSEDED 被新版本替代
    status            VARCHAR(20)  NOT NULL COMMENT '资料版本状态',
    reject_reason     VARCHAR(200)          DEFAULT NULL COMMENT '驳回原因（驳回必填）',
    revoke_reason     VARCHAR(200)          DEFAULT NULL COMMENT '撤销原因（撤销必填）',
    reviewed_at       DATETIME              DEFAULT NULL COMMENT '复核时间',
    revoked_at        DATETIME              DEFAULT NULL COMMENT '撤销时间',
    expired_at        DATETIME              DEFAULT NULL COMMENT '系统判定到期时间',
    created_at        DATETIME     NOT NULL COMMENT '录入时间',
    updated_at        DATETIME     NOT NULL COMMENT '最后状态变化时间',
    lock_version      BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁：旧页面 / 超时重试据此得到"资料版本已变化"',
    -- 生效占位：EFFECTIVE 时等于 series_id，其余为 NULL → 每条版本链至多一份生效
    effective_slot    BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'EFFECTIVE' THEN series_id ELSE NULL END) STORED COMMENT '生效占位（生成列）',
    -- 待复核占位：AWAIT_REVIEW 时等于 series_id → 每条版本链至多一条待复核
    pending_slot      BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'AWAIT_REVIEW' THEN series_id ELSE NULL END) STORED COMMENT '待复核占位（生成列）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cert_version (series_id, version_no),
    UNIQUE KEY uk_cert_effective_slot (effective_slot),
    UNIQUE KEY uk_cert_pending_slot (pending_slot),
    KEY idx_cert_subject (subject_type, subject_id),
    KEY idx_cert_status (status),
    KEY idx_cert_valid_to (valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证照资料版本（旧版本永久保留不可改）';

-- 证照轨迹：append-only，录入 / 提交 / 生效 / 驳回 / 撤销 / 到期 / 被替代全程留痕
CREATE TABLE IF NOT EXISTS cert_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    document_id  BIGINT       NOT NULL COMMENT '资料版本主键',
    series_id    BIGINT       NOT NULL COMMENT '版本链主键（冗余）',
    event_type   VARCHAR(20)  NOT NULL COMMENT 'CREATED/SUBMITTED/APPROVED/REJECTED/REVOKED/EXPIRED/SUPERSEDED',
    from_status  VARCHAR(20)           DEFAULT NULL COMMENT '变更前状态',
    to_status    VARCHAR(20)           DEFAULT NULL COMMENT '变更后状态',
    operator     VARCHAR(30)  NOT NULL COMMENT '操作人',
    note         VARCHAR(200)          DEFAULT NULL COMMENT '说明（驳回 / 撤销原因等）',
    created_at   DATETIME     NOT NULL COMMENT '事件时间',
    PRIMARY KEY (id),
    KEY idx_cert_event_doc (document_id),
    KEY idx_cert_event_series (series_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证照状态轨迹（只追加）';

-- 联锁门：全表单行（固定 id=1）。派车 / 复核生效 / 撤销 / 回场先锁它再干活，
-- 把"资质变更"与"派车确认"强制排成明确的先后次序，重启 / 多实例下依旧成立。
CREATE TABLE IF NOT EXISTS cert_interlock (
    id          BIGINT   NOT NULL COMMENT '主键（固定 1）',
    updated_at  DATETIME NOT NULL COMMENT '最近一次联锁流转时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证照派车联锁门（单行）';

-- 出车单：一次 IDLE→在途 生成一条；回场后置 FINISHED。车辆维度的未结束唯一靠 open_slot
CREATE TABLE IF NOT EXISTS dispatch_trip (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trip_no            VARCHAR(32)  NOT NULL COMMENT '出车单号：PC + yyyyMMdd + 三位序号',
    vehicle_id         BIGINT       NOT NULL COMMENT '车辆',
    plate_no           VARCHAR(20)  NOT NULL COMMENT '车牌快照',
    driver_id          BIGINT       NOT NULL COMMENT '本次司机',
    driver_name        VARCHAR(30)  NOT NULL COMMENT '司机姓名快照',
    dispatch_operator  VARCHAR(30)  NOT NULL COMMENT '调度员',
    planned_return_at  DATETIME     NOT NULL COMMENT '计划回场时间（证照必须有效到此刻）',
    dispatched_at      DATETIME     NOT NULL COMMENT '出车确认时间',
    returned_at        DATETIME              DEFAULT NULL COMMENT '实际回场时间（未回场为 NULL）',
    -- OPEN 在途 / FINISHED 已回场 / PENDING_DISPOSAL 在途但所用生效证照被撤销，待调度处置
    status             VARCHAR(20)  NOT NULL COMMENT '出车状态',
    disposal_reason    VARCHAR(200)          DEFAULT NULL COMMENT '待调度处置原因（被撤销的证照与版本）',
    disposition_note   VARCHAR(200)          DEFAULT NULL COMMENT '调度处置说明（处置后留痕）',
    disposition_at     DATETIME              DEFAULT NULL COMMENT '处置时间',
    open_slot          BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('OPEN','PENDING_DISPOSAL') THEN vehicle_id ELSE NULL END) STORED COMMENT '未结束出车占位（生成列）',
    lock_version       BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁：回场 / 撤销标记并发据此识别',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_no (trip_no),
    UNIQUE KEY uk_trip_open (open_slot),
    KEY idx_trip_driver (driver_id),
    KEY idx_trip_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出车单（派车资格快照主表）';

-- 派车资格快照明细：出车当时采用的每一份必需证照版本原样拷贝；
-- 后续换版 / 撤销 / 到期绝不改写这些行，历史出车的资格依据永久可追溯。
CREATE TABLE IF NOT EXISTS dispatch_trip_cert (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trip_id          BIGINT       NOT NULL COMMENT '出车单主键',
    series_id        BIGINT       NOT NULL COMMENT '证照版本链（撤销时据此反查在途车辆）',
    document_id      BIGINT       NOT NULL COMMENT '采用的资料版本主键',
    subject_type     VARCHAR(10)  NOT NULL COMMENT 'DRIVER / VEHICLE',
    subject_name     VARCHAR(50)  NOT NULL COMMENT '主体名称快照',
    cert_type        VARCHAR(30)  NOT NULL COMMENT '证照类型',
    cert_no          VARCHAR(60)  NOT NULL COMMENT '证件号码快照',
    license_class    VARCHAR(10)           DEFAULT NULL COMMENT '准驾车型快照',
    valid_from       DATE         NOT NULL COMMENT '有效期起快照',
    valid_to         DATE         NOT NULL COMMENT '有效期止快照',
    version_no       INT          NOT NULL COMMENT '资料版本号快照（第几版）',
    status           VARCHAR(20)  NOT NULL COMMENT '采用当时的资料状态（固定 EFFECTIVE，留痕）',
    PRIMARY KEY (id),
    KEY idx_tripcert_trip (trip_id),
    KEY idx_tripcert_series (series_id),
    KEY idx_tripcert_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派车资格快照明细（不可变）';

INSERT INTO cert_interlock (id, updated_at) VALUES (1, NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

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

-- ---------------------- 4. 司机与车辆（12 名司机 / 12 台） ----------------------

-- 司机先于车辆落库（id 1..12 与下面车辆一一对应，车辆 default_driver_id 直接引用）
INSERT INTO driver
    (id, driver_no, driver_name, phone, status, lock_version, created_at) VALUES
    ( 1, 'SJ001', '刘德海', '13801770021', 'ACTIVE',  0, NOW()),
    ( 2, 'SJ002', '孙立刚', '13801770034', 'ACTIVE',  0, NOW()),
    ( 3, 'SJ003', '吴天明', '13801770046', 'ACTIVE',  0, NOW()),
    ( 4, 'SJ004', '朱建华', '13801770058', 'ACTIVE',  0, NOW()),
    ( 5, 'SJ005', '钱志强', '13801770067', 'ACTIVE',  0, NOW()),
    ( 6, 'SJ006', '张伟民', '13801770075', 'ACTIVE',  0, NOW()),
    ( 7, 'SJ007', '李国荣', '13801770082', 'ACTIVE',  0, NOW()),
    ( 8, 'SJ008', '周春生', '13801770093', 'ACTIVE',  0, NOW()),
    ( 9, 'SJ009', '马成林', '13801770105', 'ACTIVE',  0, NOW()),
    (10, 'SJ010', '顾永康', '13801770117', 'RESTING', 0, NOW()),
    (11, 'SJ011', '姚红兵', '13801770126', 'RESTING', 0, NOW()),
    (12, 'SJ012', '谢金泉', '13801770134', 'ACTIVE',  0, NOW());

INSERT INTO vehicle
    (id, plate_no, driver_name, phone, load_capacity, status, trip_date, today_trips, remark, default_driver_id) VALUES
    ( 1, '沪C·7T215', '刘德海', '13801770021',  4.50, 'ON_ROAD', @today, 3, '废纸专线，上午跑商超', 1),
    ( 2, '沪C·3M812', '孙立刚', '13801770034',  8.00, 'ON_ROAD', @today, 2, '重料车，带随车吊', 2),
    ( 3, '沪A·8J773', '吴天明', '13801770046',  3.20, 'ON_ROAD', @today, 2, '塑料瓶砖专线', 3),
    ( 4, '沪D·9K015', '朱建华', '13801770058',  6.00, 'ON_ROAD', @today, 1, '家电拆解收运', 4),
    ( 5, '沪E·5R026', '钱志强', '13801770067', 12.00, 'ON_ROAD', @today, 1, '工地废钢尾料', 5),
    ( 6, '苏E·2Q903', '张伟民', '13801770075',  1.80, 'IDLE',    @today, 2, '三轮轻卡，走街串巷', 6),
    ( 7, '苏E·4B209', '李国荣', '13801770082',  2.50, 'IDLE',    @today, 1, '社区回收点驳接', 7),
    ( 8, '苏U·6X401', '周春生', '13801770093',  3.00, 'IDLE',    @today, 1, '散户上门收运', 8),
    ( 9, '苏E·9Z118', '马成林', '13801770105',  4.00, 'IDLE',    @today, 0, '待命；司机驾驶证昨天到期，新证待复核', 9),
    (10, '沪C·0Y642', '顾永康', '13801770117',  9.50, 'REPAIR',  @today, 0, '后桥漏油，预计周五修好', 10),
    (11, '沪B·1N530', '姚红兵', '13801770126',  7.50, 'REPAIR',  @today, 0, '司机持 C2 自动挡驾照，准驾不覆盖；车辆返厂', 11),
    (12, '沪F·2B758', '谢金泉', '13801770134',  5.00, 'REPAIR',  @today, 0, '年检待复核，暂停派单', 12);

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
-- 9. 证照有效期联锁台演示数据（模块七）
-- ---------------------------------------------------------------------
-- 日期口径：默认证有效期 = 一年前 → 一年后；下面几处刻意制造各分支：
--   * 司机 9 驾驶证 V1 昨天到期、V2 已录入待复核           → 车辆 9 不可派：资料仍待复核
--   * 车辆 3 保险 V1 被 V2 替代后 V2 今日撤销（无生效版本） → 在途车 3 待调度处置、不可新派
--   * 车辆 12 年检 V1 已到期、V2 待复核                    → 不可派：资料仍待复核
--   * 车辆 7 道路运输证已到期无新版本                      → 不可派：证照已过期
--   * 车辆 8 保险 7 天后到期                              → 计划回场排远即"计划回场晚于有效期"
--   * 车辆 3 道路运输证 10 天后到期                        → 同上（在途车也能看到风险）
--   * 司机 11 持 C2，车辆 11 要求 B2                       → 准驾车型不匹配
-- =====================================================================
SET @d360 = DATE_SUB(CURDATE(), INTERVAL 360 DAY);
SET @d180 = DATE_SUB(CURDATE(), INTERVAL 180 DAY);
SET @d30  = DATE_SUB(CURDATE(), INTERVAL 30 DAY);
SET @d20  = DATE_SUB(CURDATE(), INTERVAL 20 DAY);
SET @d10  = DATE_SUB(CURDATE(), INTERVAL 10 DAY);
SET @d7   = DATE_SUB(CURDATE(), INTERVAL 7 DAY);
SET @d3   = DATE_SUB(CURDATE(), INTERVAL 3 DAY);
SET @d2   = DATE_SUB(CURDATE(), INTERVAL 2 DAY);
SET @p7   = DATE_ADD(CURDATE(), INTERVAL 7 DAY);
SET @p10  = DATE_ADD(CURDATE(), INTERVAL 10 DAY);
SET @p180 = DATE_ADD(CURDATE(), INTERVAL 180 DAY);
SET @p360 = DATE_ADD(CURDATE(), INTERVAL 360 DAY);

-- 9.1 版本链：每名司机 2 条（驾驶证 / 资格证，id 1..24），每台车 3 条（年检 / 保险 / 道路运输证，id 25..60）
INSERT INTO cert_series
    (id, series_no, subject_type, subject_id, subject_name, cert_type, current_document_id, next_version_no, lock_version, created_at)
SELECT 2*id-1, CONCAT('DRIVER-', id, '-DRIVER_LICENSE'), 'DRIVER', id, driver_name,
       'DRIVER_LICENSE', 2*id-1, 2, 0, TIMESTAMP(@d360, '09:00:00')
  FROM driver;
INSERT INTO cert_series
    (id, series_no, subject_type, subject_id, subject_name, cert_type, current_document_id, next_version_no, lock_version, created_at)
SELECT 2*id, CONCAT('DRIVER-', id, '-QUALIFICATION'), 'DRIVER', id, driver_name,
       'QUALIFICATION', 2*id, 2, 0, TIMESTAMP(@d360, '09:05:00')
  FROM driver;

INSERT INTO cert_series
    (id, series_no, subject_type, subject_id, subject_name, cert_type, current_document_id, next_version_no, lock_version, created_at)
SELECT 24+3*(v.id-1)+1, CONCAT('VEHICLE-', v.id, '-ANNUAL_INSPECTION'), 'VEHICLE', v.id, v.plate_no,
       'ANNUAL_INSPECTION', 24+3*(v.id-1)+1, 2, 0, TIMESTAMP(@d360, '10:00:00')
  FROM vehicle v;
INSERT INTO cert_series
    (id, series_no, subject_type, subject_id, subject_name, cert_type, current_document_id, next_version_no, lock_version, created_at)
SELECT 24+3*(v.id-1)+2, CONCAT('VEHICLE-', v.id, '-INSURANCE'), 'VEHICLE', v.id, v.plate_no,
       'INSURANCE', 24+3*(v.id-1)+2, 2, 0, TIMESTAMP(@d360, '10:05:00')
  FROM vehicle v;
INSERT INTO cert_series
    (id, series_no, subject_type, subject_id, subject_name, cert_type, current_document_id, next_version_no, lock_version, created_at)
SELECT 24+3*(v.id-1)+3, CONCAT('VEHICLE-', v.id, '-ROAD_TRANSPORT_PERMIT'), 'VEHICLE', v.id, v.plate_no,
       'ROAD_TRANSPORT_PERMIT', 24+3*(v.id-1)+3, 2, 0, TIMESTAMP(@d360, '10:10:00')
  FROM vehicle v;

-- 9.2 各版本链 V1 资料：先全量生效、一年有效期，再把刻意设计的分支 UPDATE / 补新版
INSERT INTO cert_document
    (id, series_id, version_no, subject_type, subject_id, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, attachment_note, operator, reviewer, status,
     reviewed_at, created_at, updated_at)
SELECT 2*id-1, 2*id-1, 1, 'DRIVER', id, driver_name, 'DRIVER_LICENSE',
       CONCAT('3101', LPAD(id, 6, '0')),
       CASE WHEN id IN (1,2,4,10,12) THEN 'B2' WHEN id = 5 THEN 'A2' WHEN id = 11 THEN 'C2' ELSE 'C1' END,
       @d360, @p360, '驾驶证正副本扫描件 2 页', '王安全员', '赵复核', 'EFFECTIVE',
       TIMESTAMP(@d360, '14:00:00'), TIMESTAMP(@d360, '09:00:00'), TIMESTAMP(@d360, '14:00:00')
  FROM driver;
INSERT INTO cert_document
    (id, series_id, version_no, subject_type, subject_id, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, attachment_note, operator, reviewer, status,
     reviewed_at, created_at, updated_at)
SELECT 2*id, 2*id, 1, 'DRIVER', id, driver_name, 'QUALIFICATION',
       CONCAT('QG', LPAD(id, 6, '0')), NULL,
       @d360, @p360, '道路运输从业人员资格证扫描件', '王安全员', '赵复核', 'EFFECTIVE',
       TIMESTAMP(@d360, '14:05:00'), TIMESTAMP(@d360, '09:05:00'), TIMESTAMP(@d360, '14:05:00')
  FROM driver;

INSERT INTO cert_document
    (id, series_id, version_no, subject_type, subject_id, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, attachment_note, operator, reviewer, status,
     reviewed_at, created_at, updated_at)
SELECT 24+3*(v.id-1)+1, 24+3*(v.id-1)+1, 1, 'VEHICLE', v.id, v.plate_no, 'ANNUAL_INSPECTION',
       CONCAT('NJ', LPAD(v.id, 6, '0')), NULL, @d360, @p360, '年检合格标志照片 + 检测报告 PDF',
       '王安全员', '赵复核', 'EFFECTIVE',
       TIMESTAMP(@d360, '15:00:00'), TIMESTAMP(@d360, '10:00:00'), TIMESTAMP(@d360, '15:00:00')
  FROM vehicle v;
INSERT INTO cert_document
    (id, series_id, version_no, subject_type, subject_id, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, attachment_note, operator, reviewer, status,
     reviewed_at, created_at, updated_at)
SELECT 24+3*(v.id-1)+2, 24+3*(v.id-1)+2, 1, 'VEHICLE', v.id, v.plate_no, 'INSURANCE',
       CONCAT('BX', LPAD(v.id, 6, '0')), NULL, @d360, @p360, '交强险保单 PDF（含商业险批单）',
       '王安全员', '赵复核', 'EFFECTIVE',
       TIMESTAMP(@d360, '15:05:00'), TIMESTAMP(@d360, '10:05:00'), TIMESTAMP(@d360, '15:05:00')
  FROM vehicle v;
INSERT INTO cert_document
    (id, series_id, version_no, subject_type, subject_id, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, attachment_note, operator, reviewer, status,
     reviewed_at, created_at, updated_at)
SELECT 24+3*(v.id-1)+3, 24+3*(v.id-1)+3, 1, 'VEHICLE', v.id, v.plate_no, 'ROAD_TRANSPORT_PERMIT',
       CONCAT('YG', LPAD(v.id, 6, '0')), NULL, @d360, @p360, '道路运输证内页扫描件',
       '王安全员', '赵复核', 'EFFECTIVE',
       TIMESTAMP(@d360, '15:10:00'), TIMESTAMP(@d360, '10:10:00'), TIMESTAMP(@d360, '15:10:00')
  FROM vehicle v;

-- 9.3 特殊分支：到期 / 临近到期 / 被替代
-- 司机 9 驾驶证 V1 昨天到期
UPDATE cert_document SET status = 'EXPIRED', valid_to = @d1, expired_at = TIMESTAMP(@d1, '00:05:00'),
       updated_at = TIMESTAMP(@d1, '00:05:00') WHERE id = 17;
UPDATE cert_series SET current_document_id = NULL, next_version_no = 3 WHERE id = 17;
-- 车辆 7 道路运输证 30 天前到期，无人续办
UPDATE cert_document SET status = 'EXPIRED', valid_to = @d30, expired_at = TIMESTAMP(@d30, '00:05:00'),
       updated_at = TIMESTAMP(@d30, '00:05:00') WHERE id = 45;
UPDATE cert_series SET current_document_id = NULL WHERE id = 45;
-- 车辆 8 保险 7 天后到期（派车排远回场即拦截）
UPDATE cert_document SET valid_to = @p7 WHERE id = 47;
-- 车辆 3 道路运输证 10 天后到期
UPDATE cert_document SET valid_to = @p10 WHERE id = 27;
-- 车辆 12 年检 V1 30 天前到期，V2 已录入待复核
UPDATE cert_document SET status = 'EXPIRED', valid_to = @d30, expired_at = TIMESTAMP(@d30, '00:05:00'),
       updated_at = TIMESTAMP(@d30, '00:05:00') WHERE id = 58;
UPDATE cert_series SET current_document_id = NULL, next_version_no = 2 WHERE id = 58;
-- 车辆 3 保险：V2 复核生效后 V1 被替代；V2 今日又被撤销（无生效版本，在途车待调度处置）
UPDATE cert_document SET status = 'SUPERSEDED', updated_at = TIMESTAMP(@d5, '11:00:00') WHERE id = 26;
UPDATE cert_series SET current_document_id = NULL, next_version_no = 3 WHERE id = 26;

-- 9.4 新版本：待复核两条 + 已撤销一条（旧版本永久保留）
INSERT INTO cert_document
    (id, series_id, version_no, subject_type, subject_id, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, attachment_note, operator, reviewer, status,
     reject_reason, revoke_reason, reviewed_at, revoked_at, created_at, updated_at) VALUES
    (62, 58, 2, 'VEHICLE', 12, '沪F·2B758', 'ANNUAL_INSPECTION', 'NJ000012-2', NULL,
     @d7, @p360, '新一年度年检合格标志 + 检测报告（检测线：城南）', '王安全员', NULL, 'AWAIT_REVIEW',
     NULL, NULL, NULL, NULL, TIMESTAMP(@d3, '09:30:00'), TIMESTAMP(@d2, '10:00:00')),
    (63, 26, 2, 'VEHICLE', 3, '沪A·8J773', 'INSURANCE', 'BX000003-2', NULL,
     @d5, @p360, '续保保单 PDF（保险公司复核中）', '王安全员', '赵复核', 'REVOKED',
     NULL, '保单要素存疑，接到保险公司协查通知，暂停使用', TIMESTAMP(@d5, '11:00:00'), NOW(),
     TIMESTAMP(@d20, '09:00:00'), NOW()),
    (64, 17, 2, 'DRIVER', 9, '马成林', 'DRIVER_LICENSE', '3101000009-2', 'C1',
     @d1, @p360, '新换领驾驶证扫描件（车管所已制证）', '王安全员', NULL, 'AWAIT_REVIEW',
     NULL, NULL, NULL, NULL, TIMESTAMP(@d3, '14:00:00'), TIMESTAMP(@d2, '15:00:00'));

-- 9.5 特殊资料的轨迹留痕（常规 V1 的轨迹省略，页面看版本链即可）
INSERT INTO cert_event
    (document_id, series_id, event_type, from_status, to_status, operator, note, created_at) VALUES
    (17, 17, 'EXPIRED',  'EFFECTIVE',     'EXPIRED',      '系统', '驾驶证有效期已过，系统判定到期', TIMESTAMP(@d1, '00:05:00')),
    (64, 17, 'CREATED',  NULL,            'DRAFT',        '王安全员', '换领新证，录入草稿', TIMESTAMP(@d3, '14:00:00')),
    (64, 17, 'SUBMITTED','DRAFT',         'AWAIT_REVIEW', '王安全员', '提交复核', TIMESTAMP(@d2, '15:00:00')),
    (45, 45, 'EXPIRED',  'EFFECTIVE',     'EXPIRED',      '系统', '道路运输证到期未续办', TIMESTAMP(@d30, '00:05:00')),
    (58, 58, 'EXPIRED',  'EFFECTIVE',     'EXPIRED',      '系统', '年检到期', TIMESTAMP(@d30, '00:05:00')),
    (62, 58, 'CREATED',  NULL,            'DRAFT',        '王安全员', '新年度年检资料录入', TIMESTAMP(@d3, '09:30:00')),
    (62, 58, 'SUBMITTED','DRAFT',         'AWAIT_REVIEW', '王安全员', '提交复核', TIMESTAMP(@d2, '10:00:00')),
    (26, 26, 'SUPERSEDED','EFFECTIVE',    'SUPERSEDED',   '赵复核', 'V2 续保保单复核生效，本版本被新版本替代', TIMESTAMP(@d5, '11:00:00')),
    (63, 26, 'CREATED',  NULL,            'DRAFT',        '王安全员', '续保保单录入', TIMESTAMP(@d20, '09:00:00')),
    (63, 26, 'SUBMITTED','DRAFT',         'AWAIT_REVIEW', '王安全员', '提交复核', TIMESTAMP(@d10, '09:30:00')),
    (63, 26, 'APPROVED', 'AWAIT_REVIEW',  'EFFECTIVE',    '赵复核', '复核生效，V1 被替代', TIMESTAMP(@d5, '11:00:00')),
    (63, 26, 'REVOKED',  'EFFECTIVE',     'REVOKED',      '钱调度', '保单要素存疑，接到保险公司协查通知，暂停使用', NOW());

-- 9.6 出车单：5 台在途车各一条未结束（车辆 3 因保险撤销已标记待调度处置），另留 2 条昨天已回场历史
INSERT INTO dispatch_trip
    (id, trip_no, vehicle_id, plate_no, driver_id, driver_name, dispatch_operator,
     planned_return_at, dispatched_at, returned_at, status, disposal_reason,
     disposition_note, disposition_at, lock_version) VALUES
    (1, CONCAT('PC', @k0, '001'), 1, '沪C·7T215', 1, '刘德海', '钱调度',
     TIMESTAMP(@today, '18:00:00'), TIMESTAMP(@today, '08:10:00'), NULL, 'OPEN', NULL, NULL, NULL, 0),
    (2, CONCAT('PC', @k0, '002'), 2, '沪C·3M812', 2, '孙立刚', '钱调度',
     TIMESTAMP(@today, '18:00:00'), TIMESTAMP(@today, '08:35:00'), NULL, 'OPEN', NULL, NULL, NULL, 0),
    (3, CONCAT('PC', @k0, '003'), 3, '沪A·8J773', 3, '吴天明', '钱调度',
     TIMESTAMP(@today, '18:00:00'), TIMESTAMP(@today, '09:05:00'), NULL, 'PENDING_DISPOSAL',
     '本车保险 V2（保单 BX000003-2）今日被撤销：保单要素存疑，接到保险公司协查通知，暂停使用；历史出车资格快照保留',
     NULL, NULL, 0),
    (4, CONCAT('PC', @k0, '004'), 4, '沪D·9K015', 4, '朱建华', '钱调度',
     TIMESTAMP(@today, '18:00:00'), TIMESTAMP(@today, '09:40:00'), NULL, 'OPEN', NULL, NULL, NULL, 0),
    (5, CONCAT('PC', @k0, '005'), 5, '沪E·5R026', 5, '钱志强', '钱调度',
     TIMESTAMP(@today, '18:00:00'), TIMESTAMP(@today, '10:15:00'), NULL, 'OPEN', NULL, NULL, NULL, 0),
    (6, CONCAT('PC', @k1, '006'), 6, '苏E·2Q903', 6, '张伟民', '钱调度',
     TIMESTAMP(@d1, '17:30:00'), TIMESTAMP(@d1, '07:50:00'), TIMESTAMP(@d1, '16:50:00'), 'FINISHED',
     NULL, NULL, NULL, 0),
    (7, CONCAT('PC', @k1, '007'), 7, '苏E·4B209', 7, '李国荣', '钱调度',
     TIMESTAMP(@d1, '18:00:00'), TIMESTAMP(@d1, '08:20:00'), TIMESTAMP(@d1, '17:40:00'), 'FINISHED',
     NULL, NULL, NULL, 0);

-- 9.7 派车资格快照：每条出车按当时采用的生效版本原样拷贝（后续换版 / 撤销 / 到期不改这里）
INSERT INTO dispatch_trip_cert
    (trip_id, series_id, document_id, subject_type, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, version_no, status)
SELECT t.id, 2*t.driver_id-1, 2*t.driver_id-1, 'DRIVER', t.driver_name, 'DRIVER_LICENSE',
       d.cert_no, d.license_class, d.valid_from, d.valid_to, d.version_no, 'EFFECTIVE'
  FROM dispatch_trip t JOIN cert_document d ON d.id = 2*t.driver_id-1;
INSERT INTO dispatch_trip_cert
    (trip_id, series_id, document_id, subject_type, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, version_no, status)
SELECT t.id, 2*t.driver_id, 2*t.driver_id, 'DRIVER', t.driver_name, 'QUALIFICATION',
       d.cert_no, d.license_class, d.valid_from, d.valid_to, d.version_no, 'EFFECTIVE'
  FROM dispatch_trip t JOIN cert_document d ON d.id = 2*t.driver_id;
INSERT INTO dispatch_trip_cert
    (trip_id, series_id, document_id, subject_type, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, version_no, status)
SELECT t.id, 24+3*(t.vehicle_id-1)+1, 24+3*(t.vehicle_id-1)+1, 'VEHICLE', t.plate_no,
       'ANNUAL_INSPECTION', d.cert_no, NULL, d.valid_from, d.valid_to, d.version_no, 'EFFECTIVE'
  FROM dispatch_trip t JOIN cert_document d ON d.id = 24+3*(t.vehicle_id-1)+1;
INSERT INTO dispatch_trip_cert
    (trip_id, series_id, document_id, subject_type, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, version_no, status)
-- 车辆 3 的保险是唯一换过版的证照：在途车 3 当时采用 V2（63，今日撤销），故快照指向 63；
-- 快照 status 固定 EFFECTIVE 表示"采用当时生效"，不回查 cert_document 现状。
SELECT t.id, 24+3*(t.vehicle_id-1)+2,
       CASE WHEN t.vehicle_id = 3 THEN 63 ELSE 24+3*(t.vehicle_id-1)+2 END,
       'VEHICLE', t.plate_no,
       'INSURANCE', d.cert_no, NULL, d.valid_from, d.valid_to,
       CASE WHEN t.vehicle_id = 3 THEN (SELECT version_no FROM cert_document WHERE id = 63) ELSE d.version_no END,
       'EFFECTIVE'
  FROM dispatch_trip t
  JOIN cert_document d
    ON d.id = CASE WHEN t.vehicle_id = 3 THEN 63 ELSE 24+3*(t.vehicle_id-1)+2 END;
INSERT INTO dispatch_trip_cert
    (trip_id, series_id, document_id, subject_type, subject_name, cert_type, cert_no,
     license_class, valid_from, valid_to, version_no, status)
SELECT t.id, 24+3*(t.vehicle_id-1)+3, 24+3*(t.vehicle_id-1)+3, 'VEHICLE', t.plate_no,
       'ROAD_TRANSPORT_PERMIT', d.cert_no, NULL, d.valid_from, d.valid_to, d.version_no, 'EFFECTIVE'
  FROM dispatch_trip t JOIN cert_document d ON d.id = 24+3*(t.vehicle_id-1)+3;
