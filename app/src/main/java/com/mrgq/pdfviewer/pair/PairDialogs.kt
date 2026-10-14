package com.mrgq.pdfviewer.pair

import android.content.DialogInterface
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mrgq.pdfviewer.R

/**
 * 두 대로 펼쳐 보기의 대화상자 — 첫 화면 📖 단추와 악보 화면 보기 메뉴가 함께 쓴다 (사용자 요청 2026-10-14).
 *
 * - 짝이 없으면: **주 기기**(왼쪽 · 오른쪽 자리) · **보조 기기** 고르기
 * - 주 기기: 기다리는 동안 화면에 **번호**([PairSession.code]) — 붙으면 닫힌다([onConnected])
 * - 보조 기기: 찾은 주 기기의 번호와 미끼를 섞은 번호 [PairCodes.CHOICES] 개 중 주 기기 화면의 것을 고른다([PairCodeChoices])
 * - 짝이 있으면: 지금 상태 · 짝 풀기
 *
 * [beforeStart] 는 기다리기 · 붙기 바로 전(화면이 짝 알림을 받게 · 휴대폰 세로), [onChanged] 는 짝 상태가 바뀐 뒤(단추 색),
 * [onStopped] 는 이 기기에서 짝을 풀었을 때(혼자 보기로)
 */
class PairDialogs(
    private val activity: AppCompatActivity,
    private val beforeStart: () -> Unit,
    private val onChanged: () -> Unit = {},
    private val onStopped: () -> Unit = {},
) {
    private var codeDialog: AlertDialog? = null

    fun show() {
        when {
            PairSession.role == PairSession.Role.MAIN && !PairSession.connected -> showCode()
            PairSession.isActive -> showStatus()
            else -> showChoose()
        }
    }

    /** 짝이 맺어졌다 — 주 기기의 번호 화면을 닫는다 */
    fun onConnected() {
        codeDialog?.dismiss()
        codeDialog = null
    }

    private fun string(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(activity, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun sideName(side: PairSide?): String =
        string(if (side == PairSide.RIGHT) R.string.viewer_pair_side_right else R.string.viewer_pair_side_left)

    private fun roleName(): String =
        string(if (PairSession.role == PairSession.Role.MAIN) R.string.viewer_pair_role_main else R.string.viewer_pair_role_second)

    private fun showChoose() {
        val options = arrayOf(string(R.string.viewer_pair_host_left), string(R.string.viewer_pair_host_right), string(R.string.viewer_pair_find))
        AlertDialog.Builder(activity)
            .setTitle(R.string.viewer_menu_pair)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> host(PairSide.LEFT)
                    1 -> host(PairSide.RIGHT)
                    else -> find()
                }
            }
            .setNegativeButton(R.string.viewer_close, null)
            .show()
    }

    private fun showStatus() {
        val state = string(
            when {
                PairSession.connected -> R.string.viewer_menu_pair_connected
                PairSession.role == PairSession.Role.MAIN -> R.string.viewer_menu_pair_waiting
                else -> R.string.viewer_menu_pair_reconnecting
            },
            sideName(PairSession.side),
        )
        val peer = PairSession.peerName?.let { "\n" + string(R.string.viewer_pair_peer, it) }.orEmpty()
        AlertDialog.Builder(activity)
            .setTitle(R.string.viewer_menu_pair)
            .setMessage(string(R.string.viewer_pair_status, roleName(), sideName(PairSession.side), state) + peer)
            .setPositiveButton(R.string.viewer_pair_stop) { _, _ -> stop() }
            .setNegativeButton(R.string.viewer_close, null)
            .show()
    }

    private fun stop() {
        PairSession.stop()
        onStopped()
        onChanged()
    }

    private fun host(side: PairSide) {
        beforeStart()
        if (!PairSession.startHosting(side)) {
            toast(string(R.string.viewer_pair_host_failed))
            onChanged()
            return
        }
        onChanged()
        showCode()
    }

    /** 주 기기: 기다리는 동안 번호를 크게 — 닫아도 계속 기다린다(📖 단추 · 보기 메뉴로 다시 본다) */
    private fun showCode() {
        val code = PairSession.code ?: return showStatus()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(TextView(activity).apply {
                text = code
                textSize = 72f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
            })
            addView(TextView(activity).apply {
                text = string(R.string.viewer_pair_code_message)
                textSize = 15f
                setPadding(0, dp(8), 0, 0)
            })
        }
        codeDialog?.dismiss()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(string(R.string.viewer_pair_code_title, sideName(PairSession.side)))
            .setView(content)
            .setPositiveButton(R.string.viewer_close, null)
            .setNegativeButton(R.string.viewer_pair_stop_waiting) { _, _ -> stop() }
            .create()
        dialog.setOnDismissListener { if (codeDialog === dialog) codeDialog = null }
        codeDialog = dialog
        dialog.show()
    }

    /** 보조 기기: 주 기기를 찾으며 번호 칸을 보여 준다 — 진짜 번호를 고르면 그 기기에 붙는다(자리는 그 반대) */
    private fun find() {
        val choices = PairCodeChoices()
        val hosts = mutableMapOf<String, PairDiscovery.Host>()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(row)
        }
        lateinit var dialog: AlertDialog

        fun pick(label: String) {
            val host = hosts[label]
            if (host == null) {
                toast(string(R.string.viewer_pair_wrong_code), long = true)
                return
            }
            PairSession.stopFinding()
            dialog.dismiss()
            beforeStart()
            PairSession.join(host)
            toast(string(R.string.viewer_pair_connecting))
            onChanged()
        }

        fun render() {
            row.removeAllViews()
            for (label in choices.options) {
                row.addView(Button(activity).apply {
                    text = label
                    isAllCaps = false
                    // 번호는 크게, 번호 없는 옛 판 기기 이름은 작게
                    textSize = if (label.length <= 2) 32f else 15f
                    setOnClickListener { pick(label) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(6), dp(4), dp(6), dp(4))
                })
            }
            row.getChildAt(0)?.requestFocus()
        }

        fun search() {
            dialog.setTitle(if (hosts.isEmpty()) R.string.viewer_pair_finding else R.string.viewer_pair_pick_code)
            PairSession.find(
                onFound = { host ->
                    val label = host.code ?: host.name
                    if (!hosts.containsKey(label)) {
                        hosts[label] = host
                        if (choices.add(label)) render()
                        if (dialog.isShowing) dialog.setTitle(R.string.viewer_pair_pick_code)
                    }
                },
                onDone = {
                    if (hosts.isEmpty() && dialog.isShowing) {
                        dialog.setTitle(R.string.viewer_pair_none_found)
                        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.visibility = View.VISIBLE
                    }
                },
            )
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.viewer_pair_finding)
            .setView(content)
            .setNeutralButton(R.string.viewer_pair_find_again, null)
            .setNegativeButton(R.string.cancel) { _, _ -> PairSession.stopFinding() }
            .setOnCancelListener { PairSession.stopFinding() }
            .create()
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.apply {
            visibility = View.GONE
            // 기본 동작(닫기) 대신 — 다시 15초 듣는다
            setOnClickListener {
                visibility = View.GONE
                search()
            }
        }
        search()
    }
}
