package dji.sampleV5.aircraft

import android.app.Application
import android.app.Activity
import android.os.Bundle
import dji.sampleV5.aircraft.control.ControlChannelManager
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class DJIApplication : Application() {

    private val msdkManagerVM: MSDKManagerVM by globalViewModels()
    private var startedActivities = 0
    private var sdkRegistered = false
    private var appInForeground = false
    private var controlChannelStarted = false

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities == 0) {
                    appInForeground = true
                    startControlChannelIfReady()
                }
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities <= 0 && !activity.isChangingConfigurations) {
                    appInForeground = false
                    if (controlChannelStarted) {
                        ControlChannelManager.onAppBackgrounded()
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        msdkManagerVM.lvRegisterState.observeForever { state ->
            sdkRegistered = state.first
            if (sdkRegistered) {
                startControlChannelIfReady()
            } else if (controlChannelStarted) {
                ControlChannelManager.stop()
                controlChannelStarted = false
            }
        }

        // MSDK registration is asynchronous. The control channel is started only
        // after registration succeeds and an Activity is actually foregrounded.
        msdkManagerVM.initMobileSDK(this)
    }

    override fun onTerminate() {
        ControlChannelManager.stop()
        super.onTerminate()
    }

    private fun startControlChannelIfReady() {
        if (!sdkRegistered || !appInForeground) return
        if (!controlChannelStarted) {
            controlChannelStarted = ControlChannelManager.start()
        }
        if (controlChannelStarted) {
            ControlChannelManager.onAppForegrounded()
        }
    }

}
