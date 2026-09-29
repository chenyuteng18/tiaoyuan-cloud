# DyClientSdk.BandSyncBatchRequest

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deviceId** | **String** | 客户级穿戴设备 ID（非门店调理设备） | 
**customerId** | **String** | 服务端从 token 校验归属 | 
**batchNo** | **String** | 幂等键（Idempotency-Key 同值） | 
**trigger** | **String** |  | 
**state** | **String** | 四态；客户端可见的 client_sync_state（不含\&quot;未佩戴\&quot;取值） | 
**syncedAt** | **Date** | 精确记录；客户端展示精确到日 | 
**lastSuccessDate** | **Date** | state&#x3D;synced 时必填 | [optional] 
**failReasonClass** | **String** | state&#x3D;sync_failed 时必填；技术类，取值域内不得出现\&quot;未佩戴\&quot;语义 | [optional] 
**nextAction** | **String** | state&#x3D;sync_failed 时必填（可执行下一步）；不得只给一句\&quot;同步失败\&quot; | [optional] 



## Enum: TriggerEnum


* `on_show_cold` (value: `"on_show_cold"`)

* `on_show_hot` (value: `"on_show_hot"`)

* `checkin` (value: `"checkin"`)

* `daily_report` (value: `"daily_report"`)

* `manual` (value: `"manual"`)





## Enum: StateEnum


* `syncing` (value: `"syncing"`)

* `synced` (value: `"synced"`)

* `sync_failed` (value: `"sync_failed"`)

* `no_data_today` (value: `"no_data_today"`)





## Enum: FailReasonClassEnum


* `bt_off` (value: `"bt_off"`)

* `unauthorized` (value: `"unauthorized"`)

* `connect_timeout` (value: `"connect_timeout"`)

* `device_low_battery` (value: `"device_low_battery"`)

* `occupied_by_vendor_app` (value: `"occupied_by_vendor_app"`)

* `platform_suspended` (value: `"platform_suspended"`)

* `probe_out_of_window` (value: `"probe_out_of_window"`)





## Enum: NextActionEnum


* `open_bluetooth` (value: `"open_bluetooth"`)

* `grant_permission` (value: `"grant_permission"`)

* `retry` (value: `"retry"`)

* `none` (value: `"none"`)




