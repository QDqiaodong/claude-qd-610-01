package com.recycle.station.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.CertDocument;
import com.recycle.station.domain.CertEvent;
import com.recycle.station.domain.CertInterlock;
import com.recycle.station.domain.CertSeries;
import com.recycle.station.domain.DispatchTrip;
import com.recycle.station.domain.Driver;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.Vehicle;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.CertDocumentRepository;
import com.recycle.station.repository.CertInterlockRepository;
import com.recycle.station.repository.CertSeriesRepository;
import com.recycle.station.repository.DispatchTripRepository;
import com.recycle.station.repository.DriverRepository;
import com.recycle.station.repository.VehicleRepository;

/**
 * 模块七核心：证照有效期联锁台。
 * <p>
 * 管两类资料（司机证照 / 车辆证照）的版本链与完整生命周期：
 * 草稿 → 待复核 → 生效 →（驳回 / 撤销 / 到期 / 被新版本替代）。旧版本永久保留，行内容不改写。
 * <p>
 * 并发与一致性底线（全部由数据库保证，重新登录、跨日、服务重启后结论一致）：
 * <ul>
 *   <li>派车 / 复核生效 / 撤销 / 回场都先抢 {@link CertInterlock} 单行行锁，
 *       "资质变更"与"派车确认"被强制排成明确先后，不会有车已出发却找不到当时有效资料的中间态；</li>
 *   <li>uk_cert_effective_slot / uk_cert_pending_slot 兜底：两名复核员分别处理新旧版本、
 *       超时重试、页面长期未更新后再提交，数据库只保留一份生效 / 一条待复核，
 *       过期操作统一得到"资料版本已变化"；</li>
 *   <li>每份资料带 JPA 乐观锁，提交时复核 expectedLockVersion，过期页面直接拒绝；</li>
 *   <li>到期是落库状态：任何写操作先跑到期扫描，把有效期已过的生效资料置 EXPIRED，
 *       判断口径不依赖调用当天的临时计算。</li>
 * </ul>
 */
@Service
public class CertService {

    public static final int REASON_MAX = 200;

    private final CertSeriesRepository seriesRepository;
    private final CertDocumentRepository documentRepository;
    private final CertInterlockRepository interlockRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DispatchTripRepository tripRepository;

    public CertService(CertSeriesRepository seriesRepository,
                       CertDocumentRepository documentRepository,
                       CertInterlockRepository interlockRepository,
                       DriverRepository driverRepository,
                       VehicleRepository vehicleRepository,
                       DispatchTripRepository tripRepository) {
        this.seriesRepository = seriesRepository;
        this.documentRepository = documentRepository;
        this.interlockRepository = interlockRepository;
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripRepository = tripRepository;
    }

    // ================================================================
    // 联锁门与到期扫描
    // ================================================================

    /**
     * 抢联锁门钥匙并先做到期扫描。派车 / 回场 / 复核生效 / 撤销 / 驳回 / 提交都在事务开头调用，
     * 保证后到事务看到的是先到事务提交后的确定状态。
     */
    public CertInterlock lockInterlockAndSweep() {
        CertInterlock interlock = interlockRepository.lockSingleton();
        sweepExpired();
        interlock.setUpdatedAt(LocalDateTime.now());
        return interlock;
    }

    /**
     * 到期扫描：仍为 EFFECTIVE 但有效期止日已过的资料一律置 EXPIRED，并挪掉版本链生效指针。
     * 幂等：已经是 EXPIRED 的不会再处理。跨日后第一次操作 / 重启后第一次列表都会把状态补齐。
     */
    @Transactional
    public int sweepExpired() {
        LocalDate today = LocalDate.now();
        List<CertDocument> expired = documentRepository.findExpiredEffective(today);
        int count = 0;
        for (CertDocument doc : expired) {
            CertSeries series = seriesRepository.lockById(doc.getSeriesId());
            // 双重确认：持锁后可能已被并发复核流程替代
            if (!"EFFECTIVE".equals(doc.getStatus()) || doc.getValidTo() == null
                    || !doc.getValidTo().isBefore(today)) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            doc.setStatus("EXPIRED");
            doc.setExpiredAt(now);
            doc.setUpdatedAt(now);
            if (doc.getId().equals(series.getCurrentDocumentId())) {
                series.setCurrentDocumentId(null);
            }
            documentRepository.save(doc);
            seriesRepository.save(series);
            addEvent(doc, "EXPIRED", "EFFECTIVE", "EXPIRED", "系统",
                    "有效期止 " + doc.getValidTo() + " 已过，系统判定到期", now);
            documentRepository.save(doc);
            count += 1;
        }
        return count;
    }

    // ================================================================
    // 查询：证照列表 / 待复核队列 / 版本链详情
    // ================================================================

    @Transactional
    public List<Views.CertSeriesView> listSeries(String subjectType) {
        sweepExpired();
        List<CertSeries> chains = subjectType == null || subjectType.isBlank()
                ? seriesRepository.findAllByOrderBySubjectTypeAscSubjectIdAscCertTypeAsc()
                : seriesRepository.findBySubjectTypeOrderBySubjectIdAscCertTypeAsc(subjectType.trim());
        List<Views.CertSeriesView> views = new ArrayList<>();
        for (CertSeries series : chains) {
            views.add(buildSeriesView(series));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public Views.CertSeriesView seriesDetail(Long seriesId) {
        CertSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new BizException("证照版本链不存在"));
        return buildSeriesView(series);
    }

    /** 待复核队列（可按复核人视角标注"自己经办"的记录不能自己复核） */
    @Transactional
    public List<Views.CertReviewQueueItem> reviewQueue(String currentReviewer) {
        sweepExpired();
        List<Views.CertReviewQueueItem> items = new ArrayList<>();
        for (CertDocument doc : documentRepository.findByStatusOrderByUpdatedAtAsc("AWAIT_REVIEW")) {
            items.add(new Views.CertReviewQueueItem(doc.getId(), doc.getSeriesId(), doc.getVersionNo(),
                    doc.getSubjectType(), StationDict.certSubjectName(doc.getSubjectType()),
                    doc.getSubjectName(), doc.getCertType(), StationDict.certTypeName(doc.getCertType()),
                    doc.getCertNo(), doc.getLicenseClass(), StationDict.licenseClassName(doc.getLicenseClass()),
                    doc.getValidFrom(), doc.getValidTo(), doc.getAttachmentNote(),
                    doc.getOperator(), doc.getCreatedAt(), doc.getUpdatedAt(), doc.getLockVersion(),
                    currentReviewer != null && currentReviewer.equals(doc.getOperator())));
        }
        return items;
    }

    // ================================================================
    // 录入草稿 / 新版本 → 提交复核
    // ================================================================

    @Transactional
    public Views.CertSeriesView saveDraft(Reqs.CertDraftReq req) {
        String subjectType = req.subjectType().trim().toUpperCase();
        String certType = req.certType().trim().toUpperCase();
        if (!StationDict.isValidCertSubject(subjectType)) {
            throw new BizException("证照主体类型只能是 DRIVER（司机证照）/ VEHICLE（车辆证照）");
        }
        if (!StationDict.isValidCertType(subjectType, certType)) {
            throw new BizException("证照类型与主体不匹配：" + StationDict.certSubjectName(subjectType)
                    + "不能登记「" + certType + "」");
        }
        String subjectName = lockSubjectName(subjectType, req.subjectId());
        String operator = req.operator().trim();
        String certNo = req.certNo().trim();
        LocalDate validFrom = parseDate(req.validFrom(), "有效期起");
        LocalDate validTo = parseDate(req.validTo(), "有效期止");
        if (validTo.isBefore(validFrom)) {
            throw new BizException("有效期止不能早于有效期起");
        }
        String licenseClass = null;
        if ("DRIVER_LICENSE".equals(certType)) {
            licenseClass = req.licenseClass() == null ? "" : req.licenseClass().trim().toUpperCase();
            if (!StationDict.isValidLicenseClass(licenseClass)) {
                throw new BizException("驾驶证必须填写准驾车型：C2 / C1 / B2 / A2");
            }
        }

        lockInterlockAndSweep();
        // 联锁门内重新解析主体名称并查找/建链（前面的检查只用于提前给参数错误，不依赖加锁）
        subjectName = lockSubjectName(subjectType, req.subjectId());
        CertSeries series = seriesRepository
                .findBySubjectTypeAndSubjectIdAndCertType(subjectType, req.subjectId(), certType)
                .orElseGet(() -> {
                    CertSeries created = new CertSeries();
                    created.setSeriesNo(buildSeriesNo(subjectType, req.subjectId(), certType));
                    created.setSubjectType(subjectType);
                    created.setSubjectId(req.subjectId());
                    created.setSubjectName(subjectName);
                    created.setCertType(certType);
                    created.setCurrentDocumentId(null);
                    created.setNextVersionNo(1);
                    created.setCreatedAt(LocalDateTime.now());
                    return seriesRepository.save(created);
                });
        CertSeries locked = seriesRepository.lockById(series.getId());

        // 一条版本链同一时刻只允许一条待复核：旧页面重复点"录入并提交"也落不下第二条
        boolean pendingExists = documentRepository.findBySeriesIdOrderByVersionNoAsc(locked.getId()).stream()
                .anyMatch(d -> "AWAIT_REVIEW".equals(d.getStatus()));
        if (pendingExists) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该证照已有一版待复核资料，请等复核结论出来后再录新版本");
        }

        LocalDateTime now = LocalDateTime.now();
        CertDocument doc = new CertDocument();
        doc.setSeriesId(locked.getId());
        doc.setVersionNo(locked.getNextVersionNo());
        doc.setSubjectType(subjectType);
        doc.setSubjectId(req.subjectId());
        doc.setSubjectName(subjectName);
        doc.setCertType(certType);
        doc.setCertNo(certNo);
        doc.setLicenseClass(licenseClass);
        doc.setValidFrom(validFrom);
        doc.setValidTo(validTo);
        doc.setAttachmentNote(trimToNull(req.attachmentNote()));
        doc.setOperator(operator);
        doc.setStatus("DRAFT");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc = documentRepository.save(doc);
        addEvent(doc, "CREATED", null, "DRAFT", operator,
                "录入 V" + doc.getVersionNo() + " 草稿" + (licenseClass == null ? "" : "（准驾 " + licenseClass + "）"), now);

        boolean submit = Boolean.TRUE.equals(req.submit());
        if (submit) {
            doc.setStatus("AWAIT_REVIEW");
            doc.setUpdatedAt(now);
            addEvent(doc, "SUBMITTED", "DRAFT", "AWAIT_REVIEW", operator, "提交复核", now);
        }
        locked.setNextVersionNo(locked.getNextVersionNo() + 1);
        seriesRepository.save(locked);
        doc = documentRepository.save(doc);
        return seriesDetail(locked.getId());
    }

    /** 草稿提交复核 */
    @Transactional
    public Views.CertSeriesView submit(Reqs.CertSubmitReq req) {
        lockInterlockAndSweep();
        CertDocument doc = lockDocument(req.documentId());
        if (!"DRAFT".equals(doc.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该资料当前是「" + StationDict.certStatusName(doc.getStatus())
                            + "」，只有草稿才能提交复核");
        }
        // 同一条链已有待复核版本（理论上唯一索引已拦，双保险）
        boolean anotherPending = documentRepository.findBySeriesIdOrderByVersionNoAsc(doc.getSeriesId()).stream()
                .anyMatch(d -> !d.getId().equals(doc.getId()) && "AWAIT_REVIEW".equals(d.getStatus()));
        if (anotherPending) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该证照已存在一版待复核资料，本草稿不能再提交");
        }
        LocalDateTime now = LocalDateTime.now();
        doc.setStatus("AWAIT_REVIEW");
        doc.setUpdatedAt(now);
        addEvent(doc, "SUBMITTED", "DRAFT", "AWAIT_REVIEW", req.operator().trim(), "提交复核", now);
        documentRepository.save(doc);
        return seriesDetail(doc.getSeriesId());
    }

    // ================================================================
    // 复核生效 / 驳回 / 撤销
    // ================================================================

    /**
     * 复核生效。持联锁门 + 版本链行锁：
     * 若期间另一复核员已让新版本生效，本资料要么已被替代、要么链上已有生效版本，
     * 旧页面提交一律得到"资料版本已变化"。
     */
    @Transactional
    public Views.CertSeriesView approve(Reqs.CertApproveReq req) {
        lockInterlockAndSweep();
        CertDocument doc = lockDocument(req.documentId());
        String reviewer = req.reviewer().trim();
        assertStale(doc, req.expectedLockVersion());
        if (!"AWAIT_REVIEW".equals(doc.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该资料当前是「" + StationDict.certStatusName(doc.getStatus())
                            + "」，不是待复核状态，请刷新待复核队列");
        }
        if (reviewer.equals(doc.getOperator())) {
            throw new BizException(BizException.SELF_REVIEW_FORBIDDEN,
                    "本人无权复核：复核人不能是经办人本人（经办人 " + doc.getOperator() + "）");
        }
        LocalDate today = LocalDate.now();
        if (doc.getValidFrom().isAfter(today)) {
            throw new BizException("该资料有效期起为 " + doc.getValidFrom() + "，尚未到生效日期，不能复核生效");
        }
        if (doc.getValidTo().isBefore(today)) {
            throw new BizException(BizException.CERT_EXPIRED,
                    "证照已过期：有效期止 " + doc.getValidTo() + "，不能再复核生效，请重新录入有效期内的版本");
        }

        CertSeries series = seriesRepository.lockById(doc.getSeriesId());
        LocalDateTime now = LocalDateTime.now();

        // 旧生效版本：置"被新版本替代"（终态保留，不删不改内容）。
        // 持版本链锁后再判一次：页面长期未更新期间可能已有别的版本完成换版，
        // 那时本版本自己往往已被替代，必须按"资料版本已变化"拒绝而不是硬冲唯一索引。
        Long oldId = series.getCurrentDocumentId();
        if (oldId != null && oldId.equals(doc.getId()) && !"EFFECTIVE".equals(doc.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：V" + doc.getVersionNo() + " 在你打开页面后状态已更新（当前："
                            + StationDict.certStatusName(doc.getStatus()) + "），请刷新后再操作");
        }
        if (oldId != null && !oldId.equals(doc.getId())) {
            CertDocument old = documentRepository.findById(oldId).orElse(null);
            if (old != null && "EFFECTIVE".equals(old.getStatus())) {
                old.setStatus("SUPERSEDED");
                old.setUpdatedAt(now);
                addEvent(old, "SUPERSEDED", "EFFECTIVE", "SUPERSEDED", reviewer,
                        "V" + doc.getVersionNo() + " 复核生效，本版本（V" + old.getVersionNo() + "）被新版本替代", now);
                documentRepository.save(old);
            }
        }

        doc.setStatus("EFFECTIVE");
        doc.setReviewer(reviewer);
        doc.setReviewedAt(now);
        doc.setUpdatedAt(now);
        addEvent(doc, "APPROVED", "AWAIT_REVIEW", "EFFECTIVE", reviewer,
                oldId == null ? "复核生效（首版生效）"
                        : "复核生效，旧生效版本置为「被新版本替代」并永久保留", now);
        documentRepository.save(doc);

        series.setCurrentDocumentId(doc.getId());
        seriesRepository.save(series);
        return seriesDetail(series.getId());
    }

    /** 复核驳回：必须填写原因，驳回是终态，要改只能重新录新版本 */
    @Transactional
    public Views.CertSeriesView reject(Reqs.CertRejectReq req) {
        lockInterlockAndSweep();
        CertDocument doc = lockDocument(req.documentId());
        String reviewer = req.reviewer().trim();
        assertStale(doc, req.expectedLockVersion());
        if (!"AWAIT_REVIEW".equals(doc.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该资料当前是「" + StationDict.certStatusName(doc.getStatus())
                            + "」，不能再驳回，请刷新队列");
        }
        if (reviewer.equals(doc.getOperator())) {
            throw new BizException(BizException.SELF_REVIEW_FORBIDDEN,
                    "本人无权复核：复核人不能是经办人本人（经办人 " + doc.getOperator() + "）");
        }
        String reason = normalizeReason(req.reason(), "驳回原因");
        LocalDateTime now = LocalDateTime.now();
        doc.setStatus("REJECTED");
        doc.setReviewer(reviewer);
        doc.setReviewedAt(now);
        doc.setRejectReason(reason);
        doc.setUpdatedAt(now);
        addEvent(doc, "REJECTED", "AWAIT_REVIEW", "REJECTED", reviewer, "复核驳回：" + reason, now);
        documentRepository.save(doc);
        return seriesDetail(doc.getSeriesId());
    }

    /**
     * 撤销生效资料：必须填写原因。列出正在使用该资料的在途车辆并标记待调度处置；
     * 历史出车的派车资格快照原样保留。
     */
    @Transactional
    public Views.CertSeriesView revoke(Reqs.CertRevokeReq req) {
        lockInterlockAndSweep();
        CertDocument doc = lockDocument(req.documentId());
        String operator = req.operator().trim();
        assertStale(doc, req.expectedLockVersion());
        if (!"EFFECTIVE".equals(doc.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该资料当前是「" + StationDict.certStatusName(doc.getStatus())
                            + "」，只有生效资料可以撤销；它可能已被新版本替代或处理过，请刷新后再操作");
        }
        String reason = normalizeReason(req.reason(), "撤销原因");

        CertSeries series = seriesRepository.lockById(doc.getSeriesId());
        if (!doc.getId().equals(series.getCurrentDocumentId())) {
            // 页面长期未更新：链指针已挪到别的版本
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：该证照当前生效的已不是 V" + doc.getVersionNo()
                            + "，请刷新版本链后再撤销");
        }

        LocalDateTime now = LocalDateTime.now();
        doc.setStatus("REVOKED");
        doc.setRevokedAt(now);
        doc.setRevokeReason(reason);
        doc.setUpdatedAt(now);
        addEvent(doc, "REVOKED", "EFFECTIVE", "REVOKED", operator, "撤销：" + reason, now);
        documentRepository.save(doc);
        series.setCurrentDocumentId(null);
        seriesRepository.save(series);

        // 正在使用本版本的在途出车：快照保留，出车标待调度处置（仍占未结束名额，不能再次派车）
        List<DispatchTrip> affected = tripRepository.findOpenTripsUsingCert(series.getId(), doc.getId());
        String why = StationDict.certSubjectName(doc.getSubjectType()) + "·" + doc.getSubjectName()
                + " 的" + StationDict.certTypeName(doc.getCertType()) + " V" + doc.getVersionNo()
                + "（" + doc.getCertNo() + "）被撤销：" + reason + "；历史出车资格快照保留";
        for (DispatchTrip trip : affected) {
            trip.setStatus(DispatchTrip.PENDING_DISPOSAL);
            trip.setDisposalReason(why);
            tripRepository.save(trip);
        }
        return seriesDetail(series.getId());
    }

    // ================================================================
    // 派车资格核对（只核对，不落数据；快照清单供派车确认时原样固化）
    // ================================================================

    /**
     * 按"计划回场那一刻"核对一次派车资格。
     * 注意不是只判断今天：司机证照与车辆全部必需证照必须在【今天 → 计划回场】整段持续有效。
     */
    @Transactional(readOnly = true)
    public QualificationResult resolveQualification(Long vehicleId, Long driverId, LocalDateTime plannedReturn) {
        List<Issue> issues = new ArrayList<>();
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new BizException("车辆不存在"));
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new BizException("司机不存在或已删除"));
        if (!"ACTIVE".equals(driver.getStatus())) {
            issues.add(new Issue(BizException.BIZ_INVALID,
                    "司机 " + driver.getDriverName() + " 当前不是在岗状态，不能安排出车"));
        }
        LocalDate today = LocalDate.now();
        LocalDate returnDate = plannedReturn.toLocalDate();

        // 1. 准驾车型：司机驾驶证的准驾等级必须覆盖车辆按载重要求的等级
        String requiredClass = StationDict.requiredLicenseClass(vehicle.getLoadCapacity());
        CertDocument driverLicense = currentEffective(driverId, "DRIVER_LICENSE");
        String driverLicenseClass = driverLicense == null ? null : driverLicense.getLicenseClass();
        if (driverLicenseClass == null) {
            CertSeries dlSeries = findSeries("DRIVER", driverId, "DRIVER_LICENSE");
            issues.add(missingOrPending(dlSeries, "DRIVER_LICENSE", driver.getDriverName()));
        } else if (!StationDict.licenseCovers(driverLicenseClass, requiredClass)) {
            issues.add(new Issue(BizException.LICENSE_CLASS_MISMATCH,
                    "准驾车型不匹配：司机 " + driver.getDriverName() + " 持 "
                            + StationDict.licenseClassName(driverLicenseClass) + "，该车（额定载重 "
                            + vehicle.getLoadCapacity().stripTrailingZeros().toPlainString()
                            + " 吨）要求 " + StationDict.licenseClassName(requiredClass) + " 及以上"));
        }

        // 2. 司机其余必需证照
        List<CertDocument> driverDocs = new ArrayList<>();
        if (driverLicense != null) {
            driverDocs.add(driverLicense);
        }
        for (String certType : StationDict.driverCertTypes()) {
            if ("DRIVER_LICENSE".equals(certType)) {
                continue;
            }
            CertDocument doc = checkOne("DRIVER", driverId, certType, driver.getDriverName(),
                    today, returnDate, issues);
            if (doc != null) {
                driverDocs.add(doc);
            }
        }

        // 3. 车辆全部必需证照（年检 / 保险 / 道路运输证），缺一不可，且覆盖到计划回场当天
        List<CertDocument> vehicleDocs = new ArrayList<>();
        for (String certType : StationDict.vehicleCertTypes()) {
            CertDocument doc = checkOne("VEHICLE", vehicleId, certType, vehicle.getPlateNo(),
                    today, returnDate, issues);
            if (doc != null) {
                vehicleDocs.add(doc);
            }
        }

        boolean pass = issues.isEmpty();
        List<Views.TripCertView> snapshot = new ArrayList<>();
        if (pass) {
            for (CertDocument d : driverDocs) {
                snapshot.add(toTripCertView(d));
            }
            for (CertDocument d : vehicleDocs) {
                snapshot.add(toTripCertView(d));
            }
        }
        return new QualificationResult(pass, issues, requiredClass, driverLicenseClass, snapshot);
    }

    /** 核对某一份必需证照：返回当前生效版本（供快照），不通过就往 issues 里追加一条 */
    private CertDocument checkOne(String subjectType, Long subjectId, String certType, String subjectName,
                                  LocalDate today, LocalDate returnDate, List<Issue> issues) {
        CertSeries series = findSeries(subjectType, subjectId, certType);
        CertDocument doc = currentEffective(series);
        if (doc == null) {
            issues.add(missingOrPending(series, certType, subjectName));
            return null;
        }
        if (doc.getValidTo().isBefore(today)) {
            // 扫描未跑（只读场景）的兜底口径
            issues.add(new Issue(BizException.CERT_EXPIRED,
                    "证照已过期：" + subjectName + " 的" + StationDict.certTypeName(certType)
                            + "有效期至 " + doc.getValidTo()));
            return null;
        }
        if (doc.getValidTo().isBefore(returnDate)) {
            issues.add(new Issue(BizException.RETURN_BEYOND_VALIDITY,
                    "计划回场晚于有效期：" + subjectName + " 的" + StationDict.certTypeName(certType)
                            + "只有效到 " + doc.getValidTo() + "，覆盖不到计划回场日 " + returnDate
                            + "，请先续办并复核生效，或把回场时间提前"));
            return null;
        }
        return doc;
    }

    private Issue missingOrPending(CertSeries series, String certType, String subjectName) {
        String typeName = StationDict.certTypeName(certType);
        if (series != null) {
            List<CertDocument> docs = documentRepository.findBySeriesIdOrderByVersionNoAsc(series.getId());
            boolean pending = docs.stream().anyMatch(d -> "AWAIT_REVIEW".equals(d.getStatus()));
            if (pending) {
                return new Issue(BizException.CERT_PENDING_REVIEW,
                        "资料仍待复核：" + subjectName + " 的" + typeName + "新版本已提交但尚未复核生效，暂不能派车");
            }
            CertDocument latest = docs.stream().max(Comparator.comparingInt(CertDocument::getVersionNo)).orElse(null);
            if (latest != null && "REVOKED".equals(latest.getStatus())) {
                return new Issue(BizException.CERT_EXPIRED,
                        "证照资料已撤销：" + subjectName + " 的" + typeName
                                + "生效版本已被撤销，请重新录入并复核生效后再派车");
            }
            if (latest != null && ("EXPIRED".equals(latest.getStatus())
                    || (latest.getValidTo() != null && latest.getValidTo().isBefore(LocalDate.now())))) {
                return new Issue(BizException.CERT_EXPIRED,
                        "证照已过期：" + subjectName + " 的" + typeName
                                + "有效期至 " + latest.getValidTo() + "，请续办后再派车");
            }
            if (latest != null && "REJECTED".equals(latest.getStatus())) {
                return new Issue(BizException.CERT_PENDING_REVIEW,
                        "资料仍待复核：" + subjectName + " 的" + typeName
                                + "最新版本（V" + latest.getVersionNo() + "）已被驳回，尚无生效版本");
            }
        }
        return new Issue(BizException.CERT_PENDING_REVIEW,
                "资料仍待复核：" + subjectName + " 的" + typeName + "尚未录入生效资料");
    }

    // ================================================================
    // 内部工具
    // ================================================================

    CertDocument currentEffective(Long subjectId, String certType) {
        return currentEffective(findSeries("DRIVER", subjectId, certType));
    }

    CertDocument currentEffective(CertSeries series) {
        if (series == null || series.getCurrentDocumentId() == null) {
            return null;
        }
        CertDocument doc = documentRepository.findById(series.getCurrentDocumentId()).orElse(null);
        return doc != null && "EFFECTIVE".equals(doc.getStatus()) ? doc : null;
    }

    CertSeries findSeries(String subjectType, Long subjectId, String certType) {
        return seriesRepository
                .findBySubjectTypeAndSubjectIdAndCertType(subjectType, subjectId, certType)
                .orElse(null);
    }

    private CertDocument lockDocument(Long documentId) {
        CertDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new BizException("资料版本不存在或已被删除"));
        return doc;
    }

    /** 乐观锁过期判定：页面长期未更新 / 网络超时后重试，expectedLockVersion 与当前不一致一律拒绝 */
    private void assertStale(CertDocument doc, Long expectedLockVersion) {
        if (expectedLockVersion == null || !expectedLockVersion.equals(doc.getLockVersion())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：你打开页面时的 V" + doc.getVersionNo()
                            + " 状态已被其他人更新（当前版本号 " + doc.getLockVersion()
                            + "），请刷新后重新操作");
        }
    }

    private String lockSubjectName(String subjectType, Long subjectId) {
        if ("DRIVER".equals(subjectType)) {
            Driver driver = driverRepository.findById(subjectId)
                    .orElseThrow(() -> new BizException("司机不存在，请先在司机名册建档"));
            return driver.getDriverName();
        }
        Vehicle vehicle = vehicleRepository.findById(subjectId)
                .orElseThrow(() -> new BizException("车辆不存在，请先登记车辆"));
        return vehicle.getPlateNo();
    }

    private String buildSeriesNo(String subjectType, Long subjectId, String certType) {
        return subjectType + "-" + subjectId + "-" + certType;
    }

    private void addEvent(CertDocument doc, String type, String from, String to,
                          String operator, String note, LocalDateTime at) {
        CertEvent event = new CertEvent();
        event.setDocument(doc);
        event.setSeriesId(doc.getSeriesId());
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperator(operator);
        event.setNote(note);
        event.setCreatedAt(at);
        doc.getEvents().add(event);
    }

    private String normalizeReason(String reason, String field) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            throw new BizException(field + "必填");
        }
        if (value.length() > REASON_MAX) {
            throw new BizException(field + "不能超过 " + REASON_MAX + " 字");
        }
        return value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw new BizException(field + "日期格式应为 yyyy-MM-dd");
        }
    }

    /** 接受 "yyyy-MM-dd HH:mm" 与 ISO 的 "yyyy-MM-ddTHH:mm" */
    public static LocalDateTime parseDateTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(field + "不能为空");
        }
        String text = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            } catch (DateTimeParseException e2) {
                throw new BizException(field + "格式应为 yyyy-MM-dd HH:mm");
            }
        }
    }

    private Views.CertDocumentView toDocumentView(CertDocument doc, Long currentDocumentId) {
        List<Views.CertEventView> events = new ArrayList<>();
        for (CertEvent e : doc.getEvents()) {
            events.add(new Views.CertEventView(e.getId(), doc.getId(), e.getEventType(),
                    StationDict.certEventName(e.getEventType()), e.getFromStatus(),
                    StationDict.certStatusName(e.getFromStatus()), e.getToStatus(),
                    StationDict.certStatusName(e.getToStatus()), e.getOperator(), e.getNote(),
                    e.getCreatedAt()));
        }
        return new Views.CertDocumentView(doc.getId(), doc.getSeriesId(), doc.getVersionNo(),
                doc.getSubjectType(), StationDict.certSubjectName(doc.getSubjectType()),
                doc.getSubjectId(), doc.getSubjectName(),
                doc.getCertType(), StationDict.certTypeName(doc.getCertType()),
                doc.getCertNo(), doc.getLicenseClass(), StationDict.licenseClassName(doc.getLicenseClass()),
                doc.getValidFrom(), doc.getValidTo(), doc.getAttachmentNote(),
                doc.getOperator(), doc.getReviewer(),
                doc.getStatus(), StationDict.certStatusName(doc.getStatus()),
                doc.getRejectReason(), doc.getRevokeReason(),
                doc.getReviewedAt(), doc.getRevokedAt(), doc.getExpiredAt(),
                doc.getCreatedAt(), doc.getUpdatedAt(), doc.getLockVersion(),
                doc.getId().equals(currentDocumentId), events);
    }

    private Views.TripCertView toTripCertView(CertDocument doc) {
        return new Views.TripCertView(null, doc.getSeriesId(), doc.getId(),
                doc.getSubjectType(), StationDict.certSubjectName(doc.getSubjectType()),
                doc.getSubjectName(), doc.getCertType(), StationDict.certTypeName(doc.getCertType()),
                doc.getCertNo(), doc.getLicenseClass(), StationDict.licenseClassName(doc.getLicenseClass()),
                doc.getValidFrom(), doc.getValidTo(), doc.getVersionNo(),
                "EFFECTIVE", StationDict.certStatusName("EFFECTIVE"));
    }

    private Views.CertSeriesView buildSeriesView(CertSeries series) {
        List<CertDocument> docs = documentRepository.findBySeriesIdOrderByVersionNoAsc(series.getId());
        docs.sort(Comparator.comparingInt(CertDocument::getVersionNo).reversed());
        List<Views.CertDocumentView> documentViews = new ArrayList<>();
        Views.CertDocumentView currentView = null;
        Views.CertDocumentView pendingView = null;
        for (CertDocument doc : docs) {
            Views.CertDocumentView view = toDocumentView(doc, series.getCurrentDocumentId());
            documentViews.add(view);
            if (doc.getId().equals(series.getCurrentDocumentId())) {
                currentView = view;
            }
            if ("AWAIT_REVIEW".equals(doc.getStatus())) {
                pendingView = view;
            }
        }

        // 复核人视角：当前生效版本正被哪些在途车使用（撤销前必须能看到影响面）
        List<Map<String, Object>> affected = new ArrayList<>();
        if (series.getCurrentDocumentId() != null) {
            for (DispatchTrip trip : tripRepository.findOpenTripsUsingCert(
                    series.getId(), series.getCurrentDocumentId())) {
                Map<String, Object> row = new HashMap<>();
                row.put("tripId", trip.getId());
                row.put("tripNo", trip.getTripNo());
                row.put("plateNo", trip.getPlateNo());
                row.put("driverName", trip.getDriverName());
                row.put("status", trip.getStatus());
                row.put("statusName", DispatchService.tripStatusName(trip.getStatus()));
                row.put("plannedReturnAt", trip.getPlannedReturnAt());
                affected.add(row);
            }
        }
        return new Views.CertSeriesView(series.getId(), series.getSeriesNo(),
                series.getSubjectType(), StationDict.certSubjectName(series.getSubjectType()),
                series.getSubjectId(), series.getSubjectName(),
                series.getCertType(), StationDict.certTypeName(series.getCertType()),
                series.getCurrentDocumentId(), series.getNextVersionNo(),
                currentView != null, currentView, pendingView, documentViews, affected);
    }

    // ================================================================
    // 对外只读结构
    // ================================================================

    /** 一次资格核对的结构化结论 */
    public record QualificationResult(boolean pass, List<Issue> issues,
                                      String requiredLicenseClass, String driverLicenseClass,
                                      List<Views.TripCertView> snapshot) {
    }

    public record Issue(String code, String message) {

        public Map<String, Object> toMap() {
            return Map.of("code", code, "message", message);
        }
    }

    Optional<CertDocument> findEffectiveOfSeries(Long seriesId) {
        return seriesRepository.findById(seriesId).map(this::currentEffective);
    }
}
