package com.water.widget.ui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.water.widget.AppThemeMode
import com.water.widget.AppUpdates
import com.water.widget.BuildConfig
import com.water.widget.TaskRiskConsent
import com.water.widget.WaterControlPhase
import com.water.widget.WaterControlSession
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private enum class DashboardTab(val label: String, val title: String) {
    CONTROL("控制", "饮水控制中心"),
    TASK("任务", "积分任务"),
    MINE("我的", "我的")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onLogin: () -> Unit,
    onAccounts: () -> Unit,
    themeMode: AppThemeMode,
    onAppearance: () -> Unit,
    onRunTasks: () -> Unit,
    onStopTasks: () -> Unit,
    onScores: () -> Unit,
    onWallet: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onFetchDevices: () -> Unit,
    onRefreshHome: () -> Unit,
    onAddDevice: () -> Unit,
    onEditDevice: (String, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSelectDevice: (String) -> Unit,
    onStartDevice: (String) -> Unit,
    onStopDevice: (String) -> Unit = {},
    onSettings: () -> Unit = {},
    onToggleAutoTask: (Boolean) -> Unit = {},
    onToggleAutoUpdate: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onOpenWaterUsage: () -> Unit = {},
    autoTaskEnabled: Boolean = false,
    autoUpdateEnabled: Boolean = false,
    autoUpdateLabel: String = "",
    updateLabel: String = "",
    waterUsageLabel: String = "",
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var taskRiskConfirmed by remember { mutableStateOf(TaskRiskConsent.isConfirmed(context)) }
    var showTaskRiskDialog by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = DashboardTab.CONTROL.ordinal,
        pageCount = { DashboardTab.entries.size }
    )
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = DashboardTab.entries[pagerState.currentPage]
    val controlScrollState = rememberScrollState()
    val taskScrollState = rememberScrollState()
    val mineScrollState = rememberScrollState()
    val mineOverscrollEffect = rememberOverscrollEffect()
    val blurBackdrop = rememberLayerBackdrop {
        drawRect(colors.background)
        drawContent()
    }
    val pageTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 28.dp
    val bottomContentPadding = 92.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var controlHeaderHeightPx by remember { mutableIntStateOf(0) }
    val controlHeaderHeight = with(LocalDensity.current) { controlHeaderHeightPx.toDp() }
    var showAccountSwitcher by rememberSaveable { mutableStateOf(false) }
    var showSupportDialog by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .overscroll(mineOverscrollEffect)
                .layerBackdrop(blurBackdrop)
        ) { page ->
            when (DashboardTab.entries[page]) {
                DashboardTab.CONTROL -> {
                    WaterPullRefresh(
                        isRefreshing = state.homeRefreshing,
                        onRefresh = onRefreshHome,
                        modifier = Modifier.fillMaxSize(),
                        indicatorTopOffset = pageTopPadding + controlHeaderHeight
                    ) { pullOffset ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(controlScrollState)
                                .padding(start = 20.dp, top = pageTopPadding, end = 20.dp, bottom = bottomContentPadding),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            DashboardTitle(DashboardTab.CONTROL.title,
                                Modifier.onSizeChanged { controlHeaderHeightPx = it.height })
                            Column(
                                modifier = Modifier.offset(y = pullOffset),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                HomeTab(
                                    state = state.summary,
                                    syncing = state.deviceSyncing,
                                    onLogin = onLogin,
                                    onSwitchAccount = { showAccountSwitcher = true },
                                    onFetchDevices = onFetchDevices,
                                    onAddDevice = onAddDevice,
                                    onEditDevice = onEditDevice,
                                    onRemoveDevice = onRemoveDevice,
                                    onSelectDevice = onSelectDevice,
                                    onStartDevice = onStartDevice,
                                    onStopDevice = onStopDevice,
                                    waterSession = state.waterSession
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }

                DashboardTab.TASK -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(taskScrollState)
                            .padding(start = 20.dp, top = pageTopPadding, end = 20.dp, bottom = bottomContentPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DashboardTitle(DashboardTab.TASK.title)
                        TaskScreen(
                            state = state.tasks,
                            onRun = { if (taskRiskConfirmed) onRunTasks() else showTaskRiskDialog = true },
                            onStop = onStopTasks
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                DashboardTab.MINE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                state = mineScrollState,
                                overscrollEffect = mineOverscrollEffect?.withoutVisualEffect()
                            )
                            .padding(start = 20.dp, top = pageTopPadding, end = 20.dp, bottom = bottomContentPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DashboardTitle(DashboardTab.MINE.title)
                        MineTab(
                            state = state.summary,
                            onLogin = onLogin,
                            onAccounts = onAccounts,
                            onScores = onScores,
                            onWallet = onWallet,
                            themeMode = themeMode,
                            onOpenAppearanceSettings = onAppearance,
                            onOpenSettings = onSettings,
                            onOpenSupport = { showSupportDialog = true },
                            autoTaskEnabled = autoTaskEnabled,
                            onToggleAutoTask = onToggleAutoTask,
                            autoUpdateEnabled = autoUpdateEnabled,
                            autoUpdateLabel = autoUpdateLabel,
                            onToggleAutoUpdate = onToggleAutoUpdate,
                            updateLabel = updateLabel,
                            onCheckUpdate = onCheckUpdate,
                            waterUsageLabel = waterUsageLabel,
                            onOpenWaterUsage = onOpenWaterUsage
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        BottomTabs(
            selectedTab = selectedTab,
            pagerState = pagerState,
            onSelect = { tab ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(tab.ordinal)
                }
            },
            backdrop = blurBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showAccountSwitcher) {
        AccountSwitcherDialog(
            accounts = state.accounts,
            onDismiss = { showAccountSwitcher = false },
            onSelect = { phone ->
                showAccountSwitcher = false
                onSelectAccount(phone)
            }
        )
    }
    if (showSupportDialog) {
        SupportDeveloperDialog(onDismiss = { showSupportDialog = false })
    }
    if (!taskRiskConfirmed && (pagerState.settledPage == DashboardTab.TASK.ordinal || showTaskRiskDialog)) {
        TaskRiskDialog(onConfirm = {
            TaskRiskConsent.confirm(context)
            taskRiskConfirmed = true
            showTaskRiskDialog = false
        })
    }
}

@Composable
private fun HomeTab(
    state: DashboardSummaryUiState,
    syncing: Boolean,
    onLogin: () -> Unit,
    onSwitchAccount: () -> Unit,
    onFetchDevices: () -> Unit,
    onAddDevice: () -> Unit,
    onEditDevice: (String, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSelectDevice: (String) -> Unit,
    onStartDevice: (String) -> Unit,
    onStopDevice: (String) -> Unit,
    waterSession: WaterControlSession? = null
) {
    HeroCard(state = state, onAccountAction = if (state.hasAccount) onSwitchAccount else onLogin)
    DeviceCard(
        state = state,
        syncing = syncing,
        onFetchDevices = onFetchDevices,
        onAddDevice = onAddDevice,
        onEditDevice = onEditDevice,
        onRemoveDevice = onRemoveDevice,
        onSelectDevice = onSelectDevice,
        onStartDevice = onStartDevice,
        onStopDevice = onStopDevice,
        waterSession = waterSession
    )
    if (state.hasAccount) TodayOverviewCard(usage = state.usage)
}

@Composable
private fun DashboardTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 34.sp
    )
}

@Composable
private fun HeroCard(state: DashboardSummaryUiState, onAccountAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val cardColor = colors.secondaryContainer
    val contentColor = colors.onSecondaryContainer
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("账号", color = contentColor.copy(alpha = 0.70f), fontSize = 12.sp)
                    Text(state.accountTitle, color = contentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.hasAccount) {
                        Text(state.accountSubtitle, color = contentColor, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onAccountAction) {
                    Text(if (state.hasAccount) "切换账号" else "去登录", fontWeight = FontWeight.SemiBold)
                    if (state.hasAccount) Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                }
            }
            if (state.hasAccount) {
                HorizontalDivider(color = contentColor.copy(alpha = 0.12f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("可用额度", Modifier.weight(1f), color = contentColor, fontSize = 13.sp)
                    Text(state.scoreTitle, color = contentColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text(state.scoreSubtitle, color = contentColor, fontSize = 12.sp)
            } else {
                Text("登录后添加设备，即可使用出水控制。", color = contentColor, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TodayOverviewCard(usage: WaterUsageUiState) {
    InfoCard(title = "今天概览", subtitle = usage.syncNote) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            UsageMetric(
                label = "今日用水花费",
                value = usage.todayCostText,
                accent = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (usage.showWaterEstimate) {
                UsageMetric(
                    label = "今日估算水量",
                    value = usage.todayWaterText,
                    accent = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (usage.showWaterEstimate) {
            Text("水量按统一比例估算，不区分设备", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageMetric(
    label: String,
    value: String,
    accent: Color,
    content: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = WaterShapeTokens.card,
        color = accent
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = content.copy(alpha = 0.78f), fontSize = 12.sp)
            Text(value, color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DeviceCard(
    state: DashboardSummaryUiState,
    syncing: Boolean,
    onFetchDevices: () -> Unit,
    onAddDevice: () -> Unit,
    onEditDevice: (String, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSelectDevice: (String) -> Unit,
    onStartDevice: (String) -> Unit,
    onStopDevice: (String) -> Unit,
    waterSession: WaterControlSession? = null
) {
    val canStart = state.hasAccount && state.hasAppToken
    // 本地用 WaterControl 的 phase 表达会话进度，不引入上游那套 starting: Boolean 语义。
    val sessionPhase = waterSession?.phase
    val sessionDeviceId = waterSession?.deviceId
    var editingDevice by remember { mutableStateOf<DeviceUiState?>(null) }
    var editedName by remember { mutableStateOf("") }
    var removingDevice by remember { mutableStateOf<DeviceUiState?>(null) }
    var movingDownDeviceId by remember { mutableStateOf<String?>(null) }
    val deviceBoundsTransform = remember {
        BoundsTransform { _, _ ->
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        }
    }
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (state.devices.isEmpty()) {
                            "暂无设备"
                        } else {
                            "${state.devices.size} 台 · 点击卡片选择控制中心设备"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onFetchDevices, enabled = state.hasAccount && !syncing) {
                    Icon(Icons.Default.Refresh, contentDescription = "同步设备")
                }
                IconButton(onClick = onAddDevice, enabled = state.hasAccount) {
                    Icon(Icons.Default.Add, contentDescription = "添加设备")
                }
            }
            Spacer(Modifier.height(12.dp))
            LookaheadScope {
                Column {
                    state.devices.forEach { device ->
                        key(device.id) {
                            val layer = when {
                                device.id == movingDownDeviceId -> 2f
                                device.isControlCenterDevice -> 0f
                                else -> 1f
                            }
                            DeviceListItem(
                                device = device,
                                canStart = canStart,
                                modifier = Modifier
                                    .zIndex(layer)
                                    .animateBounds(
                                        lookaheadScope = this@LookaheadScope,
                                        modifier = Modifier.fillMaxWidth(),
                                        boundsTransform = deviceBoundsTransform
                                    ),
                                onSelect = {
                                    if (!device.isControlCenterDevice) {
                                        movingDownDeviceId = state.devices
                                            .firstOrNull(DeviceUiState::isControlCenterDevice)
                                            ?.id
                                    }
                                    onSelectDevice(device.id)
                                },
                                onManage = {
                                    editingDevice = device
                                    editedName = device.name
                                },
                                onStart = { onStartDevice(device.id) },
                                stopping = sessionDeviceId == device.id &&
                                    (sessionPhase == WaterControlPhase.STOPPING || sessionPhase == WaterControlPhase.SETTLING),
                                starting = sessionDeviceId == device.id && sessionPhase == WaterControlPhase.STARTING,
                                running = sessionDeviceId == device.id && sessionPhase == WaterControlPhase.RUNNING,
                                onStop = { onStopDevice(device.id) }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
            if (!state.hasAccount || !state.hasAppToken || state.devices.isEmpty()) {
                ConfigurationNotice(state = state)
            }
        }
    }
    editingDevice?.let { device ->
        val canRemove = sessionDeviceId != device.id
        AlertDialog(
            onDismissRequest = { editingDevice = null },
            title = { Text("管理设备", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(device.id, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("显示名称") },
                        singleLine = true
                    )
                    if (!canRemove) Text("请先停止接水，再移除设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onEditDevice(device.id, editedName)
                    editingDevice = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingDevice = null
                    removingDevice = device
                }, enabled = canRemove) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Text("移除")
                }
            }
        )
    }
    removingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { removingDevice = null },
            title = { Text("移除 ${device.name}？") },
            text = { Text("将从此手机移除设备，并取消官方账号中的收藏。") },
            confirmButton = {
                Button(onClick = {
                    removingDevice = null
                    onRemoveDevice(device.id)
                }, enabled = sessionDeviceId != device.id) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removingDevice = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun DeviceListItem(
    device: DeviceUiState,
    canStart: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onStart: () -> Unit,
    stopping: Boolean,
    starting: Boolean,
    running: Boolean,
    onStop: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (device.isControlCenterDevice) {
            colors.primaryContainer
        } else {
            colors.surfaceVariant
        },
        label = "deviceCardColor"
    )
    Card(
        onClick = onSelect,
        modifier = modifier,
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    device.name,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(device.id, color = colors.onSurfaceVariant, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (device.isControlCenterDevice) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.primary,
                        contentColor = colors.onPrimary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Text("控制中心默认", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            IconButton(onClick = onManage) {
                Icon(Icons.Default.MoreVert, contentDescription = "管理设备")
            }
            Button(
                onClick = if (running) onStop else onStart,
                enabled = canStart && !starting && !stopping,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (running || stopping) "停止" else "启动")
            }
        }
    }
}

@Composable
private fun ConfigurationNotice(state: DashboardSummaryUiState) {
    val (title, detail) = when {
        !state.hasAccount -> "尚未登录账号" to "登录账号后，完成用水登录即可同步设备并使用出水控制。"
        !state.hasAppToken -> "尚未完成用水登录" to "使用同一手机号再次短信登录并选择“用水登录”，完成后即可使用设备。"
        else -> "暂无设备" to "点击右上角加号添加设备。"
    }
    Surface(shape = WaterShapeTokens.compact, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(detail, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MineTab(
    state: DashboardSummaryUiState,
    onLogin: () -> Unit,
    onAccounts: () -> Unit,
    onScores: () -> Unit,
    onWallet: () -> Unit,
    themeMode: AppThemeMode,
    onOpenAppearanceSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSupport: () -> Unit,
    autoTaskEnabled: Boolean,
    onToggleAutoTask: (Boolean) -> Unit,
    autoUpdateEnabled: Boolean,
    autoUpdateLabel: String,
    onToggleAutoUpdate: (Boolean) -> Unit,
    updateLabel: String,
    onCheckUpdate: () -> Unit,
    waterUsageLabel: String,
    onOpenWaterUsage: () -> Unit
) {
    InfoCard(title = state.accountTitle, subtitle = state.accountSubtitle) {
        StatusRow("账号数量", "${state.accountCount}")
        StatusRow("可用额度", state.scoreTitle)
        StatusRow("用水登录（必需）", state.appLoginStatusText)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onLogin, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("登录") }
            OutlinedButton(onClick = onAccounts, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("管理账号") }
        }
    }
    if (state.hasAccount) PersonalUsageSummary(usage = state.usage)
    SettingsGroup("账户与资金") {
        SettingsRow(Icons.Default.CheckCircle, "积分与消费记录", "查看积分收支和接水花费", onScores)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(Icons.Default.AccountBalanceWallet, "钱包与充值",
            if (state.hasAppToken) "查看余额、充值与积分兑换" else "需要用水登录", onWallet)
    }
    SettingsGroup("自动任务") {
        SettingsSwitchRow(
            Icons.Default.CheckCircle, "自动积分任务",
            if (autoTaskEnabled) "按设定时间自动运行" else "已关闭",
            autoTaskEnabled, onToggleAutoTask
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(Icons.Default.Refresh, "自动检查更新", autoUpdateLabel, autoUpdateEnabled, onToggleAutoUpdate)
    }
    SettingsGroup("应用设置") {
        SettingsRow(Icons.Default.WaterDrop, "预估水量设置", waterUsageLabel, onOpenWaterUsage)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(Icons.Default.Palette, "外观设置", themeMode.label, onOpenAppearanceSettings)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(Icons.Default.Refresh, "检查更新", updateLabel, onCheckUpdate)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(Icons.Default.Settings, "诊断日志", "开关 · 详细模式 · 导出", onOpenSettings)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(Icons.Default.Favorite, "支持开发者", "GitHub · wasdwx/life-798", onOpenSupport)
    }
    Text(
        text = "WaterWidget  v${BuildConfig.VERSION_NAME}",
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp
    )
}

@Composable
private fun PersonalUsageSummary(usage: WaterUsageUiState) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    InfoCard(title = "消费统计", subtitle = usage.syncNote) {
        if (largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageSummaryItem("今日", usage.todayCostText, usage.todayWaterText.takeIf { usage.showWaterEstimate }, Modifier.fillMaxWidth())
                UsageSummaryItem("本月", usage.monthCostText, usage.monthWaterText.takeIf { usage.showWaterEstimate }, Modifier.fillMaxWidth())
                UsageSummaryItem("本年", usage.yearCostText, usage.yearWaterText.takeIf { usage.showWaterEstimate }, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                UsageSummaryItem("今日", usage.todayCostText, usage.todayWaterText.takeIf { usage.showWaterEstimate }, Modifier.weight(1f))
                UsageSummaryItem("本月", usage.monthCostText, usage.monthWaterText.takeIf { usage.showWaterEstimate }, Modifier.weight(1f))
                UsageSummaryItem("本年", usage.yearCostText, usage.yearWaterText.takeIf { usage.showWaterEstimate }, Modifier.weight(1f))
            }
        }
        if (usage.showWaterEstimate) {
            Text("水量按统一比例估算，不区分设备", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageSummaryItem(label: String, cost: String, water: String?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp)
            Text(cost, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (water != null) Text(water, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = WaterShapeTokens.card, color = MaterialTheme.colorScheme.surface) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = WaterShapeTokens.card, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }
    }
}

/** 带开关的设置行：开关本身可点，行其余部分不触发动作。 */
@Composable
internal fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(shape = WaterShapeTokens.card, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AccountSwitcherDialog(accounts: List<DashboardAccountUiState>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换账号", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择当前使用的账号", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                accounts.forEach { account ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (account.isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = account.isCurrent,
                            role = Role.RadioButton,
                            onClick = { onSelect(account.phone) }
                        )
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                Text(account.title.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(account.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(account.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            if (account.isCurrent) Icon(Icons.Default.Check, contentDescription = "当前账号", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = WaterShapeTokens.prominent
    )
}

@Composable
private fun SupportDeveloperDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text("支持开发者", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "喜欢 WaterWidget 的话，欢迎到 GitHub 点个 Star，或提交问题与建议。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                SupportLink("本改版仓库（个人版）", AppUpdates.REPOSITORY_URL) {
                    runCatching { uriHandler.openUri(AppUpdates.REPOSITORY_URL) }
                }
                SupportLink("上游原作者仓库", "https://github.com/nocookies111/life-798") {
                    runCatching { uriHandler.openUri("https://github.com/nocookies111/life-798") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        shape = WaterShapeTokens.prominent
    )
}

@Composable
private fun SupportLink(title: String, url: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = WaterShapeTokens.compact,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun BottomTabs(
    selectedTab: DashboardTab,
    pagerState: PagerState,
    onSelect: (DashboardTab) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 44f,
                colors = BlurColors(
                    blendColors = listOf(BlendColorEntry(colors.surface.copy(alpha = 0.64f)))
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars),
        shape = RectangleShape,
        color = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            val itemWidth = maxWidth / DashboardTab.entries.size
            val indicatorWidth = minOf(itemWidth - 16.dp, 64.dp)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val pagerPosition = (
                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                        ).coerceIn(
                            DashboardTab.CONTROL.ordinal.toFloat(),
                            DashboardTab.MINE.ordinal.toFloat()
                        )
                        IntOffset(
                            x = (itemWidth * pagerPosition + (itemWidth - indicatorWidth) / 2).roundToPx(),
                            y = 5.dp.roundToPx()
                        )
                    }
                    .width(indicatorWidth)
                    .height(32.dp)
                    .graphicsLayer {
                        val pagerPosition = (
                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                        ).coerceIn(
                            DashboardTab.CONTROL.ordinal.toFloat(),
                            DashboardTab.MINE.ordinal.toFloat()
                        )
                        val transitionPhase = abs(sin(PI * pagerPosition)).toFloat()
                        scaleX = 1f - 0.18f * transitionPhase
                        scaleY = 1f - 0.10f * transitionPhase
                    },
                shape = RoundedCornerShape(16.dp),
                color = colors.secondaryContainer
            ) {}
            Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
                BottomTab(
                    tab = DashboardTab.CONTROL,
                    selected = selectedTab == DashboardTab.CONTROL,
                    icon = Icons.Default.Home,
                    onClick = onSelect
                )
                BottomTab(
                    tab = DashboardTab.TASK,
                    selected = selectedTab == DashboardTab.TASK,
                    icon = Icons.Default.CheckCircle,
                    onClick = onSelect
                )
                BottomTab(
                    tab = DashboardTab.MINE,
                    selected = selectedTab == DashboardTab.MINE,
                    icon = Icons.Default.Person,
                    onClick = onSelect
                )
            }
        }
    }
}

@Composable
private fun RowScope.BottomTab(
    tab: DashboardTab,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (DashboardTab) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        label = "bottomTabContent"
    )
    Surface(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = { onClick(tab) }
            ),
        shape = RectangleShape,
        color = Color.Transparent,
        contentColor = contentColor
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = tab.label,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardPreview() {
    WaterTheme {
        DashboardScreen(
            state = DashboardUiState(summary = DashboardSummaryUiState("测试账户", "设备控制已连接", "2380", "≈2.38元可用", true, true, true, 2, listOf(DeviceUiState("device-001", "一号饮水机"), DeviceUiState("device-002", "二号饮水机")))),
            onLogin = {},
            onAccounts = {},
            themeMode = AppThemeMode.SYSTEM,
            onAppearance = {},
            onRunTasks = {},
            onStopTasks = {},
            onScores = {},
            onWallet = {},
            onSelectAccount = {},
            onFetchDevices = {},
            onRefreshHome = {},
            onAddDevice = {},
            onEditDevice = { _, _ -> },
            onRemoveDevice = {},
            onSelectDevice = {},
            onStartDevice = {}
        )
    }
}
