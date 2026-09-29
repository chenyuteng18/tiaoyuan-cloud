# DyClientSdk.BandTelemetryRequest

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deviceId** | **String** |  | 
**metric** | **String** |  | 
**date** | **Date** | 业务日（设备本地时区） | [optional] 
**hour** | **Number** | 日聚合型退化为 NULL | [optional] 
**minute** | **Number** |  | [optional] 
**value** | **Object** | 按 metric 的 Schema | [optional] 
**interval** | **Number** | 采样间隔 | [optional] 
**isWear** | **Number** | getHealthDetail.isWear；1&#x3D;佩戴 / 0&#x3D;脱腕 / (-1,255)&#x3D;技术性缺失（服务端强制覆写、不判行为性） | [optional] 
**rawSource** | **String** |  | [optional] 
**currentSportId** | **String** | 游标型 sport 分支 | [optional] 
**sportPayload** | **Object** |  | [optional] 
**sportLength** | **Number** | 循环判据（sportLength &gt; 1） | [optional] 



## Enum: MetricEnum


* `sleep` (value: `"sleep"`)

* `steps` (value: `"steps"`)

* `hr` (value: `"hr"`)

* `resting_hr` (value: `"resting_hr"`)

* `spo2` (value: `"spo2"`)

* `workout` (value: `"workout"`)

* `bp` (value: `"bp"`)

* `temp` (value: `"temp"`)

* `pressure` (value: `"pressure"`)

* `met` (value: `"met"`)

* `mai` (value: `"mai"`)

* `respiration` (value: `"respiration"`)

* `exercise` (value: `"exercise"`)




