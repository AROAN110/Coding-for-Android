# CFA 资源包与插件规范（v1）

> 版本：v1.0（对应 App v1.0.5+）
> 本文档是资源包 / 插件开发的唯一权威契约。App 端接口面写死在软件层，
> 资源包只能调用本文档列出的 action；调用白名单之外的接口会得到
> `unknown_action`，终端侧统一显示为 `[CFA] 资源包错误`。

## 1. 总体模型

```
终端（主权层）
  pmc 安装/卸载/授权、su 申请、环境切换 —— 一切配置只在这里发生
        │
App 基座（写死，随 App 发版演进）
  Router 固定 action 白名单 + PermissionGuard 权限门禁 + CWD 沙箱
        │
资源包（可替换的表现层）
  整站 HTML/CSS/JS，替换 Web 层页面；只能调固定接口
        │
插件（可选增强）
  JS 注入到资源包页面，与资源包同一套接口与权限模型
```

设计原则：
- **终端主权**：su、安装、授权只在终端完成，Web 层没有配置入口。
- **接口写死**：新能力 = App 发版新增 action；资源包不可能自己开洞。
- **副本制数据通道**：资源包默认操作私有副本，写回需用户确认（workspace 模型）。
- **模拟 APK 权限**：声明制 + 高危默认禁止 + pmc 手动授权。

## 2. 包格式

资源包与插件包都是 zip，均以 `manifest.json` 为唯一真相源，靠 `type` 区分。

### 2.1 资源包

```
my-resource.zip
├── manifest.json      ← type: "resource"
├── index.html         ← entry 指向的入口页
├── assets/…           ← 静态资源
└── plugins/…          ← 内置插件（可选，随包生效，权限并入资源包声明）
```

### 2.2 插件包

```
my-plugin.zip
├── manifest.json      ← type: "plugin"
├── main.js            ← entry 指向的入口脚本
└── assets/…           ← 插件静态资源（可选）
```

### 2.3 manifest.json 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | `"resource"` 或 `"plugin"` |
| `id` | string | 是 | 反向域名，如 `com.example.res`；全局唯一，同 id 安装 = 覆盖升级 |
| `name` | string | 是 | 显示名 |
| `version` | string | 是 | 语义化版本，如 `1.0.0` |
| `entry` | string | 是 | 资源包为 HTML 相对路径；插件为 JS 相对路径 |
| `minAppVersion` | string | 是 | 最低 App 版本，失配拒装 |
| `permissions` | string[] | 是 | 权限声明数组（可为空数组） |

示例（资源包）：

```json
{
  "type": "resource",
  "id": "com.example.editor",
  "name": "示例编辑器",
  "version": "1.0.0",
  "entry": "index.html",
  "minAppVersion": "1.0.5",
  "permissions": ["fs:read", "fs:write", "fs:struct", "workspace", "terminal.read"]
}
```

### 2.4 安装校验（不合规直接拒装）

- manifest 为合法 JSON，必填字段齐全；
- `type` ∈ {resource, plugin}；
- `id` 为合法反向域名（字母开头，段内 `[A-Za-z0-9_.]`）；
- `entry` 文件存在于 zip 内；
- `permissions` 是已知权限的子集（未知权限拒装）；`su` 恒拒装；
- `minAppVersion` ≤ 当前 App 版本。

安装落位：
- 资源包 → `filesDir/home/resources/<id>/`
- 插件包 → `filesDir/home/plugins/<id>/`

## 3. 权限模型

### 3.1 权限分级

| 级别 | 权限 | 行为 |
|---|---|---|
| 普通 | `fs:read` `fs:write` `fs:struct` `workspace` `terminal.read` `network` `clipboard:read` `notification` | manifest 声明即生效 |
| 高危 | `terminal.write` `shell` | 声明后**默认 denied**，运行时返回 `permission_denied`，需 pmc grant |
| 恒拒 | `su` | 预留位，声明即拒装，运行时永不放行 |

权限语义：

| 权限 | 覆盖的 action | 说明 |
|---|---|---|
| `fs:read` | `fs.list` `fs.read` | 限 CWD 沙箱内 |
| `fs:write` | `fs.write` | 限 CWD 沙箱内 |
| `fs:struct` | `fs.mkdir` `fs.delete` | 增删目录项，限 CWD 沙箱内 |
| `workspace` | `workspace.import` `workspace.export` | 工程副本导入/写回，目标限工程目录白名单 |
| `terminal.read` | `terminal.status` | 会话状态只读 |
| `terminal.write` | `terminal.write` | 向当前会话写命令（高危） |
| `network` | `net.fetch` | 经 App 代发 HTTP；禁止访问本地回环 |
| `shell` | （预留） | 容器内白名单命令，v1 未开放 |
| `clipboard:read` `notification` | （预留） | v1 未开放 action |

### 3.2 运行时判定链

每个请求经 Router：

```
action 存在？ → 否 → unknown_action（终端显示「资源包错误」）
schema 合法？ → 否 → bad_request
所需权限：
  普通权限 → manifest 已声明 → 放行 / 未声明 → permission_denied
  高危权限 → 已声明 且 pmc 已 grant → 放行 / 否则 → permission_denied
CWD 沙箱（fs 类）→ canonical 校验，越界 → forbidden
```

### 3.3 pmc perm 授权指令（终端）

```
pmc perm grant <pkg> <perm>          逐个授权
pmc perm grant <pkg> <perm1,perm2>   批量授权（逗号分隔）
pmc perm grant <pkg> all             授予其声明并默认禁用的全部高危权限
pmc perm revoke <pkg> <perm>         撤销
pmc perm list [<pkg>]                查看授权状态
```

约束：
- 只能授予 manifest 中声明过的权限，未声明报 `permission not declared`；
- `pmc perm list` 输出每个权限的 `granted/denied` 状态；
- 授权持久化于 SharedPreferences（`pmc_perm`），卸载包时清除。

## 4. 通信协议

### 4.1 请求 / 回包

```json
请求: {"id":"req_1","action":"fs.list","pkg":"com.example.editor","params":{"path":"."}}
回包: {"id":"req_1","status":"ok","data":{…}}
错误: {"id":"req_1","status":"error","code":"permission_denied","message":"…"}
```

- 通道：WebView 内 `window.__CFA_NATIVE__.send(requestJson)` 同步返回；或本地 HTTP 总线（127.0.0.1 随机端口）。
- `pkg` 必填：调用方包 id，由注入 shim 自动附加；缺失按未知调用方处理（只有免权限 action 可通过）。
- v1 已知限制：同一页面内 JS 可伪造 `pkg` 字段。缓解：高危权限只能由用户在终端显式授予，伪造调用方拿不到未授权的高危能力；普通权限泄露面限于沙箱内只读。

### 4.2 插件 API shim（App 注入）

每个页面 / 插件注入前，App 先注入极薄 shim：

```js
// App 自动注入，插件直接用
window.CFA = {
  request: (action, params) =>
    new Promise((resolve, reject) => {
      const res = window.__CFA_NATIVE__.send(JSON.stringify({
        id: "req_" + Math.random().toString(36).slice(2),
        action, pkg: window.__CFA_PKG__, params: params || {}
      }));
      const r = JSON.parse(res);
      r.status === "ok" ? resolve(r.data) : reject(r);
    })
};
```

### 4.3 action 白名单 v1

| action | 权限 | 参数 | 返回 |
|---|---|---|---|
| `ping` | 无 | — | `"pong"` |
| `fs.list` | fs:read | `path` | `{entries:[{name,type,size}]}` |
| `fs.read` | fs:read | `path` | `{content}`（≤256KB） |
| `fs.write` | fs:write | `path`,`content` | `{bytes}` |
| `fs.mkdir` | fs:struct | `path` | `{}` |
| `fs.delete` | fs:struct | `path` | `{}`（不允许删目录树，只删文件/空目录） |
| `workspace.import` | workspace | `path` | `{workspace}`（cp 到包私有目录） |
| `workspace.export` | workspace | `path` | v1 预留，返回 `not_implemented` |
| `terminal.status` | terminal.read | — | `{installed,session}` |
| `terminal.write` | terminal.write | `line` | `{}`（写入当前会话） |
| `net.fetch` | network | `url` | `{status,body}`（禁回环/内网地址） |

未列出的 action 一律 `unknown_action`。

## 5. 安装路径

| 方式 | 命令 | 说明 |
|---|---|---|
| 本地 zip | `pmc local-load /sdcard/.../x.zip` | 按 manifest.type 自动归类落库 |
| 远程仓库 | `pmc install <pkg>` | index.json 检索 → 下载 → SHA256 → 安装 |
| 容器 apk | `pmc install <pkg>` 降级档 | zip 未命中仓库时 `apk-online` + `apk add` + `apk-offline` |

资源包/插件包与容器软件包是两类东西：前者是 Web 层增强，后者是容器内运行时依赖（python、gcc 等）。前者只能走 zip 安装，后者走 apk。

## 6. 示例

### 6.1 hello 资源包

```
hello.zip
├── manifest.json
└── index.html
```

manifest.json：

```json
{
  "type": "resource",
  "id": "com.example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "entry": "index.html",
  "minAppVersion": "1.0.5",
  "permissions": ["terminal.read"]
}
```

index.html：

```html
<!DOCTYPE html>
<html><body>
<script>
  CFA.request("terminal.status").then(d => {
    document.body.textContent = "容器已安装: " + d.installed;
  }).catch(e => document.body.textContent = "错误: " + e.code);
</script>
</body></html>
```

安装与启用：

```
pmc local-load /sdcard/Download/hello.zip
```

悬浮球菜单 →「加载资源包」→ 选择 com.example.hello。

### 6.2 hello 插件（注入任意页面）

```js
// main.js —— 页面右上角显示容器状态
CFA.request("terminal.status").then(d => {
  const el = document.createElement("div");
  el.textContent = "alpine: " + (d.installed ? "ready" : "missing");
  el.style.cssText = "position:fixed;top:4px;right:4px;font-size:12px;opacity:.6";
  document.body.appendChild(el);
});
```

```
pmc local-load /sdcard/Download/hello-plugin.zip
```

插件加载：悬浮球 →「加载资源包」→ 选包 → 多选插件弹窗勾选。

## 7. 错误码

| code | HTTP | 语义 |
|---|---|---|
| `bad_request` | 200 | 请求体/参数不合法 |
| `unknown_action` | 200 | action 不在白名单（终端显示「资源包错误」） |
| `permission_denied` | 200 | 权限未声明或高危未授权 |
| `forbidden` | 403 | CWD 沙箱越界 |
| `handler_error` | 200 | action 执行异常 |
| `bridge_error` | 200 | 通信桥异常 |
```
