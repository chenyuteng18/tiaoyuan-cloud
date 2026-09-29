# DyClientSdk.BandAvailableDatesData

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**probeId** | **String** |  | [optional] 
**retentionWindowDays** | [**BandAvailableDatesDataRetentionWindowDays**](BandAvailableDatesDataRetentionWindowDays.md) |  | [optional] 
**pullStartDate** | **Date** | 服务端计算 start &#x3D; max(last_synced_date + 1, today − retention_window_days)；上界 &#x3D; today（含当日） | [optional] 
**earliestAvailable** | **Date** |  | [optional] 
**latestAvailable** | **Date** |  | [optional] 


