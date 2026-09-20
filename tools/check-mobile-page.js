/**
 * 手机端页面的静态自检（无浏览器环境下的折中方案）：
 *  1. 语法检查内联 <script>
 *  2. 检查 JS 里 $('id') / getElementById('id') 引用的元素是否都真实存在
 *  3. 检查前端调用的接口是否都能在后端 Controller 里找到
 *  4. 检查手机端适配必备项（viewport-fit / safe-area / tabbar / 触控尺寸）
 */
const fs = require('fs');

const htmlPath = process.argv[2];
const javaRoot = process.argv[3];
const html = fs.readFileSync(htmlPath, 'utf8');

const problems = [];
const notes = [];

// ---------- 1. 抽取并语法检查内联脚本 ----------
const scripts = [...html.matchAll(/<script(?![^>]*src)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (scripts.length === 0) {
    problems.push('页面里没有找到内联 <script>');
}
const js = scripts.join('\n');
try {
    new Function(js);
    notes.push('内联 JS 语法检查通过（' + js.length + ' 字符）');
} catch (e) {
    problems.push('内联 JS 语法错误：' + e.message);
}

// ---------- 2. 元素引用检查 ----------
const htmlIds = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
const jsIds = new Set();
for (const m of js.matchAll(/\$\('([^']+)'\)/g)) jsIds.add(m[1]);
for (const m of js.matchAll(/getElementById\('([^']+)'\)/g)) jsIds.add(m[1]);

const missingIds = [...jsIds].filter(id => !htmlIds.has(id));
if (missingIds.length) {
    problems.push('JS 引用了不存在的元素 id：' + missingIds.join(', '));
} else {
    notes.push('JS 引用的 ' + jsIds.size + ' 个元素 id 全部存在');
}

// ---------- 3. 接口调用检查 ----------
const apiCalls = new Set();
for (const m of js.matchAll(/['"`](\/api\/[a-zA-Z0-9_\-\/{}.]*)['"`]/g)) apiCalls.add(m[1]);

let javaSource = '';
(function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = dir + '/' + entry.name;
        if (entry.isDirectory()) walk(full);
        else if (entry.name.endsWith('.java')) javaSource += fs.readFileSync(full, 'utf8');
    }
})(javaRoot);

const unrouted = [];
for (const call of apiCalls) {
    // 去掉路径参数，例如 /api/tenders/{id} → /api/tenders
    const segments = call.split('/').filter(Boolean).slice(2); // 去掉 api 与资源名
    const tail = segments.length ? segments[segments.length - 1] : '';
    const hasLiteral = tail && !tail.includes('{') && javaSource.includes('"' + tail);
    const hasMapping = javaSource.includes('"' + tail + '"') || javaSource.includes('/' + tail);
    if (!hasLiteral && !hasMapping) unrouted.push(call);
}
notes.push('前端调用接口 ' + apiCalls.size + ' 个：' + [...apiCalls].join(', '));
if (unrouted.length) {
    problems.push('可能未实现的接口：' + unrouted.join(', '));
}

// ---------- 4. 手机端适配检查 ----------
const mobileChecks = [
    ['viewport-fit=cover（刘海屏适配）', /viewport-fit=cover/.test(html)],
    ['safe-area-inset（安全区适配）', /safe-area-inset/.test(html)],
    ['底部 Tab 栏', /class="tabbar"/.test(html)],
    ['卡片式列表（手机端不用表格）', /class="card"/.test(html)],
    ['触控目标 ≥44px', /min-height:\s*var\(--tabbar-h\)|height:\s*4[4-9]px|height:\s*46px/.test(html)],
    ['深色模式适配', /prefers-color-scheme/.test(html)],
    ['大屏自适应（平板/桌面）', /@media\s*\(min-width:\s*768px\)/.test(html)],
    ['无限滚动', /IntersectionObserver/.test(html)],
    ['截止日期徽标', /deadlineBadge/.test(html)],
    ['收藏交互', /toggleTrack/.test(html)],
    ['无固定宽度表格', !/<table/.test(html)],
];
for (const [name, ok] of mobileChecks) {
    if (ok) notes.push('✓ ' + name);
    else problems.push('缺失手机端适配项：' + name);
}

// ---------- 输出 ----------
console.log('--- 通过项 ---');
notes.forEach(n => console.log('  ' + n));
if (problems.length) {
    console.log('--- 问题 ---');
    problems.forEach(p => console.log('  ✗ ' + p));
    process.exit(1);
}
console.log('--- 静态自检全部通过 ---');
