# 生产数据库迁移

生产库 `ddl-auto=validate`，Hibernate 不会自动建表/加列，schema 变更必须先手动执行 SQL。

## 约定

- 每个迁移一个文件：`NNNN_描述.sql`（编号递增，永不重用/删除已提交的文件）
- 文件内容必须幂等（`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE` 等），防止重复执行报错
- `deploy.sh` 在上传 jar 前会比对本地 `migrations/*.sql` 与服务器 `/opt/blindrun/migrations_applied.log`，
  发现本地有、服务器记录里没有的文件会直接中止部署并打印文件名——**脚本不会自动执行 DDL**，
  需要人工确认 SQL 内容后手动在生产库执行，再手动把文件名追加进服务器的 `migrations_applied.log`
  （中止时的报错信息会给出对应的 ssh 命令，照着跑即可）。

## 历史记录

- `0001_run_order_track_point.sql` — 陪跑轨迹表 + 走散告警通知模板（2026-07-19 手动执行）
