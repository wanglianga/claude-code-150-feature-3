# 工地临时材料领用退库损耗与班组核算平台

面向项目部的材料全链路管理平台：把 **采购入库 → 质检 → 预算 → 领料 → 退库 → 损耗 → 变更签证 → 盘点 → 班组月度核算 → 财务结算/索赔** 放在同一条材料链路上，按楼栋、施工区域、工序、图纸和班组串接。

技术栈：**React 18 + Vite**（前端）、**Spring Boot 3 + Spring Security(JWT) + Spring Data JPA**（后端）、**MySQL 8**（生产库，Compose 部署）、H2（本地零依赖演示库）。

## 一、功能总览

| 角色 | 账号 | 能做什么 |
| --- | --- | --- |
| 项目经理 PM | `pm / pm123` | 查看某楼栋/工序/班组的真实材料消耗与偏差、变更签证确认、**盘点差异进度影响确认与受限班组领料逐单审批**、核算与财务总览 |
| 仓管/材料员 | `keeper / keeper123` | 入库登记与批次质检、发料核对、退库确认、损耗核销、盘点差异追查与定性、损耗月底复盘 |
| 预算员 | `budget / budget123` | 编制预算、用实际消耗反推后续采购计划与分包结算量、确认签证 |
| 财务 | `finance / finance123` | 供应商结算、班组扣款、变更索赔申报、月度核算确认 |
| 班组长 | `foreman1..5 / foreman123` | 按楼栋/工序/图纸申请领料（含夜间紧急）、办理退库、查看本班组核算 |

覆盖的业务场景：临时追加领料、规格领错退库、施工变更退库、瓷砖破损、砂浆过期、夜间紧急领料（先发后补）、供应商补货延迟/质量不合格、**月底盘点账实差异追查（冻结领用→五向核查→公共损耗/夜料补录→PM 进度确认→关闭解冻）**、班组未登记夜料补录后的加严审批。

核心口径：

- 批次现场库存 = 入库量 − 已领量 + 退库量 − 损耗量 + 盘点调整量；质检不合格/待检批次不可发料。
- 真实（净）消耗 = 已发料金额 − 完好退库金额；损坏退库不回补库存，计入班组责任损耗。
- 月底班组核算：预算金额、实际领用、退库、损耗按责任归属（班组/供应商/设计变更/项目承担/不可抗力）拆分；
  **班组扣款 = 班组责任损耗（含损坏退库）+ 正偏差（净消耗−预算的超耗部分）**；供应商质量损耗单列索赔，不扣班组。
- **月底盘点账实差异追查闭环**：盘亏材料在盘点单关闭前冻结继续领用；仓管按
  入库 → 领用 → 退库 → 破损登记 → 监控交接 五条线核查留痕；
  无法定位责任的差异计入**项目公共损耗**（生成 COMPANY 损耗单核销批次，不扣班组）；
  监控交接发现某班组**未登记夜间领料**时，由仓管补录领料单（按夜间实际领料时间回写批次、计入班组当月成本），
  该班组后续领料自动转为**项目经理逐单审批**，PM 可在整改确认后解除限制；
  关闭前必须由**项目经理确认差异是否影响现场施工进度**，全部盘亏定性轧平后才可关闭并解除冻结。

## 二、一键启动（Docker Compose，推荐）

前置：安装 Docker / Docker Compose。

```bash
cd 150-b
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
4. **仓管 keeper**：「损耗登记」登记瓷砖破损（班组）、砂浆过期（班组）、供应商质量问题；进入「盘点差异追查」处理月底两单盘点差异；「损耗月底复盘」查看按设计变更/施工错误/保管不当/供应商质量归类的损耗结构。
   - 推荐先打开 **PD20261003002（瓷砖盘亏 40 箱）**：五条线核查轨迹已预置（入库/有单领用/退库/破损均相符，监控交接锁定瓷砖班夜间运出 30 箱、另 10 箱监控盲区）。
   - 对盘亏行点「补录夜料」：责任班组选瓷砖班、数量 30、时间 2026-10-02 23:40 → 生成 BL 补录领料单，瓷砖班随即被标记"加严审批"；
   - 剩余 10 箱点「公共损耗」→ 生成 SH 损耗单（项目承担，不扣班组）；
   - 切换 **pm** 在同一界面「项目经理确认进度影响」（影响/不影响+处置说明），回到 keeper 点「轧账关闭并解冻」；
   - 关闭前可在「领料单」验证：瓷砖（防滑地砖）处于冻结期发料被拦截；关闭后恢复发料；瓷砖班新申请进入"待PM审批"，PM 批准后仓管才能发料，PM 可在「材料/供应商/班组」页解除限制。
5. **项目经理 pm**：「楼栋消耗与偏差」按 1#楼/工序/班组过滤查看真实消耗与超耗；「变更签证」查看并确认设计变更签证。
6. **预算员 budget**：「采购计划反推」查看建议采购量、补货延迟风险与分包结算复核口径。
7. **财务 finance**：「班组月度核算」选 2026-10 一键生成全部班组核算单并确认；「财务结算与索赔」查看供应商应付（扣不合格批次）、班组扣款、签证索赔。

## 五、主要 HTTP 接口（JWT：`Authorization: Bearer <token>`）

| 模块 | 接口 |
| --- | --- |
| 认证 | `POST /api/auth/login` |
| 元数据 | `GET /api/meta/options`（材料/供应商/班组/楼栋/工序下拉） |
| 库存/批次 | `GET /api/inventory`、`GET /api/batches`、`POST /api/batches/inbound`、`PUT /api/batches/{id}/qc` |
| 预算 | `GET/POST /api/budgets` |
| 领料 | `GET /api/requisitions`、`POST /api/requisitions`、`GET /api/requisitions/{id}/precheck`、`PUT /api/requisitions/{id}/issue`、`PUT /api/requisitions/{id}/reject` |
| 退库 | `GET/POST /api/returns`、`PUT /api/returns/{id}/confirm`、`PUT /api/returns/{id}/reject` |
| 损耗 | `GET/POST /api/wastes` |
| 变更签证 | `GET/POST /api/visas`、`PUT /api/visas/{id}/confirm`、`PUT /api/visas/{id}/claim` |
| 盘点追查 | `GET/POST /api/stock-checks`、`POST /api/stock-checks/{id}/traces`（入库/领用/退库/破损/监控交接轨迹）、`POST /api/stock-checks/{id}/common-loss`（公共损耗定性）、`POST /api/stock-checks/{id}/supplement-night`（夜料补录）、`PUT /api/stock-checks/{id}/progress`（PM 进度确认）、`PUT /api/stock-checks/{id}/close`（轧账关闭解冻） |
| 领料审批 | `PUT /api/requisitions/{id}/pm-approve`（受限班组 PM 逐单批准）、`PUT /api/crews/{id}/release-restriction`（PM 解除加严限制） |
| 月度核算 | `GET /api/settlements`、`POST /api/settlements/generate`、`PUT /api/settlements/{id}/confirm` |
| 分析 | `GET /api/analytics/consumption`、`/waste-review`、`/procurement-plan`、`/finance` |

curl 示例：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"keeper","password":"keeper123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://localhost:8080/api/inventory -H "Authorization: Bearer $TOKEN"
```

## 六、测试

```bash
cd backend
mvn test        # 全链路集成测试（H2 + 种子数据，见 MaterialFlowIntegrationTest）
```

测试覆盖：登录鉴权与角色权限（401/403）、发料前预算/已领/库存核对、库存不足拦截、夜间紧急发料、规格领错退库回补、
**月底瓷砖盘点差异追查全闭环**（冻结期发料/申请拦截、监控交接轨迹 403 权限、未定性与未确认进度禁止关闭、
30 箱夜料补录生成 BL 单并加严班组、10 箱公共损耗生成 SH 单、PM 进度确认、混合定性 CLOSED、关闭后解冻发料、
补录金额计入瓷砖班当月核算 8700/扣款 464、受限班组 PENDING_PM→PM 批准→发料→PM 解除限制）、
月底核算金额口径（机电班领用 4460、退库 245、净消耗 4215、偏差 −6385）、损耗复盘（设计变更/供应商质量/盘点公共损耗）、
供应商质量索赔、采购计划反推。

## 七、工程结构

```
150-b/
├── docker-compose.yml         # mysql + backend(spring) + frontend(nginx)
├── backend/                   # Spring Boot 3 / Java 17
│   ├── Dockerfile
│   └── src/main/java/com/site/material/
│       ├── domain/            # 16 个实体（批次/预算/领料/退库/损耗/签证/盘点/盘点轨迹/核算…）
│       ├── repo/ service/ controller/
│       ├── security/          # JWT + 五种角色
│       └── config/DataInitializer.java   # 演示种子数据（全部场景）
└── frontend/                  # React 18 + Vite
    ├── Dockerfile nginx.conf
    └── src/pages/             # 15 个角色化页面
```

## 八、已执行的验证结论

- 后端 `mvn package` 编译通过；`mvn test` 集成测试 **1 个用例、全部通过**（H2 内存库，2026-10-04 执行）。
- 后端 jar 以默认 H2 配置实际启动成功（端口 8080），并以 keeper/pm/foreman3/finance 四个账号经 curl 实测了瓷砖盘点追查主链路：
  瓷砖建单即冻结（库存总览 `frozenCount=3`）→ 冻结期发料与班组申请均返回 400 → 补录 30 箱夜料生成 `BL...` 单且瓷砖班自动加严 →
  余 10 箱公共损耗生成 `SH...` 单 → 未确认进度关闭返回 400、仓管代确认 403、PM 确认后关闭为 `CLOSED/MIXED` →
  受限班组新单自动 `PENDING_PM`、仓管直发 400、PM 批准后发料成功、PM 解除限制成功 →
  瓷砖班 2026-10 核算含补录夜料（实测领用 8825、扣款 589，其中含 curl 追加发的 5 卷铁丝超预算 125 元，口径与公式一致）。
- 前端 `npm run build`（Vite）构建成功，产出 dist 静态资源。
- Docker Compose 配置已按多阶段构建编写；当前沙箱环境无 Docker 守护进程，未在本机实际执行 `docker compose up`，请在有 Docker 的机器上按第二节命令验证。
