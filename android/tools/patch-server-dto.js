/** 让 LocalApiServer 复用内核里的日志 DTO 与采集结果 DTO，并补上 since 字段 */
const fs = require('fs');
const path = process.argv[2];
let src = fs.readFileSync(path, 'utf8');

// 1) 日志列表改用内核的 toLogItem（可被单测覆盖）
src = src.replace(
    '            for (CrawlLogRecord log : holder.repository.recentLogs(intParam(params, "limit", 20))) {\n'
    + '                list.add(logToMap(log, false));\n'
    + '            }',
    '            for (CrawlLogRecord log : holder.repository.recentLogs(intParam(params, "limit", 20))) {\n'
    + '                list.add(TenderQueryService.toLogItem(log, siteName(log.siteCode), false));\n'
    + '            }'
);
src = src.replace(
    '            return json(logToMap(log, true));',
    '            return json(TenderQueryService.toLogItem(log, siteName(log.siteCode), true));'
);

// 2) 采集结果改用内核的 toSummaryItem
src = src.replace(
    '        status.put("lastSummary", last == null ? null : summaryToMap(last));',
    '        status.put("lastSummary", last == null ? null : TenderQueryService.toSummaryItem(last));'
);

// 3) 补上页面要用的 since 字段
src = src.replace(
    '        status.put("site", holder.engine.getRunningSite());',
    '        status.put("site", holder.engine.getRunningSite());\n'
    + '        status.put("since", holder.engine.getRunningSince());'
);

// 4) 删除已经搬到内核的本地实现，避免两处维护
src = src.replace(/    private Map<String, Object> summaryToMap\(CrawlSummary summary\) \{[\s\S]*?\n    \}\n\n/, '');
src = src.replace(/    private Map<String, Object> logToMap\(CrawlLogRecord log, boolean withDetail\) \{[\s\S]*?\n    \}\n\n/, '');

fs.writeFileSync(path, src, 'utf8');
console.log('已更新 LocalApiServer');
console.log('  toLogItem 调用: ' + (src.match(/TenderQueryService\.toLogItem/g) || []).length);
console.log('  toSummaryItem 调用: ' + (src.match(/TenderQueryService\.toSummaryItem/g) || []).length);
console.log('  since 字段: ' + (src.includes('"since"') ? '已加' : '未加'));
console.log('  logToMap 残留: ' + (src.includes('logToMap') ? '有' : '无'));
console.log('  summaryToMap 残留: ' + (src.includes('summaryToMap') ? '有' : '无'));
