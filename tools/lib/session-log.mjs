/**
 * DSH 会话日志（多帧拼接 zstd）读写公共库
 * ---------------------------------------------------------------------------
 * DSH 的会话日志是「append 式多帧 zstd」：每次写入追加一帧。因此：
 *   - 普通 zstdDecompressSync 只解第一帧，必须按魔数切帧逐帧解；
 *   - 改写 header 时只重压「含 header 的那一帧」，其余帧保持字节不变，风险最小。
 *
 * 被 tools/dsh-history-repath.mjs 和 tools/dsh-workspace-move.mjs 共用。
 */
import { readFileSync, statSync } from 'node:fs';
import { zstdCompressSync, zstdDecompressSync } from 'node:zlib';

export const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
export const LOG_NAME = /^session\.v\d+\.jsonl(\.zstd)?$/;
// 会话目录名通常是 session-<uuid>，但也见过直接以裸 uuid 命名的旧目录，两种都算
export const SESSION_DIR = /^(session-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 按魔数切帧，返回 [{start,end}] */
export function frameOffsets(buffer) {
  const offsets = [];
  let index = buffer.indexOf(ZSTD_MAGIC, 0);
  while (index !== -1) {
    offsets.push(index);
    index = buffer.indexOf(ZSTD_MAGIC, index + 4);
  }
  return offsets.map((start, i) => ({ start, end: i + 1 < offsets.length ? offsets[i + 1] : buffer.length }));
}

/** 解一帧；假帧（压缩数据里恰巧出现魔数）返回 null */
export function decompressFrame(buffer, start, end) {
  try {
    return zstdDecompressSync(buffer.subarray(start, end)).toString('utf8');
  } catch {
    return null;
  }
}

export function countEvents(text) {
  return text.split('\n').filter((line) => line.trim()).length;
}

/** 读出 header（type=session 的那一行） */
export function readHeader(buffer) {
  for (const { start, end } of frameOffsets(buffer)) {
    const text = decompressFrame(buffer, start, end);
    if (text === null || !text.includes('"type":"session"')) continue;
    for (const line of text.split('\n')) {
      if (!line.includes('"type":"session"')) continue;
      try {
        return JSON.parse(line);
      } catch {
        /* 跨帧的行，继续找 */
      }
    }
  }
  return null;
}

/**
 * 把日志里的 header.cwd 改写成 newCwd。
 * 只重压含 header 的帧，其余帧原样保留。
 * @returns {{buffer: Buffer, frames: number, events: number, oldCwd: string, headerFrame: number}}
 */
export function repathCwd(buffer, newCwd) {
  const frames = frameOffsets(buffer);
  const pieces = [];
  let events = 0;
  let oldCwd = null;
  let headerFrame = -1;

  for (let i = 0; i < frames.length; i++) {
    const raw = buffer.subarray(frames[i].start, frames[i].end);
    const text = decompressFrame(buffer, frames[i].start, frames[i].end);
    if (text === null) {
      pieces.push(raw);
      continue;
    }
    events += countEvents(text);

    if (headerFrame === -1 && text.includes('"type":"session"')) {
      const match = text.match(/"cwd":"((?:[^"\\]|\\.)*)"/);
      if (!match) throw new Error('header 帧里找不到 cwd 字段');
      oldCwd = JSON.parse(`"${match[1]}"`);
      const from = `"cwd":${JSON.stringify(oldCwd)}`;
      const to = `"cwd":${JSON.stringify(newCwd)}`;
      if (!text.includes(from)) throw new Error(`header 帧里的 cwd 文本不是预期的 ${from}`);
      const replaced = text.replace(from, to);
      if (replaced === text) throw new Error('cwd 替换未生效');
      headerFrame = i;
      pieces.push(zstdCompressSync(Buffer.from(replaced, 'utf8')));
      continue;
    }
    pieces.push(raw);
  }

  if (headerFrame === -1) throw new Error('没有找到 header 帧（type=session）');
  return { buffer: Buffer.concat(pieces), frames: frames.length, events, oldCwd, headerFrame };
}

/** 校验一个日志文件：帧可解、header.cwd 正确、事件数与预期一致 */
export function verifyLogFile(file, { cwd, events: expectedEvents } = {}) {
  const buffer = readFileSync(file);
  const frames = frameOffsets(buffer);
  let events = 0;
  for (const { start, end } of frames) {
    const text = decompressFrame(buffer, start, end);
    if (text !== null) events += countEvents(text);
  }
  const header = readHeader(buffer);
  const problems = [];
  if (header === null) problems.push('解不出 header');
  else if (cwd !== undefined && header.cwd !== cwd) problems.push(`header.cwd=${header.cwd}，期望 ${cwd}`);
  if (expectedEvents !== undefined && events !== expectedEvents) {
    problems.push(`事件数变化：${expectedEvents} -> ${events}`);
  }
  return { frames: frames.length, events, header, problems, sizeBytes: statSync(file).size };
}
