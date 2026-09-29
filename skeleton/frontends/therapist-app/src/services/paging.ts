/**
 * 分页查询构造器 —— 参数名与上下界**只来自生成物 PROTOCOL.PAGINATION**。
 * ============================================================================
 *
 * 🛑 为什么需要这个文件（本仓第 58 条）
 * ---------------------------------------------------------------------------
 * 契约 `x-global-conventions.pagination` 只声明了**约束**：
 * 「请求 `?page=<int>&page_size=<int≤100>}；响应 data.{items[], total, page, page_size}」
 * —— 没说越界怎么办。于是后端两个列表端点各走一路（A3 超界拒 / D2 静默夹逼），
 * 而前端这一侧则是另一种形态的同一个病：**参数名 `page` / `page_size` 在各调用点
 * 手抄**（端 A 8 处、端 B 6 处）。契约若把 `page_size` 改成 `pageSize`，
 * tsc / 构建 / 全部门禁**都不会报** —— 它们从不发真实请求，只是请求参数会
 * 静默变成服务端不认识的键，分页悄然失效。
 *
 * 这与第 57 条 `X-Trace-Id` 是同族缺陷：**跨端共同遵守的协议片段被手抄**。
 * 故此处把参数名也收敛到生成物常量：调用点写 `pageQuery(page, size)`，
 * 不再写 `{ page, page_size: size }`。
 */

import { PROTOCOL } from '../contract/endpoints';

/**
 * 分页查询对象 —— 键名来自契约（故不写成 `Record<string, number>` 那种无约束形态）。
 *
 * 🛑 索引签名刻意**不加 readonly**：本类型既要作为出站 query 的**入参**，
 *    也要在 {@link pageQuery} 内**逐步装配**。写成 readonly 会让装配处
 *    `q[KEY] = v` 报 TS2542（本仓实测踩到）。契约对**跨端协议片段**要求的是
 *    「键名来自生成物常量」，而不是「对象不可变」—— 两者不是同一件事，
 *    这里只对前者负责（后者由 Object.freeze 用于 PROTOCOL 本身）。
 */
export interface PageQuery {
  [k: string]: number | undefined;
}

/**
 * 构造分页查询对象（键名取自生成物常量）。
 *
 * @param page     页码（从 {@link PROTOCOL}.PAGINATION.PAGE_MIN 起）
 * @param pageSize 每页条数（1..PAGE_SIZE_MAX；越界由**服务端**报 400，见契约
 *                 `over-range-policy: reject-400`）—— 本函数不做夹逼，
 *                 与后端 `PageQuery` 同口径：**拒，不夹逼**。
 */
export function pageQuery(page?: number, pageSize?: number): PageQuery {
  const q: PageQuery = {};
  if (page !== undefined) {
    q[PROTOCOL.PAGINATION.PAGE_FIELD] = page;
  }
  if (pageSize !== undefined) {
    q[PROTOCOL.PAGINATION.PAGE_SIZE_FIELD] = pageSize;
  }
  return q;
}

/** 契约缺省每页条数 —— 供调用点的默认形参取值。 */
export const DEFAULT_PAGE_SIZE = PROTOCOL.PAGINATION.PAGE_SIZE_DEFAULT;

/** 契约上界每页条数 —— 供界面下拉/校验取值（不做夹逼，仅用于展示可选项）。 */
export const MAX_PAGE_SIZE = PROTOCOL.PAGINATION.PAGE_SIZE_MAX;