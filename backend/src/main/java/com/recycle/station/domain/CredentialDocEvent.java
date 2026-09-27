package com.recycle.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 证照版本变化轨迹：CREATE 草稿录入 / SUBMIT 提交复核 / APPROVE 生效 / REJECT 驳回 /
 * REVOKE 撤销 / EXPIRE 到期 / SUPERSEDE 被新版本替代。
 * 只追加不改写，新旧版本差异与“当时谁经办、谁复核、为什么驳回撤销”都从这里追溯。
 */
@Entity
@Table(name = "credential_doc_event")
public class CredentialDocEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "group_uuid", nullable = false, length = 36)
    private String groupUuid;

    /** CREATE / SUBMIT / APPROVE / REJECT / REVOKE / EXPIRE / SUPERSEDE */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    /** 动作发生后该版本的状态 */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    @Column(name = "operator", nullable = false, length = 30)
    private String operator;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getGroupUuid() {
        return groupUuid;
    }

    public void setGroupUuid(String groupUuid) {
        this.groupUuid = groupUuid;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
