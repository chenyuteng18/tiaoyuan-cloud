# DyClientSdk.BApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**getCustomer**](BApi.md#getCustomer) | **GET** /customers/{id} | B4 客户详情（可见性裁剪重点接口）
[**getIntakeProfile**](BApi.md#getIntakeProfile) | **GET** /customers/{id}/intake-profile | B5 建档扩展档案



## getCustomer

> GetCustomer200Response getCustomer(id, opts)

B4 客户详情（可见性裁剪重点接口）

🔴 客户 token 请求含派生字段的 query（如 ?include&#x3D;verdict）→ 403 VISIBILITY_DENIED， data.denied_fields 回显被拒字段名。 

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.BApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
let opts = {
  'include': "include_example" // String | 追加字段（如 verdict）；命中客户不可见字段组时 403 VISIBILITY_DENIED
};
apiInstance.getCustomer(id, opts).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **id** | **String**| 客户 ID（服务端从 token 校验租户归属） | 
 **include** | **String**| 追加字段（如 verdict）；命中客户不可见字段组时 403 VISIBILITY_DENIED | [optional] 

### Return type

[**GetCustomer200Response**](GetCustomer200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## getIntakeProfile

> ResultEnvelope getIntakeProfile(id)

B5 建档扩展档案

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.BApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
apiInstance.getIntakeProfile(id).then((data) => {
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

