# 工地临时材料领用退库损耗与班组核算平台

面向项目部的材料全链路管理平台：把 **采购入库 → 质检 → 预算 → 领料 → 退库 → 损耗 → 变更签证 → 盘点 → 班组月度核算 → 财务结算/索赔** 放在同一条材料链路上，按楼栋、施工区域、工序、图纸和班组串接。

技术栈：**React 18 + Vite**（前端）、**Spring Boot 3 + Spring Security(JWT) + Spring Data JPA**（后端）、**MySQL 8**（生产库，Compose 部署）、H2（本地零依赖演示库）。

## 一、功能总览

| 角色 | 账号 | 能做什么 |
| --- | --- | --- |
| 项目经理 PM | `pm / pm123` | 查看某楼栋/工序/班组的真实材料消耗与偏差、变更签证确认、核算与财务总览、盘点差异进度影响确认、受限班组领料审批与解除限制 |
| 仓管/材料员 | `keeper / keeper123` | 入库登记与批次质检、发料核对、退库确认、损耗核销、盘点差异追查（补录/公共损耗/关闭）、监控交接登记、损耗月底复盘 |
| 预算员 | `budget / budget123` | 编制预算、用实际消耗反推后续采购计划与分包结算量、确认签证 |
| 财务 | `finance / finance123` | 供应商结算、班组扣款、变更索赔申报、月度核算确认 |
| 班组长 | `foreman1..5 / foreman123` | 按楼栋/工序/图纸申请领料（含夜间紧急）、办理退库、查看本班组核算 |

覆盖的业务场景：临时追加领料、规格领错退库、施工变更退库、瓷砖破损、砂浆过期、夜间紧急领料（先发后补）、供应商补货延迟/质量不合格、盘点账实不符。

**月底盘点账实差异追查（本次扩展）**：仓库月底盘点发现防滑地砖实盘少于账面，平台按 **入库 → 领用 → 退库 → 破损登记 → 监控交接** 全链路追查差异：

- 差异关闭前，盘亏材料**限制继续领用**（发料核对与发料接口双重拦截）；
- 监控交接记录发现瓷砖班夜间领料未登记 → 平台**补录领料单**（夜间紧急·盘点补录，直接记已发料），计入班组成本，该班组**后续领料需项目经理审批**；
- 剩余无法定位责任的差异 → 转入**项目公共损耗**（项目承担，不归集班组，损耗台账留痕并进入月底复盘）；
- 盘亏差异全部处理完毕、且**项目经理确认施工进度影响**后，方可关闭盘点并按实盘调账。

核心口径：

- 批次现场库存 = 入库量 − 已领量 + 退库量 − 损耗量 + 盘点调整量；质检不合格/待检批次不可发料。
- 真实（净）消耗 = 已发料金额 − 完好退库金额；损坏退库不回补库存，计入班组责任损耗。
- 盘点差异核销 = 补录领料（真实发料冲减账面）+ 公共损耗（关闭调账时统一核销，不重复扣减批次）。
- 月底班组核算：预算金额、实际领用、退库、损耗按责任归属（班组/供应商/设计变更/项目承担/不可抗力）拆分；
  **班组扣款 = 班组责任损耗（含损坏退库）+ 正偏差（净消耗−预算的超耗部分）**；供应商质量损耗单列索赔，不扣班组；
  盘点补录领料计入班组实际领用，项目公共损耗（COMPANY）不归集到任何班组。

## 二、一键启动（Docker Compose，推荐）

前置：安装 Docker / Docker Compose。

```bash
cd 150-feature-3-a
docker compose up -d --build
```

- 前端：http://localhost （Nginx 容器，80 端口；如被占用可改 docker-compose.yml 的端口映射）
- 后端：http://localhost:8080
- MySQL：localhost:3306（库名 `material`，用户 `material/material`，root 密码 `root`）

首次启动后端会自动建表并写入覆盖全部场景的演示数据。停止：`docker compose down`；连数据一起清除：`docker compose down -v`。

## 三、本地开发启动

### 后端（默认 H2 内存库，无需安装数据库）

```bash
cd backend
mvn spring-boot:run
# 或：mvn package && java -jar target/material-platform.jar
```

- 服务地址 http://localhost:8080 ，H2 控制台 http://localhost:8080/h2-console
  （JDBC URL：`jdbc:h2:mem:materialdb`，用户名 `sa`，密码空）
- 切换 MySQL：以 `mysql` profile 启动并提供环境变量（见 `backend/src/main/resources/application-mysql.yml`）：

```bash
SPRING_PROFILES_ACTIVE=mysql \
MYSQL_HOST=localhost MYSQL_DB=material MYSQL_USER=root MYSQL_PASSWORD=root \
mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173 ，/api 已代理到 http://localhost:8080
npm run build    # 产出 dist/，可用 npm run preview 预览
```

> 若 npm 全局缓存目录有权限问题，可指定临时缓存：`npm_config_cache=/tmp/npm-cache npm install`

## 四、推荐验证路径（登录后按角色体验）

1. **仓管 keeper**：「现场库存总览」看库存价值/预警 →「入库批次与质检」把待检批次判合格（补货批次可判不合格）。
2. **班组长 foreman5（水电班孙明）**：「领料单」可见一张"夜间紧急"单；也可新建领料申请。
3. **仓管 keeper**：「领料单 → 核对发料」核对预算量/已领量/现场库存，选择批次、填实发数量后发料（超预算/库存不足会拦截或预警）；再到「退库单」确认规格领错/变更退库（完好回补库存）与损坏退库（班组责任）。
4. **仓管 keeper**：「损耗登记」登记瓷砖破损（班组）、砂浆过期（班组）、供应商质量问题；「损耗月底复盘」查看按设计变更/施工错误/保管不当/供应商质量归类的损耗结构。
5. **盘点差异追查（月底场景）**：
   - **仓管 keeper**：「盘点与差异追查」打开盘点单 `PD20261004001`（防滑地砖账面 875 / 实盘 855）→「追查差异」查看入库/领用/退库/损耗与监控交接线索（监控显示瓷砖班陈海夜间领走约 12 箱未登记）；期间「领料单」中瓷砖班待发料单被标记"限制领用"且无法发料。
   - 在追查工作台对该差异「补录班组领料」12 箱（瓷砖班）→ 生成盘点补录领料单（已发料，计入班组成本），瓷砖班被限制：新领料需项目经理审批；剩余 8 箱「转项目公共损耗」。
   - **项目经理 pm**：「确认影响」登记进度影响（如"轻微影响，已协调调拨"）；「领料单」中审批瓷砖班被限制的领料申请。
   - **仓管 keeper**：「关闭盘点」→ 账面调整为 855，瓷砖恢复领用；「材料/供应商/班组」中 PM 可解除瓷砖班的领料限制。
6. **项目经理 pm**：「楼栋消耗与偏差」按 1#楼/工序/班组过滤查看真实消耗与超耗；「变更签证」查看并确认设计变更签证。
7. **预算员 budget**：「采购计划反推」查看建议采购量、补货延迟风险与分包结算复核口径。
8. **财务 finance**：「班组月度核算」选 2026-10 一键生成全部班组核算单并确认（瓷砖班核算含补录领料 696 元）；「财务结算与索赔」查看供应商应付（扣不合格批次）、班组扣款、签证索赔。

## 五、主要 HTTP 接口（JWT：`Authorization: Bearer <token>`）

| 模块 | 接口 |
| --- | --- |
| 认证 | `POST /api/auth/login` |
| 元数据 | `GET /api/meta/options`（材料/供应商/班组/楼栋/工序下拉） |
| 库存/批次 | `GET /api/inventory`、`GET /api/batches`、`POST /api/batches/inbound`、`PUT /api/batches/{id}/qc` |
| 预算 | `GET/POST /api/budgets` |
| 领料 | `GET /api/requisitions`、`POST /api/requisitions`、`GET /api/requisitions/{id}/precheck`、`PUT /api/requisitions/{id}/issue`、`PUT /api/requisitions/{id}/approve`（PM 审批受限班组）、`PUT /api/requisitions/{id}/reject` |
| 退库 | `GET/POST /api/returns`、`PUT /api/returns/{id}/confirm`、`PUT /api/returns/{id}/reject` |
| 损耗 | `GET/POST /api/wastes` |
| 变更签证 | `GET/POST /api/visas`、`PUT /api/visas/{id}/confirm`、`PUT /api/visas/{id}/claim` |
| 盘点 | `GET/POST /api/stock-checks`、`GET /api/stock-checks/{id}/trace`（差异追查）、`POST /api/stock-checks/{id}/items/{itemId}/backfill`（补录领料）、`POST /api/stock-checks/{id}/items/{itemId}/common-loss`（转公共损耗）、`PUT /api/stock-checks/{id}/impact`（PM 确认进度影响）、`PUT /api/stock-checks/{id}/close`（关闭调账） |
| 监控交接 | `GET/POST /api/handovers` |
| 班组限制 | `PUT /api/crews/{id}/restriction`（PM 设置/解除领料审批限制） |
| 月度核算 | `GET /api/settlements`、`POST /api/settlements/generate`、`PUT /api/settlements/{id}/confirm` |
| 分析 | `GET /api/analytics/consumption`、`/waste-review`、`/procurement-plan`、`/finance` |

curl 示例：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"keeper","password":"keeper123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://localhost:8080/api/inventory -H "Authorization: Bearer $TOKEN"

# 盘点差异追查全流程（盘点单 id 见 GET /api/stock-checks，演示数据为 PD20261004001）
curl -s http://localhost:8080/api/stock-checks/2/trace -H "Authorization: Bearer $TOKEN"          # 追查线索
curl -s -X POST http://localhost:8080/api/stock-checks/2/items/<itemId>/backfill \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"crewId":3,"qty":12,"building":"1#楼","process":"墙地砖铺贴","remark":"监控核实夜间未登记领料"}'
curl -s -X POST http://localhost:8080/api/stock-checks/2/items/<itemId>/common-loss \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"qty":8,"building":"1#楼","remark":"尾差无法定位"}'
# 项目经理确认进度影响后，仓管关闭盘点
PM=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"pm","password":"pm123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -X PUT http://localhost:8080/api/stock-checks/2/impact -H "Authorization: Bearer $PM" \
  -H 'Content-Type: application/json' -d '{"impactLevel":"MINOR","impactNote":"已协调调拨，影响轻微"}'
curl -s -X PUT http://localhost:8080/api/stock-checks/2/close -H "Authorization: Bearer $TOKEN"
```

## 六、测试

```bash
cd backend
mvn test        # 全链路集成测试（H2 + 种子数据，见 MaterialFlowIntegrationTest）
```

测试覆盖：登录鉴权与角色权限（401/403）、发料前预算/已领/库存核对、库存不足拦截、夜间紧急发料、规格领错退库回补、
**盘点账实差异追查**（盘亏材料限制领用与发料拦截、追查线索完整性、补录领料计入班组成本并触发班组审批限制、
受限班组领料 PENDING_PM→PM 审批、公共损耗台账留痕、未确认进度影响禁止关闭、关闭后账面=实盘并解除限制）、
月底核算金额口径（机电班领用 4460、退库 245、净消耗 4215、偏差 −6385；瓷砖班补录后领用 7656、扣款 464）、
损耗复盘四类原因与公共损耗归类、供应商质量索赔、采购计划反推。

## 七、工程结构

```
150-feature-3-a/
├── docker-compose.yml         # mysql + backend(spring) + frontend(nginx)
├── backend/                   # Spring Boot 3 / Java 17
│   ├── Dockerfile
│   └── src/main/java/com/site/material/
│       ├── domain/            # 14 个实体（批次/预算/领料/退库/损耗/签证/盘点/监控交接/核算…）
│       ├── repo/ service/ controller/
│       ├── security/          # JWT + 五种角色
│       └── config/DataInitializer.java   # 演示种子数据（全部场景）
└── frontend/                  # React 18 + Vite
    ├── Dockerfile nginx.conf
    └── src/pages/             # 15 个角色化页面
```

## 八、已执行的验证结论

- 后端 `mvn package` 编译通过；`mvn test` 集成测试 **1 个用例、全部通过**（H2 内存库，含盘点差异追查全流程断言）。
- 后端 jar 以默认 H2 配置实际启动成功，经 curl 实测：登录、库存、领料 precheck/issue、
  盘点 trace/backfill/common-loss/impact/close、监控交接、受限班组 PENDING_PM→PM 审批、PM 解除限制等接口返回正常；
  实测关键结果：补录 12 箱后批次账面 863、公共损耗 8 箱留痕、PM 确认影响前关闭被拦截、关闭后账面=实盘 855、瓷砖班补录金额 696 元。
- 前端 `npm run build`（Vite）构建成功，产出 dist 静态资源。
- Docker Compose 配置已按多阶段构建编写；当前沙箱环境无 Docker 守护进程，未在本机实际执行 `docker compose up`，请在有 Docker 的机器上按第二节命令验证。
