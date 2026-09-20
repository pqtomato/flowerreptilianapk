#!/usr/bin/env node
/**
 * 从 DSH 会话日志里恢复文件（重放 write / edit 工具调用）
 * ---------------------------------------------------------------------------
 * 用途：某个文件只存在于旧机器的**工作目录**里、却因为 .gitignore 规则写得太宽
 * 从来没被提交过时，项目的 git 仓库里就没有它 —— 但当时写文件的工具调用
 * 完整记在 DSH 的会话日志里（`tool/call` 事件带全部参数）。
 * 本脚本按 seq 顺序重放这些调用，重建文件的最终内容。
 *
 * 支持的工具：
 *   write  { file_path, content }
 *   edit   { file_path, old_string, new_string, replace_all? }
 *
 * 用法：
 *   # 只看匹配到哪些文件、各被写过多少次
 *   node tools/dsh-recover-files-from-session.mjs --log <session.v3.jsonl.zstd> --match "tender\\data"
 *
 *   # 真正落盘（--out 指定输出根目录；默认按日志里的绝对路径原样写回）
 *   node tools/dsh-recover-files-from-session.mjs --log <log> --match "tender\\data" --out <目录>
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { zstdDecompressSync } from 'node:zlib';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

function parseArgs(argv) {
  const options = { log: null, match: null, out: null, list: false, prefix: null, checkMissing: false, crlf: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--log') options.log = argv[++i];
    else if (arg === '--match') options.match = argv[++i];
    else if (arg === '--out') options.out = argv[++i];
    else if (arg === '--list') options.list = true;
    else if (arg === '--prefix') options.prefix = argv[++i];
    else if (arg === '--check-missing') options.checkMissing = true;
    else if (arg === '--crlf') options.crlf = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return options;
}

/** 逐帧解压拼接式 zstd 日志。 */
function decodeLog(file) {
  const buffer = readFileSync(file);
  const offsets = [];
  let index = buffer.indexOf(ZSTD_MAGIC, 0);
  while (index !== -1) {
    offsets.push(index);
    index = buffer.indexOf(ZSTD_MAGIC, index + 4);
  }
  const chunks = [];
  for (let i = 0; i < offsets.length; i++) {
    const start = offsets[i];
    const end = i + 1 < offsets.length ? offsets[i + 1] : buffer.length;
    try {
      chunks.push(zstdDecompressSync(buffer.subarray(start, end)).toString('utf8'));
    } catch { /* 压缩数据里的假帧，跳过 */ }
  }
  return chunks.join('').split('\n').filter((line) => line.trim());
}

/** 归一化路径用于匹配：统一分隔符、Windows 下忽略大小写。 */
function normalize(value) {
  let path = String(value).replace(/[\\/]+/g, '/');
  if (process.platform === 'win32') path = path.toLowerCase();
  return path;
}

const options = parseArgs(process.argv.slice(2));
if (options.log === null) {
  console.error('缺少 --log <session.v3.jsonl.zstd>，用 --help 无参查看顶部注释里的用法。');
  process.exit(2);
}
if (!existsSync(options.log)) {
  console.error(`日志不存在：${options.log}`);
  process.exit(2);
}

const needle = options.match === null ? null : normalize(options.match);
const files = new Map(); // 归一化路径 -> { path, content, writes, edits, failures }
const order = [];

for (const line of decodeLog(resolve(options.log))) {
  let event;
  try { event = JSON.parse(line); } catch { continue; }
  if (event.type !== 'tool/call') continue;
  const name = event.data?.name;
  if (name !== 'write' && name !== 'edit') continue;

  let args;
  try { args = JSON.parse(event.data.arguments); } catch { continue; }
  const path = args.file_path;
  if (typeof path !== 'string') continue;
  if (needle !== null && !normalize(path).includes(needle)) continue;
  if (options.prefix !== null && !normalize(path).startsWith(normalize(options.prefix))) continue;

  const key = normalize(path);
  if (!files.has(key)) {
    files.set(key, { path, content: null, writes: 0, edits: 0, failures: [] });
    order.push(key);
  }
  const entry = files.get(key);

  if (name === 'write') {
    entry.content = args.content ?? '';
    entry.writes++;
    continue;
  }

  // edit：在已有内容上做字面替换
  entry.edits++;
  if (entry.content === null) {
    entry.failures.push(`seq ${event.seq}: 还没有 write 记录，无法应用 edit`);
    continue;
  }
  const oldString = args.old_string ?? '';
  const newString = args.new_string ?? '';
  if (args.replace_all === true) {
    if (!entry.content.includes(oldString)) {
      entry.failures.push(`seq ${event.seq}: old_string 未命中（replace_all）`);
      continue;
    }
    entry.content = entry.content.split(oldString).join(newString);
  } else {
    const at = entry.content.indexOf(oldString);
    if (at === -1) {
      entry.failures.push(`seq ${event.seq}: old_string 未命中`);
      continue;
    }
    entry.content = entry.content.slice(0, at) + newString + entry.content.slice(at + oldString.length);
  }
}

// 日志里存的是 git 里的 LF；本机 core.autocrlf=true 会让工作区变成 CRLF，
// 所以比较与写回都要把换行归一化，否则每个文件都会被误报成"内容不一致"。
const toLf = (text) => text.replace(/\r\n/g, '\n');
const toCrlf = (text) => text.replace(/\r?\n/g, '\r\n');

console.log(`日志：${options.log}`);
console.log(`匹配 ${files.size} 个文件${needle === null ? '' : `（--match "${options.match}"）`}\n`);

let written = 0;
let missing = 0;
let differing = 0;
for (const key of order) {
  const entry = files.get(key);
  const bytes = entry.content === null ? 0 : Buffer.byteLength(entry.content, 'utf8');

  // --check-missing：只关心「日志里写过、但磁盘上没有或内容不一致」的文件
  let status = '';
  if (options.checkMissing) {
    const onDisk = existsSync(entry.path);
    if (!onDisk) { status = '缺失'; missing++; }
    else if (entry.content !== null && toLf(readFileSync(entry.path, 'utf8')) !== toLf(entry.content)) { status = '内容不一致'; differing++; }
    else { continue; }
  }

  console.log(`  ${entry.path}${status === '' ? '' : `   [${status}]`}`);
  console.log(`      write x${entry.writes}  edit x${entry.edits}  最终 ${bytes} 字节` +
    `${entry.failures.length ? `  ⚠ ${entry.failures.length} 处 edit 未命中` : ''}`);
  for (const failure of entry.failures) console.log(`        - ${failure}`);

  if (options.out !== null && entry.content !== null) {
    const target = options.out === '' ? entry.path : join(resolve(options.out), relative(process.cwd(), entry.path));
    mkdirSync(dirname(target), { recursive: true });
    // --crlf：本机 core.autocrlf=true，工作区文件是 CRLF；日志里存的是 git 里的 LF
    writeFileSync(target, options.crlf ? toCrlf(entry.content) : entry.content, 'utf8');
    written++;
  }
}
if (options.checkMissing) {
  console.log(`\n日志写过的 ${files.size} 个文件里：缺失 ${missing} 个、内容不一致 ${differing} 个。` +
    `${missing + differing === 0 ? '（与磁盘完全一致）' : '（可用 --out . 从日志恢复）'}`);
}
if (options.out !== null) console.log(`\n已写出 ${written} 个文件到 ${resolve(options.out)}`);
else if (!options.checkMissing) console.log('\n（未加 --out，仅列表，未写任何文件）');
