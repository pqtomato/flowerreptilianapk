package com.wanhong.tender.crawl;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.wanhong.crawler.crawler.model.NewTender;
import com.wanhong.tender.R;
import com.wanhong.tender.pipeline.CrawlSummaryBridge;
import com.wanhong.tender.ui.MainActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 采集结果通知。
 *
 * <p>以前只会说"新增 N 条"，点开也只是回到 App —— 用户还得自己去列表里翻到底新增了什么。
 * 现在把新公告的**标题和剩余天数**直接列在通知里，点一下直达「新公告」筛选，
 * 把 #1「只看可投」和 #2「截止提醒」串起来：采集完就能看见机会，不用自己找。
 */
public final class CrawlNotifier {

    private static final int RESULT_ID = 1002;
    /** PendingIntent 的 requestCode 必须和别的通知错开，否则 extras 会互相覆盖 */
    private static final int REQUEST_CODE = 1002;
    /** 通知里最多列几条新公告 */
    private static final int MAX_LINES = 5;

    private CrawlNotifier() {
    }

    public static void notifyResult(Context context, CrawlSummaryBridge.Summary summary) {
        if (summary == null) {
            return;
        }
        List<NewTender> items = summary.newItems == null ? Collections.emptyList() : summary.newItems;
        int total = Math.max(summary.newItemCount, items.size());
        // 只列"还没截止"的：已截止的提醒不了什么，尤其不能占掉折叠后的第一行。
        // 排序/挑选逻辑在内核 NewTender.mostUrgent 里（可单测），这里只管文案。
        List<NewTender> open = new ArrayList<>();
        for (NewTender item : items) {
            if (item != null && !item.expired()) {
                open.add(item);
            }
        }
        List<NewTender> listed = NewTender.mostUrgent(open, MAX_LINES);

        String title;
        String text;
        if (!listed.isEmpty()) {
            title = "发现 " + total + " 条可投的新公告";
            text = buildLines(listed, total);
        } else if (total > 0) {
            // 新增了公告但都已经过了报名时间 —— 直说，别让用户白点进去
            title = "采集完成";
            text = "新增 " + total + " 条新公告，但都已过报名截止时间";
        } else if (summary.savedCount > 0) {
            // 新增了东西但没有可投的（都是成交/合同类）——直说，别让用户以为漏了
            title = "采集完成";
            text = "新增 " + summary.savedCount + " 条，均为结果/合同类公告，没有可投的";
        } else {
            title = "采集完成";
            text = summary.duplicateCount > 0 ? "没有新公告（已是最新）" : "本次没有匹配到新公告";
        }
        post(context, title, text, !listed.isEmpty());
    }

    public static void notifyError(Context context, String message) {
        post(context, "采集失败", message == null ? "未知错误" : message, false);
    }

    private static void post(Context context, String title, String text, boolean openNewList) {
        // 走「重要提醒」渠道而不是「采集任务」：采集进度是过程噪声、很适合静音，
        // 但"发现了新机会"一旦被一起静掉，这个通知就白发了。
        ReminderNotifier.ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (openNewList) {
            intent.putExtra(MainActivity.EXTRA_VIEW, MainActivity.VIEW_NEW);
        }
        PendingIntent pending = PendingIntent.getActivity(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ReminderNotifier.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text.split("\n")[0])
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        manager.notify(RESULT_ID, builder.build());
    }

    /** 把已排好序的条目拼成通知正文；超出部分用「…等 N 条」收尾（N 用不封顶的总数）。 */
    private static String buildLines(List<NewTender> items, int total) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(items.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(items.get(i).describe());
        }
        if (total > shown) {
            sb.append("\n…等 ").append(total).append(" 条");
        }
        return sb.toString();
    }
}
