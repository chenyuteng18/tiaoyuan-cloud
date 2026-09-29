# DyClientSdk.DApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**getPlan**](DApi.md#getPlan) | **GET** /plans/{id} | D5-b 方案查阅
[**listDailyReports**](DApi.md#listDailyReports) | **GET** /customers/{id}/daily-reports | D4 填报记录
[**listVisits**](DApi.md#listVisits) | **GET** /customers/{id}/visits | D2 服务记录（客户维度全局账本）
[**submitDailyReport**](DApi.md#submitDailyReport) | **POST** /customers/{id}/daily-reports | D3 每日填报提交



## getPlan

> ResultEnvelope getPlan(id)

D5-b 方案查阅

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.DApi();
let id = "id_example"; // String | 
apiInstance.getPlan(id).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **id** | **String**|  | 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## listDailyReports

> ResultEnvelope listDailyReports(id)

D4 填报记录

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.DApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
apiInstance.listDailyReports(id).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **id** | **String**| 客户 ID（服务端从 token 校验租户归属） | 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## listVisits

> ResultEnvelope listVisits(id, opts)

D2 服务记录（客户维度全局账本）

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.DApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
let opts = {
  'page': 56, // Number | 
  'pageSize': 56 // Number | 
};
apiInstance.listVisits(id, opts).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **id** | **String**| 客户 ID（服务端从 token 校验租户归属） | 
 **page** | **Number**|  | [optional] 
 **pageSize** | **Number**|  | [optional] 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## submitDailyReport

> SubmitDailyReport200Response submitDailyReport(id, dailyReportRequest)

D3 每日填报提交

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.DApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
let dailyReportRequest = new DyClientSdk.DailyReportRequest(); // DailyReportRequest | 
apiInstance.submitDailyReport(id, dailyReportRequest).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **id** | **String**| 客户 ID（服务端从 token 校验租户归属） | 
 **dailyReportRequest** | [**DailyReportRequest**](DailyReportRequest.md)|  | 

### Return type

[**SubmitDailyReport200Response**](SubmitDailyReport200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

