package com.marinov.colegioetapa

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment

class MoreFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_more, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners(view)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<View>(R.id.option_detalhes_provas).setOnClickListener {
            (activity as MainActivity).openCustomFragment(DetalhesProvas())
        }

        view.findViewById<View>(R.id.option_ead_online).setOnClickListener {
            (activity as MainActivity).openCustomFragment(EADOnlineFragment())
        }

        view.findViewById<View>(R.id.navigation_material).setOnClickListener {
            (activity as MainActivity).openCustomFragment(MaterialFragment())
        }

        view.findViewById<View>(R.id.option_provas_gabaritos).setOnClickListener {
            (activity as MainActivity).openCustomFragment(ProvasGabaritos())
        }
    }
}