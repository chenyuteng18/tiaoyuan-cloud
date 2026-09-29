package com.diaoyuanyun.dy.security.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 端点声明所需的最低组织层级（S1-3 验收② · PRD §2.2 三级权限）。
 *
 * <p>用法：{@code @RequireOrgLevel(min = OrgLevel.HEADQUARTERS)} 表示"仅总部层可访问"；
 * {@code @RequireOrgLevel(min = OrgLevel.REGION)} 表示"区域层及以上（区域 + 总部）可访问"。
 *
 * <h2>为何是"最低层级"而不是"允许角色清单"</h2>
 * 白名单写法（{@code roles = {"area","hq"}}）每加一个角色都要改所有端点，且
 * <b>容易漏</b>——漏掉的那个端点不会报错，只会安静地把新角色挡在外面（或放进来）。
 * "最低层级"把层级包含关系交给 {@link OrgLevel#covers(OrgLevel)} 这一个定义，
 * 新增角色只需在 {@link OrgLevel} 登记赴任层级，<b>全部端点自动跟随</b>。
 *
 * <h2>与 {@link RequirePermission} 的分工</h2>
 * <ul>
 *   <li>{@link RequirePermission} —— <b>功能权限</b>（能不能用这个功能，如 {@code customer:read}）</li>
 *   <li>{@code RequireOrgLevel} —— <b>数据范围</b>（能看到多宽的数据：本店 / 辖区 / 全量）</li>
 * </ul>
 * 两者正交，可同时标注；任一不满足都返回 403，但错误码语义不同（2001 / 2001 vs 2003）。
 * <b>不要用一个注解表达两件事</b> —— 那会让"权限不够"与"范围不够"再也分不开。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOrgLevel {

    /** 所需的最低组织层级；请求方层级必须 {@link OrgLevel#covers(OrgLevel) 覆盖} 它。 */
    OrgLevel min();
}