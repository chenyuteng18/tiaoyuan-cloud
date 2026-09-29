# DyClientSdk.EApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**getBandSyncStatus**](EApi.md#getBandSyncStatus) | **GET** /customers/{id}/band/sync-status | E6 客户端同步状态卡（四态）
[**getBandTelemetry**](EApi.md#getBandTelemetry) | **GET** /customers/{id}/band/telemetry | E3 手环原始数据 + 采集状态（四端按档裁剪）
[**reportBandAvailableDates**](EApi.md#reportBandAvailableDates) | **POST** /band/available-dates | E5 设备可用日期探测结果上报（N 运行时探测 · 禁硬编码）
[**reportBandSyncBatch**](EApi.md#reportBandSyncBatch) | **POST** /band/sync-batches | E1 客户端上报同步批次（四态）
[**upsertBandTelemetry**](EApi.md#upsertBandTelemetry) | **POST** /band/telemetry | E2 客户端上行逐日采集数据（幂等 upsert · 双分支）



## getBandSyncStatus

> GetBandSyncStatus200Response getBandSyncStatus(id)

E6 客户端同步状态卡（四态）

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.EApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
apiInstance.getBandSyncStatus(id).then((data) => {
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

[**GetBandSyncStatus200Response**](GetBandSyncStatus200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## getBandTelemetry

> GetBandTelemetry200Response getBandTelemetry(id)

E3 手环原始数据 + 采集状态（四端按档裁剪）

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.EApi();
let id = "id_example"; // String | 客户 ID（服务端从 token 校验租户归属）
apiInstance.getBandTelemetry(id).then((data) => {
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

[**GetBandTelemetry200Response**](GetBandTelemetry200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json


## reportBandAvailableDates

> ReportBandAvailableDates200Response reportBandAvailableDates(bandAvailableDatesRequest)

E5 设备可用日期探测结果上报（N 运行时探测 · 禁硬编码）

🛑 N 取运行时探测值，禁止硬编码：   · 服务端不得内置 N &#x3D; 7 / N &#x3D; 14 等任何常量；   · retention_window_days 一律取自本接口 valid_history_dates 的计算结果；   · 未探测到 → 返回 TBD，UI 与文案不得给出具体天数，不得按\&quot;数据齐全\&quot;预设布局。 服务端计算：pull_start_date &#x3D; max(last_synced_date + 1, today − retention_window_days)， 上界 &#x3D; today（含当日）。 

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.EApi();
let bandAvailableDatesRequest = new DyClientSdk.BandAvailableDatesRequest(); // BandAvailableDatesRequest | 
apiInstance.reportBandAvailableDates(bandAvailableDatesRequest).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **bandAvailableDatesRequest** | [**BandAvailableDatesRequest**](BandAvailableDatesRequest.md)|  | 

### Return type

[**ReportBandAvailableDates200Response**](ReportBandAvailableDates200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json


## reportBandSyncBatch

> ResultEnvelope reportBandSyncBatch(bandSyncBatchRequest)

E1 客户端上报同步批次（四态）

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.EApi();
let bandSyncBatchRequest = new DyClientSdk.BandSyncBatchRequest(); // BandSyncBatchRequest | 
apiInstance.reportBandSyncBatch(bandSyncBatchRequest).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **bandSyncBatchRequest** | [**BandSyncBatchRequest**](BandSyncBatchRequest.md)|  | 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json


## upsertBandTelemetry

> ResultEnvelope upsertBandTelemetry(bandTelemetryRequest)

E2 客户端上行逐日采集数据（幂等 upsert · 双分支）

幂等键【双分支】（契约 §4.3）：   · 按日型 × 12 → (device_id, metric, date, hour, minute)，日聚合接口退化为 (device_id, metric, date)   · 游标型 × 1  → (device_id, &#39;sport&#39;, current_sport_id) 🔴 运动数据不得复用按日型幂等键。 

### Example

```javascript
import DyClientSdk from 'dy-sdk-client-mp';

let apiInstance = new DyClientSdk.EApi();
let bandTelemetryRequest = new DyClientSdk.BandTelemetryRequest(); // BandTelemetryRequest | 
apiInstance.upsertBandTelemetry(bandTelemetryRequest).then((data) => {
  console.log('API called successfully. Returned data: ' + data);
}, (error) => {
  console.error(error);
});

```

### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **bandTelemetryRequest** | [**BandTelemetryRequest**](BandTelemetryRequest.md)|  | 

### Return type

[**ResultEnvelope**](ResultEnvelope.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

