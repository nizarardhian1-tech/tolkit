package com.mondns.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.mondns.app.databinding.FragmentNativeToolsBinding

class NativeToolsFragment : Fragment() {
    private var _binding: FragmentNativeToolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNativeToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardSoInspector.setOnClickListener {
            findNavController().navigate(R.id.action_nativeTools_to_soInspector)
        }
        binding.cardCrashAnalyzer.setOnClickListener {
            findNavController().navigate(R.id.action_nativeTools_to_crashAnalyzer)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
