package com.recycle.station.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.Vehicle;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.VehicleRepository;

/**
 * 模块三：收运车辆与司机调度。
 * <p>
 * 状态机：IDLE 待命 → ON_ROAD 在途 → 回场（切回 IDLE，今日趟次 +1）；任意状态可进 REPAIR 维修。
 * 今日趟次每天首次读到旧日期的 trip_date 时自动归零重算。
 */
@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    public VehicleService(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Transactional
    public List<Views.VehicleView> list() {
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
        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo(req.plateNo().trim());
        vehicle.setDriverName(req.driverName().trim());
        vehicle.setPhone(req.phone());
        vehicle.setLoadCapacity(req.loadCapacity());
        vehicle.setStatus(status);
        vehicle.setTripDate(LocalDate.now());
        vehicle.setTodayTrips(0);
        vehicle.setRemark(req.remark());
        return toView(vehicleRepository.save(vehicle));
    }

    /** 状态切换：在途回场时今日趟次 +1；维修中的车不能派出去 */
    @Transactional
    public Views.VehicleView changeStatus(Long id, String target) {
        Vehicle vehicle = vehicleRepository.findById(id).orElseThrow(() -> new BizException("车辆不存在"));
        if (!StationDict.isValidVehicleStatus(target)) {
            throw new BizException("车辆状态只能是 IDLE / ON_ROAD / REPAIR");
        }
        String current = vehicle.getStatus();
        if (current.equals(target)) {
            return toView(vehicle);
        }
        if ("REPAIR".equals(current) && "ON_ROAD".equals(target)) {
            throw new BizException("维修中的车不能直接派出去跑车，请先改回待命");
        }
        LocalDate today = LocalDate.now();
        if (vehicle.getTripDate() == null || !today.equals(vehicle.getTripDate())) {
            vehicle.setTripDate(today);
            vehicle.setTodayTrips(0);
        }
        if ("ON_ROAD".equals(current) && "IDLE".equals(target)) {
            vehicle.setTodayTrips(vehicle.getTodayTrips() + 1);
        }
        vehicle.setStatus(target);
        return toView(vehicleRepository.save(vehicle));
    }

    private Integer rank(String status) {
        int index = StationDict.vehicleStatusOrder().indexOf(status);
        return index < 0 ? 9 : index;
    }

    public Views.VehicleView toView(Vehicle vehicle) {
        List<Map<String, String>> options = new ArrayList<>();
        for (String status : StationDict.vehicleStatusOrder()) {
            options.add(Map.of("value", status, "label", StationDict.vehicleStatusName(status)));
        }
        return new Views.VehicleView(vehicle.getId(), vehicle.getPlateNo(), vehicle.getDriverName(),
                vehicle.getPhone(), vehicle.getLoadCapacity(), vehicle.getStatus(),
                StationDict.vehicleStatusName(vehicle.getStatus()), vehicle.getTripDate(),
                vehicle.getTodayTrips(), vehicle.getRemark(), options);
    }
}
