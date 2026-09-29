package com.diaoyuanyun.dy.security.gate;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色→门禁能力项 映射 (骨架最小实现)。
 */
@Component
public class GateRegistry {

    private final Map<String, Set<String>> roleGates = new HashMap<>();

    public GateRegistry() {
        roleGates.put("SUPER_ADMIN", Set.of("*"));
        roleGates.put("REGION_ADMIN", Set.of("gate:refund_view", "gate:export"));
        roleGates.put("STORE_STAFF", Set.of("gate:refund_view"));
        roleGates.put("GUEST", Set.of());
    }

    public boolean hasGate(String role, String gateItem) {
        if (role == null) {
            return false;
        }
        Set<String> gates = roleGates.getOrDefault(role, Set.of());
        return gates.contains("*") || gates.contains(gateItem);
    }
}
