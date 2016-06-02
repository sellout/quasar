/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.qscript

import quasar._
import quasar.fp._
import quasar.Predef._
import quasar.std.StdLib._

import matryoshka._, FunctorT.ops._
import pathy.Path._
import scalaz._, Scalaz._, Inject._
import shapeless.{ Data => _, _}


// Need to keep track of our non-type-ensured guarantees:
// - all conditions in a ThetaJoin will refer to both sides of the join
// - each `Free` structure in a *Join or Union will have exactly one `point`
// - the common source in a Join or Union will be the longest common branch
// - all Reads have a Root (or another Read?) as their source
// - in `Pathable`, the only `MapFunc` node allowed is a `ProjectField`

// TODO use real EJson when it lands in master
sealed trait EJson[A]
object EJson {
  def toEJson[T[_[_]]](data: Data): T[EJson] = ???

  final case class Null[A]() extends EJson[A]
  final case class Str[A](str: String) extends EJson[A]

  //def str[A] = pPrism[EJson[A], String] { case Str(str) => str } (Str(_))

  implicit def equal: Equal ~> (Equal ∘ EJson)#λ = new (Equal ~> (Equal ∘ EJson)#λ) {
    def apply[A](in: Equal[A]): Equal[EJson[A]] = Equal.equal {
      case _ => true
    }
  }

  implicit def functor: Functor[EJson] = new Functor[EJson] {
    def map[A, B](fa: EJson[A])(f: A => B): EJson[B] = Null[B]()
  }
}

object DataLevelOps {
  sealed trait MapFunc[T[_[_]], A]
  final case class Nullary[T[_[_]], A](value: T[EJson]) extends MapFunc[T, A]
  final case class Unary[T[_[_]], A](a1: A) extends MapFunc[T, A]
  final case class Binary[T[_[_]], A](a1: A, a2: A) extends MapFunc[T, A]
  final case class Ternary[T[_[_]], A](a1: A, a2: A, a3: A) extends MapFunc[T, A]

  object MapFunc {
    implicit def equal[T[_[_]], A](implicit eqTEj: Equal[T[EJson]]): Equal ~> (Equal ∘ MapFunc[T, ?])#λ = new (Equal ~> (Equal ∘ MapFunc[T, ?])#λ) {
      def apply[A](in: Equal[A]): Equal[MapFunc[T, A]] = Equal.equal {
        case (Nullary(v1), Nullary(v2)) => v1.equals(v2)
        case (Unary(a1), Unary(a2)) => in.equal(a1, a2)
        case (Binary(a11, a12), Binary(a21, a22)) => in.equal(a11, a21) && in.equal(a12, a22)
        case (Ternary(a11, a12, a13), Ternary(a21, a22, a23)) => in.equal(a11, a21) && in.equal(a12, a22) && in.equal(a13, a23)
        case (_, _) => false
      }
    }

    implicit def functor[T[_[_]]]: Functor[MapFunc[T, ?]] = new Functor[MapFunc[T, ?]] {
      def map[A, B](fa: MapFunc[T, A])(f: A => B): MapFunc[T, B] =
        fa match {
          case Nullary(v) => Nullary[T, B](v)
          case Unary(a1) => Unary(f(a1))
          case Binary(a1, a2) => Binary(f(a1), f(a2))
          case Ternary(a1, a2, a3) => Ternary(f(a1), f(a2), f(a3))
        }
    }
  }

  type FreeMap[T[_[_]]] = Free[MapFunc[T, ?], Unit]

  // TODO this should be found from matryoshka - why isn't it being found!?!?
  implicit def NTEqual[F[_], A](implicit A: Equal[A], F: Equal ~> λ[α => Equal[F[α]]]):
    Equal[F[A]] =
  F(A)

  // TODO we would like to use `f1 ≟ f2` - but the implicit for Free is not found
  implicit def FreeMapEqual[T[_[_]]](implicit eqTEj: Equal[T[EJson]]): Equal[FreeMap[T]] =
    freeEqual[MapFunc[T, ?]].apply(Equal[Unit])

  // TODO we would like to use `f1 ≟ f2` - but the implicit for Free is not found
  implicit def JoinBranchEqual[T[_[_]]](implicit eqTEj: Equal[T[EJson]]): Equal[JoinBranch[T]] =
    freeEqual[QScript[T, ?]].apply(Equal[Unit])

  sealed trait ReduceFunc

  object ReduceFunc {
    implicit val equal: Equal[ReduceFunc] = Equal.equalRef
  }

  sealed trait JoinSide
  final case object LeftSide extends JoinSide
  final case object RightSide extends JoinSide

  object JoinSide {
    implicit val equal: Equal[JoinSide] = Equal.equalRef
  }
  
  type JoinFunc[T[_[_]]] = Free[MapFunc[T, ?], JoinSide]
  type JoinBranch[T[_[_]]] = Free[QScript[T, ?], Unit]

  def UnitF[T[_[_]]] = Free.point[MapFunc[T, ?], Unit](())
}

import DataLevelOps._

// TODO we should statically verify that these have `DimensionalEffect` of `Reduction`
object ReduceFuncs {
  final case object Count extends ReduceFunc
  final case object Sum extends ReduceFunc
  final case object Min extends ReduceFunc
  final case object Max extends ReduceFunc
  final case object Avg extends ReduceFunc
  final case object Arbitrary extends ReduceFunc
}

object MapFuncs {
  def ObjectProject[T[_[_]], A](a1: A, a2: A) = Binary[T, A](a1, a2)
  def ObjectProjectFree[T[_[_]], A](a1: A, a2: A) = Binary[T, A](a1, a2)
  def ObjectConcat[T[_[_]], A](a1: A, a2: A) = Binary[T, A](a1, a2)
  def MakeObject[T[_[_]], A](a1: A, a2: A) = Binary[T, A](a1, a2)

  def MakeArray[T[_[_]], A](a1: A) = Unary[T, A](a1)

  def StrLit[T[_[_]]: Corecursive, A](str: String) = Nullary[T, A](EJson.Str[T[EJson]](str).embed)
}

sealed trait SortDir
final case object Ascending  extends SortDir
final case object Descending extends SortDir

object SortDir {
  implicit val equal: Equal[SortDir] = Equal.equalRef
}

sealed trait JoinType
final case object Inner extends JoinType
final case object FullOuter extends JoinType
final case object LeftOuter extends JoinType
final case object RightOuter extends JoinType

object JoinType {
  implicit val equal: Equal[JoinType] = Equal.equalRef
}

sealed trait Pathable[T[_[_]], A]


object Pathable {
  //scala.Predef.implicitly[Equal[MapFunc[Fix, Unit]]]
  //scala.Predef.implicitly[FreeMap[Fix]]

  implicit def equal[T[_[_]]](implicit eqTEj: Equal[T[EJson]]): Equal ~> (Equal ∘ Pathable[T, ?])#λ =
    new (Equal ~> (Equal ∘ Pathable[T, ?])#λ) {
      def apply[A](eq: Equal[A]) =
        Equal.equal {
          case (Root(), Root()) => true
          case (Map(a1, f1), Map(a2, f2)) => f1 ≟ f2 && eq.equal(a1, a2)
          case (LeftShift(a1), LeftShift(a2)) => eq.equal(a1, a2)
          case (Union(a1, l1, r1), Union(a2, l2, r2)) =>
            eq.equal(a1, a2) && l1 ≟ l2 && r1 ≟ r2
          case (_, _) => false
        }
    }

  implicit def traverse[T[_[_]]]: Traverse[Pathable[T, ?]] =
    new Traverse[Pathable[T, ?]] {
      def traverseImpl[G[_], A, B](
        fa: Pathable[T, A])(
        f: A => G[B])(
        implicit G: Applicative[G]):
          G[Pathable[T, B]] =
        fa match {
          case Root()         => G.point(Root())
          case Empty()        => G.point(Empty())
          case Map(a, func)   => f(a) ∘ (Map[T, B](_, func))
          case LeftShift(a)   => f(a) ∘ (LeftShift(_))
          case Union(a, l, r) => f(a) ∘ (Union(_, l, r))
        }
    }
}

/** The top level of a filesystem. During compilation this represents `/`, but
  * in the structure a backend sees, it represents the mount point.
  */
final case class Root[T[_[_]], A]() extends Pathable[T, A]

// TODO is this really Pathable?
final case class Empty[T[_[_]], A]() extends Pathable[T, A]

/** A data-level transformation.
  */
final case class Map[T[_[_]], A](src: A, f: FreeMap[T]) extends Pathable[T, A]

/** Zooms in one level on the data, turning each map or array into a set of
  * values. Other data types become undefined.
  */
final case class LeftShift[T[_[_]], A](src: A) extends Pathable[T, A]

/** Creates a new dataset, |a|+|b|, containing all of the entries from each of
  * the input sets, without any indication of which set they came from
  *
  * This could be handled as another join type, the anti-join
  * (T[EJson] \/ T[EJson] => T[EJson], specifically as `_.merge`), with the
  * condition being `κ(true)`,
  */
final case class Union[T[_[_]], A](
  src: A,
  lBranch: JoinBranch[T],
  rBranch: JoinBranch[T])
    extends Pathable[T, A]

sealed trait QScriptCore[T[_[_]], A]

object QScriptCore {
  implicit def equal[T[_[_]]](implicit eqTEj: Equal[T[EJson]]): Equal ~> (Equal ∘ QScriptCore[T, ?])#λ =
    new (Equal ~> (Equal ∘ QScriptCore[T, ?])#λ) {
      def apply[A](eq: Equal[A]) =
        Equal.equal {
          case (Reduce(a1, b1, f1), Reduce(a2, b2, f2)) =>
            b1 ≟ b2 && f1 ≟ f2 && eq.equal(a1, a2)
          case (Sort(a1, b1, o1), Sort(a2, b2, o2)) =>
            b1 ≟ b2 && o1 ≟ o2 && eq.equal(a1, a2)
          case (Filter(a1, f1), Filter(a2, f2)) => f1 ≟ f2 && eq.equal(a1, a2)
          case (_, _) => false
        }
    }

  implicit def traverse[T[_[_]]]: Traverse[QScriptCore[T, ?]] =
    new Traverse[QScriptCore[T, ?]] {
      def traverseImpl[G[_]: Applicative, A, B](
        fa: QScriptCore[T, A])(
        f: A => G[B]) =
        fa match {
          case Reduce(a, b, func) => f(a) ∘ (Reduce(_, b, func))
          case Sort(a, b, o)      => f(a) ∘ (Sort(_, b, o))
          case Filter(a, func)    => f(a) ∘ (Filter(_, func))
        }
    }
}

final case class PatternGuard[T[_[_]], A](expr: A, typ: Type, cont: A, fallback: A)
    extends QScriptCore[T, A]

/** Performs a reduction over a dataset, with the dataset partitioned by the
  * result of the MapFunc. So, rather than many-to-one, this is many-to-fewer.
  *
  * @group MRA
  */
final case class Reduce[T[_[_]], A](src: A, bucket: FreeMap[T], f: ReduceFunc)
    extends QScriptCore[T, A]

/** Sorts values within a bucket. This could be represented with
  *     LeftShift(Map(_.sort, Reduce(_ :: _, ???))
  * but backends tend to provide sort directly, so this avoids backends having
  * to recognize the pattern. We could provide an algebra
  *     (Sort :+: QScript)#λ => QScript
  * so that a backend without a native sort could eliminate this node.
  */
// Should this operate on a sequence of mf/order pairs? Might be easier for
// implementers to handle stable sorting that way.
final case class Sort[T[_[_]], A](src: A, bucket: FreeMap[T], order: SortDir)
    extends QScriptCore[T, A]

/** Eliminates some values from a dataset, based on the result of FilterFunc.
  */
final case class Filter[T[_[_]], A](src: A, f: FreeMap[T])
    extends QScriptCore[T, A]

/** A backend-resolved `Root`, which is now a path. */
final case class Read[A](src: A, path: AbsFile[Sandboxed])

object Read {
  implicit def equal[T[_[_]]]: Equal ~> (Equal ∘ Read)#λ =
    new (Equal ~> (Equal ∘ Read)#λ) {
      def apply[A](eq: Equal[A]) =
        Equal.equal {
          case (Read(a1, p1), Read(a2, p2)) => eq.equal(a1, a2) && p1 ≟ p2
        }
    }

  implicit def traverse[T[_[_]]]: Traverse[Read] =
    new Traverse[Read] {
      def traverseImpl[G[_]: Applicative, A, B](fa: Read[A])(f: A => G[B]) =
        f(fa.src) ∘ (Read(_, fa.path))
    }
}

/** Applies a function across two datasets, in the cases where the JoinFunc
  * evaluates to true. The branches represent the divergent operations applied
  * to some common src. Each branch references the src exactly once. (Since no
  * constructor has more than one recursive component, it’s guaranteed that
  * neither side references the src _more_ than once.)
  *
  * This case represents a full θJoin, but we could have an algebra that
  * rewites it as
  *     Filter(_, EquiJoin(...))
  * to simplify behavior for the backend.
  */
final case class ThetaJoin[T[_[_]], A](
  on: JoinFunc[T],
  f: JoinType,
  src: A,
  lBranch: JoinBranch[T],
  rBranch: JoinBranch[T])

object ThetaJoin {
  implicit def equal[T[_[_]]](implicit eqTEj: Equal[T[EJson]]): Equal ~> (Equal ∘ ThetaJoin[T, ?])#λ =
    new (Equal ~> (Equal ∘ ThetaJoin[T, ?])#λ) {
      def apply[A](eq: Equal[A]) =
        Equal.equal {
          case (ThetaJoin(o1, f1, a1, l1, r1), ThetaJoin(o2, f2, a2, l2, r2)) =>
            eq.equal(a1, a2) && o1 ≟ o2 && f1 ≟ f2 && l1 ≟ l2 && r1 ≟ r2
        }
    }

  implicit def traverse[T[_[_]]]: Traverse[ThetaJoin[T, ?]] =
    new Traverse[ThetaJoin[T, ?]] {
      def traverseImpl[G[_]: Applicative, A, B](
        fa: ThetaJoin[T, A])(
        f: A => G[B]) =
        f(fa.src) ∘ (ThetaJoin(fa.on, fa.f, _, fa.lBranch, fa.rBranch))
    }
}

// backends can choose to rewrite joins using EquiJoin
// can rewrite a ThetaJoin as EquiJoin + Filter
final case class EquiJoin[T[_[_]], A](
  lKey: FreeMap[T],
  rKey: FreeMap[T],
  f: JoinType,
  src: A,
  lBranch: JoinBranch[T],
  rBranch: JoinBranch[T])

object EquiJoin {
  implicit def equal[T[_[_]]](implicit eqTEj: Equal[T[EJson]]): Equal ~> (Equal ∘ EquiJoin[T, ?])#λ =
    new (Equal ~> (Equal ∘ EquiJoin[T, ?])#λ) {
      def apply[A](eq: Equal[A]) =
        Equal.equal {
          case (EquiJoin(lk1, rk1, f1, a1, l1, r1),
                EquiJoin(lk2, rk2, f2, a2, l2, r2)) =>
            lk1 ≟ lk2 && rk1 ≟ rk2 && f1 ≟ f2 && eq.equal(a1, a2) && l1 ≟ l2 && r1 ≟ r2
        }
    }

  implicit def traverse[T[_[_]]]: Traverse[EquiJoin[T, ?]] =
    new Traverse[EquiJoin[T, ?]] {
      def traverseImpl[G[_]: Applicative, A, B](
        fa: EquiJoin[T, A])(
        f: A => G[B]) =
        f(fa.src) ∘
          (EquiJoin(fa.lKey, fa.rKey, fa.f, _, fa.lBranch, fa.rBranch))
    }
}

object Transform {
  import MapFuncs._
  import ReduceFuncs._

  type Inner[T[_[_]]] = T[QScript[T, ?]]
  type QSAlgebra[T[_[_]]] = LogicalPlan[Inner[T]] => QScript[T, Inner[T]]

  def toQscript[T[_[_]]: FunctorT, A](lp: T[LogicalPlan])(f: QSAlgebra[T]): T[QScript[T, ?]] =
    lp.transCata(f)

  final case class Merge[T[_[_]], A](
    namel: Option[String],
    namer: Option[String],
    merge: Inner[T])

  def mergeSrcsPathable[T[_[_]]: Corecursive, A](
      left: Pathable[T, A],
      right: Pathable[T, A])(
      implicit F: Pathable[T, ?] :<: QScript[T, ?]): Merge[T, A] =
    (left, right) match {
      case (Map(src1, m1), Map(src2, m2)) if src1 == src2 =>
        Merge(
          Some("tmp1"),
          Some("tmp2"),
          F.inj(Map(src1, Free.roll[MapFunc[T, ?], Unit](
            ObjectConcat(
              Free.roll[MapFunc[T, ?], Unit](MakeObject(Free.roll(StrLit[T, FreeMap[T]]("tmp1")), m1)),
              Free.roll[MapFunc[T, ?], Unit](MakeObject(Free.roll(StrLit[T, FreeMap[T]]("tmp2")), m2)))))))
    }


  def mergeSrcs[T[_[_]], A](
      left: Inner[T],
      right: Inner[T]): Merge[T, A] =

    (left, right) match {
      case (l, r) if l == r => Merge(None, None, l)

      //case (Reduce(src1, m1, r1), Map(src2, m2)) if src1 == src2 =>
      //  Merge(
      //    Some("tmp1"),
      //    Some("tmp2"),
      //    Cross(src1, ("tmp1", Reduce(UnitF, m1, r1)), ("tmp2", Map(UnitF, m2))))

      // TODO handle other cases (recursive and not)
      case _ => ???
    }

  def merge2Map[T[_[_]]: Corecursive, A](
      values: Func.Input[Inner[T], nat._2])(
      func: (A, A) => Pathable[T, A])(
      implicit F: Pathable[T, ?] :<: QScript[T, ?]): QScript[T, A] = {

    val Merge(left, right, merged) = mergeSrcs(values(0), values(1))

    val rewrittenFunc: FreeMap[T] = (left, right) match {
      case (Some(lname), Some(rname)) =>
        F.inj(func(
          Free.roll[MapFunc[T, ?], Unit](ObjectProject(UnitF, Free.roll[MapFunc[T, ?], Unit](StrLit[T, FreeMap[T]](lname)))),
          Free.roll[MapFunc[T, ?], Unit](ObjectProject(UnitF, Free.roll[MapFunc[T, ?], Unit](StrLit[T, FreeMap[T]](rname))))))
      case (None, Some(rname)) =>
        func(UnitF, ObjectProject(UnitF, Free.roll(StrLit(rname))))
      case (Some(lname), None) =>
        func(ObjectProject(UnitF, Free.roll(StrLit(lname))), UnitF)
      case (None, None) =>
        func(UnitF, UnitF)
    }
    Map(merged, rewrittenFunc)
  }

  def invokeMapping1[T[_[_]]](
      func: UnaryFunc,
      values: Func.Input[Inner[T], nat._1])(
      implicit F: Pathable[T, ?] :<: QScript[T, ?]): QScript[T, Inner[T]] =
    func match {
      case structural.MakeArray => F.inj(Map(values(0), Free.roll(MakeArray(UnitF))))
      case _ => ??? // TODO
    }

  def invokeMapping2[T[_[_]]](
      func: BinaryFunc,
      values: Func.Input[Inner[T], nat._2])(
      implicit F: Pathable[T, ?] :<: QScript[T, ?]): QScript[T, Inner[T]] =
    func match {
      case structural.MakeObject => ??? //F.inj(Map(values(1), Free.roll(MakeObject(values(0), UnitF))))
      case _ => ??? // TODO
    }

  // TODO we need to handling bucketing from GroupBy
  // the buckets will not always be UnitF, if we have grouped previously
  //
  // TODO also we should be able to statically guarantee that we are matching on all reductions here
  // this involves changing how DimensionalEffect is assigned (algebra rather than parameter)
  def invokeReduction1[T[_[_]]](
      func: UnaryFunc,
      values: Func.Input[Inner[T], nat._1])(
      implicit F: QScriptCore[T, ?] :<: QScript[T, ?]): QScript[T, Inner[T]] =
    func match {
      case agg.Count     => F.inj(Reduce(values(0), UnitF, Count))
      case agg.Sum       => F.inj(Reduce(values(0), UnitF, Sum))
      case agg.Min       => F.inj(Reduce(values(0), UnitF, Min))
      case agg.Max       => F.inj(Reduce(values(0), UnitF, Max))
      case agg.Avg       => F.inj(Reduce(values(0), UnitF, Avg))
      case agg.Arbitrary => F.inj(Reduce(values(0), UnitF, Arbitrary))
    }

  def algebra[T[_[_]]: Corecursive](
      lp: LogicalPlan[Inner[T]])(
      implicit F: Pathable[T, ?] :<: QScript[T, ?],
               G: QScriptCore[T, ?] :<: QScript[T, ?]): QScript[T, Inner[T]] =
    lp match {
      case LogicalPlan.ReadF(path) => ??? // nested ObjectProject

      case LogicalPlan.ConstantF(data) => F.inj(Map(
        F.inj(Root[T, Inner[T]]()).embed,
        Free.roll[MapFunc[T, ?], Unit](Nullary[T, FreeMap[T]](EJson.toEJson[T](data)))))

      case LogicalPlan.FreeF(name) => F.inj(Map(
        F.inj(Empty[T, Inner[T]]()).embed,
        Free.roll(ObjectProjectFree(Free.roll(StrLit(name.toString)), UnitF))))

      case LogicalPlan.TypecheckF(expr, typ, cont, fallback) =>
        G.inj(PatternGuard(expr, typ, cont, fallback))

      // TODO this illustrates the untypesafe ugliness b/c the pattern match does not guarantee the appropriate sized `Sized`
      // https://github.com/milessabin/shapeless/pull/187
      case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1)) if func.effect == Mapping =>
        invokeMapping1(func, Func.Input1(a1))(F)

      case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2)) if func.effect == Mapping =>
        invokeMapping2(func, Func.Input2(a1, a2))(F)

      //case LogicalPlan.InvokeF(func @ TernaryFunc(_, _, _, _, _, _, _, _), input) if func.effect == Mapping => invokeMaping3(func, input)

      case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1)) if func.effect == Reduction =>
        invokeReduction1(func, Func.Input1(a1))(G)

      //// special case Sort (is Sort a Transformation?)
      //case LogicalPlan.InvokeF(func @ BinaryFunc(_, _, _, _, _, _, _, _), input) if func.effect == Sifting => invokeSifting2(func, input)

      //case LogicalPlan.InvokeF(func @ BinaryFunc(_, _, _, _, _, _, _, _), input) if func.effect == Expansion => invokeLeftShift(func, input)

      //// handling bucketing for sorting
      //// e.g. squashing before a reduce puts everything in the same bucket
      //// TODO consider removing Squashing entirely - and using GroupBy exclusively
      //// Reduce(Sort(LeftShift(GroupBy)))  (LP)
      //// Reduce(Add(GB, GB))
      //// LeftShift and Projection can change grouping/bucketing metadata
      //// y := select sum(pop) (((from zips) group by state) group by substring(city, 0, 2))
      //// z := select sum(pop) from zips group by city
      //case LogicalPlan.InvokeF(func @ UnaryFunc(_, _, _, _, _, _, _, _), input) if func.effect == Squashing => ??? // returning the source with added metadata - mutiple buckets

      //case LogicalPlan.InvokeF(func @ BinaryFunc(_, _, _, _, _, _, _, _), input) => {
      //  func match {
      //    case GroupBy => ??? // returning the source with added metadata - mutiple buckets
      //    case UnionAll => ??? //UnionAll(...)
      //    // input(0) ~ (name, thing)
      //    case IntersectAll => ObjProj(left, ThetaJoin(Eq, Inner, Root(), input(0), input(1)))  // inner join where left = right, combine func = left (whatever)
      //    case Except => ObjProj(left, ThetaJoin(False, LeftOuter, Root(), input(0), input(1)))
      //  }
      //}
      //case LogicalPlan.InvokeF(func @ TernaryFunc, input) => {
      //  func match {
      //    // src is Root() - and we rewrite lBranch/rBranch so that () refers to Root()
      //    case InnerJoin => ThetaJoin(input(2), Inner, Root(), input(0), input(1)) // TODO use input(2)
      //    case LeftOuterJoin => ThetaJoin(input(2), LeftOuter, Root(), input(0), input(1)) // TODO use input(2)
      //    case RightOuterJoin => ???
      //    case FullOuterJoin => ???
      //  }
      //}

      //// Map(src=form, MakeObject(name, ()))
      //// Map(Map(src=form, MakeObject(name, ())), body)
      //case LogicalPlan.LetF(name, form, body) => rewriteLet(body)(qsRewrite(name, form))

      case _ => ??? // TODO
    }
}