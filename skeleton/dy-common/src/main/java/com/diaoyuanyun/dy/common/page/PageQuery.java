package com.diaoyuanyun.dy.common.page;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 分页查询参数的<b>唯一校验单点</b>（契约 {@code x-api-protocol.pagination}）。
 *
 * <h2>🛑 为什么必须有这一个类（本仓第 58 条）</h2>
 * 契约原本只在 {@code x-global-conventions.pagination} 写了一句话：
 * 「请求 {@code ?page=<int>&page_size=<int≤100>}；响应 {@code data.{items[], total, page, page_size}}」
 * —— 它声明了<b>约束</b>（上界 100），却<b>没声明越界怎么办</b>。于是同一份契约下
 * 两个列表端点各走一路：
 * <pre>
 *   A3 GET /stores   page_size=101 ⇒ 400 VALIDATION_FAILED（超界直接拒）
 *   D2 GET /visits   page_size=101 ⇒ Math.min(Math.max(101, 1), 100) = 100，
 *                                    静默夹逼后返回 200
 * </pre>
 * 两者的 200 响应体、{@code tsc}、{@code vite}、全部门禁<b>都绿</b>。客户端请求了 101 条、
 * 拿到 100 条、且<b>无从察觉</b> —— 这正是第 57 条 {@code X-Trace-Id} 的同族缺陷：
 * <b>契约写下的协议与各端实现的协议是两件事，中间丢项不报错。</b>
 *
 * <h2>唯一合法处置：reject-400（拒，不夹逼）</h2>
 * {@code x-error-codes} 里 {@code VALIDATION_FAILED.trigger} 逐字就是
 * 「参数类型 / 必填 / <b>约束不满足</b>」—— 参数超出声明的 {@code maximum: 100} 属于
 * 「约束不满足」，报 400 是契约自己写下的规则。静默夹逼则把越界变成了
 * 「悄悄替你改成合法值」，与 {@code trigger} 冲突。
 *
 * <p>故本类是<b>唯一</b>允许做分页越界判断的地方：各域控制器不得再手写
 * {@code Math.min(Math.max(...))} 之类的夹逼，也不得手写 {@code page < 1 ? 1 : page}
 * 之类的默认纠正 —— 一律调用本类的 {@link #of(Integer, Integer)}。
 * 「不得手写」由 {@code PaginationDisciplineTest} 在构建期扫描源码守住。
 *
 * <h2>缺省值只在「真的没传」时生效</h2>
 * {@code null} ⇒ 取缺省（{@code page=1} / {@code page_size=20}）；
 * 传了 {@code 0} / 负数 / 超上界 ⇒ 一律拒。「传了非法值」与「没传」是两件事，
 * 把前者当成后者是另一种静默改动 —— 会让「我要了第 0 页」与「我要了第 1 页」
 * 拿到同一个结果而不报错。
 *
 * @param page     生效页码（从 1 起）
 * @param pageSize 生效每页条数
 */
public record PageQuery(int page, int pageSize) {

    /** 契约 {@code pagination.page-size-max} —— 上界。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 契约 {@code pagination.page-size-default} —— 未传 {@code page_size} 时的缺省。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 契约 {@code pagination.page-min}。 */
    public static final int MIN_PAGE = 1;

    /** 契约 {@code pagination.page-default} —— 未传 {@code page} 时的缺省。 */
    public static final int DEFAULT_PAGE = 1;

    /**
     * 唯一的校验入口 —— 越界一律 400（{@code VALIDATION_FAILED}），绝不夹逼。
     *
     * @param page     原始 {@code page}（可空）
     * @param pageSize 原始 {@code page_size}（可空）
     * @return 生效分页参数
     * @throws BizException 参数越界时抛 {@code VALIDATION_FAILED}
     */
    public static PageQuery of(Integer page, Integer pageSize) {
        return new PageQuery(validatePage(page), validatePageSize(pageSize));
    }

    /**
     * 校验 {@code page}（契约 {@code parameters.Page: minimum: 1}）。
     *
     * <p>🛑 缺省值只在<b>真的没传</b>时生效（{@code null}）；传了 {@code 0} 或负数一律拒。
     */
    public static int validatePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < MIN_PAGE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "page 必须 ≥ " + MIN_PAGE + "：实际=" + page
                            + "（契约 x-api-protocol.pagination.page-min: 1）。"
                            + "🛑 不把 0 / 负数当作『没传』来兜底：那是静默改动用户输入，"
                            + "会让『我要了第 0 页』与『我要了第 1 页』拿到同一个结果而不报错");
        }
        return page;
    }

    /**
     * 校验 {@code page_size}（契约 {@code parameters.PageSize: maximum: 100}）。
     *
     * <p>🛑 上界之上<b>直接拒</b>而不夹逼到 100 —— 见类注释。夹逼会让客户端以为自己
     * 拿到了 {@code page_size=500} 的结果，把「还有 400 条」读成「一共就这些」，且不报错。
     */
    public static int validatePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "page_size 必须在 1.." + MAX_PAGE_SIZE + " 之间：实际=" + pageSize
                            + "（契约 x-api-protocol.pagination.page-size-max: 100，"
                            + "over-range-policy: reject-400）。"
                            + "🛑 不夹逼到 100：夹逼会让客户端以为自己拿到了 page_size=" + pageSize
                            + " 的结果，把『还有更多条』读成『一共就这些』—— 而它不会报错");
        }
        return pageSize;
    }

    /** {@code page} 的等价访问器（契约参数名为 {@code page}）。 */
    public int page() {
        return page;
    }

    /** {@code page_size} 的等价访问器（契约参数名为 {@code page_size}）。 */
    public int pageSize() {
        return pageSize;
    }
}