package com.water.widget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * 应用通知渠道与通知构建入口。
 */
public final class AppNotifications {
    public static final String CHANNEL_WATER_SESSION = "water_session";
    public static final String CHANNEL_WATER_RESULT = "water_result";
    public static final String CHANNEL_TASK_PROGRESS = "task_progress";
    public static final String CHANNEL_TASK_RESULT = "task_result";

    public static final int WATER_SESSION_ID = 2201;
    public static final int WATER_RESULT_ID = 2202;
    public static final int TASK_PROGRESS_ID = 2301;
    public static final int TASK_RESULT_ID = 2302;

    private AppNotifications() {}

    public static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel session = new NotificationChannel(
                CHANNEL_WATER_SESSION,
                "设备运行状态",
                NotificationManager.IMPORTANCE_LOW
        );
        session.setDescription("显示设备启动及用水记录获取状态");
        session.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        session.setShowBadge(false);
        manager.createNotificationChannel(session);

        NotificationChannel result = new NotificationChannel(
                CHANNEL_WATER_RESULT,
                "接水完成",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        result.setDescription("接水完成后显示本次消费与预计水量");
        result.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(result);

        NotificationChannel tasks = new NotificationChannel(
                CHANNEL_TASK_PROGRESS,
                "积分任务进度",
                NotificationManager.IMPORTANCE_LOW
        );
        tasks.setDescription("运行积分任务时显示当前任务与本次积分");
        tasks.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        tasks.setShowBadge(false);
        manager.createNotificationChannel(tasks);

        // 进度和结果分渠道，和接水那套一致：进度是常驻低优先级，结果可划掉、能冒泡。
        NotificationChannel taskResult = new NotificationChannel(
                CHANNEL_TASK_RESULT,
                "积分任务结果",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        taskResult.setDescription("积分任务结束后显示本次获得的积分");
        taskResult.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(taskResult);
    }

    public static boolean canPost(Context context, String channelId) {
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /**
     * 接水会话常驻通知。
     * @param startedAt       会话开始时刻，显示在通知上
     * @param timeoutAfterMillis 兜底自动消失时间，防止进程被杀后通知残留
     */
    public static Notification waterProgress(
            Context context,
            String text,
            long reservationId,
            long startedAt,
            long timeoutAfterMillis,
            boolean canStopDevice
    ) {
        ensureChannels(context);
        Intent stop = new Intent(context, WaterService.class)
                .setAction(WaterService.ACTION_STOP_MONITORING)
                // data 让不同会话的 PendingIntent 互不覆盖
                .setData(Uri.parse("waterwidget:monitor/" + reservationId))
                .putExtra(WaterService.EXTRA_RESERVATION_ID, reservationId);
        PendingIntent stopIntent = PendingIntent.getService(
                context,
                2203,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_WATER_SESSION)
                .setSmallIcon(R.drawable.ic_water_drop)
                .setContentTitle("接水提醒")
                .setContentText(text)
                .setContentIntent(openApp(context, false, 2201))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setWhen(startedAt)
                .setShowWhen(true)
                .setProgress(0, 0, true)
                .setTimeoutAfter(timeoutAfterMillis)
                .addAction(new Notification.Action.Builder(null, "结束提醒", stopIntent).build())
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (canStopDevice) {
            Intent endDevice = new Intent(context, WaterService.class)
                    .setAction(WaterService.ACTION_STOP_DEVICE)
                    .setData(Uri.parse("waterwidget:device/" + reservationId))
                    .putExtra(WaterService.EXTRA_RESERVATION_ID, reservationId);
            PendingIntent endIntent = PendingIntent.getService(context, 2204, endDevice,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "停止设备", endIntent).build());
        }
        return builder.build();
    }

    public static Notification waterResult(
            Context context,
            String title,
            String text,
            boolean recovery
    ) {
        ensureChannels(context);
        return new Notification.Builder(context, CHANNEL_WATER_RESULT)
                .setSmallIcon(R.drawable.ic_water_drop)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context, recovery, 2202))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
    }

    /** 积分任务结束后的结果通知：不常驻、无进度条、点掉即走。 */
    public static Notification taskResult(Context context, String title, String text) {
        ensureChannels(context);
        return new Notification.Builder(context, CHANNEL_TASK_RESULT)
                .setSmallIcon(R.drawable.ic_water_drop)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context, false, TASK_RESULT_ID))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
    }

    public static PendingIntent openApp(Context context, boolean recovery, int requestCode) {
        Intent intent = new Intent(context, ConfigActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (recovery) {
            intent.putExtra(ConfigActivity.EXTRA_OPEN_WATER_RECOVERY, true);
        }
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
