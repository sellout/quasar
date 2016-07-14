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
import quasar.ejson.{Int => _, _}
import quasar.fp._
import quasar.namegen._
import quasar.qscript.MapFunc._
import quasar.qscript.MapFuncs._
import quasar.Planner._
import quasar.Predef._
import quasar.std.StdLib._

import scala.Predef.implicitly

import matryoshka._, Recursive.ops._, TraverseT.ops._
import matryoshka.patterns._
import scalaz.{:+: => _, Divide => _, _}, Scalaz._, Inject._, Leibniz._, IndexedStateT._
import shapeless.{Fin, nat, Sized}

// Need to keep track of our non-type-ensured guarantees:
// - all conditions in a ThetaJoin will refer to both sides of the join
// - each `Free` structure in a *Join or Union will have exactly one `point`
// - the common source in a Join or Union will be the longest common branch
// - all Reads have a Root (or another Read?) as their source
// - in `Pathable`, the only `MapFunc` node allowed is a `ProjectField`

sealed abstract class JoinType
final case object Inner extends JoinType
final case object FullOuter extends JoinType
final case object LeftOuter extends JoinType
final case object RightOuter extends JoinType

object JoinType {
  implicit val equal: Equal[JoinType] = Equal.equalRef
  implicit val show: Show[JoinType] = Show.showFromToString
}

trait Helpers[T[_[_]]] {
  def equiJF: JoinFunc[T] =
    Free.roll(Eq(Free.point(LeftSide), Free.point(RightSide)))
}

// TODO: Could maybe require only Functor[F], once CoEnv exposes the proper
//       instances
class Transform[T[_[_]]: Recursive: Corecursive: EqualT: ShowT, F[_]: Traverse](
  implicit DE: Const[DeadEnd, ?] :<: F,
           SP: SourcedPathable[T, ?] :<: F,
           QC: QScriptCore[T, ?] :<: F,
           TJ: ThetaJoin[T, ?] :<: F,
           PB: ProjectBucket[T, ?] :<: F,
           // TODO: Remove this one once we have multi-sorted AST
           FI: F :<: QScriptProject[T, ?],
           mergeable:  Mergeable.Aux[T, F[Unit]],
           eq:         Delay[Equal, F])
    extends Helpers[T] {

  case class Ann(provenance: List[FreeMap[T]], values: FreeMap[T])
  val EmptyAnn: Ann = Ann(Nil, UnitF[T])

  type Target[A] = EnvT[Ann, F, A]
  type TargetT = Target[T[Target]]

  def DeadEndTarget(deadEnd: DeadEnd): TargetT =
    EnvT[Ann, F, T[Target]]((EmptyAnn, DE.inj(Const[DeadEnd, T[Target]](deadEnd))))

  val RootTarget: TargetT = DeadEndTarget(Root)
  val EmptyTarget: TargetT = DeadEndTarget(Empty)

  //type Fs[A] = List[F[A]]

  //case class ZipperSides(
  //  lSide: FreeMap[T],
  //  rSide: FreeMap[T])

  //case class ZipperTails(
  //  lTail: Fs[Unit],
  //  rTail: Fs[Unit])

  //case class ZipperAcc(
  //  acc: Fs[Unit],
  //  sides: ZipperSides,
  //  tails: ZipperTails)

  //def linearize[F[_]: Functor: Foldable]: Algebra[F, List[F[Unit]]] =
  //  fl => fl.void :: fl.fold

  //def delinearizeInner[F[_]: Functor, A](implicit DE: Const[DeadEnd, ?] :<: F):
  //    Coalgebra[F, List[F[A]]] = {
  //  case Nil    => DE.inj(Const(Root))
  //  case h :: t => h.map(_ => t)
  //}

  //def delinearizeFreeQS[F[_]: Functor, A]:
  //    ElgotCoalgebra[Unit \/ ?, F, List[F[A]]] = {
  //  case Nil    => ().left
  //  case h :: t => h.map(_ => t).right
  //}

  //val consZipped: Algebra[ListF[F[Unit], ?], ZipperAcc] = {
  //  case NilF() => ZipperAcc(Nil, ZipperSides(UnitF[T], UnitF[T]), ZipperTails(Nil, Nil))
  //  case ConsF(head, ZipperAcc(acc, sides, tails)) => ZipperAcc(head :: acc, sides, tails)
  //}

  // E, M, F, A => A => M[E[F[A]]] 
  //val zipper: ElgotCoalgebraM[
  //    ZipperAcc \/ ?,
  //    State[NameGen, ?],
  //    ListF[F[Unit], ?],
  //    (ZipperSides, ZipperTails)] = {
  //  case (zs @ ZipperSides(lm, rm), zt @ ZipperTails(l :: ls, r :: rs)) => {
  //    val ma = implicitly[Mergeable.Aux[T, F[Unit]]]

  //    ma.mergeSrcs(lm, rm, l, r).fold[ZipperAcc \/ ListF[F[Unit], (ZipperSides, ZipperTails)]]({
  //        case SrcMerge(inn, lmf, rmf) =>
  //          ConsF(inn, (ZipperSides(lmf, rmf), ZipperTails(ls, rs))).right[ZipperAcc]
  //      }, ZipperAcc(Nil, zs, zt).left)
  //  }
  //  case (sides, tails) =>
  //    ZipperAcc(Nil, sides, tails).left.point[State[NameGen, ?]]
  //}

  //def merge(left: Inner, right: Inner): State[NameGen, SrcMerge[Inner, Free[F, Unit]]] = {
  //  val lLin: Fs[Unit] = left.cata(linearize).reverse
  //  val rLin: Fs[Unit] = right.cata(linearize).reverse

  //  elgotM((
  //    ZipperSides(UnitF[T], UnitF[T]),
  //    ZipperTails(lLin, rLin)))(
  //    consZipped(_: ListF[F[Unit], ZipperAcc]).point[State[NameGen, ?]], zipper) ∘ {
  //      case ZipperAcc(common, ZipperSides(lMap, rMap), ZipperTails(lTail, rTail)) =>
  //        val leftRev: FreeUnit[F] =
  //          foldIso(CoEnv.freeIso[Unit, F])
  //            .get(lTail.reverse.ana[T, CoEnv[Unit, F, ?]](delinearizeFreeQS[F, Unit] >>> (CoEnv(_))))

  //        val rightRev: FreeUnit[F] =
  //          foldIso(CoEnv.freeIso[Unit, F])
  //            .get(rTail.reverse.ana[T, CoEnv[Unit, F, ?]](delinearizeFreeQS[F, Unit] >>> (CoEnv(_))))

  //        SrcMerge[Inner, FreeUnit[F]](
  //          common.reverse.ana[T, F](delinearizeInner),
  //          rebase(leftRev, Free.roll(QC.inj(Map(().point[Free[F, ?]], lMap)))),
  //          rebase(rightRev, Free.roll(QC.inj(Map(().point[Free[F, ?]], rMap)))))
  //  }
  //}

  /** This unifies a pair of sources into a single one, with additional
    * expressions to access the combined bucketing info, as well as the left and
    * right values.
    */
  def autojoin(left: T[Target], right: T[Target]):
      (F[T[Target]], List[FreeMap[T]], FreeMap[T], FreeMap[T]) =
    ??? // TODO

  /** A convenience for a pair of autojoins, does the same thing, but returns
    * access to all three values.
    */
  def autojoin3(left: T[Target], center: T[Target], right: T[Target]):
      (F[T[Target]], List[FreeMap[T]], FreeMap[T], FreeMap[T], FreeMap[T]) = {
    val (lsrc, lbuckets, lval, cval) = autojoin(left, center)
    val (fullSrc, fullBuckets, bval, rval) =
      autojoin(EnvT((Ann(lbuckets, UnitF), lsrc)).embed, right)

    (fullSrc, fullBuckets, bval >> lval, bval >> cval, rval)
  }

  def concatBuckets(buckets: List[FreeMap[T]]): (FreeMap[T], List[FreeMap[T]]) =
    (ConcatArraysN(buckets.map(b => Free.roll(MakeArray[T, FreeMap[T]](b))): _*),
      buckets.zipWithIndex.map(p =>
        Free.roll(ProjectIndex[T, FreeMap[T]](
          UnitF[T],
          IntLit[T, Unit](p._2)))))

  def concat(l: FreeMap[T], r: FreeMap[T]):
      State[NameGen, (FreeMap[T], FreeMap[T], FreeMap[T])] =
    (freshName("lc") ⊛ freshName("rc"))((lname, rname) =>
      (Free.roll(ConcatMaps[T, FreeMap[T]](
        Free.roll(MakeMap[T, FreeMap[T]](StrLit[T, Unit](lname), l)),
        Free.roll(MakeMap[T, FreeMap[T]](StrLit[T, Unit](rname), r)))),
        Free.roll(ProjectField[T, FreeMap[T]](UnitF[T], StrLit[T, Unit](lname))),
        Free.roll(ProjectField[T, FreeMap[T]](UnitF[T], StrLit[T, Unit](rname)))))

  def merge2Map(
    values: Func.Input[T[Target], nat._2])(
    func: (FreeMap[T], FreeMap[T]) => MapFunc[T, FreeMap[T]]):
      State[NameGen, Target[T[Target]]] = {
    val (src, buckets, lval, rval) = autojoin(values(0), values(1))
    val (bucks, newBucks) = concatBuckets(buckets)
    concat(bucks, func(lval, rval).embed) ∘ {
      case (merged, b, v) =>
        EnvT((
          Ann(newBucks.map(b >> _), v),
          // NB: Does it matter what annotation we add to `src` here?
          QC.inj(Map(EnvT((EmptyAnn, src)).embed, merged))))
    }
  }

  // TODO unify with `merge2Map`
  def merge3Map(
    values: Func.Input[T[Target], nat._3])(
    func: (FreeMap[T], FreeMap[T], FreeMap[T]) => MapFunc[T, FreeMap[T]])(
    implicit ma: Mergeable.Aux[T, F[Unit]]):
      State[NameGen, Target[T[Target]]] = {
    val (src, buckets, lval, cval, rval) = autojoin3(values(0), values(1), values(2))
    val (bucks, newBucks) = concatBuckets(buckets)
    concat(bucks, func(lval, cval, rval).embed) ∘ {
      case (merged, b, v) =>
        EnvT((
          Ann(newBucks.map(b >> _), v),
          // NB: Does it matter what annotation we add to `src` here?
          QC.inj(Map(EnvT((EmptyAnn, src)).embed, merged))))
    }
  }

  // [1, 2, 3] => [{key: 0, value: 1}, ...]
  // {a: 1, b: 2, c: 3}` => `{a: {key: a, value: 1}, b: {key: b, value: 2}, c: {key: c, value: 3}}`
  def bucketNest(keyName: String, valueName: String): MapFunc[T, FreeMap[T]] = ???

  // NB: More compilicated LeftShifts are generated as an optimization:
  // before: ThetaJoin(cs, Map((), mf), LeftShift((), struct, repair), comb)
  // after: LeftShift(cs, struct, comb.flatMap(LeftSide => mf.map(_ => LeftSide), RS => repair))
  //
  // TODO namegen
  def invokeExpansion1(
    func: UnaryFunc,
    values: Func.Input[T[Target], nat._1]):
      Target[T[Target]] =
    func match {
      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x:foo) - 12
      //   id(p, x:bar) - 18
      //   id(p, x:foo) - 1
      //   id(p, x:bar) - 2
      // (one bucket)
      case structural.FlattenMap | structural.FlattenArray =>
        val EnvT((Ann(provs, vals), src)): EnvT[Ann, F, T[Target]] =
          values(0).project

        val getVals: FreeMap[T] =
          Free.roll(ProjectField[T, FreeMap[T]](UnitF[T], StrLit[T, Unit]("value")))

        val wrappedSrc: F[T[Target]] =
          QC.inj(Map(EnvT((EmptyAnn, src)).embed, Free.roll(bucketNest("key", "value"))))

        EnvT[Ann, F, T[Target]]((
          Ann(
            Free.roll(ProjectField[T, FreeMap[T]](UnitF[T], StrLit[T, Unit]("key"))) :: provs,
            vals >> getVals),
          SP.inj(LeftShift(
            EnvT[Ann, F, T[Target]]((EmptyAnn, wrappedSrc)).embed,
            getVals,
            Free.point(RightSide)))))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x:foo) - foo
      //   id(p, x:bar) - bar
      //   id(p, y:foo) - foo
      //   id(p, y:bar) - bar
      // (one bucket)
      case structural.FlattenMapKeys =>
        SP.inj(LeftShift(
          values(0),
          Free.roll(DupMapKeys(UnitF)),
          Free.point(RightSide)))
      case structural.FlattenArrayIndices =>
        SP.inj(LeftShift(
          values(0),
          Free.roll(DupArrayIndices(UnitF)),
          Free.point(RightSide)))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x, foo) - 12
      //   id(p, x, bar) - 18
      //   id(p, y, foo) - 1
      //   id(p, y, bar) - 2
      // (two buckets)
      case structural.ShiftMap =>
        QB.inj(LeftShiftBucket(
          values(0),
          UnitF,
          Free.point(RightSide),
          Free.roll(DupMapKeys(UnitF)))) // affects bucketing metadata
      case structural.ShiftArray =>
        QB.inj(LeftShiftBucket(
          values(0),
          UnitF,
          Free.point(RightSide),
          Free.roll(DupArrayIndices(UnitF)))) // affects bucketing metadata

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x, foo) - foo
      //   id(p, x, bar) - bar
      //   id(p, y, foo) - foo
      //   id(p, y, bar) - bar
      // (two buckets)
      case structural.ShiftMapKeys =>
        QB.inj(LeftShiftBucket(
          values(0),
          Free.roll(DupMapKeys(UnitF)),
          Free.point(RightSide),
          UnitF)) // affects bucketing metadata
      case structural.ShiftArrayIndices =>
        QB.inj(LeftShiftBucket(
          values(0),
          Free.roll(DupArrayIndices(UnitF)),
          Free.point(RightSide),
          UnitF)) // affects bucketing metadata
    }

  def invokeExpansion2(
    func: BinaryFunc,
    values: Func.Input[T[Target], nat._2]):
      State[NameGen, Target[T[Target]]] =
    func match {
      // TODO left shift range values onto provenance + duping
      case set.Range => 
        val (src, buckets, lval, rval) = autojoin(values(0), values(1))
        val (bucks, newBucks) = concatBuckets(buckets)
        concat(bucks, Free.roll(Range(lval, rval))) ∘ {
          case (merged, b, v) =>
            EnvT((
              Ann(newBucks.map(b >> _), v),
              SP.inj(LeftShift(
                 EnvT((EmptyAnn, src)).embed,
                 merged,
                 Free.point[MapFunc[T, ?], JoinSide](RightSide)))))
        }
    }

  def invokeReduction1(
    func: UnaryFunc,
    values: Func.Input[T[Target], nat._1]):
      State[NameGen, Target[T[Target]]] = {

    findBucket(values(0)) map {
      case (src, bucket, reduce) =>
        Reduce[T, T[Target], nat._0](
          src,
          bucket,
          Sized[List](ReduceFunc.translateReduction[FreeMap[T]](func)(reduce)),
          Free.point(Fin[nat._0, nat._1]))
    }
  }

  // TODO: This should definitely be in Matryoshka.
  // apomorphism - short circuit by returning left
  def substitute[T[_[_]], F[_]](original: T[F], replacement: T[F])(implicit T: Equal[T[F]]):
      T[F] => T[F] \/ T[F] =
   tf => if (tf ≟ original) replacement.left else original.right

  // TODO: This should definitely be in Matryoshka.
  def transApoT[T[_[_]]: FunctorT, F[_]: Functor](t: T[F])(f: T[F] => T[F] \/ T[F]):
      T[F] =
    f(t).fold(ι, FunctorT[T].map(_)(_.map(transApoT(_)(f))))

  def invokeThetaJoin(
    input: Func.Input[T[Target], nat._3],
    tpe: JoinType):
      State[NameGen, Target[T[Target]]] =
    for {
      tup0 <- merge(input(0), input(1)).mapK(_.right[PlannerError])
      SrcMerge(src1, jbLeft, jbRight) = tup0
      tup1 <- merge(src1, input(2)).mapK(_.right[PlannerError])
    } yield {
      val SrcMerge(src2, bothSides, cond) = tup1

      val leftBr = rebase(bothSides, jbLeft)
      val rightBr = rebase(bothSides, jbRight)

      val onQS =
        transApoT[Free[?[_], JoinSide], F](transApoT[Free[?[_], JoinSide], F](cond.map[JoinSide](κ(RightSide)))(
          substitute[Free[?[_], JoinSide], F](jbLeft.map[JoinSide](κ(RightSide)), Free.point(LeftSide))))(
          substitute[Free[?[_], JoinSide], F](jbRight.map[JoinSide](κ(RightSide)), Free.point(RightSide)))

      val on: JoinFunc[T] = equiJF // TODO get from onQS to here somehow

      // TODO namegen
      ThetaJoin(
        src2,
        leftBr.mapSuspension(FI),
        rightBr.mapSuspension(FI),
        on,
        Inner,
        Free.roll(ConcatMaps(
          Free.roll(MakeMap(StrLit("left"), Free.point(LeftSide))),
          Free.roll(MakeMap(StrLit("right"), Free.point(RightSide))))))
    }

  def ProjectTarget(prefix: Target[T[Target]], field: FreeMap[T]) = {
    val Ann(provenance, values) = prefix.ask
    EnvT[Ann, F, T[Target]]((
      Ann(Free.roll(ConcatArrays[T, FreeMap[T]](
        Free.roll(MakeArray[T, FreeMap[T]](UnitF[T])),
        Free.roll(MakeArray[T, FreeMap[T]](field)))) :: provenance, values),
      PB.inj(BucketField(prefix.embed, UnitF[T], field))))
  }

  def pathToProj(path: pathy.Path[_, _, _]): TargetT =
    pathy.Path.peel(path).fold[TargetT](
      RootTarget) {
      case (p, n) =>
        ProjectTarget(pathToProj(p), StrLit(n.fold(_.value, _.value)))
    }

  // TODO error handling
  def fromData[T[_[_]]: Corecursive](data: Data): String \/ T[EJson] = {
    data.hyloM[String \/ ?, CoEnv[Data, EJson, ?], T[EJson]](
      interpretM[String \/ ?, EJson, Data, T[EJson]](
        _.toString.left[T[EJson]],
        _.embed.right[String]),
      Data.toEJson[EJson].apply(_).right)
  }

  def lpToQScript: LogicalPlan[T[Target]] => QSState[TargetT] = {
    case LogicalPlan.ReadF(path) =>
      stateT(pathToProj(path))

    case LogicalPlan.ConstantF(data) =>
      val res = QC.inj(Map(
        RootTarget.embed,
        Free.roll[MapFunc[T, ?], Unit](Nullary[T, FreeMap[T]](fromData(data).fold(
          error => CommonEJson.inj(ejson.Str[T[EJson]](error)).embed,
          ι)))))
      stateT(EnvT((EmptyAnn, res)))

    //case LogicalPlan.FreeF(name) =>
    //  val res = QC.inj(Map(
    //    EmptyTarget.embed,
    //    Free.roll(ProjectField(StrLit(name.toString), UnitF[T]))))
    //  stateT(EnvT((EmptyAnn, res)))

    //case LogicalPlan.LetF(name, form, body) =>
    //  for {
    //    tmpName <- freshName("let").lift[PlannerError \/ ?]
    //    tup <- merge(form, body).mapK(_.right[PlannerError])
    //    SrcMerge(src, jb1, jb2) = tup
    //    theta <- makeBasicTheta(src, jb1, jb2)
    //  } yield {
    //    QC.inj(Map(
    //      QC.inj(Map(
    //        TJ.inj(theta.src).embed,
    //        Free.roll(ConcatMaps(
    //          Free.roll(MakeMap(StrLit(tmpName), UnitF[T])),
    //          Free.roll(MakeMap(StrLit(name.toString), theta.left)))))).embed,
    //      rebase(theta.right, Free.roll(ProjectField(UnitF[T], StrLit(tmpName))))))
    //  }

    case LogicalPlan.TypecheckF(expr, typ, cont, fallback) =>
      merge3Map(Func.Input3(expr, cont, fallback))(Guard(_, typ, _, _))
        .map(QC.inj)

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1)) if func.effect ≟ Mapping =>
      stateT(QC.inj(
        Map(a1, Free.roll(MapFunc.translateUnaryMapping(func)(UnitF)))))

    case LogicalPlan.InvokeFUnapply(structural.ObjectProject, Sized(a1, a2)) =>
      merge2Map(Func.Input(a1, a2))(BucketField(_, _))

    case LogicalPlan.InvokeFUnapply(structural.ArrayProject, Sized(a1, a2)) =>
      merge2Map(Func.Input(a1, a2))(BucketIndex(_, _))

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2))
        if func.effect ≟ Mapping =>
      merge2Map(Func.Input2(a1, a2))(MapFunc.translateBinaryMapping(func))
        .map(QC.inj)

    case LogicalPlan.InvokeFUnapply(func @ TernaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2, a3))
        if func.effect ≟ Mapping =>
      merge3Map(Func.Input3(a1, a2, a3))(MapFunc.translateTernaryMapping(func))
        .map(QC.inj)

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Reduction =>
      invokeReduction1(func, Func.Input1(a1)) map(QC.inj)

    case LogicalPlan.InvokeFUnapply(set.Take, Sized(a1, a2)) =>
      merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
        case SrcMerge(src, jb1, jb2) =>
          QC.inj(Take(src, jb1.mapSuspension(FI), jb2.mapSuspension(FI)))
      }

    case LogicalPlan.InvokeFUnapply(set.Drop, Sized(a1, a2)) =>
      merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
        case SrcMerge(src, jb1, jb2) =>
          QC.inj(Drop(src, jb1.mapSuspension(FI), jb2.mapSuspension(FI)))
      }

    case LogicalPlan.InvokeFUnapply(set.OrderBy, Sized(a1, a2, a3)) => {
      // we assign extra variables because of:
      // https://issues.scala-lang.org/browse/SI-5589
      // https://issues.scala-lang.org/browse/SI-7515

      (for {
        bucket0 <- findBucket(a1)
        (bucketSrc, bucket, thing) = bucket0
        merged0 <- merge3(a2, a3, bucketSrc)
      } yield {
        val Merge3(src, keys, order, buckets, arrays) = merged0
        val rebasedArrays = rebase(thing, arrays)

        val keysList: List[FreeMap[T]] = rebase(rebasedArrays, keys) match {
          case ConcatArraysN(as) => as
          case mf => List(mf)
        }

        // TODO handle errors
        val orderList: PlannerError \/ List[SortDir] = {
          val orderStrs: PlannerError \/ List[String] = rebase(rebasedArrays, order) match {
            case ConcatArraysN(as) => as.traverse(StrLit.unapply(_)) \/> InternalError("unsupported ordering type") // disjunctionify
            case StrLit(str) => List(str).right
            case _ => InternalError("unsupported ordering function").left
          }
          orderStrs.flatMap {
            _.traverse {
              case "ASC" => SortDir.Ascending.right
              case "DESC" => SortDir.Descending.right
              case _ => InternalError("unsupported ordering direction").left
            }
          }
        }

        val lists: PlannerError \/ List[(FreeMap[T], SortDir)] =
          orderList.map { keysList.zip(_) }

        (lists.map { pairs =>
          QC.inj(Sort(
            TJ.inj(src).embed,
            rebase(bucket, buckets),
            pairs))
        }).liftM[StateT[?[_], NameGen, ?]]
      }).join
    }

    case LogicalPlan.InvokeFUnapply(set.Filter, Sized(a1, a2)) =>
      mergeTheta[F[Inner]](a1, a2, {
        case SrcMerge(src, fm1, fm2) =>
          QC.inj(Map(
            QC.inj(Filter(TJ.inj(src).embed, fm2)).embed,
            fm1))
      })

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1)) if func.effect ≟ Squashing =>
      stateT(func match {
        case identity.Squash => QB.inj(SquashBucket(a1))
      })

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1)) if func.effect ≟ Expansion =>
      stateT(invokeExpansion1(func, Func.Input1(a1)))

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2)) if func.effect ≟ Expansion =>
      invokeExpansion2(func, Func.Input2(a1, a2)) map {
        SP.inj(_)
      }

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2)) if func.effect ≟ Transformation =>
      func match {
        case set.GroupBy =>
          mergeTheta[F[Inner]](a1, a2, {
            case SrcMerge(merged, source, bucket) =>
              QB.inj(GroupBy(TJ.inj(merged).embed, source, bucket))
          })
        case set.Union =>
          merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
            case SrcMerge(src, jb1, jb2) =>
              SP.inj(Union(src, jb1.mapSuspension(FI), jb2.mapSuspension(FI)))
          }
        case set.Intersect =>
          merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
            case SrcMerge(src, jb1, jb2) =>
              TJ.inj(ThetaJoin(src, jb1.mapSuspension(FI), jb2.mapSuspension(FI), equiJF, Inner, Free.point(LeftSide)))
          }
        case set.Except =>
          merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
            case SrcMerge(src, jb1, jb2) =>
              TJ.inj(ThetaJoin(
                src,
                jb1.mapSuspension(FI),
                jb2.mapSuspension(FI),
                Free.roll(Nullary(CommonEJson.inj(ejson.Bool[T[EJson]](false)).embed)),
                LeftOuter,
                Free.point(LeftSide)))
          }
      }

    case LogicalPlan.InvokeFUnapply(func @ TernaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2, a3))  if func.effect ≟ Transformation=>
      def invoke(tpe: JoinType): QSState[F[Inner]] =
        invokeThetaJoin(Func.Input3(a1, a2, a3), tpe).map(TJ.inj)

      func match {
        case set.InnerJoin      => invoke(Inner)
        case set.LeftOuterJoin  => invoke(LeftOuter)
        case set.RightOuterJoin => invoke(RightOuter)
        case set.FullOuterJoin  => invoke(FullOuter)
      }
    
    // TODO Let and FreeVar should not be hit
    case _ => ???
  }
}

class Optimize[T[_[_]]: Recursive: Corecursive: EqualT] extends Helpers[T] {

  // TODO: These optimizations should give rise to various property tests:
  //       • elideNopMap ⇒ no `Map(???, UnitF)`
  //       • normalize ⇒ a whole bunch, based on MapFuncs
  //       • elideNopJoin ⇒ no `ThetaJoin(???, UnitF, UnitF, LeftSide === RightSide, ???, ???)`
  //       • coalesceMaps ⇒ no `Map(Map(???, ???), ???)`
  //       • coalesceMapJoin ⇒ no `Map(ThetaJoin(???, …), ???)`

  // TODO: Turn `elideNop` into a type class?
  // def elideNopFilter[F[_]: Functor](implicit QC: QScriptCore[T, ?] :<: F):
  //     QScriptCore[T, T[F]] => F[T[F]] = {
  //   case Filter(src, Patts.True) => src.project
  //   case qc                      => QC.inj(qc)
  // }

  def elideNopMap[F[_]: Functor](implicit QC: QScriptCore[T, ?] :<: F):
      QScriptCore[T, T[F]] => F[T[F]] = {
    case Map(src, mf) if mf ≟ UnitF => src.project
    case x                          => QC.inj(x)
  }

  def elideNopJoin[F[_]](
    implicit Th: ThetaJoin[T, ?] :<: F, QC: QScriptCore[T, ?] :<: F):
      ThetaJoin[T, T[F]] => F[T[F]] = {
    case ThetaJoin(src, l, r, on, _, combine)
        if l ≟ Free.point(()) && r ≟ Free.point(()) && on ≟ equiJF =>
      QC.inj(Map(src, combine.void))
    case x => Th.inj(x)
  }

  def simplifyProjections:
      ProjectBucket[T, ?] ~> QScriptCore[T, ?] =
    new (ProjectBucket[T, ?] ~> QScriptCore[T, ?]) {
      def apply[A](proj: ProjectBucket[T, A]) = proj match {
        case BucketField(src, value, field) =>
          Map(src, Free.roll(MapFuncs.ProjectField(value, field)))
        case BucketIndex(src, value, index) =>
          Map(src, Free.roll(MapFuncs.ProjectIndex(value, index)))
      }
    }

  // TODO write extractor for inject
  //SourcedPathable[T, T[CoEnv[A,F, ?]]] => SourcedPathable[T, T[F]] = {
  //F[A] => A  ===> CoEnv[E, F, A] => A
  def coalesceMaps[F[_]: Functor](
    implicit QC: QScriptCore[T, ?] :<: F):
      QScriptCore[T, T[F]] => QScriptCore[T, T[F]] = {
    case x @ Map(Embed(src), mf) => QC.prj(src) match {
      case Some(Map(srcInner, mfInner)) => Map(srcInner, rebase(mf, mfInner))
      case _ => x
    }
    case x => x
  }

  def coalesceMapJoin[F[_]: Functor](
    implicit QC: QScriptCore[T, ?] :<: F, TJ: ThetaJoin[T, ?] :<: F):
      QScriptCore[T, T[F]] => F[T[F]] = {
    case x @ Map(Embed(src), mf) =>
      TJ.prj(src).fold(
        QC.inj(x))(
        tj => TJ.inj(ThetaJoin.combine.modify(mf >> (_: JoinFunc[T]))(tj)))
    case x => QC.inj(x)
  }

  // The order of optimizations is roughly this:
  // - elide NOPs
  // - purify (elide buckets)
  // - read conversion given to us by the filesystem
  // - convert any remaning projects to maps
  // - coalesce nodes
  // - normalize mapfunc
  // TODO: Apply this to FreeQS structures.
  def applyAll[F[_]: Functor](
    implicit QC: QScriptCore[T, ?] :<: F,
             TJ: ThetaJoin[T, ?] :<: F,
             PB: ProjectBucket[T, ?] :<: F):
      F[T[F]] => F[T[F]] =
    liftFG(elideNopJoin[F]) ⋙
    liftFG(elideNopMap[F]) ⋙
    quasar.fp.free.injectedNT[F](simplifyProjections) ⋙
    liftFF(coalesceMaps[F]) ⋙
    liftFG(coalesceMapJoin[F]) ⋙
    Normalizable[F].normalize
}
