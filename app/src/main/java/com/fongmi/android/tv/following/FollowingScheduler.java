package com.fongmi.android.tv.following;

import android.content.Context;

/**
 * 追更调度占位实现。
 *
 * restore-upstream 分支在恢复上游时只搬来了调度器外壳，缺 androidx.work 依赖，
 * 以及 Following / FollowingUpdateWorker / FollowingSchedulePolicy 等配套类，
 * 原样无法编译（javac: package androidx.work does not exist / cannot find symbol）。
 * 这里保留设置页用到的公开 API，让工程可以编译；后台追更任务不会真正启动。
 * 如需完整功能，应从 webhtv main 补齐整套 following 包并加上 WorkManager 依赖。
 */
public final class FollowingScheduler {

    public static final String PERIODIC_WORK = "webhtv.following.periodic";
    public static final String ONE_SHOT_WORK = "webhtv.following.one-shot";
    public static final String ONE_SHOT_PREFIX = ONE_SHOT_WORK + ".";
    public static final String TAG = "webhtv.following";

    private FollowingScheduler() {
    }

    public static void ensurePeriodic(Context context) {
    }

    public static void enqueueDueNow(Context context) {
    }

    public static void scheduleNext(Context context, Object item) {
    }

    public static void cancelNext(Context context, String identityKey) {
    }

    public static void cancelAll(Context context) {
    }
}
