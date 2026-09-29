# DyClientSdk.AApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**authLogin**](AApi.md#authLogin) | **POST** /auth/login | A1 登录，签发 token
[**authMe**](AApi.md#authMe) | **GET** /auth/me | A2 当前身份 + 角色 + 可见性档位解算结果（可见性档位唯一权威下发点）



## authLogin

> AuthLogin200Response authLogin(authLoginRequest)

A1 登录，签发 token

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.AApi();
let authLoginRequest = new DyClientSdk.AuthLoginRequest(); // AuthLoginRequest | 
apiInstance.authLogin(authLoginRequest).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **authLoginRequest** | [**AuthLoginRequest**](AuthLoginRequest.md)|  | 

### Return type

[**AuthLogin200Response**](AuthLogin200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json


## authMe

> AuthMe200Response authMe()

A2 当前身份 + 角色 + 可见性档位解算结果（可见性档位唯一权威下发点）

⚠️ 本接口只下发「档位布尔值」，不下发任何业务字段 —— 它是声明，不是数据。 三端 UI 依此声明决定渲染分支，但字段仍由服务端裁剪（X-1：声明 ≠ 授权，二者须一致）。 

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.AApi();
apiInstance.authMe().then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters

This endpoint does not need any parameter.

### Return type

[**AuthMe200Response**](AuthMe200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

