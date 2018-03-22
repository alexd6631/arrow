package arrow.instances

import arrow.Kind
import arrow.core.Either
import arrow.core.Eval
import arrow.data.*
import arrow.instance
import arrow.typeclasses.*

@instance(Ior::class)
interface IorFunctorInstance<L> : Functor<IorPartialOf<L>> {
    override fun <A, B> map(fa: IorOf<L, A>, f: (A) -> B): Ior<L, B> = fa.fix().map(f)
}

@instance(Ior::class)
interface IorApplicativeInstance<L> : IorFunctorInstance<L>, Applicative<IorPartialOf<L>> {

    fun SL(): Semigroup<L>

    override fun <A> pure(a: A): Ior<L, A> = Ior.Right(a)

    override fun <A, B> map(fa: IorOf<L, A>, f: (A) -> B): Ior<L, B> = fa.fix().map(f)

    override fun <A, B> ap(fa: IorOf<L, A>, ff: IorOf<L, (A) -> B>): Ior<L, B> =
            fa.fix().ap(SL(), ff)
}

@instance(Ior::class)
interface IorMonadInstance<L> : IorApplicativeInstance<L>, Monad<IorPartialOf<L>> {

    override fun <A, B> map(fa: IorOf<L, A>, f: (A) -> B): Ior<L, B> = fa.fix().map(f)

    override fun <A, B> flatMap(fa: IorOf<L, A>, f: (A) -> IorOf<L, B>): Ior<L, B> =
            fa.fix().flatMap(SL(), { f(it).fix() })

    override fun <A, B> ap(fa: IorOf<L, A>, ff: IorOf<L, (A) -> B>): Ior<L, B> =
            fa.fix().ap(SL(), ff)

    override fun <A, B> tailRecM(a: A, f: (A) -> IorOf<L, Either<A, B>>): Ior<L, B> =
            Ior.tailRecM(a, f, SL())

}

@instance(Ior::class)
interface IorFoldableInstance<L> : Foldable<IorPartialOf<L>> {

    override fun <B, C> foldLeft(fa: Kind<Kind<ForIor, L>, B>, b: C, f: (C, B) -> C): C = fa.fix().foldLeft(b, f)

    override fun <B, C> foldRight(fa: Kind<Kind<ForIor, L>, B>, lb: Eval<C>, f: (B, Eval<C>) -> Eval<C>): Eval<C> =
            fa.fix().foldRight(lb, f)

}

@instance(Ior::class)
interface IorTraverseInstance<L> : IorFoldableInstance<L>, Traverse<IorPartialOf<L>> {

    override fun <G, B, C> Applicative<G>.traverse(fa: IorOf<L, B>, f: (B) -> Kind<G, C>): Kind<G, Ior<L, C>> =
            fa.fix().traverse(f, this)

}

@instance(Ior::class)
interface IorEqInstance<L, R> : Eq<Ior<L, R>> {

    fun EQL(): Eq<L>

    fun EQR(): Eq<R>

    override fun Ior<L, R>.eqv(b: Ior<L, R>): Boolean = when (this) {
        is Ior.Left -> when (b) {
            is Ior.Both -> false
            is Ior.Right -> false
            is Ior.Left -> EQL().run { value.eqv(b.value) }
        }
        is Ior.Both -> when (b) {
            is Ior.Left -> false
            is Ior.Both -> EQL().run { leftValue.eqv(b.leftValue) } && EQR().run { rightValue.eqv(b.rightValue) }
            is Ior.Right -> false
        }
        is Ior.Right -> when (b) {
            is Ior.Left -> false
            is Ior.Both -> false
            is Ior.Right -> EQR().run { value.eqv(b.value) }
        }

    }
}

@instance(Ior::class)
interface IorShowInstance<L, R> : Show<Ior<L, R>> {
    override fun show(a: Ior<L, R>): String =
            a.toString()
}
