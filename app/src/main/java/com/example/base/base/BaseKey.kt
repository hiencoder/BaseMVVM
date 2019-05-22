package com.example.base.base

import android.os.Bundle
import androidx.fragment.app.Fragment

/**
 * Created by Zhuinden on 2018. 08. 20.
 */

abstract class BaseKey : Key {
    override fun newFragment(): Fragment = createFragment().also { fragment ->
        fragment.arguments = (fragment.arguments ?: Bundle()).also { bundle ->
            bundle.putParcelable(BaseFragment.KEY_TAG, this)
        }
    }

    protected abstract fun createFragment(): Fragment

    override val fragmentTag: String
        get() = toString()
}
