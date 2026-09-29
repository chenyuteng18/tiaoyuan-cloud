# DyClientSdk.CApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**getAssessment**](CApi.md#getAssessment) | **GET** /customers/{id}/assessments/{assessment_id} | C3 评估详情
[**listScaleItemBanks**](CApi.md#listScaleItemBanks) | **GET** /scale-item-banks | C1 题库拉取（按分龄组 + 维度）



## getAssessment

> ResultEnvelope getAssessment(id, assessmentId)

C3 评估详情

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.CApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
let assessmentId = "assessmentId_example"; // String | 
apiInstance.getAssessment(id, assessmentId).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **id** | **String**| 客户 ID（服务端从 token 校验租户归属） | 
 **assessmentId** | **String**|  | 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## listScaleItemBanks

> ResultEnvelope listScaleItemBanks(ageGroup, opts)

C1 题库拉取（按分龄组 + 维度）

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.CApi();
let ageGroup = new DyClientSdk.AgeGroup(); // AgeGroup | 
let opts = {
  'dimension': new DyClientSdk.Dimension(), // Dimension | 
  'version': "version_example" // String | 
};
apiInstance.listScaleItemBanks(ageGroup, opts).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **ageGroup** | [**AgeGroup**](.md)|  | 
 **dimension** | [**Dimension**](.md)|  | [optional] 
 **version** | **String**|  | [optional] 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

