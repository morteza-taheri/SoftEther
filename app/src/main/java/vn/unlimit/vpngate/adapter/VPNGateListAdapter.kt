package vn.unlimit.vpngate.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Created by hoangnd on 1/29/2018.
 */
class VPNGateListAdapter(private val mContext: Context) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val _list = VPNGateConnectionList()
    private val layoutInflater: LayoutInflater = LayoutInflater.from(mContext)
    private var onItemClickListener: OnItemClickListener? = null
    private var onItemLongClickListener: OnItemLongClickListener? = null
    private var onScrollListener: OnScrollListener? = null
    private var lastPosition = 0

    @SuppressLint("NotifyDataSetChanged")
    fun initialize(vpnGateConnectionList: VPNGateConnectionList?) {
        try {
            Log.d(TAG, "initialize with: ${vpnGateConnectionList?.size()} items")
            _list.clear()
            if (vpnGateConnectionList != null) {
                _list.addAll(vpnGateConnectionList)
            }
            notifyDataSetChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setOnItemClickListener(inOnItemClickListener: OnItemClickListener?) {
        this.onItemClickListener = inOnItemClickListener
    }

    fun setOnItemLongClickListener(inOnItemLongPressListener: OnItemLongClickListener?) {
        this.onItemLongClickListener = inOnItemLongPressListener
    }

    fun setOnScrollListener(inOnScrollListener: OnScrollListener?) {
        this.onScrollListener = inOnScrollListener
    }

    // Ads have been removed. Retained as no-op for API compatibility.
    fun setHasAds(hasAds: Boolean) {
        // Ads disabled.
    }

    // Ads have been removed. Retained as no-op for API compatibility.
    fun setAdUnitId(adUnitId: String?) {
        // Ads disabled.
    }

    override fun onBindViewHolder(
        viewHolder: RecyclerView.ViewHolder,
        @SuppressLint("RecyclerView") position: Int
    ) {
        if (onScrollListener != null) {
            if (position > lastPosition || position == 0) {
                onScrollListener!!.onScrollDown()
            } else if (position < lastPosition) {
                onScrollListener!!.onScrollUp()
            }
        }
        (viewHolder as VHTypeVPN).bindViewHolder(position)
        lastPosition = position
    }

    override fun getItemCount(): Int {
        return _list.size()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = layoutInflater.inflate(R.layout.item_vpn, parent, false)
        return VHTypeVPN(view)
    }

    private inner class VHTypeVPN(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnClickListener, OnLongClickListener {
        var imgFlag: ImageView = itemView.findViewById(R.id.img_flag)
        var txtCountry: TextView = itemView.findViewById(R.id.txt_country)
        var txtIp: TextView = itemView.findViewById(R.id.txt_ip)
        var txtHostname: TextView = itemView.findViewById(R.id.txt_hostname)
        var txtScore: TextView = itemView.findViewById(R.id.txt_score)
        var txtUptime: TextView = itemView.findViewById(R.id.txt_uptime)
        var txtSpeed: TextView = itemView.findViewById(R.id.txt_speed)
        var txtPing: TextView = itemView.findViewById(R.id.txt_ping)
        var txtSession: TextView = itemView.findViewById(R.id.txt_session)
        var txtOwner: TextView = itemView.findViewById(R.id.txt_owner)
        var lnTCP: View = itemView.findViewById(R.id.ln_tcp)
        var txtTCP: TextView = itemView.findViewById(R.id.txt_tcp_port)
        var lnUDP: View = itemView.findViewById(R.id.ln_udp)
        var txtUDP: TextView = itemView.findViewById(R.id.txt_udp_port)
        var lnL2TP: View = itemView.findViewById(R.id.ln_l2tp)
        var lnSSTP: View = itemView.findViewById(R.id.ln_sstp)

        init {
            itemView.setOnLongClickListener(this)
            itemView.setOnClickListener(this)
        }