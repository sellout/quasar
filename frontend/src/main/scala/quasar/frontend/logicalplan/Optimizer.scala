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

package quasar.frontend.logicalplan

import quasar.Predef._
import quasar._
import quasar.contrib.shapeless._
import quasar.fp._
import quasar.fp.binder._
import quasar.fp.ski._
import quasar.frontend.logicalplan.{LogicalPlan => LP}
import quasar.namegen._
import quasar.sql.JoinDir

import scala.Predef.$conforms

import matryoshka._
import matryoshka.implicits._
import scalaz._, Scalaz.{ToIdOps => _, _}
import shapeless.{Data => _, :: => _, _}

sealed abstract class Component[T, A] {
  def run(l: T, r: T): A = this match {
    case EquiCond(run0)  => run0(l, r)
    case LeftCond(run0)  => run0(l)
    case RightCond(run0) => run0(r)
    case OtherCond(run0) => run0(l, r)
    case NeitherCond(a)  => a
  }
}

/** A condition that refers to left and right sources using equality, so may be
  * rewritten into the join condition.
  */
final case class EquiCond[T, A](run0: (T, T) => A) extends Component[T, A]
/** A condition which refers only to the left source */
final case class LeftCond[T, A](run0: T => A) extends Component[T, A]
/** A condition which refers only to the right source. */
final case class RightCond[T, A](run0: T => A) extends Component[T, A]
/** A condition which refers to both sources but doesn't have the right shape to
  * become the join condition.
  */
final case class OtherCond[T, A](run0: (T, T) => A) extends Component[T, A]
/** An expression that doesn't refer to any source. */
final case class NeitherCond[T, A](run0: A) extends Component[T, A]

object Component {
  implicit def applicative[T]: Applicative[Component[T, ?]] =
    new Applicative[Component[T, ?]] {
      def point[A](a: => A) = NeitherCond(a)

      def ap[A, B](fa: => Component[T, A])(f: => Component[T, A => B]) =
        (fa, f) match {
          // A             // A => B
          case (NeitherCond(a), NeitherCond(g)) => NeitherCond(g(a))

          // A             // LP => A => B
          case (NeitherCond(a), LeftCond(g))    => LeftCond(g(_)(a))
          // A             // LP => A => B
          case (NeitherCond(a), RightCond(g))   => RightCond(g(_)(a))

          // LP => A       // A => B
          case (LeftCond(a),    NeitherCond(g)) => LeftCond(g <<< a) // lp => g(a(lp))
                                                                     // LP => A       // LP => A => B
          case (LeftCond(a),    LeftCond(g))    => LeftCond(lp => g(lp)(a(lp)))

          // LP => A       // A => B
          case (RightCond(a),   NeitherCond(g)) => RightCond(g <<< a)
          // LP => A       // LP => A => B
          case (RightCond(a),   RightCond(g))   => RightCond(lp => g(lp)(a(lp)))

          case (ca, cg)                         => OtherCond((l, r) => cg.run(l, r)(ca.run(l, r)))
        }
    }
}

final class Optimizer[T: Equal]
  (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP]) {
  import quasar.std.StdLib._
  import set._
  import structural._

  val lpr = new LogicalPlanR[T]

  private def countUsageƒ(target: Symbol): Algebra[LP, Int] = {
    case Free(symbol) if symbol == target => 1
    case Let(ident, form, _) if ident == target => form
    case x => x.fold
  }

  private def inlineƒ[A](target: Symbol, repl: LP[T]):
      LP[(T, T)] => LP[T] =
  {
    case Free(symbol) if symbol == target => repl
    case Let(ident, form, body) if ident == target =>
      Let(ident, form._2, body._1)
    case x => x.map(_._2)
  }

  val simplifyƒ: LP[T] => Option[LP[T]] = {
    case inv @ Invoke(func, _) => func.simplify(inv)
    case Let(ident, form, in) => form.project match {
      case Constant(_) | Free(_) =>
        in.transPara[T](inlineƒ(ident, form.project)).project.some
      case _ => in.cata(countUsageƒ(ident)) match {
        case 0 => in.project.some
        case 1 => in.transPara[T](inlineƒ(ident, form.project)).project.some
        case _ => None
      }
    }
    case _ => None
  }

  def simplify(t: T): T = t.transCata[T](repeatedly(simplifyƒ))

  /** Like `simplifyƒ`, but eliminates _all_ `Let` (and any bound `Free`) nodes.
    */
  val elideLets:
      LP[T] => Option[LP[T]] = {
    case Let(ident, form, in) =>
      in.transPara[T](inlineƒ(ident, form.project)).project.some
    case _ => None
  }

  val namesƒ: Algebra[LP, Set[Symbol]] = {
    case Free(name) => Set(name)
    case x          => x.fold
  }

  def uniqueName[F[_]: Functor: Foldable](
    prefix: String, plans: F[T]):
      Symbol = {
    val existingNames = plans.map(_.cata(namesƒ)).fold
    def loop(pre: String): Symbol =
      if (existingNames.contains(Symbol(prefix)))
        loop(pre + "_")
      else Symbol(prefix)

    loop(prefix)
  }

  val shapeƒ: GAlgebra[(T, ?), LP, Option[List[T]]] = {
    case Let(_, _, body) => body._2
    case Constant(Data.Obj(map)) =>
      Some(map.keys.map(n => lpr.constant(Data.Str(n))).toList)
    case Sort(src, _) => src._2
    case InvokeUnapply(DeleteField, Sized(src, field)) =>
      src._2.map(_.filterNot(_ == field._1))
    case InvokeUnapply(MakeObject, Sized(field, _)) => Some(List(field._1))
    case InvokeUnapply(ObjectConcat, srcs) => srcs.traverse(_._2).map(_.flatten)
    // NB: the remaining Invoke cases simply pass through or combine shapes
    //     from their inputs. It would be great if this information could be
    //     handled generically by the type system.
    case InvokeUnapply(Take, Sized(src, _)) => src._2
    case InvokeUnapply(Drop, Sized(src, _)) => src._2
    case InvokeUnapply(Filter, Sized(src, _)) => src._2
    case InvokeUnapply(InnerJoin | LeftOuterJoin | RightOuterJoin | FullOuterJoin, _) =>
      Some(List(JoinDir.Left.const, JoinDir.Right.const))
    case InvokeUnapply(GroupBy, Sized(src, _)) => src._2
    case InvokeUnapply(identity.Squash, Sized(src)) => src._2
    case _ => None
  }

  private def preserveFree0[A](x: (T, A))(f: A => T)
      : T =
    x._1.project match {
      case Free(_) => x._1
      case _       => f(x._2)
    }

  // TODO: implement `preferDeletions` for other backends that may have more
  //       efficient deletes. Even better, a single function that takes a
  //       function parameter deciding which way each case should be converted.
  private val preferProjectionsƒ:
      GAlgebra[
        (T, ?),
        LP,
        (T, Option[List[T]])] = { node =>

    def preserveFree(x: (T, (T, Option[List[T]]))) =
      preserveFree0(x)(_._1)

    (node match {
      case InvokeUnapply(DeleteField, Sized(src, field)) =>
        src._2._2.fold(
          DeleteField(preserveFree(src), preserveFree(field)).embed) {
          fields =>
            val name = uniqueName("src", fields)
              lpr.let(name, preserveFree(src),
                MakeObjectN(fields.filterNot(_ == field._2._1).map(f =>
                  f -> ObjectProject(lpr.free(name), f).embed): _*).embed)
        }
      case lp => lp.map(preserveFree).embed
    },
      shapeƒ(node.map(_._2)))
  }

  def preferProjections(t: T): T =
    boundPara(t)(preferProjectionsƒ)._1.transCata[T](repeatedly(simplifyƒ))

  // FIXME: Make this a transformation instead of an algebra.
  val elideTypeCheckƒ: Algebra[LP, T] = {
    case Typecheck(_, _, cont, _) => cont
    case x => x.embed
  }

  /** Rewrite joins and subsequent filtering so that:
    * 1) Filtering that is equivalent to an equi-join is rewritten into the join condition.
    * 2) Filtering that refers to only side of the join is hoisted prior to the join.
    * The input plan must have been simplified already so that the structure
    * is in a canonical form for inspection.
    *
    * TODO: Separate the combining of filter and join from ...
    */
  val rewriteCrossJoinsƒ: LP[(T, T)] => State[NameGen, T] = { node =>
    def preserveFree(x: (T, T)) = preserveFree0(x)(ι)

    def flattenAnd: T => List[T] = {
      case Embed(InvokeUnapply(relations.And, ts)) => ts.unsized.flatMap(flattenAnd)
      case t                                       => List(t)
    }

    def toComp(left: T, right: T)(c: T):
        (List[Component[T, T]], Component[T, T]) =
      boundParaM[T, (List[Component[T, T]], ?), LP, Component[T, T]](c) {
        case t if t.map(_._1) ≟ left.project  => (Nil, LeftCond(ι))
        case t if t.map(_._1) ≟ right.project => (Nil, RightCond(ι))

        case InvokeUnapply(relations.Eq, Sized((_, LeftCond(lc)), (_, RightCond(rc)))) =>
          (Nil, EquiCond((l, r) => relations.Eq(lc(l), rc(r)).embed))

        case InvokeUnapply(relations.Eq, Sized((_, RightCond(rc)), (_, LeftCond(lc)))) =>
          (Nil, EquiCond((l, r) => relations.Eq(rc(r), lc(l)).embed))

        case Typecheck((_, LeftCond(lc)), tpe, (_, cont), (Embed(Constant(Data.NA)), _)) =>
          (List(LeftCond(l =>
            Typecheck(lc(l), tpe, Constant[T](Data.Bool(true)).embed, Constant[T](Data.Bool(false)).embed).embed)), cont)

        case Typecheck((_, RightCond(rc)), tpe, (_, cont), (Embed(Constant(Data.NA)), _)) =>
          (List(RightCond(r =>
            Typecheck(rc(r), tpe, Constant[T](Data.Bool(true)).embed, Constant[T](Data.Bool(false)).embed).embed)), cont)

        case Typecheck((_, cond), tpe, (_, cont), (_, fallback)) =>
          (Nil, (cond |@| cont |@| fallback)(lpr.typecheck(_, tpe, _, _)))

        case InvokeUnapply(func @ UnaryFunc(_, _, _, _, _, _, _), Sized(t1)) =>
          (Nil, Func.Input1(t1).traverse(_._2).map(lpr.invoke(func, _)))

        // Preserve the previously-computed components in the `And`.
        // Return a constant `true` which is included as a no-op filter post-join.
        case t @ InvokeUnapply(func @ BinaryFunc(_, _, _, _, _, _, _), Sized(t1, t2)) if func == relations.And =>
          (List(t1._2, t2._2), NeitherCond(lpr.constant(Data.Bool(true))))

        case InvokeUnapply(func @ BinaryFunc(_, _, _, _, _, _, _), Sized(t1, t2)) =>
          (Nil, Func.Input2(t1, t2).traverse(_._2).map(lpr.invoke(func, _)))

        case InvokeUnapply(func @ TernaryFunc(_, _, _, _, _, _, _), Sized(t1, t2, t3)) =>
          (Nil, Func.Input3(t1, t2, t3).traverse(_._2).map(lpr.invoke(func, _)))

        case Let(ident, form, body) =>
          (Nil, (form._2 ⊛ body._2)(lpr.let(ident, _, _)))

        case t =>
          (Nil, NeitherCond(t.map(_._1).embed))
    }

    def assembleCond(conds: List[T]): T =
      conds.foldLeft(lpr.constant(Data.True))(relations.And(_, _).embed)

    def newJoin(lSrc: T, rSrc: T, comps: List[Component[T, T]])
        : State[NameGen, T] = {
      val equis    = comps.collect { case c @ EquiCond(_) => c }
      val lefts    = comps.collect { case c @ LeftCond(_) => c }
      val rights   = comps.collect { case c @ RightCond(_) => c }
      val others   = comps.collect { case c @ OtherCond(_) => c }
      val neithers = comps.collect { case c @ NeitherCond(_) => c }

      for {
        lName  <- freshName("leftSrc")
        lFName <- freshName("left")
        rName  <- freshName("rightSrc")
        rFName <- freshName("right")
        jName  <- freshName("joined")
      } yield {
        // NB: simplifying eagerly to make matching easier up the tree
        simplify(
          lpr.let(lName, lSrc,
            lpr.let(lFName,
              Filter(lpr.free(lName), assembleCond(lefts.map(_.run0(lpr.free(lName))))).embed,
              lpr.let(rName, rSrc,
                lpr.let(rFName,
                  Filter(lpr.free(rName), assembleCond(rights.map(_.run0(lpr.free(rName))))).embed,
                  lpr.let(jName,
                    InnerJoin(lpr.free(lFName), lpr.free(rFName),
                      assembleCond(equis.map(_.run(lpr.free(lFName), lpr.free(rFName))))).embed,
                    Filter(lpr.free(jName), assembleCond(
                      others.map(_.run0(JoinDir.Left.projectFrom(lpr.free(jName)), JoinDir.Right.projectFrom(lpr.free(jName)))) ++
                      neithers.map(_.run0))).embed))))))
      }
    }


    node match {
      case InvokeUnapply(Filter, Sized((src, Embed(InvokeUnapply(InnerJoin, Sized(joinL, joinR, joinCond0)))), (cond0, _))) =>
        val joinCond = joinCond0.transCata[T](orOriginal(elideLets)) // TODO unduplicate
        val cond = cond0.transCata[T](orOriginal(elideLets))
        val comps =
          flattenAnd(joinCond).traverse(toComp(joinL, joinR)).bifoldMap(ι)(ι) ++
          flattenAnd(cond).traverse(toComp(
            JoinDir.Left.projectFrom(src),
            JoinDir.Right.projectFrom(src))).bifoldMap(ι)(ι)
        newJoin(joinL, joinR, comps)
      case InvokeUnapply(InnerJoin, Sized((srcL, _), (srcR, _), (_, joinCond0))) =>
        val joinCond = joinCond0.transCata[T](orOriginal(elideLets))
        newJoin(srcL, srcR, flattenAnd(joinCond).traverse(toComp(srcL, srcR)(_)).bifoldMap(ι)(ι))
      case _ => State.state(node.map(preserveFree).embed)
    }
  }

  /** Apply universal, type-oblivious transformations intended to
    * improve the performance of a query regardless of the backend. The
    * input is expected to come straight from the SQL^2 compiler or
    * another source of un-optimized queries.
    */
  val optimize: T => T =
    NonEmptyList[T => T](
      // Eliminate extraneous constants, etc.:
      simplify,

      // NB: must precede normalizeLets to eliminate possibility of shadowing:
      lpr.normalizeTempNames,

      // NB: must precede rewriteCrossJoins to normalize Filter/Join shapes:
      lpr.normalizeLets,

      // Now for the big one:
      boundParaS(_)(rewriteCrossJoinsƒ).evalZero,

      // Eliminate trivial bindings introduced in rewriteCrossJoins:
      simplify,

      // Final pass to normalize the resulting plans for better matching in tests:
      lpr.normalizeLets,

      // This time, fix the names last so they will read naturally:
      lpr.normalizeTempNames

    ).foldLeft1(_ >>> _)

  /** In `rewriteJoins` we maintain the association and nested level of let bindings
    * by not calling `normalizeLets` (as we do in `optimize`). We would like `rewriteJoins`
    * to be identical to `optimize`, but can only begin to address this after old mongo
    * is deleted.
    */
  val rewriteJoins: T => T =
    NonEmptyList[T => T](
      // Eliminate extraneous constants, etc.:
      simplify,

      // NB: must precede normalizeLets to eliminate possibility of shadowing:
      lpr.normalizeTempNames,

      // Now for the big one:
      boundParaS(_)(rewriteCrossJoinsƒ).evalZero,

      // Eliminate trivial bindings introduced in rewriteCrossJoins:
      simplify,

      // This time, fix the names last so they will read naturally:
      lpr.normalizeTempNames

    ).foldLeft1(_ >>> _)
}
