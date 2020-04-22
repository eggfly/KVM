package kvm.core

import kotlin.math.abs
import kotlin.math.cos

const val eps = 1E-10 // "good enough", could be 10^-15

/**
 * https://www.kotlincn.net/docs/reference/functions.html
 */
tailrec fun findFixPoint(x: Double = 1.0): Double =
    if (abs(x - cos(x)) < eps) x else findFixPoint(cos(x))
