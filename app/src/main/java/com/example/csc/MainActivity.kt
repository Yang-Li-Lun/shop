package com.example.csc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.csc.automation.AutomationConfig
import com.example.csc.automation.DailyTriggerStats
import com.example.csc.automation.RecognitionRegion
import com.example.csc.automation.RecognitionTarget
import com.example.csc.automation.RecognitionZone
import com.example.csc.automation.ScreenAutomationService
import com.example.csc.automation.TargetMode
import com.example.csc.automation.isValidTargetPackage
import com.example.csc.capture.MediaProjectionCaptureService
import com.example.csc.capture.MediaProjectionAuthorizationGate
import com.example.csc.ui.RegionSelectorView
import java.time.LocalDate
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var zonesContainer: LinearLayout
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdSeek: SeekBar
    private lateinit var circleXThresholdLabel: TextView
    private lateinit var circleXThresholdSeek: SeekBar
    private lateinit var backArrowThresholdLabel: TextView
    private lateinit var backArrowThresholdSeek: SeekBar
    private lateinit var cooldownLabel: TextView
    private lateinit var cooldownSeek: SeekBar
    private lateinit var randomClickTimeLabel: TextView
    private lateinit var randomClickTimeSeek: SeekBar
    private lateinit var numberMonitorSwitch: Switch
    private lateinit var dailyTriggerStatsText: TextView
    private lateinit var numberMonitorSelector: RegionSelectorView
    private lateinit var numberMonitorRegionLabel: TextView
    private lateinit var numberMonitorThresholdInput: EditText
    private lateinit var numberMonitorUpperLimitInput: EditText
    private lateinit var numberColorFilterSwitch: Switch
    private lateinit var numberColorHexInput: EditText
    private lateinit var numberColorToleranceInput: EditText
    private lateinit var numberMonitorWaitSecondsInput: EditText
    private lateinit var numberTriggerZoneSpinner: Spinner
    private lateinit var numberTriggerWaitSecondsInput: EditText
    private lateinit var numberMonitorLeftInput: EditText
    private lateinit var numberMonitorTopInput: EditText
    private lateinit var numberMonitorRightInput: EditText
    private lateinit var numberMonitorBottomInput: EditText
    private lateinit var showClickMarkerSwitch: Switch
    private lateinit var automationSwitch: Switch
    private lateinit var automationStateText: TextView
    private lateinit var targetPackageInput: EditText

    private val zoneEditors = mutableListOf<ZoneEditor>()
    private var pendingImageZoneId: String? = null
    private var loadingUi = false
    private var updatingNumberMonitorPercentInputs = false
    private val projectionAuthorizationGate = MediaProjectionAuthorizationGate()
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 1_000L)
        }
    }
    private val captureStartTimeoutRunnable = Runnable {
        if (projectionAuthorizationGate.onStartTimeout()) {
            refreshStatus()
            toast("螢幕擷取啟動逾時，請重開自動辨識。")
        }
    }
    private var numberTriggerZoneIds = listOf<String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionAuthorizationGate.restoreState(savedInstanceState?.getString(STATE_PROJECTION_AUTHORIZATION))
        window.statusBarColor = SURFACE
        window.navigationBarColor = SURFACE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildContent())
        loadSettings()
        requestNotificationPermissionIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROJECTION_AUTHORIZATION, projectionAuthorizationGate.saveState())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshDailyTriggerStats()
        if (::automationSwitch.isInitialized) {
            loadingUi = true
            automationSwitch.isChecked = AutomationConfig.read(this).enabled
            loadingUi = false
        }
        ScreenAutomationService.requestImmediateRefresh()
        statusHandler.removeCallbacks(statusRefreshRunnable)
        statusHandler.post(statusRefreshRunnable)
        requestMediaProjectionIfNeeded()
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefreshRunnable)
        super.onPause()
    }

    @Deprecated("Uses platform result APIs for Android 10 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            statusHandler.removeCallbacks(captureStartTimeoutRunnable)
            if (resultCode == RESULT_OK && data != null) {
                projectionAuthorizationGate.onAuthorizationResult(granted = true)
                startForegroundService(MediaProjectionCaptureService.startIntent(this, resultCode, data))
                statusHandler.postDelayed(captureStartTimeoutRunnable, CAPTURE_START_TIMEOUT_MS)
                AutomationConfig.setEnabled(this, true)
                setSwitchWithoutCallback(true)
                refreshStatus()
                toast("已允許螢幕擷取，請切換到目標 App。")
            } else {
                projectionAuthorizationGate.onAuthorizationResult(granted = false)
                AutomationConfig.setEnabled(this, false)
                setSwitchWithoutCallback(false)
                toast("未允許螢幕擷取。")
            }
            return
        }
        if (requestCode != REQUEST_REFERENCE || resultCode != RESULT_OK) return
        val editor = zoneEditors.firstOrNull { it.zoneId == pendingImageZoneId } ?: return
        val uris = buildList {
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
            data?.data?.let { if (it !in this) add(it) }
        }
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (editor.imageTargets.none { it.value == uri.toString() }) {
                editor.imageTargets += RecognitionTarget(
                    id = newId("image"),
                    mode = TargetMode.IMAGE,
                    value = uri.toString(),
                    label = displayName(uri) ?: "參考圖片",
                )
            }
        }
        pendingImageZoneId = null
        renderImageTargets(editor)
        saveSettings()
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(SURFACE)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(22), dp(20), dp(22), dp(36))
        }
        scroll.addView(root, matchWidth())

        root.addView(text("CSC", 30f, ON_SURFACE, Typeface.BOLD))
        dailyTriggerStatsText = text("", 14f, PRIMARY, Typeface.BOLD).apply {
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(dailyTriggerStatsText, matchWidth())
        root.addView(space(8))

        val statusCard = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusCard.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PRIMARY)
            }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
        statusText = text("檢查中…", 15f, ON_SURFACE, Typeface.BOLD)
        statusCard.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusCard)

        root.addView(button("1. 開啟無障礙").apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("請選擇「CSC」並允許。")
            }
        }, matchWidth())
        root.addView(space(14))

        val activationCard = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PRIMARY_CONTAINER, 18f)
        }
        activationCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("自動辨識", 18f, ON_PRIMARY_CONTAINER, Typeface.BOLD))
            automationStateText = text("已關閉", 13f, ON_PRIMARY_CONTAINER)
            addView(automationStateText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        automationSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, isChecked -> onAutomationToggled(isChecked) }
        }
        activationCard.addView(automationSwitch)
        root.addView(activationCard, matchWidth())
        root.addView(text("目標 App 套件", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(12), 0, 0)
        })
        targetPackageInput = EditText(this).apply {
            setText(AutomationConfig.DEFAULT_TARGET_PACKAGE)
            hint = "com.shopee.tw"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(targetPackageInput, matchWidth())
        root.addView(text("僅在此 App 前景執行。", 13f, MUTED))
        root.addView(space(14))

        val zonesSection = collapsibleSection(root, "2. 辨識區域")
        zonesSection.addView(text("支援文字、圖片與內建圖形。", 14f, MUTED).apply {
            setPadding(dp(2), 0, dp(2), dp(12))
        })
        zonesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zonesSection.addView(zonesContainer, matchWidth())
        zonesSection.addView(button("新增區域").apply {
            setOnClickListener {
                if (zoneEditors.size >= AutomationConfig.MAX_ZONES) {
                     toast("最多 ${AutomationConfig.MAX_ZONES} 個區域。")
                    return@setOnClickListener
                }
                addZoneEditor(
                    RecognitionZone(
                        id = newId("zone"),
                        name = "區域 ${zoneEditors.size + 1}",
                        region = RecognitionRegion.FULL,
                        targets = emptyList(),
                    ),
                )
                saveSettings()
            }
        }, matchWidth())

        root.addView(space(10))
        val numberSection = collapsibleSection(root, "3. 數字監控與上滑")
        val numberMonitorCard = card()
        val numberMonitorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        numberMonitorRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("數字監控", 16f, ON_SURFACE, Typeface.BOLD))
            addView(text("範圍內停留；低於門檻、超過上限或逾時未辨識時上滑。", 13f, MUTED))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        numberMonitorSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        numberMonitorRow.addView(numberMonitorSwitch)
        numberMonitorCard.addView(numberMonitorRow, matchWidth())
        numberMonitorCard.addView(text("停留門檻", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(16), 0, 0)
        })
        numberMonitorThresholdInput = EditText(this).apply {
            setText("0.15")
            hint = "0.15"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        numberMonitorCard.addView(numberMonitorThresholdInput, matchWidth())
        numberMonitorCard.addView(text("達門檻時停留。", 13f, MUTED))
        numberMonitorCard.addView(text("上限", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, 0)
        })
        numberMonitorUpperLimitInput = EditText(this).apply {
            setText("999999")
            hint = "例如：1.0"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        numberMonitorCard.addView(numberMonitorUpperLimitInput, matchWidth())
        numberMonitorCard.addView(text("超過時上滑；999999 = 不限制。", 13f, MUTED))
        val colorFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        colorFilterRow.addView(text("限制顏色", 14f, MUTED, Typeface.BOLD), LinearLayout.LayoutParams(0, dp(48), 1f))
        numberColorFilterSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        colorFilterRow.addView(numberColorFilterSwitch)
        numberMonitorCard.addView(colorFilterRow, matchWidth())
        numberColorHexInput = EditText(this).apply {
            setText("#FFFFFF")
            hint = "#RRGGBB，例如 #FF0000"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        numberMonitorCard.addView(numberColorHexInput, matchWidth())
        numberColorToleranceInput = EditText(this).apply {
            setText("45")
            hint = "45"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        numberMonitorCard.addView(text("顏色誤差（0–255）", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(8), 0, 0)
        })
        numberMonitorCard.addView(numberColorToleranceInput, matchWidth())
        numberMonitorCard.addView(text("預設 45；漏判時可提高。", 13f, MUTED))
        numberMonitorCard.addView(text("點擊後上滑區域", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, 0)
        })
        numberTriggerZoneSpinner = Spinner(this)
        numberTriggerZoneSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = saveSettings()
        }
        numberMonitorCard.addView(numberTriggerZoneSpinner, matchWidth())
        numberMonitorCard.addView(text("點擊後等待（秒）", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, 0)
        })
        numberTriggerWaitSecondsInput = EditText(this).apply {
            setText("0")
            hint = "0"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        numberMonitorCard.addView(numberTriggerWaitSecondsInput, matchWidth())
        numberMonitorCard.addView(text("0 = 立即。", 13f, MUTED))
        numberMonitorCard.addView(text("未辨識等待（秒）", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, 0)
        })
        numberMonitorWaitSecondsInput = EditText(this).apply {
            setText("2")
            hint = "2"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        numberMonitorCard.addView(numberMonitorWaitSecondsInput, matchWidth())
        numberMonitorCard.addView(text("可設 0.5–30。", 13f, MUTED))
        numberMonitorCard.addView(text("其他區域命中時暫停上滑倒數。", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        numberMonitorRegionLabel = text(regionLabel(RecognitionRegion.FULL), 15f, PRIMARY, Typeface.BOLD).apply {
            setPadding(0, dp(18), 0, 0)
        }
        numberMonitorCard.addView(numberMonitorRegionLabel)
        numberMonitorCard.addView(text("監控範圍（%）", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        fun percentInput(initialValue: String): EditText = EditText(this).apply {
            setText(initialValue)
            setSingleLine(true)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val percentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        numberMonitorLeftInput = percentInput("0")
        numberMonitorTopInput = percentInput("0")
        numberMonitorRightInput = percentInput("100")
        numberMonitorBottomInput = percentInput("100")
        listOf(
            "左 %" to numberMonitorLeftInput,
            "上 %" to numberMonitorTopInput,
            "右 %" to numberMonitorRightInput,
            "下 %" to numberMonitorBottomInput,
        ).forEach { (label, input) ->
            percentRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text(label, 12f, MUTED, Typeface.BOLD))
                addView(input, matchWidth())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
        }
        val percentWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateNumberMonitorRegionFromPercentInputs()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(numberMonitorLeftInput, numberMonitorTopInput, numberMonitorRightInput, numberMonitorBottomInput)
            .forEach { it.addTextChangedListener(percentWatcher) }
        numberMonitorCard.addView(percentRow, matchWidth())
        numberMonitorCard.addView(text("按「調整」後拖曳四角。", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        numberMonitorSelector = RegionSelectorView(this).apply {
            isEnabled = false
            setRegion(RecognitionRegion.FULL)
            onRegionChanged = { region ->
                numberMonitorRegionLabel.text = regionLabel(region)
                setNumberMonitorPercentInputs(region)
                saveSettings()
            }
        }
        numberMonitorCard.addView(numberMonitorSelector, matchWidth())
        val numberMonitorAdjustButton = button("調整監控範圍")
        numberMonitorAdjustButton.setOnClickListener {
            val editing = !numberMonitorSelector.isEnabled
            numberMonitorSelector.isEnabled = editing
            numberMonitorSelector.invalidate()
            numberMonitorAdjustButton.text = if (editing) "完成" else "調整監控範圍"
        }
        numberMonitorCard.addView(numberMonitorAdjustButton, matchWidth())
        numberMonitorCard.addView(space(8))
        numberMonitorCard.addView(button("設為全螢幕").apply {
            setOnClickListener { numberMonitorSelector.setRegion(RecognitionRegion.FULL, notify = true) }
        }, matchWidth())
        numberSection.addView(numberMonitorCard)

        root.addView(space(10))
        val tuningSection = collapsibleSection(root, "4. 辨識調整")
        val tuningCard = card()
        thresholdLabel = text("圖片相似度：82%", 15f, ON_SURFACE, Typeface.BOLD)
        tuningCard.addView(thresholdLabel)
        thresholdSeek = SeekBar(this).apply {
            min = 55
            max = 99
            progress = 82
            setOnSeekBarChangeListener(simpleSeekListener { progress ->
                thresholdLabel.text = "圖片相似度：$progress%"
                saveSettings()
            })
        }
        tuningCard.addView(thresholdSeek, matchWidth())
        tuningCard.addView(text("誤點調高；漏判調低。", 13f, MUTED))
        tuningCard.addView(space(18))
        circleXThresholdLabel = text("圓圈＋X 命中門檻：88%", 15f, ON_SURFACE, Typeface.BOLD)
        tuningCard.addView(circleXThresholdLabel)
        circleXThresholdSeek = SeekBar(this).apply {
            min = 50
            max = 99
            progress = 88
            setOnSeekBarChangeListener(simpleSeekListener { progress ->
                circleXThresholdLabel.text = "圓圈＋X 命中門檻：$progress%"
                saveSettings()
            })
        }
        tuningCard.addView(circleXThresholdSeek, matchWidth())
        tuningCard.addView(text("累積 16 筆背景後自動微調；圖形需二次確認。", 13f, MUTED))
        backArrowThresholdLabel = text("返回箭頭命中門檻：72%", 15f, ON_SURFACE, Typeface.BOLD)
        tuningCard.addView(backArrowThresholdLabel)
        backArrowThresholdSeek = SeekBar(this).apply {
            min = 50
            max = 99
            progress = 72
            setOnSeekBarChangeListener(simpleSeekListener { progress ->
                backArrowThresholdLabel.text = "返回箭頭命中門檻：$progress%"
                saveSettings()
            })
        }
        tuningCard.addView(backArrowThresholdSeek, matchWidth())
        tuningCard.addView(space(18))
        cooldownLabel = text("點擊冷卻：3 秒", 15f, ON_SURFACE, Typeface.BOLD)
        tuningCard.addView(cooldownLabel)
        cooldownSeek = SeekBar(this).apply {
            min = 1
            max = 15
            progress = 3
            setOnSeekBarChangeListener(simpleSeekListener { progress ->
                cooldownLabel.text = "點擊冷卻：$progress 秒"
                saveSettings()
            })
        }
        tuningCard.addView(cooldownSeek, matchWidth())
        tuningCard.addView(space(18))
        randomClickTimeLabel = text("點擊隨機延遲：500 毫秒", 15f, ON_SURFACE, Typeface.BOLD)
        tuningCard.addView(randomClickTimeLabel)
        randomClickTimeSeek = SeekBar(this).apply {
            min = 1
            max = 30
            progress = 5
            setOnSeekBarChangeListener(simpleSeekListener { progress ->
                randomClickTimeLabel.text = "隨機點擊時間上限：${progress * 100} 毫秒"
                saveSettings()
            })
        }
        tuningCard.addView(randomClickTimeSeek, matchWidth())
        tuningCard.addView(space(18))
        val markerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        markerRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("點擊位置", 15f, ON_SURFACE, Typeface.BOLD))
            addView(text("點擊後顯示紫點。", 13f, MUTED))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        showClickMarkerSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        markerRow.addView(showClickMarkerSwitch)
        tuningCard.addView(markerRow, matchWidth())
        tuningSection.addView(tuningCard)
        tuningSection.addView(space(2))

        tuningSection.addView(text("請勿用於付款、驗證碼等不可逆操作；可從通知列停止。", 13f, ERROR).apply {
            setPadding(dp(4), dp(18), dp(4), 0)
        })
        root.requestFocus()
        return scroll
    }

    private fun loadSettings() {
        loadingUi = true
        val settings = AutomationConfig.read(this)
        targetPackageInput.setText(settings.targetPackage)
        thresholdSeek.progress = (settings.matchThreshold * 100).toInt()
        circleXThresholdSeek.progress = (settings.circleXThreshold * 100).toInt()
        backArrowThresholdSeek.progress = (settings.backArrowThreshold * 100).toInt()
        cooldownSeek.progress = (settings.clickCooldownMs / 1_000L).toInt()
        randomClickTimeSeek.progress = (settings.randomClickMaxMs / 100L).toInt()
        numberMonitorSwitch.isChecked = settings.numberMonitorEnabled
        numberMonitorSelector.setRegion(settings.numberMonitorRegion)
        numberMonitorRegionLabel.text = regionLabel(settings.numberMonitorRegion)
        setNumberMonitorPercentInputs(settings.numberMonitorRegion)
        numberMonitorThresholdInput.setText(settings.numberMonitorThreshold.toString())
        numberMonitorUpperLimitInput.setText(settings.numberMonitorUpperLimit.toString())
        numberColorFilterSwitch.isChecked = settings.numberColorFilterEnabled
        numberColorHexInput.setText(settings.numberColorHex)
        numberColorToleranceInput.setText(settings.numberColorTolerance.toString())
        numberTriggerWaitSecondsInput.setText(secondsInputText(settings.numberTriggerDelayMs))
        numberMonitorWaitSecondsInput.setText(secondsInputText(settings.numberAbsenceTimeoutMs))
        showClickMarkerSwitch.isChecked = settings.showClickMarker
        automationSwitch.isChecked = settings.enabled
        thresholdLabel.text = "圖片相似度：${thresholdSeek.progress}%"
        circleXThresholdLabel.text = "圓圈＋X 命中門檻：${circleXThresholdSeek.progress}%"
        backArrowThresholdLabel.text = "返回箭頭命中門檻：${backArrowThresholdSeek.progress}%"
        cooldownLabel.text = "點擊冷卻：${cooldownSeek.progress} 秒"
        randomClickTimeLabel.text = "隨機點擊時間上限：${randomClickTimeSeek.progress * 100} 毫秒"
        zonesContainer.removeAllViews()
        zoneEditors.clear()
        settings.zones.ifEmpty {
            listOf(RecognitionZone(newId("zone"), "區域 1", RecognitionRegion.FULL, emptyList()))
        }.forEach(::addZoneEditor)
        refreshNumberTriggerZoneChoices(settings.numberTriggerZoneId)
        loadingUi = false
        saveSettings()
        refreshDailyTriggerStats()
    }

    private fun refreshDailyTriggerStats() {
        if (!::dailyTriggerStatsText.isInitialized) return
        val counts = DailyTriggerStats.lastThreeDays(this, LocalDate.now())
        dailyTriggerStatsText.text = counts.mapIndexed { index, item ->
            val label = when (index) {
                0 -> "今日"
                1 -> "昨日"
                else -> "前日"
            }
            "$label ${item.count} 次"
        }.joinToString("　")
    }

    private fun addZoneEditor(zone: RecognitionZone) {
        val normalized = zone.normalized()
        val circleXOnly = normalized.targets.any { it.mode == TargetMode.CIRCLE_X }
        val backArrowOnly = normalized.targets.any { it.mode == TargetMode.BACK_ARROW }
        val card = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameInput = EditText(this).apply {
            setText(normalized.name)
            hint = "區域名稱"
            textSize = 18f
            setSingleLine(true)
        }
        header.addView(nameInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        val deleteButton = button("刪除").apply {
            background = rounded(ERROR, 12f)
            minHeight = dp(44)
        }
        header.addView(deleteButton, LinearLayout.LayoutParams(dp(92), dp(48)).apply { marginStart = dp(8) })
        card.addView(header, matchWidth())

        val textInput = EditText(this).apply {
            hint = "例如：\n直播\n立即購買"
            textSize = 16f
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(normalized.targets.filter { it.mode == TargetMode.TEXT }.joinToString("\n") { it.value })
        }
        val imagesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addImageButton = button("加入圖片")
        if (circleXOnly) {
            card.addView(text("圓圈＋X", 16f, PRIMARY, Typeface.BOLD).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            card.addView(text("內建圖形辨識，不需參考圖片。", 13f, MUTED))
        } else if (backArrowOnly) {
            card.addView(text("返回箭頭", 16f, PRIMARY, Typeface.BOLD).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            card.addView(text("內建圖形辨識，不需參考圖片。", 13f, MUTED))
        } else {
            card.addView(text("文字（一行一個）", 14f, MUTED, Typeface.BOLD).apply {
                setPadding(0, dp(12), 0, 0)
            })
            card.addView(textInput, matchWidth())
            card.addView(text("參考圖片", 14f, MUTED, Typeface.BOLD).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            card.addView(imagesContainer, matchWidth())
            card.addView(addImageButton, matchWidth())
        }

        val zoneColor = ZONE_COLORS[zoneEditors.size % ZONE_COLORS.size]
        val regionLabel = text(regionLabel(normalized.region), 15f, zoneColor, Typeface.BOLD).apply {
            setPadding(0, dp(18), 0, 0)
        }
        card.addView(regionLabel)
        card.addView(text("辨識範圍（%）", 14f, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        val zonePercentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val zoneLeftInput = percentInput(percentText(normalized.region.left))
        val zoneTopInput = percentInput(percentText(normalized.region.top))
        val zoneRightInput = percentInput(percentText(normalized.region.right))
        val zoneBottomInput = percentInput(percentText(normalized.region.bottom))
        listOf(
            "左 %" to zoneLeftInput,
            "上 %" to zoneTopInput,
            "右 %" to zoneRightInput,
            "下 %" to zoneBottomInput,
        ).forEach { (label, input) ->
            zonePercentRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text(label, 12f, MUTED, Typeface.BOLD))
                addView(input, matchWidth())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
        }
        card.addView(zonePercentRow, matchWidth())
        card.addView(text("按「調整」後拖曳四角。", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        val selector = RegionSelectorView(this).apply {
            isEnabled = false
            setRegion(normalized.region)
        }
        card.addView(selector, matchWidth())
        val adjustButton = button("調整範圍")
        card.addView(adjustButton, matchWidth())
        card.addView(space(8))
        card.addView(button("設為全螢幕").apply {
            setOnClickListener { selector.setRegion(RecognitionRegion.FULL, notify = true) }
        }, matchWidth())

        val editor = ZoneEditor(
            zoneId = normalized.id,
            card = card,
            nameInput = nameInput,
            textInput = textInput,
            imageTargets = normalized.targets.filter { it.mode == TargetMode.IMAGE }.toMutableList(),
            imagesContainer = imagesContainer,
            regionSelector = selector,
            regionLabel = regionLabel,
            circleXOnly = circleXOnly,
            backArrowOnly = backArrowOnly,
        )
        zoneEditors += editor
        zonesContainer.addView(card)
        renderImageTargets(editor)
        if (!loadingUi && ::numberTriggerZoneSpinner.isInitialized) {
            refreshNumberTriggerZoneChoices(numberTriggerZoneIds.getOrNull(numberTriggerZoneSpinner.selectedItemPosition))
        }

        var updatingZonePercentInputs = false
        fun setZonePercentInputs(region: RecognitionRegion) {
            updatingZonePercentInputs = true
            zoneLeftInput.setText(percentText(region.left))
            zoneTopInput.setText(percentText(region.top))
            zoneRightInput.setText(percentText(region.right))
            zoneBottomInput.setText(percentText(region.bottom))
            updatingZonePercentInputs = false
        }
        fun updateZoneRegionFromPercentInputs() {
            if (loadingUi || updatingZonePercentInputs) return
            val values = listOf(zoneLeftInput, zoneTopInput, zoneRightInput, zoneBottomInput)
                .map { input -> input.text.toString().replace(',', '.').toFloatOrNull() ?: return }
            val region = RecognitionRegion(
                values[0] / 100f,
                values[1] / 100f,
                values[2] / 100f,
                values[3] / 100f,
            ).normalized()
            selector.setRegion(region)
            regionLabel.text = regionLabel(region)
            saveSettings()
        }
        val zonePercentWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                updateZoneRegionFromPercentInputs()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(zoneLeftInput, zoneTopInput, zoneRightInput, zoneBottomInput)
            .forEach { it.addTextChangedListener(zonePercentWatcher) }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = saveSettings()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        nameInput.addTextChangedListener(watcher)
        textInput.addTextChangedListener(watcher)
        selector.onRegionChanged = { region ->
            regionLabel.text = regionLabel(region)
            setZonePercentInputs(region)
            saveSettings()
        }
        adjustButton.setOnClickListener {
            val editing = !selector.isEnabled
            selector.isEnabled = editing
            selector.invalidate()
            adjustButton.text = if (editing) "完成" else "調整範圍"
        }
        addImageButton.setOnClickListener { chooseReferenceImages(editor.zoneId) }
        deleteButton.setOnClickListener {
            if (zoneEditors.size == 1) {
                toast("至少保留一個區域。")
                return@setOnClickListener
            }
            zoneEditors.remove(editor)
            zonesContainer.removeView(card)
            refreshZoneColors()
            refreshNumberTriggerZoneChoices(numberTriggerZoneIds.getOrNull(numberTriggerZoneSpinner.selectedItemPosition))
            saveSettings()
        }
    }

    private fun refreshZoneColors() {
        zoneEditors.forEachIndexed { index, editor ->
            editor.regionLabel.setTextColor(ZONE_COLORS[index % ZONE_COLORS.size])
        }
    }

    private fun renderImageTargets(editor: ZoneEditor) {
        editor.imagesContainer.removeAllViews()
        if (editor.imageTargets.isEmpty()) {
            editor.imagesContainer.addView(text("尚未加入圖片", 13f, MUTED).apply {
                setPadding(0, 0, 0, dp(8))
            })
            return
        }
        editor.imageTargets.forEach { target ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            row.addView(text(target.label.ifBlank { "參考圖片" }, 14f, ON_SURFACE), LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ))
            row.addView(button("移除").apply {
                minHeight = dp(40)
                setOnClickListener {
                    editor.imageTargets.remove(target)
                    renderImageTargets(editor)
                    saveSettings()
                }
            }, LinearLayout.LayoutParams(dp(88), dp(44)).apply { marginStart = dp(8) })
            editor.imagesContainer.addView(row)
        }
    }

    private fun collectZones(): List<RecognitionZone> = zoneEditors.map { editor ->
        if (editor.circleXOnly) return@map RecognitionZone(
            id = editor.zoneId,
            name = editor.nameInput.text.toString(),
            region = editor.regionSelector.getRegion(),
            targets = listOf(RecognitionTarget("${editor.zoneId}-circle-x", TargetMode.CIRCLE_X, "circle_x", "圓圈＋X")),
        ).normalized()
        if (editor.backArrowOnly) return@map RecognitionZone(
            id = editor.zoneId,
            name = editor.nameInput.text.toString(),
            region = editor.regionSelector.getRegion(),
            targets = listOf(RecognitionTarget("${editor.zoneId}-back-arrow", TargetMode.BACK_ARROW, "back_arrow", "返回箭頭")),
        ).normalized()
        val imageTargets = editor.imageTargets.take(RecognitionZone.MAX_TARGETS_PER_ZONE)
        val availableTextSlots = RecognitionZone.MAX_TARGETS_PER_ZONE - imageTargets.size
        val textTargets = editor.textInput.text.toString()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(availableTextSlots)
            .map { value ->
                RecognitionTarget(
                    id = "${editor.zoneId}-text-${value.hashCode()}",
                    mode = TargetMode.TEXT,
                    value = value,
                    label = value,
                )
            }
            .toList()
        RecognitionZone(
            id = editor.zoneId,
            name = editor.nameInput.text.toString(),
            region = editor.regionSelector.getRegion(),
            targets = textTargets + imageTargets,
        ).normalized()
    }

    private fun saveSettings() {
        if (loadingUi || !::zonesContainer.isInitialized || !::thresholdSeek.isInitialized || !::backArrowThresholdSeek.isInitialized) return
        AutomationConfig.update(
            context = this,
            zones = collectZones(),
            targetPackage = targetPackageInput.text.toString(),
            matchThreshold = thresholdSeek.progress / 100f,
            circleXThreshold = circleXThresholdSeek.progress / 100f,
            backArrowThreshold = backArrowThresholdSeek.progress / 100f,
            clickCooldownMs = cooldownSeek.progress * 1_000L,
            showClickMarker = showClickMarkerSwitch.isChecked,
            randomClickMaxMs = randomClickTimeSeek.progress * 100L,
            numberMonitorEnabled = numberMonitorSwitch.isChecked,
            numberMonitorRegion = numberMonitorSelector.getRegion(),
            numberMonitorThreshold = numberMonitorThresholdInput.text.toString()
                .replace(',', '.')
                .toFloatOrNull()
                ?: 0.15f,
            numberMonitorUpperLimit = numberMonitorUpperLimitInput.text.toString()
                .replace(',', '.')
                .toFloatOrNull()
                ?: 999_999f,
            numberColorFilterEnabled = numberColorFilterSwitch.isChecked,
            numberColorHex = numberColorHexInput.text.toString(),
            numberColorTolerance = numberColorToleranceInput.text.toString().toIntOrNull() ?: 45,
            numberAbsenceTimeoutMs = numberMonitorWaitSecondsInput.text.toString()
                .replace(',', '.')
                .toDoubleOrNull()
                ?.times(1_000.0)
                ?.toLong()
                ?.coerceIn(500L, 30_000L)
                ?: 2_000L,
            numberTriggerZoneId = numberTriggerZoneIds.getOrNull(numberTriggerZoneSpinner.selectedItemPosition),
            numberTriggerDelayMs = numberTriggerWaitSecondsInput.text.toString()
                .replace(',', '.')
                .toDoubleOrNull()
                ?.times(1_000.0)
                ?.toLong()
                ?.coerceIn(0L, 30_000L)
                ?: 0L,
        )
        ScreenAutomationService.requestImmediateRefresh()
    }

    private fun updateNumberMonitorRegionFromPercentInputs() {
        if (loadingUi || updatingNumberMonitorPercentInputs || !::numberMonitorSelector.isInitialized) return
        val values = listOf(
            numberMonitorLeftInput,
            numberMonitorTopInput,
            numberMonitorRightInput,
            numberMonitorBottomInput,
        ).map { input -> input.text.toString().replace(',', '.').toFloatOrNull() ?: return }
        val region = RecognitionRegion(
            values[0] / 100f,
            values[1] / 100f,
            values[2] / 100f,
            values[3] / 100f,
        ).normalized()
        numberMonitorSelector.setRegion(region)
        numberMonitorRegionLabel.text = regionLabel(region)
        saveSettings()
    }

    private fun setNumberMonitorPercentInputs(region: RecognitionRegion) {
        if (!::numberMonitorLeftInput.isInitialized) return
        updatingNumberMonitorPercentInputs = true
        numberMonitorLeftInput.setText(percentText(region.left))
        numberMonitorTopInput.setText(percentText(region.top))
        numberMonitorRightInput.setText(percentText(region.right))
        numberMonitorBottomInput.setText(percentText(region.bottom))
        updatingNumberMonitorPercentInputs = false
    }

    private fun percentInput(initialValue: String): EditText = EditText(this).apply {
        setText(initialValue)
        setSingleLine(true)
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun percentText(value: Float): String {
        val percent = value * 100f
        return if (percent % 1f == 0f) percent.toInt().toString() else "%.1f".format(Locale.US, percent)
    }

    private fun secondsInputText(milliseconds: Long): String =
        if (milliseconds % 1_000L == 0L) {
            (milliseconds / 1_000L).toString()
        } else {
            "%.1f".format(Locale.US, milliseconds / 1_000.0)
        }

    private fun refreshNumberTriggerZoneChoices(selectedZoneId: String?) {
        if (!::numberTriggerZoneSpinner.isInitialized) return
        val choices = listOf(null) + zoneEditors.map { it.zoneId }
        val labels = listOf("不使用") + zoneEditors.mapIndexed { index, editor ->
            "${index + 1}. ${editor.nameInput.text.toString().trim().ifBlank { "未命名區域" }}"
        }
        numberTriggerZoneIds = choices
        numberTriggerZoneSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        numberTriggerZoneSpinner.setSelection(choices.indexOf(selectedZoneId).takeIf { it >= 0 } ?: 0)
    }

    private fun onAutomationToggled(enabled: Boolean) {
        if (loadingUi) return
        saveSettings()
        if (enabled) {
            val targetPackage = targetPackageInput.text.toString().trim()
            if (!isValidTargetPackage(targetPackage) || targetPackage == packageName) {
                setSwitchWithoutCallback(false)
                toast("請輸入有效的目標 App 套件。")
                return
            }
            if (collectZones().none { it.targets.isNotEmpty() }) {
                setSwitchWithoutCallback(false)
                toast("請先加入辨識項目。")
                return
            }
            if (!AutomationConfig.isAccessibilityServiceEnabled(this)) {
                AutomationConfig.setEnabled(this, true)
                toast("請選擇「CSC」並允許。")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            if (Build.VERSION.SDK_INT < 30 && !MediaProjectionCaptureService.running) {
                AutomationConfig.setEnabled(this, true)
                requestMediaProjectionIfNeeded()
                return
            }
        }
        AutomationConfig.setEnabled(this, enabled)
        if (!enabled) {
            projectionAuthorizationGate.reset()
            statusHandler.removeCallbacks(captureStartTimeoutRunnable)
            MediaProjectionCaptureService.stop(this)
        }
        ScreenAutomationService.requestImmediateRefresh()
        refreshStatus()
        if (enabled) toast("已啟用，請切換到目標 App。")
    }

    private fun setSwitchWithoutCallback(value: Boolean) {
        loadingUi = true
        automationSwitch.isChecked = value
        loadingUi = false
        AutomationConfig.setEnabled(this, value)
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val systemEnabled = AutomationConfig.isAccessibilityServiceEnabled(this)
        val settings = AutomationConfig.read(this)
        projectionAuthorizationGate.onCaptureRunningChanged(MediaProjectionCaptureService.running)
        if (MediaProjectionCaptureService.running) {
            statusHandler.removeCallbacks(captureStartTimeoutRunnable)
        }
        statusText.text = when {
            Build.VERSION.SDK_INT < 30 && MediaProjectionCaptureService.running && ScreenAutomationService.connected ->
                "服務已連線"
            ScreenAutomationService.connected -> "服務已連線"
            systemEnabled -> "等待服務連線"
            else -> "請開啟無障礙"
        }
        if (::automationStateText.isInitialized) {
            automationStateText.text = if (settings.enabled) "已開啟" else "已關閉"
        }
    }

    private fun requestMediaProjectionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 30) return
        val settings = AutomationConfig.read(this)
        if (!projectionAuthorizationGate.tryBeginRequest(
                enabled = settings.enabled,
                accessibilityEnabled = AutomationConfig.isAccessibilityServiceEnabled(this),
                captureRunning = MediaProjectionCaptureService.running,
            )
        ) return
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun chooseReferenceImages(zoneId: String) {
        pendingImageZoneId = zoneId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        startActivityForResult(intent, REQUEST_REFERENCE)
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun regionLabel(region: RecognitionRegion): String {
        val normalized = region.normalized()
        return if (normalized == RecognitionRegion.FULL) "全螢幕" else {
            "左 ${(normalized.left * 100).toInt()}% · 上 ${(normalized.top * 100).toInt()}% · " +
                "右 ${(normalized.right * 100).toInt()}% · 下 ${(normalized.bottom * 100).toInt()}%"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun collapsibleSection(
        parent: LinearLayout,
        title: String,
        initiallyExpanded: Boolean = false,
    ): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val header = TextView(this).apply {
            textSize = 18f
            setTextColor(ON_SURFACE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(CARD, 16f, STROKE)
            isClickable = true
            isFocusable = true
            minHeight = dp(56)
        }
        fun updateHeader(expanded: Boolean) {
            header.text = if (expanded) "▼  $title" else "▶  $title"
            header.contentDescription = if (expanded) "$title，已展開，點擊收合" else "$title，已收合，點擊展開"
            content.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        var expanded = initiallyExpanded
        header.setOnClickListener {
            expanded = !expanded
            updateHeader(expanded)
        }
        updateHeader(expanded)
        parent.addView(header, matchWidth())
        parent.addView(content, matchWidth())
        return content
    }

    private fun text(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setTypeface(typeface, style)
            setLineSpacing(0f, 1.12f)
        }

    private fun button(label: String): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(PRIMARY, 14f)
        minHeight = dp(52)
        stateListAnimator = null
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = rounded(CARD, 18f, STROKE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(fill)
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun simpleSeekListener(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun space(heightDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun newId(prefix: String): String = "$prefix-${System.currentTimeMillis()}-${View.generateViewId()}"

    private data class ZoneEditor(
        val zoneId: String,
        val card: LinearLayout,
        val nameInput: EditText,
        val textInput: EditText,
        val imageTargets: MutableList<RecognitionTarget>,
        val imagesContainer: LinearLayout,
        val regionSelector: RegionSelectorView,
        val regionLabel: TextView,
        val circleXOnly: Boolean,
        val backArrowOnly: Boolean,
    )

    companion object {
        private const val STATE_PROJECTION_AUTHORIZATION = "projection_authorization"
        private const val REQUEST_REFERENCE = 501
        private const val REQUEST_NOTIFICATIONS = 502
        private const val REQUEST_SCREEN_CAPTURE = 503
        private const val CAPTURE_START_TIMEOUT_MS = 5_000L
        private val SURFACE = Color.rgb(255, 251, 254)
        private val CARD = Color.WHITE
        private val STROKE = Color.rgb(226, 221, 229)
        private val ON_SURFACE = Color.rgb(29, 27, 32)
        private val MUTED = Color.rgb(73, 69, 79)
        private val PRIMARY = Color.rgb(103, 80, 164)
        private val PRIMARY_CONTAINER = Color.rgb(234, 221, 255)
        private val ON_PRIMARY_CONTAINER = Color.rgb(33, 0, 93)
        private val ERROR = Color.rgb(179, 38, 30)
        private val ZONE_COLORS = intArrayOf(
            Color.rgb(103, 80, 164),
            Color.rgb(0, 121, 107),
            Color.rgb(0, 94, 184),
            Color.rgb(198, 80, 0),
        )
    }
}
