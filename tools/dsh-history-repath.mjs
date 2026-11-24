#!/usr/bin/env node
/**
 * DSH 会话日志「改写工作区路径」工具（用于把历史迁移到新目录）
 * ---------------------------------------------------------------------------
 * 为什么需要它：
 *   会话日志的 header 里带 `cwd`（原工作区绝对路径）。把 sessions 目录整体复制到
 *   新路径后，历史会话能列出来，但续用时 cwd 仍指向旧目录 —— 轻则显示错路径，
 *   重则在新目录下点开却操作旧目录的文件。所以 cwd 必须跟着一起改。
 *
 * 做法（尽量少动数据）：
 *   日志是「多帧拼接 zstd」。只解压、改写、重压**含 header 的那一帧**，
 *   其余帧原样保留字节 —— 这样 99% 的内容不会被重编码，风险最小。
 *
 * 用法：
 *   node tools/dsh-history-repath.mjs --log <session.vN.jsonl.zstd> --cwd "D:\new\path"
 *   node tools/dsh-history-repath.mjs --log <file> --cwd <path> --dry-run
 *   node tools/dsh-history-repath.mjs --dir <sessions 分组目录> --cwd "D:\new\path"   # 批量
 *
 * 退出码：0 成功；1 校验失败或参数错误。
 */
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { zstdCompressSync, zstdDecompressSync } from 'node:zlib';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
const LOG_NAME = /^session\.v\d+\.jsonl(\.zstd)?$/;

function parseArgs(argv) {
  const options = { log: null, dir: null, cwd: null, dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--log') options.log = argv[++i];
    else if (arg === '--dir') options.dir = argv[++i];
    else if (arg === '--cwd') options.cwd = argv[++i];
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--help' || arg === '-h') options.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return options;
}

/** 按魔数切帧，返回 [{start,end}] */
function frameOffsets(buffer) {
  const offsets = [];
  let index = buffer.indexOf(ZSTD_MAGIC, 0);
  while (index !== -1) {
    offsets.push(index);
    index = buffer.indexOf(ZSTD_MAGIC, index + 4);
  }
  return offsets.map((start, i) => ({ start, end: i + 1 < offsets.length ? offsets[i + 1] : buffer.length }));
}

function countEvents(text) {
  return text.split('\n').filter((line) => line.trim()).length;
}

/** 找到 header 帧并改写 cwd；返回 {buffer, changed, frames, eventsBefore, eventsAfter, oldCwd, newCwd, headerFrame} */
function repath(buffer, newCwd, dryRun) {
  const frames = frameOffsets(buffer);
  let eventsBefore = 0;
  let changed = false;
  let oldCwd = null;
  let headerFrame = -1;
  const pieces = [];

  for (let i = 0; i < frames.length; i++) {
    const raw = buffer.subarray(frames[i].start, frames[i].end);
    let text = null;
    try {
      text = zstdDecompressSync(raw).toString('utf8');
    } catch {
      // 压缩数据里恰好出现魔数会产生假帧，原样保留
      pieces.push(raw);
      continue;
    }
    eventsBefore += countEvents(text);

    if (!changed && text.includes('"type":"session"')) {
      // header 在日志首行，用「首个匹配」精确替换，避免整帧重新序列化
      const match = text.match(/"cwd":"((?:[^"\\]|\\.)*)"/);
      if (!match) throw new Error('header 帧里找不到 cwd 字段');
      oldCwd = JSON.parse(`"${match[1]}"`);
      const from = `"cwd":${JSON.stringify(oldCwd)}`;
      const to = `"cwd":${JSON.stringify(newCwd)}`;
      if (!text.includes(from)) throw new Error(`header 帧里 cwd 文本不是预期的 ${from}`);
      const replaced = text.replace(from, to);
      if (replaced === text) throw new Error('cwd 替换未生效');
      changed = true;
      headerFrame = i;
      pieces.push(dryRun ? raw : zstdCompressSync(Buffer.from(replaced, 'utf8')));
      continue;
    }
    pieces.push(raw);
  }

  if (!changed) throw new Error('没有找到 header 帧（type=session）');
  const out = dryRun ? buffer : Buffer.concat(pieces);
  const eventsAfter = dryRun ? eventsBefore : countEvents(
    frameOffsets(out).map(({ start, end }) => {
      try { return zstdDecompressSync(out.subarray(start, end)).toString('utf8'); } catch { return ''; }
    }).join('\n'),
  );
  return { buffer: out, changed, frames: frames.length, eventsBefore, eventsAfter, oldCwd, headerFrame };
}

/** 改完再验一遍：所有帧可解、header 的 cwd 已是新值、事件数不变 */
function verify(file, newCwd, expectedEvents) {
  const buffer = readFileSync(file);
  const frames = frameOffsets(buffer);
  let events = 0;
  let header = null;
  for (const { start, end } of frames) {
    let text;
    try { text = zstdDecompressSync(buffer.subarray(start, end)).toString('utf8'); } catch { continue; }
    events += countEvents(text);
    if (header === null) {
      for (const line of text.split('\n')) {
        if (!line.includes('"type":"session"')) continue;
        try { header = JSON.parse(line); } catch { /* 非完整行 */ }
        if (header) break;
      }
    }
  }
  const problems = [];
  if (!header) problems.push('解不出 header');
  else if (header.cwd !== newCwd) problems.push(`header.cwd=${header.cwd}，期望 ${newCwd}`);
  if (expectedEvents !== undefined && events !== expectedEvents) {
    problems.push(`事件数变化：${expectedEvents} -> ${events}`);
  }
  return { frames: frames.length, events, header, problems };
}

function processFile(file, newCwd, dryRun) {
  const before = statSync(file).size;
  const source = readFileSync(file);
  const result = repath(source, newCwd, dryRun);
  console.log(`  ${basename(file)}`);
  console.log(`    帧 ${result.frames}  含 header 的帧 #${result.headerFrame}  事件 ${result.eventsBefore}`);
  console.log(`    cwd: ${result.oldCwd}  ->  ${newCwd}`);

  if (dryRun) {
    console.log('    (dry-run，未写文件)');
    return [];
  }

  writeFileSync(file, result.buffer);
  const check = verify(file, newCwd, result.eventsBefore);
  console.log(`    写入后：${(statSync(file).size / 1024 / 1024).toFixed(2)} MB（原 ${(before / 1024 / 1024).toFixed(2)} MB，帧 ${check.frames}，事件 ${check.events}）`);
  if (check.problems.length > 0) {
    for (const problem of check.problems) console.log(`    ! ${problem}`);
  } else {
    console.log('    校验通过');
  }
  return check.problems;
}

const options = parseArgs(process.argv.slice(2));
if (options.help) {
  console.log('用法: node tools/dsh-history-repath.mjs --log <file> --cwd <newPath> [--dry-run]');
  console.log('      node tools/dsh-history-repath.mjs --dir <sessions 分组目录> --cwd <newPath> [--dry-run]');
  process.exit(0);
}
if (options.cwd === null || (options.log === null && options.dir === null)) {
  console.error('需要 --cwd，以及 --log 或 --dir。用 --help 查看用法。');
  process.exit(2);
}

try {
  const targets = [];
  if (options.log !== null) {
    if (!existsSync(options.log)) throw new Error(`日志不存在：${options.log}`);
    targets.push(options.log);
  }
  if (options.dir !== null) {
    if (!existsSync(options.dir)) throw new Error(`目录不存在：${options.dir}`);
    for (const sessionDir of readdirSync(options.dir, { withFileTypes: true })) {
      if (!sessionDir.isDirectory()) continue;
      for (const name of readdirSync(join(options.dir, sessionDir.name))) {
        if (LOG_NAME.test(name)) targets.push(join(options.dir, sessionDir.name, name));
      }
    }
  }
  if (targets.length === 0) throw new Error('没有找到 session.v*.jsonl(.zstd) 文件');

  console.log(`目标 ${targets.length} 个日志文件，新工作区路径：${options.cwd}\n`);
  let failed = 0;
  for (const file of targets) {
    try {
      failed += processFile(file, options.cwd, options.dryRun).length;
    } catch (error) {
      failed++;
      console.log(`  FAIL ${basename(file)}: ${error.message}`);
    }
  }
  console.log(failed === 0 ? '\n全部完成，校验通过。' : `\n有 ${failed} 处问题。`);
  process.exit(failed === 0 ? 0 : 1);
} catch (error) {
  console.error(`失败：${error.message}`);
  process.exit(1);
}
