package vn.unlimit.vpngate.dialog

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.databinding.LayoutDebugInfoBinding
import vn.unlimit.vpngate.repository.VpnServerRepository

/**
 * §33 developer diagnostics: per-server provenance dump (per-source
 * fields, conflicts, confidence) with a raw HTML/API toggle.
 */
class DebugInfoBottomSheetDialog : BottomSheetDialogFragment() {
    private var _binding: LayoutDebugInfoBinding? = null
    private val binding get() = _binding!!

    private var title: String = ""
    private var payload: VpnServerRepository.DebugPayload? = null
    private var currentContent: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireActivity())
        dialog.setOnShowListener { localDialog ->
            val d = localDialog as BottomSheetDialog
            val bottomSheet =
                d.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)!!
            BottomSheetBehavior.from(bottomSheet).state =
                BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = LayoutDebugInfoBinding.inflate(layoutInflater)

        binding.txtDebugTitle.text = title.ifEmpty {
            getString(R.string.collector_debug_title)
        }

        val localPayload = payload
        if (localPayload == null) {
            binding.btnShowRawHtml.visibility = View.GONE
            binding.btnShowRawApi.visibility = View.GONE
            binding.btnCopyDebug.visibility = View.GONE
            binding.txtDebugContent.text =
                getString(R.string.collector_debug_unavailable)
            return binding.root
        }

        currentContent = localPayload.dump
        binding.txtDebugContent.text = currentContent

        binding.btnShowDump.setOnClickListener {
            currentContent = localPayload.dump
            binding.txtDebugContent.text = currentContent
        }

        binding.btnShowRawHtml.setOnClickListener {
            val raw = localPayload.rawHtml
            if (raw == null) {
                Toast.makeText(
                    context,
                    R.string.collector_debug_raw_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                currentContent = raw
                binding.txtDebugContent.text = currentContent
            }
        }

        binding.btnShowRawApi.setOnClickListener {
            val raw = localPayload.rawApi
            if (raw == null) {
                Toast.makeText(
                    context,
                    R.string.collector_debug_raw_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                currentContent = raw
                binding.txtDebugContent.text = currentContent
            }
        }

        binding.btnCopyDebug.setOnClickListener {
            val clipboard = requireActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("collector-debug", currentContent)
            )
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DebugInfoBottomSheet"

        @JvmStatic
        fun newInstance(
            title: String,
            payload: VpnServerRepository.DebugPayload?,
        ): DebugInfoBottomSheetDialog = DebugInfoBottomSheetDialog().apply {
            this.title = title
            this.payload = payload
        }
    }
}
