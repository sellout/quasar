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

package quasar.physical.mongodbq

import quasar.Predef._
import quasar.jscore._
import quasar.fp._

import matryoshka._, Recursive.ops._, FunctorT.ops._
import scalaz._, Scalaz._

package object optimize {
  object pipeline {
    import quasar.physical.mongodbq.accumulator._
    import quasar.physical.mongodbq.expression._
    import Workflow._
    import IdHandling._

    private def deleteUnusedFields0(op: Workflow, usedRefs: Option[Set[DocVar]]):
        Workflow = {
      def getRefs[A](op: WorkflowF[Workflow], prev: Option[Set[DocVar]]):
          Option[Set[DocVar]] = op match {
        case $Group(_, _, _)            => Some(refs(op).toSet)
        // FIXME: Since we can’t reliably identify which fields are used by a
        //        JS function, we need to assume they all are, until we hit the
        //        next $Group or $Project.
        case $Map(_, _, _)             => None
        case $SimpleMap(_, _, _)       => None
        case $FlatMap(_, _, _)         => None
        case $Reduce(_, _, _)          => None
        case $Project(_, _, IncludeId) => Some(refs(op).toSet + IdVar)
        case $Project(_, _, _)         => Some(refs(op).toSet)
        case $FoldLeft(_, _)           => prev.map(_ + IdVar)
        case _                         => prev.map(_ ++ refs(op))
      }

      def unused(defs: Set[DocVar], refs: Set[DocVar]): Set[DocVar] =
        defs.filterNot(d => refs.exists(ref => d.startsWith(ref) || ref.startsWith(d)))

      def getDefs[A](op: WorkflowF[A]): Set[DocVar] = (op match {
        case p @ $Project(_, _, _)      => p.getAll.map(_._1)
        case g @ $Group(_, _, _)        => g.getAll.map(_._1)
        case s @ $SimpleMap(_, _, _)    => s.getAll.getOrElse(Nil)
        case _                          => Nil
      }).map(DocVar.ROOT(_)).toSet

      val pruned =
        usedRefs.fold(op.unFix) { usedRefs =>
          val unusedRefs =
            unused(getDefs(op.unFix), usedRefs).toList.flatMap(_.deref.toList)
          op.unFix match {
            case p @ $Project(_, _, _)      =>
              val p1 = p.deleteAll(unusedRefs)
              if (p1.shape.value.isEmpty) p1.src.unFix
              else p1
            case g @ $Group(_, _, _)        => g.deleteAll(unusedRefs.map(_.flatten.head))
            case s @ $SimpleMap(_, _, _)    => s.deleteAll(unusedRefs)
            case o                          => o
          }
        }

      Fix(pruned.map(deleteUnusedFields0(_, getRefs(pruned, usedRefs))))
    }

    def deleteUnusedFields(op: Workflow) = deleteUnusedFields0(op, None)

    /** Converts a \$group with fields that duplicate keys into a \$group
      * without those fields followed by a \$project that projects those fields
      * from the key.
      */
    val simplifyGroupƒ:
        WorkflowF[Workflow] => Option[WorkflowF[Workflow]] = {
      case $Group(src, Grouped(cont), id) =>
        val (newCont, proj) =
          cont.foldLeft[(ListMap[BsonField.Name, Accumulator], ListMap[BsonField.Name, Reshape.Shape])](
            (ListMap(), ListMap())) {
            case ((newCont, proj), (k, v)) =>
              lazy val default =
                (newCont + (k -> v), proj + (k -> $include().right))
              def maybeSimplify(loc: Option[DocVar]) =
                loc.fold(
                  default)(
                  l => v match {
                    case $addToSet(_) | $push(_) => default
                    case _ => (newCont, proj + (k -> $var(l).right))
                  })

              id.fold(
                re => maybeSimplify(re.find(v.copoint).map(f => DocField(IdName \ f))),
                expr =>
                  maybeSimplify(
                    if (v.copoint == expr) DocField(IdName).some
                    else None))
          }

        if (newCont == cont)
          None
        else
          $Project(
            Fix($Group(src, Grouped(newCont), id)),
            Reshape(proj),
            IgnoreId).some
      case _ => None
    }

    private val reorderOpsƒ: WorkflowF[Workflow] => Option[WorkflowF[Workflow]] = {
      def rewriteSelector(sel: Selector, defs: Map[DocVar, DocVar]): Option[Selector] =
        sel.mapUpFieldsM { f =>
          defs.toList.map {
            case (DocVar(_, Some(lhs)), DocVar(_, rhs)) =>
              if (f == lhs) rhs
              else (f relativeTo lhs).map(rel => rhs.fold(rel)(_ \ rel))
            case _ => None
          }.collectFirst { case Some(x) => x }
        }

      {
        case $Skip(Fix($Project(src0, shape, id)), count) =>
          $Project(Fix($Skip(src0, count)), shape, id).some
        case $Skip(Fix($SimpleMap(src0, fn @ NonEmptyList(MapExpr(_), INil()), scope)), count) =>
          $SimpleMap(Fix($Skip(src0, count)), fn, scope).some

        case $Limit(Fix($Project(src0, shape, id)), count) =>
          $Project(Fix($Limit(src0, count)), shape, id).some
        case $Limit(Fix($SimpleMap(src0, fn @ NonEmptyList(MapExpr(_), INil()), scope)), count) =>
          $SimpleMap(Fix($Limit(src0, count)), fn, scope).some

        case $Match(Fix(p @ $Project(src0, shape, id)), sel) =>
          val defs = p.getAll.collect {
            case (n, $var(x))    => DocField(n) -> x
            case (n, $include()) => DocField(n) -> DocField(n)
          }.toMap
          rewriteSelector(sel, defs).map(sel =>
            $Project(Fix($Match(src0, sel)), shape, id))

        case $Match(Fix($SimpleMap(src0, fn @ NonEmptyList(MapExpr(jsFn), INil()), scope)), sel) => {
          import quasar.javascript._
          def defs(expr: JsCore): Map[DocVar, DocVar] =
            expr.simplify match {
              case Obj(values) =>
                values.toList.collect {
                  case (n, Ident(jsFn.param)) => DocField(BsonField.Name(n.value)) -> DocVar.ROOT()
                  case (n, Access(Ident(jsFn.param), Literal(Js.Str(x)))) => DocField(BsonField.Name(n.value)) -> DocField(BsonField.Name(x))
                }.toMap
              case SpliceObjects(srcs) => srcs.map(defs).foldLeft(Map[DocVar, DocVar]())(_++_)
              case _ => Map.empty
            }
          rewriteSelector(sel, defs(jsFn.expr)).map(sel =>
            $SimpleMap(Fix($Match (src0, sel)), fn, scope))
        }

        case op => None
      }
    }

    def reorderOps(wf: Workflow): Workflow = {
      val reordered = wf.transCata(orOriginal(reorderOpsƒ))
      if (reordered == wf) wf else reorderOps(reordered)
    }

    def get0(leaves: List[BsonField.Name], rs: List[Reshape]): Option[Reshape.Shape] = {
      (leaves, rs) match {
        case (_, Nil) => $var(BsonField(leaves).map(DocVar.ROOT(_)).getOrElse(DocVar.ROOT())).right.some

        case (Nil, r :: rs) => inlineProject0(r, rs).map(_.left)

        case (l :: ls, r :: rs) => r.get(l).flatMap {
          case  -\/ (r)          => get0(ls, r :: rs)
          case   \/-($include()) => get0(leaves, rs)
          case   \/-($var(d))    => get0(d.path ++ ls, rs)
          case   \/-(e) => if (ls.isEmpty) fixExpr(e, rs).map(_.right) else none
        }
      }
    }

    private def fixExpr(e: Expression, rs: List[Reshape]):
        Option[Expression] =
      e.cataM[Option, Expression] {
        case $varF(ref) => get0(ref.path, rs).flatMap(_.toOption)
        case x          => Fix(x).some
      }

    private def inlineProject0(r: Reshape, rs: List[Reshape]): Option[Reshape] =
      inlineProject($Project((), r, IdHandling.IgnoreId), rs)

    def inlineProject[A](p: $Project[A], rs: List[Reshape]): Option[Reshape] = {
      val map = p.getAll.map { case (k, v) =>
        k -> (v match {
          case $include() =>
            get0(k.flatten.toList, rs).map(_.right).orElse(
              if (k == IdName) ().left.some
              else none)
          case $var(d)    => get0(d.path, rs).map(_.right)
          case _          => fixExpr(v, rs).map(_.right.right)
        })
      }.foldLeftM[Option, ListMap[BsonField, Reshape.Shape]](ListMap()) {
        case (acc, (k, Some(\/-(v)))) => (acc + (k -> v)).some
        case (acc, (_, Some(-\/(_)))) => acc.some
        case (_,   (_, None))         => none
      }

      map.map(p.empty.setAll(_).shape)
    }

    /** Map from old grouped names to new names and mapping of expressions. */
    def renameProjectGroup(r: Reshape, g: Grouped): Option[ListMap[BsonField.Name, List[BsonField.Name]]] = {
      val s = r.value.toList.traverse {
        case (newName, \/-($var(v))) =>
          v.path match {
            case List(oldHead @ BsonField.Name(_)) =>
              g.value.get(oldHead).map { κ(oldHead -> newName) }
            case _ => None
          }
        case _ => None
      }

      def multiListMap[A, B](ts: List[(A, B)]): ListMap[A, List[B]] =
        ts.foldLeft(ListMap[A,List[B]]()) { case (map, (a, b)) => map + (a -> (map.get(a).getOrElse(List[B]()) :+ b)) }

      s.map(multiListMap)
    }

    def inlineProjectGroup(r: Reshape, g: Grouped): Option[Grouped] = {
      for {
        names   <- renameProjectGroup(r, g)
        values1 = names.flatMap {
          case (oldName, ts) => ts.map((_: BsonField.Name) -> g.value(oldName))
        }
      } yield Grouped(values1)
    }

    def inlineProjectUnwindGroup(r: Reshape, unwound: DocVar, g: Grouped): Option[(DocVar, Grouped)] = {
      for {
        names    <- renameProjectGroup(r, g)
        unwound1 <- unwound.path match {
          case (name @ BsonField.Name(_)) :: Nil => names.get(name) match {
            case Some(n :: Nil) => Some(DocField(n))
            case _ => None
          }
          case _ => None
        }
        values1 = names.flatMap {
          case (oldName, ts) => ts.map((_: BsonField.Name) -> g.value(oldName))
        }
      } yield unwound1 -> Grouped(values1)
    }

    def inlineGroupProjects(g: $Group[Workflow]):
        Option[(Workflow, Grouped, Reshape.Shape)] = {
      val (rs, src) = g.src.para(collectShapes)

      if (src == g.src) None
      else {
        val grouped = ListMap(g.getAll: _*).traverse(_.traverse(fixExpr(_, rs)))

        val by = g.by.fold(
          inlineProject0(_, rs).map(_.left),
          fixExpr(_, rs).map(\/-(_)))
        (grouped |@| by)((grouped, by) => (src, Grouped(grouped), by))
      }
    }
  }
}
