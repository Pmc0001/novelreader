package com.example.novelreader

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment

class UrlInputDialog : DialogFragment() {

    private var onSubmitUrl: ((url: String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private lateinit var etUrl: EditText
    private lateinit var btnLoad: TextView
    private lateinit var btnCancel: TextView
    private lateinit var btnPaste: TextView
    private lateinit var btnCopy: TextView
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

        // 键盘「前往」直接加载；重新输入时清除错误提示
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                btnLoad.performClick()
                true
            } else {
                false
            }
        }
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrBlank() && tvStatus.visibility == View.VISIBLE) {
                    tvStatus.visibility = View.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

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
