package com.recycle.station.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.domain.Vehicle;
import com.recycle.station.repository.DispatchTripRepository;
import com.recycle.station.repository.VehicleRepository;

/**
 * 启动 / 重启后的一致性对齐：
 * <ul>
 *   <li>先跑证照到期扫描，保证"到期判断"在跨日 / 重启后与数据库状态一致；</li>
 *   <li>车辆状态与出车单对齐：存在未结束出车的车必须是 ON_ROAD；无未结束出车的在途车
 *       （例如容器在出车事务后被直接销毁、未及回场）自动回 IDLE 且不重复计趟次，
 *       避免重启后出现"车在途但查不到未结束出车 / 可派状态错乱"。</li>
 * </ul>
 * 派车资格快照本身不可变，不需要任何对齐。
 */
@Component
public class CertStartupReconciler {

    private final CertService certService;
    private final VehicleRepository vehicleRepository;
    private final DispatchTripRepository tripRepository;

    public CertStartupReconciler(CertService certService,
                                 VehicleRepository vehicleRepository,
                                 DispatchTripRepository tripRepository) {
        this.certService = certService;
        this.vehicleRepository = vehicleRepository;
        this.tripRepository = tripRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcile() {
        certService.sweepExpired();
        for (Vehicle vehicle : vehicleRepository.findAll()) {
            boolean hasOpen = !tripRepository.findOpenByVehicleIdList(vehicle.getId()).isEmpty();
            if (hasOpen && !"ON_ROAD".equals(vehicle.getStatus())) {
                vehicle.setStatus("ON_ROAD");
                vehicleRepository.save(vehicle);
            } else if (!hasOpen && "ON_ROAD".equals(vehicle.getStatus())) {
                // 无未结束出车却停在在途：按"已回场未落账"对齐回待命，趟次不重复加
                vehicle.setStatus("IDLE");
                vehicleRepository.save(vehicle);
            }
        }
    }
}
