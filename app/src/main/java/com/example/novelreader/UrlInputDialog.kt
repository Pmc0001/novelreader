package com.example.novelreader

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment

class UrlInputDialog : DialogFragment() {

    private var onSubmitUrl: ((url: String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private lateinit var etUrl: EditText
    private lateinit var btnLoad: Button
    private lateinit var btnCancel: Button
    private lateinit var btnPaste: ImageButton
    private lateinit var btnCopy: ImageButton
    private lateinit var tvStatus: TextView

    private var presetUrl: String = ""
    private var autoLoad: Boolean = false

    fun setOnLoadListener(
        onSubmit: (url: String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        onSubmitUrl = onSubmit
        this.onCancel = onCancel
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.layout_url_dialog, null)

        etUrl = view.findViewById(R.id.et_url)
        btnLoad = view.findViewById(R.id.btn_load)
        btnCancel = view.findViewById(R.id.btn_cancel)
        tvStatus = view.findViewById(R.id.tv_status)

        if (presetUrl.isNotBlank()) {
            etUrl.setText(presetUrl)
            etUrl.setSelection(etUrl.text.length)
        }

        btnLoad.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isBlank()) {
                tvStatus.text = "请输入URL"
                tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            var finalUrl = url
            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                finalUrl = "https://$finalUrl"
            }

            onSubmitUrl?.invoke(finalUrl)
            dismiss()
        }

        btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }

        btnPaste = view.findViewById(R.id.btn_paste)
        btnPaste.setOnClickListener {
            val clipboardText = ClipboardHelper.getClipboardText(requireContext())
            if (clipboardText.isNullOrBlank()) {
                Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
            } else {
                etUrl.setText(clipboardText.trim())
                etUrl.setSelection(etUrl.text.length)
            }
        }

        btnCopy = view.findViewById(R.id.btn_copy)
        btnCopy.setOnClickListener {
            val text = etUrl.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), "输入框为空", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("url", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        return dialog
    }

    companion object {
        const val TAG = "UrlInputDialog"

        fun newInstance(presetUrl: String = "", autoLoad: Boolean = false): UrlInputDialog {
            return UrlInputDialog().apply {
                this.presetUrl = presetUrl
                this.autoLoad = autoLoad
            }
        }
    }
}
