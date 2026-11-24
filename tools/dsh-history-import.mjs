#!/usr/bin/env node
/**
 * DSH 会话历史导入 / 校验工具（幂等，可重复执行）
 * ---------------------------------------------------------------------------
 * 把一个「DSH 会话历史迁移包」（sessions/ + storages/workspace.json + attachments/）
 * 合并进本机的 DSH_HOME（默认 %USERPROFILE%\.dsh），并在导入后做完整性校验。
 *
 * 为什么不是简单复制：
 *   1) sessions 目录名是「工作区路径」编码来的（D:\project\test\wanhongyy ->
 *      --D-project-test-wanhongyy--），路径不同的机器必须改名；
 *   2) storages/workspace.json 是登记表，直接覆盖会丢掉本机已有的工作区；
 *      而 DSH 有一条不变式：同一个 path 只允许被一个 workspace 记录占有，
 *      所以正确做法是把历史 sessionId **合并**进本机同路径的那条记录；
 *   3) 会话日志是「多帧拼接 zstd」，普通解压只出第一帧，必须逐帧扫描才能校验。
 *
 * 用法：
 *   node tools/dsh-history-import.mjs --source D:\project\test\history
 *   node tools/dsh-history-import.mjs --source <包目录> --dsh-home <DSH_HOME> --dry-run
 *
 * 退出码：0 成功；1 参数/结构错误或有校验失败。
 */
import { createHash, randomUUID } from 'node:crypto';
import {
  cpSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { zstdDecompressSync } from 'node:zlib';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
const SESSION_DIR_PATTERN = /^session-[0-9a-f-]{36}$/i;

// --------------------------------------------------------------------------- utils

function parseArgs(argv) {
  const options = { source: null, dshHome: join(homedir(), '.dsh'), dryRun: false, force: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--source' || arg === '-s') options.source = argv[++i];
    else if (arg === '--dsh-home') options.dshHome = argv[++i];
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--force') options.force = true;
    else if (arg === '--help' || arg === '-h') options.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return options;
}

/** DSH 的工作区目录命名：去掉盘符冒号，路径分隔符换成 '-'，首尾各加 '--'。 */
function workspaceDirName(workspacePath) {
  return `--${workspacePath.replace(/:/g, '').replace(/[\\/]/g, '-')}--`;
}

/** 归一化路径用于比较：统一分隔符、去掉末尾分隔符、Windows 下忽略大小写。 */
function normalizePath(value) {
  let path = String(value).replace(/[\\/]+/g, '/').replace(/\/+$/, '');
  if (process.platform === 'win32') path = path.toLowerCase();
  return path;
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function readJson(file) {
  return JSON.parse(readFileSync(file, 'utf8'));
}

/** 逐帧解压拼接式 zstd 日志（普通 zstdDecompressSync 只会解出第一帧）。 */
function decodeSessionLog(file) {
  const buffer = readFileSync(file);
  const offsets = [];
  let index = buffer.indexOf(ZSTD_MAGIC, 0);
  while (index !== -1) {
    offsets.push(index);
    index = buffer.indexOf(ZSTD_MAGIC, index + 4);
  }
  const chunks = [];
  let frames = 0;
  for (let i = 0; i < offsets.length; i++) {
    const start = offsets[i];
    const end = i + 1 < offsets.length ? offsets[i + 1] : buffer.length;
    try {
      chunks.push(zstdDecompressSync(buffer.subarray(start, end)).toString('utf8'));
      frames++;
    } catch {
      // 压缩数据里恰好出现魔数会产生假帧，解压失败是正常的，跳过
    }
  }
  const lines = chunks.join('').split('\n').filter((line) => line.trim());
  let header = null;
  for (const line of lines) {
    try {
      const event = JSON.parse(line);
      if (event.type === 'session') { header = event; break; }
    } catch { /* 非 JSON 行 */ }
  }
  return { frames, events: lines.length, header };
}

function info(message) { console.log(message); }
function step(message) { console.log(`\n== ${message}`); }

// ---------------------------------------------------------------------- 导入主流程

function run(options) {
  const source = resolve(options.source ?? '');
  const dshHome = resolve(options.dshHome);
  const problems = [];
  const summary = { sessions: [], attachments: 0, workspace: null };

  if (!existsSync(source)) throw new Error(`迁移包目录不存在：${source}`);
  if (!existsSync(join(source, 'sessions'))) throw new Error(`迁移包结构不对，缺少 sessions/：${source}`);

  const sourceRegistryPath = join(source, 'storages', 'workspace.json');
  const localRegistryPath = join(dshHome, 'storages', 'workspace.json');
  if (!existsSync(localRegistryPath)) {
    throw new Error(`本机 DSH 登记表不存在：${localRegistryPath}\n（先启动一次 DSH，让它生成 ${join(dshHome, 'storages')}）`);
  }

  const sourceRegistry = existsSync(sourceRegistryPath) ? readJson(sourceRegistryPath) : null;
  const localRegistry = readJson(localRegistryPath);

  // 源包里的工作区路径（优先取 export-info/登记表）
  const sourceWorkspaces = Object.values(sourceRegistry?.tables?.workspaces ?? {});
  if (sourceWorkspaces.length === 0) throw new Error('迁移包的 storages/workspace.json 里没有工作区记录');

  const localWorkspaces = localRegistry.tables.workspaces ?? {};
  const localByPath = new Map(
    Object.entries(localWorkspaces).map(([id, record]) => [normalizePath(record.path), { id, record }]),
  );

  step('迁移包');
  info(`  源目录     : ${source}`);
  info(`  DSH_HOME   : ${dshHome}`);
  if (options.dryRun) info('  模式       : dry-run（只报告，不写任何文件）');

  const sourceSessionRoot = join(source, 'sessions');
  const sourceProjectDirs = readdirSync(sourceSessionRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
  if (sourceProjectDirs.length === 0) throw new Error('迁移包 sessions/ 下没有工作区目录');

  for (const sourceWorkspace of sourceWorkspaces) {
    const workspacePath = sourceWorkspace.path;
    const sourceProjectDir = sourceProjectDirs.find((name) => name === workspaceDirName(workspacePath))
      ?? sourceProjectDirs[0];
    const localMatch = localByPath.get(normalizePath(workspacePath));
    const targetProjectDir = localMatch ? workspaceDirName(localMatch.record.path) : sourceProjectDir;
    const targetProjectPath = join(dshHome, 'sessions', targetProjectDir);

    info(`\n  工作区     : ${workspacePath}`);
    info(`  源分组目录 : sessions/${sourceProjectDir}`);
    info(`  目标分组   : sessions/${targetProjectDir}`);
    if (!localMatch) {
      info('  本机登记表 : 没有同路径工作区，将新建一条记录');
    } else if (normalizePath(localMatch.record.path) !== normalizePath(workspacePath)) {
      info(`  本机登记表 : 路径不同（本机为 ${localMatch.record.path}），按本机路径落盘`);
    }

    const sessionDirs = readdirSync(join(sourceSessionRoot, sourceProjectDir), { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && SESSION_DIR_PATTERN.test(entry.name))
      .map((entry) => entry.name)
      .sort();

    step(`会话日志（${sessionDirs.length} 个会话）`);
    const importedIds = [];
    for (const sessionId of sessionDirs) {
      const sourceSessionDir = join(sourceSessionRoot, sourceProjectDir, sessionId);
      const targetSessionDir = join(targetProjectPath, sessionId);
      // 同一会话可能有「多代」日志文件：DSH 升级格式时会保留 session.v3.* 并新写 session.v4.*，
      // 当前生效的是最高版本那一代。用 find() 只取一个会漏掉新代（历史回退到旧状态），
      // 所以这里复制全部代文件。
      const logNames = readdirSync(sourceSessionDir)
        .filter((name) => /^session\.v\d+\.jsonl(\.zstd)?$/.test(name))
        .sort();
      if (logNames.length === 0) {
        problems.push(`${sessionId}: 源目录里没有 session.v*.jsonl(.zstd)`);
        continue;
      }

      for (const logName of logNames) {
        const sourceLog = join(sourceSessionDir, logName);
        const targetLog = join(targetSessionDir, logName);
        const sourceSize = statSync(sourceLog).size;
        const decoded = decodeSessionLog(sourceLog);
        const version = decoded.header?.version ?? '?';
        const headerCwd = decoded.header?.cwd ?? '(缺失)';
        const label = `${sessionId}/${logName}`;

        if (normalizePath(headerCwd) !== normalizePath(workspacePath)) {
          problems.push(`${label}: 日志 header.cwd=${headerCwd} 与工作区路径 ${workspacePath} 不一致`
            + '（先用 tools/dsh-history-repath.mjs --cwd 改写）');
        }

        const exists = existsSync(targetLog);
        if (exists && !options.force) {
          const same = statSync(targetLog).size === sourceSize && sha256(targetLog) === sha256(sourceLog);
          info(`  = ${label} 已存在且${same ? '内容一致' : '内容不同'}，跳过（--force 可覆盖）`);
          if (!same) problems.push(`${label}: 目标已存在但内容不同，未覆盖`);
        } else if (options.dryRun) {
          info(`  + ${label} 待复制（v${version}, ${(sourceSize / 1024 / 1024).toFixed(2)} MB, ${decoded.frames} 帧, ${decoded.events} 事件）`);
        } else {
          mkdirSync(targetSessionDir, { recursive: true });
          cpSync(sourceLog, targetLog, { force: true });
          const copiedSize = statSync(targetLog).size;
          const ok = copiedSize === sourceSize && sha256(targetLog) === sha256(sourceLog);
          info(`  + ${label} 已导入（v${version}, ${(sourceSize / 1024 / 1024).toFixed(2)} MB, ${decoded.frames} 帧, ${decoded.events} 事件）${ok ? '' : ' [校验失败]'}`);
          if (!ok) problems.push(`${label}: 复制后校验失败`);
        }
        summary.sessions.push({ id: sessionId, file: logName, bytes: sourceSize, frames: decoded.frames, events: decoded.events, version });
      }
      importedIds.push(sessionId);
    }

    // ------------------------------------------------- 合并登记表（不覆盖本机记录）
    step('工作区登记表 storages/workspace.json');
    if (localMatch) {
      const record = localWorkspaces[localMatch.id];
      const existing = Array.isArray(record.sessionIds) ? record.sessionIds : [];
      const added = importedIds.filter((id) => !existing.includes(id));
      // DSH 启动时 bootstrap() 会把「历史会话」排在本机会话之前，这里保持一致
      const merged = [...added, ...existing];
      if (added.length === 0) {
        info(`  = 工作区 ${localMatch.id} 已包含全部历史会话，无需改动`);
      } else if (options.dryRun) {
        info(`  + 将向工作区 ${localMatch.id} 加入 ${added.length} 条会话：${added.join(', ')}`);
      } else {
        record.sessionIds = merged;
        record.updatedAt = new Date().toISOString();
        summary.workspace = { id: localMatch.id, added };
        info(`  + 已向工作区 ${localMatch.id} 加入 ${added.length} 条会话：${added.join(', ')}`);
        info(`    最终 sessionIds（${merged.length} 条）：${merged.join(', ')}`);
      }
    } else if (options.dryRun) {
      info('  + 将新建工作区记录');
    } else {
      const id = randomUUID();
      const historical = importedIds;
      localWorkspaces[id] = {
        path: workspacePath,
        title: workspacePath.replace(/[\\/]+$/, '').split(/[\\/]/).pop(),
        sessionIds: historical,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      localRegistry.global.workspaceIds = [...(localRegistry.global.workspaceIds ?? []), id];
      summary.workspace = { id, added: historical };
      info(`  + 已新建工作区 ${id}（${historical.length} 条会话）`);
    }
  }

  // ------------------------------------------------------------------ 附件
  const sourceAttachments = join(source, 'attachments');
  if (existsSync(sourceAttachments) && !options.dryRun) {
    step('附件 attachments/');
    const targetAttachments = join(dshHome, 'attachments');
    mkdirSync(targetAttachments, { recursive: true });
    // 附件复制失败不应中断整个导入：同一台机器上附件本来就在目标位置（甚至可能被
    // 运行中的 DSH 占用），这时复制是多余的；而且真实目的是别让失败挡住后面的
    // 「登记表落盘」——否则会留下「日志已复制、却没登记」的半成品状态。
    try {
      cpSync(sourceAttachments, targetAttachments, { recursive: true, force: true });
    } catch (error) {
      info(`  ! 附件复制失败（已跳过，不影响会话导入）：${error.message}`);
      problems.push(`附件复制失败（已跳过）：${error.message}`);
    }
    let count = 0;
    const walk = (dir) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (entry.isDirectory()) walk(join(dir, entry.name));
        else count++;
      }
    };
    walk(sourceAttachments);
    summary.attachments = count;
    info(`  + 源附件 ${count} 个，目标目录 ${targetAttachments}`);
  } else if (existsSync(sourceAttachments) && options.dryRun) {
    step('附件 attachments/');
    info('  + 待合并到 ' + join(dshHome, 'attachments'));
  }

  // -------------------------------------------------------------- 落盘登记表
  if (!options.dryRun && summary.workspace !== null) {
    const backupDir = join(dshHome, `dsh-import-backup-${new Date().toISOString().replace(/[:.]/g, '-')}`);
    mkdirSync(backupDir, { recursive: true });
    cpSync(localRegistryPath, join(backupDir, 'workspace.json.before'));
    info(`\n  原登记表已备份：${join(backupDir, 'workspace.json.before')}`);
    writeFileSync(localRegistryPath, `${JSON.stringify(localRegistry, null, 2)}\n`, 'utf8');
    info(`  登记表已写回：${localRegistryPath}`);
  }

  // -------------------------------------------------------------- 校验
  step('导入结果校验');
  const recheck = options.dryRun ? localRegistry : readJson(localRegistryPath);
  const recheckWorkspaces = Object.values(recheck.tables.workspaces ?? {});
  for (const record of recheckWorkspaces) {
    const projectDir = join(dshHome, 'sessions', workspaceDirName(record.path));
    if (!existsSync(projectDir)) continue;
    const onDisk = readdirSync(projectDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && SESSION_DIR_PATTERN.test(entry.name))
      .map((entry) => entry.name);
    const registered = new Set(record.sessionIds ?? []);
    const orphans = onDisk.filter((id) => !registered.has(id));
    info(`  ${record.path}`);
    info(`    登记 ${registered.size} 条 / 磁盘 ${onDisk.length} 条${orphans.length ? `（${orphans.length} 条未登记，DSH 启动时会自动补登）` : ''}`);
  }

  step('结论');
  if (problems.length === 0) {
    info('  全部校验通过。');
  } else {
    info(`  有 ${problems.length} 处需要注意：`);
    for (const problem of problems) info(`    - ${problem}`);
  }
  info(options.dryRun
    ? '\n  dry-run 结束，未写入任何文件。'
    : '\n  完成。请**重启 DSH**（新会话列表在启动时重建），历史会话即出现在该项目下。');
  return problems.length === 0 ? 0 : 1;
}

// --------------------------------------------------------------------------- entry

const options = parseArgs(process.argv.slice(2));
if (options.help) {
  console.log('用法: node tools/dsh-history-import.mjs --source <迁移包目录> [--dsh-home <DSH_HOME>] [--dry-run] [--force]');
  process.exit(0);
}
if (options.source === null) {
  console.error('缺少 --source <迁移包目录>，用 --help 查看用法。');
  process.exit(2);
}
try {
  process.exit(run(options));
} catch (error) {
  console.error(`\n导入失败：${error.message}`);
  process.exit(1);
}
