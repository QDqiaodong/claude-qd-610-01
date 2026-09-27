package com.recycle.station.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.DispatchTrip;
import com.recycle.station.domain.Driver;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.Vehicle;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.DispatchTripRepository;
import com.recycle.station.repository.DriverRepository;
import com.recycle.station.repository.VehicleRepository;

/**
 * 模块三 + 模块七：收运车辆与司机调度。
 * <p>
 * 状态机：IDLE 待命 →（派车，带司机 + 计划回场 + 资格快照）→ ON_ROAD 在途 → 回场（IDLE，趟次 +1）；
 * 待命与维修之间可手工切换，在途车必须先回场。
 * <p>
 * 车辆列表直接给出"当前可派状态 + 不可派原因"：维修、未结束出车、默认司机证照待复核 / 过期 /
 * 准驾不匹配 / 近期到期都会逐行列明，调度员不用点进派车弹窗就能看出这车为什么不能派。
 * 派车资格的权威核对在 {@link DispatchService}（必须带计划回场时间），列表只做"以默认司机 +
 * 今天为口径"的预检。
 */
@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final DispatchTripRepository tripRepository;
    private final CertService certService;

    public VehicleService(VehicleRepository vehicleRepository,
                          DriverRepository driverRepository,
                          DispatchTripRepository tripRepository,
                          CertService certService) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.tripRepository = tripRepository;
        this.certService = certService;
    }

    @Transactional
    public List<Views.VehicleView> list() {
        // 任何一次打开列表都先把到期状态补齐：跨日 / 重启后看到的证照状态与到期判断一致
        certService.sweepExpired();
        LocalDate today = LocalDate.now();
        List<Views.VehicleView> rows = new ArrayList<>();
        List<Vehicle> touched = new ArrayList<>();
        for (Vehicle vehicle : vehicleRepository.findAll()) {
            if (vehicle.getTripDate() == null || !today.equals(vehicle.getTripDate())) {
                vehicle.setTripDate(today);
                vehicle.setTodayTrips(0);
                touched.add(vehicle);
            }
            rows.add(toView(vehicle));
        }
        if (!touched.isEmpty()) {
            vehicleRepository.saveAll(touched);
        }
        rows.sort((a, b) -> {
            int byStatus = rank(a.status()).compareTo(rank(b.status()));
            return byStatus != 0 ? byStatus : b.todayTrips().compareTo(a.todayTrips());
        });
        return rows;
    }

    @Transactional
    public Views.VehicleView create(Reqs.VehicleSaveReq req) {
        if (vehicleRepository.findByPlateNo(req.plateNo().trim()) != null) {
            throw new BizException("车牌 " + req.plateNo() + " 已登记过");
        }
        if (req.loadCapacity() == null || req.loadCapacity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("额定载重必须大于 0 吨");
        }
        String status = req.status() == null || req.status().isBlank() ? "IDLE" : req.status().trim();
        if (!StationDict.isValidVehicleStatus(status)) {
            throw new BizException("车辆状态只能是 IDLE / ON_ROAD / REPAIR");
        }
        Long defaultDriverId = null;
        if (req.defaultDriverId() != null) {
            Driver driver = driverRepository.findById(req.defaultDriverId())
                    .orElseThrow(() -> new BizException("默认司机不存在，请先在证照联锁台建档"));
            defaultDriverId = driver.getId();
        }
        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo(req.plateNo().trim());
        vehicle.setDriverName(req.driverName().trim());
        vehicle.setPhone(req.phone());
        vehicle.setLoadCapacity(req.loadCapacity());
        // 登记时不允许直接落在途：在途必须走派车确认（带司机 + 计划回场 + 资格快照）
        vehicle.setStatus("REPAIR".equals(status) ? "REPAIR" : "IDLE");
        vehicle.setTripDate(LocalDate.now());
        vehicle.setTodayTrips(0);
        vehicle.setRemark(req.remark());
        vehicle.setDefaultDriverId(defaultDriverId);
        return toView(vehicleRepository.save(vehicle));
    }

    /**
     * 手工状态切换：只允许 待命⇄维修。
     * 待命→在途必须走派车确认（{@link DispatchService#dispatch}），在途→待命必须走回场，
     * 避免绕过准驾 / 有效期核对与派车资格快照。
     */
    @Transactional
    public Views.VehicleView changeStatus(Long id, String target) {
        certService.lockInterlockAndSweep();
        Vehicle vehicle = vehicleRepository.lockById(id);
        if (!StationDict.isValidVehicleStatus(target)) {
            throw new BizException("车辆状态只能是 IDLE / ON_ROAD / REPAIR");
        }
        String current = vehicle.getStatus();
        if (current.equals(target)) {
            return toView(vehicle);
        }
        if ("ON_ROAD".equals(target)) {
            throw new BizException("待命改在途必须走【派车】：请选定本次司机与计划回场时间，系统核对证照后固化派车资格快照");
        }
        if ("ON_ROAD".equals(current) && ("IDLE".equals(target) || "REPAIR".equals(target))) {
            DispatchTrip open = tripRepository.findOpenByVehicleId(vehicle.getId()).orElse(null);
            if (open != null) {
                throw new BizException(BizException.TRIP_OPEN_EXISTS,
                        "车辆已有未结束出车：" + open.getTripNo() + "，请先在车辆卡片上办理回场");
            }
        }
        if ("REPAIR".equals(current) && "IDLE".equals(target)) {
            vehicle.setStatus("IDLE");
        } else if ("IDLE".equals(current) && "REPAIR".equals(target)) {
            vehicle.setStatus("REPAIR");
        } else {
            throw new BizException("不允许从「" + StationDict.vehicleStatusName(current)
                    + "」直接改为「" + StationDict.vehicleStatusName(target) + "」");
        }
        return toView(vehicleRepository.save(vehicle));
    }

    private Integer rank(String status) {
        int index = StationDict.vehicleStatusOrder().indexOf(status);
        return index < 0 ? 9 : index;
    }

    public Views.VehicleView toView(Vehicle vehicle) {
        return toView(vehicle, null);
    }

    /**
     * 车辆视图附"当前可派状态"。预检口径：默认司机 + 计划回场 = 今天 18:00。
     * 真正派车时调度员选的司机 / 计划回场可能不同，权威结论以派车预核对接口为准。
     */
    public Views.VehicleView toView(Vehicle vehicle, DispatchTrip forcedOpenTrip) {
        List<Map<String, String>> options = new ArrayList<>();
        for (String status : StationDict.vehicleStatusOrder()) {
            options.add(Map.of("value", status, "label", StationDict.vehicleStatusName(status)));
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        DispatchTrip openTrip = forcedOpenTrip != null ? forcedOpenTrip
                : tripRepository.findOpenByVehicleId(vehicle.getId()).orElse(null);

        String requiredClass = StationDict.requiredLicenseClass(vehicle.getLoadCapacity());
        boolean dispatchable = "IDLE".equals(vehicle.getStatus());

        if ("REPAIR".equals(vehicle.getStatus())) {
            dispatchable = false;
            blocks.add(Map.of("code", BizException.BIZ_INVALID, "message", "车辆正在维修"));
        }
        if (openTrip != null) {
            dispatchable = false;
            blocks.add(Map.of("code", BizException.TRIP_OPEN_EXISTS, "message",
                    DispatchTrip.PENDING_DISPOSAL.equals(openTrip.getStatus())
                            ? "在途车被标记待调度处置（" + openTrip.getTripNo() + "），请先处置并回场"
                            : "车辆已有未结束出车 " + openTrip.getTripNo() + "，回场后才能再派"));
        }

        if (vehicle.getDefaultDriverId() == null) {
            // 没有默认司机不阻断派车（派车弹窗可另选司机），仅作提示（warning=true 前端不标红）
            blocks.add(Map.of("code", BizException.BIZ_INVALID, "message", "未设置默认司机，派车时请选择本次司机",
                    "warning", "true"));
        } else {
            Driver defaultDriver = driverRepository.findById(vehicle.getDefaultDriverId()).orElse(null);
            if (defaultDriver == null) {
                blocks.add(Map.of("code", BizException.BIZ_INVALID, "message", "默认司机已不存在，请重新指定"));
                dispatchable = false;
            } else {
                CertService.QualificationResult result = certService.resolveQualification(
                        vehicle.getId(), defaultDriver.getId(),
                        java.time.LocalDateTime.now().toLocalDate().atTime(18, 0));
                if (!result.pass()) {
                    dispatchable = false;
                    for (CertService.Issue issue : result.issues()) {
                        blocks.add(issue.toMap());
                    }
                }
            }
        }

        return new Views.VehicleView(vehicle.getId(), vehicle.getPlateNo(), vehicle.getDriverName(),
                vehicle.getPhone(), vehicle.getLoadCapacity(), vehicle.getStatus(),
                StationDict.vehicleStatusName(vehicle.getStatus()), vehicle.getTripDate(),
                vehicle.getTodayTrips(), vehicle.getRemark(), options,
                vehicle.getDefaultDriverId(), requiredClass,
                StationDict.licenseClassName(requiredClass),
                dispatchable, blocks,
                openTrip == null ? null : openTrip.getId(),
                openTrip == null ? null : openTrip.getStatus(),
                openTrip == null ? null : DispatchService.tripStatusName(openTrip.getStatus()),
                vehicle.getLockVersion());
    }
}
