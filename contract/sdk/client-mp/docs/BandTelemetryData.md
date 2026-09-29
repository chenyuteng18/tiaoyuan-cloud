# DyClientSdk.BandTelemetryData

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**dataSource** | **String** | 未接入时数值字段不下发，不得显示 0 / 空图表 / 示例数据 | [optional] 
**collectedDays** | **Number** | 已采集天数（正向计数） | [optional] 
**syncedDate** | **Date** | 客户可见同步时间精确到日（不得到分秒） | [optional] 
**metrics** | **[Object]** |  | [optional] 
**gapReason** | **String** | 🔴 客户恒 403 / 不下发（硬约束，配置不得放开） | [optional] 



## Enum: DataSourceEnum


* `手环` (value: `"手环"`)

* `未接入` (value: `"未接入"`)





## Enum: GapReasonEnum


* `no_open` (value: `"no_open"`)

* `sync_failed` (value: `"sync_failed"`)

* `not_worn` (value: `"not_worn"`)

* `compliant_removal` (value: `"compliant_removal"`)

* `involuntary_technical` (value: `"involuntary_technical"`)

* `beyond_retention_window` (value: `"beyond_retention_window"`)

* `unknown` (value: `"unknown"`)




