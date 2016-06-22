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

package quasar

import quasar.Predef._
import quasar.RenderTree.ops._
import quasar.fs.prettyPrint
import quasar.sql._

import scala.AnyRef

import matryoshka._, Recursive.ops._, FunctorT.ops._
import monocle._
import pathy.Path, Path._
import scalaz._, Scalaz._, Validation.{success, failure}
import shapeless.{Prism => _, _}
import shapeless.contrib.scalaz._

sealed trait SemanticError {
  def message: String
}

object SemanticError {
  implicit val SemanticErrorShow: Show[SemanticError] = Show.shows(_.message)

  final case class GenericError(message: String) extends SemanticError

  final case class DomainError(data: Data, hint: Option[String]) extends SemanticError {
    def message = "The data '" + data + "' did not fall within its expected domain" + hint.map(": " + _)
  }

  final case class FunctionNotFound(name: String) extends SemanticError {
    def message = "The function '" + name + "' could not be found in the standard library"
  }
  final case class TypeError(expected: Type, actual: Type, hint: Option[String]) extends SemanticError {
    def message = "Expected type " + expected + " but found " + actual + hint.map(": " + _).getOrElse("")
  }
  final case class VariableParseError(vari: VarName, value: VarValue, cause: quasar.sql.ParsingError) extends SemanticError {
    def message = "The variable " + vari + " should contain a SQL expression but was `" + value.value + "` (" + cause.message + ")"
  }
  final case class UnboundVariable(vari: VarName) extends SemanticError {
    def message = "There is no binding for the variable " + vari
  }
  final case class DuplicateRelationName(defined: String) extends SemanticError {
    def message = "Found relation with duplicate name '" + defined + "'"
  }
  final case class NoTableDefined(node: Fix[Sql]) extends SemanticError {
    def message = "No table was defined in the scope of \'" + pprint(node) + "\'"
  }
  final case class MissingField(name: String) extends SemanticError {
    def message = "No field named '" + name + "' exists"
  }
  final case class DuplicateAlias(name: String) extends SemanticError {
    def message = s"Alias `$name` appears twice in projections"
  }
  final case class MissingIndex(index: Int) extends SemanticError {
    def message = "No element exists at array index '" + index
  }
  final case class WrongArgumentCount(func: String, expected: Int, actual: Int) extends SemanticError {
    def message = "Wrong number of arguments for function '" + func + "': expected " + expected + " but found " + actual
  }
  final case class InvalidStringCoercion(str: String, expected: String \/ List[String]) extends SemanticError {
    def message =
      "Expected " +
        expected.fold("“" + _ + "”", "one of " + _.mkString("“", "”", ", ")) +
        " but found “" + str + "”"
  }
  final case class AmbiguousReference(node: Fix[Sql], relations: List[SqlRelation[Unit]])
      extends SemanticError {
    def message = "The expression '" + pprint(node) + "' is ambiguous and might refer to any of the tables " + relations.mkString(", ")
  }
  final case object CompiledTableMissing extends SemanticError {
    def message = "Expected the root table to be compiled but found nothing"
  }
  final case class CompiledSubtableMissing(name: String) extends SemanticError {
    def message = "Expected to find a compiled subtable with name \"" + name + "\""
  }
  final case class DateFormatError[N <: Nat](func: GenericFunc[N], str: String, hint: Option[String]) extends SemanticError {
    def message = "Date/time string could not be parsed as " + func.name + ": " + str + hint.map(" (" + _ + ")").getOrElse("")
  }
  final case class InvalidPathError(path: Path[_, File, _], hint: Option[String]) extends SemanticError {
    def message = "Invalid path: " + posixCodec.unsafePrintPath(path) + hint.map(" (" + _ + ")").getOrElse("")
  }

  // TODO: Add other prisms when necessary (unless we enable the "No Any" wart first)
  val genericError: Prism[SemanticError, String] =
    Prism[SemanticError, String] {
      case GenericError(msg) => Some(msg)
      case _ => None
    } (GenericError(_))
}

trait SemanticAnalysis {
  import SemanticError._

  type Failure = NonEmptyList[SemanticError]

  private def fail[A](e: SemanticError) = Validation.failure[Failure, A](NonEmptyList(e))
  private def succeed[A](s: A) = Validation.success[Failure, A](s)

  sealed trait Synthetic
  object Synthetic {
    final case object SortKey extends Synthetic

    implicit val SyntheticRenderTree: RenderTree[Synthetic] =
      RenderTree.fromToString[Synthetic]("Synthetic")
  }

  private val syntheticPrefix = "__sd__"

  /** Inserts synthetic fields into the projections of each `select` stmt to
    * hold the values that will be used in sorting, and annotates each new
    * projection with Synthetic.SortKey. The compiler will generate a step to
    * remove these fields after the sort operation.
    */
  def projectSortKeysƒ[T[_[_]]: Recursive: Corecursive]:
      Sql[T[Sql]] => Option[Sql[T[Sql]]] = {
    case Select(d, projections, r, f, g, Some(sql.OrderBy(keys))) => {
      def matches(key: T[Sql]): PartialFunction[Proj[T[Sql]], T[Sql]] =
        key.project match {
          case Ident(keyName) => {
            case Proj(_, Some(alias))               if keyName == alias    => key
            case Proj(Embed(Ident(projName)), None) if keyName == projName => key
            case Proj(Embed(Splice(_)), _)                                 => key
          }
          case _ => {
            case Proj(expr2, Some(alias)) if key == expr2 => ident[T[Sql]](alias).embed
          }
        }

      // NB: order of the keys has to be preserved, so this complex fold
      //     seems to be the best way.
      type Target = (List[Proj[T[Sql]]], List[(OrderType, T[Sql])], Int)

      val (projs2, keys2, _) = keys.foldRight[Target]((Nil, Nil, 0)) {
        case ((orderType, expr), (projs, keys, index)) =>
          projections.collectFirst(matches(expr)).fold {
            val name  = syntheticPrefix + index.toString()
            val proj2 = Proj(expr, Some(name))
            val key2  = (orderType, ident[T[Sql]](name).embed)
            (proj2 :: projs, key2 :: keys, index + 1)
          } (
            kExpr => (projs, (orderType, kExpr) :: keys, index))
      }
      select(d, projections ⊹ projs2, r, f, g, sql.OrderBy(keys2).some).some
    }
    case _ => None
  }

  private val identifySyntheticsƒ: Algebra[Sql, List[Option[Synthetic]]] = {
    case Select(_, projections, _, _, _, _) =>
      projections.map(_.alias match {
        case Some(name) if name.startsWith(syntheticPrefix) =>
          Some(Synthetic.SortKey)
        case _ => None
      })
    case _ => Nil
  }

  case class BindingScope(scope: Map[String, SqlRelation[Unit]])

  implicit val ShowBindingScope: Show[BindingScope] = new Show[BindingScope] {
    override def show(v: BindingScope) = v.scope.toString
  }

  case class TableScope(scope: Map[String, SqlRelation[Unit]])

  implicit def ShowTableScope: Show[TableScope] =
    new Show[TableScope] {
      override def show(v: TableScope) = v.scope.toString
    }

  case class Scope(tableScope: TableScope, bindingScope: BindingScope)

  import Validation.FlatMap._

  type ValidSem[A] = ValidationNel[SemanticError, A]

  /** This analysis identifies all the named tables within scope at each node in
    * the tree. If two tables are given the same name within the same scope,
    * then because this leads to an ambiguity, an error is produced containing
    * details on the duplicate name.
    */
  def scopeTablesƒ[T[_[_]]: Recursive]:
      CoalgebraM[ValidSem, Sql, (Scope, T[Sql])] = {
    case (Scope(ts, bs), Embed(expr)) => expr match {
      case Select(_, _, relations, _, _, _) =>
        def findRelations(r: SqlRelation[T[Sql]]): ValidSem[Map[String, SqlRelation[Unit]]] =
          r match {
            case IdentRelationAST(name, aliasOpt) =>
              success(Map(aliasOpt.getOrElse(name) ->
                IdentRelationAST(name, aliasOpt)))
            case TableRelationAST(file, aliasOpt) =>
              success(Map(aliasOpt.getOrElse(prettyPrint(file)) -> TableRelationAST(file, aliasOpt)))
            case ExprRelationAST(_, alias) =>
              success(Map(alias -> ExprRelationAST((), alias)))
            case JoinRelation(l, r, _, _) => for {
              rels <- findRelations(l) tuple findRelations(r)
              (left, right) = rels
              rez <- (left.keySet intersect right.keySet).toList.toNel.cata(
                nel => failure(nel.map(DuplicateRelationName(_):SemanticError)),
                success(left ++ right))
            } yield rez
          }

        relations.fold[ValidSem[Map[String, SqlRelation[Unit]]]](
          success(Map[String, SqlRelation[Unit]]()))(
          findRelations)
          .map(m => expr.map((Scope(TableScope(m), bs), _)))

      case Let(name, form, body) => {
        val bs2: BindingScope =
          BindingScope(bs.scope ++ Map(name -> ExprRelationAST((), name)))

        success(Let(name, (Scope(ts, bs), form), (Scope(ts, bs2), body)))
      }

      case x => success(x.map((Scope(ts, bs), _)))
    }
  }

  sealed trait Provenance {
    import Provenance._

    def & (that: Provenance): Provenance = Both(this, that)

    def | (that: Provenance): Provenance = Either(this, that)

    def simplify: Provenance = this match {
      case x : Either => anyOf(x.flatten.map(_.simplify).filterNot(_ == Empty))
      case x : Both => allOf(x.flatten.map(_.simplify).filterNot(_ == Empty))
      case _ => this
    }

    def namedRelations: Map[String, List[NamedRelation[Unit]]] =
      relations.foldMap(_.namedRelations)

    def relations: List[SqlRelation[Unit]] = this match {
      case Empty => Nil
      case Value => Nil
      case Relation(value) => value :: Nil
      case Either(v1, v2) => v1.relations ++ v2.relations
      case Both(v1, v2) => v1.relations ++ v2.relations
    }

    def flatten: Set[Provenance] = Set(this)

    override def equals(that: scala.Any): Boolean = (this, that) match {
      case (x, y) if (x.eq(y.asInstanceOf[AnyRef])) => true
      case (Relation(v1), Relation(v2)) => v1 == v2
      case (Either(_, _), that @ Either(_, _)) => this.simplify.flatten == that.simplify.flatten
      case (Both(_, _), that @ Both(_, _)) => this.simplify.flatten == that.simplify.flatten
      case (_, _) => false
    }

    override def hashCode = this match {
      case Either(_, _) => this.simplify.flatten.hashCode
      case Both(_, _) => this.simplify.flatten.hashCode
      case _ => super.hashCode
    }
  }
  trait ProvenanceInstances {
    implicit val ProvenanceRenderTree: RenderTree[Provenance] =
      new RenderTree[Provenance] { self =>
        import Provenance._

        def render(v: Provenance) = {
          val ProvenanceNodeType = List("Provenance")

          def nest(l: RenderedTree, r: RenderedTree, sep: String) = (l, r) match {
            case (RenderedTree(_, ll, Nil), RenderedTree(_, rl, Nil)) =>
              Terminal(ProvenanceNodeType, Some("(" + ll + " " + sep + " " + rl + ")"))
            case _ => NonTerminal(ProvenanceNodeType, Some(sep), l :: r :: Nil)
          }

          v match {
            case Empty               => Terminal(ProvenanceNodeType, Some("Empty"))
            case Value               => Terminal(ProvenanceNodeType, Some("Value"))
            case Relation(value)     => value.render.copy(nodeType = ProvenanceNodeType)
            case Either(left, right) => nest(self.render(left), self.render(right), "|")
            case Both(left, right)   => nest(self.render(left), self.render(right), "&")
        }
      }
    }

    implicit val ProvenanceOrMonoid: Monoid[Provenance] =
      new Monoid[Provenance] {
        import Provenance._

        def zero = Empty

        def append(v1: Provenance, v2: => Provenance) = (v1, v2) match {
          case (Empty, that) => that
          case (this0, Empty) => this0
          case _ => v1 | v2
        }
      }

    implicit val ProvenanceAndMonoid: Monoid[Provenance] =
      new Monoid[Provenance] {
        import Provenance._

        def zero = Empty

        def append(v1: Provenance, v2: => Provenance) = (v1, v2) match {
          case (Empty, that) => that
          case (this0, Empty) => this0
          case _ => v1 & v2
        }
      }
  }
  object Provenance extends ProvenanceInstances {
    case object Empty extends Provenance
    case object Value extends Provenance
    case class Relation(value: SqlRelation[Unit]) extends Provenance
    case class Either(left: Provenance, right: Provenance) extends Provenance {
      override def flatten: Set[Provenance] = {
        def flatten0(x: Provenance): Set[Provenance] = x match {
          case Either(left, right) => flatten0(left) ++ flatten0(right)
          case _ => Set(x)
        }
        flatten0(this)
      }
    }
    case class Both(left: Provenance, right: Provenance) extends Provenance {
      override def flatten: Set[Provenance] = {
        def flatten0(x: Provenance): Set[Provenance] = x match {
          case Both(left, right) => flatten0(left) ++ flatten0(right)
          case _ => Set(x)
        }
        flatten0(this)
      }
    }

    def allOf[F[_]: Foldable](xs: F[Provenance]): Provenance =
      xs.concatenate(ProvenanceAndMonoid)

    def anyOf[F[_]: Foldable](xs: F[Provenance]): Provenance =
      xs.concatenate(ProvenanceOrMonoid)
  }

  /** This phase infers the provenance of every expression, issuing errors
    * if identifiers are used with unknown provenance. The phase requires
    * TableScope and BindingScope annotations on the tree.
    */
  def inferProvenanceƒ[T[_[_]]: Corecursive]:
      ElgotAlgebraM[(Scope, ?), ValidSem, Sql, Provenance] = {
    case (Scope(ts, bs), expr) => expr match {
      case Select(_, projections, _, _, _, _) =>
        success(Provenance.allOf(projections.map(_.expr)))

      case SetLiteral(_)  => success(Provenance.Value)
      case ArrayLiteral(_) => success(Provenance.Value)
      case MapLiteral(_) => success(Provenance.Value)
      case Splice(expr)       => success(expr.getOrElse(Provenance.Empty))
      case Vari(_)        => success(Provenance.Value)
      case Binop(left, right, _) => success(left & right)
      case Unop(expr, _) => success(expr)
      case Ident(name) =>
        val scope = bs.scope.get(name) match {
          case None => ts.scope.get(name)
          case s => s
        }
        scope.fold(
          Provenance.anyOf[Map[String, ?]]((ts.scope ++ bs.scope) ∘ (Provenance.Relation(_))) match {
            case Provenance.Empty => fail(NoTableDefined(Ident[Fix[Sql]](name).embed))
            case x                => success(x)
          })(
          (Provenance.Relation(_)) ⋙ success)
      case InvokeFunction(_, args) => success(Provenance.allOf(args))
      case Match(_, cases, _)      =>
        success(cases.map(_.expr).concatenate(Provenance.ProvenanceAndMonoid))
      case Switch(cases, _)        =>
        success(cases.map(_.expr).concatenate(Provenance.ProvenanceAndMonoid))
      case Let(_, form, body)      => success(form & body)
      case IntLiteral(_)           => success(Provenance.Value)
      case FloatLiteral(_)         => success(Provenance.Value)
      case StringLiteral(_)        => success(Provenance.Value)
      case BoolLiteral(_)          => success(Provenance.Value)
      case NullLiteral()           => success(Provenance.Value)
    }
  }

  type Annotations = (List[Option[Synthetic]], Provenance)

  val synthElgotMƒ:
      ElgotAlgebraM[(Scope, ?), ValidSem, Sql, List[Option[Synthetic]]] =
    identifySyntheticsƒ.generalizeElgot[(Scope, ?)] ⋙ (_.point[ValidSem])

  def addAnnotations[T[_[_]]: Corecursive]:
      ElgotAlgebraM[
        ((Scope, T[Sql]), ?),
        NonEmptyList[SemanticError] \/ ?,
        Sql,
        Cofree[Sql, Annotations]] =
    e => attributeElgotM[(Scope, ?), ValidSem][Sql, Annotations](
      ElgotAlgebraMZip[(Scope, ?), ValidSem, Sql].zip(
        synthElgotMƒ,
        inferProvenanceƒ)).apply(e.leftMap(_._1)).disjunction

  // NB: projectSortKeys ⋙ (identifySynthetics &&& (scopeTables ⋙ inferProvenance))
  def AllPhases[T[_[_]]: Recursive: Corecursive](expr: T[Sql]) =
    (Scope(TableScope(Map()), BindingScope(Map())), expr.transCata(orOriginal(projectSortKeysƒ)))
      .coelgotM(addAnnotations, scopeTablesƒ.apply(_).disjunction)
}

object SemanticAnalysis extends SemanticAnalysis
