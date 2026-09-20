/**
 * 把「应用设置」区块插入到应用内页面（assets/index.html）。
 *
 * 页面是"设计稿"级别的产物，直接手改容易漏；用脚本插入保证可重复执行（幂等）。
 * 应用内页面自此为唯一维护源：android/app/src/main/assets/index.html
 */
const fs = require('fs');
const path = require('path');

const pagePath = process.argv[2];
const sectionPath = process.argv[3];
const jsPath = process.argv[4];

let html = fs.readFileSync(pagePath, 'utf8');
const section = fs.readFileSync(sectionPath, 'utf8');
const js = fs.readFileSync(jsPath, 'utf8');

const MARK_SECTION = '<h2>应用设置</h2>';
const MARK_JS = '/* ---------------- 应用设置 ---------------- */';

// 1. 插入设置区块（放在「站点」小节之前）
if (!html.includes(MARK_SECTION)) {
    const anchor = '            <h2>站点</h2>';
    if (!html.includes(anchor)) {
        console.error('未找到锚点：<h2>站点</h2>');
        process.exit(1);
    }
    html = html.replace(anchor, section + anchor);
    console.log('已插入设置区块');
} else {
    console.log('设置区块已存在，跳过');
}

// 2. 插入设置脚本（放在初始化之前）
if (!html.includes(MARK_JS)) {
    const anchor = '    /* ---------------- 初始化 ---------------- */';
    if (!html.includes(anchor)) {
        console.error('未找到初始化锚点');
        process.exit(1);
    }
    html = html.replace(anchor, js + '\n' + anchor);
    console.log('已插入设置脚本');
} else {
    console.log('设置脚本已存在，跳过');
}

// 3. 初始化时加载设置并绑定按钮
if (!html.includes('loadAppSettings();')) {
    html = html.replace('    loadScopes();', '    loadScopes();\n    loadAppSettings();\n    bindAppSettings();');
    console.log('已接入初始化');
} else {
    console.log('初始化已接入，跳过');
}

fs.writeFileSync(pagePath, html, 'utf8');
console.log('页面大小：' + html.length + ' 字符');
