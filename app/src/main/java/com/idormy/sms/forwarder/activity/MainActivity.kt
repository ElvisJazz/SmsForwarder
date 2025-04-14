package com.idormy.sms.forwarder.activity

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.tabs.TabLayout
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.idormy.sms.forwarder.App
import com.idormy.sms.forwarder.R
import com.idormy.sms.forwarder.adapter.menu.DrawerAdapter
import com.idormy.sms.forwarder.adapter.menu.DrawerItem
import com.idormy.sms.forwarder.adapter.menu.SimpleItem
import com.idormy.sms.forwarder.adapter.menu.SpaceItem
import com.idormy.sms.forwarder.core.BaseActivity
import com.idormy.sms.forwarder.core.webview.AgentWebActivity
import com.idormy.sms.forwarder.databinding.ActivityMainBinding
import com.idormy.sms.forwarder.fragment.AboutFragment
import com.idormy.sms.forwarder.fragment.AppListFragment
import com.idormy.sms.forwarder.fragment.ClientFragment
import com.idormy.sms.forwarder.fragment.FrpcFragment
import com.idormy.sms.forwarder.fragment.LogsFragment
import com.idormy.sms.forwarder.fragment.RulesFragment
import com.idormy.sms.forwarder.fragment.SendersFragment
import com.idormy.sms.forwarder.fragment.ServerFragment
import com.idormy.sms.forwarder.fragment.SettingsFragment
import com.idormy.sms.forwarder.fragment.TasksFragment
import com.idormy.sms.forwarder.service.ForegroundService
import com.idormy.sms.forwarder.utils.ACTION_START
import com.idormy.sms.forwarder.utils.CommonUtils.Companion.restartApplication
import com.idormy.sms.forwarder.utils.EVENT_LOAD_APP_LIST
import com.idormy.sms.forwarder.utils.FRPC_LIB_DOWNLOAD_URL
import com.idormy.sms.forwarder.utils.FRPC_LIB_VERSION
import com.idormy.sms.forwarder.utils.Log
import com.idormy.sms.forwarder.utils.SettingUtils
import com.idormy.sms.forwarder.utils.XToastUtils
import com.idormy.sms.forwarder.utils.sdkinit.XUpdateInit
import com.idormy.sms.forwarder.widget.GuideTipsDialog.Companion.showTips
import com.idormy.sms.forwarder.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.DownloadProgressCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xui.XUI.getContext
import com.xuexiang.xui.utils.ResUtils
import com.xuexiang.xui.utils.ThemeUtils
import com.xuexiang.xui.utils.ViewUtils
import com.xuexiang.xui.utils.WidgetUtils
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.GravityEnum
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.file.FileUtils
import com.xuexiang.xutil.net.NetworkUtils
import com.yarolegovich.slidingrootnav.SlideGravity
import com.yarolegovich.slidingrootnav.SlidingRootNav
import com.yarolegovich.slidingrootnav.SlidingRootNavBuilder
import com.yarolegovich.slidingrootnav.callback.DragStateListener
import java.io.File

@Suppress("PrivatePropertyName", "unused", "DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>(), DrawerAdapter.OnItemSelectedListener {

    private val TAG: String = MainActivity::class.java.simpleName
    private val POS_LOG = 0
    private val POS_RULE = 1
    private val POS_SENDER = 2
    private val POS_SETTING = 3
    private var needToAppListFragment = false

    private lateinit var mTabLayout: TabLayout
    private lateinit var mLLMenu: LinearLayout
    private lateinit var mMenuTitles: Array<String>
    private lateinit var mMenuIcons: Array<Drawable>
    private lateinit var mAdapter: DrawerAdapter

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initData()
        initViews()

        //不在最近任务列表中显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SettingUtils.enableExcludeFromRecents) {
            val am = App.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.let {
                val tasks = it.appTasks
                if (!tasks.isNullOrEmpty()) {
                    tasks[0].setExcludeFromRecents(true)
                }
            }
        }

        //检查通知权限是否获取
        XXPermissions.with(this).permission(Permission.NOTIFICATION_SERVICE).permission(Permission.POST_NOTIFICATIONS).request(OnPermissionCallback { _, allGranted ->
            if (!allGranted) {
                XToastUtils.error(R.string.tips_notification)
                return@OnPermissionCallback
            }

            //启动前台服务
            if (!ForegroundService.isRunning) {
                val serviceIntent = Intent(this, ForegroundService::class.java)
                serviceIntent.action = ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        })

        //监听已安装App信息列表加载完成事件
        LiveEventBus.get(EVENT_LOAD_APP_LIST, String::class.java).observe(this) {
            if (needToAppListFragment) {
                openNewPage(AppListFragment::class.java)
            }
        }
    }

    override val isSupportSlideBack: Boolean
        get() = false

    private fun initViews() {
        WidgetUtils.clearActivityBackground(this)
        initTab()
    }

    private fun initTab() {
        mTabLayout = binding!!.tabs
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_logs), R.drawable.selector_icon_tabbar_logs)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_rules), R.drawable.selector_icon_tabbar_rules)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_senders), R.drawable.selector_icon_tabbar_senders)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_settings), R.drawable.selector_icon_tabbar_settings)
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
        switchPage(LogsFragment::class.java)
        mTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                needToAppListFragment = false
                mAdapter.setSelected(tab.position)
                when (tab.position) {
                    POS_LOG -> switchPage(LogsFragment::class.java)
                    POS_RULE -> switchPage(RulesFragment::class.java)
                    POS_SENDER -> switchPage(SendersFragment::class.java)
                    POS_SETTING -> switchPage(SettingsFragment::class.java)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun initData() {
        mMenuTitles = ResUtils.getStringArray(this, R.array.menu_titles)
        mMenuIcons = ResUtils.getDrawableArray(this, R.array.menu_icons)
    }

    //按返回键不退出回到桌面
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }

    override fun onItemSelected(position: Int) {
        needToAppListFragment = false
        when (position) {
            POS_LOG, POS_RULE, POS_SENDER, POS_SETTING -> {
                val tab = mTabLayout.getTabAt(position)
                tab?.select()
            }
        }
    }

    private fun createItemFor(position: Int): DrawerItem<*> {
        return SimpleItem(mMenuIcons[position], mMenuTitles[position])
            .withIconTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withTextTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withSelectedIconTint(ThemeUtils.getMainThemeColor(this))
            .withSelectedTextTint(ThemeUtils.getMainThemeColor(this))
    }
}
