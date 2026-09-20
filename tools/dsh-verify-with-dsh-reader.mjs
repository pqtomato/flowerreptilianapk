#!/usr/bin/env node
/**
 * 用 DSH 自己的存储后端校验导入结果（端到端）
 * ---------------------------------------------------------------------------
 * tools/dsh-history-import.mjs 里的校验是自写的「多帧 zstd 扫描」，只能证明
 * "文件能解开"；本脚本直接加载 DSH 安装目录里的真实后端
 *   @deepseek-ai/dsh-session-persistence-jsonl
 * 调它的 list() / open(id,'read') / read()，也就是 DSH 自己启动时读会话的那条路径
 * （含当前代 v3 的准入规则与逐事件解码）。两个校验都过，才算真的能读。
 *
 * 用法：
 *   node tools/dsh-verify-with-dsh-reader.mjs
 *   node tools/dsh-verify-with-dsh-reader.mjs --dsh-home <DSH_HOME> --npm-root <node_modules 里的 @deepseek-ai>
 */
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const DEFAULT_NPM_ROOT = 'C:/Users/89710/AppData/Local/npm-cache/_npx/1e7f6d9597241db0/node_modules/@deepseek-ai';

function parseArgs(argv) {
  const options = { dshHome: join(homedir(), '.dsh'), npmRoot: DEFAULT_NPM_ROOT };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--dsh-home') options.dshHome = argv[++i];
    else if (argv[i] === '--npm-root') options.npmRoot = argv[++i];
    else throw new Error(`未知参数：${argv[i]}`);
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
const cordisEntry = join(options.npmRoot, 'cordis', 'lib', 'index.js');
const backendEntry = join(options.npmRoot, 'dsh-session-persistence-jsonl', 'lib', 'index.js');
for (const entry of [cordisEntry, backendEntry]) {
  if (!existsSync(entry)) {
    console.error(`找不到 DSH 模块：${entry}\n（用 --npm-root 指向 dsh 安装目录的 node_modules/@deepseek-ai）`);
    process.exit(2);
  }
}

const { Context } = await import(pathToFileURL(cordisEntry).href);
const { default: JsonlSessionPersistence } = await import(pathToFileURL(backendEntry).href);

const sessionsRoot = join(resolve(options.dshHome), 'sessions');
const backend = new JsonlSessionPersistence(new Context(), { root: sessionsRoot });

let total = 0;
let failures = 0;
const snapshots = await backend.list();
console.log(`DSH 后端 ${backend.name} 在 ${sessionsRoot} 下读到 ${snapshots.length} 个会话\n`);

for (const snapshot of snapshots) {
  total++;
  const header = snapshot.header;
  try {
    const handle = await backend.open(header.id, 'read');
    const { events } = await handle.read(0);
    const kinds = new Map();
    for (const event of events) kinds.set(event.type, (kinds.get(event.type) ?? 0) + 1);
    const top = [...kinds].sort((a, b) => b[1] - a[1]).slice(0, 4)
      .map(([type, count]) => `${type}=${count}`).join(', ');
    await handle.close();
    console.log(`  OK   ${header.id}`);
    console.log(`       v${header.version}  cwd=${header.cwd}`);
    console.log(`       ${events.length} 个事件  ${(snapshot.sizeBytes / 1024 / 1024).toFixed(2)} MB  [${top}]`);
  } catch (error) {
    failures++;
    console.log(`  FAIL ${header.id}: ${error.message}`);
  }
}

console.log(`\n合计 ${total} 个会话，失败 ${failures} 个。`);
process.exit(failures === 0 ? 0 : 1);
