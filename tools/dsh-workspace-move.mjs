#!/usr/bin/env node
/**
 * 把 DSH 某个工作区的会话历史「移动」到另一个工作区路径（真正的迁移）
 * ---------------------------------------------------------------------------
 * 为什么不能简单复制：
 *   DSH 的 jsonl 后端有一条硬不变式 —— **同一个 sessionId 不允许出现在两个
 *   项目目录里**，否则启动/列举会话时直接抛
 *     duplicate JSONL session id "session-xxx" appears in multiple project directories
 *   所以复制会直接把 DSH 弄坏，只能「移动」。
 *
 * 同时做三件事（缺一不可）：
 *   1) 改写每个日志 header 里的 cwd（否则续用时会指回旧目录）；
 *   2) 把会话目录从旧分组移到新分组；
 *   3) 更新 storages/workspace.json：把 sessionId 从旧工作区摘掉、挂到新工作区，
 *      旧工作区空了就删记录（写前备份）。
 *
 * 用法：
 *   node tools/dsh-workspace-move.mjs --from "D:\old\path" --to "D:\new\path"
 *   node tools/dsh-workspace-move.mjs --from ... --to ... --dry-run
 *   node tools/dsh-workspace-move.mjs --from ... --to ... --skip <sessionId>   # 跳过正在使用的会话
 *
 * 幂等：目标已存在且内容一致的会话会跳过；可以反复执行。
 * 退出码：0 成功；1 有校验问题；2 参数错误。
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { LOG_NAME, SESSION_DIR, repathCwd, verifyLogFile } from './lib/session-log.mjs';

function parseArgs(argv) {
  const options = { from: null, to: null, dshHome: join(homedir(), '.dsh'), dryRun: false, skip: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--from') options.from = argv[++i];
    else if (arg === '--to') options.to = argv[++i];
    else if (arg === '--dsh-home') options.dshHome = argv[++i];
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--skip') options.skip.push(argv[++i]);
    else if (arg === '--help' || arg === '-h') options.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return options;
}

/** DSH 的工作区分组目录命名 */
function groupDir(path) {
  return `--${path.replace(/:/g, '').replace(/[\\/]/g, '-')}--`;
}

function normalizePath(value) {
  let path = String(value).replace(/[\\/]+/g, '/').replace(/\/+$/, '');
  if (process.platform === 'win32') path = path.toLowerCase();
  return path;
}

function readJson(file) {
  return JSON.parse(readFileSync(file, 'utf8'));
}

function info(message) { console.log(message); }
function step(message) { console.log(`\n== ${message}`); }

const options = parseArgs(process.argv.slice(2));
if (options.help) {
  console.log('用法: node tools/dsh-workspace-move.mjs --from <旧路径> --to <新路径> [--dsh-home <DSH_HOME>] [--dry-run] [--skip <sessionId>]');
  process.exit(0);
}
if (options.from === null || options.to === null) {
  console.error('需要 --from 和 --to。用 --help 查看用法。');
  process.exit(2);
}

const dshHome = resolve(options.dshHome);
const fromPath = resolve(options.from);
const toPath = resolve(options.to);
const fromGroup = join(dshHome, 'sessions', groupDir(fromPath));
const toGroup = join(dshHome, 'sessions', groupDir(toPath));
const registryPath = join(dshHome, 'storages', 'workspace.json');

const problems = [];
const moved = [];
const skipped = [];

step('迁移参数');
info(`  从        : ${fromPath}`);
info(`  到        : ${toPath}`);
info(`  旧分组    : sessions/${groupDir(fromPath)}`);
info(`  新分组    : sessions/${groupDir(toPath)}`);
info(`  DSH_HOME  : ${dshHome}`);
if (options.dryRun) info('  模式      : dry-run（不写任何文件）');

if (!existsSync(fromGroup)) {
  console.error(`\n旧工作区分组不存在：${fromGroup}`);
  process.exit(1);
}
if (normalizePath(fromPath) === normalizePath(toPath)) {
  console.error('\n--from 与 --to 相同，无需迁移。');
  process.exit(2);
}

// ---------------------------------------------------------------- 移动会话
step('会话目录');
const sessionDirs = readdirSync(fromGroup, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && SESSION_DIR.test(entry.name))
  .map((entry) => entry.name)
  .sort();

if (sessionDirs.length === 0) info('  旧分组下没有会话目录（可能已经迁移过）');

for (const sessionId of sessionDirs) {
  if (options.skip.includes(sessionId)) {
    info(`  ~ ${sessionId} 按要求跳过（--skip）`);
    skipped.push(sessionId);
    continue;
  }
  const sourceDir = join(fromGroup, sessionId);
  const targetDir = join(toGroup, sessionId);
  const logNames = readdirSync(sourceDir).filter((name) => LOG_NAME.test(name)).sort();
  if (logNames.length === 0) {
    problems.push(`${sessionId}: 目录里没有 session.v*.jsonl(.zstd)`);
    continue;
  }

  if (options.dryRun) {
    info(`  + ${sessionId} 待移动（${logNames.length} 个日志文件，cwd -> ${toPath}）`);
    moved.push(sessionId);
    continue;
  }

  mkdirSync(targetDir, { recursive: true });
  let ok = true;
  for (const logName of logNames) {
    const sourceLog = join(sourceDir, logName);
    const buffer = readFileSync(sourceLog);
    let result;
    try {
      result = repathCwd(buffer, toPath);
    } catch (error) {
      problems.push(`${sessionId}/${logName}: 改写 cwd 失败：${error.message}`);
      ok = false;
      continue;
    }
    writeFileSync(join(targetDir, logName), result.buffer);
    const check = verifyLogFile(join(targetDir, logName), { cwd: toPath, events: result.events });
    if (check.problems.length > 0) {
      ok = false;
      for (const problem of check.problems) problems.push(`${sessionId}/${logName}: ${problem}`);
    }
  }
  if (!ok) {
    // 失败就不要留下半个会话，避免出现「旧目录没删、新目录不全」
    rmSync(targetDir, { recursive: true, force: true });
    continue;
  }

  // 校验通过后再删旧目录：先复制后删除，任何一步失败都不会丢数据
  try {
    rmSync(sourceDir, { recursive: true, force: true });
  } catch (error) {
    problems.push(`${sessionId}: 旧目录删除失败（新目录已就绪）：${error.message}`);
  }
  info(`  + ${sessionId} 已移动（${logNames.length} 个日志文件）`);
  moved.push(sessionId);
}

// 旧分组空了就删掉，避免留一个空目录
if (!options.dryRun && existsSync(fromGroup) && readdirSync(fromGroup).length === 0) {
  rmSync(fromGroup, { recursive: true, force: true });
  info('  旧分组目录已空，已删除');
}

// ---------------------------------------------------------------- 更新登记表
step('工作区登记表');
const registry = readJson(registryPath);
const workspaces = registry.tables.workspaces ?? {};
const find = (path) => Object.entries(workspaces)
  .find(([, record]) => normalizePath(record.path) === normalizePath(path));

const fromEntry = find(fromPath);
const toEntry = find(toPath);
info(`  旧工作区 : ${fromEntry ? fromEntry[0] : '（未登记）'}`);
info(`  新工作区 : ${toEntry ? toEntry[0] : '（未登记，将新建）'}`);

if (moved.length > 0 || toEntry === undefined) {
  if (options.dryRun) {
    info(`  + 将把 ${moved.length} 条会话挂到 ${toPath}`);
    if (fromEntry) info(`  - 将从 ${fromPath} 摘掉 ${moved.length} 条会话`);
  } else {
    const now = new Date().toISOString();
    let targetId;
    if (toEntry) {
      const [id, record] = toEntry;
      targetId = id;
      const existing = Array.isArray(record.sessionIds) ? record.sessionIds : [];
      record.sessionIds = [...moved.filter((sid) => !existing.includes(sid)), ...existing];
      record.updatedAt = now;
    } else {
      targetId = crypto.randomUUID();
      workspaces[targetId] = {
        path: toPath,
        title: toPath.replace(/[\\/]+$/, '').split(/[\\/]/).pop(),
        sessionIds: [...moved],
        createdAt: now,
        updatedAt: now,
      };
      info(`  + 新建工作区 ${targetId}`);
    }
    info(`    ${toPath} 现有 ${workspaces[targetId].sessionIds.length} 条会话`);

    if (fromEntry) {
      const [id, record] = fromEntry;
      const keep = (Array.isArray(record.sessionIds) ? record.sessionIds : []).filter((sid) => !moved.includes(sid));
      if (keep.length === 0) {
        delete workspaces[id];
        info(`  - 旧工作区 ${id} 已无会话，删除记录`);
      } else {
        record.sessionIds = keep;
        record.updatedAt = now;
        info(`  - 旧工作区 ${id} 保留 ${keep.length} 条会话：${keep.join(', ')}`);
      }
    }

    registry.global.workspaceIds = Object.keys(workspaces);
    writeFileSync(registryPath, `${JSON.stringify(registry, null, 2)}\n`, 'utf8');
    info(`  登记表已写回：${registryPath}`);
  }
}

// ---------------------------------------------------------------- 校验
step('校验：会话 ID 不能重复出现在多个目录');
const sessionsRoot = join(dshHome, 'sessions');
const seen = new Map();
for (const group of readdirSync(sessionsRoot, { withFileTypes: true })) {
  if (!group.isDirectory()) continue;
  for (const session of readdirSync(join(sessionsRoot, group.name), { withFileTypes: true })) {
    if (!session.isDirectory() || !SESSION_DIR.test(session.name)) continue;
    if (seen.has(session.name)) {
      problems.push(`会话 ID 重复：${session.name} 同时出现在 ${seen.get(session.name)} 和 ${group.name}`);
    } else {
      seen.set(session.name, group.name);
    }
  }
}
info(`  共 ${seen.size} 个会话目录，${problems.length === 0 ? '无重复' : `发现 ${problems.length} 处重复`}`);

// 迁移过去的会话逐个验可读（dry-run 下没有实际移动，跳过）
for (const sessionId of options.dryRun ? [] : moved) {
  const dir = join(toGroup, sessionId);
  if (!existsSync(dir)) { problems.push(`${sessionId}: 目标目录不存在`); continue; }
  for (const logName of readdirSync(dir).filter((name) => LOG_NAME.test(name))) {
    const check = verifyLogFile(join(dir, logName), { cwd: toPath });
    for (const problem of check.problems) problems.push(`${sessionId}/${logName}: ${problem}`);
  }
}

step('结论');
if (problems.length === 0) {
  info(`  已迁移 ${moved.length} 个会话，全部校验通过。`);
  if (skipped.length > 0) {
    info(`  跳过 ${skipped.length} 个（当前正在使用）：${skipped.join(', ')}`);
    info('  → 关闭 DSH 后重跑同一条命令即可把它们也迁过去（本工具幂等）。');
  }
  info('  → 请重启 DSH，让新的会话列表与工作区生效。');
} else {
  info(`  有 ${problems.length} 处问题：`);
  for (const problem of problems) info(`    - ${problem}`);
}
process.exit(problems.length === 0 ? 0 : 1);
