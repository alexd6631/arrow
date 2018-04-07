package arrow

import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.CLASS

@Retention(SOURCE)
@Target(CLASS)
annotation class optic(val targets: Array<OpticsTarget> = [OpticsTarget.ISO, OpticsTarget.LENS, OpticsTarget.PRISM, OpticsTarget.OPTIONAL, OpticsTarget.DSL])

enum class OpticsTarget {
  ISO, LENS, PRISM, OPTIONAL, DSL
}
