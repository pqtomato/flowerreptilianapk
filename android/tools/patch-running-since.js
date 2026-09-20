/** 给 CrawlEngine 增加 runningSince（页面要显示“开始于 …”） */
const fs = require('fs');
const path = process.argv[2];
let src = fs.readFileSync(path, 'utf8');

if (!src.includes('runningSince')) {
    src = src.replace(
        '    private volatile String runningSite;\n    private volatile CrawlSummary lastSummary;',
        '    private volatile String runningSite;\n    private volatile LocalDateTime runningSince;\n    private volatile CrawlSummary lastSummary;'
    );

    src = src.replace(
        '    public String getRunningSite() {\n        return runningSite;\n    }',
        '    public String getRunningSite() {\n        return runningSite;\n    }\n\n'
        + '    /** 本次采集的开始时间（页面显示“开始于 …”） */\n'
        + '    public LocalDateTime getRunningSince() {\n        return runningSince;\n    }'
    );

    src = src.replace(
        '        try {\n            CrawlSummary summary = doCrawl(',
        '        this.runningSince = LocalDateTime.now();\n        try {\n            CrawlSummary summary = doCrawl('
    );

    src = src.replace(
        '            running.set(false);\n            runningSite = null;\n        }',
        '            running.set(false);\n            runningSite = null;\n            runningSince = null;\n        }'
    );

    fs.writeFileSync(path, src, 'utf8');
    console.log('已修改 CrawlEngine');
} else {
    console.log('已存在 runningSince，跳过');
}

const check = fs.readFileSync(path, 'utf8');
check.split('\n').forEach((line, i) => {
    if (/runningSince|getRunningSince/.test(line)) {
        console.log('  ' + (i + 1) + ': ' + line.trim());
    }
});
