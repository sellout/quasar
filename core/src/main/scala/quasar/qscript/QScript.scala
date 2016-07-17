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
import quasar.qscript.MapFunc._
import quasar.qscript.MapFuncs._
import quasar.Planner._
import quasar.Predef._
import quasar.std.StdLib._

import matryoshka._, Recursive.ops._
import matryoshka.patterns._
import scalaz.{:+: => _, Divide => _, _}, Scalaz._, Inject._, Leibniz._
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
           mergeable:  Mergeable.Aux[T, F],
           eq:         Delay[Equal, F])
    extends Helpers[T] {

  val prov = new Provenance[T]

  type Target[A] = EnvT[Ann[T], F, A]
  type TargetT = Target[T[Target]]
  type FreeEnv = Free[Target, Unit]

  def DeadEndTarget(deadEnd: DeadEnd): TargetT =
    EnvT[Ann[T], F, T[Target]]((EmptyAnn[T], DE.inj(Const[DeadEnd, T[Target]](deadEnd))))

  val RootTarget: TargetT = DeadEndTarget(Root)
  val EmptyTarget: TargetT = DeadEndTarget(Empty)

  type Envs = List[Target[Unit]]

  case class ZipperSides(
    lSide: FreeMap[T],
    rSide: FreeMap[T])

  case class ZipperTails(
    lTail: Envs,
    rTail: Envs)

  case class ZipperAcc(
    acc: Envs,
    sides: ZipperSides,
    tails: ZipperTails)

  def linearize[F[_]: Functor: Foldable]: Algebra[F, List[F[Unit]]] =
    fl => fl.void :: fl.fold

  def linearizeEnv[E, F[_]: Functor: Foldable]:
      Algebra[EnvT[E, F, ?], List[EnvT[E, F, Unit]]] =
    fl => fl.void :: fl.lower.fold


  def delinearizeInner[A]: Coalgebra[Target, List[Target[A]]] = {
    case Nil => EnvT((EmptyAnn, DE.inj(Const[DeadEnd, List[Target[A]]](Root))))
    case h :: t => h.map(_ => t)
  }

  def delinearizeTargets[F[_]: Functor, A]:
      ElgotCoalgebra[Unit \/ ?, Target, List[Target[A]]] = {
    case Nil    => ().left
    case h :: t => h.map(_ => t).right
  }

  val consZipped: Algebra[ListF[Target[Unit], ?], ZipperAcc] = {
    case NilF() => ZipperAcc(Nil, ZipperSides(UnitF[T], UnitF[T]), ZipperTails(Nil, Nil))
    case ConsF(head, ZipperAcc(acc, sides, tails)) => ZipperAcc(head :: acc, sides, tails)
  }

  // E, M, F, A => A => M[E[F[A]]]
  val zipper: ElgotCoalgebra[
      ZipperAcc \/ ?,
      ListF[Target[Unit], ?],
      (ZipperSides, ZipperTails)] = {
    case (zs @ ZipperSides(lm, rm), zt @ ZipperTails(l :: ls, r :: rs)) =>
      mergeable.mergeSrcs(lm, rm, l, r).fold[ZipperAcc \/ ListF[Target[Unit], (ZipperSides, ZipperTails)]](
        ZipperAcc(Nil, zs, zt).left) {
        case SrcMerge(inn, lmf, rmf) =>
          ConsF(inn, (ZipperSides(lmf, rmf), ZipperTails(ls, rs))).right[ZipperAcc]
      }
    case (sides, tails) =>
      ZipperAcc(Nil, sides, tails).left
  }

  type MergeResult = (T[Target], Free[Target, Unit], Free[Target, Unit])

  def merge(left: T[Target], right: T[Target]): MergeResult = {
    val lLin: Envs = left.cata(linearizeEnv).reverse
    val rLin: Envs = right.cata(linearizeEnv).reverse

    val ZipperAcc(common, ZipperSides(lMap, rMap), ZipperTails(lTail, rTail)) =
      elgot(
        (ZipperSides(UnitF[T], UnitF[T]), ZipperTails(lLin, rLin)))(
        consZipped, zipper)

    val leftF =
      foldIso(CoEnv.freeIso[Unit, Target])
        .get(lTail.reverse.ana[T, CoEnv[Unit, Target, ?]](delinearizeTargets[F, Unit] >>> (CoEnv(_))))

    val rightF =
      foldIso(CoEnv.freeIso[Unit, Target])
        .get(rTail.reverse.ana[T, CoEnv[Unit, Target, ?]](delinearizeTargets[F, Unit] >>> (CoEnv(_))))

    val commonSrc: T[Target] =
      common.reverse.ana[T, Target](delinearizeInner)

    (commonSrc, leftF, rightF)
  }

  def someAnn[A](v: Target[Free[Target, A]] \/ A, default: T[Target]) =
    v.fold(_.ask, κ(default.project.ask))

  /** This unifies a pair of sources into a single one, with additional
    * expressions to access the combined bucketing info, as well as the left and
    * right values.
    */
  def autojoin(left: T[Target], right: T[Target]):
      (F[T[Target]], List[FreeMap[T]], FreeMap[T], FreeMap[T]) = {
    val (src, lfree, rfree) = merge(left, right)
    val lcomp = lfree.resume
    val rcomp = rfree.resume
    val (combine, lacc, racc) =
      concat[T, JoinSide](Free.point(LeftSide), Free.point(RightSide))

    val lann = someAnn(lcomp, src)
    val rann = someAnn(rcomp, src)
    val commonProvLength = src.project.ask.provenance.length
    (TJ.inj(ThetaJoin(
      src,
      lfree.mapSuspension(FI.compose(envtLowerNT)),
      rfree.mapSuspension(FI.compose(envtLowerNT)),
      // FIXME: not quite right – e.g., if there is a reduction in a branch the
      //        condition won’t line up.
      Free.roll(Eq(
        concatBuckets(lann.provenance.drop(lann.provenance.length - commonProvLength))._1.map(κ(LeftSide)),
        concatBuckets(rann.provenance.drop(rann.provenance.length - commonProvLength))._1.map(κ(RightSide)))),
      Inner,
      combine)),
      prov.joinProvenances(
        lann.provenance.map(_ >> lacc),
        rann.provenance.map(_ >> racc)),
      lann.values >> lacc,
      rann.values >> racc)
  }

  /** A convenience for a pair of autojoins, does the same thing, but returns
    * access to all three values.
    */
  def autojoin3(left: T[Target], center: T[Target], right: T[Target]):
      (F[T[Target]], List[FreeMap[T]], FreeMap[T], FreeMap[T], FreeMap[T]) = {
    val (lsrc, lbuckets, lval, cval) = autojoin(left, center)
    val (fullSrc, fullBuckets, bval, rval) =
      autojoin(EnvT((Ann[T](lbuckets, UnitF), lsrc)).embed, right)

    (fullSrc, fullBuckets, bval >> lval, bval >> cval, rval)
  }

  def merge2Map(
    values: Func.Input[T[Target], nat._2])(
    func: (FreeMap[T], FreeMap[T]) => MapFunc[T, FreeMap[T]]):
      TargetT = {
    val (src, buckets, lval, rval) = autojoin(values(0), values(1))
    val (bucks, newBucks) = concatBuckets(buckets)
    val (merged, b, v) = concat(bucks, func(lval, rval).embed)

    EnvT((
      Ann[T](newBucks.map(_ >> b), v),
      QC.inj(Map(EnvT((EmptyAnn[T], src)).embed, merged))))
  }

  // TODO unify with `merge2Map`
  def merge3Map(
    values: Func.Input[T[Target], nat._3])(
    func: (FreeMap[T], FreeMap[T], FreeMap[T]) => MapFunc[T, FreeMap[T]]):
      Target[T[Target]] = {
    val (src, buckets, lval, cval, rval) = autojoin3(values(0), values(1), values(2))
    val (bucks, newBucks) = concatBuckets(buckets)
    val (merged, b, v) = concat(bucks, func(lval, cval, rval).embed)

    EnvT((
      Ann[T](newBucks.map(_ >> b), v),
      QC.inj(Map(EnvT((EmptyAnn[T], src)).embed, merged))))
  }

  def shiftValues(input: T[Target], f: FreeMap[T] => MapFunc[T, FreeMap[T]]):
      Target[T[Target]] = {
    val Ann(provs, value) = input.project.ask

    val (buck, newBucks) = concatBuckets(provs)
    val (shiftedBuck, shiftAccess, buckAccess) =
      concat(Free.roll(f(value)), buck)
    val (merged, fullBuckAccess, valAccess) = concat(shiftedBuck, value)

    EnvT((
      Ann(
        prov.shiftMap(shiftAccess >> fullBuckAccess) ::
          newBucks.map(_ >> buckAccess >> fullBuckAccess),
        UnitF),
      SP.inj(LeftShift(
        EnvT((EmptyAnn[T], QC.inj(Map(input, merged)))).embed,
        valAccess,
        Free.point(RightSide)))))
  }

  def shiftIds(input: T[Target], f: FreeMap[T] => MapFunc[T, FreeMap[T]]):
      Target[T[Target]] = {
    val Ann(provs, value) = input.project.ask

    val (buck, newBucks) = concatBuckets(provs)
    val (merged, buckAccess, valAccess) = concat(buck, Free.roll(f(value)))

    EnvT((
      Ann(prov.shiftMap(valAccess) :: newBucks.map(_ >> buckAccess), UnitF),
      SP.inj(LeftShift(
        EnvT((EmptyAnn[T], QC.inj(Map(input, merged)))).embed,
        valAccess,
        Free.point(RightSide)))))
  }

  def flatten(input: Target[T[Target]]): Target[T[Target]] = {
    val EnvT((Ann(buckets, value), fa)) = input
    EnvT((Ann(prov.nestProvenances(buckets), value), fa))
  }

  // NB: More compilicated LeftShifts are generated as an optimization:
  // before: ThetaJoin(cs, Map((), mf), LeftShift((), struct, repair), comb)
  // after: LeftShift(cs, struct, comb.flatMap(LeftSide => mf.map(_ => LeftSide), RS => repair))
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
      case structural.FlattenMap =>
        flatten(shiftValues(values(0), DupMapKeys(_)))
      case structural.FlattenArray =>
        flatten(shiftValues(values(0), DupArrayIndices(_)))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x:foo) - foo
      //   id(p, x:bar) - bar
      //   id(p, y:foo) - foo
      //   id(p, y:bar) - bar
      // (one bucket)
      case structural.FlattenMapKeys =>
        flatten(shiftIds(values(0), DupMapKeys(_)))
      case structural.FlattenArrayIndices =>
        flatten(shiftIds(values(0), DupArrayIndices(_)))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x, foo) - 12
      //   id(p, x, bar) - 18
      //   id(p, y, foo) - 1
      //   id(p, y, bar) - 2
      // (two buckets)
      case structural.ShiftMap   => shiftValues(values(0), DupMapKeys(_))
      case structural.ShiftArray => shiftValues(values(0), DupArrayIndices(_))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x, foo) - foo
      //   id(p, x, bar) - bar
      //   id(p, y, foo) - foo
      //   id(p, y, bar) - bar
      // (two buckets)
      case structural.ShiftMapKeys      => shiftIds(values(0), DupMapKeys(_))
      case structural.ShiftArrayIndices => shiftIds(values(0), DupArrayIndices(_))
    }

  def invokeExpansion2(func: BinaryFunc, values: Func.Input[T[Target], nat._2]):
      TargetT =
    func match {
      // TODO left shift `freshName + range value` values onto provenance
      case set.Range =>
        val (src, buckets, lval, rval) = autojoin(values(0), values(1))
        val (bucksArray, newBucks) = concatBuckets(buckets)
        val (merged, b, v) = concat[T, Unit](bucksArray, Free.roll(Range(lval, rval)))

        EnvT((
          Ann[T](/*Concat(freshName("range"), v) ::*/ newBucks.map(b >> _), v),
          SP.inj(LeftShift(
            EnvT((EmptyAnn[T], src)).embed,
            merged,
            Free.point[MapFunc[T, ?], JoinSide](RightSide)))))
    }

  def invokeReduction1(
    func: UnaryFunc,
    values: Func.Input[T[Target], nat._1]):
      TargetT = {
    val EnvT((Ann(provs, reduce), src)): TargetT = values(0).project
    // NB: If there’s no provenance, then there’s nothing to reduce. We’re
    //     already holding a single value.
    provs.tailOption.fold(values(0).project) { tail =>
      val (newProvs, provAccess) = concatBuckets(tail)

      EnvT[Ann[T], F, T[Target]]((
        Ann[T](
          provAccess.map(_ >> Free.roll(ProjectIndex(UnitF[T], IntLit[T, Unit](0)))),
          Free.roll(ProjectIndex(UnitF[T], IntLit[T, Unit](1)))),
        QC.inj(Reduce[T, T[Target], nat._1](
          EnvT((EmptyAnn[T], src)).embed,
          newProvs,
          Sized[List](
            ReduceFuncs.Arbitrary(newProvs),
            ReduceFunc.translateReduction[FreeMap[T]](func)(reduce)),
          Free.roll(ConcatArrays(
            Free.roll(MakeArray(Free.point(Fin[nat._0, nat._2]))),
            Free.roll(MakeArray(Free.point(Fin[nat._1, nat._2])))))))))
    }
  }

  def invokeThetaJoin(
    values: Func.Input[T[Target], nat._3],
    tpe: JoinType):
      TargetT = ??? // {
  //   val (src, buckets, lBranch, rBranch, cond) = autojoin3(values(0), values(1), values(2))
  //   val (buck, newBucks) = concatBuckets(buckets)

  //   val left: F[Free[F, Unit]] = QC.inj(Map[T, Free[F, Unit]](Free.point[F, Unit](()), lBranch))
  //   val right: F[Free[F, Unit]] = QC.inj(Map[T, Free[F, Unit]](Free.point[F, Unit](()), rBranch))

  //   val (concatted, buckAccess, valAccess): (FreeMap[T], FreeMap[T], FreeMap[T]) =
  //     concat(
  //       buck: FreeMap[T],
  //       Free.roll(ConcatMaps(
  //         Free.roll(MakeMap(StrLit[T, JoinSide]("left"), Free.point[MapFunc[T, ?], JoinSide](LeftSide))),
  //         Free.roll(MakeMap(StrLit[T, JoinSide]("right"), Free.point[MapFunc[T, ?], JoinSide](RightSide))))))

  //   EnvT((
  //     Ann[T](newBucks.map(_ >> buckAccess), valAccess),
  //     TJ.inj(ThetaJoin(
  //       EnvT((EmptyAnn[T], src)).embed,
  //       Free.roll(left).mapSuspension(FI),
  //       Free.roll(right).mapSuspension(FI),
  //       cond,
  //       tpe,
  //       concatted))))
  // }

  def ProjectTarget(prefix: TargetT, field: FreeMap[T]): TargetT = {
    val Ann(provenance, values) = prefix.ask
    EnvT[Ann[T], F, T[Target]]((
      Ann[T](prov.projectField(field) :: provenance, values),
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

  // TODO: Replace disjunction with validation.
  def lpToQScript: LogicalPlan[T[Target]] => PlannerError \/ TargetT = {
    case LogicalPlan.ReadF(path) =>
      pathToProj(path).right

    case LogicalPlan.ConstantF(data) =>
      val res = QC.inj(Map(
        RootTarget.embed,
        Free.roll[MapFunc[T, ?], Unit](Nullary[T, FreeMap[T]](fromData(data).fold(
          error => CommonEJson.inj(ejson.Str[T[EJson]](error)).embed,
          ι)))))
        EnvT((EmptyAnn[T], res)).right

    case LogicalPlan.FreeF(name) =>
      (Planner.UnboundVariable(name): PlannerError).left[TargetT]

    case LogicalPlan.LetF(name, form, body) =>
      (Planner.InternalError("un-elided Let"): PlannerError).left[TargetT]

    case LogicalPlan.TypecheckF(expr, typ, cont, fallback) =>
      merge3Map(Func.Input3(expr, cont, fallback))(Guard(_, typ, _, _)).right[PlannerError]

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Mapping =>
      val Ann(buckets, value) = a1.project.ask
      val (buck, newBuckets) = concatBuckets(buckets)
      val (mf, bucketAccess, valAccess) =
        concat[T, Unit](buck, Free.roll(MapFunc.translateUnaryMapping(func)(UnitF[T])))

      EnvT((
        Ann(newBuckets.map(_ >> bucketAccess), valAccess),
        QC.inj(Map(a1, mf)))).right

    case LogicalPlan.InvokeFUnapply(structural.ObjectProject, Sized(a1, a2)) =>
      val (src, buckets, lval, rval) = autojoin(a1, a2)
      EnvT((
        Ann[T](buckets, UnitF[T]),
        PB.inj(BucketField(EnvT((EmptyAnn[T], src)).embed, lval, rval)))).right

    case LogicalPlan.InvokeFUnapply(structural.ArrayProject, Sized(a1, a2)) =>
      val (src, buckets, lval, rval) = autojoin(a1, a2)
      EnvT((
        Ann[T](buckets, UnitF[T]),
        PB.inj(BucketIndex(EnvT((EmptyAnn[T], src)).embed, lval, rval)))).right

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2))
        if func.effect ≟ Mapping =>
      merge2Map(Func.Input2(a1, a2))(MapFunc.translateBinaryMapping(func)).right[PlannerError]

    case LogicalPlan.InvokeFUnapply(func @ TernaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2, a3))
        if func.effect ≟ Mapping =>
      merge3Map(Func.Input3(a1, a2, a3))(MapFunc.translateTernaryMapping(func)).right[PlannerError]

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Reduction =>
      invokeReduction1(func, Func.Input1(a1)).right

    case LogicalPlan.InvokeFUnapply(set.Take, Sized(a1, a2)) =>
      val (src, buckets, lval, rval) = autojoin(a1, a2)
      val left: F[Free[F, Unit]] = QC.inj(Map[T, Free[F, Unit]](Free.point[F, Unit](()), lval))
      val right: F[Free[F, Unit]] = QC.inj(Map[T, Free[F, Unit]](Free.point[F, Unit](()), rval))
      EnvT((
        Ann[T](buckets, UnitF[T]),
        QC.inj(Take(
          EnvT((EmptyAnn[T], src)).embed,
          Free.roll(left).mapSuspension(FI),
          Free.roll(left).mapSuspension(FI))))).right

    case LogicalPlan.InvokeFUnapply(set.Drop, Sized(a1, a2)) =>
      val (src, buckets, lval, rval) = autojoin(a1, a2)
      val left: F[Free[F, Unit]] = QC.inj(Map[T, Free[F, Unit]](Free.point[F, Unit](()), lval))
      val right: F[Free[F, Unit]] = QC.inj(Map[T, Free[F, Unit]](Free.point[F, Unit](()), rval))
      EnvT((
        Ann[T](buckets, UnitF[T]),
        QC.inj(Drop(
          EnvT((EmptyAnn[T], src)).embed,
          Free.roll(left).mapSuspension(FI),
          Free.roll(left).mapSuspension(FI))))).right

    case LogicalPlan.InvokeFUnapply(set.OrderBy, Sized(a1, a2, a3)) => ??? // {
    //   (for {
    //     bucket0 <- findBucket(a1)
    //     (bucketSrc, bucket, thing) = bucket0
    //     merged0 <- merge3(a2, a3, bucketSrc)
    //   } yield {
    //     val Merge3(src, keys, order, buckets, arrays) = merged0
    //     val rebasedArrays = rebase(thing, arrays)

    //     val keysList: List[FreeMap[T]] = rebase(rebasedArrays, keys) match {
    //       case ConcatArraysN(as) => as
    //       case mf => List(mf)
    //     }

    //     // TODO handle errors
    //     val orderList: PlannerError \/ List[SortDir] = {
    //       val orderStrs: PlannerError \/ List[String] = rebase(rebasedArrays, order) match {
    //         case ConcatArraysN(as) => as.traverse(StrLit.unapply(_)) \/> InternalError("unsupported ordering type") // disjunctionify
    //         case StrLit(str) => List(str).right
    //         case _ => InternalError("unsupported ordering function").left
    //       }
    //       orderStrs.flatMap {
    //         _.traverse {
    //           case "ASC" => SortDir.Ascending.right
    //           case "DESC" => SortDir.Descending.right
    //           case _ => InternalError("unsupported ordering direction").left
    //         }
    //       }
    //     }

    //     val lists: PlannerError \/ List[(FreeMap[T], SortDir)] =
    //       orderList.map { keysList.zip(_) }

    //     lists.map(pairs =>
    //       QC.inj(Sort(
    //         TJ.inj(src).embed,
    //         rebase(bucket, buckets),
    //         pairs)))
    //   }).join
    // }

    case LogicalPlan.InvokeFUnapply(set.Filter, Sized(a1, a2)) =>
      val (src, buckets, lval, rval) = autojoin(a1, a2)
      EnvT((
        Ann[T](buckets, UnitF[T]),
        QC.inj(Map(
          EnvT((
            EmptyAnn[T],
            QC.inj(Filter(EnvT((EmptyAnn[T], src)).embed, rval)))).embed,
          lval)))).right
      //mergeTheta[F[Inner]](a1, a2, {
      //  case SrcMerge(src, fm1, fm2) =>
      //    QC.inj(Map(
      //      QC.inj(Filter(TJ.inj(src).embed, fm2)).embed,
      //      fm1))
      //})

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Squashing =>
      val Ann(buckets, value) = a1.project.ask
      val (buck, newBuckets) = concatBuckets(prov.squashProvenances(buckets))
      val (mf, buckAccess, valAccess) = concat(buck, value)

      EnvT((
        Ann(newBuckets.map(_ >> buckAccess), valAccess),
        QC.inj(Map(a1, mf)))).right

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Expansion =>
      invokeExpansion1(func, Func.Input1(a1)).right

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2))
        if func.effect ≟ Expansion =>
      invokeExpansion2(func, Func.Input2(a1, a2)).right

    case LogicalPlan.InvokeFUnapply(set.GroupBy, Sized(a1, a2)) => ??? // TODO
    case LogicalPlan.InvokeFUnapply(set.Union, Sized(a1, a2)) =>
      val (src, lfree, rfree) = merge(a1, a2)
      EnvT((
        Ann(
          prov.unionProvenances(
            someAnn(lfree.resume, src).provenance,
            someAnn(rfree.resume, src).provenance),
          UnitF), // FIXME: Not UnitF
        SP.inj(Union(src,
          lfree.mapSuspension(FI.compose(envtLowerNT)),
          rfree.mapSuspension(FI.compose(envtLowerNT)))))).right

    case LogicalPlan.InvokeFUnapply(set.Intersect, Sized(a1, a2)) => ??? // TODO
      //merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
      //  case SrcMerge(src, jb1, jb2) =>
      //    TJ.inj(ThetaJoin(src, jb1.mapSuspension(FI), jb2.mapSuspension(FI), equiJF, Inner, Free.point(LeftSide)))
      //}

    case LogicalPlan.InvokeFUnapply(set.Except, Sized(a1, a2)) => ??? // TODO
      //merge(a1, a2).mapK(_.right[PlannerError]) ∘ {
      //  case SrcMerge(src, jb1, jb2) =>
      //    TJ.inj(ThetaJoin(
      //      src,
      //      jb1.mapSuspension(FI),
      //      jb2.mapSuspension(FI),
      //      Free.roll(Nullary(CommonEJson.inj(ejson.Bool[T[EJson]](false)).embed)),
      //      LeftOuter,
      //      Free.point(LeftSide)))
      //}

    case LogicalPlan.InvokeFUnapply(func @ TernaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2, a3))
        if func.effect ≟ Transformation=>
      def invoke(tpe: JoinType) =
        invokeThetaJoin(Func.Input3(a1, a2, a3), tpe).right

      func match {
        case set.InnerJoin      => invoke(Inner)
        case set.LeftOuterJoin  => invoke(LeftOuter)
        case set.RightOuterJoin => invoke(RightOuter)
        case set.FullOuterJoin  => invoke(FullOuter)
      }
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
  // - read conversion given to us by the filesystem
  // - convert any remaning projects to maps
  // - coalesce nodes
  // - normalize mapfunc
  // TODO: Apply this to FreeQS structures.
  def applyAll[F[_]: Functor: Normalizable](
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
