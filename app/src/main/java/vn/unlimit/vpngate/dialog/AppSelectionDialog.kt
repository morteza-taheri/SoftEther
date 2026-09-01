package vn.unlimit.vpngate.dialog

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.adapter.AppSelectionAdapter
import vn.unlimit.vpngate.models.ExcludedApp

/**
 * Modern searchable app-selection sheet (2026 redesign): a Material
 * bottom sheet with a rounded grab handle, a real search bar and a live
 * count badge. Replaces the old centered dialog; the public API
 * (listener + preselected apps) is unchanged so all call sites keep
 * working.
 */
class AppSelectionDialog : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var loadingProgress: ProgressBar
    private lateinit var excludedCountLabel: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnAdd: Button
    private lateinit var appSelectionAdapter: AppSelectionAdapter
    private var listener: AppSelectionListener? = null
    private var excludedApps: List<ExcludedApp> = emptyList()
    private var allApps: List<ExcludedApp> = emptyList()
    private var originalExcludedApps: List<ExcludedApp> = emptyList()
    private var isLoadingCancelled = false
    private val loadScope = CoroutineScope(Dispatchers.IO)

    interface AppSelectionListener {
        fun onAppsSelected(apps: List<ExcludedApp>)
    }

    fun setAppSelectionListener(listener: AppSelectionListener) {
        this.listener = listener
    }

    fun setExcludedApps(excludedApps: List<ExcludedApp>) {
        this.excludedApps = excludedApps
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_app_selection, container, false)

        recyclerView = view.findViewById(R.id.recycler_view_apps)
        searchInput = view.findViewById(R.id.search_input)
        loadingProgress = view.findViewById(R.id.loading_progress)
        excludedCountLabel = view.findViewById(R.id.excluded_count_label)
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnAdd = view.findViewById(R.id.btn_add)

        setupSearchInput()
        setupRecyclerView()
        setupButtons()
        loadApps()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Expand to (near) full height so the list gets maximum space,
        // still draggable/collapsible like a modern bottom sheet.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        }
    }

    private fun setupRecyclerView() {
        appSelectionAdapter = AppSelectionAdapter(emptyList())
        appSelectionAdapter.setSelectionChangeListener(object : AppSelectionAdapter.SelectionChangeListener {
            override fun onSelectionChanged() {
                updateCountLabel()
                updateApplyButtonState()
            }
        })
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = appSelectionAdapter
    }

    private fun setupButtons() {
        btnCancel.setOnClickListener {
            isLoadingCancelled = true
            dismiss()
        }
        btnAdd.setOnClickListener {
            val selectedApps = appSelectionAdapter.getSelectedApps()
            listener?.onAppsSelected(selectedApps)
            dismiss()
        }
    }

    private fun setupSearchInput() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadApps() {
        showLoading(true)
        isLoadingCancelled = false

        loadScope.launch {
            try {
                val apps = getInstalledApps()

                if (!isLoadingCancelled && isAdded && activity != null) {
                    withContext(Dispatchers.Main) {
                        if (!isLoadingCancelled && isAdded && activity != null) {
                            allApps = apps
                            originalExcludedApps = excludedApps.toList()
                            appSelectionAdapter.initializeWithPreSelectedApps(apps, excludedApps)
                            updateCountLabel()
                            updateApplyButtonState()
                            showLoading(false)
                            recyclerView.post {
                                appSelectionAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!isLoadingCancelled && isAdded && activity != null) {
                    withContext(Dispatchers.Main) {
                        if (!isLoadingCancelled && isAdded && activity != null) {
                            showLoading(false)
                            Toast.makeText(context, "Error loading apps", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun filterApps(query: String) {
        val filteredApps = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { app ->
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            }
        }
        appSelectionAdapter.updateApps(filteredApps)
    }

    private fun showLoading(show: Boolean) {
        if (show) {
            loadingProgress.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            searchInput.isEnabled = false
            btnAdd.visibility = View.GONE
        } else {
            loadingProgress.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            searchInput.isEnabled = true
            btnAdd.visibility = View.VISIBLE
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getInstalledApps(): List<ExcludedApp> {
        val pm = requireContext().packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return apps.filter { appInfo ->
            appInfo.packageName != requireContext().packageName
        }.map { appInfo ->
            ExcludedApp(
                packageName = appInfo.packageName,
                appName = pm.getApplicationLabel(appInfo).toString()
            )
        }.sortedBy { it.appName }
    }

    private fun updateCountLabel() {
        val selectedCount = appSelectionAdapter.getSelectedApps().size
        excludedCountLabel.text = getString(R.string.exclude_apps_text, selectedCount)
    }

    private fun updateApplyButtonState() {
        val currentSelectedApps = appSelectionAdapter.getSelectedApps()

        val originalPackageNames = originalExcludedApps.map { it.packageName }.toSet()
        val currentPackageNames = currentSelectedApps.map { it.packageName }.toSet()

        val hasChanges = originalPackageNames != currentPackageNames
        btnAdd.isEnabled = hasChanges
        btnAdd.alpha = if (hasChanges) 1.0f else 0.5f
    }
}
