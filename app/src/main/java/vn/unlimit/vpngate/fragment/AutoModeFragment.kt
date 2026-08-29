package vn.unlimit.vpngate.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.automode.AutoModeController
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.databinding.FragmentAutoModeBinding
import vn.unlimit.vpngate.viewmodels.AutoModeViewModel

/**
 * Auto Mode screen (§2/§3): a single dynamic button across the four
 * states plus live server info and attempt progress. UI only renders
 * [AutoModeState]; all logic lives in the engine (§20).
 */
class AutoModeFragment : Fragment() {

    private var _binding: FragmentAutoModeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AutoModeViewModel by activityViewModels()

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
        binding.btnAutoToggle.setOnClickListener { viewModel.onButtonPressed() }
        viewModel.state.observe(viewLifecycleOwner) { render(it) }
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
                binding.txtAutoState.setText(R.string.auto_mode_state_error)
                binding.txtAutoProgress.visibility = View.GONE
                binding.lnAutoServerInfo.visibility = View.GONE
                binding.txtAutoError.visibility = View.VISIBLE
                binding.txtAutoError.setText(
                    if (state.message == AutoModeController.ERROR_NO_SERVER)
                        R.string.auto_mode_error_no_server
                    else
                        R.string.auto_mode_error_generic
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
