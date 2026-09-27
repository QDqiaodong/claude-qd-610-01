package com.recycle.station.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 证照到期扫描：不依赖前端是否打开页面，每日零点后统一把过期的生效版本转 EXPIRED，
 * 并给仍在途的车打待调度处置。服务启动时也扫一次——重新登录、跨日、重启后
 * 证照状态/到期判断/派车限制口径一致。
 */
@Component
public class CredentialExpiryScheduler {

    private final CredentialService credentialService;

    public CredentialExpiryScheduler(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 每天 00:05 跑一次（服务所在时区 Asia/Shanghai） */
    @Scheduled(cron = "0 5 0 * * ?")
    public void dailySweep() {
        credentialService.expireDueDocs();
    }

    /** 服务重启后先扫一次，防止停机期间跨过有效期止 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        credentialService.expireDueDocs();
    }
}
