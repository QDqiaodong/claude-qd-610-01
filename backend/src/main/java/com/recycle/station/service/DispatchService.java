package com.recycle.station.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.CredentialDoc;
import com.recycle.station.domain.DispatchCredentialSnapshot;
import com.recycle.station.domain.DispatchTrip;
import com.recycle.station.domain.Driver;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.Vehicle;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.CredentialDocRepository;
import com.recycle.station.repository.DispatchCredentialSnapshotRepository;
import com.recycle.station.repository.DispatchTripRepository;
import com.recycle.station.repository.DriverRepository;
import com.recycle.station.repository.VehicleRepository;

/**
 * 模块七·派车联锁。
 * <p>
 * 车辆 待命→在途 只能走派车确认：选定本次司机和计划回场时间后，在同一事务内
 * 对车辆行、在途出车行、相关生效证照行全部加锁，依次核对——
 * 车辆不在维修、没有未结束出车；司机准驾车型覆盖车辆；司机证照与车辆全部必需证照
 * 不但“今天有效”，而且有效期覆盖到计划回场当日。全部通过才把采用的版本固化成
 * 派车资格快照。资质变更与派车确认撞在同一窗口时，按行锁先后落定，
 * 后提交的一方按“资料版本已变化”重来，绝不出现车已出发却找不到一组当时有效资料。
 */
@Service
public class DispatchService {

    /** 快照里证照有效期至少要覆盖计划回场到当天 23:59:59，比较时用日期粒度 */
    private static final DateTimeFormatter TRIP_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final DispatchTripRepository tripRepository;
    private final DispatchCredentialSnapshotRepository snapshotRepository;
    private final CredentialDocRepository docRepository;
    private final CredentialService credentialService;
    private final VehicleService vehicleService;

    public DispatchService(VehicleRepository vehicleRepository,
                           DriverRepository driverRepository,
                           DispatchTripRepository tripRepository,
                           DispatchCredentialSnapshotRepository snapshotRepository,
                           CredentialDocRepository docRepository,
                           CredentialService credentialService,
                           VehicleService vehicleService) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.tripRepository = tripRepository;
        this.snapshotRepository = snapshotRepository;
        this.docRepository = docRepository;
        this.credentialService = credentialService;
        this.vehicleService = vehicleService;
    }

    /** 车辆当前可派状态：车辆列表直接展示不可派原因（错误码 + 人话） */
    @Transactional(readOnly = true)
    public List<Views.VehicleDispatchView> board() {
        LocalDate today = LocalDate.now();
        List<Views.VehicleDispatchView> result = new ArrayList<>();
        for (Vehicle vehicle : vehicleRepository.findAll()) {
            List<Map<String, Object>> reasons = new ArrayList<>();
            DispatchTrip openTrip = tripRepository
                    .findFirstByVehicleIdAndStatusOrderByIdDesc(vehicle.getId(), "ON_ROAD").orElse(null);

            if ("REPAIR".equals(vehicle.getStatus())) {
                addReason(reasons, BizException.VEHICLE_UNDER_REPAIR, "车辆维修中，禁止派车");
            }
            if (openTrip != null) {
                addReason(reasons, BizException.TRIP_ALREADY_OPEN,
                        "车辆已有未结束出车 " + openTrip.getTripNo() + "（司机 " + openTrip.getDriverName() + "）");
            }
            if (Boolean.TRUE.equals(vehicle.getPendingDispatchFlag())) {
                addReason(reasons, "PENDING_DISPATCH",
                        "待调度处置：" + nullSafe(vehicle.getPendingDispatchReason()));
            }

            // 静态可派才检查默认司机证照口径（车辆列表给出“为什么不可派”的预览）
            String driverName = vehicle.getDriverName();
            Driver driver = driverRepository.findByDriverName(driverName);
            if (driver != null) {
                Map<String, CredentialDoc> driverDocs = collectEffective("DRIVER", driver.getId(),
                        StationDict.DRIVER_REQUIRED_DOCS);
                checkDriverDocs(driverDocs, today, reasons, vehicle);
                checkVehicleDocs(vehicle, today, reasons);
            } else {
                addReason(reasons, "DRIVER_NOT_REGISTERED",
                        "默认司机 " + driverName + " 未在司机花名册登记，派车时请重新选择司机");
            }

            String requiredClass = vehicle.getRequiredLicenseClass();
            boolean dispatchable = reasons.isEmpty() && driver != null;
            Views.VehicleView vehicleView = vehicleService.toView(vehicle);
            result.add(new Views.VehicleDispatchView(vehicleView, requiredClass,
                    StationDict.licenseClassName(requiredClass), dispatchable, reasons,
                    openTrip == null ? null : openTrip.getId(),
                    openTrip == null ? null : openTrip.getTripNo(),
                    openTrip == null ? null : openTrip.getDriverName(),
                    openTrip == null ? null : openTrip.getPlannedReturnAt(),
                    Boolean.TRUE.equals(vehicle.getPendingDispatchFlag()),
                    vehicle.getPendingDispatchReason(), vehicle.getLockVersion()));
        }
        return result;
    }

    /** 派车试算：不落库，返回联锁结论 + 将固化的快照清单 */
    @Transactional(readOnly = true)
    public Views.DispatchCheckView check(Reqs.DispatchCheckReq req) {
        Vehicle vehicle = vehicleRepository.findById(req.vehicleId())
                .orElseThrow(() -> new BizException("车辆不存在"));
        Driver driver = driverRepository.findById(req.driverId())
                .orElseThrow(() -> new BizException("司机不存在"));
        LocalDateTime plannedReturn = parseDateTime(req.plannedReturnAt());
        LocalDate returnDate = plannedReturn.toLocalDate();
        if (!plannedReturn.isAfter(LocalDateTime.now())) {
            throw new BizException("计划回场时间必须晚于当前时间");
        }

        List<Map<String, Object>> reasons = new ArrayList<>();
        List<CredentialDoc> used = evaluateForDispatch(vehicle, driver, returnDate, reasons, false);

        List<Views.SnapshotLineView> wouldFreeze = new ArrayList<>();
        if (reasons.isEmpty()) {
            for (CredentialDoc doc : used) {
                wouldFreeze.add(freezeLine(doc));
            }
        }
        String driverClass = credentialService
                .findActive("DRIVER", driver.getId(), "CRED_DRIVER_LICENSE")
                .map(CredentialDoc::getLicenseClass).orElse(null);
        return new Views.DispatchCheckView(reasons.isEmpty(), reasons, vehicle.getId(), vehicle.getPlateNo(),
                driver.getId(), driver.getDriverName(), driverClass, vehicle.getRequiredLicenseClass(),
                plannedReturn, wouldFreeze);
    }

    /**
     * 派车确认。事务内先锁车辆行，再查并锁在途出车行与全部相关生效证照行：
     * 与复核换版/撤销事务互斥，谁先拿到证照行锁谁先生效，另一方按资料版本变化处理。
     */
    @Transactional
    public Views.TripView dispatch(Reqs.DispatchCreateReq req) {
        Vehicle vehicle = vehicleRepository.lockById(req.vehicleId())
                .orElseThrow(() -> new BizException("车辆不存在"));
        if (req.expectedVehicleVersion() != null
                && !req.expectedVehicleVersion().equals(vehicle.getLockVersion())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "资料版本已变化：车辆状态/证照联锁结果已刷新，请重新打开派车窗口");
        }
        Driver driver = driverRepository.findById(req.driverId())
                .orElseThrow(() -> new BizException("司机不存在"));
        LocalDateTime plannedReturn = parseDateTime(req.plannedReturnAt());
        if (!plannedReturn.isAfter(LocalDateTime.now())) {
            throw new BizException("计划回场时间必须晚于当前时间");
        }

        if ("REPAIR".equals(vehicle.getStatus())) {
            throw new BizException(BizException.VEHICLE_UNDER_REPAIR, "车辆维修中，不能派车");
        }
        List<DispatchTrip> openRows = tripRepository.lockOpenByVehicle(vehicle.getId());
        if (!openRows.isEmpty()) {
            DispatchTrip open = openRows.get(0);
            throw new BizException(BizException.TRIP_ALREADY_OPEN,
                    "车辆已有未结束出车 " + open.getTripNo() + "，请先办理回场");
        }
        if (!"IDLE".equals(vehicle.getStatus())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "资料版本已变化：车辆当前为「" + StationDict.vehicleStatusName(vehicle.getStatus())
                            + "」，请刷新车辆列表");
        }

        LocalDate returnDate = plannedReturn.toLocalDate();
        List<Map<String, Object>> reasons = new ArrayList<>();
        List<CredentialDoc> used = evaluateForDispatch(vehicle, driver, returnDate, reasons, true);
        if (!reasons.isEmpty()) {
            throw firstBizError(reasons);
        }

        LocalDateTime now = LocalDateTime.now();
        DispatchTrip trip = new DispatchTrip();
        trip.setTripNo(nextTripNo(now));
        trip.setVehicleId(vehicle.getId());
        trip.setPlateNo(vehicle.getPlateNo());
        trip.setDriverId(driver.getId());
        trip.setDriverName(driver.getDriverName());
        CredentialDoc license = used.stream()
                .filter(d -> "CRED_DRIVER_LICENSE".equals(d.getDocType())).findFirst().orElseThrow();
        trip.setDriverLicenseClass(license.getLicenseClass());
        trip.setRequiredLicenseClass(vehicle.getRequiredLicenseClass());
        trip.setDispatchedAt(now);
        trip.setPlannedReturnAt(plannedReturn);
        trip.setStatus("ON_ROAD");
        trip.setPendingDispatchFlag(false);
        trip.setDispatcher(req.dispatcher().trim());
        trip.setRemark(req.remark());
        trip.setLockVersion(0L);
        for (CredentialDoc doc : used) {
            trip.getSnapshots().add(buildSnapshot(trip, doc));
        }
        DispatchTrip saved = tripRepository.save(trip);

        vehicle.setStatus("ON_ROAD");
        vehicle.setPendingDispatchFlag(false);
        vehicle.setPendingDispatchReason(null);
        if (vehicle.getTripDate() == null || !LocalDate.now().equals(vehicle.getTripDate())) {
            vehicle.setTripDate(LocalDate.now());
            vehicle.setTodayTrips(0);
        }
        vehicleRepository.save(vehicle);

        return toTripView(saved);
    }

    /** 在途回场：趟次 +1，清待调度处置；历史快照永久保留 */
    @Transactional
    public Views.TripView doReturn(Long tripId, Reqs.DispatchReturnReq req) {
        DispatchTrip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new BizException("出车单不存在"));
        if (!"ON_ROAD".equals(trip.getStatus())) {
            throw new BizException(BizException.VERSION_CONFLICT, "该出车已回场，请勿重复操作");
        }
        LocalDateTime returnedAt = req.actualReturnAt() == null || req.actualReturnAt().isBlank()
                ? LocalDateTime.now() : parseDateTime(req.actualReturnAt());
        trip.setReturnedAt(returnedAt);
        trip.setStatus("RETURNED");
        trip.setPendingDispatchFlag(false);
        DispatchTrip saved = tripRepository.save(trip);

        Vehicle vehicle = vehicleRepository.lockById(trip.getVehicleId())
                .orElseThrow(() -> new BizException("车辆不存在"));
        vehicle.setStatus("IDLE");
        vehicle.setPendingDispatchFlag(false);
        vehicle.setPendingDispatchReason(null);
        if (vehicle.getTripDate() == null || !LocalDate.now().equals(vehicle.getTripDate())) {
            vehicle.setTripDate(LocalDate.now());
            vehicle.setTodayTrips(0);
        }
        vehicle.setTodayTrips(vehicle.getTodayTrips() + 1);
        vehicleRepository.save(vehicle);
        return toTripView(saved);
    }

    @Transactional(readOnly = true)
    public Views.TripView tripDetail(Long tripId) {
        return toTripView(tripRepository.findById(tripId)
                .orElseThrow(() -> new BizException("出车单不存在")));
    }

    @Transactional(readOnly = true)
    public List<Views.TripView> recentTrips() {
        return tripRepository.findTop20ByOrderByDispatchedAtDesc().stream().map(this::toTripView).toList();
    }

    @Transactional(readOnly = true)
    public List<Views.TripView> onRoadTrips() {
        return tripRepository.findByStatusOrderByDispatchedAtDesc("ON_ROAD").stream()
                .map(this::toTripView).toList();
    }

    // ================= 联锁核验（共用口径） =================

    /**
     * 核对并返回本次将采用的证照版本清单。
     * 错误区分：资料仍待复核（无生效版但有草稿/待复核）/ 证照已过期 /
     * 准驾车型不匹配 / 计划回场晚于有效期 / 车辆已有未结束出车（由调用方先拦）。
     *
     * @param lock true=派车确认事务，对生效证照行加写锁与资质变更排队
     */
    private List<CredentialDoc> evaluateForDispatch(Vehicle vehicle, Driver driver, LocalDate returnDate,
                                                    List<Map<String, Object>> reasons, boolean lock) {
        List<CredentialDoc> used = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // ---- 司机证照 ----
        Map<String, CredentialDoc> driverDocs = new LinkedHashMap<>();
        for (String docType : StationDict.DRIVER_REQUIRED_DOCS) {
            Optional<CredentialDoc> active = lock
                    ? credentialService.lockActiveForDispatch("DRIVER", driver.getId(), docType)
                    : credentialService.findActive("DRIVER", driver.getId(), docType);
            CredentialDoc doc = active.orElse(null);
            if (doc == null) {
                addMissingReason(reasons, "司机 " + driver.getDriverName(), "DRIVER", driver.getId(), docType);
            } else {
                driverDocs.put(docType, doc);
                used.add(doc);
            }
        }

        // 准驾车型（取生效驾驶证版本；缺证已记原因）
        CredentialDoc license = driverDocs.get("CRED_DRIVER_LICENSE");
        if (license != null) {
            if (license.getValidUntil().isBefore(today)) {
                addReason(reasons, BizException.CRED_EXPIRED,
                        "司机驾驶证已于 " + license.getValidUntil() + " 过期");
            } else {
                if (!StationDict.licenseCovers(license.getLicenseClass(), vehicle.getRequiredLicenseClass())) {
                    addReason(reasons, BizException.LICENSE_CLASS_MISMATCH,
                            "准驾车型不匹配：司机驾驶证为 " + StationDict.licenseClassName(license.getLicenseClass())
                                    + "，车辆要求 " + StationDict.licenseClassName(vehicle.getRequiredLicenseClass()));
                }
                if (license.getValidUntil().isBefore(returnDate)) {
                    addReason(reasons, BizException.RETURN_BEYOND_VALID_UNTIL,
                            "计划回场晚于驾驶证有效期：驾驶证仅有效至 " + license.getValidUntil());
                }
            }
        }
        CredentialDoc qualification = driverDocs.get("CRED_QUALIFICATION");
        if (qualification != null) {
            checkDateReasons(qualification, "司机从业资格证", today, returnDate, reasons);
        }

        // ---- 车辆全部必需证照 ----
        for (String docType : StationDict.VEHICLE_REQUIRED_DOCS) {
            Optional<CredentialDoc> active = lock
                    ? credentialService.lockActiveForDispatch("VEHICLE", vehicle.getId(), docType)
                    : credentialService.findActive("VEHICLE", vehicle.getId(), docType);
            CredentialDoc doc = active.orElse(null);
            if (doc == null) {
                addMissingReason(reasons, "车辆 " + vehicle.getPlateNo(), "VEHICLE", vehicle.getId(), docType);
            } else {
                checkDateReasons(doc, "车辆「" + StationDict.credentialTypeName(docType) + "」",
                        today, returnDate, reasons);
                used.add(doc);
            }
        }
        return used;
    }

    private void checkDateReasons(CredentialDoc doc, String label, LocalDate today, LocalDate returnDate,
                                  List<Map<String, Object>> reasons) {
        if (doc.getValidUntil().isBefore(today)) {
            addReason(reasons, BizException.CRED_EXPIRED,
                    label + "已于 " + doc.getValidUntil() + " 过期");
            return;
        }
        if (doc.getValidUntil().isBefore(returnDate)) {
            addReason(reasons, BizException.RETURN_BEYOND_VALID_UNTIL,
                    label + "仅有效至 " + doc.getValidUntil() + "，覆盖不到计划回场日 " + returnDate);
        }
    }

    /** 无生效版本时区分“仍待复核 / 草稿 / 根本没录”，错误码归到 CRED_PENDING_REVIEW */
    private void addMissingReason(List<Map<String, Object>> reasons, String who, String ownerType,
                                  Long ownerId, String docType) {
        String typeName = StationDict.credentialTypeName(docType);
        List<CredentialDoc> all = docRepository
                .findByOwnerTypeAndOwnerIdOrderByDocTypeAscVersionNoAsc(ownerType, ownerId).stream()
                .filter(d -> d.getDocType().equals(docType)).toList();
        String detail;
        if (all.isEmpty()) {
            detail = who + "的" + typeName + "尚未登记任何资料，请到证照联锁台录入并复核";
        } else {
            CredentialDoc latest = all.get(all.size() - 1);
            String state = StationDict.credentialStatusName(latest.getStatus());
            detail = who + "的" + typeName + "资料仍待复核（最新 V" + latest.getVersionNo() + "：" + state
                    + "），暂无生效版本可作为派车依据";
        }
        addReason(reasons, BizException.CRED_PENDING_REVIEW, detail);
    }

    private Map<String, CredentialDoc> collectEffective(String ownerType, Long ownerId, List<String> types) {
        Map<String, CredentialDoc> map = new LinkedHashMap<>();
        for (String type : types) {
            credentialService.findActive(ownerType, ownerId, type).ifPresent(d -> map.put(type, d));
        }
        return map;
    }

    /** 车辆列表预览用：司机证照原因（返回生效驾驶证准驾车型，缺则 null） */
    private String checkDriverDocs(Map<String, CredentialDoc> driverDocs, LocalDate today,
                                   List<Map<String, Object>> reasons, Vehicle vehicle) {
        CredentialDoc license = driverDocs.get("CRED_DRIVER_LICENSE");
        if (license == null) {
            return null;
        }
        if (license.getValidUntil().isBefore(today)) {
            addReason(reasons, BizException.CRED_EXPIRED,
                    "默认司机驾驶证已于 " + license.getValidUntil() + " 过期");
            return license.getLicenseClass();
        }
        if (!StationDict.licenseCovers(license.getLicenseClass(), vehicle.getRequiredLicenseClass())) {
            addReason(reasons, BizException.LICENSE_CLASS_MISMATCH,
                    "准驾车型不匹配：默认司机驾驶证为 "
                            + StationDict.licenseClassName(license.getLicenseClass())
                            + "，车辆要求 " + StationDict.licenseClassName(vehicle.getRequiredLicenseClass()));
        }
        return license.getLicenseClass();
    }

    /** 车辆列表预览用：车辆证照原因（仅判今天是否有效，不判计划回场——回场在派车时才填） */
    private void checkVehicleDocs(Vehicle vehicle, LocalDate today, List<Map<String, Object>> reasons) {
        for (String docType : StationDict.VEHICLE_REQUIRED_DOCS) {
            CredentialDoc doc = credentialService
                    .findActive("VEHICLE", vehicle.getId(), docType).orElse(null);
            if (doc == null) {
                addMissingReason(reasons, "车辆 " + vehicle.getPlateNo(), "VEHICLE", vehicle.getId(), docType);
            } else if (doc.getValidUntil().isBefore(today)) {
                addReason(reasons, BizException.CRED_EXPIRED,
                        "车辆「" + StationDict.credentialTypeName(docType) + "」已于 "
                                + doc.getValidUntil() + " 过期");
            }
        }
    }

    // ================= 快照 / 编号 / 转换 =================

    private DispatchCredentialSnapshot buildSnapshot(DispatchTrip trip, CredentialDoc doc) {
        DispatchCredentialSnapshot snapshot = new DispatchCredentialSnapshot();
        snapshot.setTrip(trip);
        snapshot.setOwnerType(doc.getOwnerType());
        snapshot.setOwnerId(doc.getOwnerId());
        snapshot.setOwnerName(doc.getOwnerName());
        snapshot.setDocType(doc.getDocType());
        snapshot.setDocId(doc.getId());
        snapshot.setDocVersionNo(doc.getVersionNo());
        snapshot.setDocNumber(doc.getDocNumber());
        snapshot.setLicenseClass(doc.getLicenseClass());
        snapshot.setSubjectDesc(doc.getSubjectDesc());
        snapshot.setValidFrom(doc.getValidFrom());
        snapshot.setValidUntil(doc.getValidUntil());
        snapshot.setAttachmentNote(doc.getAttachmentNote());
        snapshot.setDocStatusAtDispatch("EFFECTIVE");
        snapshot.setHandler(doc.getHandler());
        snapshot.setReviewer(doc.getReviewer());
        return snapshot;
    }

    private Views.SnapshotLineView freezeLine(CredentialDoc doc) {
        return new Views.SnapshotLineView(doc.getId(), doc.getOwnerType(), doc.getOwnerName(), doc.getDocType(),
                StationDict.credentialTypeName(doc.getDocType()), doc.getVersionNo(), doc.getDocNumber(),
                doc.getLicenseClass(), StationDict.licenseClassName(doc.getLicenseClass()), doc.getSubjectDesc(),
                doc.getValidFrom(), doc.getValidUntil(), doc.getAttachmentNote(),
                doc.getHandler(), doc.getReviewer());
    }

    private String nextTripNo(LocalDateTime now) {
        String prefix = "PC" + now.format(TRIP_NO_DATE);
        String max = tripRepository.findMaxTripNo(prefix + "%");
        int seq = 1;
        if (max != null && max.length() >= prefix.length()) {
            try {
                seq = Integer.parseInt(max.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                // 落到 1
            }
        }
        return prefix + String.format("%03d", seq);
    }

    private LocalDateTime parseDateTime(String text) {
        String value = text.trim().replace(' ', 'T');
        try {
            return value.length() <= 16 ? LocalDateTime.parse(value + ":00") : LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BizException("时间格式应为 yyyy-MM-dd HH:mm");
        }
    }

    private void addReason(List<Map<String, Object>> reasons, String code, String message) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("code", code);
        reason.put("message", message);
        reasons.add(reason);
    }

    private BizException firstBizError(List<Map<String, Object>> reasons) {
        Map<String, Object> first = reasons.get(0);
        return new BizException((String) first.get("code"), (String) first.get("message"));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private Views.TripView toTripView(DispatchTrip trip) {
        List<Views.SnapshotLineView> lines = snapshotRepository
                .findByTripIdOrderByOwnerTypeAscDocTypeAsc(trip.getId())
                .stream().map(CredentialService::toSnapshotLineView).toList();
        return new Views.TripView(trip.getId(), trip.getTripNo(), trip.getVehicleId(), trip.getPlateNo(),
                trip.getDriverId(), trip.getDriverName(), trip.getDriverLicenseClass(),
                StationDict.licenseClassName(trip.getDriverLicenseClass()),
                trip.getRequiredLicenseClass(), StationDict.licenseClassName(trip.getRequiredLicenseClass()),
                trip.getDispatchedAt(), trip.getPlannedReturnAt(), trip.getReturnedAt(),
                trip.getStatus(), StationDict.tripStatusName(trip.getStatus()),
                Boolean.TRUE.equals(trip.getPendingDispatchFlag()), trip.getPendingReason(),
                trip.getDispatcher(), trip.getRemark(), lines);
    }
}
