package tofu.optics

import alleycats.Pure
import cats.instances.either._
import cats.instances.option._
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.{Applicative, Functor, Monoid}
import tofu.optics.data.Identity

/** aka Optional
  * S may or may not contain single element of A
  * which may be set to B and change whole type to T*/
trait PProperty[-S, +T, +A, -B] extends PItems[S, T, A, B] with PDowncast[S, T, A, B] {
  def set(s: S, b: B): T
  def narrow(s: S): Either[T, A]

  def traverse[F[+_]](s: S)(f: A => F[B])(implicit F: Applicative[F]): F[T] =
    narrow(s).traverse(f).map(_.fold(t => t, set(s, _)))

  def traject[F[+_]](s: S)(fab: A => F[B])(implicit FP: Pure[F], F: Functor[F]): F[T] =
    narrow(s).fold[F[T]](FP.pure, a => fab(a).map(set(s, _)))

  def downcast(s: S): Option[A] = narrow(s).toOption

  override def foldMap[X: Monoid](a: S)(f: A => X): X = downcast(a).foldMap(f)
}

object Property extends MonoOpticCompanion(PProperty) {
  def apply[A] = new PropertyApplied[A](true)

  class PropertyApplied[A](private val dummy: Boolean) extends AnyVal {
    def apply[B](fgetOpt: A => Option[B])(fset: (A, B) => A): Property[A, B] = new DownCasting[A, B] {
      def getOpt(s: A): Option[B] = fgetOpt(s)
      def set(s: A, b: B): A      = fset(s, b)
    }
  }

  trait DownCasting[S, A] extends Property[S, A] {
    def getOpt(s: S): Option[A]
    def narrow(s: S): Either[S, A]         = getOpt(s).toRight(s)
    override def downcast(s: S): Option[A] = getOpt(s)
  }

  def mapItem[K, V](k: K): Property[Map[K, V], V] = new DownCasting[Map[K, V], V] {
    def set(s: Map[K, V], b: V): Map[K, V] = s.updated(k, b)
    def getOpt(s: Map[K, V]): Option[V]    = s.get(k)
  }

  def vecItem[A](i: Int): Property[Vector[A], A] = new DownCasting[Vector[A], A] {
    def getOpt(s: Vector[A]): Option[A]    = s.lift(i)
    def set(s: Vector[A], b: A): Vector[A] = if (s.length < i) s.updated(i, b) else s
  }
}

object PProperty extends OpticCompanion[PProperty] {
  def apply[S, B] = new PPropertyApplied[S, B](true)

  class PPropertyApplied[S, B](private val dummy: Boolean) extends AnyVal {
    def apply[T, A](fgetOpt: S => Either[T, A])(fset: (S, B) => T): PProperty[S, T, A, B] = new PProperty[S, T, A, B] {
      def set(s: S, b: B): T         = fset(s, b)
      def narrow(s: S): Either[T, A] = fgetOpt(s)
    }
  }

  trait Context extends PSubset.Context with PContains.Context

  def compose[S, T, A, B, U, V](f: PProperty[A, B, U, V], g: PProperty[S, T, A, B]): PProperty[S, T, U, V] =
    new PProperty[S, T, U, V] {
      def set(s: S, b: V): T         = g.narrow(s).fold(identity[T], a => g.set(s, f.set(a, b)))
      def narrow(s: S): Either[T, U] = g.narrow(s).flatMap(a => f.narrow(a).leftMap(g.set(s, _)))
    }

  trait ByTraject[S, T, A, B] extends PProperty[S, T, A, B] {
    def traj[F[+_]](s: S)(fab: A => F[B])(implicit FP: Pure[F], F: Functor[F]): F[T]

    override def traject[F[+_]](s: S)(fab: A => F[B])(implicit FP: Pure[F], F: Functor[F]): F[T] = traj(s)(fab)
    def set(s: S, b: B): T                                                                       = traj[Identity](s)(_ => b)
    def narrow(s: S): Either[T, A]                                                               = traj[Either[A, +*]](s)(a => Left(a)).swap
  }

  override def toGeneric[S, T, A, B](o: PProperty[S, T, A, B]): Optic[Context, S, T, A, B] =
    new Optic[Context, S, T, A, B] {
      def apply(c: Context)(p: A => c.F[B]): S => c.F[T] = s => o.traject(s)(p)(c.pure, c.functor)
    }
  override def fromGeneric[S, T, A, B](o: Optic[Context, S, T, A, B]): PProperty[S, T, A, B] =
    new ByTraject[S, T, A, B] {
      def traj[G[+_]](s: S)(fab: A => G[B])(implicit FP: Pure[G], F: Functor[G]): G[T] =
        o(new Context {
          def pure    = FP
          def functor = F
          type F[+x] = G[x]
        })(fab)(s)
    }
}