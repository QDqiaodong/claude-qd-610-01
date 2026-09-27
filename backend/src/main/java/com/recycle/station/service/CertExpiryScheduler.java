package com.recycle.station.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨日自动到期：每天凌晨把有效期止日已过的生效证照统一置 EXPIRED。
 * 即使当夜没有任何操作，第二天调度员打开列表看到的也是"已到期"而不是残留的"生效"；
 * 另外每次写操作与列表加载都会再跑一次到期扫描，多重保证判断口径一致。
 */
@Component
public class CertExpiryScheduler {

    private final CertService certService;

    public CertExpiryScheduler(CertService certService) {
        this.certService = certService;
    }

    /** 每天 00:05 扫描（服务端时区，application.yml 已固定 Asia/Shanghai） */
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void dailyExpire() {
        certService.sweepExpired();
    }
}
