package com.water.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Arrays;

/**
 * 桌面小部件 Provider。
 * 不使用 android:configure（HyperOS 不兼容），直接添加。
 * 未配置时整个小部件点击打开配置页；已配置时通过单一按钮启动或停止当前设备。
 */
public class WaterWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_START = "com.water.widget.ACTION_START";
    private static final String LEGACY_ACTION_HOT = "com.water.widget.ACTION_HOT";
    private static final String LEGACY_ACTION_COLD = "com.water.widget.ACTION_COLD";
    public static final String EXTRA_DID = "extra_did";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, id, null));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        onUpdate(context, manager, new int[]{id});
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_START.equals(action)
                || LEGACY_ACTION_HOT.equals(action)
                || LEGACY_ACTION_COLD.equals(action)) {
            String did = intent.getStringExtra(EXTRA_DID);
            int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);

            // 同一个按钮兼顾启动与停止：会话属于该设备且已进入可停止阶段时点击即停止。
            String status = WidgetSupport.toggleDevice(context, did, widgetId);
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                AppWidgetManager.getInstance(context).updateAppWidget(
                        widgetId,
                        buildViews(context, widgetId, status)
                );
            }
        }
    }

    static RemoteViews buildViews(Context context, int widgetId, String status) {
        return WidgetSupport.responsiveViews(context, widgetId,
                Arrays.asList(new SizeF(300f, 110f), new SizeF(300f, 180f)),
                size -> buildLayout(context, widgetId, status, size));
    }

    static RemoteViews buildLayout(Context context, int widgetId, String status, SizeF size) {
        boolean configured = WaterApi.isConfigured(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_water);
        float fontScale = Math.max(1f, context.getResources().getConfiguration().fontScale);
        views.setViewVisibility(R.id.widget_header, size.getHeight() >= 150f * fontScale ? View.VISIBLE : View.GONE);
        // 经典小部件只吃不透明度，渐变大按钮不做颜色自定义
        WidgetSupport.tintSurface(views, context, R.color.widget_bg,
                ThemeSettings.INSTANCE.widgetStyle(context).getOpacity());

        if (!configured) {
            // 未配置：整个小部件点击打开主页
            Intent cfg = new Intent(context, ConfigActivity.class);
            PendingIntent pi = PendingIntent.getActivity(context, 100 + widgetId, cfg,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(android.R.id.background, pi);
            views.setTextViewText(R.id.widget_status, "未配置 · 点击设置");
        } else {
            String did = WaterApi.getDid(context);
            Account account = AccountStore.getCurrent(context);
            if (account != null) {
                views.setTextViewText(R.id.widget_device_name, account.deviceDisplayName(did));
            }
            views.setOnClickPendingIntent(R.id.btn_start,
                    buildPI(context, widgetId, did));
            String label = WidgetSupport.deviceActionLabel(did);
            views.setTextViewText(R.id.btn_start, label);
            String activeStatus = WaterService.activeStatus();
            String idleHint = "启动设备".equals(label) ? "点击启动当前设备" : "点击" + label;
            views.setTextViewText(R.id.widget_status,
                    status != null ? status : activeStatus != null ? activeStatus : idleHint);
        }
        return views;
    }

    private static PendingIntent buildPI(Context context, int widgetId, String did) {
        Intent intent = new Intent(context, WaterWidgetProvider.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_DID, did);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId * 10 + 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
