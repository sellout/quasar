/*
 * Copyright 2014–2017 SlamData Inc.
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

import quasar.Predef._
import quasar.contrib.pathy.APath
import quasar.fp._
import quasar.fs.MonadFsErr
import quasar.qscript.MapFunc._
import quasar.qscript.MapFuncs._

import matryoshka._
import matryoshka.data._
import matryoshka.implicits._
import matryoshka.patterns._
import scalaz.{:+: => _, Divide => _, _},
  Inject.{ reflexiveInjectInstance => _, _ },
  Leibniz._,
  Scalaz._

class Rewrite[T[_[_]]: BirecursiveT: EqualT] extends TTypes[T] {
  def flattenArray[A: Show](array: ConcatArrays[T, FreeMapA[A]]): List[FreeMapA[A]] = {
    def inner(jf: FreeMapA[A]): List[FreeMapA[A]] =
      jf.resume match {
        case -\/(ConcatArrays(lhs, rhs)) => inner(lhs) ++ inner(rhs)
        case _                           => List(jf)
      }
    inner(Free.roll(array))
  }

  def rebuildArray[A](funcs: List[FreeMapA[A]]): FreeMapA[A] = {
    def inner(funcs: List[FreeMapA[A]]): FreeMapA[A] = funcs match {
      case Nil          => Free.roll(EmptyArray[T, FreeMapA[A]])
      case func :: Nil  => func
      case func :: rest => Free.roll(ConcatArrays(inner(rest), func))
    }
    inner(funcs.reverse)
  }

  def rewriteShift(idStatus: IdStatus, repair: JoinFunc)
      : Option[(IdStatus, JoinFunc)] =
    (idStatus ≟ IncludeId).option[Option[(IdStatus, JoinFunc)]] {
      def makeRef(idx: Int): JoinFunc =
        Free.roll[MapFunc, JoinSide](ProjectIndex(RightSideF, IntLit(idx)))

      val zeroRef: JoinFunc = makeRef(0)
      val oneRef: JoinFunc = makeRef(1)
      val rightCount: Int = repair.elgotPara(count(RightSideF))

      if (repair.elgotPara(count(oneRef)) ≟ rightCount)
        // all `RightSide` access is through `oneRef`
        (ExcludeId, repair.transApoT(substitute[JoinFunc](oneRef, RightSideF))).some
      else if (repair.elgotPara(count(zeroRef)) ≟ rightCount)
        // all `RightSide` access is through `zeroRef`
        (IdOnly, repair.transApoT(substitute[JoinFunc](zeroRef, RightSideF))).some
      else
        None
    }.join

  // TODO: make this simply a transform itself, rather than a full traversal.
  def shiftRead[F[_]: Functor, G[_]: Traverse]
    (implicit QC: QScriptCore :<: G,
              TJ: ThetaJoin :<: G,
              SR: Const[ShiftedRead[APath], ?] :<: G,
              GI: Injectable.Aux[G, QScriptTotal],
              S: ShiftRead.Aux[T, F, G],
              C: Coalesce.Aux[T, G, G],
              N: Normalizable[G])
      : T[F] => T[G] = {
    _.codyna(
      normalize[G] >>> liftFG(injectRepeatedly(C.coalesceSR[G, APath](idPrism))) >>> (_.embed),
      ((_: T[F]).project) >>> (S.shiftRead(idPrism.reverseGet)(_)))
  }

  def simplifyJoinOnShiftRead[F[_]: Functor, G[_]: Traverse, H[_]: Functor]
    (implicit QC: QScriptCore :<: G,
              TJ: ThetaJoin :<: G,
              SR: Const[ShiftedRead[APath], ?] :<: G,
              GI: Injectable.Aux[G, QScriptTotal],
              S: ShiftRead.Aux[T, F, G],
              J: SimplifyJoin.Aux[T, G, H],
              C: Coalesce.Aux[T, G, G],
              N: Normalizable[G])
      : T[F] => T[H] =
    shiftRead[F, G].apply(_).transCata[T[H]](J.simplifyJoin(idPrism.reverseGet))


  // TODO: These optimizations should give rise to various property tests:
  //       • elideNopMap ⇒ no `Map(???, HoleF)`
  //       • normalize ⇒ a whole bunch, based on MapFuncs
  //       • elideNopJoin ⇒ no `ThetaJoin(???, HoleF, HoleF, LeftSide === RightSide, ???, ???)`
  //       • coalesceMaps ⇒ no `Map(Map(???, ???), ???)`
  //       • coalesceMapJoin ⇒ no `Map(ThetaJoin(???, …), ???)`

  def elideNopQC[F[_]: Functor, G[_]: Functor]
    (FtoG: F ~> G)
    (implicit QC: QScriptCore :<: F)
      : QScriptCore[T[G]] => G[T[G]] = {
    case Filter(Embed(src), BoolLit(true)) => src
    case Map(Embed(src), mf) if mf ≟ HoleF => src
    case x                                 => FtoG(QC.inj(x))
  }

  def unifySimpleBranches[F[_], A]
    (src: A, l: FreeQS, r: FreeQS, combine: JoinFunc)
    (rebase: FreeQS => A => Option[A])
    (implicit
      QC: QScriptCore :<: F,
      FI: Injectable.Aux[F, QScriptTotal])
      : Option[F[A]] = {
    val UnrefedSrc: QScriptTotal[FreeQS] =
      Inject[QScriptCore, QScriptTotal] inj Unreferenced[T, FreeQS]()

    (l.resumeTwice, r.resumeTwice) match {
      case (-\/(m1), -\/(m2)) =>
        (FI.project(m1) >>= QC.prj, FI.project(m2) >>= QC.prj) match {
          // both sides only map over the same data
          case (Some(Map(\/-(SrcHole), mf1)), Some(Map(\/-(SrcHole), mf2))) =>
            QC.inj(Map(src, combine >>= {
              case LeftSide  => mf1
              case RightSide => mf2
            })).some
          // left side maps over the data while the right side shifts the same data
          case (Some(Map(\/-(SrcHole), mf1)), Some(LeftShift(-\/(values), struct, status, repair))) =>
            FI.project(values) >>= QC.prj match {
              case Some(Map(src2, mf2)) if src2 ≟ HoleQS =>
                QC.inj(LeftShift(src,
                  struct >> mf2,
                  status,
                  combine >>= {
                    case LeftSide  => mf1 >> LeftSideF  // references `src`
                    case RightSide => repair >>= {
                      case LeftSide  => mf2 >> LeftSideF
                      case RightSide => RightSideF
                    }
                  })).some
              case _ => None
            }
          // right side maps over the data while the left side shifts the same data
          case (Some(LeftShift(-\/(values), struct, status, repair)), Some(Map(\/-(SrcHole), mf2))) =>
            FI.project(values) >>= QC.prj match {
              case Some(Map(src1, mf1)) if src1 ≟ HoleQS =>
                QC.inj(LeftShift(src,
                  struct >> mf1,
                  status,
                  combine >>= {
                    case LeftSide  => repair >>= {
                      case LeftSide  => mf1 >> LeftSideF
                      case RightSide => RightSideF
                    }
                    case RightSide => mf2 >> LeftSideF  // references `src`
                  })).some
              case _ => None
            }
          // neither side references the src
          case (Some(Map(-\/(src1), mf1)), Some(Map(-\/(src2), mf2)))
              if src1 ≟ UnrefedSrc && src2 ≟ UnrefedSrc =>
            QC.inj(Map(src, combine >>= {
              case LeftSide  => mf1
              case RightSide => mf2
            })).some
          // only the right side references the source
          case (Some(Map(-\/(src1), mf1)), _) if src1 ≟ UnrefedSrc =>
            rebase(r)(src).map(
              tf => QC.inj(Map(tf, combine >>= {
                case LeftSide  => mf1
                case RightSide => HoleF
              })))
          case (Some(Unreferenced()), _) =>
            rebase(r)(src) >>= (
              tf => combine.traverseM[Option, Hole] {
                case LeftSide  => None
                case RightSide => HoleF.some
              } ∘ (comb => QC.inj(Map(tf, comb))))
          // only the left side references the source
          case (_, Some(Map(-\/(src2), mf2))) if src2 ≟ UnrefedSrc =>
            rebase(l)(src).map(
              tf => QC.inj(Map(tf, combine >>= {
                case LeftSide  => HoleF
                case RightSide => mf2
              })))
          case (_, Some(Unreferenced())) =>
            rebase(l)(src) >>= (
              tf => combine.traverseM[Option, Hole] {
                case LeftSide  => HoleF.some
                case RightSide => None
              } ∘ (comb => QC.inj(Map(tf, comb))))
          case (_, _) => None
        }
      // one side maps over the src while the other passes the src untouched
      case (-\/(m1), \/-(SrcHole)) => (FI.project(m1) >>= QC.prj) match {
        case Some(Map(\/-(SrcHole), mf1)) =>
          QC.inj(Map(src, combine >>= {
            case LeftSide  => mf1
            case RightSide => HoleF
          })).some
        case Some(Unreferenced()) =>
          combine.traverseM[Option, Hole] {
            case LeftSide  => None
            case RightSide => HoleF.some
          } ∘ (comb => QC.inj(Map(src, comb)))
        case _ => None
      }
      // the other side maps over the src while the one passes the src untouched
      case (\/-(SrcHole), -\/(m2)) => (FI.project(m2) >>= QC.prj) match {
        case Some(Map(\/-(SrcHole), mf2)) =>
          QC.inj(Map(src, combine >>= {
            case LeftSide  => HoleF
            case RightSide => mf2
          })).some
        case Some(Unreferenced()) =>
          combine.traverseM[Option, Hole] {
            case LeftSide  => HoleF.some
            case RightSide => None
          } ∘ (comb => QC.inj(Map(src, comb)))
        case _ => None
      }
      // both sides are the src
      case (\/-(SrcHole), \/-(SrcHole)) =>
        QC.inj(Map(src, combine.as(SrcHole))).some
      case (_, _) => None
    }
  }

  def unifySimpleBranchesCoEnv[F[_], A]
    (src: A, l: FreeQS, r: FreeQS, combine: JoinFunc)
    (rebase: FreeQS => A => Option[A])
    (implicit
      QC: QScriptCore :<: F,
      FI: Injectable.Aux[F, QScriptTotal])
      : Option[CoEnv[Hole, F, A]] =
    unifySimpleBranches(src, l, r, combine)(rebase)(QC, FI).map(fa => CoEnv(\/-(fa)))

  // FIXME: This really needs to ensure that the condition is that of an
  //        autojoin, otherwise it’ll elide things that are truly meaningful.
  def elideNopJoin[F[_], A]
    (rebase: FreeQS => A => Option[A])
    (implicit QC: QScriptCore :<: F, FI: Injectable.Aux[F, QScriptTotal])
      : ThetaJoin[A] => Option[F[A]] = {
    case ThetaJoin(s, l, r, _, _, combine) => unifySimpleBranches[F, A](s, l, r, combine)(rebase)(QC, FI)
    case _                                 => None
  }

  /** Pull more work to _after_ count operations, limiting the dataset. */
  // TODO: we should be able to pull _most_ of a Reduce repair function to after a Subset
  def swapMapSubset[F[_], G[_]: Functor]
    (FtoG: F ~> G)
    (implicit QC: QScriptCore :<: F)
      : QScriptCore[T[G]] => Option[QScriptCore[T[G]]] = {

    val FI = Injectable.inject[QScriptCore, QScriptTotal]

    {
      case Subset(src, from, sel, count) =>
        from.resume.swap.toOption >>= (FI project _) >>= {
          case Map(fromInner, mf) =>
            Map(FtoG(QC.inj(Subset(src, fromInner, sel, count))).embed, mf).some
          case _ => None
        }
      case _ => None
    }
  }

  def compactQC = λ[QScriptCore ~> (Option ∘ QScriptCore)#λ] {
    case LeftShift(src, struct, id, repair) =>
      rewriteShift(id, repair) ∘ (xy => LeftShift(src, struct, xy._1, xy._2))

    case Reduce(src, bucket, reducers, repair0) =>
      // `indices`: the indices into `reducers` that are used
      val used    = repair0.map(_.idx).toSet
      val indices = reducers.indices filter used
      val repair  = repair0 map (r => r.copy(indices indexOf r.idx))
      val done    = repair ≟ repair0 || (repair element ReduceIndex.Empty)

      !done option Reduce(src, bucket, (indices map reducers).toList, repair)

    case _ => None
  }

  private def findUniqueBuckets(bucket0: FreeMap): Option[FreeMap] =
    bucket0.resume match {
      case -\/(array @ ConcatArrays(_, _)) =>
        val bucket: FreeMap = rebuildArray(flattenArray(array).distinctE.toList)
        if (bucket0 ≟ bucket) None else bucket.some
     case _ => None
  }

  def uniqueBuckets = λ[QScriptCore ~> (Option ∘ QScriptCore)#λ] {
    case Reduce(src, bucket, reducers, repair) =>
      findUniqueBuckets(bucket).map(Reduce(src, _, reducers, repair))
    case Sort(src, bucket, order) =>
      findUniqueBuckets(bucket).map(Sort(src, _, order))
    case _ => None
  }

  // TODO: add reordering
  // - Filter can be moved ahead of Sort
  // - Subset can have a normalized order _if_ their counts are constant
  //   (maybe in some additional cases)

  // The order of optimizations is roughly this:
  // - elide NOPs
  // - read conversion given to us by the filesystem
  // - convert any remaning projects to maps
  // - coalesce nodes
  // - normalize mapfunc
  private def applyNormalizations[F[_]: Traverse: Normalizable, G[_]: Traverse](
    prism: PrismNT[G, F],
    rebase: FreeQS => T[G] => Option[T[G]])(
    implicit C: Coalesce.Aux[T, F, F],
             QC: QScriptCore :<: F,
             TJ: ThetaJoin :<: F,
             FI: Injectable.Aux[F, QScriptTotal]):
      F[T[G]] => G[T[G]] =
    repeatedly(Normalizable[F].normalizeF(_: F[T[G]])) ⋙
      liftFG(injectRepeatedly(elideNopJoin[F, T[G]](rebase))) ⋙
      liftFF(repeatedly(compactQC(_: QScriptCore[T[G]]))) ⋙
      liftFF(repeatedly(uniqueBuckets(_: QScriptCore[T[G]]))) ⋙
      repeatedly(C.coalesceQC[G](prism)) ⋙
      liftFG(injectRepeatedly(C.coalesceTJ[G](prism.get))) ⋙
      (fa => QC.prj(fa).fold(prism.reverseGet(fa))(elideNopQC[F, G](prism.reverseGet)))

  def normalizeCoEnv[F[_]: Traverse: Normalizable](
    implicit C:  Coalesce.Aux[T, F, F],
             QC: QScriptCore :<: F,
             TJ: ThetaJoin :<: F,
             FI: Injectable.Aux[F, QScriptTotal]):
      F[Free[F, Hole]] => CoEnv[Hole, F, Free[F, Hole]] =
    in => applyNormalizations[F, CoEnv[Hole, F, ?]](coenvPrism, rebaseTCo).apply(in ∘ (_.convertTo[T[CoEnv[Hole, F, ?]]])) ∘
      (_.convertTo[Free[F, Hole]])

  def normalize[F[_]: Traverse: Normalizable](
    implicit C:  Coalesce.Aux[T, F, F],
             QC: QScriptCore :<: F,
             TJ: ThetaJoin :<: F,
             FI: Injectable.Aux[F, QScriptTotal]):
      F[T[F]] => F[T[F]] =
    applyNormalizations[F, F](idPrism, rebaseT)

  /** Should only be applied after all other QScript transformations. This gives
    * the final, optimized QScript for conversion.
    */
  def optimize[F[_], G[_]: Functor](FtoG: F ~> G)(implicit QC: QScriptCore :<: F)
      : F[T[G]] => F[T[G]] =
    liftFF(repeatedly(swapMapSubset(FtoG)))

  /** A backend-or-mount-specific `f` is provided, that allows us to rewrite
    * [[Root]] (and projections, etc.) into [[Read]], so then we can handle
    * exposing only “true” joins and converting intra-data joins to map
    * operations.
    *
    * `f` takes QScript representing a _potential_ path to a file, converts
    * [[Root]] and its children to path, with the operations post-file remaining.
    */
  def pathify[M[_]: Monad: MonadFsErr, IN[_]: Traverse, OUT[_]: Traverse]
    (g: DiscoverPath.ListContents[M])
    (implicit
      FS: DiscoverPath.Aux[T, IN, OUT],
      R:  Const[Read[APath], ?] :<: OUT,
      QC:           QScriptCore :<: OUT,
      FI: Injectable.Aux[OUT, QScriptTotal])
      : T[IN] => M[T[OUT]] =
    _.cataM(FS.discoverPath[M](g)) >>= DiscoverPath.unionAll[T, M, OUT](g)
}
