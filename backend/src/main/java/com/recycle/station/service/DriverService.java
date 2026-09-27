package com.recycle.station.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.Driver;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.DriverRepository;

/**
 * 司机名册：证照主体管理。司机是司机证照（驾驶证 / 资格证）的挂载对象，
 * 派车时"本次司机"必须从在岗司机里选定。
 */
@Service
public class DriverService {

    private final DriverRepository driverRepository;

    public DriverService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.DriverView> list(String status) {
        List<Driver> drivers = status == null || status.isBlank()
                ? driverRepository.findAllByOrderByIdAsc()
                : driverRepository.findByStatusOrderByIdAsc(status.trim().toUpperCase());
        List<Views.DriverView> views = new ArrayList<>();
        for (Driver driver : drivers) {
            views.add(toView(driver));
        }
        return views;
    }

    @Transactional
    public Views.DriverView create(Reqs.DriverSaveReq req) {
        String driverNo = req.driverNo().trim();
        if (driverRepository.findByDriverNo(driverNo) != null) {
            throw new BizException("司机工号 " + driverNo + " 已存在");
        }
        String status = req.status() == null || req.status().isBlank() ? "ACTIVE" : req.status().trim().toUpperCase();
        if (!"ACTIVE".equals(status) && !"RESTING".equals(status) && !"RESIGNED".equals(status)) {
            throw new BizException("司机状态只能是 ACTIVE / RESTING / RESIGNED");
        }
        Driver driver = new Driver();
        driver.setDriverNo(driverNo);
        driver.setDriverName(req.driverName().trim());
        driver.setPhone(req.phone());
        driver.setStatus(status);
        driver.setCreatedAt(LocalDateTime.now());
        return toView(driverRepository.save(driver));
    }

    private Views.DriverView toView(Driver driver) {
        String statusName = switch (driver.getStatus()) {
            case "ACTIVE" -> "在岗";
            case "RESTING" -> "休息";
            case "RESIGNED" -> "离职";
            default -> driver.getStatus();
        };
        return new Views.DriverView(driver.getId(), driver.getDriverNo(), driver.getDriverName(),
                driver.getPhone(), driver.getStatus(), statusName, driver.getLockVersion());
    }
}
