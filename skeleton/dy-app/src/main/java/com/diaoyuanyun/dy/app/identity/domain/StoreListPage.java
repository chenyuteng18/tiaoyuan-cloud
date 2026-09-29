package com.diaoyuanyun.dy.app.identity.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.common.page.PageQuery;

import java.util.List;

/**
 * A3 {@code GET /stores} 的<b>出站分页体</b>（契约 {@code StoreListData}）。
 *
 * <h2>契约逐字</h2>
 * <pre>
 *   StoreListData:
 *     items     : array of Store
 *     total     : integer
 *     page      : integer
 *     page_size : integer
 * </pre>
 * 契约 §0（{@code x-global-conventions.pagination}）逐字：
 * 「请求 {@code ?page=<int>&page_size=<int≤100>}；响应 {@code data.{items[], total, page, page_size}}」。
 *
 * <h2>🛑 {@code total} 是"过滤后的总数"，不是"表里的行数"</h2>
 * 这是分页接口最容易被写错、且写错了不报错的一处：若 {@code total} 取全表计数，
 * 客户端会算出"还有 N 页"，翻到第 N 页却拿到空数组 —— 而它<b>不会报错</b>，
 * 只是列表看起来"少了一截"。故 {@code total} 必须与 {@code items} 出自<b>同一个
 * WHERE 子句</b>（同一份行级过滤 + 同一租户上下文），这一点由
 * {@code StoreRepository} 用同一份 where 片段保证。
 *
 * <h2>🛑 {@code page} / {@code page_size} 回显的是"实际生效值"</h2>
 * 请求未带参数时服务端有缺省值，回显必须是<b>生效后的那个数</b>（而非 {@code null}），
 * 否则客户端无从知道"这一页为什么有 20 条"。同理，请求超界被夹逼后
 * （{@code page_size > 100}）回显的也应是夹逼后的值 —— 但那属于"静默改动用户输入"，
 * 故本项目的处置见 {@link com.diaoyuanyun.dy.app.identity.service.StoreListService}：
 * <b>超界直接拒（400）而不是夹逼</b>，故本类回显的恒等于请求值。
 *
 * @param items    本页门店（可能为空数组 —— 空页不是错误）
 * @param total    行级过滤后的总行数
 * @param page     生效页码（从 1 起）
 * @param pageSize 生效每页条数
 */
public record StoreListPage(List<StoreRow> items, int total, int page, int pageSize) {

    /**
     * 契约 {@code pagination.page-size-max} 的上界（{@code 100}）。
     *
     * <p>🛑 值不再本类另外写死 —— 引用唯一校验单点 {@link PageQuery}，
     * 使「上界」在全局只有一个来源（本仓第 58 条：分页协议越界语义收口）。
     */
    public static final int MAX_PAGE_SIZE = PageQuery.MAX_PAGE_SIZE;

    /** 未传 {@code page_size} 时的缺省值 —— {@code 20}，同样引用单点。 */
    public static final int DEFAULT_PAGE_SIZE = PageQuery.DEFAULT_PAGE_SIZE;

    public StoreListPage {
        if (items == null) {
            throw new IllegalArgumentException(
                    "门店分页的 items 不得为 null —— 空结果应为空数组："
                            + "null 会被客户端读成『接口异常』，而空数组是『确实没有』");
        }
        if (total < 0) {
            throw new IllegalArgumentException("门店分页的 total 不得为负: " + total);
        }
        if (page < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "page 必须 ≥ 1：实际=" + page
                            + "（契约 parameters.Page: minimum: 1）。"
                            + "0 与负数没有对应的分页语义");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "page_size 必须在 1.." + MAX_PAGE_SIZE + " 之间：实际=" + pageSize
                            + "（契约 parameters.PageSize: maximum: 100）");
        }
        items = List.copyOf(items);
    }

    /** 契约 {@code StoreListData} 的逐字装配（用 {@code LinkedHashMap} 保序）。 */
    public java.util.Map<String, Object> toContractData() {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (StoreRow row : items) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("store_id", row.storeId().toString());
            item.put("name", row.name());
            item.put("franchise_type", row.franchiseType());
            rows.add(item);
        }
        data.put("items", rows);
        data.put("total", total);
        data.put("page", page);
        data.put("page_size", pageSize);
        return data;
    }
}