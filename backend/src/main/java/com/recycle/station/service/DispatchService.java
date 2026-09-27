package com.recycle.station.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.CertDocument;
import com.recycle.station.domain.DispatchTrip;
import com.recycle.station.domain.DispatchTripCert;
import com.recycle.station.domain.Driver;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.Vehicle;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.DispatchTripCertRepository;
import com.recycle.station.repository.DispatchTripRepository;
import com.recycle.station.repository.DriverRepository;
import com.recycle.station.repository.VehicleRepository;

/**
 * 模块七派车：待命 → 在途必须选定本次司机与计划回场时间，
 * 通过全部资格核对后把采用的资料版本固化成派车资格快照。
 * <p>
 * 与资质变更的先后：派车事务在最前面抢证照联锁门（{@code CertService#lockInterlockAndSweep}），
 * 复核生效 / 撤销 / 回场也抢同一把锁。因此同一时间窗口内两笔操作必有明确先后：
 * 资质变更先提交，则派车按新状态重新核对（可能拦下）；派车先提交，则撤销只能看到已固化快照，
 * 历史出车不受影响，只触发待调度处置。
 */
@Service
public class DispatchService {

    private final DispatchTripRepository tripRepository;
    private final DispatchTripCertRepository tripCertRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final CertService certService;

    public DispatchService(DispatchTripRepository tripRepository,
                           DispatchTripCertRepository tripCertRepository,
                           VehicleRepository vehicleRepository,
                           DriverRepository driverRepository,
                           CertService certService) {
        this.tripRepository = tripRepository;
        this.tripCertRepository = tripCertRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.certService = certService;
    }

    public static String tripStatusName(String status) {
        return switch (status) {
            case DispatchTrip.OPEN -> "在途";
            case DispatchTrip.FINISHED -> "已回场";
            case DispatchTrip.PENDING_DISPOSAL -> "待调度处置";
            default -> status;
        };
    }

    // ================================================================
    // 派车预核对（不落数据，给派车弹窗实时反馈 + 展示将固化的快照清单）
    // ================================================================

    @Transactional
    public Views.DispatchCheckView precheck(Reqs.DispatchCheckReq req) {
        // 预核对也在联锁门内做：与复核生效/撤销排队，且先把当天到期状态补齐，
        // 保证调度员看到的结论与随后点"确认派车"完全同口径。只核对不落数据。
        certService.lockInterlockAndSweep();
        Vehicle vehicle = vehicleRepository.findById(req.vehicleId())
                .orElseThrow(() -> new BizException("车辆不存在"));
        Driver driver = driverRepository.findById(req.driverId())
                .orElseThrow(() -> new BizException("司机不存在或已删除"));
        LocalDateTime plannedReturn = CertService.parseDateTime(req.plannedReturnAt(), "计划回场时间");
        List<Map<String, Object>> blocks = new ArrayList<>();
        if ("REPAIR".equals(vehicle.getStatus())) {
            blocks.add(block(BizException.BIZ_INVALID, "车辆正在维修，不能派车，请先改回待命"));
        }
        if ("ON_ROAD".equals(vehicle.getStatus())) {
            DispatchTrip open = tripRepository.findOpenByVehicleId(vehicle.getId()).orElse(null);
            if (open != null) {
                blocks.add(block(BizException.TRIP_OPEN_EXISTS,
                        "车辆已有未结束出车：" + open.getTripNo() + "（" + tripStatusName(open.getStatus())
                                + "，司机 " + open.getDriverName() + "），请先回场"));
            }
        }
        if (plannedReturn.isBefore(LocalDateTime.now())) {
            blocks.add(block(BizException.BIZ_INVALID, "计划回场时间不能早于当前时间"));
        }

        CertService.QualificationResult qualification = null;
        if (plannedReturn.isAfter(LocalDateTime.now()) && !"REPAIR".equals(vehicle.getStatus())) {
            qualification = certService.resolveQualification(vehicle.getId(), driver.getId(), plannedReturn);
            if (!qualification.pass()) {
                for (CertService.Issue issue : qualification.issues()) {
                    blocks.add(block(issue.code(), issue.message()));
                }
            }
        }

        return new Views.DispatchCheckView(blocks.isEmpty(), blocks,
                vehicle.getId(), vehicle.getPlateNo(), driver.getId(), driver.getDriverName(),
                qualification == null ? StationDict.requiredLicenseClass(vehicle.getLoadCapacity())
                        : qualification.requiredLicenseClass(),
                qualification == null ? null : qualification.driverLicenseClass(),
                plannedReturn,
                qualification == null ? List.of() : qualification.snapshot());
    }

    // ================================================================
    // 派车确认：待命 → 在途 + 固化资格快照
    // ================================================================

    @Transactional
    public Views.DispatchTripView dispatch(Reqs.DispatchReq req) {
        LocalDateTime plannedReturn = CertService.parseDateTime(req.plannedReturnAt(), "计划回场时间");
        if (plannedReturn.isBefore(LocalDateTime.now())) {
            throw new BizException("计划回场时间不能早于当前时间");
        }

        // 第一把锁：联锁门（顺带做到期扫描）；之后车辆行锁 → 版本链行锁，全局统一顺序，杜绝死锁
        certService.lockInterlockAndSweep();
        Vehicle vehicle = vehicleRepository.lockById(req.vehicleId());
        Driver driver = driverRepository.findById(req.driverId())
                .orElseThrow(() -> new BizException("司机不存在或已删除"));

        if (req.expectedVehicleVersion() != null
                && !req.expectedVehicleVersion().equals(vehicle.getLockVersion())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：车辆 " + vehicle.getPlateNo() + " 的状态刚被其他人更新，请刷新车辆列表再派车");
        }

        if ("REPAIR".equals(vehicle.getStatus())) {
            throw new BizException(BizException.BIZ_INVALID, "车辆正在维修，不能派车，请先改回待命");
        }
        if (!"IDLE".equals(vehicle.getStatus())) {
            // ON_ROAD 但列表没刷出来：以未结束出车为准给出专属错误
            DispatchTrip open = tripRepository.findOpenByVehicleId(vehicle.getId()).orElse(null);
            if (open != null) {
                throw new BizException(BizException.TRIP_OPEN_EXISTS,
                        "车辆已有未结束出车：" + open.getTripNo() + "（" + tripStatusName(open.getStatus())
                                + "，司机 " + open.getDriverName() + "），必须先回场才能再次派车");
            }
            throw new BizException(BizException.BIZ_INVALID,
                    "车辆当前是「" + StationDict.vehicleStatusName(vehicle.getStatus()) + "」，只有待命车辆可以派车");
        }
        // 锁内二次确认未结束出车（uk_trip_open 是最后防线）
        if (tripRepository.lockOpenByVehicleId(vehicle.getId()).stream().findAny().isPresent()) {
            throw new BizException(BizException.TRIP_OPEN_EXISTS, "车辆已有未结束出车，请先回场后再派车");
        }
        if (!"ACTIVE".equals(driver.getStatus())) {
            throw new BizException(BizException.BIZ_INVALID,
                    "司机 " + driver.getDriverName() + " 当前不是在岗状态，不能安排出车");
        }

        // 资格核对：准驾覆盖 + 司机证照 + 车辆全部必需证照，持续有效到计划回场当天
        CertService.QualificationResult qualification =
                certService.resolveQualification(vehicle.getId(), driver.getId(), plannedReturn);
        if (!qualification.pass()) {
            // 前端会拿到第一个阻断原因（错误码可区分五类情况），全部原因在派车弹窗的预核对里可见
            CertService.Issue first = qualification.issues().get(0);
            throw new BizException(first.code(), first.message());
        }

        LocalDateTime now = LocalDateTime.now();
        DispatchTrip trip = new DispatchTrip();
        trip.setTripNo(nextTripNo(now));
        trip.setVehicleId(vehicle.getId());
        trip.setPlateNo(vehicle.getPlateNo());
        trip.setDriverId(driver.getId());
        trip.setDriverName(driver.getDriverName());
        trip.setDispatchOperator(req.operator().trim());
        trip.setPlannedReturnAt(plannedReturn);
        trip.setDispatchedAt(now);
        trip.setStatus(DispatchTrip.OPEN);
        trip = tripRepository.save(trip);

        // 固化派车资格快照：只认核对通过的当前生效版本，逐份原样拷贝，之后换版/撤销/到期都不改
        for (Views.TripCertView certView : qualification.snapshot()) {
            CertDocument doc = certService.findEffectiveOfSeries(certView.seriesId())
                    .orElseThrow(() -> new BizException(BizException.CERT_VERSION_CHANGED,
                            "资料版本已变化：固化快照时有证照刚刚失效，请重新核对后派车"));
            DispatchTripCert snapshot = new DispatchTripCert();
            snapshot.setTrip(trip);
            snapshot.setSeriesId(doc.getSeriesId());
            snapshot.setDocumentId(doc.getId());
            snapshot.setSubjectType(doc.getSubjectType());
            snapshot.setSubjectName(doc.getSubjectName());
            snapshot.setCertType(doc.getCertType());
            snapshot.setCertNo(doc.getCertNo());
            snapshot.setLicenseClass(doc.getLicenseClass());
            snapshot.setValidFrom(doc.getValidFrom());
            snapshot.setValidTo(doc.getValidTo());
            snapshot.setVersionNo(doc.getVersionNo());
            snapshot.setStatus("EFFECTIVE");
            trip.getCerts().add(snapshot);
        }
        tripRepository.save(trip);

        vehicle.setStatus("ON_ROAD");
        if (vehicle.getTripDate() == null || !java.time.LocalDate.now().equals(vehicle.getTripDate())) {
            vehicle.setTripDate(java.time.LocalDate.now());
            vehicle.setTodayTrips(0);
        }
        vehicleRepository.save(vehicle);

        return toTripView(trip);
    }

    // ================================================================
    // 回场 + 待调度处置
    // ================================================================

    /** 在途（含待调度处置）回场：车辆回待命，今日趟次 +1 */
    @Transactional
    public Views.DispatchTripView finishTrip(Reqs.ReturnReq req) {
        certService.lockInterlockAndSweep();
        DispatchTrip trip = tripRepository.findById(req.tripId())
                .orElseThrow(() -> new BizException("出车单不存在"));
        Vehicle vehicle = vehicleRepository.lockById(trip.getVehicleId());
        // 锁内重读，防止处置 / 回场并发
        trip = tripRepository.findById(req.tripId()).orElseThrow();
        if (DispatchTrip.FINISHED.equals(trip.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：出车单 " + trip.getTripNo() + " 已回场，请勿重复操作");
        }
        LocalDateTime now = LocalDateTime.now();
        trip.setStatus(DispatchTrip.FINISHED);
        trip.setReturnedAt(now);
        if (trip.getDispositionAt() == null) {
            trip.setDispositionNote(trip.getDispositionNote() == null
                    ? "回场时一并结束待调度处置" : trip.getDispositionNote() + "；回场结束");
            trip.setDispositionAt(now);
        }
        tripRepository.save(trip);

        vehicle.setStatus("IDLE");
        if (vehicle.getTripDate() == null || !java.time.LocalDate.now().equals(vehicle.getTripDate())) {
            vehicle.setTripDate(java.time.LocalDate.now());
            vehicle.setTodayTrips(0);
        }
        vehicle.setTodayTrips(vehicle.getTodayTrips() + 1);
        vehicleRepository.save(vehicle);
        return toTripView(trip);
    }

    /** 待调度处置：调度员对被撤销证照波及的在途车登记处置说明（召回 / 换车 / 就近回场等） */
    @Transactional
    public Views.DispatchTripView dispose(Reqs.DispositionReq req) {
        certService.lockInterlockAndSweep();
        DispatchTrip trip = tripRepository.findById(req.tripId())
                .orElseThrow(() -> new BizException("出车单不存在"));
        if (!DispatchTrip.PENDING_DISPOSAL.equals(trip.getStatus())) {
            throw new BizException(BizException.CERT_VERSION_CHANGED,
                    "资料版本已变化：出车单 " + trip.getTripNo() + " 当前不是待调度处置状态，请刷新后再操作");
        }
        String note = req.note().trim();
        if (note.isEmpty()) {
            throw new BizException("处置说明必填");
        }
        if (note.length() > 200) {
            throw new BizException("处置说明不能超过 200 字");
        }
        trip.setDispositionNote((trip.getDispositionNote() == null ? "" : trip.getDispositionNote() + "；")
                + req.operator().trim() + "：" + note);
        trip.setDispositionAt(LocalDateTime.now());
        tripRepository.save(trip);
        return toTripView(trip);
    }

    // ================================================================
    // 查询
    // ================================================================

    @Transactional(readOnly = true)
    public List<Views.DispatchTripView> list(String status) {
        List<DispatchTrip> trips = status == null || status.isBlank()
                ? tripRepository.findTop50ByOrderByDispatchedAtDesc()
                : tripRepository.findByStatusOrderByDispatchedAtDesc(status.trim().toUpperCase());
        List<Views.DispatchTripView> views = new ArrayList<>();
        for (DispatchTrip trip : trips) {
            views.add(toTripView(trip));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public Views.DispatchTripView detail(Long tripId) {
        DispatchTrip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new BizException("出车单不存在"));
        return toTripView(trip);
    }

    @Transactional(readOnly = true)
    public List<Views.DispatchTripView> vehicleTrips(Long vehicleId) {
        List<Views.DispatchTripView> views = new ArrayList<>();
        for (DispatchTrip trip : tripRepository.findByVehicleIdOrderByDispatchedAtDesc(vehicleId)) {
            views.add(toTripView(trip));
        }
        return views;
    }

    private Views.DispatchTripView toTripView(DispatchTrip trip) {
        List<Views.TripCertView> certs = new ArrayList<>();
        for (DispatchTripCert c : tripCertRepository.findByTripIdOrderByIdAsc(trip.getId())) {
            certs.add(new Views.TripCertView(c.getId(), c.getSeriesId(), c.getDocumentId(),
                    c.getSubjectType(), StationDict.certSubjectName(c.getSubjectType()),
                    c.getSubjectName(), c.getCertType(), StationDict.certTypeName(c.getCertType()),
                    c.getCertNo(), c.getLicenseClass(), StationDict.licenseClassName(c.getLicenseClass()),
                    c.getValidFrom(), c.getValidTo(), c.getVersionNo(),
                    c.getStatus(), StationDict.certStatusName(c.getStatus())));
        }
        return new Views.DispatchTripView(trip.getId(), trip.getTripNo(),
                trip.getVehicleId(), trip.getPlateNo(), trip.getDriverId(), trip.getDriverName(),
                trip.getDispatchOperator(), trip.getPlannedReturnAt(), trip.getDispatchedAt(),
                trip.getReturnedAt(), trip.getStatus(), tripStatusName(trip.getStatus()),
                trip.getDisposalReason(), trip.getDispositionNote(), trip.getDispositionAt(),
                trip.getLockVersion(), certs);
    }

    /** PC + yyyyMMdd + 三位序号（以当日已有出车数为序号基准，撞号顺延） */
    private String nextTripNo(LocalDateTime now) {
        String prefix = "PC" + now.format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = tripRepository.countByTripNoStartingWith(prefix) + 1;
        String candidate = prefix + String.format("%03d", seq);
        while (tripRepository.existsByTripNo(candidate)) {
            seq += 1;
            candidate = prefix + String.format("%03d", seq);
        }
        return candidate;
    }

    private Map<String, Object> block(String code, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
