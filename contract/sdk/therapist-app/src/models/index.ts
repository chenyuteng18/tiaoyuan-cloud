/* tslint:disable */
/* eslint-disable */

/**
 * 
 * @export
 */
export const AgeGroup = {
    _1632: '男16-32',
    _3340: '男33-40',
    _4148: '男41-48',
    _49: '男49以上',
    _1428: '女14-28',
    _2935: '女29-35',
    _3642: '女36-42',
    _4349: '女43-49以上'
} as const;
export type AgeGroup = typeof AgeGroup[keyof typeof AgeGroup];

/**
 * 
 * @export
 * @interface AuthLogin200Response
 */
export interface AuthLogin200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof AuthLogin200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof AuthLogin200Response
     */
    message: string;
    /**
     * 
     * @type {LoginData}
     * @memberof AuthLogin200Response
     */
    data?: LoginData;
    /**
     * 
     * @type {string}
     * @memberof AuthLogin200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface AuthLoginRequest
 */
export interface AuthLoginRequest {
    /**
     * 员工号 / 客户 openid 绑定号
     * @type {string}
     * @memberof AuthLoginRequest
     */
    account: string;
    /**
     * 密码 / 短信码
     * @type {string}
     * @memberof AuthLoginRequest
     */
    credential: string;
    /**
     * 
     * @type {string}
     * @memberof AuthLoginRequest
     */
    clientEnd: AuthLoginRequestClientEndEnum;
}


/**
 * @export
 */
export const AuthLoginRequestClientEndEnum = {
    Mp: 'mp',
    App: 'app',
    Web: 'web'
} as const;
export type AuthLoginRequestClientEndEnum = typeof AuthLoginRequestClientEndEnum[keyof typeof AuthLoginRequestClientEndEnum];

/**
 * 
 * @export
 * @interface AuthMe200Response
 */
export interface AuthMe200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof AuthMe200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof AuthMe200Response
     */
    message: string;
    /**
     * 
     * @type {AuthMeData}
     * @memberof AuthMe200Response
     */
    data?: AuthMeData;
    /**
     * 
     * @type {string}
     * @memberof AuthMe200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface AuthMeData
 */
export interface AuthMeData {
    /**
     * 
     * @type {string}
     * @memberof AuthMeData
     */
    role?: string;
    /**
     * 
     * @type {AuthMeDataBandVisibility}
     * @memberof AuthMeData
     */
    bandVisibility?: AuthMeDataBandVisibility;
    /**
     * 调理师 false（config
     * @type {boolean}
     * @memberof AuthMeData
     */
    refundVisibility?: boolean;
    /**
     * 
     * @type {AuthMeDataStoreScope}
     * @memberof AuthMeData
     */
    storeScope?: AuthMeDataStoreScope;
}
/**
 * 字段组档位布尔值（声明，不是数据）
 * @export
 * @interface AuthMeDataBandVisibility
 */
export interface AuthMeDataBandVisibility {
    /**
     * 
     * @type {boolean}
     * @memberof AuthMeDataBandVisibility
     */
    fieldGroup1Raw?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof AuthMeDataBandVisibility
     */
    fieldGroup2Status?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof AuthMeDataBandVisibility
     */
    fieldGroup3GapReason?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof AuthMeDataBandVisibility
     */
    fieldGroup4Derived?: boolean;
}
/**
 * 
 * @export
 * @interface AuthMeDataStoreScope
 */
export interface AuthMeDataStoreScope {
    /**
     * 
     * @type {string}
     * @memberof AuthMeDataStoreScope
     */
    rowLevel?: AuthMeDataStoreScopeRowLevelEnum;
    /**
     * 
     * @type {Array<string>}
     * @memberof AuthMeDataStoreScope
     */
    storeIds?: Array<string>;
}


/**
 * @export
 */
export const AuthMeDataStoreScopeRowLevelEnum = {
    OwnStore: 'own_store',
    Region: 'region',
    All: 'all'
} as const;
export type AuthMeDataStoreScopeRowLevelEnum = typeof AuthMeDataStoreScopeRowLevelEnum[keyof typeof AuthMeDataStoreScopeRowLevelEnum];

/**
 * 
 * @export
 * @interface BandDerivedData
 */
export interface BandDerivedData {
    /**
     * 为 false 时不返回 a3_value（避免 0 值被误读为"戴了 0 天"）
     * @type {boolean}
     * @memberof BandDerivedData
     */
    a3Applicable?: boolean;
    /**
     * 
     * @type {number}
     * @memberof BandDerivedData
     */
    a3Value?: number;
    /**
     * 
     * @type {number}
     * @memberof BandDerivedData
     */
    asValue?: number;
    /**
     * 
     * @type {string}
     * @memberof BandDerivedData
     */
    effectVerdict?: BandDerivedDataEffectVerdictEnum;
    /**
     * 
     * @type {boolean}
     * @memberof BandDerivedData
     */
    refundEligibility?: boolean;
}


/**
 * @export
 */
export const BandDerivedDataEffectVerdictEnum = {
    E1: 'E1显著改善',
    E2: 'E2部分改善',
    E3: 'E3稳定',
    E4: 'E4无明显改善',
    E5: 'E5加重'
} as const;
export type BandDerivedDataEffectVerdictEnum = typeof BandDerivedDataEffectVerdictEnum[keyof typeof BandDerivedDataEffectVerdictEnum];

/**
 * 
 * @export
 * @interface BandTelemetryData
 */
export interface BandTelemetryData {
    /**
     * 未接入时数值字段不下发，不得显示 0 / 空图表 / 示例数据
     * @type {string}
     * @memberof BandTelemetryData
     */
    dataSource?: BandTelemetryDataDataSourceEnum;
    /**
     * 已采集天数（正向计数）
     * @type {number}
     * @memberof BandTelemetryData
     */
    collectedDays?: number;
    /**
     * 客户可见同步时间精确到日（不得到分秒）
     * @type {string}
     * @memberof BandTelemetryData
     */
    syncedDate?: string;
    /**
     * 
     * @type {Array<object>}
     * @memberof BandTelemetryData
     */
    metrics?: Array<object>;
    /**
     * 🔴 客户恒 403 / 不下发（硬约束，配置不得放开）
     * @type {string}
     * @memberof BandTelemetryData
     */
    gapReason?: BandTelemetryDataGapReasonEnum;
}


/**
 * @export
 */
export const BandTelemetryDataDataSourceEnum = {
    : '手环',
    2: '未接入'
} as const;
export type BandTelemetryDataDataSourceEnum = typeof BandTelemetryDataDataSourceEnum[keyof typeof BandTelemetryDataDataSourceEnum];

/**
 * @export
 */
export const BandTelemetryDataGapReasonEnum = {
    NoOpen: 'no_open',
    SyncFailed: 'sync_failed',
    NotWorn: 'not_worn',
    CompliantRemoval: 'compliant_removal',
    InvoluntaryTechnical: 'involuntary_technical',
    BeyondRetentionWindow: 'beyond_retention_window',
    Unknown: 'unknown'
} as const;
export type BandTelemetryDataGapReasonEnum = typeof BandTelemetryDataGapReasonEnum[keyof typeof BandTelemetryDataGapReasonEnum];

/**
 * 
 * @export
 * @interface BaselineAssessmentData
 */
export interface BaselineAssessmentData {
    /**
     * 
     * @type {string}
     * @memberof BaselineAssessmentData
     */
    assessmentId?: string;
    /**
     * 
     * @type {string}
     * @memberof BaselineAssessmentData
     */
    assessedAt?: string;
    /**
     * 
     * @type {BaselineAssessmentDataBaselineConclusion}
     * @memberof BaselineAssessmentData
     */
    baselineConclusion?: BaselineAssessmentDataBaselineConclusion;
    /**
     * 客户不下发
     * @type {boolean}
     * @memberof BaselineAssessmentData
     */
    migratable?: boolean;
    /**
     * 客户仅见本人填答维度
     * @type {Array<number>}
     * @memberof BaselineAssessmentData
     */
    dimensionScores?: Array<number>;
}
/**
 * 
 * @export
 * @interface BaselineAssessmentDataBaselineConclusion
 */
export interface BaselineAssessmentDataBaselineConclusion {
    /**
     * 
     * @type {string}
     * @memberof BaselineAssessmentDataBaselineConclusion
     */
    haozhuan?: BaselineAssessmentDataBaselineConclusionHaozhuanEnum;
}


/**
 * @export
 */
export const BaselineAssessmentDataBaselineConclusionHaozhuanEnum = {
    : '好转',
    2: '稳定',
    3: '下降'
} as const;
export type BaselineAssessmentDataBaselineConclusionHaozhuanEnum = typeof BaselineAssessmentDataBaselineConclusionHaozhuanEnum[keyof typeof BaselineAssessmentDataBaselineConclusionHaozhuanEnum];

/**
 * 
 * @export
 * @interface BaselineAssessmentRequest
 */
export interface BaselineAssessmentRequest {
    /**
     * 
     * @type {string}
     * @memberof BaselineAssessmentRequest
     */
    scaleId: string;
    /**
     * 同源题组锁定
     * @type {string}
     * @memberof BaselineAssessmentRequest
     */
    itemGroupId: string;
    /**
     * 
     * @type {AgeGroup}
     * @memberof BaselineAssessmentRequest
     */
    ageGroupLocked: AgeGroup;
    /**
     * 
     * @type {Array<number>}
     * @memberof BaselineAssessmentRequest
     */
    dimensionScores: Array<number>;
    /**
     * 
     * @type {number}
     * @memberof BaselineAssessmentRequest
     */
    totalScore: number;
    /**
     * 
     * @type {string}
     * @memberof BaselineAssessmentRequest
     */
    measureOperator: string;
}


/**
 * 
 * @export
 * @interface CreateCustomer200Response
 */
export interface CreateCustomer200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof CreateCustomer200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof CreateCustomer200Response
     */
    message: string;
    /**
     * 
     * @type {CustomerCreateData}
     * @memberof CreateCustomer200Response
     */
    data?: CustomerCreateData;
    /**
     * 
     * @type {string}
     * @memberof CreateCustomer200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface CreateCycleAssessmentRequest
 */
export interface CreateCycleAssessmentRequest {
    /**
     * 周期评估主键（必填 —— 使 F1 能在同一行上补判定）
     * @type {string}
     * @memberof CreateCycleAssessmentRequest
     */
    cycleId: string;
    /**
     * 第 N 次评估（每 7 次触发）
     * @type {number}
     * @memberof CreateCycleAssessmentRequest
     */
    sequenceNo: number;
    /**
     * 
     * @type {CreateCycleAssessmentRequestAdherence}
     * @memberof CreateCycleAssessmentRequest
     */
    adherence: CreateCycleAssessmentRequestAdherence;
    /**
     * 应填天数（样本护栏）
     * @type {number}
     * @memberof CreateCycleAssessmentRequest
     */
    expectedDays?: number;
    /**
     * 模块分 M1–M5（各 0–16）
     * @type {{ [key: string]: number; }}
     * @memberof CreateCycleAssessmentRequest
     */
    moduleScores: { [key: string]: number; };
    /**
     * 
     * @type {string}
     * @memberof CreateCycleAssessmentRequest
     */
    bandTrendNote?: string;
    /**
     * 断言位（可空 = 服务端填；非空须逐字等于服务端指纹）
     * @type {string}
     * @memberof CreateCycleAssessmentRequest
     */
    thresholdVersion?: string;
}
/**
 * 依从四维（与 F1 同一形状 —— 两侧对同一事实不得有两套字段名）
 * @export
 * @interface CreateCycleAssessmentRequestAdherence
 */
export interface CreateCycleAssessmentRequestAdherence {
    /**
     * 
     * @type {number}
     * @memberof CreateCycleAssessmentRequestAdherence
     */
    expectedDays?: number;
    /**
     * 
     * @type {{ [key: string]: CreateVerdictRequestAdherenceDimensionsValue; }}
     * @memberof CreateCycleAssessmentRequestAdherence
     */
    dimensions?: { [key: string]: CreateVerdictRequestAdherenceDimensionsValue; };
}
/**
 * 
 * @export
 * @interface CreateRefund200Response
 */
export interface CreateRefund200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof CreateRefund200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof CreateRefund200Response
     */
    message: string;
    /**
     * 
     * @type {RefundData}
     * @memberof CreateRefund200Response
     */
    data?: RefundData;
    /**
     * 
     * @type {string}
     * @memberof CreateRefund200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface CreateRefundReceipt200Response
 */
export interface CreateRefundReceipt200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof CreateRefundReceipt200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof CreateRefundReceipt200Response
     */
    message: string;
    /**
     * 
     * @type {RefundReceiptData}
     * @memberof CreateRefundReceipt200Response
     */
    data?: RefundReceiptData;
    /**
     * 
     * @type {string}
     * @memberof CreateRefundReceipt200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface CreateScreeningRecord200Response
 */
export interface CreateScreeningRecord200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof CreateScreeningRecord200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof CreateScreeningRecord200Response
     */
    message: string;
    /**
     * 
     * @type {ScreeningData}
     * @memberof CreateScreeningRecord200Response
     */
    data?: ScreeningData;
    /**
     * 
     * @type {string}
     * @memberof CreateScreeningRecord200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface CreateVerdict200Response
 */
export interface CreateVerdict200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof CreateVerdict200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof CreateVerdict200Response
     */
    message: string;
    /**
     * 
     * @type {VerdictData}
     * @memberof CreateVerdict200Response
     */
    data?: VerdictData;
    /**
     * 
     * @type {string}
     * @memberof CreateVerdict200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface CreateVerdictRequest
 */
export interface CreateVerdictRequest {
    /**
     * 客户（用于历史查询与越权判定）
     * @type {string}
     * @memberof CreateVerdictRequest
     */
    customerId: string;
    /**
     * 第 N 次评估
     * @type {number}
     * @memberof CreateVerdictRequest
     */
    sequenceNo: number;
    /**
     * 基线模块总分；缺失 ⇒ 挂起（D5）
     * @type {number}
     * @memberof CreateVerdictRequest
     */
    baseTotal?: number;
    /**
     * 复评模块总分；缺失 ⇒ 挂起（D5）
     * @type {number}
     * @memberof CreateVerdictRequest
     */
    currentTotal?: number;
    /**
     * 
     * @type {CreateVerdictRequestSameOrigin}
     * @memberof CreateVerdictRequest
     */
    sameOrigin: CreateVerdictRequestSameOrigin;
    /**
     * 
     * @type {CreateVerdictRequestAdherence}
     * @memberof CreateVerdictRequest
     */
    adherence: CreateVerdictRequestAdherence;
    /**
     * 🛑 缺失 ⇒ 未定（不得默认「无」—— 那会让 D4 该触发而不触发）
     * @type {string}
     * @memberof CreateVerdictRequest
     */
    riskFlag: CreateVerdictRequestRiskFlagEnum;
    /**
     * 经络师结构化录入；缺失 ⇒ 挂起
     * @type {boolean}
     * @memberof CreateVerdictRequest
     */
    coreMetricImproved: boolean;
    /**
     * 
     * @type {CreateVerdictRequestConfidence}
     * @memberof CreateVerdictRequest
     */
    confidence: CreateVerdictRequestConfidence;
    /**
     * 模块分 M1–M5（各 0–16）
     * @type {{ [key: string]: number; }}
     * @memberof CreateVerdictRequest
     */
    moduleScores: { [key: string]: number; };
    /**
     * 手环趋势说明（U-15 四条备注语义的载体）
     * @type {string}
     * @memberof CreateVerdictRequest
     */
    bandTrendNote?: string;
    /**
     * 🛑【断言位，不是取值位】可空（空 = 服务端按当前口径填）；
     * 非空时必须逐字等于服务端算出的内容寻址指纹，否则拒写 4001。
     * 
     * @type {string}
     * @memberof CreateVerdictRequest
     */
    thresholdVersion?: string;
}


/**
 * @export
 */
export const CreateVerdictRequestRiskFlagEnum = {
    : '无',
    2: '高危',
    3: '新发',
    4: '同病'
} as const;
export type CreateVerdictRequestRiskFlagEnum = typeof CreateVerdictRequestRiskFlagEnum[keyof typeof CreateVerdictRequestRiskFlagEnum];

/**
 * 依从四维（键为 A1/A3/A4；🛑 A2 永不参与 AS）
 * @export
 * @interface CreateVerdictRequestAdherence
 */
export interface CreateVerdictRequestAdherence {
    /**
     * 
     * @type {number}
     * @memberof CreateVerdictRequestAdherence
     */
    expectedDays?: number;
    /**
     * 
     * @type {{ [key: string]: CreateVerdictRequestAdherenceDimensionsValue; }}
     * @memberof CreateVerdictRequestAdherence
     */
    dimensions?: { [key: string]: CreateVerdictRequestAdherenceDimensionsValue; };
}
/**
 * 
 * @export
 * @interface CreateVerdictRequestAdherenceDimensionsValue
 */
export interface CreateVerdictRequestAdherenceDimensionsValue {
    /**
     * 
     * @type {boolean}
     * @memberof CreateVerdictRequestAdherenceDimensionsValue
     */
    applicable?: boolean;
    /**
     * 
     * @type {number}
     * @memberof CreateVerdictRequestAdherenceDimensionsValue
     */
    value?: number;
    /**
     * 
     * @type {boolean}
     * @memberof CreateVerdictRequestAdherenceDimensionsValue
     */
    structuralMissing?: boolean;
}
/**
 * 置信度四项因子
 * @export
 * @interface CreateVerdictRequestConfidence
 */
export interface CreateVerdictRequestConfidence {
    /**
     * 
     * @type {string}
     * @memberof CreateVerdictRequestConfidence
     */
    sameOriginStatus?: string;
    /**
     * 
     * @type {number}
     * @memberof CreateVerdictRequestConfidence
     */
    answeredCount?: number;
    /**
     * 
     * @type {number}
     * @memberof CreateVerdictRequestConfidence
     */
    expectedDays?: number;
    /**
     * 
     * @type {number}
     * @memberof CreateVerdictRequestConfidence
     */
    mcidDelta?: number;
}
/**
 * 同源断言（三项；任一项缺省 = 不成立 = 不可比 ⇒ 挂起）
 * @export
 * @interface CreateVerdictRequestSameOrigin
 */
export interface CreateVerdictRequestSameOrigin {
    /**
     * 
     * @type {boolean}
     * @memberof CreateVerdictRequestSameOrigin
     */
    sameItemGroup?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof CreateVerdictRequestSameOrigin
     */
    rangeMatches?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof CreateVerdictRequestSameOrigin
     */
    sameMeasurer?: boolean;
}
/**
 * 
 * @export
 * @interface CreateVisit200Response
 */
export interface CreateVisit200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof CreateVisit200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof CreateVisit200Response
     */
    message: string;
    /**
     * 
     * @type {VisitData}
     * @memberof CreateVisit200Response
     */
    data?: VisitData;
    /**
     * 
     * @type {string}
     * @memberof CreateVisit200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface CustomerCreateData
 */
export interface CustomerCreateData {
    /**
     * 
     * @type {string}
     * @memberof CustomerCreateData
     */
    customerId?: string;
    /**
     * 5 值粗粒度派生聚合态，不是服务主状态机（14 态见 customer_state_transition）
     * @type {string}
     * @memberof CustomerCreateData
     */
    status?: CustomerCreateDataStatusEnum;
    /**
     * 客户不下发
     * @type {string}
     * @memberof CustomerCreateData
     */
    ownerStoreId?: string;
    /**
     * 客户不下发
     * @type {string}
     * @memberof CustomerCreateData
     */
    servingStoreId?: string;
}


/**
 * @export
 */
export const CustomerCreateDataStatusEnum = {
    Created: 'CREATED',
    Profiled: 'PROFILED',
    Consented: 'CONSENTED',
    Rejected: 'REJECTED',
    Archived: 'ARCHIVED'
} as const;
export type CustomerCreateDataStatusEnum = typeof CustomerCreateDataStatusEnum[keyof typeof CustomerCreateDataStatusEnum];

/**
 * 
 * @export
 * @interface CustomerCreateRequest
 */
export interface CustomerCreateRequest {
    /**
     * 
     * @type {string}
     * @memberof CustomerCreateRequest
     */
    name: string;
    /**
     * 
     * @type {string}
     * @memberof CustomerCreateRequest
     */
    gender: CustomerCreateRequestGenderEnum;
    /**
     * 
     * @type {number}
     * @memberof CustomerCreateRequest
     */
    age: number;
    /**
     * 租户内唯一（跨店识别键）
     * @type {string}
     * @memberof CustomerCreateRequest
     */
    phone: string;
    /**
     * 服务端校验其 result=通过
     * @type {string}
     * @memberof CustomerCreateRequest
     */
    screeningId: string;
}


/**
 * @export
 */
export const CustomerCreateRequestGenderEnum = {
    : '男',
    2: '女'
} as const;
export type CustomerCreateRequestGenderEnum = typeof CustomerCreateRequestGenderEnum[keyof typeof CustomerCreateRequestGenderEnum];

/**
 * 
 * @export
 * @interface CustomerDetailData
 */
export interface CustomerDetailData {
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    customerId?: string;
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    name?: string;
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    gender?: string;
    /**
     * 
     * @type {number}
     * @memberof CustomerDetailData
     */
    age?: number;
    /**
     * 
     * @type {object}
     * @memberof CustomerDetailData
     */
    intakeProfile?: object;
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    screeningResult?: string;
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    bandWillingness?: CustomerDetailDataBandWillingnessEnum;
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    ownerStoreId?: string;
    /**
     * 
     * @type {string}
     * @memberof CustomerDetailData
     */
    servingStoreId?: string;
    /**
     * 派生字段；客户 token 请求 include=verdict → 403 VISIBILITY_DENIED
     * @type {string}
     * @memberof CustomerDetailData
     */
    effectVerdict?: string;
    /**
     * 派生字段；客户恒不下发
     * @type {number}
     * @memberof CustomerDetailData
     */
    asValue?: number;
}


/**
 * @export
 */
export const CustomerDetailDataBandWillingnessEnum = {
    : '自愿佩戴',
    2: '暂不佩戴'
} as const;
export type CustomerDetailDataBandWillingnessEnum = typeof CustomerDetailDataBandWillingnessEnum[keyof typeof CustomerDetailDataBandWillingnessEnum];

/**
 * 
 * @export
 * @interface DailyReportData
 */
export interface DailyReportData {
    /**
     * 
     * @type {string}
     * @memberof DailyReportData
     */
    reportId?: string;
    /**
     * 
     * @type {string}
     * @memberof DailyReportData
     */
    date?: string;
    /**
     * 本周 X/7 天（正向计数）
     * @type {number}
     * @memberof DailyReportData
     */
    weeklyCount?: number;
    /**
     * 
     * @type {object}
     * @memberof DailyReportData
     */
    lastWeekCompare?: object;
    /**
     * 
     * @type {string}
     * @memberof DailyReportData
     */
    suggestion?: string;
    /**
     * 
     * @type {string}
     * @memberof DailyReportData
     */
    source?: string;
}
/**
 * 
 * @export
 * @interface DailyReportRequest
 */
export interface DailyReportRequest {
    /**
     * 补填窗口 ≤2 天（可配置）
     * @type {string}
     * @memberof DailyReportRequest
     */
    date: string;
    /**
     * 全点选，无开放输入
     * @type {object}
     * @memberof DailyReportRequest
     */
    answersJson: object;
    /**
     * 代录须标 代核
     * @type {string}
     * @memberof DailyReportRequest
     */
    source: DailyReportRequestSourceEnum;
}


/**
 * @export
 */
export const DailyReportRequestSourceEnum = {
    : '客户',
    2: '代核'
} as const;
export type DailyReportRequestSourceEnum = typeof DailyReportRequestSourceEnum[keyof typeof DailyReportRequestSourceEnum];


/**
 * 
 * @export
 */
export const Dimension = {
    : '体能精力',
    2: '面部气色肤质',
    3: '肩颈腰背筋骨',
    4: '睡眠质量',
    5: '记忆专注',
    6: '代谢体态消化',
    7: '情绪抗压与抵抗力'
} as const;
export type Dimension = typeof Dimension[keyof typeof Dimension];

/**
 * 
 * @export
 * @interface GetBandDerived200Response
 */
export interface GetBandDerived200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof GetBandDerived200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof GetBandDerived200Response
     */
    message: string;
    /**
     * 
     * @type {BandDerivedData}
     * @memberof GetBandDerived200Response
     */
    data?: BandDerivedData;
    /**
     * 
     * @type {string}
     * @memberof GetBandDerived200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface GetBandTelemetry200Response
 */
export interface GetBandTelemetry200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof GetBandTelemetry200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof GetBandTelemetry200Response
     */
    message: string;
    /**
     * 
     * @type {BandTelemetryData}
     * @memberof GetBandTelemetry200Response
     */
    data?: BandTelemetryData;
    /**
     * 
     * @type {string}
     * @memberof GetBandTelemetry200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface GetCustomer200Response
 */
export interface GetCustomer200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof GetCustomer200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof GetCustomer200Response
     */
    message: string;
    /**
     * 
     * @type {CustomerDetailData}
     * @memberof GetCustomer200Response
     */
    data?: CustomerDetailData;
    /**
     * 
     * @type {string}
     * @memberof GetCustomer200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface ListStores200Response
 */
export interface ListStores200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof ListStores200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof ListStores200Response
     */
    message: string;
    /**
     * 
     * @type {StoreListData}
     * @memberof ListStores200Response
     */
    data?: StoreListData;
    /**
     * 
     * @type {string}
     * @memberof ListStores200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface LoginData
 */
export interface LoginData {
    /**
     * 
     * @type {string}
     * @memberof LoginData
     */
    token?: string;
    /**
     * 
     * @type {number}
     * @memberof LoginData
     */
    expiresIn?: number;
    /**
     * 客户=client / 调理师=therapist / 经络师=meridian / 门店负责人=manager / 区域督导=area / 总部运营=hq
     * @type {string}
     * @memberof LoginData
     */
    role?: LoginDataRoleEnum;
    /**
     * 
     * @type {string}
     * @memberof LoginData
     */
    clientEnd?: LoginDataClientEndEnum;
    /**
     * 
     * @type {string}
     * @memberof LoginData
     */
    tenantId?: string;
    /**
     * 客户无 staff_id（差异项）
     * @type {string}
     * @memberof LoginData
     */
    staffId?: string;
}


/**
 * @export
 */
export const LoginDataRoleEnum = {
    Client: 'client',
    Therapist: 'therapist',
    Meridian: 'meridian',
    Manager: 'manager',
    Area: 'area',
    Hq: 'hq'
} as const;
export type LoginDataRoleEnum = typeof LoginDataRoleEnum[keyof typeof LoginDataRoleEnum];

/**
 * @export
 */
export const LoginDataClientEndEnum = {
    Mp: 'mp',
    App: 'app',
    Web: 'web'
} as const;
export type LoginDataClientEndEnum = typeof LoginDataClientEndEnum[keyof typeof LoginDataClientEndEnum];

/**
 * 
 * @export
 * @interface RefundCreateRequest
 */
export interface RefundCreateRequest {
    /**
     * 
     * @type {string}
     * @memberof RefundCreateRequest
     */
    customerId: string;
    /**
     * 
     * @type {string}
     * @memberof RefundCreateRequest
     */
    entry: RefundCreateRequestEntryEnum;
    /**
     * 
     * @type {string}
     * @memberof RefundCreateRequest
     */
    refundRoute: RefundCreateRequestRefundRouteEnum;
    /**
     * 必填；未记录原因不可结案
     * @type {string}
     * @memberof RefundCreateRequest
     */
    reasonCode: RefundCreateRequestReasonCodeEnum;
    /**
     * 最早且可核实
     * @type {string}
     * @memberof RefundCreateRequest
     */
    requestedAt: string;
    /**
     * 客户主张，仅留存不计时
     * @type {string}
     * @memberof RefundCreateRequest
     */
    requestedAtClaimed?: string;
    /**
     * append-only 原话
     * @type {string}
     * @memberof RefundCreateRequest
     */
    customerStatement?: string;
}


/**
 * @export
 */
export const RefundCreateRequestEntryEnum = {
    A: 'A门店代录',
    B: 'B首周期'
} as const;
export type RefundCreateRequestEntryEnum = typeof RefundCreateRequestEntryEnum[keyof typeof RefundCreateRequestEntryEnum];

/**
 * @export
 */
export const RefundCreateRequestRefundRouteEnum = {
    : '履约类',
    2: '效果类'
} as const;
export type RefundCreateRequestRefundRouteEnum = typeof RefundCreateRequestRefundRouteEnum[keyof typeof RefundCreateRequestRefundRouteEnum];

/**
 * @export
 */
export const RefundCreateRequestReasonCodeEnum = {
    : '效果未达预期',
    2: '症状加重或出现新不适',
    3: '服务体验或沟通问题',
    4: '时间·经济·家庭原因',
    5: '配合度不足导致无明显变化',
    6: '信任或价格异议'
} as const;
export type RefundCreateRequestReasonCodeEnum = typeof RefundCreateRequestReasonCodeEnum[keyof typeof RefundCreateRequestReasonCodeEnum];

/**
 * 
 * @export
 * @interface RefundData
 */
export interface RefundData {
    /**
     * 
     * @type {string}
     * @memberof RefundData
     */
    refundId?: string;
    /**
     * 
     * @type {string}
     * @memberof RefundData
     */
    liableStoreId?: string;
    /**
     * 
     * @type {string}
     * @memberof RefundData
     */
    slaDueAt?: string;
    /**
     * 
     * @type {number}
     * @memberof RefundData
     */
    recordingDelayH?: number;
    /**
     * 
     * @type {string}
     * @memberof RefundData
     */
    outcome?: RefundDataOutcomeEnum;
}


/**
 * @export
 */
export const RefundDataOutcomeEnum = {
    : '继续',
    2: '终止',
    3: '归档'
} as const;
export type RefundDataOutcomeEnum = typeof RefundDataOutcomeEnum[keyof typeof RefundDataOutcomeEnum];

/**
 * 
 * @export
 * @interface RefundReceiptData
 */
export interface RefundReceiptData {
    /**
     * 三态留痕
     * @type {string}
     * @memberof RefundReceiptData
     */
    receiptState?: RefundReceiptDataReceiptStateEnum;
}


/**
 * @export
 */
export const RefundReceiptDataReceiptStateEnum = {
    : '已推送',
    2: '未授权（转线下）',
    3: '推送失败'
} as const;
export type RefundReceiptDataReceiptStateEnum = typeof RefundReceiptDataReceiptStateEnum[keyof typeof RefundReceiptDataReceiptStateEnum];

/**
 * 
 * @export
 * @interface ResultEnvelope
 */
export interface ResultEnvelope {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof ResultEnvelope
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof ResultEnvelope
     */
    message: string;
    /**
     * code != 0 时为空
     * @type {object}
     * @memberof ResultEnvelope
     */
    data?: object | null;
    /**
     * 
     * @type {string}
     * @memberof ResultEnvelope
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface ScreeningCreateRequest
 */
export interface ScreeningCreateRequest {
    /**
     * 
     * @type {string}
     * @memberof ScreeningCreateRequest
     */
    customerId: string;
    /**
     * 含 pregnancy / acute / risk_history / nonmedical_disclosed
     * @type {object}
     * @memberof ScreeningCreateRequest
     */
    itemsJson: object;
    /**
     * 服务端从 token 覆写
     * @type {string}
     * @memberof ScreeningCreateRequest
     */
    operatorId: string;
    /**
     * 
     * @type {string}
     * @memberof ScreeningCreateRequest
     */
    customerSign?: string;
    /**
     * 
     * @type {string}
     * @memberof ScreeningCreateRequest
     */
    operatorSign?: string;
}
/**
 * 
 * @export
 * @interface ScreeningData
 */
export interface ScreeningData {
    /**
     * 
     * @type {string}
     * @memberof ScreeningData
     */
    screeningId?: string;
    /**
     * 
     * @type {string}
     * @memberof ScreeningData
     */
    result?: ScreeningDataResultEnum;
    /**
     * 
     * @type {string}
     * @memberof ScreeningData
     */
    submittedAt?: string;
}


/**
 * @export
 */
export const ScreeningDataResultEnum = {
    : '通过',
    2: '不通过'
} as const;
export type ScreeningDataResultEnum = typeof ScreeningDataResultEnum[keyof typeof ScreeningDataResultEnum];

/**
 * 
 * @export
 * @interface Store
 */
export interface Store {
    /**
     * 
     * @type {string}
     * @memberof Store
     */
    storeId?: string;
    /**
     * 
     * @type {string}
     * @memberof Store
     */
    name?: string;
    /**
     * 
     * @type {string}
     * @memberof Store
     */
    franchiseType?: StoreFranchiseTypeEnum;
}


/**
 * @export
 */
export const StoreFranchiseTypeEnum = {
    : '直营',
    2: '加盟'
} as const;
export type StoreFranchiseTypeEnum = typeof StoreFranchiseTypeEnum[keyof typeof StoreFranchiseTypeEnum];

/**
 * 
 * @export
 * @interface StoreListData
 */
export interface StoreListData {
    /**
     * 
     * @type {Array<Store>}
     * @memberof StoreListData
     */
    items?: Array<Store>;
    /**
     * 
     * @type {number}
     * @memberof StoreListData
     */
    total?: number;
    /**
     * 
     * @type {number}
     * @memberof StoreListData
     */
    page?: number;
    /**
     * 
     * @type {number}
     * @memberof StoreListData
     */
    pageSize?: number;
}
/**
 * 
 * @export
 * @interface SubmitBaselineAssessment200Response
 */
export interface SubmitBaselineAssessment200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof SubmitBaselineAssessment200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof SubmitBaselineAssessment200Response
     */
    message: string;
    /**
     * 
     * @type {BaselineAssessmentData}
     * @memberof SubmitBaselineAssessment200Response
     */
    data?: BaselineAssessmentData;
    /**
     * 
     * @type {string}
     * @memberof SubmitBaselineAssessment200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface SubmitDailyReport200Response
 */
export interface SubmitDailyReport200Response {
    /**
     * 0 = 成功；非 0 见 x-error-codes
     * @type {number}
     * @memberof SubmitDailyReport200Response
     */
    code: number;
    /**
     * 面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
     * @type {string}
     * @memberof SubmitDailyReport200Response
     */
    message: string;
    /**
     * 
     * @type {DailyReportData}
     * @memberof SubmitDailyReport200Response
     */
    data?: DailyReportData;
    /**
     * 
     * @type {string}
     * @memberof SubmitDailyReport200Response
     */
    traceId: string;
}
/**
 * 
 * @export
 * @interface VerdictData
 */
export interface VerdictData {
    /**
     * 
     * @type {string}
     * @memberof VerdictData
     */
    verdictId?: string;
    /**
     * 
     * @type {string}
     * @memberof VerdictData
     */
    branch?: VerdictDataBranchEnum;
    /**
     * 
     * @type {number}
     * @memberof VerdictData
     */
    confidence?: number;
    /**
     * 
     * @type {object}
     * @memberof VerdictData
     */
    evidenceSnapshot?: object;
    /**
     * 
     * @type {string}
     * @memberof VerdictData
     */
    thresholdVersion?: string;
    /**
     * 
     * @type {string}
     * @memberof VerdictData
     */
    decidedAt?: string;
}


/**
 * @export
 */
export const VerdictDataBranchEnum = {
    : '稳定',
    2: '依从不足',
    3: '达标无效',
    4: '全面评估',
    5: '人工复核'
} as const;
export type VerdictDataBranchEnum = typeof VerdictDataBranchEnum[keyof typeof VerdictDataBranchEnum];

/**
 * 
 * @export
 * @interface VisitData
 */
export interface VisitData {
    /**
     * 
     * @type {string}
     * @memberof VisitData
     */
    visitId?: string;
    /**
     * 
     * @type {number}
     * @memberof VisitData
     */
    visitNo?: number;
    /**
     * 
     * @type {string}
     * @memberof VisitData
     */
    executedAt?: string;
    /**
     * 客户端可见（标记服务门店）
     * @type {string}
     * @memberof VisitData
     */
    servingStoreId?: string;
    /**
     * 客户不下发
     * @type {object}
     * @memberof VisitData
     */
    gateCheckJson?: object;
    /**
     * 
     * @type {boolean}
     * @memberof VisitData
     */
    customerConfirmed?: boolean;
    /**
     * 客户不下发
     * @type {string}
     * @memberof VisitData
     */
    abnormalNote?: string;
}
