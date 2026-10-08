# 反向代理公共参数（被 nginx.conf 的两处 location include）
proxy_http_version 1.1;
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto https;
proxy_set_header Connection        "";

# 🛑 后端做租户一致性校验的 X-Tenant-Id 来自请求头本身（仅校验、不采纳其值建立上下文
#    —— ADR-05），代理层不得注入或改写它，原样透传即可。
proxy_read_timeout    60s;
proxy_connect_timeout 10s;
proxy_send_timeout    60s;
