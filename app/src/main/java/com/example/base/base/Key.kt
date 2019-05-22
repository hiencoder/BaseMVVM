package com.example.base.base

import android.os.Parcelable
import androidx.fragment.app.Fragment

/**
 * Created by Zhuinden on 2017. 01. 12..
 * Define Key to put data
 */

interface Key : Parcelable {
    val fragmentTag: String

    fun newFragment(): Fragment

    fun getIdentify(): String
}
