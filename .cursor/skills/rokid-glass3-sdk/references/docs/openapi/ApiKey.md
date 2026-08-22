# 获取 API Key

## 1. 准备

使用 Rokid OpenAPI 前，请先开通 Rokid 平台企业账号，并获取 API Key 作为鉴权凭证。

## 2. 获取 API Key

请联系对接的销售或商务，通过 OA 申请开通企业账号并获取 API Key。本文涉及的 OpenAPI 鉴权凭证统一称为 API Key。

## 3. 使用 API Key

* 调用接口时，需要在请求头中携带 API Key 进行鉴权。

| 请求头字段 | value |
| --- | --- |
| Content-Type | application/json |
| Authorization | `Bearer <API_KEY>` |

* 请勿以任何方式公开 API Key，避免因未经授权的使用导致安全风险或资金损失。

## 4. API Key 时效性说明

创建的 API Key 具有失效时间，申请时可指定有效期，默认有效期为 1 个月。

## 常见问题
- Q：单个企业账号下最多能创建多少个 API Key？
- A：暂时不受限制，但是建议每个应用或服务只使用一个 API Key，避免密钥泄露风险。

- Q：公司注销后，其申请的 API Key 是否依然可用？
- A：在公司注销后，其申请的所有 API Key 均将失效，无法再用于 OpenAPI 调用。
