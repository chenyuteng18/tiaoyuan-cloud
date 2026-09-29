package com.diaoyuanyun.dy.web.trace;

import java.util.UUID;

/**
 * trace_id 生成工具。骨架用 UUID; 生产可换 ULID/有序 UUID 以适配高并发 (ADR-09)。
 */
public final class TraceUtils {

    private TraceUtils() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
