package com.wanhong.tender.crawl;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.wanhong.crawler.pipeline.DeadlineReminder;
import com.wanhong.crawler.pipeline.PageResult;
import com.wanhong.crawler.pipeline.TenderQuery;
import com.wanhong.crawler.pipeline.TenderRecord;
import com.wanhong.tender.core.CrawlerHolder;

import java.util.List;

/**
 * 每日检查"即将截止"的公告，有就发一条汇总通知。
 *
 * <p>和采集任务分开跑：采集可能失败 / 被跳过，但提醒用的是**已经入库的数据**，
 * 不该因为采集没跑成功就一起哑掉。
 *
 * <p>没有到期的公告时**不发通知**（Result.success 直接结束）—— 每天准点报"没有内容"
 * 只会让人把通知权限关掉，反而漏掉真提醒。
 */
public class ReminderWorker extends Worker {

    private static final String TAG = "ReminderWorker";

    /** 一次最多看多少条候选；正常库里也就几十条未截止的，这个上限够用。 */
    private static final int CANDIDATE_LIMIT = 200;

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            List<TenderRecord> due = findDue(context);
            if (due.isEmpty()) {
                Log.i(TAG, "未来 " + CrawlerHolder.get(context).getReminderDays()
                        + " 天内没有即将截止的公告，不打扰");
                return Result.success();
            }
            Log.i(TAG, "有 " + due.size() + " 条公告即将截止，发提醒");
            ReminderNotifier.notifyDue(context, due);
            return Result.success();
        } catch (Exception e) {
            // 提醒失败不值得让 WorkManager 重试轰炸用户，记日志即可
            Log.e(TAG, "截止提醒失败", e);
            return Result.failure();
        }
    }

    /**
     * 找出当前需要提醒的公告。
     *
     * <p>抽成静态方法是为了让"立即检查"接口（设置页上的按钮，用于确认提醒配好了）
     * 走**完全相同**的判定逻辑 —— 否则手动测试通过、定时任务却不发，最难查。
     */
    public static List<TenderRecord> findDue(Context context) {
        CrawlerHolder holder = CrawlerHolder.get(context);
        TenderQuery query = new TenderQuery();
        query.onlyOpen = true;          // 已截止的不必提醒
        query.onlyBiddable = true;      // 成交 / 中标 / 合同类不提醒
        query.sortBy = TenderQuery.SORT_DEADLINE;

        PageResult<TenderRecord> page = holder.queryService.query(query, 0, CANDIDATE_LIMIT);
        return DeadlineReminder.pick(page.content, holder.getReminderDays());
    }
}
