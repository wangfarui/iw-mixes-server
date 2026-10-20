# 实际业务验收场景

所有业务场景初始未执行。API通过不能替代UI、SQL或并发通过。每个场景结果记录执行时间、仓库HEAD+未提交差异、环境、角色/runId、前置、请求/页面、预期/实际、脱敏证据、结论与修复/复验。

## 先准备角色与金额基准

A=家庭G群主，B=家长，C=普通成员，D=儿童（保留既有仅本人限制），E=无家庭，后续可使用独立H。登录凭据仅安全输入/environment，不写入本文件、cases.json或证据。角色接口以当前服务返回为准，runner会校验五个真实身份，不能借用生产账号凑场景。

独立空月份、同名A私人/B私人/G家庭分类（可用工具的验收前缀隔离），A私人/G家庭同名支出标签，B私人标签，以及同名收入/支出标签。保存实际生成ID，不硬编码生产ID。先完成不破坏夹具的管理与统计，再独立runId测试退群/删除/换家庭。

| 单号 | 记录人 | 付款人 | 共享 | 金额 |
|---|---|---|---|---|
| R1 | A | A | 否 | 100 |
| R2 | A | A | 是 | 200 |
| R3 | A | B | 是 | 80 |
| R4 | B | B | 是 | 300 |
| R5 | B | B | 否 | 50 |

同一分类名称：A个人300，B个人430，A家庭680，B家庭630，C家庭580。将A私人分类改名后新建R6(A记录/A付款/共享20)：R1-R3原ID/名不变；新名20，旧名A家庭680/B家庭630；A个人300+20，B个人430。预算按付款人A300/B430。不要为凑金额更改权限，先核对queryOnlyMyself与家庭偏好。

## 实际合同及页面

所有HTTP仍沿统一包装code/message/data与iwtoken。字典管理 `/bookkeeping-service/bookkeeping/dictionaries`；类型CATEGORY/EXPENSE_TAG/INCOME_TAG，范围PERSONAL/FAMILY。管理items响应items/canManage/scopeVersion/familyGroupId，item是id/name/enabled/sort/revision；改名返回oldId/item；copy返回created item[]/skipped ID[]；排序路径是 **PUT /order**。

新增账单recordTypeId与recordTags；更新仅dictionaryChanges.categoryId/tagIds（属性缺省保持、categoryId=null与tagIds=[]清空）。历史VO recordTypeId/recordTypeName/recordTagSnapshots[{id,type,name}]/dictionarySnapshotStatus。筛选categoryNames/tagNames（标签OR），分类状态categoryState=UNCATEGORIZED/PENDING，状态与名称互斥；绝不把真实名称“未分类”当作空分类。

小程序入口菜单→财务记账→记账设置，pagesBookkeeping/bookkeeping/bookkeeping-settings，三管理页bookkeeping-category-settings / bookkeeping-consume-tag-settings / bookkeeping-income-tag-settings。其他重点页面bookkeeping-action（普通）、bookkeeping-quick、bookkeeping-action-edit/setting、bookkeeping-records/detail、bookkeeping-budget、consume/income-statistics、年度统计及pagesPoints/points/task-plan-add、任务详情。原快捷页标题“快捷设置”；无使用位置管理捷径。

Web `/bookkeeping` 列表/详情及历史名筛选；`/dict`和旧编辑深链接禁维护三类；不新增Web设置页/记账录入产品。预算类型1月总/2年总/11月分类/21年分类。任务模板schema2由服务端生成，账单收支类型仍不允许修改；导入整体事务且沿旧语义不联动钱包。

## 工具与执行顺序

1. 用户先完成MIGRATION-RUNBOOK全部步骤；新会话授权后启动。后端只能用scripts/local-backend.sh，先读usage；微信使用开发者工具，Web本地服务+浏览器。
2. 核心API runner见scripts/acceptance/bookkeeping-dictionary-v2/README.md。五角色token、独立空月、runId、显式--execute与--dedicated-test-accounts必需，test远程需--allow-host。先运行它建立R1-R6，再用保留夹具看UI；完整复验使用新空月/runId。
3. runner只覆盖下面标注的子断言，SKIPPED_PREVIOUS不是本次通过。未覆盖的权限并发、模板幂等/删除重建、预算结算、快捷/语音供应商、导入及UI逐项另验。预算结算只能对测试周期隔离验证，不能对真实用户重复结算。
4. SQL场景由用户的00/04证据支撑，runner不连数据库。故意歧义/类型错误的旧数据库夹具只能在独立测试库由用户确认并执行，不在生产构造。
5. 对每项记录实际值与证据，再更新RESULTS。缺账号/环境/供应商/工具时写阻塞或未执行，不能用静态检查替代运行结论。

## 逐项步骤与断言

### UI-01 (P0; UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：从菜单进入财务记账。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/items`；`GET /auth-service/dict/getDictTypeList`。

通过条件：有“记账设置”，其中恰好三项；顺序为分类、支出标签、收入标签

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### UI-02 (P1; UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：浏览账单页、录入、快捷、语音、预算、模板、家庭详情。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/items`；`GET /auth-service/dict/getDictTypeList`。

通过条件：未新增上述位置的快捷管理入口；原快捷设置页面标题为“快捷设置”

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### UI-03 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：进入原通用字典管理及三类旧详情深链接。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/items`；`GET /auth-service/dict/getDictTypeList`。

通过条件：三类不再可维护；其他类型仍正常；直接调旧写接口也失败

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### UI-04 (P1; UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：E 无家庭；A/B/C/D 有家庭。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/items`；`GET /auth-service/dict/getDictTypeList`。

通过条件：E 只有我的；A/B 家庭可管理；C/D 家庭只读且无管理按钮

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### UI-05 (P1; UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：长名称、空白、32/33 码点、emoji、全部停用/删除。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/items`；`GET /auth-service/dict/getDictTypeList`。

通过条件：校验与后端一致；空态可用；无层级/编码字段；弹窗及底部安全区正常

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### UI-06 (P1; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：排序后刷新、切范围、重进。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/items`；`GET /auth-service/dict/getDictTypeList`。

通过条件：顺序稳定且只影响对应范围；状态与字典 ID 正确

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### MG-01 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：A/B 维护 G；C/D/E 伪造管理请求。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：A/B 成功；C/D/E 拒绝；不能通过指定其他家庭 ID 越权

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### MG-02 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：同范围重名、跨范围同名、停用后同名新增。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：同范围有效项拒绝重名，停用仍占名；跨范围允许

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### MG-03 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：将“餐饮”改成“用餐”，随后重复提交旧版本。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：原行逻辑删除且原名不变；只有一个新版本；过期提交冲突，无分叉

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### MG-04 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：不改名称保存、改排序、启停。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：ID 不变；revision 更新规则一致；旧已替换项不可启用

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### MG-05 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：两人同时改名/删改/复制同名；并发排序。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：无重复有效项/多分支；过期操作冲突明确；事务失败不把旧项单独删掉

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### MG-06 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：字典写入期间降级角色/退群。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：不能在失去权限后凭旧列表继续写；事务和成员状态校验有证据

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### MG-07 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：旧通用批量/管理员同步/改类型/repair。

实际目标：`GET/POST /bookkeeping-service/bookkeeping/dictionaries/items`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/name`；`PUT /bookkeeping-service/bookkeeping/dictionaries/items/{id}/status`；`PUT /bookkeeping-service/bookkeeping/dictionaries/order`；`DELETE /bookkeeping-service/bookkeeping/dictionaries/items/{id}?revision=`；`POST/PUT/DELETE /auth-service/dict/{add|update|delete}`。

通过条件：不可绕过三类版本管理，不能复活已删除项或覆盖历史名称

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### OP-01 (P0; API, UI)

角色：A, B, C, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：A 有私人餐饮，G 有同名餐饮。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`；`POST /bookkeeping-service/bookkeeping/records/add`。

通过条件：新录入只显示一项，返回/保存 A 的个人 ID；无个人/家庭来源徽标

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### OP-02 (P0; API, UI)

角色：A, B, C, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：A 私人同名项停用。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`；`POST /bookkeeping-service/bookkeeping/records/add`。

通过条件：合并候选可使用 G 的启用项；重新启用个人项后新选择优先个人；旧账单 ID 不自动改

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### OP-03 (P0; API)

角色：A, B, C, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：C 查看选项；B 共享账单使用私人“减脂餐”。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`；`POST /bookkeeping-service/bookkeeping/records/add`。

通过条件：C 可见该账单标签快照，但 C 的可选列表没有 B 私人标签

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### OP-04 (P0; API)

角色：A, B, C, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：伪造其他人个人 ID、其他家庭 ID、错误类型、删除 ID。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`；`POST /bookkeeping-service/bookkeeping/records/add`。

通过条件：新保存拒绝，不泄露完整他人字典；不部分落库或改变钱包

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### OP-05 (P0; API, UI)

角色：A, B, C, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：A 为 B 记账。

实际目标：`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`；`POST /bookkeeping-service/bookkeeping/records/add`。

通过条件：使用 A 的候选；账单付款人仍是 B，金额归属与钱包沿用既有规则

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### HS-01 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：改名、停用、删除已使用分类/标签。

实际目标：`PUT /bookkeeping-service/bookkeeping/records/update`；`GET /bookkeeping-service/bookkeeping/records/detail?id=`；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`。

通过条件：所有既有账单列表、详情、统计仍显示原名称；来源 ID 不变

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### HS-02 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：来源失效后只编辑金额/备注/日期/附件/共享等。

实际目标：`PUT /bookkeeping-service/bookkeeping/records/update`；`GET /bookkeeping-service/bookkeeping/records/detail?id=`；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`。

通过条件：保存成功；原分类/标签快照不被清空、重映射或重查改名

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### HS-03 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：不传 dictionaryChanges；只传 categoryId；categoryId=null；tagIds=[]。

实际目标：`PUT /bookkeeping-service/bookkeeping/records/update`；`GET /bookkeeping-service/bookkeeping/records/detail?id=`；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`。

通过条件：缺省保持、局部更新、显式清空逐一正确；无意外覆盖

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### HS-04 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：保留历史标签并新增一个当前标签，再删除其中一个。

实际目标：`PUT /bookkeeping-service/bookkeeping/records/update`；`GET /bookkeeping-service/bookkeeping/records/detail?id=`；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`。

通过条件：保留标签快照原样；新增来自后端可信源；删除只作用该账单

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### HS-05 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：修改收支类型并携带旧类型标签。

实际目标：`PUT /bookkeeping-service/bookkeeping/records/update`；`GET /bookkeeping-service/bookkeeping/records/detail?id=`；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`。

通过条件：沿既有行为拒绝修改账单收支类型；因此旧类型标签不会跨类型写入。显式清空标签仍不开放收支切换。

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### HS-06 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：通过账单查询旧 source；伪造账单 ID 或任意 source。

实际目标：`PUT /bookkeeping-service/bookkeeping/records/update`；`GET /bookkeeping-service/bookkeeping/records/detail?id=`；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`。

通过条件：有账单权限可溯源逻辑删除项；无权限拒绝；源丢失仍显示账单快照

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-01 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：老用户带私人选项加入 G。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：私人值与 ID 不变；家庭项可用；不强制合并/上传私人字典

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-02 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：创建家庭、重进、删空后重进。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：默认分类只初始化一次；家庭标签初始为空；删空不重新灌模板

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-03 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：A 从自己的选项复制到家庭。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：产生独立家庭 ID；重名跳过；停用重名不自动启用；无权读取 B 全部私人项

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-04 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：B 用 G 家庭项录账后退出，A 再改名/删除该项。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：B 可访问的旧账单不变；B 新选择无 G 项；不因此保留查看 A 私人/旧家庭共享账单的额外权限

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-05 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：G 转让群主/解散，成员再加入 H。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：字典归属不转给旧创建者；历史快照不变；不能把 G 的选项带成 H 的当前候选

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-06 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：切账号/退群/重入期间延迟响应返回。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：没有旧账号/家庭选项污染，onShow 刷新；请求迟到不能覆盖新身份数据

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### FG-07 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：新用户注册与字典修复。

实际目标：`GET /auth-service/family/group/myGroup`；`POST /bookkeeping-service/bookkeeping/dictionaries/copy-personal`；`GET /bookkeeping-service/bookkeeping/dictionaries/options?type=`。

通过条件：user_id=0 的家庭项不进入个人模板复制；修复不复活主动删除的三类项

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### ST-01 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：按第 2 节基准查询个人/家庭、月/年、首页/下钻。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：数值一致；相同名称不同 ID 只有一个分类；可见集合符合基准

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### ST-02 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：改名并录入 R6。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：餐饮/用餐分别统计，金额符合基准；旧项删除也不改历史

覆盖：核心runner部分自动覆盖；其余子断言独立验证。 当前：未执行。证据：待记录。

### ST-03 (P0; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：按已删除历史名称筛选和分页。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：候选仍包含该名；列表、条数、汇总、下钻一致，不靠当前输入字典生成候选

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### ST-04 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：同一账单存在同类型同名不同来源标签的历史夹具。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：该名只计一笔/一份金额；列表不重复，分页总数不膨胀

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### ST-05 (P1; API, UI)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：一笔 100 同时有“外卖”“加班”。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：两标签各 100；标签分配额 200，各占 50%；账单总额仍 100

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### ST-06 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：大小写/重音不同名称、内部空格、首尾空白。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：首尾规范化后相同才合并；其他精确不同名称分开；数据库与前端一致

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### ST-07 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：同名支出/收入标签、未分类、零金额集合。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：类型不混合；未分类独立；无除零/NaN；待迁移异常不冒充未分类

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### ST-08 (P0; API)

角色：A, B, C, D, E。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：C/D/E 查询统计、筛选选项、历史名称。

实际目标：`POST /bookkeeping-service/bookkeeping/consume/{totalStatistics|pieChartStatistics|tagsStatistics|rankStatistics|barChartStatistics}`；`POST /bookkeeping-service/bookkeeping/records/{page|statistics}`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`POST /bookkeeping-service/bookkeeping/records/yearStatistics/{overview|consume|income}`。

通过条件：没有通过聚合/候选项泄露不可见账单名称，儿童规则保留

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### BU-01 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：A/B 分别建“餐饮”预算。

实际目标：`POST /bookkeeping-service/bookkeeping/budget/add`；`PUT /bookkeeping-service/bookkeeping/budget/update`；`GET /bookkeeping-service/bookkeeping/budget/{totalBudget|categoryBudget}?budgetType=`。

通过条件：实际金额按付款人：A=300、B=430；不按记录人算 R3

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### BU-02 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：改名后仅修改旧预算额度、创建新名预算。

实际目标：`POST /bookkeeping-service/bookkeeping/budget/add`；`PUT /bookkeeping-service/bookkeeping/budget/update`；`GET /bookkeeping-service/bookkeeping/budget/{totalBudget|categoryBudget}?budgetType=`。

通过条件：旧预算名称不变；用餐 R6 不进入餐饮预算；可明确重选分类

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### BU-03 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：同期同名不同来源新建分类预算、总预算。

实际目标：`POST /bookkeeping-service/bookkeeping/budget/add`；`PUT /bookkeeping-service/bookkeeping/budget/update`；`GET /bookkeeping-service/bookkeeping/budget/{totalBudget|categoryBudget}?budgetType=`。

通过条件：同名分类预算重复拒绝；总预算包含全部应计支出且不随改名减少

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### BU-04 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；按本文基准建立R1-R5；统计基准在改名/退群/删除等破坏场景前串行执行。

步骤：执行预算结算相关隔离测试/测试周期结算。

实际目标：`POST /bookkeeping-service/bookkeeping/budget/add`；`PUT /bookkeeping-service/bookkeeping/budget/update`；`GET /bookkeeping-service/bookkeeping/budget/{totalBudget|categoryBudget}?budgetType=`。

通过条件：名称匹配正确；积分奖惩与幂等不退化，不对真实用户重复结算

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-01 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：普通新增、编辑、快捷记账。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：全部写可信快照，返回历史名称，钱包变动一次

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-02 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：快捷配置来源改名、删除、停用/退群。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：改名可沿后继生成新名；无可用后继明确提示修复，不偷偷换同名别的来源

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-03 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：新用户默认快捷配置初始化。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：分类映射本人来源 ID，不直接引用系统模板或家庭的 user_id=0 行

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-04 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：schema2 任务模板保存/生成；schema1 升级。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：类型/归属校验、快照正确，模板金额/项目等保留，错误模板可定位修复

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-05 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：完成任务重复点击/重试；已记录账单后改名字典。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：只有一张原账单和一次钱包/积分效果；改名不会重建已存在账单

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-06 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：取消完成→删除关联账单→再次完成；账单仅不可访问。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：真删除可重建并按规则解析当前版本；不可访问不重建；任务状态与页面刷新正确

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-07 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：私人模板引用家庭项后退群/加入其他家庭。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：不因模板 shared=false 绕过来源家庭权限；旧生成账单仍保留快照

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-08 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：语音解析、确认、自动保存、重复确认。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：候选为 ID；源值可信；快照与金额正确；重放不重复记账/扣款

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-09 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：AI 返回未知/错误类型 ID，草稿期间字典失效。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：服务端拒绝或要求重选；不自动创建字典、不接受模型给的名称事实

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-10 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：导入同名个人/家庭、未知名称及无分类。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：已知值个人优先；未知行明确错误；不能造字典或按查看人 code 误配；导入保持原整体事务与不联动钱包的既有语义。

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-11 (P0; API, UI)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：其他直接创建账单的现有业务路径。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：均经快照写入；未扩大会员类型等本期外字典的迁移范围

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### EN-12 (P0; API)

角色：A, B。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据；并发/退群/删除/配置变更使用独立夹具，避免破坏金额基准。

步骤：旧客户端 code-only 新增/修改字典字段。

实际目标：`POST /bookkeeping-service/bookkeeping/records/add`；`POST /bookkeeping-service/bookkeeping/records/import (multipart file)`；`GET /bookkeeping-service/bookkeeping/actions/list?recordCategory=`；`POST/PUT /bookkeeping-service/bookkeeping/actions/{add|update}`；`POST /points-service/points/task/plan/{add|generateNow}`；`PUT /points-service/points/task/basics/{updateStatus|updateBookkeepingTemplate}`；`POST /bookkeeping-service/bookkeeping/assistant/expense/{parseAudio|confirm}`。

通过条件：明确版本升级提示，未部分落库；新版字段不塞入 TINYINT code

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### WB-01 (P0; API, WEB)

角色：A, B, C。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：查看共享记录、历史名称筛选、原字典旧链接。

实际目标：`POST /bookkeeping-service/bookkeeping/records/page`；`GET /bookkeeping-service/bookkeeping/records/filter-options`；`GET /auth-service/dict/detail?id=`。

通过条件：Web 与小程序显示一致；旧管理写入受阻；无新增 Web 设置范围

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DB-01 (P0; USER_SQL)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：预检数据库/前置 schema。

实际目标：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql`。

通过条件：明确支持环境；缺前置迁移或歧义数据时阻止继续，不假定 init=线上

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DB-02 (P0; USER_SQL)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：旧分类同 code 不同用户、已删除标签回填。

实际目标：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql`。

通过条件：按原归属唯一解析；可用旧 ID 留存，不能跨用户猜测

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DB-03 (P0; USER_SQL)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：缺源/歧义/超长/重复预算/错误标签/模板。

实际目标：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql`。

通过条件：异常清单可定位；无伪造默认值；未完成项不被标记完成

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DB-04 (P0; USER_SQL)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：首次执行及重跑。

实际目标：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql`。

通过条件：不覆盖已有快照、JSON 新版本；不重复家族初始化；金额/钱包/记录归属不变

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DB-05 (P0; USER_SQL)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：检查非三类字典和其他业务关系。

实际目标：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql`。

通过条件：未被删除/改名/搬迁；旧通用关联保留，记账新读写不用双份事实

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DB-06 (P0; USER_SQL)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：核对被原地覆盖的历史名称。

实际目标：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql`；`/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql`。

通过条件：报告诚实说明只能建立迁移基线；不宣称恢复不可得历史

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DV-01 (P0; STATIC_DOCUMENT)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：核对编译报告与 Git 状态。

实际目标：`docs/acceptance/bookkeeping-dictionary-v2/COMPILE-REPORT.md`。

通过条件：真正有命令/退出码，三端限制明确；没有自动提交/推送/发布

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。

### DV-02 (P0; STATIC_DOCUMENT)

角色：operator。前置：已核对迁移00/04及幂等结果无阻塞；新会话授权启动和验收；专用本地/测试环境、A-E角色和唯一runId已准备；不使用生产数据。

步骤：核对实施阶段行为。

实际目标：`docs/acceptance/bookkeeping-dictionary-v2/COMPILE-REPORT.md`。

通过条件：未启动应用、未执行 SQL/业务验收；所有业务结果初始未执行

覆盖：手工/独立隔离测试；SQL仅用户执行。 当前：未执行。证据：待记录。
