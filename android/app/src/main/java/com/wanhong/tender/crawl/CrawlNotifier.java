package com.wanhong.tender.crawl;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.wanhong.tender.R;
import com.wanhong.tender.pipeline.CrawlSummaryBridge;
import com.wanhong.tender.ui.MainActivity;

/** 采集结果通知。 */
public final class CrawlNotifier {

    private static final int RESULT_ID = 1002;

    private CrawlNotifier() {
    }

    public static void notifyResult(Context context, CrawlSummaryBridge.Summary summary) {
        if (summary == null) {
            return;
        }
        CrawlWorker.ensureChannel(context);
        String text = "新增 " + summary.savedCount + " 条，未截止情况可在应用内查看";
        if (summary.savedCount == 0) {
            text = summary.duplicateCount > 0
                    ? "没有新公告（已是最新）"
                    : "本次没有匹配到新公告";
        }
        post(context, "采集完成", text);
    }

    public static void notifyError(Context context, String message) {
        CrawlWorker.ensureChannel(context);
        post(context, "采集失败", message == null ? "未知错误" : message);
    }

    private static void post(Context context, String title, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CrawlWorker.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        manager.notify(RESULT_ID, builder.build());
    }
}
