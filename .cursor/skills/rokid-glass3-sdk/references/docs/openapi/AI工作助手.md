# AI工作助手

## 1. 准备

**基础域名**：`https://api.rokid.com`

**准备**：
- 确保已经获取到了有效的 API 密钥（`API_KEY`），用于身份验证。
- 确保企业下已经存在 AI 工作助手任务记录，并且任务已生成工作文档，便于查询和下载。

## 2. 功能介绍

AI 工作助手模块当前提供工作任务记录分页查询能力，帮助开发者按用户、任务名称、执行时间范围查询工作记录结果。

### 2.1 分页查询 AI 助手工作任务记录

**接口地址**：`https://api.rokid.com/ar/flow/openapi/record/page?uid={$uid}&pageNum={$pageNum}&pageSize={$pageSize}&taskName={$taskName}&gmtStart={$gmtStart}&gmtEnd={$gmtEnd}`

**请求方式**：`GET`

**请求数据类型**：`application/x-www-form-urlencoded`

**响应数据类型**：`*/*`

**接口描述**：根据用户 ID、任务名称、执行时间范围分页查询 AI 助手工作任务记录。

**请求参数**:

| **参数名称** | **参数说明** | **是否必须** | **数据类型** |
| --- | --- | --- | --- |
| uid | 用户 ID | true | string |
| pageNum | 页码，默认值 `1` | false | int |
| pageSize | 每页数量，默认值 `10` | false | int |
| taskName | 任务名称，模糊匹配 | false | string |
| gmtStart | 执行开始时间，格式 `yyyy-MM-dd HH:mm:ss` | false | string |
| gmtEnd | 执行结束时间，格式 `yyyy-MM-dd HH:mm:ss` | false | string |

**输出结果：**

| **参数名称** | **参数说明** | **是否必须** | **数据类型** |
| --- | --- | --- | --- |
| pageNum | 当前页码 | true | int |
| pageSize | 每页数量 | true | int |
| total | 总记录数 | true | long |
| list | 工作记录列表 | true | array |
| taskId | 工作任务 ID | true | string |
| taskName | 任务名称 | false | string |
| sessionId | 会话 ID | false | string |
| planId | 计划 ID | false | string |
| ticketId | 工单 ID | false | string |
| ticketFlowType | 工单流程类型 | false | string |
| companyId | 企业 ID | false | string |
| uid | 用户 ID | false | string |
| userName | 用户名称 | false | string |
| chatContent | 会话内容 | false | string |
| result | 任务结果 | false | string |
| taskProgress | 任务进度：`0` 未开始，`1` 进行中，`2` 报告生成中，`3` 任务异常，`4` 已完成，`5` 待签字 | false | int |
| videoUrl | 视频地址 | false | string |
| uploadTime | 上传时间 | false | string |
| gmtCreated | 创建时间 | false | string |
| gmtModified | 更新时间 | false | string |
| gmtCreatedStart | 开始时间 | false | string |
| gmtCreatedEnd | 结束时间 | false | string |
| deleted | 删除标识：`0` 未删除，`1` 已删除 | false | int |
| signUrl | 签名地址 | false | string |
| flowType | 流程类型 | false | string |
| reportUrl | 工作文档地址 | false | string |

**请求示例：**

```bash
curl -X GET "https://api.rokid.com/ar/flow/openapi/record/page?uid=E9985178CD6C4207BDB30608FF90B909&pageNum=1&pageSize=10&taskName=%E5%B7%A1%E6%A3%80&gmtStart=2026-06-01%2000:00:00&gmtEnd=2026-06-17%2023:59:59" \
-H "Authorization: Bearer $API_KEY"
```

**响应示例**:

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "pageNum": 1,
    "pageSize": 10,
    "total": 1,
    "list": [
      {
        "taskId": "0c0d7fc3f4a04b9caf2f4a534c6b5b10",
        "taskName": "日常巡检",
        "sessionId": "conversation_001",
        "planId": "plan_001",
        "ticketId": "ticket_001",
        "ticketFlowType": "custom",
        "companyId": "7A15F74ED76542678825CA7DE5F18E03",
        "uid": "E9985178CD6C4207BDB30608FF90B909",
        "userName": "张三",
        "chatContent": "今日巡检任务已完成",
        "result": "{}",
        "taskProgress": 4,
        "videoUrl": "",
        "uploadTime": "",
        "gmtCreated": "2026-06-17T10:00:00.000+08:00",
        "gmtModified": "2026-06-17T10:30:00.000+08:00",
        "gmtCreatedStart": "2026-06-17T10:00:00.000+08:00",
        "gmtCreatedEnd": "2026-06-17T10:30:00.000+08:00",
        "deleted": 0,
        "signUrl": "",
        "flowType": "custom",
        "reportUrl": "https://cdn.rokid.com/reports/work-record-001.docx"
      }
    ]
  },
  "success": true
}
```
