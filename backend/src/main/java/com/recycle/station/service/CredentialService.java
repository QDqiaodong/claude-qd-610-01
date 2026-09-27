package com.recycle.station.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.CredentialDoc;
import com.recycle.station.domain.CredentialDocEvent;
import com.recycle.station.domain.DispatchCredentialSnapshot;
import com.recycle.station.domain.DispatchTrip;
import com.recycle.station.domain.Driver;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.Vehicle;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.CredentialDocEventRepository;
import com.recycle.station.repository.CredentialDocRepository;
import com.recycle.station.repository.DispatchCredentialSnapshotRepository;
import com.recycle.station.repository.DispatchTripRepository;
import com.recycle.station.repository.DriverRepository;
import com.recycle.station.repository.VehicleRepository;

/**
 * 模块七·证照有效期联锁台。
 * <p>
 * 一条版本链（owner + 证件类型）上的全部版本永久留痕：草稿 → 待复核 → 生效 →
 * （驳回 / 撤销 / 到期 / 被新版本替代）。同一业务键永远只有一行 EFFECTIVE：
 * 业务层先把旧生效版置为 SUPERSEDED 并立即 flush，再放新生效版；
 * 数据库 uk_cred_active_slot 唯一索引兜底，双复核并发 / 超时重试也落不出第二份生效版本。
 * <p>
 * 生效版本被撤销或替代时，正在使用该版本的在途车辆只打“待调度处置”标志，
 * 派车资格快照原样保留；历史出车永远能回放当时那一版。
 */
@Service
public class CredentialService {

    private final CredentialDocRepository docRepository;
    private final CredentialDocEventRepository eventRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DispatchTripRepository tripRepository;
    private final DispatchCredentialSnapshotRepository snapshotRepository;

    public CredentialService(CredentialDocRepository docRepository,
                             CredentialDocEventRepository eventRepository,
                             DriverRepository driverRepository,
                             VehicleRepository vehicleRepository,
                             DispatchTripRepository tripRepository,
                             DispatchCredentialSnapshotRepository snapshotRepository) {
        this.docRepository = docRepository;
        this.eventRepository = eventRepository;
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripRepository = tripRepository;
        this.snapshotRepository = snapshotRepository;
    }

    // ================= 司机花名册 =================

    @Transactional(readOnly = true)
    public List<Views.DriverView> listDrivers() {
        return driverRepository.findAllByOrderByIdAsc().stream().map(this::toDriverView).toList();
    }

    @Transactional
    public Views.DriverView createDriver(Reqs.DriverSaveReq req) {
        if (driverRepository.findByDriverName(req.driverName().trim()) != null) {
            throw new BizException("司机 " + req.driverName() + " 已登记过");
        }
        String licenseClass = normalizeLicenseClass(req.licenseClass());
        Driver driver = new Driver();
        driver.setDriverName(req.driverName().trim());
        driver.setPhone(blankToNull(req.phone()));
        driver.setLicenseClass(licenseClass);
        driver.setStatus(normalizeStatus(req.status(), "ACTIVE", StationDict::isValidDriverStatus));
        driver.setRemark(blankToNull(req.remark()));
        return toDriverView(driverRepository.save(driver));
    }

    @Transactional
    public Views.DriverView updateDriver(Long id, Reqs.DriverSaveReq req) {
        Driver driver = driverRepository.findById(id).orElseThrow(() -> new BizException("司机不存在"));
        Driver same = driverRepository.findByDriverName(req.driverName().trim());
        if (same != null && !same.getId().equals(id)) {
            throw new BizException("司机姓名 " + req.driverName() + " 已被其他司机占用");
        }
        driver.setDriverName(req.driverName().trim());
        driver.setPhone(blankToNull(req.phone()));
        driver.setLicenseClass(normalizeLicenseClass(req.licenseClass()));
        driver.setStatus(normalizeStatus(req.status(), "ACTIVE", StationDict::isValidDriverStatus));
        driver.setRemark(blankToNull(req.remark()));
        return toDriverView(driverRepository.save(driver));
    }

    // ================= 证照列表 / 队列 / 版本链 =================

    @Transactional(readOnly = true)
    public List<Views.CredentialDocView> listDocs(String ownerType, Long ownerId) {
        List<CredentialDoc> docs;
        if (ownerType != null && ownerId != null) {
            docs = docRepository.findByOwnerTypeAndOwnerIdOrderByDocTypeAscVersionNoAsc(ownerType, ownerId);
        } else {
            docs = docRepository.findAll();
            docs.sort(Comparator.comparing(CredentialDoc::getOwnerType)
                    .thenComparing(CredentialDoc::getOwnerId)
                    .thenComparing(CredentialDoc::getDocType)
                    .thenComparing(CredentialDoc::getVersionNo));
        }
        LocalDate today = LocalDate.now();
        return docs.stream().map(d -> toDocView(d, today)).toList();
    }

    /** 当前生效版本（派车试算/车辆可派状态共用）；惰性判断到期，不改写历史状态口径外的内容 */
    @Transactional(readOnly = true)
    public Optional<CredentialDoc> findActive(String ownerType, Long ownerId, String docType) {
        return docRepository.findByOwnerTypeAndOwnerIdAndDocTypeAndStatus(ownerType, ownerId, docType, "EFFECTIVE");
    }

    /** 待复核队列：按提交时间先到先复核 */
    @Transactional(readOnly = true)
    public List<Views.ReviewQueueItem> reviewQueue() {
        LocalDate today = LocalDate.now();
        List<Views.ReviewQueueItem> items = new ArrayList<>();
        for (CredentialDoc doc : docRepository.findByStatusOrderBySubmittedAtAsc("PENDING_REVIEW")) {
            items.add(new Views.ReviewQueueItem(toDocView(doc, today), doc.getOwnerType(), doc.getOwnerId(),
                    doc.getOwnerName(), StationDict.credentialTypeName(doc.getDocType()), doc.getHandler(),
                    doc.getSubmittedAt(), StationDict.requiredCredentialTypes(doc.getOwnerType())));
        }
        return items;
    }

    /** 版本链全貌：全部旧版 + 逐版字段差异 + 变化轨迹（复核人核对新旧版本用） */
    @Transactional(readOnly = true)
    public Views.CredentialGroupView groupDetail(String groupUuid) {
        List<CredentialDoc> versions = docRepository.findByGroupUuidOrderByVersionNoAsc(groupUuid);
        if (versions.isEmpty()) {
            throw new BizException("证照版本链不存在");
        }
        CredentialDoc first = versions.get(0);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (int i = 1; i < versions.size(); i++) {
            CredentialDoc oldV = versions.get(i - 1);
            CredentialDoc newV = versions.get(i);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("fromVersion", oldV.getVersionNo());
            pair.put("toVersion", newV.getVersionNo());
            pair.put("fields", fieldDiffs(oldV, newV));
            diffs.add(pair);
        }
        List<Views.CredentialEventView> events =
                eventRepository.findByGroupUuidOrderByCreatedAtAscIdAsc(groupUuid).stream()
                        .map(this::toEventView).toList();
        return new Views.CredentialGroupView(first.getOwnerType(), first.getOwnerId(), first.getOwnerName(),
                first.getDocType(), StationDict.credentialTypeName(first.getDocType()), groupUuid,
                versions.stream().map(d -> toDocView(d, today)).toList(), events, diffs);
    }

    /** 正在使用某份资料的在途车辆（撤销/换版影响面 + 复核台提示） */
    @Transactional(readOnly = true)
    public List<Views.AffectedTripView> listAffectedOnRoad(Long docId) {
        Map<Long, DispatchTrip> tripMap = new LinkedHashMap<>();
        for (DispatchCredentialSnapshot snapshot : snapshotRepository.findByDocId(docId)) {
            DispatchTrip trip = snapshot.getTrip();
            if (trip != null && "ON_ROAD".equals(trip.getStatus())) {
                tripMap.put(trip.getId(), trip);
            }
        }
        CredentialDoc doc = docRepository.findById(docId).orElse(null);
        return tripMap.values().stream()
                .map(t -> toAffectedView(t, doc))
                .sorted(Comparator.comparing(Views.AffectedTripView::dispatchedAt).reversed())
                .toList();
    }

    // ================= 草稿 / 提交 / 复核 / 撤销 =================

    @Transactional
    public Views.CredentialDocView saveDraft(Reqs.CredentialSaveReq req) {
        String ownerType = normalizeOwnerType(req.ownerType());
        String docType = normalizeDocType(ownerType, req.docType());
        Long ownerId = req.ownerId();
        String ownerName = ownerName(ownerType, ownerId);

        // 同一业务键：有待复核版本必须先处理；有草稿就改草稿，不允许平行两条新版本
        List<CredentialDoc> chain = docRepository
                .findByOwnerTypeAndOwnerIdOrderByDocTypeAscVersionNoAsc(ownerType, ownerId).stream()
                .filter(d -> d.getDocType().equals(docType)).toList();
        for (CredentialDoc d : chain) {
            if ("PENDING_REVIEW".equals(d.getStatus())) {
                throw new BizException("该证照已有 V" + d.getVersionNo() + " 待复核，请等复核结论后再操作");
            }
            if ("DRAFT".equals(d.getStatus())) {
                throw new BizException("该证照已有 V" + d.getVersionNo()
                        + " 草稿，请直接编辑该草稿提交，不要重复建档");
            }
        }

        String groupUuid;
        int versionNo;
        if (req.basedOnId() != null) {
            CredentialDoc base = docRepository.findById(req.basedOnId())
                    .orElseThrow(() -> new BizException("基准版本不存在"));
            if (!base.getOwnerType().equals(ownerType) || !base.getOwnerId().equals(ownerId)
                    || !base.getDocType().equals(docType)) {
                throw new BizException("新版本必须与基准版本属于同一司机/车辆和同一证件类型");
            }
            if (!"EFFECTIVE".equals(base.getStatus())) {
                throw new BizException("只能基于当前生效版本换版，旧版本内容不能直接改");
            }
            groupUuid = base.getGroupUuid();
            versionNo = chain.size() + 1;
        } else {
            boolean hasEffective = chain.stream().anyMatch(d -> "EFFECTIVE".equals(d.getStatus()));
            if (hasEffective) {
                throw new BizException("该证照已有生效版本，换证/续期请在生效版本上“提交新版本”");
            }
            groupUuid = UUID.randomUUID().toString();
            versionNo = chain.size() + 1;
        }

        CredentialDoc doc = new CredentialDoc();
        doc.setOwnerType(ownerType);
        doc.setOwnerId(ownerId);
        doc.setOwnerName(ownerName);
        doc.setDocType(docType);
        doc.setVersionNo(versionNo);
        doc.setGroupUuid(groupUuid);
        doc.setHandler(req.handler().trim());
        doc.setStatus("DRAFT");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setLockVersion(0L);
        applyEditableFields(doc, req.docNumber(), req.licenseClass(), req.subjectDesc(),
                req.validFrom(), req.validUntil(), req.attachmentNote(), docType);
        CredentialDoc saved = docRepository.save(doc);
        recordEvent(saved, "CREATE", null, "DRAFT", saved.getHandler(), null);
        return toDocView(saved, LocalDate.now());
    }

    @Transactional
    public Views.CredentialDocView updateDraft(Long id, Reqs.CredentialUpdateReq req) {
        CredentialDoc doc = lockDoc(id);
        if (!"DRAFT".equals(doc.getStatus()) && !"REJECTED".equals(doc.getStatus())) {
            throw new BizException("资料版本已变化：只有草稿或被驳回的版本能修改，生效/待复核版本不可改写");
        }
        applyEditableFields(doc, req.docNumber(), req.licenseClass(), req.subjectDesc(),
                req.validFrom(), req.validUntil(), req.attachmentNote(), doc.getDocType());
        CredentialDoc saved = docRepository.save(doc);
        return toDocView(saved, LocalDate.now());
    }

    @Transactional
    public Views.CredentialDocView submit(Long id, Reqs.CredentialSubmitReq req) {
        CredentialDoc doc = lockDoc(id);
        if (!"DRAFT".equals(doc.getStatus()) && !"REJECTED".equals(doc.getStatus())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "资料版本已变化：该版本当前是「" + StationDict.credentialStatusName(doc.getStatus())
                            + "」，不能再提交复核，请刷新");
        }
        if (!doc.getHandler().equals(req.handler().trim())) {
            throw new BizException("只能由资料经办人本人提交复核");
        }
        LocalDate today = LocalDate.now();
        if (doc.getValidUntil().isBefore(today)) {
            throw new BizException(BizException.CRED_EXPIRED, "该资料有效期已过，提交复核也无法生效，请换新版本");
        }
        doc.setStatus("PENDING_REVIEW");
        doc.setSubmittedAt(LocalDateTime.now());
        doc.setReason(null);
        CredentialDoc saved = docRepository.save(doc);
        recordEvent(saved, "SUBMIT", "REJECTED".equals(doc.getStatus()) ? "REJECTED" : "DRAFT",
                "PENDING_REVIEW", saved.getHandler(), null);
        return toDocView(saved, today);
    }

    /**
     * 复核通过：复核人不能是经办人；旧生效版先落 SUPERSEDED 并 flush，新生效版才允许 EFFECTIVE。
     * 正在使用旧版的在途车辆打待调度处置标志，历史快照不动。
     */
    @Transactional
    public Views.CredentialDocView approve(Long id, Reqs.CredentialApproveReq req) {
        CredentialDoc doc = lockDoc(id);
        if (!"PENDING_REVIEW".equals(doc.getStatus())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "资料版本已变化：该版本已被处理为「" + StationDict.credentialStatusName(doc.getStatus())
                            + "」，请刷新待复核队列");
        }
        String reviewer = req.reviewer().trim();
        if (reviewer.equals(doc.getHandler())) {
            throw new BizException(BizException.SELF_CRED_REVIEW_FORBIDDEN,
                    "经办人不能复核自己录入的资料（经办人：" + doc.getHandler() + "）");
        }
        LocalDate today = LocalDate.now();
        if (doc.getValidUntil().isBefore(today)) {
            throw new BizException(BizException.CRED_EXPIRED, "证照有效期止于 " + doc.getValidUntil()
                    + "，已过期，不能生效；请驳回并要求经办人换版");
        }
        if (doc.getValidFrom().isAfter(today)) {
            throw new BizException("证照起始日为 " + doc.getValidFrom() + "，尚未到生效时间");
        }

        CredentialDoc oldEffective = docRepository
                .findByOwnerTypeAndOwnerIdAndDocTypeAndStatus(
                        doc.getOwnerType(), doc.getOwnerId(), doc.getDocType(), "EFFECTIVE")
                .orElse(null);
        if (oldEffective != null && !oldEffective.getId().equals(doc.getId())) {
            oldEffective.setStatus("SUPERSEDED");
            oldEffective.setClosedAt(LocalDateTime.now());
            docRepository.saveAndFlush(oldEffective);
            recordEvent(oldEffective, "SUPERSEDE", "EFFECTIVE", "SUPERSEDED", reviewer,
                    "由 V" + doc.getVersionNo() + " 替代");
        }

        doc.setStatus("EFFECTIVE");
        doc.setReviewer(reviewer);
        doc.setReviewedAt(LocalDateTime.now());
        CredentialDoc saved = docRepository.saveAndFlush(doc);
        recordEvent(saved, "APPROVE", "PENDING_REVIEW", "EFFECTIVE", reviewer, req.note());

        if (oldEffective != null) {
            markTripsPending(oldEffective, "原生效版本 V" + oldEffective.getVersionNo()
                    + " 已被 V" + doc.getVersionNo() + " 替代，请联系车辆提前回场核验新资料");
        }
        return toDocView(saved, today);
    }

    @Transactional
    public Views.CredentialDocView reject(Long id, Reqs.CredentialRejectReq req) {
        CredentialDoc doc = lockDoc(id);
        if (!"PENDING_REVIEW".equals(doc.getStatus())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "资料版本已变化：该版本已被处理为「" + StationDict.credentialStatusName(doc.getStatus())
                            + "」，请刷新待复核队列");
        }
        String reviewer = req.reviewer().trim();
        if (reviewer.equals(doc.getHandler())) {
            throw new BizException(BizException.SELF_CRED_REVIEW_FORBIDDEN,
                    "经办人不能复核自己录入的资料（经办人：" + doc.getHandler() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        doc.setStatus("REJECTED");
        doc.setReviewer(reviewer);
        doc.setReviewedAt(now);
        doc.setReason(req.reason().trim());
        CredentialDoc saved = docRepository.save(doc);
        recordEvent(saved, "REJECT", "PENDING_REVIEW", "REJECTED", reviewer, req.reason().trim());
        return toDocView(saved, LocalDate.now());
    }

    /**
     * 撤销生效资料：必须填原因；列出正在使用它的在途车辆并全部标记待调度处置；
     * 历史出车的资格快照原样保留。撤销后该业务键没有生效版本，派车一律被联锁拦下。
     */
    @Transactional
    public List<Views.AffectedTripView> revoke(Long id, Reqs.CredentialRevokeReq req) {
        CredentialDoc doc = lockDoc(id);
        if (!"EFFECTIVE".equals(doc.getStatus())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "资料版本已变化：只有生效中的资料能撤销，该版本当前是「"
                            + StationDict.credentialStatusName(doc.getStatus()) + "」，请刷新");
        }
        LocalDateTime now = LocalDateTime.now();
        doc.setStatus("REVOKED");
        doc.setReason(req.reason().trim());
        doc.setClosedAt(now);
        CredentialDoc saved = docRepository.saveAndFlush(doc);
        recordEvent(saved, "REVOKE", "EFFECTIVE", "REVOKED", req.operator().trim(), req.reason().trim());

        List<DispatchTrip> affected = markTripsPending(saved,
                "生效资料「" + StationDict.credentialTypeName(saved.getDocType()) + " V" + saved.getVersionNo()
                        + "」已撤销：" + req.reason().trim());
        return affected.stream().map(t -> toAffectedView(t, saved)).toList();
    }

    /**
     * 到期扫描：每日凌晨 + 服务启动时执行。生效版本有效期已过的统一转 EXPIRED，
     * 仍在途的车一并打待调度处置。跨日/重启后状态口径一致靠它兜底。
     */
    @Transactional
    public int expireDueDocs() {
        LocalDate today = LocalDate.now();
        int count = 0;
        for (CredentialDoc doc : docRepository.findByStatus("EFFECTIVE")) {
            if (doc.getValidUntil().isBefore(today)) {
                doc.setStatus("EXPIRED");
                doc.setClosedAt(LocalDateTime.now());
                CredentialDoc saved = docRepository.saveAndFlush(doc);
                recordEvent(saved, "EXPIRE", "EFFECTIVE", "EXPIRED", "SYSTEM",
                        "有效期止于 " + saved.getValidUntil() + "，系统自动转到期");
                markTripsPending(saved, "证照「" + StationDict.credentialTypeName(saved.getDocType())
                        + "」已于 " + saved.getValidUntil() + " 到期，请安排车辆回场");
                count++;
            }
        }
        return count;
    }

    // ================= 派车侧取数（DispatchService 用） =================

    /** 派车事务内对生效行加写锁，拿不到就排在资质变更事务后面，杜绝“车已出发无有效资料” */
    @Transactional
    public Optional<CredentialDoc> lockActiveForDispatch(String ownerType, Long ownerId, String docType) {
        return docRepository.lockActive(ownerType, ownerId, docType);
    }

    // ================= 私有辅助 =================

    private CredentialDoc lockDoc(Long id) {
        return docRepository.lockById(id).orElseThrow(() -> new BizException("证照资料不存在"));
    }

    private List<DispatchTrip> markTripsPending(CredentialDoc doc, String reason) {
        List<DispatchTrip> affected = new ArrayList<>();
        Map<Long, DispatchTrip> unique = new LinkedHashMap<>();
        for (DispatchCredentialSnapshot snapshot : snapshotRepository.findByDocId(doc.getId())) {
            DispatchTrip trip = snapshot.getTrip();
            if (trip != null && "ON_ROAD".equals(trip.getStatus())) {
                unique.put(trip.getId(), trip);
            }
        }
        for (DispatchTrip trip : unique.values()) {
            trip.setPendingDispatchFlag(true);
            trip.setPendingReason(reason);
            tripRepository.save(trip);
            vehicleRepository.findById(trip.getVehicleId()).ifPresent(v -> {
                // 仅当车辆确实挂着本次未结束出车时打车辆标志，避免与出车单状态漂移
                v.setPendingDispatchFlag(true);
                v.setPendingDispatchReason(reason);
                vehicleRepository.save(v);
            });
            affected.add(trip);
        }
        return affected;
    }

    private void applyEditableFields(CredentialDoc doc, String docNumber, String licenseClass, String subjectDesc,
                                     String validFromText, String validUntilText, String attachmentNote,
                                     String docType) {
        LocalDate validFrom = parseDate(validFromText, "有效期起");
        LocalDate validUntil = parseDate(validUntilText, "有效期止");
        if (validUntil.isBefore(validFrom)) {
            throw new BizException("有效期止不能早于有效期起");
        }
        doc.setDocNumber(docNumber.trim());
        doc.setValidFrom(validFrom);
        doc.setValidUntil(validUntil);
        doc.setAttachmentNote(blankToNull(attachmentNote));
        if ("CRED_DRIVER_LICENSE".equals(docType)) {
            String cls = normalizeLicenseClass(licenseClass);
            if (cls == null) {
                throw new BizException("驾驶证必须填写准驾车型（C1 / B2 / A2 / A1）");
            }
            doc.setLicenseClass(cls);
            doc.setSubjectDesc(blankToNull(subjectDesc));
        } else if ("DRIVER".equals(doc.getOwnerType())) {
            doc.setLicenseClass(null);
            doc.setSubjectDesc(blankToNull(subjectDesc));
        } else {
            doc.setLicenseClass(null);
            doc.setSubjectDesc(blankToNull(subjectDesc));
        }
    }

    private List<Views.DiffField> fieldDiffs(CredentialDoc a, CredentialDoc b) {
        List<Views.DiffField> fields = new ArrayList<>();
        fields.add(new Views.DiffField("docNumber", "证件号码", a.getDocNumber(), b.getDocNumber(),
                !a.getDocNumber().equals(b.getDocNumber())));
        fields.add(new Views.DiffField("licenseClass", "准驾车型",
                StationDict.licenseClassName(a.getLicenseClass()),
                StationDict.licenseClassName(b.getLicenseClass()),
                !ObjectsEquals(a.getLicenseClass(), b.getLicenseClass())));
        fields.add(new Views.DiffField("subjectDesc", "适用车型/车辆",
                nullToEmpty(a.getSubjectDesc()), nullToEmpty(b.getSubjectDesc()),
                !ObjectsEquals(a.getSubjectDesc(), b.getSubjectDesc())));
        fields.add(new Views.DiffField("validFrom", "有效期起",
                String.valueOf(a.getValidFrom()), String.valueOf(b.getValidFrom()),
                !a.getValidFrom().equals(b.getValidFrom())));
        fields.add(new Views.DiffField("validUntil", "有效期止",
                String.valueOf(a.getValidUntil()), String.valueOf(b.getValidUntil()),
                !a.getValidUntil().equals(b.getValidUntil())));
        fields.add(new Views.DiffField("attachmentNote", "附件说明",
                nullToEmpty(a.getAttachmentNote()), nullToEmpty(b.getAttachmentNote()),
                !ObjectsEquals(a.getAttachmentNote(), b.getAttachmentNote())));
        return fields;
    }

    private void recordEvent(CredentialDoc doc, String action, String fromStatus, String toStatus,
                             String operator, String reason) {
        CredentialDocEvent event = new CredentialDocEvent();
        event.setDocId(doc.getId());
        event.setGroupUuid(doc.getGroupUuid());
        event.setAction(action);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setOperator(operator);
        event.setReason(reason);
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    private String ownerName(String ownerType, Long ownerId) {
        if ("DRIVER".equals(ownerType)) {
            return driverRepository.findById(ownerId)
                    .orElseThrow(() -> new BizException("司机不存在")).getDriverName();
        }
        return vehicleRepository.findById(ownerId)
                .orElseThrow(() -> new BizException("车辆不存在")).getPlateNo();
    }

    private String normalizeOwnerType(String ownerType) {
        String value = ownerType == null ? "" : ownerType.trim();
        if (!"DRIVER".equals(value) && !"VEHICLE".equals(value)) {
            throw new BizException("证照归属类型只能是 DRIVER 司机证照 / VEHICLE 车辆证照");
        }
        return value;
    }

    private String normalizeDocType(String ownerType, String docType) {
        String value = docType == null ? "" : docType.trim();
        if (!StationDict.isValidCredentialType(value)) {
            throw new BizException("证件类型不合法");
        }
        boolean driverType = StationDict.DRIVER_REQUIRED_DOCS.contains(value);
        if ("DRIVER".equals(ownerType) && !driverType) {
            throw new BizException("驾驶证 / 从业资格证只能挂在司机名下");
        }
        if ("VEHICLE".equals(ownerType) && driverType) {
            throw new BizException("年检 / 保险 / 道路运输证只能挂在车辆名下");
        }
        return value;
    }

    private String normalizeLicenseClass(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cls = value.trim().toUpperCase();
        if (!StationDict.isValidLicenseClass(cls)) {
            throw new BizException("准驾车型只能是 C1 / B2 / A2 / A1");
        }
        return cls;
    }

    private <T> T normalizeStatus(String value, T fallback, java.util.function.Predicate<T> valid) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        @SuppressWarnings("unchecked")
        T cast = (T) value.trim();
        if (!valid.test(cast)) {
            throw new BizException("状态取值不合法");
        }
        return cast;
    }

    private LocalDate parseDate(String text, String field) {
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            throw new BizException(field + "日期格式应为 yyyy-MM-dd");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean ObjectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private Views.DriverView toDriverView(Driver driver) {
        return new Views.DriverView(driver.getId(), driver.getDriverName(), driver.getPhone(),
                driver.getLicenseClass(), StationDict.licenseClassName(driver.getLicenseClass()),
                driver.getStatus(), StationDict.driverStatusName(driver.getStatus()), driver.getRemark());
    }

    private Views.CredentialDocView toDocView(CredentialDoc doc, LocalDate today) {
        boolean expiredByToday = "EFFECTIVE".equals(doc.getStatus()) && doc.getValidUntil().isBefore(today);
        return new Views.CredentialDocView(doc.getId(), doc.getOwnerType(), doc.getOwnerId(), doc.getOwnerName(),
                doc.getDocType(), StationDict.credentialTypeName(doc.getDocType()), doc.getVersionNo(),
                doc.getGroupUuid(), doc.getDocNumber(), doc.getLicenseClass(),
                StationDict.licenseClassName(doc.getLicenseClass()), doc.getSubjectDesc(),
                doc.getValidFrom(), doc.getValidUntil(), doc.getAttachmentNote(),
                doc.getStatus(), StationDict.credentialStatusName(doc.getStatus()),
                doc.getHandler(), doc.getReviewer(), doc.getReason(),
                doc.getCreatedAt(), doc.getSubmittedAt(), doc.getReviewedAt(), doc.getClosedAt(),
                doc.getLockVersion(), expiredByToday);
    }

    private Views.CredentialEventView toEventView(CredentialDocEvent event) {
        return new Views.CredentialEventView(event.getId(), event.getDocId(), event.getAction(),
                StationDict.credentialActionName(event.getAction()),
                event.getFromStatus(), StationDict.credentialStatusName(event.getFromStatus()),
                event.getToStatus(), StationDict.credentialStatusName(event.getToStatus()),
                event.getOperator(), event.getReason(), event.getCreatedAt());
    }

    private Views.AffectedTripView toAffectedView(DispatchTrip trip, CredentialDoc doc) {
        return new Views.AffectedTripView(trip.getTripNo(), trip.getPlateNo(), trip.getDriverName(),
                trip.getDispatchedAt(), trip.getPlannedReturnAt(),
                doc == null ? "" : StationDict.credentialTypeName(doc.getDocType()),
                doc == null ? null : doc.getVersionNo(),
                Boolean.TRUE.equals(trip.getPendingDispatchFlag()), trip.getPendingReason());
    }

    /** 派车快照拷贝用：把一个生效版本的字段原样固化 */
    public static Views.SnapshotLineView toSnapshotLineView(DispatchCredentialSnapshot s) {
        return new Views.SnapshotLineView(s.getDocId(), s.getOwnerType(), s.getOwnerName(), s.getDocType(),
                StationDict.credentialTypeName(s.getDocType()), s.getDocVersionNo(), s.getDocNumber(),
                s.getLicenseClass(), StationDict.licenseClassName(s.getLicenseClass()), s.getSubjectDesc(),
                s.getValidFrom(), s.getValidUntil(), s.getAttachmentNote(), s.getHandler(), s.getReviewer());
    }
}
