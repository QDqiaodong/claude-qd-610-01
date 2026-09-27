package com.recycle.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 证照派车联锁门（全表单行，固定 id=1）。
 * <p>
 * 派车确认、复核生效、撤销生效证照、在途回场都在同一事务里先
 * {@code SELECT ... FROM cert_interlock WHERE id = 1 FOR UPDATE} 拿到这把钥匙，
 * 于是"资质变更"与"派车确认"被数据库强制排成明确的先后：
 * 先到的事务把状态改成什么，后到的事务就只能看到什么，绝不可能车已出发却找不到一组当时有效的资料。
 */
@Entity
@Table(name = "cert_interlock")
public class CertInterlock {

    /** 固定主键，全站唯一一行 */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
