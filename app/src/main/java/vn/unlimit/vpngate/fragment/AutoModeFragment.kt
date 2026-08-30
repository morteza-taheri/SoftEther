package vn.unlimit.vpngate.fragment

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.automode.AutoModeController
import vn.unlimit.vpngate.automode.AutoModeLogStore
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.databinding.FragmentAutoModeBinding
import vn.unlimit.vpngate.viewmodels.AutoModeViewModel

/**
 * Auto Mode screen (§2/§3): a single dynamic button across the four
 * states plus live server info and attempt progress. UI only renders
 * [AutoModeState]; all logic lives in the engine (§20).
 *
 * Also hosts the live module log window (Copy/Clear) showing the real
 * output of the protocol module that Auto Mode is driving, and the
 * Try next server button.
 */
class AutoModeFragment : Fragment() {

    private var _binding: FragmentAutoModeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AutoModeViewModel by activityViewModels()

    /**
     * The OS VPN permission must be granted BEFORE Auto Mode starts —
     * the connection dialog cannot be shown from a background connect
     * attempt. Mirrors the manual flows (DetailActivity/StatusFragment):
     * launch the system dialog, start Auto Mode only after RESULT_OK.
     */
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onButtonPressed()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.auto_mode_error_vpn_permission,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAutoModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnAutoToggle.setOnClickListener { startAutoModeOrRequestPermission() }
        binding.btnAutoNextServer.setOnClickListener { viewModel.tryNextServer() }
        binding.btnAutoLogCopy.setOnClickListener { copyLog() }
        binding.btnAutoLogClear.setOnClickListener { AutoModeLogStore.clear() }
        viewModel.state.observe(viewLifecycleOwner) { render(it) }

        // Live module log window: re-render whenever the store changes
        // (module output + AUTO lines), filtered by the protocol in use.
        // Hidden entirely when Developer Mode is off (all logs suppressed).
        val developerMode = viewModel.dataUtil.getDeveloperMode()
        binding.root.findViewById<View>(R.id.ln_auto_mode_log_panel)?.visibility =
            if (developerMode) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoModeLogStore.lines.collect { renderLog() }
            }
        }
    }

    private fun copyLog() {
        val text = AutoModeLogStore.asText()
        if (text.isEmpty()) return
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AutoModeLog", text))
        Toast.makeText(requireContext(), R.string.auto_mode_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun renderLog() {
        val binding = _binding ?: return
        val protocol = when (val s = viewModel.state.value) {
            is AutoModeState.Connecting -> s.protocol
            is AutoModeState.Connected -> s.protocol
            else -> null
        }
        binding.txtAutoLog.text = AutoModeLogStore.filterFor(protocol)
            .joinToString("\n") { AutoModeLogStore.format(it) }
        binding.scrollAutoLog.post { binding.scrollAutoLog.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Pressing while Disconnected/Error starts a run; Connecting stops
     * it; Connected disconnects. Only the START path may need the VPN
     * permission dialog first (§12 stop must never be blocked by it).
     */
    private fun startAutoModeOrRequestPermission() {
        val state = viewModel.state.value
        val isStart = state is AutoModeState.Disconnected || state is AutoModeState.Error
        if (!isStart) {
            viewModel.onButtonPressed()
            return
        }
        try {
            val prepareIntent = VpnService.prepare(requireContext())
            if (prepareIntent == null) {
                viewModel.onButtonPressed()
            } else {
                vpnPermissionLauncher.launch(prepareIntent)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.auto_mode_error_vpn_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: AutoModeState) {
        when (state) {
            is AutoModeState.Disconnected -> {
                bindButton(
                    text = getString(R.string.auto_mode_state_disconnected),
                    color = R.color.colorAutoDisconnected,
                    progressVisible = false,
                )
                binding.btnAutoNextServer.visibility = View.GONE
                binding.txtAutoState.setText(R.string.auto_mode_state_disconnected)
                binding.txtAutoProgress.visibility = View.GONE
                binding.lnAutoServerInfo.visibility = View.GONE
                binding.txtAutoError.visibility = View.GONE
            }

            is AutoModeState.Connecting -> {
                bindButton(
                    text = getString(R.string.auto_mode_state_connecting),
                    color = R.color.colorAutoConnecting,
                    progressVisible = true,
                )
                binding.btnAutoNextServer.visibility = View.VISIBLE
                binding.txtAutoState.setText(R.string.auto_mode_state_connecting)
                binding.txtAutoProgress.visibility = View.VISIBLE
                binding.txtAutoProgress.text =
                    getString(R.string.auto_mode_attempt_progress, state.attempt, state.total)
                binding.lnAutoServerInfo.visibility = View.VISIBLE
                binding.txtAutoError.visibility = View.GONE
                bindServerInfo(state.hostname, state.ip, state.protocol, state.speed, state.ping)
                binding.txtAutoAttempt.text =
                    getString(R.string.auto_mode_attempt_n_of_m, state.attempt, state.total)
            }

            is AutoModeState.Connected -> {
                bindButton(
                    text = getString(R.string.auto_mode_state_connected),
                    color = R.color.colorAutoConnected,
                    progressVisible = false,
                )
                binding.btnAutoNextServer.visibility = View.GONE
                binding.txtAutoState.setText(R.string.auto_mode_state_connected)
                binding.txtAutoProgress.visibility = View.GONE
                binding.lnAutoServerInfo.visibility = View.VISIBLE
                binding.txtAutoError.visibility = View.GONE
                bindServerInfo(state.hostname, state.ip, state.protocol, state.speed, state.ping)
            }

            is AutoModeState.Error -> {
                bindButton(
                    text = getString(R.string.auto_mode_state_error),
                    color = R.color.colorAutoDisconnected,
                    progressVisible = false,
                )
                binding.btnAutoNextServer.visibility = View.GONE
                binding.txtAutoState.setText(R.string.auto_mode_state_error)
                binding.txtAutoProgress.visibility = View.GONE
                binding.lnAutoServerInfo.visibility = View.GONE
                binding.txtAutoError.visibility = View.VISIBLE
                binding.txtAutoError.setText(
                    when (state.message) {
                        AutoModeController.ERROR_NO_SERVER -> R.string.auto_mode_error_no_server
                        AutoModeController.ERROR_VPN_PERMISSION ->
                            R.string.auto_mode_error_vpn_permission
                        else -> R.string.auto_mode_error_generic
                    }
                )
            }
        }
    }

    private fun bindButton(text: String, color: Int, progressVisible: Boolean) {
        binding.btnAutoToggle.text = text
        binding.btnAutoToggle.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), color)
        binding.progressAuto.visibility = if (progressVisible) View.VISIBLE else View.GONE
    }

    private fun bindServerInfo(
        hostname: String?,
        ip: String?,
        protocol: vn.unlimit.vpngate.automode.AutoModeProtocol,
        speed: Long,
        ping: Int,
    ) {
        binding.txtAutoServer.text = getString(R.string.auto_mode_server_name, hostname ?: "-")
        binding.txtAutoIp.text = getString(R.string.auto_mode_server_ip, ip ?: "-")
        binding.txtAutoProtocol.text =
            getString(R.string.auto_mode_server_protocol, protocol.id.lowercase().replace('_', ' '))
        binding.txtAutoSpeed.text =
            getString(R.string.auto_mode_server_speed, speed / 1_000_000)
        binding.txtAutoPing.text = getString(R.string.auto_mode_server_ping, ping)
    }
}
