# 记账字典 v2 实施交接

2026-10-16 更新：本地数据库已完成授权清理、迁移和幂等验证，后端保持停服。后续先读[数据库执行报告](DATABASE-REPORT-20261016.md)；生产改用报告链接的三步入口，不重跑本地清理。下文 2026-10-15 内容为原实施交接，API/UI 业务场景仍待验收。

交付边界：代码实施与离线编译/静态检查完成，改动未提交。数据库脚本、应用启动、接口、UI、业务测试均未执行。此文描述实际代码，不代表功能已通过运行验收。验收入口见 [START-ACCEPTANCE.md](START-ACCEPTANCE.md)。

## 基线与改动归属

| 仓库 | 实施前分支 / HEAD | 本期范围 |
|---|---|---|
| iw-mixes-server | main / eac7fd9666a3744008dfdc0e38be00994c0f82c5 | auth字典/家庭权限、共享iw-web合同、bookkeeping全链路、points模板DTO、增量SQL/init、交接和工具 |
| iw-mixes-app-wx | main / cd997144a41b129ff4e886802c64c6a5ce4034b9 | 四个设置页与共享管理实现、录入/编辑/快捷/语音/任务/统计/预算/历史筛选、缓存隔离 |
| iw-mixes-web-platform | dev / 2937e893c648799d12eb7c9aaeb946760dd184f7 | 仅五个必要兼容文件，见下文 |

后端和小程序实施前干净。Web既有 local-ai-launcher/**、scripts/aiLauncher*、package.json、src/router/toolCatalog.ts、docs/tools/**、Skill Workbench相关services/types/utils/views/workers与测试文件均保留，未纳入本期修改；全Web构建包含这些既有工作。未记录其实施前SHA256，因此不宣称有密码学级前后不变证明。联合根目录未初始化Git，三仓库无暂存/提交/推送/tag/发布操作。

[FILE-MANIFEST.json](FILE-MANIFEST.json)列出最终文件SHA256、仓库HEAD、工作区状态与本期/既有归属；只用于核对交付版本，不能代替运行证据。

## 实际实现

- 仅分类4002、支出标签4001、收入标签4011迁移。BaseDictEntity新增scopeType/familyGroupId/previousId/revision。个人/家庭范围隔离；只有群主/家长可管理家庭项；普通成员/儿童只读，无家庭无家庭页签。家庭变动与字典写入使用统一锁顺序。
- Java DictionaryNames与小程序规范化一致：去指定Unicode首尾空白、1–32 Unicode码点；不忽略大小写、不做NFC/NFKC或内部空格折叠，FEFF保留。数据库名称唯一与历史分组使用字节精确语义，停用项仍占用当前名称。重复/非规范旧名称由迁移预检阻塞，不能静默覆盖。
- 新候选只包含当前启用且可访问的项，同名个人ID优先；历史账单保留原来源ID与名称。改名逻辑删除旧行并创建previousId后继；旧revision拒绝，排序以scopeVersion拒绝陈旧完整列表。启停/排序不换ID；无关同名项不作为后继。
- auth的BookkeepingDictionaryService/Mapper/Controller承接三类管理与BookkeepingDictionaryAccess共享合同。旧通用字典写入口及管理员同步不可绕过；注册模板限定个人系统模板，repair排除三类；有效家庭只初始化一次系统分类，标签初始为空，删空不重灌。
- BookkeepingDictionarySnapshots统一服务端取名、权限/来源锁与快照。新增记录status=1；新表bookkeeping_record_tag_snapshot替代这三类标签的通用关系双写，旧关系保留作为迁移证据，账单逻辑删除不抹快照。
- 普通记账、快捷、语音确认/自动保存、导入、任务自动记账均接入可信来源ID。更新未触碰字段保留旧快照，不重新验证已经失效的历史引用；新增选择验证当前有效项。继承付款人/家庭可见性、儿童、钱包、积分与任务行锁规则。
- 月度/年度/排名/历史筛选按账单快照名称，同名来源合并，标签按每账单/类型/精确名称去重，标签筛选保持OR。分类预算保存来源ID与名称，按名称匹配实际、按付款人统计与结算，同所有者同周期同名预算受锁保护。
- 快捷与任务模板沿同归属同类型后继链刷新；失效无后继提示修复。默认快捷从系统模板映射到新用户实际来源，歧义/缺失跳过并日志，不使用系统模板ID写账单。schema2任务模板由服务端生成，保留既有幂等/删除后重建边界。
- 小程序主入口只在菜单→财务记账→记账设置；原快捷设置标题改“快捷设置”。旧字典三类入口封堵；历史当前选择保留本地展示；登录/家庭切换清缓存并拒收过期响应。快捷标签接口正常由后端CSV序列化器输出数组，客户端同时兼容CSV并归一为数字ID数组。

主要文件入口：

| 项目 | 文件/目录（仓库相对路径） |
|---|---|
| server共享合同 | iw-web/src/main/java/com/itwray/iw/web/bookkeeping/；iw-web/.../model/entity/BaseDictEntity.java；iw-points/.../TaskBookkeepingTemplateDto.java |
| auth | iw-packaging-parent/iw-auth/src/main/java/com/itwray/iw/auth/{controller/BookkeepingDictionaryController.java,service/impl/BookkeepingDictionaryService.java,mapper/BookkeepingDictionaryMapper.java}及Mapper XML；BaseDictServiceImpl、AuthFamilyGroupServiceImpl |
| bookkeeping | iw-packaging-parent/iw-bookkeeping/src/main/java/com/itwray/iw/bookkeeping/support/BookkeepingDictionarySnapshots.java；BookkeepingCategoryFilter.java；Records/Actions/Assistant/Budget/Consume/Income服务、TaskBookkeepingAdapter、Records Controller/DTO/VO/Mapper及年度Mapper |
| wx新增 | api/bookkeeping-dictionaries.js；stores/bookkeeping-options.js；pagesBookkeeping/bookkeeping/bookkeeping-settings.*、bookkeeping-category-settings.*、bookkeeping-consume-tag-settings.*、bookkeeping-income-tag-settings.*、dictionary-management.* |
| wx联动 | api/bookkeeping.js、api/request.js、app.json、pages/menu.js、pages/bill/index.js、pagesBase/base/dict-*、pagesBookkeeping/bookkeeping/录入/详情/列表/快捷/预算/统计、pagesPoints/points/task-plan-add.js、stores/session.js/family.js、utils/bookkeeping.js/task-bookkeeping.js |
| Web全部本期文件 | src/api/bookkeeping.ts；src/types/bookkeeping.ts；src/views/bookkeeping/components/BookkeepingTable.vue；src/views/dict/components/DictForm.vue；src/views/dict/components/DictManagementTable.vue |

Web只适配历史快照显示、历史名称筛选及旧维护入口禁用，未扩建Web设置/录入页面。以上路径缩写只作导航，完整逐文件路径以manifest为准。

## 最终HTTP与字段合同

完整兼容入口为 `/bookkeeping-service/bookkeeping/dictionaries`，仍使用统一code/message/data及iwtoken；Controller内部不含/bookkeeping-service。type为CATEGORY/EXPENSE_TAG/INCOME_TAG，scope为PERSONAL/FAMILY。

| 方法与相对路径 | 输入 | data |
|---|---|---|
| GET /items | type,scope | items,canManage,scopeVersion,familyGroupId |
| POST /items | type,scope,name | item |
| PUT /items/{id}/name | name,revision | oldId,item |
| PUT /items/{id}/status | enabled,revision | item |
| DELETE /items/{id} | query revision | 无 |
| PUT /order | type,scope,ids,scopeVersion | 无 |
| POST /copy-personal | sourceIds | created item[],skipped ID[] |
| GET /options | type | [{id,name,sort}] |

item为id/name/enabled/sort/revision。排序ids是该范围当前项完整列表，不能只传局部移动项。管理失败不得只靠客户端按钮隐藏防护。

新增账单：recordTypeId可空，recordTags为来源ID数组，非空legacy recordType拒绝。更新：dictionaryChanges.categoryId缺省保持/null清空/ID重新选择；tagIds缺省保持/[]清空/数组保留历史已选并验证新加项，禁止null标签及旧顶层分类/标签写入。DTO普通字段按原更新合同传递，不能把此功能当通用PATCH。

账单读VO：recordTypeId、recordTypeName、dictionarySnapshotStatus、recordTagSnapshots[{id,type,name}]。兼容旧字段不用于计算实时字典名称。`GET /bookkeeping-service/bookkeeping/records/filter-options`从可见历史返回categories/expenseTags/incomeTags的{name}列表；`GET /bookkeeping-service/bookkeeping/records/{id}/dictionary-sources`先验证账单访问权再返回历史来源。

列表/统计过滤：categoryNames/tagNames名称数组（标签OR），categoryState为UNCATEGORIZED或PENDING，与categoryNames互斥。UNCATEGORIZED仅status=1且无分类；PENDING仅迁移未完成，二者与真实名称“未分类/待处理”分开。预算recordTypeId为来源，名称由服务端取得；快捷与任务采用ID引用，任务schemaVersion=2由后端生成。

## 与计划对照及保留边界

1. 增加categoryState明确区分未分类/迁移待处理及同名真实分类，使统计下钻可精确复现；已同步后端、wx、Web合同与场景。
2. 实际旧服务禁止修改账单收支类型，本期保留，HS-05验收是拒绝跨收支修改并保持账单/金额不变，不扩展重分类产品。
3. 导入沿原整体事务且不联动钱包；未知名称整批失败并给行信息，不自动建字典。不能把普通手工录入的钱包联动套到旧导入语义。
4. 默认快捷无法唯一映射时跳过并日志；不为缺来源配置伪造ID。历史已原地覆盖的字典名只能建立当前可解析的迁移基线，无法恢复真实历史名称。
5. SQL实现支持MySQL 8.0.21+的8.x，非MariaDB/5.7；实际版本、字符集、schema与数据只能由用户执行预检确认。本期未连库。
6. API runner覆盖权限/快照/统计的核心子断言，不涵盖全部64场景，更不涵盖任务幂等或UI。详见工具README与SCENARIOS，禁止把工具全绿等同整体通过。

## 交付与下一步

- [COMPILE-REPORT.md](COMPILE-REPORT.md)：实际命令、退出码、初始错误修复、日志与限制。
- [MIGRATION-RUNBOOK.md](MIGRATION-RUNBOOK.md)：所有待执行SQL准确路径/条件依赖/顺序/停写与异常处理；不运行已有数据库的整套init。
- [SCENARIOS.md](SCENARIOS.md)、[cases.json](cases.json)、[RESULTS.md](RESULTS.md)：64场景，全部初始未执行。
- `scripts/acceptance/bookkeeping-dictionary-v2/run.mjs`与README：显式授权后可重跑核心API工具，实施阶段仅语法检查，Node20+。
- `scripts/acceptance/bookkeeping-dictionary-v2/check-static.py`：纯离线SQL范围/Mapper XML/交接一致性检查，不连接数据库，不运行验收。
- [START-ACCEPTANCE.md](START-ACCEPTANCE.md)：用户执行SQL并交回脱敏结果后，在新会话授权启动、验收与修复的完整提示词。

尚未验证：MySQL执行/真实并发锁与DDL唯一索引、Java测试运行（含H2对MySQL BINARY语法兼容性）、所有API/角色/任务幂等/钱包与预算结算、语音供应商/导入运行、小程序WXML/WXSS开发者工具编译与UI、Web浏览器交互。全部保留到下一授权会话，不能以编译结果替代。
