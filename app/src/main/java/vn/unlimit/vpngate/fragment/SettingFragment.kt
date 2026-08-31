package vn.unlimit.vpngate.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.InetAddresses.isNumericAddress
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.OpenVPNService
import kittoku.osc.preference.OscPrefKey
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.databinding.FragmentSettingBinding
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.SpinnerInit
import vn.unlimit.vpngate.utils.SpinnerInit.OnItemSelectedIndexListener
import java.text.DateFormat

/**
 * Created by dongh on 31/01/2018.
 */
class SettingFragment : Fragment(), View.OnClickListener, AdapterView.OnItemSelectedListener,
    CompoundButton.OnCheckedChangeListener, OnFocusChangeListener {
    private lateinit var dataUtil: DataUtil
    private lateinit var mContext: Context
    private lateinit var prefs: SharedPreferences

    private lateinit var binding: FragmentSettingBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedState: Bundle?
    ): View {
        binding = FragmentSettingBinding.inflate(layoutInflater)
        dataUtil = instance!!.dataUtil!!
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        binding.spinCacheTime.onItemSelectedListener = this
        binding.btnClearCache.setOnClickListener(this)
        binding.lnBlockAds.setOnClickListener(this)
        binding.swBlockAds.setChecked(dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false))
        binding.swBlockAds.setOnCheckedChangeListener(this)
        binding.lnUdp.setOnClickListener(this)
        binding.swUdp.setChecked(dataUtil.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true))
        binding.swUdp.setOnCheckedChangeListener(this)
        binding.lnAutoProtocol.setOnClickListener(this)
        updateAutoProtocolLabel()
        binding.lnAutoTimeout.setOnClickListener(this)
        updateAutoTimeoutLabel()
        binding.lnSoftetherMaxConnections.setOnClickListener(this)
        updateSoftEtherMaxConnectionsLabel()
        val spinnerInit = SpinnerInit(context, binding.spinCacheTime)
        val listCacheTime = resources.getStringArray(R.array.setting_cache_time)
        spinnerInit.setStringArray(
            listCacheTime,
            listCacheTime[
                dataUtil.getIntSetting(
                    DataUtil.SETTING_CACHE_TIME_KEY,
                    DataUtil.DEFAULT_CACHE_TIME_INDEX,
                )
            ]
        )
        spinnerInit.onItemSelectedIndexListener = object : OnItemSelectedIndexListener {
            override fun onItemSelected(name: String?, index: Int) {
                dataUtil.setIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, index)
            }
        }
        // App language (system default / English / فارسی): applies
        // AppCompatDelegate per-app locales; recreation picks up the change.
        val languageInit = SpinnerInit(context, binding.spinLanguage)
        val languageCodes = arrayOf("", "en", "fa")
        val languageNames = arrayOf(
            getString(R.string.setting_language_system),
            "English",
            "فارسی",
        )
        val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
        val currentTag = when {
            currentLocale.startsWith("fa") -> "fa"
            currentLocale.startsWith("en") -> "en"
            else -> ""
        }
        languageInit.setStringArray(languageNames, languageNames[languageCodes.indexOf(currentTag)])
        languageInit.onItemSelectedIndexListener = object : OnItemSelectedIndexListener {
            override fun onItemSelected(name: String?, index: Int) {
                val tag = languageCodes.getOrElse(index) { "" }
                val locales = if (tag.isEmpty()) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            }
        }
        // Developer Mode switch: default ON (troubleshooting). Turning it
        // off suppresses every collector/auto-mode log line for speed.
        binding.swDeveloperMode.isChecked = dataUtil.getDeveloperMode()
        binding.swDeveloperMode.setOnCheckedChangeListener { _, isChecked ->
            dataUtil.setDeveloperMode(isChecked)
            vn.unlimit.vpngate.data.model.CollectorLog.enabled = isChecked
            vn.unlimit.vpngate.automode.AutoModeLogStore.setPaused(!isChecked)
        }
        onHiddenChanged(false)
        binding.lnDns.setOnClickListener(this)
        if (dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)) {
            binding.swDns.setChecked(true)
            binding.lnDnsIp.visibility = View.VISIBLE
        } else {
            binding.swDns.setChecked(false)
            binding.lnDnsIp.visibility = View.GONE
        }
        binding.swDns.setOnCheckedChangeListener(this)
        val inputFilters = this.ipInputFilters
        binding.txtDns1.setFilters(inputFilters)
        binding.txtDns1.setText(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8"))
        binding.txtDns1.onFocusChangeListener = this
        binding.txtDns2.setFilters(inputFilters)
        binding.txtDns2.setText(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, ""))
        binding.txtDns2.onFocusChangeListener = this
        binding.lnDomain.setOnClickListener(this)
        binding.swDomain.setChecked(dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false))
        binding.swDomain.setOnCheckedChangeListener(this)
        binding.lnNotifySpeed.setOnClickListener(this)
        binding.swNotifySpeed.setChecked(dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true))
        binding.swNotifySpeed.setOnCheckedChangeListener(this)
        if (dataUtil.lastVPNConnection != null) {
            binding.lnStartupScreen.visibility = View.VISIBLE
            val listScreen = resources.getStringArray(R.array.startup_screen)
            val spinnerInitScreen = SpinnerInit(context, binding.spinScreen)
            spinnerInitScreen.setStringArray(
                listScreen,
                listScreen[dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0)]
            )
            spinnerInitScreen.onItemSelectedIndexListener = object : OnItemSelectedIndexListener {
                override fun onItemSelected(name: String?, index: Int) {
                    dataUtil.setIntSetting(DataUtil.SETTING_STARTUP_SCREEN, index)
                    OpenVPNService.setNotificationActivityClass(if (index == 0) DetailActivity::class.java else MainActivity::class.java)
                }

            }
        } else {
            binding.lnStartupScreen.visibility = View.GONE
        }

        return binding.root
    }

    private val ipInputFilters: Array<InputFilter?>
        get() {
            val filters = arrayOfNulls<InputFilter>(1)
            filters[0] =
                InputFilter { source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int ->
                    if (end > start) {
                        val destTxt = dest.toString()
                        val resultingTxt = destTxt.substring(0, dstart) + source.subSequence(
                            start,
                            end
                        ) + destTxt.substring(dend)
                        if (!resultingTxt.matches("^\\d{1,3}(\\.(\\d{1,3}(\\.(\\d{1,3}(\\.(\\d{1,3})?)?)?)?)?)?".toRegex())) {
                            return@InputFilter ""
                        } else {
                            val splits =
                                resultingTxt.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
                                    .toTypedArray()
                            for (`val` in splits) {
                                if (`val`.toInt() > 255) {
                                    return@InputFilter ""
                                }
                            }
                        }
                    }
                    null
                }
            return filters
        }

    override fun onFocusChange(view: View, isFocus: Boolean) {
        if (!isFocus) {
            val dnsIP: String
            val settingKey: String
            if (view == binding.txtDns1) {
                dnsIP = binding.txtDns1.text.toString()
                settingKey = DataUtil.CUSTOM_DNS_IP_1
            } else {
                dnsIP = binding.txtDns2.text.toString()
                settingKey = DataUtil.CUSTOM_DNS_IP_2
            }
            val isValidIp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isNumericAddress(dnsIP)
            } else {
                @Suppress("DEPRECATION")
                Patterns.IP_ADDRESS.matcher(dnsIP).matches()
            }
            if (isValidIp) {
                dataUtil.setStringSetting(settingKey, dnsIP)
                if (settingKey == DataUtil.CUSTOM_DNS_IP_1) {
                    val editor = prefs.edit()
                    editor.putString(OscPrefKey.DNS_CUSTOM_ADDRESS.toString(), dnsIP)
                    editor.apply()
                } else if (settingKey == DataUtil.CUSTOM_DNS_IP_2) {
                    val editor = prefs.edit()
                    editor.putString(OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(), dnsIP)
                    editor.apply()
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) {
            if (dataUtil.connectionCacheExpires == null) {
                binding.lnClearCache.visibility = View.GONE
            } else {
                binding.lnClearCache.visibility = View.VISIBLE
                binding.txtCacheExpire.text =
                    dataUtil.connectionCacheExpires?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(
                            it
                        )
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (App.isImportToOpenVPN) {
            binding.lnBlockAdsWrap.visibility = View.GONE
            binding.lnDnsWrap.visibility = View.GONE
        } else {
            binding.lnBlockAdsWrap.visibility = View.VISIBLE
            binding.lnDnsWrap.visibility = View.VISIBLE
        }
    }

    private fun clearListServerCache(showToast: Boolean) {
        val activity = activity as MainActivity?
        if (dataUtil.clearConnectionCache()) {
            if (showToast) {
                Toast.makeText(
                    activity,
                    resources.getString(R.string.setting_clear_cache_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
            binding.lnClearCache.visibility = View.GONE
            sendClearCache()
        } else if (showToast) {
            Toast.makeText(
                activity,
                resources.getString(R.string.setting_clear_cache_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onClick(view: View) {
        if (view == binding.lnAutoProtocol) {
            showAutoProtocolPicker()
            return
        }
        if (view == binding.lnAutoTimeout) {
            showAutoTimeoutPicker()
            return
        }
        if (view == binding.lnSoftetherMaxConnections) {
            showSoftEtherMaxConnectionsPicker()
            return
        }
        when(view) {
            binding.btnClearCache -> clearListServerCache(true)
            binding.lnBlockAds -> binding.swBlockAds.isChecked = !binding.swBlockAds.isChecked
            binding.lnUdp ->  binding.swUdp.isChecked = !binding.swUdp.isChecked
            binding.lnDns -> binding.swDns.isChecked = !binding.swDns.isChecked
            binding.lnDomain -> binding.swDomain.isChecked = !binding.swDomain.isChecked
            binding.lnNotifySpeed -> binding.swNotifySpeed.isChecked = !binding.swNotifySpeed.isChecked
        }
    }

    /** §24: Default VPN Protocol picker for Auto Mode. */
    private fun showAutoProtocolPicker() {
        val protocols = vn.unlimit.vpngate.automode.AutoModeProtocol.entries
        val labels = protocols.map { it.id.lowercase().replace('_', ' ') }.toTypedArray()
        val current = vn.unlimit.vpngate.automode.AutoModeProtocol.fromId(
            dataUtil.getStringSetting(DataUtil.SETTING_DEFAULT_VPN_PROTOCOL, null)
        )
        val checked = protocols.indexOf(current)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_auto_protocol_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dataUtil.setStringSetting(
                    DataUtil.SETTING_DEFAULT_VPN_PROTOCOL,
                    protocols[which].id
                )
                updateAutoProtocolLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

      private fun updateAutoProtocolLabel() {
        val current = vn.unlimit.vpngate.automode.AutoModeProtocol.fromId(
            dataUtil.getStringSetting(DataUtil.SETTING_DEFAULT_VPN_PROTOCOL, null)
        )
        binding.txtAutoProtocolValue.text = current.id.lowercase().replace('_', ' ')
    }

    /** §3 §4 §5 §13 Auto Mode per-server connection timeout picker (5–60 seconds). */
    private fun showAutoTimeoutPicker() {
        val current = dataUtil.getAutoModeTimeoutSeconds()
        val values = (5..60).toList().toTypedArray()
        val labels = values.map { "$it seconds" }.toTypedArray()
        val checked = values.indexOf(current)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_auto_timeout_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dataUtil.setAutoModeTimeoutSeconds(values[which])
                updateAutoTimeoutLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAutoTimeoutLabel() {
        val seconds = dataUtil.getAutoModeTimeoutSeconds()
        binding.txtAutoTimeoutValue.text = "$seconds seconds"
    }

    /** SoftEther client concurrent TCP connections picker (1–8, native MAX_SE_CONNECTIONS). */
    private fun showSoftEtherMaxConnectionsPicker() {
        val values = intArrayOf(1, 2, 3, 4, 5, 6, 8)
        val current = dataUtil.getSoftEtherMaxConnections()
        val labels = values.map { getString(R.string.setting_softether_max_connections_value, it) }.toTypedArray()
        val checked = values.indexOf(current).let { if (it < 0) values.indexOf(4) else it }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_softether_max_connections_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dataUtil.setSoftEtherMaxConnections(values[which])
                updateSoftEtherMaxConnectionsLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSoftEtherMaxConnectionsLabel() {
        val current = dataUtil.getSoftEtherMaxConnections()
        binding.txtSoftetherMaxConnectionsValue.text =
            getString(R.string.setting_softether_max_connections_value, current)
    }

    private fun hideKeyBroad() {
        val imm = mContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(
            binding.txtDns1.windowToken,
            InputMethodManager.HIDE_IMPLICIT_ONLY
        )
    }

    override fun onCheckedChanged(switchCompat: CompoundButton, isChecked: Boolean) {
        if (switchCompat == binding.swUdp) {
            dataUtil.setBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, isChecked)
            clearListServerCache(false)
            return
        }
        val editor = prefs.edit()
        if (switchCompat == binding.swDns) {
            dataUtil.setBooleanSetting(DataUtil.USE_CUSTOM_DNS, isChecked)
            if (isChecked) {
                if (binding.swBlockAds.isChecked) {
                    // Turn off Block Ads if custom DNS is enabled
                    binding.swBlockAds.isChecked = false
                    dataUtil.setBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false)
                }
                binding.lnDnsIp.visibility = View.VISIBLE
                binding.txtDns1.requestFocus()
                val imm =
                    mContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.txtDns1, InputMethodManager.SHOW_IMPLICIT)
                editor.putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), true)
                editor.putString(OscPrefKey.DNS_CUSTOM_ADDRESS.toString(), dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8"))
                editor.putString(OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(), dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, "8.8.4.4"))
            } else {
                hideKeyBroad()
                binding.lnDnsIp.visibility = View.GONE
                editor.remove(OscPrefKey.DNS_CUSTOM_ADDRESS.toString())
                editor.remove(OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString())
                editor.putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), false)
            }
            editor.apply()
            return
        }
        if (switchCompat == binding.swDomain) {
            dataUtil.setBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, isChecked)
            return
        }
        if (switchCompat == binding.swNotifySpeed) {
            Toast.makeText(
                context,
                getText(R.string.setting_apply_on_next_connection_time),
                Toast.LENGTH_SHORT
            ).show()
            dataUtil.setBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, isChecked)
            return
        }
        //Only save setting in pro version
        if (switchCompat == binding.swBlockAds) {
            Toast.makeText(
                context,
                getText(R.string.setting_apply_on_next_connection_time),
                Toast.LENGTH_SHORT
            ).show()
            dataUtil.setBooleanSetting(DataUtil.SETTING_BLOCK_ADS, isChecked)
            if (isChecked && binding.swDns.isChecked) binding.swDns.isChecked = false
            if (isChecked) {
                editor.putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), true)
                editor.putString(
                    OscPrefKey.DNS_CUSTOM_ADDRESS.toString(),
                    AppConfig.getString("vpn_dns_block_ads_primary")
                )
                editor.putString(
                    OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(),
                    AppConfig.getString("vpn_dns_block_ads_alternative")
                )
            } else {
                editor.putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), false)
                editor.remove(OscPrefKey.DNS_CUSTOM_ADDRESS.toString())
                editor.remove(OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString())
            }
            editor.apply()
        }
    }

    private fun sendClearCache() {
        try {
            val intent = Intent(BaseProvider.ACTION.ACTION_CLEAR_CACHE)
            mContext.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
        dataUtil.setIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
    }
}
