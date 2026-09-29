package com.diaoyuanyun.dy.common.audit;

import java.time.Instant;

/**
 * 审计基类 (ADR-A3/A-5/A-6, dy-audit 自动填充 created_by)。
 *
 * <p>字段严格按裁定: created_at / updated_at / created_by / updated_at_by / deleted_at (软删除)。
 * 设计为可被 JPA {@code @MappedSuperclass} 继承 (本轮骨架不引入 JPA 依赖, 业务实体落地时再加注解)。
 */
public abstract class AuditableEntity {

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedAtBy;
    private Instant deletedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedAtBy() {
        return updatedAtBy;
    }

    public void setUpdatedAtBy(String updatedAtBy) {
        this.updatedAtBy = updatedAtBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
