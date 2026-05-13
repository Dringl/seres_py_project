# eVTOL LAN Demo (Android)

这是一个用于局域网演示的 Android MVP，已实现：

- 定位（Fused Location）
- 地图显示（天地图 DataServer，基于 osmdroid）
- 地面接驳路线（优先高德驾车路线 API，失败自动回退模拟路线）
- 附近 eVTOL 列表
- 选择目的停机坪
- 价格预估
- 下单、取消
- 订单状态自动流转（`RESERVED -> BOARDING -> IN_FLIGHT -> DONE`）
- Room 历史订单缓存
- 地图图标区分用户/停机坪/飞行器，支持“回到定位”按钮

当前版本默认使用**本地模拟仓库**（`FakeDispatchRepository`），不依赖后端即可演示完整流程。

## 技术栈

- Kotlin
- Jetpack Compose + MVVM
- Hilt
- Room
- Retrofit/OkHttp（已预置接口，便于后续接 LAN 后端）
- osmdroid + 天地图 + 高德备用底图
- 高德 Web API（驾车路线）

## 目录结构

- `app/src/main/java/com/seres/evtoldemo/ui`: 页面与 ViewModel
- `app/src/main/java/com/seres/evtoldemo/ui/map`: 天地图瓦片源实现
- `app/src/main/java/com/seres/evtoldemo/data/model`: 领域模型
- `app/src/main/java/com/seres/evtoldemo/data/repository`: 仓库层（含模拟匹配/锁定/状态机）
- `app/src/main/java/com/seres/evtoldemo/data/local`: Room
- `app/src/main/java/com/seres/evtoldemo/data/remote`: Retrofit DTO/API
- `app/src/main/java/com/seres/evtoldemo/di`: Hilt 依赖注入

## 运行步骤（Android Studio）

1. 用 Android Studio 打开项目根目录。
2. 安装 Android SDK 35（如提示）。
3. 复制 `gradle.properties.example` 为本地 `gradle.properties`，并填写 `TDT_TOKEN` 与 `GAODE_WEB_KEY`。
4. 同步 Gradle 并运行 `app` 到真机/模拟器。
5. 首次启动授予定位权限。
6. 按顺序演示：
   - 点击“刷新定位”
   - 选择目的停机坪
   - 查看预估价格
   - 点击“下单”
- 观察订单状态自动流转

地图页支持底图模式切换：`AUTO / TDT / GAODE / OSM`

- `AUTO`：优先天地图，鉴权失败自动降级高德
- `TDT`：强制天地图
- `GAODE`：高德深色底图（style=8）
- `OSM`：标准 OSM 底图

## Android Studio 一键运行（新手）

1. 打开 Android Studio，点击 `Open`，选择项目目录。
2. 等待右下角 `Gradle Sync` 完成（首次需要几分钟）。
3. 打开 `Device Manager`，新建并启动一个模拟器（如 Pixel 7 + Android 14/15）。
4. 顶部运行配置选择 `app`。
5. 点击绿色三角形 `Run`（或 `Shift + F10`）。

如果你本机提示 Gradle JVM 问题，手动选择 JDK 17：

- `File > Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`
- 选择 Android Studio 的内置 JDK 17，或你本机安装的 JDK 17。

## 局域网后端接入（下一步）

当前已预置 `DispatchApi`，后续你只需：

1. 把 `app/build.gradle.kts` 的 `BASE_URL` 改为局域网后端地址。
2. 新增 `LanDispatchRepository`（调用 Retrofit + WebSocket）。
3. 在 `AppModule` 中把 `DispatchRepository` 绑定从 `FakeDispatchRepository` 切换为 `LanDispatchRepository`。

## 已实现的匹配与锁定逻辑（模拟）

- 仅选择 `online=true && status=IDLE && battery >= 40%` 的机体。
- 按乘客位置最近距离选车。
- 下单后立即锁定机体状态为 `RESERVED`，不再参与匹配。

## 注意

- 当前天地图 key 在部分网络下可能返回 WMTS `403`，这通常是 token 权限范围问题。
- 若天地图不可用，`AUTO` 模式会自动回退到高德底图以保证可用性。
- 若要使用真实驾车路线，请在 `gradle.properties` 配置 `GAODE_WEB_KEY=你的高德Web服务Key`。
