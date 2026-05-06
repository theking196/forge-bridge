package com.forge.bridge.ui.providers

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.databinding.DialogConnectBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ConnectDialogFragment : DialogFragment() {

    private var _binding: DialogConnectBinding? = null
    private val binding get() = _binding!!

    private val db get() = (requireActivity().application as ForgeBridgeApp).container.database
    private val vault get() = (requireActivity().application as ForgeBridgeApp).container.vaultManager

    private val providerId get() = requireArguments().getString(ARG_PROVIDER_ID)!!
    private val providerName get() = requireArguments().getString(ARG_PROVIDER_NAME)!!
    private val isOllama get() = requireArguments().getBoolean(ARG_IS_OLLAMA, false)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogConnectBinding.inflate(LayoutInflater.from(requireContext()))

        binding.tvTitle.text = "Connect ${providerName}"

        if (isOllama) {
            // Ollama can use local or remote URL
            binding.layoutApiKey.hint = "Ollama URL (optional)"
            binding.layoutApiKey.helperText = "Leave empty for localhost:11434, or enter remote URL"
            // Show the Ollama hint with instructions
            binding.tvOllamaHint.visibility = android.view.View.VISIBLE
            binding.tvOllamaHint.text =
                "Ollama can run locally or on a remote server.\n\n" +
                "• Local: Leave empty (assumes localhost:11434)\n" +
                "• Remote: Enter URL like http://192.168.1.x:11434\n\n" +
                "Make sure Ollama is running:\n  ollama serve"
            
            // Pre-fill with existing URL if any
            val existingUrl = vault.getApiKey(providerId)
            if (!existingUrl.isNullOrBlank()) {
                binding.etApiKey.setText(existingUrl)
            }
        } else {
            binding.layoutApiKey.hint = "API Key for $providerName"
            binding.tvOllamaHint.visibility = android.view.View.GONE
            // Allow submitting via keyboard done action
            binding.etApiKey.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setPositiveButton("Connect", null) // Set later to prevent auto-dismiss on error
            .setNegativeButton("Cancel") { _, _ -> dismiss() }
            .create()
            .also { dialog ->
                // Override positive button to handle validation without auto-dismiss
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener { save() }
                }
            }
    }

    private fun save() {
        val inputText = binding.etApiKey.text?.toString()?.trim() ?: ""

        if (!isOllama && inputText.isEmpty()) {
            binding.layoutApiKey.error = "API key is required"
            return
        }
        
        // For Ollama, validate URL format if provided
        if (isOllama && inputText.isNotEmpty()) {
            if (!inputText.startsWith("http://") && !inputText.startsWith("https://")) {
                binding.layoutApiKey.error = "URL must start with http:// or https://"
                return
            }
        }
        
        binding.layoutApiKey.error = null

        val setAsDefault = binding.checkDefault.isChecked

        // For Ollama, store the URL as the "API key"
        vault.storeApiKey(providerId, inputText)
        db.updateProviderStatus(providerId, "connected", System.currentTimeMillis())
        if (setAsDefault) db.setDefaultProvider(providerId)

        val message = if (isOllama) {
            if (inputText.isEmpty()) "$providerName connected (localhost:11434)"
            else "$providerName connected ($inputText)"
        } else {
            "$providerName connected"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        (activity as? ProviderListActivity)?.onProviderConnected()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_PROVIDER_ID = "provider_id"
        private const val ARG_PROVIDER_NAME = "provider_name"
        private const val ARG_IS_OLLAMA = "is_ollama"

        fun newInstance(providerId: String, providerName: String, isOllama: Boolean) =
            ConnectDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROVIDER_ID, providerId)
                    putString(ARG_PROVIDER_NAME, providerName)
                    putBoolean(ARG_IS_OLLAMA, isOllama)
                }
            }
    }
}
