SELECT 'config_slot' AS t, count(*) FROM config_slot
UNION ALL SELECT 'scale', count(*) FROM scale
UNION ALL SELECT 'device', count(*) FROM device
UNION ALL SELECT 'app_config', count(*) FROM app_config
UNION ALL SELECT 'baseline_assessment', count(*) FROM baseline_assessment;
