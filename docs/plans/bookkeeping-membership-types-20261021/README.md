# 会员类型迁入记账设置：开发方案 V1

日期：2026-10-21。主仓库：`iw-mixes-server`。

- 设计状态：V1已整理；个人范围、历史名称保留规则已确认，其余为待实施评审的技术建议。
- 实施授权：2026-10-21用户已授权按V1开发并自行验证；随后要求停止扩大自动验证，改为提供业务验收清单。后续已授权将会员类型及原有记账优化提交并推送，未授权发布。
- 代码状态：V1已实施，保留原有性能优化、候选缓存和底部间距修改；按后续授权进行提交与推送交付。
- 验收状态：已完成相关测试、隔离接口/迁移及部分真实页面操作；未宣称全部验收通过。后续按[简短验收清单与结果](../../acceptance/bookkeeping-membership-types-20261021-r1/RESULTS.md)确认即可。

## 1. 目标、确认与影响范围

用户请求：“把记账的‘会员类型’字典也移到‘记账设置’中来，帮忙先整理开发方案”。

已确认：2026-10-21 用户回复“仅个人类型，因为会员订阅目前没有计划做成共享数据”。因此会员类型不提供“我的/家庭共用”切换、家庭复制、家庭管理权限；已有三个分类/标签页面的家庭能力保持原样。

已确认：2026-10-21 用户选择“已有订阅保留原名称（推荐）”。类型改名、停用、删除不改写已有订阅名称；新建或主动重新选择类型时使用当前可用名称。

当前没有阻断方案整理的业务问题。后续实施仍需获得明确授权；实际数据分布、异常映射数量和可用测试环境属于实施前检查，尚未核实。

| 项目 | 责任 |
|---|---|
| 后端 | 会员类型个人权限、字典版本与有效性、订阅类型契约与迁移、通用字典旧入口拦截 |
| 微信小程序 | 记账设置第四项、个人会员类型管理、订阅表单/列表/筛选、按需候选缓存与旧入口隐藏 |
| Web | 通用字典页面排除4004并展示迁移提示；不新增会员订阅或会员类型管理页 |

不扩展会员订阅共享，不调整计费周期4005、周期单位4006、扣费金额、日期计算及自动续费规则；自动续费只延续当前订阅，不被视为新选会员类型。

## 2. 页面与交互建议

交互原型：[prototype.html](prototype.html)。原型仅使用本地演示数据，不连接任何真实接口。

已实际渲染并打开检查的状态图：[记账设置入口](assets/settings.png)、[个人会员类型](assets/types.png)、[改名后保留原订阅名称](assets/renamed.png)。原型新增与改名交互已检查，属于设计验证，不代表真实接口、数据库或小程序验收。

1. 记账设置新增“会员类型”，说明“管理我的会员订阅类型”，进入计划新增的 `bookkeeping-membership-type-settings` 页面。
2. 复用字典管理工厂、模板和样式，增加明确的个人模式配置。会员类型页面不渲染家庭分段和复制入口，说明“仅自己管理，用于我的会员订阅”。
3. 提供新增、改名、启用/停用、删除、排序；名称去首尾空白，1–32个Unicode码点，同一范围当前项含停用项不能重名。复用现有revision及scopeVersion冲突规则。
4. 复用已调整的“安全区 + 32rpx”底部间距和列表占位；长名称可换行，按钮文字不换行。空、加载、失败、只读、防重复提交、保存成功但刷新失败等状态复用现有管理页。
5. 新增/更改订阅类型只能选择本人当前启用的会员类型。类型列表为空时引导去记账设置创建，不再以硬编码默认类型冒充真实可用项；接口失败必须有重试状态。
6. 已确认的历史规则：已有订阅显示保存时类型名称；改名产生新的字典版本，只影响之后的选择；停用/删除不破坏已有订阅显示。编辑其它字段时原类型保持，只有主动重新选择类型时更新类型快照。
7. 旧订阅原类型已停用/删除时显示原名称并标记“原类型”，不得悄悄替换为选择器第一项。无法核实旧类型的记录显示“类型待核对”，支持主动重选；正常自动续费仍保持原类型快照。

## 3. 当前源码事实与基线

核查日期：2026-10-21。工作区：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai`。仅核查源码，未查询真实数据库或接口，当前数据量、4004重复编码和默认模板内容尚未核实。

| 位置 | 事实及计划 |
|---|---|
| [DictTypeEnum](../../../iw-web/src/main/java/com/itwray/iw/web/model/enums/DictTypeEnum.java) | 会员类型=4004，当前通用字典按CODE使用；4005/4006对应服务端枚举，本需求不迁移 |
| [DictionaryNames](../../../iw-web/src/main/java/com/itwray/iw/web/bookkeeping/DictionaryNames.java) | 当前业务字典识别仅包含4001/4002/4011，新增4004时需区分“已迁移字典”与“支持家庭的字典” |
| [BookkeepingDictionaryService](../../../iw-packaging-parent/iw-auth/src/main/java/com/itwray/iw/auth/service/impl/BookkeepingDictionaryService.java) | 现有Type枚举、管理/候选/改名/排序逻辑可复用；actor与reader当前依赖家庭信息，4004需要个人路径 |
| [BookkeepingDictionaryMapper](../../../iw-packaging-parent/iw-auth/src/main/java/com/itwray/iw/auth/mapper/BookkeepingDictionaryMapper.java) | 默认初始化历史计数、个人授权读取/锁定、类型白名单都需评估，不能仅新增前端入口 |
| [BaseDictServiceImpl](../../../iw-packaging-parent/iw-auth/src/main/java/com/itwray/iw/auth/service/impl/BaseDictServiceImpl.java) | 通用字典缓存、读写、同步、补齐等处有三种旧类型的显式排除，4004需一致排除，防止删除后补回 |
| [会员订阅Entity](../../../iw-packaging-parent/iw-bookkeeping/src/main/java/com/itwray/iw/bookkeeping/model/entity/BookkeepingMembershipSubscriptionEntity.java) | 当前只有membershipType编码，无来源ID或类型名称快照 |
| [会员订阅Service](../../../iw-packaging-parent/iw-bookkeeping/src/main/java/com/itwray/iw/bookkeeping/service/impl/BookkeepingMembershipSubscriptionServiceImpl.java) | 列表按当前userId隔离、按编码过滤；新增/修改只补算结束日期，没有会员类型归属/有效性校验；自动续费直接更新日期 |
| [订阅页](../../../../iw-mixes-app-wx/pagesBookkeeping/bookkeeping/bookkeeping-membership-subscription.js) | 从通用缓存取4004，按dictCode显示、筛选与提交；空缓存回退到写死的四种类型，名称随当前字典变化 |
| [记账设置入口](../../../../iw-mixes-app-wx/pagesBookkeeping/bookkeeping/bookkeeping-settings.js) | 当前只有分类、支出标签、收入标签三个入口 |
| [管理工厂](../../../../iw-mixes-app-wx/pagesBookkeeping/bookkeeping/dictionary-management.js) | 当前三个页面共享两种范围及复制操作，需增加能力配置，不能将个人模式硬编码到所有页面 |
| [候选缓存](../../../../iw-mixes-app-wx/stores/bookkeeping-options.js) | 上轮已加入30秒缓存与并发合并；目前固定加载三类，不应让快捷记账因新增会员类型而多一次无关请求 |
| [Web字典表单](../../../../iw-mixes-web-platform/src/views/dict/components/DictForm.vue) | 硬编码排除4001/4002/4011；需要同步4004与提示文字，后台仍须独立拦截旧入口 |

当前仓库基线：后端main/40027a9，小程序main/e6685f1，Web dev/96d7f17。HEAD只用于定位，不代表包含未提交改动的现状；精确文件指纹见[BASELINE.json](BASELINE.json)。

已有改动：后端字典只读/批量解析及相关测试；小程序快捷项加载、候选缓存、加载回归、字典管理底部间距。相关未跟踪测试亦纳入基线。Web核查时工作区干净。不得覆盖、回滚、混入无关提交或将旧验证结果当成本需求验收。

## 4. 接口与个人权限

建议复用 `/bookkeeping-service/bookkeeping/dictionaries`，新增Type枚举 `MEMBERSHIP_TYPE(4004)`，4004只接受PERSONAL。

| 操作 | 调用路径与入参 |
|---|---|
| 管理列表 | `GET .../items?type=MEMBERSHIP_TYPE&scope=PERSONAL` |
| 可选类型 | `GET .../options?type=MEMBERSHIP_TYPE`，仅本人启用项，返回id/name/sort |
| 新增 | `POST .../items`，type、scope、name |
| 改名 | `PUT .../items/{id}/name`，name、revision；返回oldId及新版本item |
| 启停 | `PUT .../items/{id}/status`，enabled、revision |
| 删除 | `DELETE .../items/{id}?revision=...` |
| 排序 | `PUT .../order`，type、scope、全量ids、scopeVersion |

个人权限强制条件：`dict_type=4004 AND scope_type=0 AND family_group_id=0 AND user_id=当前用户`。有无家庭、家庭角色或初始化标记不影响本人会员类型管理。

必须在后端拒绝4004的FAMILY列表/创建/排序请求，候选永远只返回本人个人项；拒绝通过copy-personal将4004复制到家庭；根据ID改名/启停/删除时同样验证本人个人来源及正确类型，不能只隐藏前端按钮。原三个类型的家庭权限与初始化规则不得改变。

展示/候选读取复用上轮只读路径，不重新引入行锁或逐条查询。新增/更改订阅类型时仍在写入事务内锁定用户和来源，校验类型、归属、启用、未删除。个人路径不锁家庭/成员、不依赖家庭状态。

## 5. 类型快照与订阅契约建议

推荐继续在base_dict存储4004来源及改名版本；不以dictCode充当新来源ID。

| 订阅字段 | 建议用途 |
|---|---|
| membership_type | 保留旧编码用于迁移核对与旧查询兼容；新流程不写入来源ID到此字段 |
| membership_type_id / membershipTypeId | 保存选择时的个人字典来源ID |
| membership_type_name / membershipTypeName | 服务端从锁定来源生成的不可随字典变化的名称快照 |
| membership_type_snapshot_status | 0=旧数据待核对，1=快照已核实；不伪造缺失类型名称 |

新增订阅请求使用必填membershipTypeId，客户端不能自行填写可信名称或快照状态。订阅详情/列表返回来源ID、快照名和状态，列表优先使用服务端快照，不再用当前查看者字典翻译旧编码。

修改建议采用可省略的 `membershipTypeChange: {sourceId}`：省略即保持原类型ID、名称、状态；明确提交时sourceId必须非空且有效。已有UpdateDto继承AddDto，实施时需调整共用字段/校验分组或拆开DTO，避免新增必填约束误伤“编辑其它字段保留原类型”。

自动续费只更新已有记录的起止日期，不替换类型来源、不重新根据当前字典改写名称；不要把续费视为新的字典选择。

筛选建议新增 `GET /bookkeeping-service/bookkeeping/membership/filter-options`，只汇总本人订阅中实际保存过的类型名称，并提供“类型待核对”状态选项。列表增加membershipTypeName或membershipTypeState=PENDING过滤，两者互斥，与到期/排序/本地关键词搜索组合；同名历史记录作为同一筛选项，改名前后不同名称分别可筛。会员类型管理排序不改变历史筛选结果的归属。

旧客户端兼容：旧编码查询可暂时保留，但新来源ID绝不复用旧字段。旧编码新增/变更类型请求返回明确升级提示，不静默猜测来源；旧订阅其它字段更新若需兼容，只允许原编码与数据库一致时保持快照，否则拒绝。上线顺序及客户端升级窗口必须在实施时验证，不能仅依赖发布后所有用户已升级。

## 6. 数据迁移方案建议（不在设计阶段执行）

实施阶段在本目录下新增独立SQL与运行说明，不改写已发布的dictionary-v2历史脚本。本轮仅规划，不生成可直接误执行的生产写入SQL。

1. preflight：只读检查订阅表/4004字典真实字段、数量、编码重复/重用、删除/停用行、模板、来源归属及当前唯一约束。记录每项断言、备份和停止写入前提。
2. schema：增加会员类型来源、快照、状态字段及必要索引，匹配现有数据库字符集/排序规则；字段长度与32码点及Unicode规范一致。
3. backfill：仅按“订阅user_id + 4004 + 本人个人范围 + 旧membership_type=dict_code”建立可证明的唯一映射，名称写入当前可核实值并记录迁移口径。删除/重用/多个候选或来源缺失不得任意取第一条、套默认模板或用当前查看者替换，保留待核对状态并输出清单。
4. 原通用字典若曾就地改名，数据库现值不能证明订阅创建时的名称；本次最多保存迁移时可核实名称，不宣称恢复了无法证实的旧名。小程序四种fallback也不能当作真实历史来源证据。
5. 默认值初始化：迁移保留已有4004个人来源；需要默认项时仅对经确认的缺失范围初始化，不能因用户已停用/删除而自动补回。新用户按模板初始化一次；注册事件重放与字典修复不复活已删除类型。无需家庭数据初始化。
6. verify：核对订阅总数/所有者/金额/周期/日期/续费开关不变；完整与待核对数量可对账；完整快照有唯一本人来源；无4004家庭行或跨用户引用。重复运行不得新增重复来源或改写已有完整快照。
7. 发布与恢复：先完成备份、停写和部署兼容性检查，执行前置检查→结构与回填→验证，验证失败停止切换。恢复以备份和明确停写窗口为依据，不默认用删除新字段/回滚旧二进制处理已出现新ID的数据。

以上仅为生产方案约束；本轮没有授权SQL执行、服务启动、数据清理或发布。

## 7. 文件级实施步骤

| 步骤 | 文件/模块 | 工作内容及验证 |
|---|---|---|
| 1 | iw-web/DictionaryNames、iw-auth/BookkeepingDictionaryService与Mapper | 区分迁移字典和家庭能力，增加个人4004分支、所有显式白名单/默认历史计数；测试无家庭、有家庭、角色变化下个人读写及伪造家庭请求 |
| 2 | iw-auth/BaseDictServiceImpl及通用字典相关测试 | 同步读、写、缓存、类型目录、同步和补齐拦截；旧入口不能绕过，清缓存策略明确 |
| 3 | iw-bookkeeping/会员订阅Entity、Add/Update/List DTO、List/Detail VO、Service、Controller、Mapper | 实施确认后的类型引用/快照、状态过滤、有效性与归属校验；回归自动续费和其它订阅字段 |
| 4 | 本目录sql/（计划新增） | preflight、schema/backfill、verify及执行说明；先以授权的隔离测试库验证幂等和异常数据，不执行真实生产 |
| 5 | 小程序bookkeeping-settings.js、app.json、static/bookkeeping/settings | 新入口、路由与会员类型图标；不使用AI栅格图替换现有SVG图标体系 |
| 6 | 小程序bookkeeping-membership-type-settings四件套（计划新增）、dictionary-management.js/.wxml | 个人模式配置，复用现有增改启停删排序和底部间距；原三页保持家庭模式 |
| 7 | 小程序api/bookkeeping-dictionaries、stores/bookkeeping-options、会员订阅页、api/bookkeeping | 4004候选单独按需加载；只在会员订阅场景请求，不能把固定三类刷新扩大为每次四类；写后失效、会话隔离、空/失败及历史选择处理 |
| 8 | 小程序pagesBase/base/dict-manage.js、dict-detail.js；Web DictForm.vue、DictManagementTable.vue | 隐藏4004旧通用入口并更正提示；保持后端拦截；Web不新增业务入口 |
| 9 | 各项目相关测试与本包验收记录 | 完成以下场景，保存真实版本、工具输出、必要截图和数据残留；未执行场景明确保留 |

## 8. V1设计时的自验清单（实施结果另记）

以下保留原设计清单；表格结果为设计阶段记录，当前实施结果以[验收记录](../../acceptance/bookkeeping-membership-types-20261021-r1/RESULTS.md)为准。不继承上轮性能优化的通过结果。无真实账号或数据库时先完成离线测试，缺失部分保持未执行，不把mock原型当业务证明。临时数据建议前缀 `AT-MTYPE-20261021-`；真实数据创建/修改/清理需在实施会话确认具体测试环境和范围。

| 编号 | 前置/操作 | 可观察预期 | 结果 |
|---|---|---|---|
| AC-01 | 打开记账设置→会员类型 | 新入口与个人管理页可达，无家庭切换和复制 | 未执行 |
| AC-02 | 无家庭/有家庭/普通成员分别管理本人类型 | 个人能力相同，不依赖家庭角色或初始化状态 | 未执行 |
| AC-03 | 伪造FAMILY scope、他人ID或复制4004 | 后端拒绝，记录无变化，原三类家庭功能正常 | 未执行 |
| AC-04 | 新增、重名含停用、空白、32/33码点、emoji | 名称规则、后端校验及错误保留草稿一致 | 未执行 |
| AC-05 | 改名、启停、删除、排序、过期revision/scopeVersion | 新版本/状态/顺序正确；冲突不自动重放写入 | 未执行 |
| AC-06 | 创建订阅，提交有效/停用/删除/他人/错类型ID | 仅本人有效4004可保存，快照由服务端生成 | 未执行 |
| AC-07 | 改名、停用、删除已被订阅引用的类型 | 已有订阅保留原名称，候选只提供当前启用项 | 未执行 |
| AC-08 | 编辑已有订阅其它字段/主动重选类型 | 原类型默认保持，重选才更新；失效旧项不被第一项替代 | 未执行 |
| AC-09 | 历史快照筛选与到期、排序、搜索组合 | 本人记录范围正确，旧/新名称及待核对数据可查 | 未执行 |
| AC-10 | 空类型/请求失败/返回设置新增后再返回 | 无硬编码假候选，可重试/引导，返回刷新且不丢草稿 | 未执行 |
| AC-11 | 自动续费，以及改名/删除后续费 | 日期仍按原规则变化，类型快照不被重写 | 未执行 |
| AC-12 | 通用字典小程序/Web及直接调用旧接口 | 4004无法继续由旧入口读写/同步补回，有迁移提示 | 未执行 |
| AC-13 | 迁移含同编码多用户、重复/缺失/删除来源 | 不跨用户错配，异常待核对；重复运行幂等且原业务字段不变 | 未执行 |
| AC-14 | 快速切账号、切家庭、请求迟到、并发点保存 | 个人4004隔离正确，写请求防重，原三类缓存不串数据 | 未执行 |
| AC-15 | 长名称、底部安全区0/34、长列表、输入法 | 按钮不贴底，末项不遮挡，弹层输入和错误可见；模拟器与可用真机分别记录 | 未执行 |
| AC-16 | 进入快捷记账/普通记账分类标签场景 | 不额外请求4004，不退化为串行全量加载，原性能改动保留 | 未执行 |

编译/静态检查按各仓库AGENTS.md执行：后端固定Maven路径与本地仓库、显式非用户settings、Java17及`-am`；小程序路由/相关脚本回归和微信工具编译；Web类型检查与构建。设计阶段不运行这些业务验证命令。

## 9. 原设计阶段交接与下一步

本节保留原交接说明；实施授权与当前状态已更新在文首，后续无需重复设计审批或扩大验证范围。

个人范围和历史名称保留已确认，V1方案可用于评审与后续实施交接。本轮仍仅整理方案；接口字段、迁移步骤和技术实现为方案建议，实施前需复核源码及真实数据。

新会话提示词（供用户后续明确授权实施时主动发送；不代表本轮已授权）：

> 使用 $iw-feature-handoff，读取 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/plans/bookkeeping-membership-types-20261021/README.md`，按会员类型迁入记账设置V1方案开发并自行验证。已确认仅个人类型、不做家庭共享，已有订阅保留原名称，主动重选才更新名称，不重新询问这些规则。先核对联合工作区和受影响仓库AGENTS.md、BASELINE.json及已有改动，保留性能优化、候选缓存和底部间距修改，不重置或覆盖工作区。复核真实数据和测试环境后推进；数据库写入、业务服务启动、真实数据清理按实施会话授权执行，授权不明时先完成离线测试。后端需要本地启动时只能使用scripts/local-backend.sh；Maven遵循工作区固定路径、settings和仓库约定。不要提交、推送、执行生产SQL或发布，除非另获明确授权。记录真实构建、接口/UI、迁移结果与测试数据残留，不把原型或上轮通过结果当本需求验收。
