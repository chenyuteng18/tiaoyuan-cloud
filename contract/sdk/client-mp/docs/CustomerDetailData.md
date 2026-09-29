# DyClientSdk.CustomerDetailData

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**customerId** | **String** |  | [optional] 
**name** | **String** |  | [optional] 
**gender** | **String** |  | [optional] 
**age** | **Number** |  | [optional] 
**intakeProfile** | **Object** |  | [optional] 
**screeningResult** | **String** |  | [optional] 
**bandWillingness** | **String** |  | [optional] 
**ownerStoreId** | **String** |  | [optional] 
**servingStoreId** | **String** |  | [optional] 
**effectVerdict** | **String** | 派生字段；客户 token 请求 include&#x3D;verdict → 403 VISIBILITY_DENIED | [optional] 
**asValue** | **Number** | 派生字段；客户恒不下发 | [optional] 



## Enum: BandWillingnessEnum


* `自愿佩戴` (value: `"自愿佩戴"`)

* `暂不佩戴` (value: `"暂不佩戴"`)




