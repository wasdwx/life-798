package com.water.widget

import android.app.Application

/**
 * 进程入口：只负责初始化诊断日志。
 *
 * 之前 AuthDiagnostics 用 Class.forName("android.app.ActivityThread") 反射拿 Context，
 * 现在有了 Application 就正经初始化，顺带把「正式版默认关闭 / debug 默认开启」的开关读进来。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
    }
}
